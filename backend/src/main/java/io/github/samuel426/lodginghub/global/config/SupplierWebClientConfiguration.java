package io.github.samuel426.lodginghub.global.config;

import io.github.samuel426.lodginghub.supplier.client.SupplierJsonSupport;
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
            .disableRetry(true)
            .followRedirect(false);
    // Initialize event loops, resolver and native libraries before the first request deadline.
    // This does not connect to any supplier or fetch catalog data.
    httpClient.warmup().block();
    var mapper = SupplierJsonSupport.mapper();
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
