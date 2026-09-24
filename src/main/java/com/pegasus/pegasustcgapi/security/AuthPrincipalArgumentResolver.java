package com.pegasus.pegasustcgapi.security;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.UnauthorizedException;
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
 * as a parameter and receive the authenticated caller.
 *
 * <p>Which routes may be called without signing in is decided by the parameter's
 * own type, not by its URL. A handler that takes {@code Optional<AuthPrincipal>}
 * is saying it serves guests; a handler that takes {@code AuthPrincipal} is saying
 * it does not, and an anonymous request to it is rejected here.
 *
 * <p>This used to be a list of path fragments — anything containing {@code /cart/}
 * resolved to a null principal. That is a second, quietly diverging copy of the
 * filter chain's rules, and it grants guest access to any future route that happens
 * to have the word in it, such as an admin cart audit endpoint.
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
            if (isOptional) {
                return Optional.empty();
            }
            throw new UnauthorizedException(ErrorCode.UNAUTHENTICATED);
        }
        AuthPrincipal principal = AuthPrincipal.from(jwt);
        return isOptional ? Optional.of(principal) : principal;
    }
}
