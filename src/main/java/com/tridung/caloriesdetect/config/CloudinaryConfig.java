package com.tridung.caloriesdetect.config;

import com.cloudinary.Cloudinary;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@EnableConfigurationProperties(CloudinaryProperties.class)
public class CloudinaryConfig {
    @Bean
    Cloudinary cloudinary(CloudinaryProperties properties) {
        return new Cloudinary(Map.of(
                "cloud_name", properties.cloudName(),
                "api_key", properties.apiKey(),
                "api_secret", properties.apiSecret(),
                "secure", true
        ));
    }
}
