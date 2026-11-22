package com.example.mockbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the job detail page (static/job/index.html) at /job and /job/ — the address is /job/?id={jobId}, linked from
 * the admin dashboard. Same approach as AdminPageConfig: static resources only answer to the full file name and
 * Spring 6 no longer matches a trailing slash; the page links its assets with absolute paths (/job/job.js).
 * Caddy Basic auth guards /job* in deployment; Spring has no auth code.
 */
@Configuration
public class JobPageConfig implements WebMvcConfigurer {
    static final String PAGE = "forward:/job/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/job").setViewName(PAGE);
        registry.addViewController("/job/").setViewName(PAGE);
    }
}
