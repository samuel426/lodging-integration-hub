# Architecture Decision Records

ADR은 구현에 오래 영향을 주는 결정을 배경, 대안, 결과와 함께 보존합니다. 승인된 결정은 구현 중 임의로 바꾸지 않고, 변경이 필요하면 기존 문서를 수정하는 대신 새 ADR로 대체 관계를 기록합니다.

| ID | 결정 | 상태 |
|---|---|---|
| [0001](0001-use-spring-boot-4.md) | Spring Boot 4.0.8 사용 | Accepted |
| [0002](0002-use-mvc-with-webclient.md) | MVC와 WebClient 조합 | Accepted |
| [0003](0003-persist-stable-catalog-mappings.md) | 안정적인 catalog mapping 영속화 | Accepted - 정책 범위 |
| [0004](0004-normalize-gross-total-price.md) | 세금 포함 총액 기준 가격 정규화 | Accepted |
| [0005](0005-return-partial-search-results.md) | 부분 검색 결과 반환 | Proposed - Gate 2 |
| [0006](0006-avoid-automatic-cross-supplier-merge.md) | 공급사 간 자동 병합 제외 | Accepted |

상세 API 필드나 테이블 컬럼처럼 구현 과정에서 조정 가능한 내용은 설계 문서에서 관리하고, 시스템 경계를 바꾸는 선택만 ADR로 남깁니다.

승인 날짜와 범위는 [정책 결정 대장](../policy-decisions.md)에서 추적합니다. ADR 0005의 전부 정규화 실패 시 응답은 [대안 검토](../search-response-policy.md) 중이며 구현은 보류 상태입니다.
