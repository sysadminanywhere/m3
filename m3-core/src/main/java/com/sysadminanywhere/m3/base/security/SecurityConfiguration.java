package com.sysadminanywhere.m3.base.security;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import com.sysadminanywhere.m3.base.ui.LoginView;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.core.annotation.Order;

@Configuration
@EnableMethodSecurity
@Profile("!worker")
public class SecurityConfiguration {
    @Bean UserDetailsService users(Environment environment, MachineAccounts machines) {
        var users = new InMemoryUserDetailsManager(); var encoder = new BCryptPasswordEncoder();
        for (String role : java.util.List.of("admin", "operator", "viewer")) {
            String password = environment.getProperty("m3.security." + role + "-password", "");
            if (password.isBlank()) {
                if (role.equals("admin")) throw new IllegalStateException("Set M3_ADMIN_PASSWORD or initialize local security before starting the UI");
                continue;
            }
            if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 16 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new IllegalStateException("Configured login passwords must contain 16–72 UTF-8 bytes");
            var authorities=new java.util.ArrayList<String>(); authorities.add(role.toUpperCase(java.util.Locale.ROOT));
            if(java.util.Arrays.stream(environment.getProperty("m3.security.original-readers","admin").split(","))
                    .map(String::trim).anyMatch(role::equals)) authorities.add("PAYLOAD_ORIGINAL");
            users.createUser(User.withUsername(role).password("{bcrypt}" + encoder.encode(password)).roles(authorities.toArray(String[]::new)).build());
        }
        for (var account : machines.getServices().values()) {
            if(account.getUsername()==null || !account.getUsername().matches("[a-zA-Z][a-zA-Z0-9_-]{0,79}") || users.userExists(account.getUsername())
                    || account.getPasswordHash()==null || !account.getPasswordHash().matches("\\{bcrypt\\}\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")
                    || account.getChannels().isEmpty() || account.getRecipients().isEmpty()) throw new IllegalArgumentException("Machine accounts require a unique username, BCrypt hash, channels and recipients");
            var authorities=new java.util.ArrayList<String>();authorities.add("ROLE_SERVICE");
            if(account.isOriginal()) authorities.add("ROLE_PAYLOAD_ORIGINAL");
            users.createUser(User.withUsername(account.getUsername()).password(account.getPasswordHash()).authorities(authorities.toArray(String[]::new)).build());
        }
        return users;
    }
    @Bean @Order(1) SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/**").sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new org.springframework.security.web.savedrequest.NullRequestCache()))
                .addFilterBefore(new org.springframework.web.filter.OncePerRequestFilter() {
                    @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain) throws java.io.IOException,jakarta.servlet.ServletException {
                        if (!java.util.Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()) && !"1".equals(request.getHeader("X-M3-Request"))) {
                            response.sendError(403,"State-changing API calls require X-M3-Request: 1"); return;
                        }
                        chain.doFilter(request,response);
                    }
                },org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                // A stateless API accepts explicit HTTP Basic credentials, never browser session cookies.
                .csrf(csrf -> csrf.disable()).httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/messages/*", "/api/v1/messages/*/payload", "/api/v1/messages/*/text", "/api/v1/messages/*/processing").hasAnyRole("ADMIN","OPERATOR","VIEWER","SERVICE")
                        .requestMatchers(HttpMethod.PATCH,"/api/v1/messages/*/status","/api/v1/messages/*/processing/*/status").hasAnyRole("ADMIN","OPERATOR","SERVICE")
                        .requestMatchers(HttpMethod.POST,"/api/v1/messages/*/processing/*/replay").hasAnyRole("ADMIN","OPERATOR","SERVICE")
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN","OPERATOR","VIEWER")
                        .anyRequest().hasAnyRole("ADMIN","OPERATOR"))
                .build();
    }
    @Bean @Order(2) @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="m3.security.vaadin-ui",havingValue="true",matchIfMissing=true)
    SecurityFilterChain ui(HttpSecurity http) throws Exception {
        http.addFilterAfter(new org.springframework.web.filter.OncePerRequestFilter(){
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)throws java.io.IOException,jakarta.servlet.ServletException{
                var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if(auth!=null&&auth.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_SERVICE"))){response.sendError(403,"Machine accounts use the scoped API only");return;}chain.doFilter(request,response);
            }
        },org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/error","/icons/**","/theme-preference.js","/unsaved-changes.js").permitAll());
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/error"));
        http.with(VaadinSecurityConfigurer.vaadin(), config -> config.loginView(LoginView.class));
        return http.build();
    }
    @Bean @Order(2) @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="m3.security.vaadin-ui",havingValue="false")
    SecurityFilterChain headless(HttpSecurity http) throws Exception {
        return http.requestCache(cache -> cache.requestCache(new org.springframework.security.web.savedrequest.NullRequestCache()))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/error"))
                .authorizeHttpRequests(auth -> auth.requestMatchers("/error").permitAll().anyRequest().denyAll()).build();
    }
}
