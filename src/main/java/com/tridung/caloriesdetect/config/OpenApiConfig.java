package com.tridung.caloriesdetect.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    OpenAPI caloriesDetectOpenApi(AuthCookieProperties authProperties) {
        return new OpenAPI()
                .info(new Info()
                        .title("Calories Detect API")
                        .description("REST API for Calories Detect. Access JWTs use Bearer authorization; refresh tokens are only HttpOnly cookies. Fetch GET /api/auth/csrf with credentials, then send its masked token in X-XSRF-TOKEN for login, Google login, refresh and logout. Cookie names: calories_refresh locally; __Secure-calories_refresh on HTTPS production. No refresh tokens in JSON. Responses containing tokens must not be cached.")
                        .version("v1")
                        .contact(new Contact().name("Calories Detect Team"))
                )
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes("csrfHeader", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-XSRF-TOKEN")
                                .description("Execute GET /api/auth/csrf first, then paste data.token here without a Bearer prefix. "
                                        + "The matching HttpOnly cookie " + authProperties.csrfCookieName()
                                        + " must accompany the request; the browser manages it automatically. "
                                        + "Keep Swagger UI and the API on the same host. Fetch a new token and authorize again if you receive 40301."))
                        .addSecuritySchemes("refreshCookie", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name(authProperties.refreshCookieName())
                                .description("HttpOnly; set automatically by login or Google login and rotated by refresh. "
                                        + "Use the same browser and API host. Swagger UI cannot manually set the Cookie header; "
                                        + "do not paste the refresh token into Authorize. Logout also succeeds without this cookie."))
                        .addResponses("InvalidCsrfToken", new ApiResponse()
                                .description("Missing or invalid X-XSRF-TOKEN header or matching CSRF cookie. "
                                        + "Call GET /api/auth/csrf, retain its cookie, then set csrfHeader in Authorize to data.token. "
                                        + "A Bearer token does not replace CSRF protection.")
                                .content(new Content().addMediaType("application/json", new MediaType()
                                        .schema(new Schema<>().type("object")
                                                .addProperty("success", new Schema<>().type("boolean"))
                                                .addProperty("code", new Schema<>().type("integer"))
                                                .addProperty("message", new Schema<>().type("string")))
                                        .example(Map.of("success", false, "code", 40301, "message", "Invalid CSRF token")))))
                        .addSecuritySchemes(
                                SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                        )
                );
    }

    @Bean
    OpenApiCustomizer refreshCookieSecurity() {
        return openApi -> {
            var path = openApi.getPaths().get("/api/auth/refresh-token");
            if (path != null && path.getPost() != null) {
                // One requirement means CSRF AND cookie; separate requirements would mean OR.
                path.getPost().setSecurity(List.of(new SecurityRequirement()
                        .addList("csrfHeader").addList("refreshCookie")));
            }
        };
    }
}
