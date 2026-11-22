package com.example.mockbackend.web;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The share link people open: /s/{token} (ShareCreatedResponse.url) shows the static web viewer
 * (static/share.html), which reads the token from its own path and calls the public /api/v1/shares/{token} routes.
 * A page, not an API: it lives outside /api/v1, so springdoc and the contract test do not see it. Unknown or expired
 * tokens still get the page; it shows its "link not found" screen after the API answers 404 SHARE_NOT_FOUND.
 */
@Controller
@Hidden
public class SharePageController {

    @GetMapping("/s/{token}")
    public String sharePage(HttpServletResponse response) {
        // The token is the secret of the link: never send it on as a Referer, and keep shared pages out of search.
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex");
        return "forward:/share.html";
    }
}
