package io.github.samuel426.lodginghub.search.config;

import io.swagger.v3.oas.models.media.Schema;
import java.util.LinkedHashSet;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SearchOpenApiConfiguration {
  @Bean
  OpenApiCustomizer nullableNightlyBreakdown() {
    // The current array model converter replaces annotation types with only "array".
    // Preserve the public OpenAPI 3.1 null contract after model conversion.
    return openApi -> {
      Schema<?> price = openApi.getComponents().getSchemas().get("PriceSummary");
      if (price != null) {
        Schema<?> nightly = price.getProperties().get("nightlyBreakdown");
        nightly.setTypes(new LinkedHashSet<>(List.of("array", "null")));
      }
    };
  }
}
