package com.github.wuzhudexiaopacai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wuzhudexiaopacai.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public SecurityConfig(JwtService jwtService,
                          ObjectMapper objectMapper,
                          @Value("${coherer.security.internal-token}") String internalToken) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/refresh",
                                "/api/ping", "/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus", "/internal/**").permitAll()
                        .requestMatchers("/api/audit-logs/**").hasRole("ADMIN")
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        // RBAC：只读角色仅允许 GET；写操作要求 ADMIN / OPERATOR
                        .requestMatchers(HttpMethod.GET, "/api/**")
                        .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, exception) ->
                                writeJson(response, 401, "未登录或登录已过期"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeJson(response, 403, "无权限执行该操作")))
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new InternalTokenFilter(internalToken),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeJson(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}
