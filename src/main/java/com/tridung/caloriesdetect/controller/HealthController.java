package com.tridung.caloriesdetect.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health", description = "Application health endpoints")
public class HealthController {

    @GetMapping("/")
    @SecurityRequirements
    @Operation(summary = "Application information")
    public Map<String, String> home() {
        return Map.of(
                "application", "calories-detect-backend",
                "status", "running"
        );
    }

    @GetMapping("/api/health")
    @SecurityRequirements
    @Operation(summary = "Health check")
    public Map<String, String> health() {
        return Map.of("status", "OK");
    }
}
