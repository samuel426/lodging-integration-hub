package io.github.samuel426.lodginghub.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
  public static final String HEADER = "X-Correlation-Id";
  public static final String TRACE_ID = "traceId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String trace =
        supplied != null && supplied.matches("[A-Za-z0-9_-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    request.setAttribute(TRACE_ID, trace);
    response.setHeader(HEADER, trace);
    String previous = MDC.get(TRACE_ID);
    MDC.put(TRACE_ID, trace);
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove(TRACE_ID);
      } else {
        MDC.put(TRACE_ID, previous);
      }
    }
  }
}
