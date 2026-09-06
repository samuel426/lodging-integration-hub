package io.github.samuel426.lodginghub.supplier.model;

import java.time.LocalDate;
import java.util.List;

public record PriceSummary(
    long totalAmount,
    String currency,
    boolean taxIncluded,
    Long taxAmount,
    List<NightlyPrice> nightlyBreakdown) {
  public PriceSummary {
    if (nightlyBreakdown != null) {
      nightlyBreakdown = List.copyOf(nightlyBreakdown);
    }
  }

  public record NightlyPrice(LocalDate date, long amount) {}
}
