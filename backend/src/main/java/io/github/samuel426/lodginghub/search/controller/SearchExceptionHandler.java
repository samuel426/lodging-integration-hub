package io.github.samuel426.lodginghub.search.controller;

import io.github.samuel426.lodginghub.global.config.CorrelationIdFilter;
import io.github.samuel426.lodginghub.global.response.ErrorResponse;
import io.github.samuel426.lodginghub.global.response.ErrorResponse.ErrorDetail;
import io.github.samuel426.lodginghub.global.response.ErrorResponse.FieldError;
import io.github.samuel426.lodginghub.search.service.SearchException;
import io.github.samuel426.lodginghub.search.service.SearchFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = StaySearchController.class)
public class SearchExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(SearchExceptionHandler.class);

  @ExceptionHandler(SearchException.class)
  ResponseEntity<ErrorResponse> known(SearchException error, HttpServletRequest request) {
    return response(error.failure(), List.of(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> invalid(
      MethodArgumentNotValidException error, HttpServletRequest request) {
    var fields =
        error.getBindingResult().getFieldErrors().stream()
            .map(field -> new FieldError(field.getField(), "필수 값과 입력 형식을 확인해 주세요."))
            .distinct()
            .sorted(java.util.Comparator.comparing(FieldError::field))
            .toList();
    return response(SearchFailure.INVALID_SEARCH_CONDITION, fields, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
    // Exception messages can contain upstream payloads, URLs or database values.
    LOG.error("search_failed category=INTERNAL_ERROR exceptionType={}", error.getClass().getName());
    return response(SearchFailure.INTERNAL_ERROR, List.of(), request);
  }

  private ResponseEntity<ErrorResponse> response(
      SearchFailure failure, List<FieldError> fields, HttpServletRequest request) {
    return ResponseEntity.status(failure.status())
        .body(
            new ErrorResponse(
                new ErrorDetail(failure.name(), failure.message(), fields),
                (String) request.getAttribute(CorrelationIdFilter.TRACE_ID)));
  }
}
