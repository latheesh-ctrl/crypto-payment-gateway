package com.cpg.gateway.session;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param baseUrl origin of the hosted payment page; payment URLs are {@code <baseUrl>/s/<session id>#t=<token>} */
@ConfigurationProperties("gateway.checkout")
public record CheckoutProperties(@DefaultValue("http://localhost:3000") String baseUrl) {
}
