package com.openbank.analytics.internal;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the internal-service authentication filter for the
 * {@code /internal/**} path only (Phase 23, Slice 4). Nothing else — actuator,
 * public endpoints, or the servlet container defaults — is affected.
 */
@Configuration(proxyBeanMethods = false)
public class InternalApiSecurityConfiguration {

    @Bean
    FilterRegistrationBean<InternalApiAuthenticationFilter> internalApiAuthenticationFilter(
            InternalApiAuthProperties properties) {
        FilterRegistrationBean<InternalApiAuthenticationFilter> registration =
                new FilterRegistrationBean<>(new InternalApiAuthenticationFilter(properties));
        registration.addUrlPatterns("/internal/*");
        registration.setName("internalApiAuthenticationFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}