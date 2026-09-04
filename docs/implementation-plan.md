# Implementation Plan

## 승인 절차

| Gate | 검토 대상 | 구현 진행 조건 |
|---|---|---|
| Gate 1 | 기술 스택, 주요 도메인 정책 | 승인 완료 |
| Gate 2 | ERD, API, 패키지, 장애 정책 | POL-001/002 승인. POL-003 및 전체 검토 대기. 구현 시작은 별도 승인 필요 |
| Gate 3 | 필수 검색 흐름과 테스트 결과 | 사용자 승인 후 선택 기능 결정 |
| Gate 4 | README, Journal, 품질 검사 결과 | 승인 후 최종 병합 |

## Phase 0 - Repository foundation

상태: 진행 중

- [x] 저장소 규칙 작성
- [x] `main`, `dev`, feature branch 준비
- [x] Java 21 및 Spring Boot 4.0.8 설정
- [x] PostgreSQL, Flyway, JPA 구성
- [x] WireMock Compose 구성
- [x] Testcontainers context test
- [x] Spotless와 JaCoCo
- [x] 소프트 삭제와 catalog 부분 준비 정책 승인 기록
- [ ] 정규화 실패 응답 정책 확정
- [ ] Gate 2 설계 승인
- [ ] 사용자 구현 시작 허가

2026-09-04 사용자가 구현 보류를 명시했습니다. [정책 대장](policy-decisions.md)과 [응답 정책 비교](search-response-policy.md)를 검토하며, 도메인 코드 작성과 PR 생성·병합은 진행하지 않습니다.

예상 커밋:

```text
🚀 infra: 백엔드 실행 기반 구성
📝 docs: 통합 검색 설계와 결정 기록
```

## Phase 1 - Catalog mapping

브랜치: `feat/catalog-sync`

- Flyway V1 schema
- Stay와 RoomType Entity
- Supplier stay/room mapping Entity
- Supplier catalog sync state Entity
- 외부 catalog 공통 모델과 client port
- Supplier A/B catalog adapter
- Supplier 단위 upsert와 비활성화
- startup synchronization
- idempotency 및 실패 격리 테스트
- catalog metric과 로그

완료 조건:

- 반복 sync에서 내부 UUID가 유지됩니다.
- 정상적인 빈 catalog와 첫 동기화 실패가 구분됩니다.
- 객실 외부 키의 숙소 범위 유일성이 DB에서 보장됩니다.
- Supplier 하나의 실패가 다른 Supplier 반영을 막지 않습니다.
- 외부 호출 중 DB 트랜잭션이 열리지 않습니다.

## Phase 2 - Supplier availability adapters

브랜치: `feat/supplier-adapters`

- WebClient 공통 설정
- connect/response timeout
- API key header 처리
- Supplier별 request/response DTO
- HTTP 및 본문 실패 판정
- 가격과 재고 정규화
- 50개 batch 분할
- WireMock 정상, 오류, 무응답 fixture
- Supplier 계약 테스트

완료 조건:

- Supplier DTO가 adapter 외부로 노출되지 않습니다.
- 서로 다른 가격 형식이 동일한 total gross 의미를 가집니다.
- timeout과 두 종류의 실패 표현을 공통 결과로 변환합니다.
- fixture를 이용해 장애 모드를 재현할 수 있습니다.

## Phase 3 - Unified search

브랜치: `feat/unified-search`

- 검색 query validation
- 활성 mapping 조회 projection
- Supplier와 batch 단위 병렬 orchestration
- concurrency 4 제한
- 내부 UUID 연결
- 품절 offer 제외
- 부분 성공 metadata
- 개별 offer 거절 건수
- 미준비 Supplier catalog 목록
- 전체 실패 503
- Controller/Slice 및 통합 테스트
- OpenAPI 문서

완료 조건:

- 정상 검색이 모든 Supplier의 offer를 반환합니다.
- 한 Supplier가 무응답이어도 제한 시간 안에 다른 결과를 반환합니다.
- 일부 batch 실패에도 성공 batch 결과가 유지됩니다.
- 응답에 외부 식별자가 노출되지 않습니다.

## Phase 4 - Observability and quality

브랜치: `feat/integration-observability`

- traceId filter와 응답 연계
- Supplier별 호출 횟수, 성공률, 지연, timeout metric
- catalog sync metric
- structured logging field 정리
- GitHub Actions test/build/format
- secret scan
- 전체 문서와 실행 예시 갱신

## 선택 기능 결정

Gate 3에서 필수 흐름의 완성도와 남은 시간을 확인한 뒤 선택합니다.

우선순위:

1. Resilience4j retry와 circuit breaker
2. 정규화 실패 데이터 격리
3. 요금/재고 cache 설계
4. 서로 다른 통화 처리 설계
5. 중복 숙소 matching 설계
6. 예약 생성/취소 및 보상 설계

필수 흐름의 테스트와 문서가 부족하면 선택 기능을 구현하지 않고 설계만 남깁니다.

현재 고려 중인 선택 기능의 경계와 위험은 [확장 설계](extensions.md)에 정리합니다. 이 문서는 해당 기능의 구현 승인을 의미하지 않습니다.

## 최종 완료 조건

- README 명령으로 새로운 환경에서 실행할 수 있습니다.
- OpenAPI와 실제 Controller 계약이 일치합니다.
- 설계 문서의 결정이 코드와 테스트에 반영됩니다.
- test, format, build, secret scan이 통과합니다.
- 외부 원문 문서, 실제 secret, 특정 조직 식별 정보가 저장소에 없습니다.
- JOURNAL과 AI 활용 기록에 수용, 수정, 거부한 판단이 남아 있습니다.
