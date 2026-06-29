package com.jaram.be.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtProvider jwtProvider,
                                           RestAuthEntryPoint entryPoint,
                                           RestAccessDeniedHandler deniedHandler) throws Exception {
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/people", "/api/seminars", "/api/studies").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.GET, "/api/studies/pending", "/api/studies/applicants").hasAuthority("OFFICER")
                .requestMatchers("/api/studies/applicants/**").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.POST, "/api/seminars").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.GET, "/api/seminars/*/roster").hasAuthority("OFFICER")
                .requestMatchers(HttpMethod.POST, "/api/studies/*/approve", "/api/studies/*/reject").hasAuthority("OFFICER")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint)
                .accessDeniedHandler(deniedHandler))
            .addFilterBefore(new JwtAuthFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
