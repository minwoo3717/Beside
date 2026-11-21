package com.example.mockbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the pet library (static/library/index.html) at /library and /library/, the same way AdminPageConfig serves
 * /admin: static resources only answer to the full file name. The page links its assets with absolute paths
 * (/library/library.js). Caddy Basic auth guards /library* in deployment; Spring has no auth code.
 */
@Configuration
public class LibraryPageConfig implements WebMvcConfigurer {
    static final String PAGE = "forward:/library/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/library").setViewName(PAGE);
        registry.addViewController("/library/").setViewName(PAGE);
    }
}
