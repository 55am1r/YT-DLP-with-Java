package com.predatorfx.ytdlpweb.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Gates the API behind the team session cookie. The static app shell (index.html,
 * assets, favicon) and the auth/health endpoints stay open so the login screen can
 * load; everything else under /api requires a valid session.
 */
@Component
public class AuthFilter implements Filter {

    private final AuthService auth;

    public AuthFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (!auth.isEnabled() || isOpen(path) || auth.validCookie(request)) {
            chain.doFilter(req, res);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"login required\"}");
    }

    private static boolean isOpen(String path) {
        if (!path.startsWith("/api/")) {
            return true; // static app shell + assets
        }
        return path.equals("/api/login")
                || path.equals("/api/logout")
                || path.equals("/api/me")
                || path.equals("/api/health");
    }
}
