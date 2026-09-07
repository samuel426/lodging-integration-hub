package io.github.samuel426.lodginghub.supplier.mapper;

import static org.assertj.core.api.Assertions.*;

import io.github.samuel426.lodginghub.supplier.a.dto.SupplierAAvailabilityResponse;
import io.github.samuel426.lodginghub.supplier.a.mapper.SupplierAOfferMapper;
import io.github.samuel426.lodginghub.supplier.b.dto.SupplierBAvailabilityResponse;
import io.github.samuel426.lodginghub.supplier.b.mapper.SupplierBOfferMapper;
import io.github.samuel426.lodginghub.supplier.client.SupplierJsonSupport;
import io.github.samuel426.lodginghub.supplier.model.*;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class AvailabilityMapperTest {
  private static final String SECOND_NIGHT = "2026-10-11";
  private static final AvailabilityCondition CONDITION =
      new AvailabilityCondition(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"), 2, 0);
  private static final SupplierBatchRequest REQUEST =
      new SupplierBatchRequest(List.of("stay-1"), CONDITION);
  private static final String A_OFFER =
      """
      {"hotelCode":"stay-1","hotelName":"Canal House","roomTypeCode":"twin",
       "roomTypeName":"Garden Twin","maxOccupancy":2,"breakfastIncluded":false,"currency":"KRW",
       "dailyRates":[{"date":"2026-10-11","remainingRooms":3,"nightlyRate":11000,"taxAmount":1100},
                     {"date":"2026-10-10","remainingRooms":1,"nightlyRate":10000,"taxAmount":1000}]}
      """;
  private static final String B_OFFER =
      """
      {"propertyId":"stay-1","propertyName":"Meadow House","roomId":"twin",
       "roomName":"Courtyard Twin","maxOccupancy":3,"breakfastIncluded":true,"currency":"KRW",
       "totalPrice":23100,"taxIncluded":true,
       "inventory":[{"date":"2026-10-10","remainingRooms":2},{"date":"2026-10-11","remainingRooms":1}]}
      """;

  @Test
  void aSumsGrossTaxAndSortsNights() {
    var offer = a(A_OFFER).validOffers().getFirst();
    assertThat(offer.price().totalAmount()).isEqualTo(23100);
    assertThat(offer.price().taxAmount()).isEqualTo(2100);
    assertThat(offer.price().nightlyBreakdown())
        .containsExactly(
            new PriceSummary.NightlyPrice(CONDITION.checkIn(), 11000),
            new PriceSummary.NightlyPrice(CONDITION.checkIn().plusDays(1), 12100));
    assertThat(offer.availableRoomCount()).isEqualTo(1);
  }

  @Test
  void bPreservesGrossWithoutInventingTaxOrNightlyPrices() {
    var offer = b(B_OFFER).validOffers().getFirst();
    assertThat(offer.price().totalAmount()).isEqualTo(23100);
    assertThat(offer.price().taxAmount()).isNull();
    assertThat(offer.price().nightlyBreakdown()).isNull();
    assertThat(offer.availableRoomCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({
    "10000,-1",
    "10000,9223372036854775807",
    "10000,1.5",
    "10000,null",
    "false,null",
    "2,0",
    "KRW,krw",
    "stay-1,unsolicited"
  })
  void malformedAOfferDoesNotDiscardValidSibling(String from, String to) {
    var result = a(A_OFFER.replace(from, to) + "," + A_OFFER);
    assertThat(result.validOffers()).hasSize(1);
    assertThat(result.rejectedOfferCount()).isEqualTo(1);
    assertThat(result.isValidatedEmptyBatch()).isFalse();
  }

  @Test
  void scalarCoercionIsRejectedPerOffer() {
    assertThat(a(A_OFFER.replace("10000", "\"10000\"")).rejectedOfferCount()).isEqualTo(1);
    assertThat(
            a(A_OFFER.replace("\"hotelName\":\"Canal House\"", "\"hotelName\":17"))
                .rejectedOfferCount())
        .isEqualTo(1);
    assertThat(a("null,42,[]").rejectedOfferCount()).isEqualTo(3);
  }

  @Test
  void missingDuplicateAndInvalidDatesAreRejected() {
    assertThat(a(A_OFFER.replace(SECOND_NIGHT, "2026-10-10")).rejectedOfferCount()).isEqualTo(1);
    assertThat(a(A_OFFER.replace(SECOND_NIGHT, "2026-10-12")).rejectedOfferCount()).isEqualTo(1);
    assertThat(a(A_OFFER.replace(SECOND_NIGHT, "2026-02-30")).rejectedOfferCount()).isEqualTo(1);
    assertThat(b(B_OFFER.replace(SECOND_NIGHT, "2026-10-10")).rejectedOfferCount()).isEqualTo(1);
  }

  @Test
  void checkoutAndOtherOutsideDaysDoNotAffectPriceOrInventory() {
    String extra =
        ",{\"date\":\"2026-10-12\",\"remainingRooms\":-1,\"nightlyRate\":-1,\"taxAmount\":-1}";
    var offer = a(A_OFFER.replace("]}", extra + "]}")).validOffers().getFirst();
    assertThat(offer.price().totalAmount()).isEqualTo(23100);
    assertThat(offer.availableRoomCount()).isEqualTo(1);
  }

  @Test
  void stockIsValidatedBeforeBusinessFiltering() {
    assertThat(a(A_OFFER.replace("\"remainingRooms\":1", "\"remainingRooms\":0")).validOffers())
        .hasSize(1);
    assertThat(
            a(A_OFFER
                    .replace("\"remainingRooms\":1", "\"remainingRooms\":0")
                    .replace("10000", "-1"))
                .rejectedOfferCount())
        .isEqualTo(1);
    assertThat(
            b(B_OFFER.replace("\"remainingRooms\":1", "\"remainingRooms\":-1"))
                .rejectedOfferCount())
        .isEqualTo(1);
    assertThat(
            b(B_OFFER.replace("\"taxIncluded\":true", "\"taxIncluded\":false"))
                .rejectedOfferCount())
        .isEqualTo(1);
  }

  @Test
  void onlyExplicitEmptyItemsAreValidEmptyObservations() {
    assertThat(a("").isValidatedEmptyBatch()).isTrue();
    assertThat(b("").isValidatedEmptyBatch()).isTrue();
    assertThatThrownBy(
            () -> SupplierAOfferMapper.map(new SupplierAAvailabilityResponse(null), REQUEST))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(
            () ->
                SupplierBOfferMapper.map(new SupplierBAvailabilityResponse("0000", null), REQUEST))
        .isInstanceOf(SupplierCallException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "E400,INVALID_REQUEST",
    "E401,AUTHENTICATION_ERROR",
    "E429,RATE_LIMITED",
    "E500,UPSTREAM_ERROR",
    "E503,UPSTREAM_ERROR",
    "unknown,INVALID_RESPONSE"
  })
  void bBodyFailureRemainsFailure(String code, SupplierFailureCategory category) {
    assertThatThrownBy(
            () -> SupplierBOfferMapper.map(new SupplierBAvailabilityResponse(code, null), REQUEST))
        .isInstanceOfSatisfying(
            SupplierCallException.class, e -> assertThat(e.category()).isEqualTo(category));
  }

  @Test
  void internalBugsAreNotReclassifiedAsSupplierDataErrors() {
    var item = SupplierJsonSupport.mapper().readTree("{}");
    assertThatThrownBy(
            () ->
                OfferBatchMapper.map(
                    List.of(item),
                    ignored -> {
                      throw new NullPointerException("bug");
                    }))
        .isInstanceOf(NullPointerException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 49, 50, 51, 101})
  void splitsWithoutLossOrOversizedRequests(int count) {
    var codes = IntStream.range(0, count).mapToObj(i -> "stay-" + i).toList();
    var batches = SupplierBatches.split(codes, CONDITION);
    assertThat(batches).hasSize((count + 49) / 50);
    assertThat(batches).allSatisfy(batch -> assertThat(batch.stayCodes().size()).isBetween(1, 50));
    assertThat(batches.stream().flatMap(batch -> batch.stayCodes().stream()).toList())
        .isEqualTo(codes);
  }

  @Test
  void guestArithmeticDoesNotOverflow() {
    assertThat(
            new AvailabilityCondition(
                    CONDITION.checkIn(), CONDITION.checkOut(), Integer.MAX_VALUE, Integer.MAX_VALUE)
                .guests())
        .isEqualTo(4294967294L);
    assertThatThrownBy(() -> new SupplierBatchRequest(List.of("same", "same"), CONDITION))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private SupplierBatchOutcome a(String items) {
    return SupplierAOfferMapper.map(
        SupplierJsonSupport.mapper()
            .readValue("{\"items\":[" + items + "]}", SupplierAAvailabilityResponse.class),
        REQUEST);
  }

  private SupplierBatchOutcome b(String items) {
    return SupplierBOfferMapper.map(
        SupplierJsonSupport.mapper()
            .readValue(
                "{\"resultCode\":\"0000\",\"data\":{\"items\":[" + items + "]}}",
                SupplierBAvailabilityResponse.class),
        REQUEST);
  }
}
