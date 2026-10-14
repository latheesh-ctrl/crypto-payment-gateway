package com.cpg.gateway.security;

import com.cpg.gateway.apikey.ApiKeyService;
import com.cpg.gateway.config.GatewayProperties;
import com.cpg.gateway.web.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiKeyService apiKeys, GatewayProperties properties,
                                    JsonMapper json) throws Exception {
        return http
                // Stateless bearer-token API: no cookies, so CSRF protection does not apply.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new AdminTokenFilter(properties.security().adminToken()),
                        AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/internal/**").hasRole("ADMIN")
                        .requestMatchers("/v1/**").hasRole("MERCHANT")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {
                            ProblemDetail p = Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated",
                                    "Missing or invalid credentials");
                            res.setStatus(401);
                            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            json.writeValue(res.getOutputStream(), p);
                        })
                        .accessDeniedHandler((req, res, ex) -> {
                            ProblemDetail p = Problems.of(HttpStatus.FORBIDDEN, "forbidden",
                                    "These credentials cannot access this resource");
                            res.setStatus(403);
                            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            json.writeValue(res.getOutputStream(), p);
                        }))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'")))
                .build();
    }
}
