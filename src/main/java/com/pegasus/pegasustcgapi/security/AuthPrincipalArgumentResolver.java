package com.pegasus.pegasustcgapi.security;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller declare {@code AuthPrincipal} (or {@code Optional<AuthPrincipal>})
 * as a parameter and receive the authenticated caller. The filter chain has already
 * rejected anonymous requests to guarded routes; cart routes allow unauthenticated
 * guest access and safely resolve to null / empty Optional.
 */
public class AuthPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (AuthPrincipal.class.equals(parameter.getParameterType())) {
            return true;
        }
        if (Optional.class.equals(parameter.getParameterType())) {
            ResolvableType resolvableType = ResolvableType.forMethodParameter(parameter);
            return AuthPrincipal.class.equals(resolvableType.getGeneric(0).resolve());
        }
        return false;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {

        boolean isOptional = Optional.class.equals(parameter.getParameterType());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            if (isCartRequest(webRequest)) {
                return isOptional ? Optional.empty() : null;
            }
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }
        AuthPrincipal principal = AuthPrincipal.from(jwt);
        return isOptional ? Optional.of(principal) : principal;
    }

    private boolean isCartRequest(NativeWebRequest webRequest) {
        HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
        if (servletRequest == null) {
            return false;
        }
        String uri = servletRequest.getRequestURI();
        String servletPath = servletRequest.getServletPath();
        return matchesCartPath(uri) || matchesCartPath(servletPath);
    }

    private boolean matchesCartPath(String path) {
        if (path == null) {
            return false;
        }
        return path.equals("/cart")
                || path.startsWith("/cart/")
                || path.equals(ApiPaths.CART)
                || path.startsWith(ApiPaths.CART + "/");
    }
}
