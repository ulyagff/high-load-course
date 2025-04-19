package ru.quipy.payments.logic

import java.util.concurrent.TimeUnit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import liquibase.pro.packaged.e
import okhttp3.*
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.RateLimiter
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests
    private val retryCount = 1

    private val rateLimiter: RateLimiter =
        SlidingWindowRateLimiter(rateLimitPerSec.toLong(), Duration.ofSeconds(1))

private val client =
    OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .callTimeout(Duration.ofSeconds(requestAverageProcessingTime.seconds * 2))
        .connectionPool(
            ConnectionPool(
                maxIdleConnections = parallelRequests + 500,
                keepAliveDuration = 10,
                timeUnit = TimeUnit.MINUTES
            )
        )
        .dispatcher(
            Dispatcher().apply {
                maxRequests = parallelRequests
                maxRequestsPerHost = parallelRequests
            }
        )
       .build()

override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
    logger.warn("[$accountName] Submitting payment request for payment $paymentId")

    val transactionId = UUID.randomUUID()
    logger.info("[$accountName] Submit for $paymentId , txId: $transactionId")

    // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
    // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
    paymentESService.update(paymentId) {
        it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
    }

    val request = Request.Builder().run {
        url("http://localhost:1234/external/process?serviceName=${serviceName}&accountName=${accountName}&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
        post(emptyBody)
    }.build()


    try {
        var currentTry = 1
        var suc = false
        while (currentTry++ <= retryCount) {
            while (!rateLimiter.tick()) {
            }
            if (now() + requestAverageProcessingTime.toMillis() <= deadline) {
                try {
                    client.newCall(request).enqueue(object : Callback {
                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                val body = try {
                                    mapper.readValue(response.body?.string(), ExternalSysResponse::class.java)
                                } catch (e: Exception) {
                                    logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.code}, reason: ${response.body?.string()}")
                                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                            }

                            logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

                            // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
                            // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
                            paymentESService.update(paymentId) {
                                it.logProcessing(body.result, now(), transactionId, reason = body.message)
                            }

                            suc = body.result
                            }
                        }

                    override fun onFailure(call: Call, e: IOException) {
                        logger.error("[$accountName] [ERROR] Payment failed for txId: $transactionId, payment: $paymentId", e)
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, reason = e.message)
                        }

                        suc = false
                    }
                    })
                } catch (ioe: InterruptedIOException) {
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = ioe.message)
                    }
                }
            } else {
                break
            }
            if (suc) {
                break
            }
        }
        } catch (e: Exception) {
            when (e) {
                is SocketTimeoutException -> {
                    logger.error("[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId", e)
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                    }
                }

                else -> {
                    logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)

                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message)
                    }
                }
            }
        }
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()