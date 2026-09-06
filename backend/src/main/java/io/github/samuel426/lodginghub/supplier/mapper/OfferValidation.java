package io.github.samuel426.lodginghub.supplier.mapper;

import io.github.samuel426.lodginghub.supplier.model.AvailabilityCondition;
import io.github.samuel426.lodginghub.supplier.model.SupplierBatchRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

public final class OfferValidation {
  private OfferValidation() {}

  public static <T> T required(T value) {
    if (value == null) {
      throw new InvalidSupplierOfferException();
    }
    return value;
  }

  public static String text(String value, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidSupplierOfferException();
    }
    return value;
  }

  public static String stayCode(String value, SupplierBatchRequest request) {
    text(value, 128);
    if (!request.stayCodes().contains(value)) {
      throw new InvalidSupplierOfferException();
    }
    return value;
  }

  public static String currency(String value) {
    text(value, 3);
    try {
      Currency.getInstance(value);
    } catch (IllegalArgumentException error) {
      throw new InvalidSupplierOfferException();
    }
    return value;
  }

  public static int occupancy(Integer value) {
    if (required(value) <= 0) {
      throw new InvalidSupplierOfferException();
    }
    return value;
  }

  public static int inventory(Integer value) {
    if (required(value) < 0) {
      throw new InvalidSupplierOfferException();
    }
    return value;
  }

  public static long money(Long value) {
    if (required(value) < 0) {
      throw new InvalidSupplierOfferException();
    }
    return value;
  }

  public static long add(long first, long second) {
    try {
      return Math.addExact(first, second);
    } catch (ArithmeticException error) {
      throw new InvalidSupplierOfferException();
    }
  }

  public static <T> SortedMap<LocalDate, T> nights(
      List<T> items, Function<T, String> dateField, AvailabilityCondition condition) {
    var nights = new TreeMap<LocalDate, T>();
    for (T item : required(items)) {
      LocalDate date;
      try {
        String raw = required(dateField.apply(required(item)));
        if (!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
          throw new InvalidSupplierOfferException();
        }
        date = LocalDate.parse(raw);
      } catch (DateTimeParseException error) {
        throw new InvalidSupplierOfferException();
      }
      if (condition.includes(date) && nights.putIfAbsent(date, item) != null) {
        throw new InvalidSupplierOfferException();
      }
    }
    if (nights.size() != condition.nights()) {
      throw new InvalidSupplierOfferException();
    }
    return nights;
  }
}
