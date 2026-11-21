package com.example.mockbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the admin dashboard (static/admin/index.html) at /admin and /admin/ — static resources only answer to the
 * full file name, and Spring 6 no longer matches a trailing slash. The page links its assets with absolute paths
 * (/admin/admin.js), so both forms work. Caddy Basic auth guards /admin* in deployment; Spring has no auth code.
 */
@Configuration
public class AdminPageConfig implements WebMvcConfigurer {
    static final String PAGE = "forward:/admin/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin").setViewName(PAGE);
        registry.addViewController("/admin/").setViewName(PAGE);
    }
}
