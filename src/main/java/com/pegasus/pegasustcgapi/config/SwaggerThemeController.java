package com.pegasus.pegasustcgapi.config;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the Swagger UI's local theme through its existing public resource path. */
@RestController
@Hidden
public class SwaggerThemeController {

    @GetMapping(value = "/swagger-ui/index.css", produces = "text/css")
    public Resource stylesheet() {
        return new ClassPathResource("swagger/index.css");
    }

    @GetMapping(value = "/swagger-ui/golden-council.png", produces = MediaType.IMAGE_PNG_VALUE)
    public Resource background() {
        return new ClassPathResource("swagger/golden-council.png");
    }
}
