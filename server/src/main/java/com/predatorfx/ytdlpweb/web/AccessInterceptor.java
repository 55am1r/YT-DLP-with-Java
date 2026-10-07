package com.predatorfx.ytdlpweb.web;

import com.predatorfx.ytdlpweb.admin.ActivityFilter;
import com.predatorfx.ytdlpweb.admin.ActivityService;
import com.predatorfx.ytdlpweb.admin.AdminAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * The second gate, after the filters. They decide by the URL's text; this decides by the
 * controller method the request actually reached, so it holds however the URL was spelled:
 * blocked devices and IPs are refused (bar the health check), {@link AdminOnly} controllers
 * need an admin session, {@link PublicEndpoint}s need nothing, every other controller needs
 * the team login (or an admin session). Static files pass.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    private final AuthService auth;
    private final AdminAuthService admin;
    private final ActivityService activity;

    public AccessInterceptor(AuthService auth, AdminAuthService admin, ActivityService activity) {
        this.auth = auth;
        this.admin = admin;
        this.activity = activity;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        boolean isAdmin = admin.validSession(request);
        PublicEndpoint open = method.getMethodAnnotation(PublicEndpoint.class);
        if (open == null) {
            open = method.getBeanType().getAnnotation(PublicEndpoint.class);
        }
        if (!isAdmin && (open == null || !open.evenWhenBlocked())
                && activity.isBlocked(ActivityFilter.deviceId(request), ActivityFilter.client(request).ip())) {
            ActivityFilter.refuseBlocked(response);
            return false;
        }
        if (method.getBeanType().isAnnotationPresent(AdminOnly.class)) {
            if (isAdmin) {
                return true;
            }
            AuthFilter.deny(response, "admin login required");
            return false;
        }
        if (open != null || isAdmin || !auth.isEnabled() || auth.validCookie(request)) {
            return true;
        }
        AuthFilter.deny(response, "login required");
        return false;
    }
}
