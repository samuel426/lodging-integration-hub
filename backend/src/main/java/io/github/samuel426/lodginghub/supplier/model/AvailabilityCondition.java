package io.github.samuel426.lodginghub.supplier.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record AvailabilityCondition(
    LocalDate checkIn, LocalDate checkOut, int adults, int children) {
  public AvailabilityCondition {
    if (checkIn == null
        || checkOut == null
        || !checkOut.isAfter(checkIn)
        || adults < 1
        || children < 0) {
      throw new IllegalArgumentException("Invalid availability condition");
    }
  }

  public long nights() {
    return ChronoUnit.DAYS.between(checkIn, checkOut);
  }

  public long guests() {
    return (long) adults + children;
  }

  public boolean includes(LocalDate date) {
    return !date.isBefore(checkIn) && date.isBefore(checkOut);
  }
}
