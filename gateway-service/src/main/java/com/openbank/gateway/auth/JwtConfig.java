package com.openbank.gateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class JwtConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}