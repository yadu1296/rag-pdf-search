package com.practice.ragpdfsearch.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the static chat UI at the friendlier URL {@code /chat}
 * instead of the default {@code /chat.html}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/chat.html");
        registry.addViewController("/chat").setViewName("forward:/chat.html");
    }
}
