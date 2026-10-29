package com.sysadminanywhere.m3.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
public class TechnicalTokenFilter extends OncePerRequestFilter {
    private final String token;
    public TechnicalTokenFilter(@Value("${M3_API_TOKEN:}") String token) { this.token = token; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return token.isBlank() || request.getRequestURI().startsWith("/api/v2/health") || request.getRequestURI().startsWith("/actuator"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!token.equals(request.getHeader("X-M3-Service-Token"))) { response.sendError(401, "Missing or invalid service token"); return; }
        chain.doFilter(request, response);
    }
}
