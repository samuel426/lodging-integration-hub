package io.github.samuel426.lodginghub.supplier.model;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public record PriceSummary(
    long totalAmount,
    String currency,
    boolean taxIncluded,
    @Schema(
            types = {"integer", "null"},
            format = "int64",
            description = "공급사가 제공하지 않으면 null")
        Long taxAmount,
    @ArraySchema(
            arraySchema = @Schema(types = {"array", "null"}),
            schema = @Schema(implementation = NightlyPrice.class))
        List<NightlyPrice> nightlyBreakdown) {
  public PriceSummary {
    if (nightlyBreakdown != null) {
      nightlyBreakdown = List.copyOf(nightlyBreakdown);
    }
  }

  public record NightlyPrice(LocalDate date, long amount) {}
}
