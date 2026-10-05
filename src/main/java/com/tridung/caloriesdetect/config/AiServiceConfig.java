package com.tridung.caloriesdetect.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceConfig {
    @Bean
    RestClient aiRestClient(AiServiceProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.responseTimeout());
        return RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory).build();
    }
}
