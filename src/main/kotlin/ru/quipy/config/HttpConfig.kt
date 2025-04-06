package ru.quipy.config

import org.apache.coyote.http2.Http2Protocol
import org.slf4j.LoggerFactory
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.quipy.payments.logic.PaymentExternalSystemAdapter

@Configuration
class HttpConfig {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
    }

    @Bean
    fun tomcatConnectorCustomizer(): TomcatConnectorCustomizer {
        return TomcatConnectorCustomizer {
            try {
                (it.protocolHandler.findUpgradeProtocols().get(0) as Http2Protocol).maxConcurrentStreams = 10_000_000
            } catch (e: Exception) {
                logger.error("!!! Failed to increase number of http2 streams per connection !!!")
            }
        }
    }
}