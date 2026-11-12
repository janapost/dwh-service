package kz.janapost.dwhservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenAPIConfiguration {

    @Bean
    public OpenAPI defineOpenAPI() {
        return new OpenAPI()
                .info(new Info().title("Jana Post IT — dwh-service").version("1.0"))
                .servers(List.of(
                        server("http://localhost:8080", "Local Environment"),
                        server("https://api.janapost.kz/dwh-service", "Real Environment"),
                        server("https://gateway-priv.janatest.kz/dwh-service", "Real Environment Without Authorization"),
                        server("https://api-dev.janatest.kz/dwh-service", "Test Environment"),
                        server("https://gateway-priv-dev.janatest.kz/dwh-service", "Test Environment Without Authorization")
                ))
                .addSecurityItem(new SecurityRequirement().addList("authorization"))
                .components(new Components().addSecuritySchemes("authorization", new SecurityScheme()
                        .name("authorization")
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }

    @Bean
    public OpenApiCustomizer tokenInfoHeaderCustomizer() {
        return openApi -> openApi.getPaths().values().forEach(pathItem ->
                pathItem.readOperations().forEach(operation -> operation.addParametersItem(new Parameter()
                        .in("header")
                        .name("X-TokenInfo")
                        .description("Token Info")
                        .required(false)
                        .schema(new StringSchema()))));
    }

    private static Server server(String url, String description) {
        Server server = new Server();
        server.setUrl(url);
        server.setDescription(description);
        return server;
    }
}
