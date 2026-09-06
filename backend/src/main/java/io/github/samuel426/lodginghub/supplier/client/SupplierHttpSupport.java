package io.github.samuel426.lodginghub.supplier.client;

import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

public final class SupplierHttpSupport {
  private SupplierHttpSupport() {}

  public static <T> Mono<T> get(
      WebClient client, String path, Class<T> responseType, Duration deadline) {
    return client
        .get()
        .uri(path)
        .retrieve()
        .onStatus(
            status -> !status.is2xxSuccessful(),
            response ->
                response
                    .releaseBody()
                    // The status is authoritative even when discarding its body fails.
                    .onErrorResume(SupplierHttpSupport::isTransportFailure, ignored -> Mono.empty())
                    .thenReturn(
                        new SupplierCallException(httpCategory(response.statusCode().value()))))
        .bodyToMono(responseType)
        .switchIfEmpty(Mono.error(SupplierCallException.invalidResponse()))
        .timeout(deadline)
        .onErrorMap(SupplierHttpSupport::classify);
  }

  private static Throwable classify(Throwable error) {
    if (error instanceof SupplierCallException) {
      return error;
    }
    if (isTimeout(error)) {
      return new SupplierCallException(SupplierFailureCategory.TIMEOUT);
    }
    if (error instanceof DecodingException || error instanceof DataBufferLimitException) {
      return SupplierCallException.invalidResponse();
    }
    if (isTransportFailure(error)) {
      return new SupplierCallException(SupplierFailureCategory.CONNECTION_ERROR);
    }
    if (error instanceof WebClientResponseException) {
      return SupplierCallException.invalidResponse();
    }
    return error;
  }

  private static boolean isTransportFailure(Throwable error) {
    if (isTimeout(error) || error instanceof WebClientRequestException) {
      return true;
    }
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof IOException) {
        return true;
      }
    }
    return false;
  }

  public static SupplierFailureCategory httpCategory(int status) {
    return switch (status) {
      case 401, 403 -> SupplierFailureCategory.AUTHENTICATION_ERROR;
      case 429 -> SupplierFailureCategory.RATE_LIMITED;
      default ->
          status >= 500
              ? SupplierFailureCategory.UPSTREAM_ERROR
              : status >= 400
                  ? SupplierFailureCategory.INVALID_REQUEST
                  : SupplierFailureCategory.INVALID_RESPONSE;
    };
  }

  private static boolean isTimeout(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConnectTimeoutException
          || cause instanceof ReadTimeoutException
          || cause instanceof TimeoutException) {
        return true;
      }
    }
    return false;
  }
}
