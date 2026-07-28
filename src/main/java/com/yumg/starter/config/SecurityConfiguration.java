package com.yumg.starter.config;

import com.yumg.starter.common.api.SecurityApiErrorHandler;
import com.yumg.starter.common.web.TraceIdFilter;
import com.yumg.starter.common.web.PublicApiRequestMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import com.yumg.starter.modules.security.web.RateLimitFilter;
import com.yumg.starter.modules.security.web.EndpointPolicyFilter;
import com.yumg.starter.modules.security.web.IpAccessFilter;
import com.yumg.starter.modules.security.web.RequestLoggingFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;
import java.util.Arrays;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityApiErrorHandler errors,
                                            TraceIdFilter traceIdFilter, RateLimitFilter rateLimitFilter, EndpointPolicyFilter endpointPolicyFilter, IpAccessFilter ipAccessFilter, RequestLoggingFilter requestLoggingFilter,
                                            PublicApiRequestMatcher publicApiRequestMatcher) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .addFilterBefore(traceIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(endpointPolicyFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(ipAccessFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(rateLimitFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(requestLoggingFilter, SecurityContextHolderFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout", "/actuator/health/**",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/actuator/**").hasAuthority("system:config:read")
                        .requestMatchers(publicApiRequestMatcher).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(RuntimeSettingService settings) {
        return request -> {
            if (!request.getRequestURI().startsWith("/api/")) return null;
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOriginPatterns(csv(settings.string("security.cors.allowed-origins")));
            configuration.setAllowedMethods(csv(settings.string("security.cors.allowed-methods")));
            configuration.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Trace-Id"));
            configuration.setAllowCredentials(true);
            return configuration;
        };
    }
    private List<String> csv(String value) { return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList(); }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("permissions");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
