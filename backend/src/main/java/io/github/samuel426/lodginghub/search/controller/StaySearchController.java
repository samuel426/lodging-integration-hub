package io.github.samuel426.lodginghub.search.controller;

import io.github.samuel426.lodginghub.global.response.ErrorResponse;
import io.github.samuel426.lodginghub.search.dto.SearchResponse;
import io.github.samuel426.lodginghub.search.dto.SearchStayRequest;
import io.github.samuel426.lodginghub.search.service.StaySearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StaySearchController {
  private final StaySearchService service;

  public StaySearchController(StaySearchService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/stays/search")
  @Operation(
      summary = "숙박 상품 통합 검색",
      description = "준비된 공급사 카탈로그 전체를 조회합니다. 일부 실패는 meta.partial과 실패 요약으로 제공합니다.")
  @ApiResponse(responseCode = "200", description = "정상 또는 부분 검색 결과")
  @ApiResponse(
      responseCode = "400",
      description = "잘못된 검색 조건",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description = "내부 결함 또는 연동 설정 오류",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "502",
      description = "유효한 공급사 데이터 없음",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "카탈로그 미준비·매핑 불일치 또는 전체 공급사 이용 불가",
      content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
  public SearchResponse search(@Valid @ModelAttribute @ParameterObject SearchStayRequest request) {
    return service.search(request.toCondition());
  }
}
