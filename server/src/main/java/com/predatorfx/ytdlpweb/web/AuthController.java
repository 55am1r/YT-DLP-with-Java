package com.predatorfx.ytdlpweb.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Login / session endpoints for the team password. */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(String username, String password) {}

    /** Used by the UI on load to decide whether to show the app or the login screen. */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        if (auth.validCookie(req)) {
            return ResponseEntity.ok(Map.of("authenticated", true, "username", auth.username()));
        }
        return ResponseEntity.status(401).body(Map.of("authenticated", false));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) LoginRequest body) {
        if (body != null && auth.checkCredentials(body.username(), body.password())) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, auth.sessionCookie().toString())
                    .body(Map.of("ok", true));
        }
        return ResponseEntity.status(401).body(Map.of("ok", false, "error", "Wrong username or password"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, auth.clearCookie().toString())
                .body(Map.of("ok", true));
    }
}
