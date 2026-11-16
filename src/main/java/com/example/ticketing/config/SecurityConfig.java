package com.example.ticketing.config;

import java.time.Clock;

import com.example.ticketing.exception.ErrorCode;
import com.example.ticketing.security.JsonErrorWriter;
import com.example.ticketing.security.JwtService;
import com.example.ticketing.security.RateLimitFilter;
import com.example.ticketing.security.RateLimiter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.accessDecoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                   AppProperties properties, Clock clock) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) ->
                JsonErrorWriter.write(response, ErrorCode.UNAUTHORIZED,
                        "Authentication is required or the token is invalid", request.getRequestURI());
        AccessDeniedHandler deniedHandler = (request, response, ex) ->
                JsonErrorWriter.write(response, ErrorCode.FORBIDDEN,
                        "You do not have permission to perform this action", request.getRequestURI());

        AppProperties.RateLimit rl = properties.rateLimit();
        RateLimitFilter rateLimitFilter =
                new RateLimitFilter(new RateLimiter(clock), rl.enabled(), rl.authPerMinute(), rl.apiPerMinute());

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/events/public").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events/*/reservations")
                                .hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers("/api/reservations/**").hasAnyRole("CUSTOMER", "ORGANIZER", "ADMIN")
                        .requestMatchers("/api/events/**").hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.CLAIM_ROLES);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
