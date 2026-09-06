package io.github.samuel426.lodginghub.global.response;

import java.util.List;

public record ErrorResponse(ErrorDetail error, String traceId) {
  public record ErrorDetail(String code, String message, List<FieldError> fieldErrors) {
    public ErrorDetail {
      fieldErrors = List.copyOf(fieldErrors);
    }
  }

  public record FieldError(String field, String reason) {}
}
