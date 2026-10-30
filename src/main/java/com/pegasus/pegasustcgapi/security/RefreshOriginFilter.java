package com.pegasus.pegasustcgapi.security;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.config.CorsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Cookie-authenticated requests must come from an explicitly allowed browser origin. */
public class RefreshOriginFilter extends OncePerRequestFilter {
    private final CorsProperties cors;

    public RefreshOriginFilter(CorsProperties cors) {
        this.cors = cors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod())
                && (path.equals(ApiPaths.AUTH + "/refresh") || path.equals(ApiPaths.AUTH + "/logout"))) {
            String origin = request.getHeader("Origin");
            String type = request.getContentType();
            boolean json;
            try {
                json = type != null && MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(type));
            } catch (IllegalArgumentException invalidType) {
                json = false;
            }
            if (origin == null || !cors.allowedOrigins().contains(origin) || !json) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
