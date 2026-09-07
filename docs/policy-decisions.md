# Policy Decision Register

최종 갱신: 2026-09-07

서비스 동작에 영향을 주는 사용자 승인과 구현 과정의 구체화를 기록합니다. 현재 정책의 이유와 손실은 각 항목에서, 과거 제안 변경은 이력에서 확인할 수 있습니다.

## 승인 현황

| ID | 정책 | 상태 | 승인일 | 구현 / 검증 |
|---|---|---|---|---|
| POL-001 | 사라진 외부 상품 mapping의 소프트 삭제와 ID 유지 | Accepted | 2026-09-04 | catalog 구현·DB 테스트 완료 |
| POL-002 | 일부 Supplier catalog만 준비된 상태의 검색 허용 | Accepted | 2026-09-04 | 준비 상태 및 검색 HTTP 연계 구현·검증 완료 |
| POL-003 | 정규화 실패와 정상 빈 결과를 구분하는 검색 응답(C안) | Accepted | 2026-09-04 | S01~S16 및 HTTP 계약 검증 완료 |
| POL-004 | 공급사별 availability Circuit Breaker | Accepted | 2026-09-07 | 상태 전환·실제 HTTP 차단·복구 검증 |

이전 기술 스택과 가격 기준 등의 합의는 [ADR 목록](adr/README.md)과 [작업 기록](../JOURNAL.md)에 있습니다. 이 대장은 이번 정책 검토부터 승인 단위를 식별자로 추적합니다. 아래 승인 근거는 사용자 의견의 요약이며 발언 전문을 옮긴 것이 아닙니다.

## POL-001: 소프트 삭제

### 승인 내용

- 사용자는 삭제 대신 비활성화하는 방향을 소프트 삭제로 확인하고 수용했습니다.
- 정상적으로 검증된 전체 catalog snapshot에서 사라진 외부 상품 mapping을 `is_active=false`로 전환합니다.
- 내부 숙소/객실 ID와 외부 키 mapping은 물리 삭제하지 않습니다.
- 동일한 외부 키가 재등장하면 기존 mapping과 UUID를 재활성화합니다.

### 선택 이유와 대안

물리 삭제 후 재생성하면 동일한 상품의 ID가 달라집니다. 모든 과거 항목을 계속 활성 상태로 두면 현재 공급하지 않는 상품을 조회하게 됩니다. 비활성화는 식별자 연속성과 현재 검색 대상을 분리하는 선택입니다. 여기서 의미하는 소프트 삭제는 사용자 탈퇴나 개인정보 삭제가 아니라 Supplier listing의 제공 상태 관리입니다.

### 반드시 지킬 경계

- timeout, 본문 오류, 불완전한 snapshot을 빈 catalog로 해석해 일괄 비활성화하지 않습니다.
- 비활성 mapping도 외부 키 유일성 제약을 유지합니다. 비활성 레코드를 보지 못해 같은 키를 신규 생성하는 조회를 피합니다.
- 숙소 mapping이 비활성일 때 그 아래 객실도 검색 대상에서 제외합니다.
- 반복 sync와 재활성화에 대한 불변식은 DB 통합 테스트로 검증합니다.
- 삭제 시각이나 이력 테이블을 추가하는 상세 schema 변경까지 자동 승인된 것으로 보지 않습니다.

### 추적

- 설계: [ADR 0003](adr/0003-persist-stable-catalog-mappings.md), [도메인 모델](domain-model.md)
- 검증 계획: [Catalog 테스트](testing.md#catalog)
- 구현: [CatalogPersistenceService](../backend/src/main/java/io/github/samuel426/lodginghub/catalog/service/CatalogPersistenceService.java), [V1 schema](../backend/src/main/resources/db/migration/V1__create_catalog.sql)
- 실행된 검증: [CatalogIntegrationTest](../backend/src/test/java/io/github/samuel426/lodginghub/catalog/service/CatalogIntegrationTest.java)의 `missingStayDeactivatesParentAndRoomsAndReappearanceRestoresIds`, `missingRoomDoesNotDeactivateItsStayAndRoomReactivates`, `databaseFailureRollsBackWholeSupplierSnapshotAndSuccessTimestamp` 통과. 실제 JAR 재기동에서도 숙소·객실 UUID 유지 확인.

## POL-002: 카탈로그 부분 준비

### 승인 내용

- 사용자는 일부 Supplier catalog만 준비되어도 검색을 진행하고 누락 범위를 알리는 방향을 승인했습니다.
- 하나 이상의 Supplier에 성공한 snapshot이 있으면 준비된 범위로 검색할 수 있습니다.
- 성공 이력이 없는 Supplier는 `meta.unavailableCatalogSuppliers`에 표시하고 성공 응답이라면 `partial=true`로 알립니다.
- 모든 Supplier에 성공 이력이 없으면 `503 CATALOG_NOT_READY`를 반환합니다.
- 준비되었다는 사실과 활성 상품 개수를 구분합니다. 검증된 빈 catalog도 정상적인 성공 snapshot입니다.
- 기존 성공 snapshot이 있으면 refresh 실패에도 보존된 mapping으로 검색하며, 최신성 문제는 운영 지표와 로그로 관찰합니다.

### 선택 이유와 대안

모든 catalog의 준비를 강제하면 한 Supplier의 초기 장애가 다른 Supplier의 검색도 막습니다. 반대로 미준비 Supplier를 숨기면 전체 범위를 조회한 것으로 오인할 수 있습니다. 사용 가능한 범위와 누락 범위를 함께 반환합니다.

### 반드시 지킬 경계

- catalog 준비는 availability 성공을 보장하지 않습니다. 준비된 Supplier의 실시간 조회마저 실패한 경우는 검색 응답 정책으로 판정합니다.
- 이전 snapshot은 최신성 보장이 아닙니다. `partial=false`도 catalog가 방금 갱신되었다는 의미가 아닙니다.
- 정상 빈 snapshot과 실패를 명시적으로 구분하는 영속 상태가 필요합니다.

### 추적

- 설계: [ADR 0003](adr/0003-persist-stable-catalog-mappings.md), [아키텍처](architecture.md), [검색 API](api.md)
- 검증 계획: [Catalog 테스트](testing.md#catalog)
- 구현: [CatalogQueryService](../backend/src/main/java/io/github/samuel426/lodginghub/catalog/service/CatalogQueryService.java), [CatalogSnapshot](../backend/src/main/java/io/github/samuel426/lodginghub/catalog/dto/CatalogSnapshot.java)
- 실행된 검증: [CatalogIntegrationTest](../backend/src/test/java/io/github/samuel426/lodginghub/catalog/service/CatalogIntegrationTest.java)의 `distinguishesNeverReadyFromValidatedEmptyCatalog`, `failurePreservesOldSnapshotAndOtherSupplierStillCommits`, `timeoutDoesNotBlockOtherSupplierOrBecomeEmptySuccess` 통과.
- 검색 연결: 미준비 목록과 503/200 응답은 통합 검색 및 실제 HTTP 테스트에서 검증했습니다.

## POL-003: 정규화 실패 시 응답

### 승인 상태

사용자는 대안별 시나리오와 근거를 검토한 뒤 C안 추천대로 진행하도록 승인했습니다. 같은 요청에서 구현 시작도 허가했습니다. 유효한 관측 결과 기반의 200/부분 성공, 외부 데이터 불능 502, 이용 불가 및 catalog 문제 503, 내부 결함·설정 문제 500의 구분을 적용합니다.

### 제안 이력

| 날짜 | 제안 | 상태 / 이유 |
|---|---|---|
| 2026-09-03 | 본문을 해석한 batch가 있으면 전부 정규화 실패해도 200과 부분 실패 표시 | 이전 초안, 사용자 승인 없음 |
| 2026-09-04 | 유효한 관측 결과가 있으면 부분 성공, 전혀 없으면 원인에 맞는 5xx | C안 추천과 구현 시작 승인 |

기존 부분 성공 원칙은 유지하되, 사용 가능한 외부 데이터가 전혀 없는 경우를 `502 NO_VALID_SUPPLIER_DATA`로 세분화합니다. 검증된 정상 빈 batch, 정상 빈 catalog, 검증 후 품절로 제외된 상품은 유효한 관측으로 인정합니다. JSON 파싱 성공만으로 관측 성공을 판정하지 않습니다. 오류 혼합 시 우선순위는 [판정 순서](search-response-policy.md#5-최종-응답의-판정-순서)에 따릅니다.

### 검토 자료와 추적

- 대안, 추천, 시나리오와 HTTP 근거: [검색 응답 정책 검토](search-response-policy.md)
- 확정 계약: [ADR 0005](adr/0005-return-partial-search-results.md), [검색 API](api.md), [견고성 문서](resilience.md)
- 검증 계획: [Failure handling](testing.md#failure-handling)
- 구현: `StaySearchService`, `SearchAggregation`. 실행 검증: `StaySearchServiceTest.approvedScenariosS01ThroughS16`, `StaySearchControllerTest`, `SearchIntegrationTest`.

## POL-004: 반복 장애 공급사 차단

사용자는 필수 기능에 더해 문서를 설계 판단 중심으로 정리하고 Circuit Breaker 하나를 완성하도록 요청했습니다. 자동 재시도, cache, 다른 선택 기능과 최종 main 병합은 이 범위에 포함하지 않습니다.

같은 공급사 장애를 매 검색에서 계속 기다리는 비용을 줄이고, 정상 공급사 결과를 보존하는 것이 목적입니다. 그 대가로 복구 직후의 공급사 결과를 잠시 놓칠 수 있습니다. 차단 사실은 `CIRCUIT_OPEN`과 부분 성공 metadata에 공개하며, 유효한 관측이 없으면 기존 503 정책을 유지합니다.

공급사별 상태 분리, 일시적 이용 불가 오류만 집계, 제한된 복구 확인과 그 시간 상한을 구현했습니다. 최근 10회·실패율 50%·차단 10초·복구 확인 2회/최대 5초는 구현자가 선택한 검증 가능한 초기값입니다. 사용자가 각 수치를 개별 지정했거나 운영 지표로 최적화했다고 기록하지 않습니다.

- 판단과 대안: [ADR 0007](adr/0007-protect-supplier-availability.md)
- 동작·설정·손실·검증: [Circuit Breaker](circuit-breaker.md)

## 기록 규칙

1. 승인일, 승인 범위, 이유와 배제한 대안을 기록합니다. 제안일과 승인일을 혼동하지 않습니다.
2. 정책이 바뀌면 이전 이력과 변경 이유를 보존합니다. 승인 전 제안을 확정 사실로 기록하지 않습니다.
3. 코드와 테스트가 생기면 실제 경로를 연결합니다. 계획만 있는 테스트를 통과했다고 기록하지 않습니다.
4. 전체 설계 승인과 구현 시작 허가를 별도로 확인합니다.
