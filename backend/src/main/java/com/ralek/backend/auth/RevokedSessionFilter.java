package com.ralek.backend.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs after JWT signature validation, never trusts unverified token claims. */
public class RevokedSessionFilter extends OncePerRequestFilter {
    private final JdbcClient jdbc;
    public RevokedSessionFilter(JdbcClient jdbc) {this.jdbc=jdbc;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException,IOException {
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control","no-store");
        if(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authentication) {
            var jwt=authentication.getToken();
            String session=jwt.getClaimAsString("sid"); if(session==null) session=jwt.getId();
            if(session!=null && jdbc.sql("SELECT count(*) FROM revoked_sessions WHERE subject=:subject AND session_id=:session")
                .param("subject",jwt.getSubject()).param("session",session).query(Integer.class).single()>0) {
                SecurityContextHolder.clearContext();response.setStatus(401);return;
            }
        }
        chain.doFilter(request,response);
    }
}
