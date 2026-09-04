package io.github.samuel426.lodginghub.global.config;

import io.netty.channel.ChannelOption;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierClientProperties.class)
public class SupplierWebClientConfiguration {
  @Bean
  WebClient supplierAWebClient(WebClient.Builder builder, SupplierClientProperties properties) {
    return create(builder, properties, properties.a());
  }

  @Bean
  WebClient supplierBWebClient(WebClient.Builder builder, SupplierClientProperties properties) {
    return create(builder, properties, properties.b());
  }

  private WebClient create(
      WebClient.Builder builder,
      SupplierClientProperties properties,
      SupplierClientProperties.Endpoint endpoint) {
    var httpClient =
        HttpClient.create()
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis())
            .responseTimeout(properties.responseTimeout())
            .followRedirect(false);
    // Initialize event loops, resolver and native libraries before the first request deadline.
    // This does not connect to any supplier or fetch catalog data.
    httpClient.warmup().block();
    var mapper =
        JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(
                LogicalType.Textual,
                config ->
                    config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();
    return builder
        .clone()
        .baseUrl(endpoint.baseUrl().toString())
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .codecs(
            codecs -> {
              codecs.defaultCodecs().maxInMemorySize(properties.maxInMemoryBytes());
              codecs.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(mapper));
            })
        .filter(
            (request, next) ->
                next.exchange(
                    ClientRequest.from(request)
                        .headers(
                            headers -> {
                              headers.set("X-Api-Key", endpoint.apiKey());
                              // Startup calls have no inbound request; every outbound call is
                              // identifiable.
                              if (headers.getFirst("X-Correlation-Id") == null) {
                                headers.set("X-Correlation-Id", UUID.randomUUID().toString());
                              }
                            })
                        .build()))
        .build();
  }
}
