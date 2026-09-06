package io.github.samuel426.lodginghub.search.service;

public enum SearchFailure {
  INVALID_SEARCH_CONDITION(400, "검색 조건이 올바르지 않습니다."),
  INTERNAL_ERROR(500, "검색 처리 중 오류가 발생했습니다."),
  CATALOG_NOT_READY(503, "검색 가능한 상품 정보가 아직 준비되지 않았습니다."),
  INTEGRATION_CONFIGURATION_ERROR(500, "공급사 연동 설정을 확인할 수 없습니다."),
  CATALOG_MAPPING_UNAVAILABLE(503, "상품 식별 정보를 확인할 수 없습니다."),
  NO_VALID_SUPPLIER_DATA(502, "공급사 상품 정보를 확인할 수 없습니다."),
  ALL_SUPPLIERS_UNAVAILABLE(503, "현재 숙박 상품을 조회할 수 없습니다.");

  private final int httpStatus;
  private final String safeMessage;

  SearchFailure(int status, String message) {
    this.httpStatus = status;
    this.safeMessage = message;
  }

  public int status() {
    return httpStatus;
  }

  public String message() {
    return safeMessage;
  }
}
