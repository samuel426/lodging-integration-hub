# Testing Strategy

상태: 승인된 검증 계획 - 실행 여부는 단계별로 기록

## 목표

- 외부 계약 차이가 내부 모델에서 사라지는지 검증합니다.
- 매핑 ID와 데이터 무결성 규칙을 실제 PostgreSQL에서 검증합니다.
- 병렬 호출, 타임아웃, 부분 성공을 재현 가능한 방식으로 검증합니다.
- 테스트가 구현 세부사항보다 공개 계약과 도메인 규칙을 설명하도록 작성합니다.

## 테스트 계층

| 계층 | 대상 | 도구 |
|---|---|---|
| Unit | 가격 합산, 재고 최솟값, 날짜 검증, batch 분할 | JUnit 6 (Jupiter), AssertJ |
| Controller/Slice | query validation, 응답 envelope, 오류 매핑 | MockMvc |
| Repository Integration | unique constraint, upsert, 활성/비활성 전환 | Testcontainers PostgreSQL |
| Supplier Contract | HTTP 상태, 본문 실패, DTO 변환 | WireMock |
| Application Integration | catalog sync부터 통합 검색까지 | SpringBootTest, PostgreSQL, WireMock |
| Smoke | Compose 환경에서 health와 검색 API | script 또는 수동 명령 |

## 필수 시나리오

### Catalog

- 최초 동기화가 내부 숙소와 객실 타입 ID를 생성합니다.
- 같은 catalog를 다시 동기화해도 내부 ID가 유지됩니다.
- 이름과 수용 인원 변경은 ID 변경 없이 반영됩니다.
- 같은 객실 코드가 다른 숙소에 존재해도 충돌하지 않습니다.
- catalog에서 사라진 상품은 비활성화됩니다.
- 비활성 상품이 다시 등장하면 기존 ID로 재활성화됩니다.
- 한 Supplier 동기화 실패가 다른 Supplier 반영을 rollback하지 않습니다.
- 잘못된 catalog 응답은 기존 매핑을 변경하지 않습니다.
- 정상적인 빈 catalog는 성공 상태를 기록하고 준비 완료로 판단합니다.
- 모든 Supplier가 첫 동기화에 실패한 경우에만 catalog 미준비로 판단합니다.
- 일부 Supplier만 준비되면 검색을 계속하고 미준비 Supplier 목록과 `partial=true`를 반환합니다.

### Price normalization

- 일자별 net 금액과 세액을 전체 gross 금액으로 합산합니다.
- 전체 gross 금액은 그대로 보존합니다.
- 세액을 알 수 없을 때 `null`을 유지합니다.
- 일자별 금액이 없을 때 균등 분배하지 않습니다.
- 음수 금액과 알 수 없는 통화 코드를 거부합니다.
- 가격 합산의 `long` overflow를 감지하고 잘못된 offer로 처리합니다.
- 일자별 세금 포함 금액의 합계가 총액과 일치합니다.
- 서로 다른 통화를 임의로 합산하지 않습니다.

### Inventory normalization

- N박 재고는 모든 숙박일의 최솟값입니다.
- 하루라도 0이면 품절 처리합니다.
- 요청 날짜가 누락되면 offer를 제외합니다.
- 중복 날짜가 있으면 잘못된 응답으로 처리합니다.
- 잘못된 offer를 제외해도 같은 batch의 유효한 offer는 유지하고 거절 건수를 반환합니다.
- 체크아웃 날짜의 재고는 계산에 포함하지 않습니다.
- 요청 인원이 최대 수용 인원을 초과하면 offer를 제외합니다.

### Batch and concurrency

- 0개는 batch를 생성하지 않으며 외부 호출도 없습니다.
- 1, 49, 50개는 한 batch입니다.
- 51개는 50과 1로 나뉩니다.
- 101개는 50, 50, 1로 나뉩니다.
- 동시 실행 수가 설정값 4를 넘지 않습니다.
- 같은 Supplier의 한 batch 실패가 다른 batch 성공을 제거하지 않습니다.

### Failure handling

2026-09-04: POL-003 C안 승인에 따라 [응답 정책 시나리오 S01~S16](search-response-policy.md)의 기대값을 확정했습니다. 검색 테스트는 아직 작성하거나 실행하지 않았습니다.

- Supplier의 HTTP 4xx/5xx를 공통 실패로 변환합니다.
- HTTP와 본문 수준의 인증 실패를 동일한 `AUTHENTICATION_ERROR`로 변환합니다.
- Supplier 인증·요청 거부를 고객의 400/401 응답으로 잘못 전달하지 않습니다.
- 격리 가능한 Supplier 인증 실패와 다른 Supplier의 정상 결과가 함께 있으면 부분 성공을 유지합니다.
- HTTP 성공 상태의 본문 실패를 정상으로 오인하지 않습니다.
- 연결 실패를 `CONNECTION_ERROR`로 분류합니다.
- 지연 응답을 제한 시간 후 `TIMEOUT`으로 분류합니다.
- 본문을 조금씩 지속해서 전송하는 경우에도 batch deadline을 넘기지 않습니다.
- 일부 성공은 HTTP 200과 `partial=true`를 반환합니다.
- 유효한 관측 결과가 남는 개별 offer 정규화 실패는 HTTP 200, `partial=true`, 거절 건수를 반환합니다.
- 모든 offer가 외부 계약 위반이고 정상 빈 결과도 없으면 502를 반환합니다.
- 정상 빈 batch 또는 검증된 품절 상품과 오류가 섞여도 반환 상품 수만으로 전체 실패를 판정하지 않는지 검증합니다.
- 필수 필드 누락과 허용된 nullable 필드, 내부 mapper 결함과 외부 데이터 계약 오류를 구분합니다.
- 같은 입력에서 batch 완료 순서에 따라 상태 코드와 실패 집계가 바뀌지 않는지 검증합니다.
- 일부/전체 rate limit을 각각 부분 성공과 전체 이용 불가로 구분합니다.
- 모든 외부 호출이 이용 불가이면 503을 반환합니다. 내부 결함·설정 오류와 catalog mapping 문제는 C안의 별도 분류를 검증합니다.
- 전부 성공했지만 상품이 없으면 HTTP 200과 빈 결과를 반환합니다.

### API validation

- 필수 query 누락
- 날짜 형식 오류
- `checkOut <= checkIn`
- `adults < 1`
- `children < 0`
- field error와 traceId 포함 여부

## 2026-09-04 Catalog 구현 검증

전체 61건 통과, 실패·오류·skip 0건입니다. 전체 테스트를 캐시 없이 다시 실행해 같은 결과를 확인했습니다. 검색 API, 가격·재고, batch 병렬성과 S01~S16은 아직 구현하지 않았으며 아래 수치에 포함되지 않습니다.

| Suite | 건수 | 검증 대상 |
|---|---:|---|
| `CatalogIntegrationTest` | 39 | PostgreSQL 원자성·ID·준비 상태 및 WireMock HTTP 계약 |
| `SupplierCatalogTest` | 9 | 전체 snapshot 검증, 외부 키 범위, 불변 목록 |
| `CatalogSyncServiceTest` | 4 | 실패 격리, 내부 결함 전파, metric 판정 |
| `SupplierClientPropertiesTest` | 7 | 설정 검증과 문자열 표현의 비밀값 비노출 |
| `SupplierRoomTypeMappingTest` | 1 | 다른 숙소 소유 객실의 mapping 생성 거부 |
| `LodgingIntegrationHubApplicationTests` | 1 | Flyway/JPA/PostgreSQL 컨텍스트 |

[Catalog 통합 테스트 코드](../backend/src/test/java/io/github/samuel426/lodginghub/catalog/service/CatalogIntegrationTest.java)는 실제 PostgreSQL과 WireMock 컨테이너를 사용합니다. 테스트마다 자체 DB 테이블과 자체 mock을 초기화하며 로컬 Compose DB를 건드리지 않습니다. 공개 Controller는 아직 없어 이 단계에 Controller/Slice 테스트는 해당하지 않습니다.

JaCoCo production line coverage는 442/460(96.1%), branch coverage는 130/158(82.3%)입니다. 현재 존재하는 코드의 수치이며 전체 제품 기능 완성도가 아닙니다. 실행 JAR smoke는 계측 테스트 수치에 포함하지 않습니다.

실행 JAR smoke: health UP, 두 Supplier sync 성공, 숙소 2개·객실 3개 저장. 동일 DB 재시작 뒤 숙소·객실 mapping UUID 유지 확인. 자세한 재현 명령은 [Catalog 운영 문서](catalog-sync.md)를 참고합니다.

## 테스트 데이터 원칙

- 외부에서 제공된 예시를 그대로 복사하지 않고 계약 구조만 만족하는 독립 fixture를 만듭니다.
- 숙소명, 객실명, 코드와 금액은 테스트 목적이 드러나는 값으로 구성합니다.
- 하나의 fixture가 여러 규칙을 동시에 검증하지 않게 최소화합니다.
- 시간 의존 로직에는 `Clock`을 주입합니다.

## 실행 명령

```bash
cd backend
./gradlew spotlessCheck test
```

테스트 결과:

- HTML: `backend/build/reports/tests/test/index.html`
- JaCoCo HTML: `backend/build/reports/jacoco/test/html/index.html`
- JaCoCo XML: `backend/build/reports/jacoco/test/jacocoTestReport.xml`

## 완료 기준

- 새 도메인 규칙에 Unit 테스트가 있습니다.
- 새 API 동작에 Controller 또는 계약 테스트가 있습니다.
- DB 제약 및 외부 HTTP 경계는 실제 통합 테스트로 검증합니다.
- 정상 경로와 최소 하나 이상의 실패 경로가 함께 있습니다.
- 전체 테스트와 Spotless가 로컬 및 CI에서 통과합니다.
