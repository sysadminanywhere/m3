package com.sysadminanywhere.m3.community;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Avoid Vaadin's HTML fallback for the full distribution's absent activation API. */
@Component @Profile("!worker") @Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CommunityLicenseFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws IOException, ServletException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.equals("/api/v1/license") || path.startsWith("/api/v1/license/")) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Not found\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
