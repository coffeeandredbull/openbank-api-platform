package com.openbank.apimanagement.security;

import com.openbank.apimanagement.auth.JwtTokenService;
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
                        .requestMatchers(HttpMethod.POST, "/apis").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/apis").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/apis/{id}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.POST, "/apis/{apiId}/versions").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/apis/{apiId}/versions").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/apis/{apiId}/versions/{versionId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.PATCH, "/apis/{apiId}/versions/{versionId}/lifecycle").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/applications").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/applications").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/applications/{applicationId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.PATCH, "/applications/{applicationId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.POST, "/subscriptions").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/subscriptions").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/subscriptions/{subscriptionId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.PATCH, "/subscriptions/{subscriptionId}/status").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/credentials").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/credentials").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/credentials/{credentialId}").hasAnyRole("ADMIN", "DEVELOPER")
                        .requestMatchers(HttpMethod.GET, "/internal/subscription-check").authenticated()
                        // Intentionally public: the API Gateway calls this with the caller's
                        // Basic credentials and this endpoint authenticates them itself. It is an
                        // internal, self-authenticating check (not a management API) and is never
                        // exposed through the gateway outside the managed /runtime/apis/** paths.
                        .requestMatchers(HttpMethod.GET, "/internal/credential-check").permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}