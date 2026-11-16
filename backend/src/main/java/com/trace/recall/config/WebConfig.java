package com.trace.recall.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ExternalApiKeyInterceptor externalApiKeyInterceptor;

    public WebConfig(ExternalApiKeyInterceptor externalApiKeyInterceptor) {
        this.externalApiKeyInterceptor = externalApiKeyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(externalApiKeyInterceptor).addPathPatterns("/external/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedMethods("*").allowedHeaders("*").allowedOrigins("*");
    }
}
