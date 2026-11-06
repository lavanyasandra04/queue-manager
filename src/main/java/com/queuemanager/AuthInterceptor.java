package com.queuemanager;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Checks the "Authorization: Bearer <jwt>" header and stores the user id on the request. */
@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtService jwt;

    public AuthInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return reject(res, "Login required");
        }
        try {
            Claims claims = jwt.verify(header.substring(7));
            req.setAttribute("userId", Long.parseLong(claims.getSubject()));
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return reject(res, "Session expired. Please log in again.");
        }
    }

    private boolean reject(HttpServletResponse res, String message) throws IOException {
        res.setStatus(401);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"error\":\"" + message + "\"}");
        return false;
    }
}
