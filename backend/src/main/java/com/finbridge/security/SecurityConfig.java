package com.finbridge.security;

import com.finbridge.config.AppProperties;
import com.finbridge.user.UserRepository;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security. Authorization is enforced here (URL rules) and again with
 * @PreAuthorize on controllers, so hiding a button in the frontend is never the only guard.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final AppProperties properties;

    public SecurityConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   UserRepository userRepository,
                                                   SecurityErrorHandler errorHandler) throws Exception {
        boolean publicApiDocs = properties.security().publicApiDocs();

        http
                // No cookies and no server session: a CSRF token would protect nothing.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
                    if (publicApiDocs) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .permitAll();
                    }
                    auth.requestMatchers("/api/auth/register", "/api/users", "/api/users/**", "/api/roles")
                            .hasRole("ADMIN");
                    // Everyone may see whether the integrations are up; everything else under
                    // /api/integrations (sync, health checks, logs) is for admins and operators.
                    auth.requestMatchers(HttpMethod.GET, "/api/integrations/status").authenticated();
                    auth.requestMatchers("/api/integrations/config", "/api/integrations/config/**")
                            .hasRole("ADMIN");
                    auth.requestMatchers("/api/dashboard/reconciliation-summary")
                            .hasAnyRole("ADMIN", "FINANCE_MANAGER");
                    auth.requestMatchers("/api/integrations/**", "/api/invoices/sync")
                            .hasAnyRole("ADMIN", "INTEGRATION_OPERATOR");
                    auth.requestMatchers("/api/reconciliation", "/api/reconciliation/**")
                            .hasAnyRole("ADMIN", "FINANCE_MANAGER");
                    // Creating a payment and retrying one are held by different roles on purpose.
                    auth.requestMatchers(HttpMethod.POST, "/api/payments")
                            .hasAnyRole("ADMIN", "FINANCE_MANAGER");
                    auth.requestMatchers(HttpMethod.POST, "/api/payments/*/status-check", "/api/payments/*/retry")
                            .hasAnyRole("ADMIN", "INTEGRATION_OPERATOR");
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userRepository, errorHandler),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(properties.security().bcryptStrength());
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.security().corsAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Idempotent-Replay"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
