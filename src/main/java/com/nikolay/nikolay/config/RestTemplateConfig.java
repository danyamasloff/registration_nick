package com.nikolay.nikolay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    /**
     * Создает и настраивает объект RestTemplate для работы с API
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}