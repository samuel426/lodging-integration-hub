package io.github.samuel426.lodginghub.search.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.samuel426.lodginghub.search.dto.SearchResponse;
import io.github.samuel426.lodginghub.search.dto.SearchResponse.SearchData;
import io.github.samuel426.lodginghub.search.dto.SearchResponse.SearchMeta;
import io.github.samuel426.lodginghub.search.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StaySearchController.class)
class StaySearchControllerTest {
  private static final String CHECK_IN = "checkIn";
  private static final String CHECK_OUT = "checkOut";
  private static final String ADULTS = "adults";
  private static final String CHILDREN = "children";
  private static final String ERROR_CODE = "$.error.code";

  private static final String PATH = "/api/v1/stays/search";
  private static final String TRACE_HEADER = "X-Correlation-Id";
  @Autowired MockMvc mvc;
  @MockitoBean StaySearchService service;

  @Test
  void validPastDatesAndEmptyResultsAreSuccessful() throws Exception {
    when(service.search(any()))
        .thenReturn(
            new SearchResponse(
                new SearchData(List.of()), new SearchMeta(false, 0, List.of(), List.of())));
    mvc.perform(
            get(PATH)
                .param(CHECK_IN, "2020-01-01")
                .param(CHECK_OUT, "2020-01-02")
                .param(ADULTS, "1")
                .param(CHILDREN, "0")
                .header(TRACE_HEADER, "contract-trace"))
        .andExpect(status().isOk())
        .andExpect(header().string(TRACE_HEADER, "contract-trace"))
        .andExpect(jsonPath("$.data.offers").isEmpty())
        .andExpect(jsonPath("$.meta.partial").value(false));
  }

  @ParameterizedTest
  @CsvSource({
    "2026-10-10,2026-10-10,2,0",
    "2026-10-12,2026-10-10,2,0",
    "2026-02-30,2026-10-12,2,0",
    "2026-10-10,2026-10-12,0,0",
    "2026-10-10,2026-10-12,2,-1",
    "2026-10-10,2026-10-12,text,0",
    "2026-10-10,2026-10-12,2147483648,0",
    "2026-1-1,2026-10-12,2,0"
  })
  void rejectsInvalidQueriesBeforeService(
      String checkIn, String checkOut, String adults, String children) throws Exception {
    mvc.perform(
            get(PATH)
                .param(CHECK_IN, checkIn)
                .param(CHECK_OUT, checkOut)
                .param(ADULTS, adults)
                .param(CHILDREN, children))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_SEARCH_CONDITION"))
        .andExpect(jsonPath("$.traceId").isNotEmpty())
        .andExpect(jsonPath("$.error.fieldErrors").isArray());
    verifyNoInteractions(service);
  }

  @Test
  void missingParametersAre400() throws Exception {
    mvc.perform(get(PATH))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath(ERROR_CODE).value("INVALID_SEARCH_CONDITION"));
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @EnumSource(SearchFailure.class)
  void failureStatusAndSafeEnvelopeMatchPolicy(SearchFailure failure) throws Exception {
    when(service.search(any())).thenThrow(new SearchException(failure));
    mvc.perform(
            get(PATH)
                .param(CHECK_IN, "2026-10-10")
                .param(CHECK_OUT, "2026-10-12")
                .param(ADULTS, "2")
                .param(CHILDREN, "0")
                .header(TRACE_HEADER, "failure-trace"))
        .andExpect(status().is(failure.status()))
        .andExpect(jsonPath(ERROR_CODE).value(failure.name()))
        .andExpect(jsonPath("$.traceId").value("failure-trace"))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void internalExceptionDoesNotLeakMessageAndInvalidTraceIsReplaced() throws Exception {
    when(service.search(any())).thenThrow(new NullPointerException("private-payload"));
    var result =
        mvc.perform(
                get(PATH)
                    .param(CHECK_IN, "2026-10-10")
                    .param(CHECK_OUT, "2026-10-12")
                    .param(ADULTS, "2")
                    .param(CHILDREN, "0")
                    .header(TRACE_HEADER, "invalid trace"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath(ERROR_CODE).value("INTERNAL_ERROR"))
            .andReturn();
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
        .doesNotContain("private-payload", "invalid trace");
    org.assertj.core.api.Assertions.assertThat(result.getResponse().getHeader(TRACE_HEADER))
        .matches("[0-9a-f-]{36}");
  }
}
