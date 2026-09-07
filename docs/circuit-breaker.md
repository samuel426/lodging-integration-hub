# Supplier Circuit Breaker

## 해결하려는 문제와 선택

공급사 하나의 장애가 지속되면 검색마다 같은 실패를 기다리게 됩니다. 정상 공급사의 상품을 제공하면서도 불필요한 실패 호출을 줄이기 위해 실시간 availability에 공급사별 차단 상태를 둡니다.

타임아웃만 사용하면 매 요청이 외부 연결을 다시 시도합니다. 재시도는 일시적 실패를 복구할 수 있지만, 지속 장애에서는 호출량과 대기 시간을 늘립니다. 이번 확장에서는 재시도 없이 Circuit Breaker를 선택했습니다.

감수하는 손실은 복구 직후의 상품 누락입니다. 실제 공급사가 복구돼도 대기 시간과 시험 조회가 끝나기 전까지 해당 범위가 차단될 수 있습니다. 따라서 차단 여부를 고객에게 숨기지 않고 부분 실패 정보에 포함합니다.

## 범위와 상태

- 공급사별로 하나의 상태를 **애플리케이션 인스턴스 안의 모든 검색 요청**이 공유합니다.
- A와 B는 독립적이며 catalog 동기화에는 적용하지 않습니다.
- CLOSED: 외부 호출을 허용하고 최근 batch 결과를 집계합니다.
- OPEN: HTTP 요청을 보내지 않고 `CIRCUIT_OPEN`을 반환합니다.
- HALF_OPEN: 대기 시간 이후 들어온 검색으로 제한된 복구 조회를 수행합니다. 허용량을 넘는 호출도 `CIRCUIT_OPEN`입니다.
- 복구 조회의 실패율이 기준 미만이면 CLOSED, 기준 이상이면 OPEN입니다. 충분한 관측 없이 오래 머무르면 다시 OPEN이 됩니다.

이미 실행 중인 호출을 소급 취소하지 않습니다. 배치 완료 순서가 차단 시점에 영향을 줄 수 있으므로, 차단 경계의 동시 요청 수가 항상 동일하다고 보장하지 않습니다. 이미 얻은 성공 결과는 보존합니다.

## 무엇을 실패로 세는가

| 결과 | 차단 실패율 반영 | 이유 |
|---|---|---|
| TIMEOUT, CONNECTION_ERROR, UPSTREAM_ERROR, RATE_LIMITED | 실패 | 현재 공급사 이용이 어렵거나 호출량을 줄여야 하는 신호 |
| 정상 상품 또는 명시적 빈 batch | 성공 | 정상 계약의 응답을 확인 |
| 일부 상품 거절 + 정상 상품 존재 | 성공 | 호출 가용성은 확인됐고 데이터 품질 문제는 거절 지표로 분리 |
| AUTHENTICATION_ERROR, INVALID_REQUEST | 제외 | 차단으로 설정 문제를 해결하거나 정상 복구로 오인하지 않음 |
| INVALID_RESPONSE, 상품 전부 거절 | 제외 | 유효한 응답인지 확인하지 못했으며 일시적 장애라고 단정하지 않음 |
| 예상하지 못한 예외, 빈 publisher | 제외 후 내부 오류 전파 | 코드 결함을 공급사 장애로 숨기지 않음 |
| 취소 | 성공·실패로 기록하지 않고 시험 허가 반환 | 관측하지 않은 결과를 만들어 넣지 않음 |

제외한 결과는 정상 복구의 근거로도 사용하지 않습니다. HALF_OPEN에서 제외 결과만 이어지면 최대 대기 후 다시 차단합니다. `RATE_LIMITED`를 반영하지만 Retry-After에 따른 재시도나 Rate Limiter를 구현한 것은 아닙니다.

## 초기 설정과 근거

`suppliers.circuit-breaker.*`는 [application.yml](../backend/src/main/resources/application.yml)에서 관리하며 잘못된 값은 시작 시 거부합니다.

| 설정 | 초기값 | 의도·한계 |
|---|---:|---|
| sliding-window-size | 10 batch | 최근 호출을 기준으로 재현 가능한 작은 창 사용 |
| minimum-number-of-calls | 10 | 한두 번의 실패로 즉시 차단하지 않음 |
| failure-rate-threshold | 50% | 최소 표본 확보 후 실패가 절반 이상인 상태를 차단 |
| wait-duration-in-open-state | 10초 | 즉시 반복 호출을 멈추되 로컬 복구 확인은 짧게 유지 |
| permitted-calls-in-half-open-state | 2 | 한 번의 우연한 성공만으로 전체 호출을 재개하지 않음 |
| max-wait-duration-in-half-open-state | 5초 | 시험 호출 부족·취소·제외 결과로 복구 상태가 무한 유지되는 것을 방지 |

이 값은 mock 환경에서 기능을 확인하기 위한 초기값입니다. 실제 운영 최적값이나 SLA에서 산출한 수치가 아닙니다. 운영에서는 표본량, 평시 실패율, 복구 시간과 요청량을 측정해 조정해야 합니다. 정상적인 느린 호출을 별도로 차단하기보다 기존 HTTP deadline의 실패 분류를 사용합니다.

표본 단위는 검색 요청 수가 아닌 **공급사 batch 수**입니다. 큰 검색 한 번으로 표본을 채울 수도 있습니다. Circuit Breaker는 전체 호출량 상한, 전역 bulkhead 또는 검색 전체 deadline을 대신하지 않습니다. 상태는 메모리에 있으며 재시작 시 초기화되고 다른 인스턴스와 공유되지 않습니다.

## 공개 API와 관측

HTTP 상태와 응답 구조는 기존 계약을 유지합니다. 실패 category에 `CIRCUIT_OPEN`을 추가했습니다. enum을 고정한 소비자는 이 값의 처리가 필요합니다. 현재 API 구현 단계에서는 `/api/v1`을 유지하며 추가되는 실패 분류를 같은 변경의 OpenAPI·계약 테스트에서 확인합니다.

- 유효한 관측 + 일부 차단: 200, `partial=true`, 실패 batch 요약.
- 유효한 관측 없이 모든 공급사 차단: 503 `ALL_SUPPLIERS_UNAVAILABLE`.
- 정상 빈 catalog도 유효 관측이므로 실제로 호출할 대상이 없는 경우는 별도 정상 처리합니다.
- `supplier.availability.duration`은 시도 단위 Timer이며 CIRCUIT_OPEN은 실제 HTTP가 없는 차단 시도입니다.
- `resilience4j.circuitbreaker.state`: name·state별 상태 gauge.
- `resilience4j.circuitbreaker.not.permitted.calls`: 외부 호출을 허용하지 않은 횟수.
- `supplier.circuit.transitions`: supplier·transition별 상태 전환 횟수와 같은 필드의 로그.

조회 예: `/actuator/metrics/resilience4j.circuitbreaker.state`, `/actuator/metrics/supplier.circuit.transitions`.

## 검증과 로컬 재현

`SupplierCircuitBreakerTest`는 최소 표본·실패율·공급사 격리·허용되지 않은 호출 미실행·제외 오류·내부 예외·복구·취소·시험 동시성·최대 대기를 검증합니다. `SearchIntegrationTest`는 WireMock 요청 이력으로 차단 중 HTTP 미전송을 확인하고 실제 대기 후 복구, 부분 200·전체 503·OpenAPI를 검사합니다.

앱과 Compose를 실행한 뒤 PowerShell에서 저장소 루트의 `./scripts/circuit-smoke.ps1`을 실행합니다. 스크립트는 정상 기준을 확보하고 B 오류를 반복해 차단·부분 응답을 확인한 뒤 mock을 복구합니다. 기본 대기 10초를 포함하며 마지막에 정상 검색으로 돌아오는지 확인합니다. 다른 터미널에서 동시에 장애 시나리오를 바꾸지 않아야 재현 결과를 비교할 수 있습니다.

## 라이브러리 선택

Resilience4j 2.4.0의 circuitbreaker·reactor·micrometer 모듈을 사용합니다. 기존 MVC·WebClient 경계를 유지하며 Spring Boot 전용 starter나 AOP annotation 없이 구독 시점에 허가를 검사합니다. 검증된 상태 전이와 취소 처리를 활용하고, 우리 서비스에서는 어떤 결과를 가용성 실패로 해석할지에 집중합니다.

참고: [공식 Circuit Breaker 설명](https://resilience4j.readme.io/docs/circuitbreaker), [2.4.0 릴리스](https://github.com/resilience4j/resilience4j/releases/tag/v2.4.0), [Reactor 취소·결과 처리 구현](https://github.com/resilience4j/resilience4j/blob/v2.4.0/resilience4j-reactor/src/main/java/io/github/resilience4j/reactor/circuitbreaker/operator/CircuitBreakerSubscriber.java).
