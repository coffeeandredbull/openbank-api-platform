package com.openbank.payment.security;

import com.openbank.payment.auth.JwtTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtTokenService jwtTokenService) {
        return new JwtAuthenticationFilter(jwtTokenService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/accounts").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/accounts").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/accounts/{accountId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.POST, "/payments").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/payments").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/payments/{paymentId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.POST, "/transactions").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/transactions").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/transactions/{transactionId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .anyRequest().denyAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}