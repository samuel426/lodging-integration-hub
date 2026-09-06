package io.github.samuel426.lodginghub.search.dto;

import io.github.samuel426.lodginghub.search.service.SearchException;
import io.github.samuel426.lodginghub.search.service.SearchFailure;
import io.github.samuel426.lodginghub.supplier.model.AvailabilityCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

public record SearchStayRequest(
    @NotNull @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") @Schema(example = "2026-10-10")
        String checkIn,
    @NotNull @Pattern(regexp = "[0-9]{4}-[0-9]{2}-[0-9]{2}") @Schema(example = "2026-10-12")
        String checkOut,
    @NotNull @Min(1) @Schema(example = "2") Integer adults,
    @NotNull @Min(0) @Schema(example = "0") Integer children) {
  public AvailabilityCondition toCondition() {
    try {
      return new AvailabilityCondition(
          LocalDate.parse(checkIn), LocalDate.parse(checkOut), adults, children);
    } catch (IllegalArgumentException | java.time.DateTimeException error) {
      throw new SearchException(SearchFailure.INVALID_SEARCH_CONDITION);
    }
  }
}
