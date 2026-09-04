# Catalog Synchronization

## Scope

Supplier A/B의 정적 숙소·객실 정보를 저장합니다. 실시간 요금·재고와 검색 API는 이 단계의 구현 범위가 아닙니다. 시작 시 단일 인스턴스에서 한 번 동기화하며 주기 작업이나 관리자 동기화 API는 제공하지 않습니다.

## Transaction and identity rules

1. `StartupCatalogSynchronizer`가 `CatalogSyncService`를 호출합니다.
2. 외부 HTTP와 전체 snapshot 검증을 DB 트랜잭션 밖에서 완료합니다. `Propagation.NEVER`로 기존 트랜잭션 안에서 잘못 호출하는 것도 차단합니다.
3. `CatalogPersistenceService.apply`는 Supplier별 독립 트랜잭션에서 비활성 mapping까지 조회합니다.
4. 기존 외부 키는 ID를 유지하고 이름·수용 인원을 갱신합니다. 신규 키에만 UUID를 생성합니다.
5. 정상 전체 목록에서 누락된 숙소/객실 mapping은 비활성화합니다. 숙소 누락 시 하위 객실도 비활성화합니다. 재등장 시 기존 UUID를 재사용합니다.
6. mapping 변경과 성공 시각을 같은 트랜잭션으로 커밋합니다. 실패하면 모두 rollback합니다.
7. 알려진 실패의 시도 시각·분류만 별도 짧은 트랜잭션에 기록합니다. 기존 mapping과 성공 시각은 보존하고 다음 Supplier를 진행합니다.

숙소 키는 `(supplier, externalStayCode)`, 객실 키는 `(supplierStayMappingId, externalRoomTypeCode)`이며 비활성 항목에도 unique constraint를 유지합니다. 서로 다른 Supplier의 동일 이름·코드는 합치지 않습니다.

처음부터 검증된 빈 목록은 성공입니다. `last_succeeded_at`이 있는 상태와 활성 상품 수 0개는 모순이 아닙니다. 조회용 `CatalogSnapshot`은 Entity를 포함하지 않는 불변 DTO이며, 여러 SQL이 하나의 snapshot을 관찰하도록 PostgreSQL `REPEATABLE_READ` 읽기 트랜잭션을 사용합니다.

## Validation boundaries

- 필수 목록 누락, `null`, null 항목, 중복 외부 키는 snapshot 전체 실패입니다.
- 객실 코드 중복은 같은 숙소 안에서만 검사합니다.
- 코드 128자, 이름 255자 이내이며 공백뿐인 값과 제어 문자를 거부합니다. 키를 임의로 trim/변환해 다른 키와 합치지 않습니다.
- `maxOccupancy`는 필수 양의 32-bit 정수입니다. 소수·문자열·overflow를 자동 변환하지 않습니다.
- 빈 숙소 목록과 빈 객실 목록은 명시적으로 전달된 경우 허용합니다.
- 추가 필드는 호환성을 위해 무시하지만 필수 필드의 누락을 기본값으로 보충하지 않습니다.
- HTTP 오류, B의 HTTP 200 본문 오류, 빈 본문, 잘못된 JSON, 과대 본문은 실패입니다.
- redirect를 따라가지 않아 다른 호스트로 API key를 전달하지 않습니다.

검증된 정상 snapshot만 전체 목록으로 신뢰합니다. 외부 서버가 일부 항목을 조용히 누락하면서 정상 응답을 반환한 경우까지 탐지할 수는 없습니다. 현재 계약은 전체 목록이며 pagination이나 완전성 토큰이 없다는 한계가 있습니다.

## Local operation

```bash
docker compose up -d
cd backend
./gradlew bootRun
```

Windows에서는 `.\gradlew.bat bootRun`을 사용합니다. 기본 fixture는 숙소 2개와 객실 3개를 만듭니다. 같은 DB로 애플리케이션을 다시 시작해도 mapping ID는 유지됩니다. 애플리케이션 재시작은 동기화를 다시 실행하지만 DB volume 삭제는 ID 연속성을 없앱니다.

저장소 루트에서 읽기 전용으로 상태를 확인합니다.

```bash
docker compose exec -T postgres psql -U lodging -d lodging_hub -c "select supplier, last_attempted_at, last_succeeded_at, last_failure_category from supplier_catalog_sync_state order by supplier;"
docker compose exec -T postgres psql -U lodging -d lodging_hub -c "select supplier, external_stay_code, stay_id, is_active from supplier_stay_mapping order by supplier, external_stay_code;"
```

외부 URL과 실제 키를 사용하지 않는 로컬 fixture 환경입니다. 프로세스 환경변수는 [.env.example](../.env.example)을 참고합니다. `.env`를 Spring Boot가 자동으로 읽지는 않습니다. 설정 객체의 문자열 표현에는 URL·키를 출력하지 않습니다.

## Failures and observability

- `supplier.catalog.sync`: Supplier와 outcome별 결과 수
- `supplier.catalog.sync.duration`: HTTP·검증·DB 반영을 포함한 동기화 시간
- `supplier.catalog.state.failures`: 실패 상태를 저장하는 DB 작업마저 실패한 횟수
- 로그: Supplier, operation, outcome, durationMs. 외부 응답·키·driver 오류 메시지를 직접 기록하지 않음

outcome은 `SUCCESS`, 공통 Supplier 실패 분류, `PERSISTENCE_ERROR`, `INTERNAL_ERROR`입니다. 예상하지 못한 코드 결함을 외부 장애나 성공으로 바꾸지 않으며 시작 시 발생하면 기동을 실패시킵니다. DB 장애로 실패 상태를 저장하지 못하면 이전 상태가 남을 수 있어 별도 오류 로그·metric을 확인합니다.

성공 snapshot이 하나라도 있으면 catalog query의 `isReady=true`입니다. 이것은 검색 API 구현 완료나 availability 성공 보장이 아닙니다. 미준비 Supplier 목록을 검색 metadata에 연결하는 작업은 검색 단계에 남아 있습니다. 기존 snapshot의 refresh 실패는 미준비 목록에 넣지 않습니다.

## Limits and next work

- 다중 인스턴스 동시 동기화에 대한 분산 락·version ordering은 없습니다. 동시에 같은 DB에 startup sync를 실행하지 않습니다.
- 한 Supplier catalog는 전체 메모리에 적재하며 본문 4MiB 상한이 있습니다. 대규모 catalog에는 pagination/staging 및 측정된 제한 조정이 필요합니다.
- Supplier별 조회와 JPA fetch join으로 항목별 N+1 조회를 피하지만 쓰기는 현재 단순 upsert입니다. 수천 항목 처리 시간과 JDBC batching은 아직 부하 검증하지 않았습니다.
- timeout은 HTTP operation의 제한이며 모든 Supplier 동기화나 전체 검색의 2초 SLA가 아닙니다.
- outbound correlation header는 생성·보존하지만 inbound trace 연계와 구조화 로그 완성은 관찰 가능성 단계에서 진행합니다.
- catalog 활성 mapping gauge, freshness alert, 주기 refresh, retry, circuit breaker는 미구현입니다.

관련 정책: [POL-001/002](policy-decisions.md), 검증: [테스트 전략](testing.md).
