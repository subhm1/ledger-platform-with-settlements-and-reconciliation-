package com.ledger.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers("/api/v1/webhooks/razorpay").permitAll()
                        .requestMatchers("/api/**", "/actuator/health").hasRole("OPERATOR")
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    InMemoryUserDetailsManager operator(
            @Value("${ledger.security.username}") String username,
            @Value("${ledger.security.password}") String password) {
        if (username.isBlank() || password.length() < 24) {
            throw new IllegalStateException("Configure a non-empty operator username and a password of at least 24 characters.");
        }
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(username)
                .password(encoder.encode(password))
                .roles("OPERATOR")
                .build());
    }
}
