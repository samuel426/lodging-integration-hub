# 구현 현황과 제출 절차

필수 검색 흐름과 관측 기능은 PR #1~#5를 통해 `dev`에 반영했습니다. 현재 추가 범위는 설계 판단 중심 문서 정리와 공급사별 Circuit Breaker입니다.

## 구현 현황

| 범위 | 결과 | 검증 근거 |
|---|---|---|
| 실행 기반 | Java 21, Spring Boot, PostgreSQL/Flyway, WireMock | context, schema, 빌드 검사 |
| Catalog | 안정적 UUID, 비활성화·재활성화, 공급사별 원자적 동기화 | [Catalog 문서](catalog-sync.md), DB·HTTP 테스트 |
| Availability | 가격·재고 정규화, 50개 분할, 타임아웃과 실패 분류 | [어댑터 문서](supplier-adapters.md), 계약 테스트 |
| 통합 검색 | 요청당 동시성 4, 내부 ID 연결, 유효 관측 기반 부분 성공 | [응답 정책](search-response-policy.md), S01~S16·Controller·HTTP 테스트 |
| 관측·품질 | trace 전파, 지표, 안전한 로그, OpenAPI, CI | [품질 기록](quality.md), 로컬 smoke |
| Circuit Breaker | 공급사별 차단, 제한된 복구 확인, 차단 상태 노출 | [설계와 검증](circuit-breaker.md), 상태·실제 HTTP 테스트 |

## 이번 추가 기능의 선택 이유

반복 장애에서도 매번 동일한 외부 응답을 기다리는 문제를 줄이는 데 집중했습니다. Circuit Breaker는 기존 타임아웃·부분 성공 정책을 확장하며 정상 공급사의 결과를 유지할 수 있습니다. 대신 공급사가 복구된 직후에도 재조회까지 기다려야 하는 시간이 생깁니다.

자동 재시도는 지연과 외부 부하를 늘릴 수 있고, cache는 가격·재고의 오래된 값 허용 정책이 필요합니다. 이번에는 함께 넣지 않습니다. [미구현 확장과 한계](extensions.md)는 현재 기능과 구분해 기록합니다.

## 결정과 병합 경계

| 단계 | 상태 |
|---|---|
| Gate 1: 기술 스택·주요 정책 | 승인 완료 |
| Gate 2: 도메인·API·장애 정책과 구현 시작 | 2026-09-04 승인 완료 |
| Gate 3: 선택 기능 | 2026-09-07 문서 정리와 Circuit Breaker 구현 승인 |
| Gate 4: 제출본 최종 검토 | 품질 결과와 문서를 검토한 뒤 `dev → main` 최종 병합 승인 필요 |

세부 차단 설정값은 운영 실측에 기반한 확정 정책이 아니라 구현·검증용 초기값입니다. 사용자 승인 범위와 구체화 과정은 [정책 대장](policy-decisions.md), [작업 기록](../JOURNAL.md), [AI 활용 기록](ai-usage.md)에서 추적합니다.

## 제출 전 확인

- README의 명령으로 실행하고 Swagger에서 실제 검색 API를 호출합니다.
- 정상·부분 장애·차단·복구 동작을 실행 JAR로 확인합니다.
- 전체 테스트, 포맷, 정적 분석, 빌드, 비밀정보 및 실행 JAR 취약점 검사를 통과합니다.
- 문서의 현재 동작과 미구현 범위를 코드·OpenAPI와 맞춥니다.
- 기능 PR을 `dev`로 squash merge하고, 최종 검토 후 `main`에 제출본을 반영합니다.
