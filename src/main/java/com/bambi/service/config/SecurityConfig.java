package com.bambi.service.config;

import com.bambi.service.auth.JwtAuthenticationFilter;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.common.response.ApiResponse;
import com.bambi.service.common.response.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * 인증/인가 설정 (P0).
 * - stateless(JWT), 세션 없음
 * - /api/health, /api/version, /api/auth/signup, /api/auth/login 은 공개
 * - /api/admin/** 는 ADMIN 권한 필수, 그 외는 인증 필요
 * - 401/403 도 공통 응답 포맷(JSON)으로 내려준다
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/version").permitAll()
                        .requestMatchers("/api/auth/signup", "/api/auth/login").permitAll()
                        // 공개 피드·공개 프로필은 비로그인 열람 허용 (팀 정책: 홈=공개 SNS).
                        // GET 만 공개 — 팔로우/좋아요/공개설정 등 쓰기는 아래 authenticated 로 막힌다.
                        // 팔로잉 스코프(?following=true)는 로그인 전제라 서비스에서 401 을 던진다.
                        .requestMatchers(HttpMethod.GET, "/api/feed/public", "/api/users/*/profile",
                                "/api/users/*/cards", "/api/cards/*", "/api/reports/*").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 인증 안 됨 → 401 (AUTH_INVALID_TOKEN) */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> writeError(response, ErrorCode.AUTH_INVALID_TOKEN);
    }

    /** 권한 부족 → 403 (FORBIDDEN) */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeError(response, ErrorCode.FORBIDDEN);
    }

    private void writeError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiResponse<Void> body = ApiResponse.fail(new ErrorResponse(code.getCode(), code.getDefaultMessage()));
        objectMapper.writeValue(response.getWriter(), body);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // MVP: 전체 허용 (JWT 는 Authorization 헤더 기반, 쿠키 미사용). 운영 시 도메인 화이트리스트로 좁힌다.
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
