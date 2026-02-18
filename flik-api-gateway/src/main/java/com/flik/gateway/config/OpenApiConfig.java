package com.flik.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flikOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Flik AI Generation Pipeline API")
                        .description("Real-time AI content generation pipeline with task submission, "
                                + "DAG orchestration, cost tracking, and multi-region routing.")
                        .version("1.0.0")
                        .contact(new Contact().name("Flik Engineering")))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("API Key")
                                .description("Enter any non-blank token (e.g. 'test-token')")));
    }
}
