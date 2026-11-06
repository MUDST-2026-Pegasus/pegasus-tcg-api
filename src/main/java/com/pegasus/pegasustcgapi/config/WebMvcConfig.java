package com.pegasus.pegasustcgapi.config;

import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new AuthPrincipalArgumentResolver());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/v1/api-doc", "/v1/api-docs");
        registry.addRedirectViewController("/v3/api-docs", "/v1/api-docs");
    }

    @Override
    @SuppressWarnings("removal")
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jacksonConverter) {
                jacksonConverter.setDefaultCharset(StandardCharsets.UTF_8);
                List<MediaType> supported = new ArrayList<>(jacksonConverter.getSupportedMediaTypes());
                MediaType utf8Json = new MediaType("application", "json", StandardCharsets.UTF_8);
                supported.remove(utf8Json);
                supported.add(0, utf8Json);
                jacksonConverter.setSupportedMediaTypes(supported);
            } else if (converter instanceof StringHttpMessageConverter stringConverter) {
                stringConverter.setDefaultCharset(StandardCharsets.UTF_8);
            }
        }
    }
}
