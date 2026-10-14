package com.cpg.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bootstrap authentication for {@code /internal/**} using a shared operator token, compared in constant time.
 * To be replaced by SSO with per-operator identities and MFA when the admin console lands.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Token";

    private final byte[] expected;

    public AdminTokenFilter(String adminToken) {
        this.expected = adminToken == null || adminToken.isBlank() ? null : adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (expected != null && presented != null
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {
            var auth = new UsernamePasswordAuthenticationToken("operator", null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}
