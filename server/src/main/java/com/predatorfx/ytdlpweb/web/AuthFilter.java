package com.predatorfx.ytdlpweb.web;

import com.predatorfx.ytdlpweb.admin.AdminAuthService;
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
 * load; everything else under /api requires a valid session. /api/admin/** needs an
 * admin session — the team cookie alone never opens it, even with team auth turned off.
 */
@Component
public class AuthFilter implements Filter {

    private final AuthService auth;
    private final AdminAuthService admin;

    public AuthFilter(AuthService auth, AdminAuthService admin) {
        this.auth = auth;
        this.admin = admin;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();
        if (path.startsWith("/api/admin/")) {
            if (admin.validSession(request)) {
                chain.doFilter(req, res);
            } else {
                deny(response, "admin login required");
            }
            return;
        }
        if (!auth.isEnabled() || isOpen(path) || auth.validCookie(request) || admin.validSession(request)) {
            chain.doFilter(req, res);
            return;
        }
        deny(response, "login required");
    }

    /** 401 with a small JSON body — shared with {@link AccessInterceptor}. */
    static void deny(HttpServletResponse response, String error) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
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
