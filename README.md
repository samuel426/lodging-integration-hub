# Lodging Integration Hub

서로 다른 숙박 공급사의 상품을 하나의 검색 API로 조회하는 백엔드입니다. 가격과 재고를 같은 의미로 해석하고, 일부 공급사를 조회하지 못했을 때 그 사실을 결과와 함께 전달하는 것을 목표로 합니다.

## 제공하는 기능

- 공급사 A/B 카탈로그 동기화, 숙소·객실의 안정적인 내부 UUID
- 전체 숙박 기간의 세금 포함 금액과 연박 가능 재고 정규화
- 날짜·인원 검증, 50개 단위 배치, 검색 요청당 최대 4개 병렬 호출
- 정상 결과를 보존하는 부분 성공과 원인별 실패 응답
- 공급사별 Circuit Breaker, 추적 ID, 호출·검색 지표와 안전한 로그
- PostgreSQL·WireMock 통합 테스트, OpenAPI와 GitHub Actions 품질 검사

실제 외부 서비스 대신 독립적으로 작성한 WireMock fixture로 연동을 재현합니다. 인증·결제·예약·프론트엔드는 구현 범위에 포함하지 않습니다. 제출 검토용 구현은 `dev`를 기준으로 확인하며, 최종 검토 후 `main`에 반영합니다.

## 빠른 실행

JDK 21, Docker Engine/Desktop과 Compose가 필요합니다.

```bash
git clone https://github.com/samuel426/lodging-integration-hub.git
cd lodging-integration-hub
git switch dev
docker compose up -d
cd backend
./gradlew bootRun
```

Windows에서는 마지막 명령을 `.\gradlew.bat bootRun`으로 실행합니다. 시작 시 공급사별 카탈로그를 동기화합니다.

| 확인 대상 | 주소 |
|---|---|
| Swagger | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |
| 로컬 mock 관리 | http://localhost:9090/__admin/mappings |

다른 터미널에서 검색합니다.

```bash
curl "http://localhost:8080/api/v1/stays/search?checkIn=2026-10-10&checkOut=2026-10-12&adults=2&children=0"
```

정상 fixture는 A **220000 KRW**, B **236000 KRW**의 두 상품과 `partial=false`를 반환합니다. 예시 날짜는 fixture에 고정돼 있습니다. UUID는 최초 동기화에서 생성되고 같은 DB를 사용하는 동안 유지됩니다.

Windows PowerShell 또는 PowerShell 7에서 저장소 루트의 `./scripts/smoke.ps1`을 실행하면 정상 금액·OpenAPI·부분 timeout·본문 오류·전체 실패를 확인하고 mock을 정상 상태로 복구합니다. 차단·복구 재현은 [Circuit Breaker 운영 안내](docs/circuit-breaker.md)를 따릅니다.

종료할 때는 `bootRun` 터미널에서 **Ctrl+C**로 앱을 먼저 종료하고, 저장소 루트에서 `docker compose down`으로 인프라를 종료합니다. DB volume은 보존됩니다.

## 요구사항을 해석한 기준

가격의 구성이나 외부 오류 표현이 달라도 검색 결과의 의미는 같아야 합니다. 다음은 공급사 계약과 검색 조건을 충족하기 위한 처리입니다.

| 처리 | 확인하는 의미 |
|---|---|
| A 일별 순액+세액 합산, B 전체 gross 보존 | 요청한 숙박 기간 전체의 세금 포함 금액 |
| 체크인 포함·체크아웃 제외, 숙박일별 재고 최솟값 | 모든 숙박일에 이용 가능한 객실 수 |
| 품절·수용 인원 미달 상품 제외 | 검색 조건을 만족하는 상품 |
| 공급사 요청을 최대 50개씩 분할 | 외부 bulk 조회 제한 준수 |
| 외부 코드와 내부 UUID 매핑 | 같은 상품의 식별자를 반복 조회에서도 유지 |

검색 재고는 예약 확정이 아닙니다. 세금 포함 총액도 제공된 공급사 계약 범위의 금액이며, 구현하지 않은 예약·결제 단계의 보장으로 확대하지 않습니다.

## 설계 판단과 감수한 한계

구현 방식은 검색 결과의 정확성, 조회 범위의 투명성, 응답 가용성을 기준으로 선택했습니다.

| 판단한 문제 | 선택과 이유 | 감수한 한계 |
|---|---|---|
| 일부 공급사의 장애 때문에 다른 정상 상품까지 보여주지 못할 수 있다. | 확인한 결과는 제공하고 누락된 범위를 metadata로 알린다. 완전한 결과를 기다리는 것보다 현재 확인할 수 있는 정보를 전달하는 데 우선순위를 뒀다. | 결과의 완전성을 보장하지 않는다. 소비자는 부분 조회 사실을 사용자에게 설명해야 한다. |
| 조회 실패가 정상적인 상품 없음으로 보이면 이용자가 잘못 판단할 수 있다. | 검증된 상품이나 명시적 빈 결과가 있을 때만 검색 성공으로 판단한다. 정상 무결과와 정보 불능을 구분한다. | 결과 건수 외에 관측·거절·실패를 따로 집계해야 하며 소비자의 오류 처리도 필요하다. |
| 제공되지 않은 세부 가격을 계산해 채우면 추정값이 실제 가격처럼 보인다. | 세액·일별 가격은 공급사가 제공한 경우에만 보존한다. 상세함보다 확인 가능한 정보의 정확성을 우선한다. | 일부 상품은 가격 상세 비교가 불가능하고 필드가 null일 수 있다. |
| 이름이 비슷한 상품을 합치면 서로 다른 객실·조식 조건을 같은 것으로 취급할 수 있다. | 동일 상품이라는 근거가 부족하므로 공급사별 상품을 별도로 유지한다. | 같은 숙소가 중복 노출될 수 있다. 별도의 매칭 근거와 오병합 복구 정책이 필요하다. |
| 정적 목록 조회의 지연이나 실패가 매 검색에 영향을 줄 수 있다. | 정적 정보는 미리 저장하고 가격·재고는 검색 시 조회한다. 초기 동기화 범위는 시작 시점으로 한정했다. | 실행 중 정적 정보 변경은 다음 동기화까지 반영되지 않는다. |
| 일시적으로 사라진 상품이 돌아왔을 때 다른 상품처럼 식별될 수 있다. | 매핑을 삭제하지 않고 비활성화하며 재등장 시 같은 UUID를 사용한다. | 비활성 데이터가 남으므로 장기 운영 시 보존·정리 정책이 필요하다. |
| 지속적인 외부 장애를 매 요청마다 다시 기다리는 비용이 누적된다. | 공급사별 Circuit Breaker로 호출을 잠시 멈추고 제한된 조회로 복구를 확인한다. | 실제 복구 직후에도 확인 전까지 일부 상품이 누락될 수 있다. 차단 설정을 운영 지표로 조정해야 한다. |

이 판단은 현재 계약과 재현 테스트에 근거합니다. 실제 고객 조사나 운영 부하 측정으로 효과를 입증했다는 의미는 아닙니다. 기술적 대안과 결정 이력은 [ADR](docs/adr/README.md)에 분리했습니다.

## 검색 결과를 읽는 방법

- **200 + partial=false:** 모든 대상 범위의 조회가 정상이며 거절된 상품이 없습니다. 정상 빈 결과도 포함합니다.
- **200 + partial=true:** 유효한 관측은 있지만 일부 호출 실패, 상품 거절 또는 미준비 카탈로그가 있습니다.
- **400:** 날짜·인원 등 고객 요청 검증 실패입니다.
- **500/502/503:** 유효한 결과를 확보하지 못한 원인을 구분합니다. 내부 결함은 다른 성공 결과가 있어도 500으로 처리합니다.

Circuit Breaker가 호출을 허용하지 않으면 `supplierFailures.category=CIRCUIT_OPEN`으로 알립니다. 다른 유효한 관측이 있으면 부분 200, 유효한 관측 없이 모든 공급사가 차단됐으면 503입니다. 상세 필드와 오류 우선순위는 [API 계약](docs/api.md)에 있습니다.

## 기술 구성과 경계

| 영역 | 사용 기술 |
|---|---|
| 애플리케이션 | Java 21, Spring Boot 4.0.8, Gradle Kotlin DSL |
| 요청·외부 I/O | Spring MVC, WebClient, Resilience4j 2.4.0 |
| 저장소 | PostgreSQL 17, JPA, Flyway |
| 검증 | JUnit 6, MockMvc, Testcontainers, WireMock |
| 문서·품질 | SpringDoc, Spotless, PMD, JaCoCo, Gitleaks, Trivy |

MVC·JPA를 유지하면서 외부 I/O만 병렬화했습니다. 카탈로그 스냅샷을 확보한 뒤 DB 트랜잭션을 종료하고 공급사를 호출합니다. 외부 DTO는 어댑터에서 공통 결과로 변환하며 Entity를 검색 응답으로 노출하지 않습니다. [아키텍처](docs/architecture.md)

## 검증

`backend/`에서 실행합니다. Windows에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다.

```bash
./gradlew spotlessCheck test build
```

Docker가 필요합니다. 테스트는 별도 PostgreSQL·WireMock 컨테이너를 사용하며 로컬 Compose DB를 초기화하지 않습니다.

가격·재고·배치 경계, 외부 HTTP·본문 오류, 정상 빈 결과와 오류 무결과, DB 무결성, 병렬 호출, 차단·복구, OpenAPI와 추적 ID를 검증합니다. 최신 테스트 수·커버리지·CI 근거는 [품질 기록](docs/quality.md), 요구별 테스트 연결은 [검증 문서](docs/testing.md)에 모읍니다.

## 실행 설정과 운영 한계

기본 포트는 앱 8080, PostgreSQL 5432, mock 9090입니다. 환경변수는 [.env.example](.env.example), HTTP·차단 설정은 [application.yml](backend/src/main/resources/application.yml)을 참고합니다. `bootRun`은 `.env`를 자동으로 읽지 않으므로 변경값은 shell 또는 IDE 환경변수로 전달합니다.

연결 제한은 500ms, 배치 응답 읽기와 전체 본문 처리 제한은 2초입니다. **검색 전체가 2초 안에 끝난다는 보장은 아닙니다.** 배치가 많으면 실행 구간이 누적되며, 동시성 4는 검색 요청당 상한입니다.

Circuit Breaker는 인스턴스 안에서 공급사별 상태를 공유합니다. 카탈로그 동기화, 자동 재시도, 캐시, 전역 호출량 제한에는 적용하지 않습니다. 기본 임계값은 mock 검증용 초기값이며 운영 SLA에서 도출한 값이 아닙니다.

이 구성은 로컬 실행용입니다. 운영 인증·비밀정보 관리, 주기 동기화, 전체 검색 deadline, 다중 인스턴스 제어와 부하 검증은 별도 범위입니다. [확장과 한계](docs/extensions.md)

## 상세 문서

- [검색 API](docs/api.md) · [Circuit Breaker 운영](docs/circuit-breaker.md) · [공급사 어댑터](docs/supplier-adapters.md)
- [아키텍처](docs/architecture.md) · [도메인·ERD](docs/domain-model.md) · [카탈로그 동기화](docs/catalog-sync.md)
- [장애 처리](docs/resilience.md) · [테스트](docs/testing.md) · [품질 검사](docs/quality.md)
- [구현 상태](docs/implementation-plan.md) · [설계 결정 ADR](docs/adr/README.md)
- [작업 기록](JOURNAL.md) · [AI 활용 기록](docs/ai-usage.md) · [정책 변경 이력](docs/policy-decisions.md)
