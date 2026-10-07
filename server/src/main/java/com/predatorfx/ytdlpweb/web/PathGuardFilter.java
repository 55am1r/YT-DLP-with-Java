package com.predatorfx.ytdlpweb.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Turns away request paths spelled in ways this app never uses: percent-escapes, ';' path
 * parameters, backslashes, empty or dot segments.
 *
 * The filters after this one compare the raw path as text, while Spring routes on the decoded
 * path. Without this, "/%61pi/admin/…" or "/api;x/admin/…" looked like a static file to
 * AuthFilter yet still reached the admin API — and "/%61pi/…" skipped the block check. Every
 * real URL here (pages, assets, ids) is plain ASCII, so refusing these costs nothing.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PathGuardFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (isSuspicious(((HttpServletRequest) req).getRequestURI())) {
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"bad request path\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean isSuspicious(String uri) {
        return uri == null || uri.isEmpty() || uri.indexOf('%') >= 0 || uri.indexOf(';') >= 0 || uri.indexOf('\\') >= 0
                || uri.contains("//") || uri.contains("/./") || uri.contains("/../")
                || uri.endsWith("/.") || uri.endsWith("/..");
    }
}
