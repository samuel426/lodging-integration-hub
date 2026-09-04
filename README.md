# Lodging Integration Hub

여러 숙박 상품 공급사의 카탈로그, 요금, 재고를 일관된 내부 모델로 통합하는 백엔드 플랫폼입니다.

공급사별 계약 차이는 어댑터 내부에 격리하고, 검색 사용자는 공급사와 무관한 동일한 응답 계약을 사용합니다. 외부 연동은 실패할 수 있다는 전제에서 병렬 조회, 타임아웃, 부분 성공을 핵심 품질 속성으로 다룹니다.

## 현재 상태

- Spring Boot 실행 기반 구성
- PostgreSQL 및 Flyway 구성
- WireMock 기반 Supplier mock 실행 환경 구성
- Supplier A/B catalog HTTP 어댑터와 시작 시 동기화
- 내부 숙소·객실 ID mapping, 소프트 삭제와 재활성화
- Supplier별 성공 이력과 실패 보존, 조회용 불변 snapshot
- Testcontainers PostgreSQL 및 WireMock 계약·통합 테스트
- 검색 API와 실시간 가격·재고 정규화는 후속 구현 예정

2026-09-04 C안 응답 정책과 구현 시작을 승인받았습니다. 기능별 구현과 검증 결과는 문서에 구분해 기록합니다.

Flyway V1이 catalog 테이블을 생성하고 시작 시 local mock의 정적 상품을 동기화합니다. 검색 Controller는 아직 없어 OpenAPI의 검색 경로는 비어 있습니다. catalog 준비 상태와 애플리케이션 health는 서로 다른 지표입니다.

## 목표

- 외부 숙소 및 객실 타입 코드에 안정적인 내부 식별자를 부여합니다.
- 공급사별로 다른 요금과 재고 표현을 공통 상품 모델로 정규화합니다.
- 다수 공급사와 다수 배치를 제한된 동시성으로 병렬 조회합니다.
- 일부 공급사가 실패해도 성공한 상품을 반환하고 실패 사실을 명시합니다.
- 신규 공급사를 추가할 때 기존 검색 흐름의 변경을 최소화합니다.

## 구현 범위

### 포함

- 공급사 카탈로그 동기화
- 숙소 및 객실 타입 식별자 매핑 저장
- Supplier A/B WebClient 어댑터
- 날짜와 인원 기반 통합 검색 API
- 총 결제 금액과 연박 재고 정규화
- 연결 및 응답 타임아웃
- 부분 성공과 전체 실패 처리
- 정상, 오류, 무응답을 재현하는 mock
- 단위, Controller, 계약, 통합 테스트
- OpenAPI와 운영 지표

### 제외

- 인증과 인가
- 결제
- 관리자 UI
- 프론트엔드
- 지역 및 키워드 검색
- 페이징과 비즈니스 정렬
- 실제 외부 서비스 호출
- 공급사 간 동일 숙소 자동 병합
- 예약 생성과 취소

제외한 기능의 확장 방향은 설계 문서에 기록합니다.

## 기술 스택

| 영역 | 선택 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.0.8 |
| Web | Spring MVC, Spring WebClient |
| Persistence | Spring Data JPA, PostgreSQL 17, Flyway |
| API documentation | SpringDoc OpenAPI |
| Test | JUnit 6 (Jupiter), AssertJ, Testcontainers, WireMock |
| Quality | Spotless, PMD, JaCoCo, Gitleaks, Trivy |
| Local environment | Docker Compose |

Spring Boot 3.5 계열도 검토했지만 [3.5.16이 마지막 OSS 릴리스라는 공식 안내](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now)를 확인해 새 프로젝트의 기반으로 선택하지 않았습니다. 최신 기능 도입보다 지원 중인 안정적인 4.0 패치 계열을 사용하는 것을 우선했습니다.

## 저장소 구조

```text
.
├── backend/              Spring Boot 애플리케이션
├── docs/                 설계, API, 테스트 및 의사결정 기록
├── mock/wiremock/        로컬 Supplier mock 정의
├── compose.yaml          PostgreSQL 및 WireMock 실행 환경
├── AGENTS.md             저장소 작업 규칙
├── JOURNAL.md            진행 과정과 판단 기록
└── README.md
```

예정된 애플리케이션 패키지 구조는 [아키텍처 문서](docs/architecture.md)에 설명합니다.

## 로컬 실행

### 사전 요구사항

- JDK 21
- Docker Desktop 또는 Docker Engine과 Compose

### 인프라 실행

```bash
docker compose up -d
```

기본 포트:

| 서비스 | 포트 |
|---|---:|
| PostgreSQL | `5432` |
| WireMock | `9090` |
| Application | `8080` |

### 애플리케이션 실행

macOS/Linux:

```bash
cd backend
./gradlew bootRun
```

Windows:

```powershell
cd backend
.\gradlew.bat bootRun
```

기동 후 확인할 수 있는 주소:

- Health: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- WireMock mappings: `http://localhost:9090/__admin/mappings`

### 종료

```bash
docker compose down
```

DB 데이터를 포함한 volume까지 삭제하려면 개발 데이터가 필요하지 않은지 확인한 뒤 `docker compose down -v`를 사용합니다.

## 환경 변수

| 이름 | 설명 | 로컬 기본값 |
|---|---|---|
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/lodging_hub` |
| `DB_USERNAME` | DB 사용자 | `lodging` |
| `DB_PASSWORD` | DB 비밀번호 | 로컬 Compose 전용 값 |
| `SUPPLIER_A_BASE_URL` | Supplier A HTTP base URL | `http://localhost:9090` |
| `SUPPLIER_B_BASE_URL` | Supplier B HTTP base URL | `http://localhost:9090` |
| `SUPPLIER_A_API_KEY` | Supplier A 인증 헤더 값 | 로컬 mock 전용 값 |
| `SUPPLIER_B_API_KEY` | Supplier B 인증 헤더 값 | 로컬 mock 전용 값 |
| `CATALOG_SYNC_ON_STARTUP` | 시작 시 1회 동기화 | `true` |

실제 비밀값은 커밋하지 않습니다. 변수 이름은 [.env.example](.env.example)에서 확인할 수 있습니다.

Spring Boot를 `bootRun`으로 실행할 때 `.env`가 자동으로 로드되지는 않습니다. 값을 바꾸려면 실행 shell 또는 IDE의 환경 변수로 전달합니다. Compose의 고정 사용자와 비밀번호는 폐기 가능한 로컬 개발용으로만 사용합니다. 이 구성은 인증이 없는 로컬 실행용이며 운영 배포 설정이 아닙니다.

외부 호출은 연결 500ms, 응답 읽기 2s, 전체 본문 수신·역직렬화 deadline 2s, 본문 메모리 상한 4MiB를 사용합니다. `suppliers.*` Spring 설정으로 조정할 수 있습니다. 검색 동시성 설정은 availability 단계에서 추가합니다. [Catalog 운영 안내](docs/catalog-sync.md)에 재실행·실패 확인 절차를 정리합니다.

## 품질 검사

macOS/Linux:

```bash
cd backend
./gradlew spotlessCheck test build
```

Windows:

```powershell
cd backend
.\gradlew.bat spotlessCheck test build
```

테스트는 로컬에 설치된 DB 대신 Testcontainers PostgreSQL을 사용합니다. Docker가 실행 중이어야 합니다.

PMD는 `build`에 포함됩니다. 비밀정보 및 실행 JAR 의존성 검사 명령과 적용 범위는 [품질 검사 문서](docs/quality.md)를 참고합니다.

## 설계 문서

- [정책 승인 및 변경 대장](docs/policy-decisions.md)
- [검색 응답 정책 대안 검토](docs/search-response-policy.md)
- [아키텍처](docs/architecture.md)
- [통합 도메인 모델](docs/domain-model.md)
- [검색 API](docs/api.md)
- [외부 연동 견고성](docs/resilience.md)
- [테스트 전략](docs/testing.md)
- [Catalog 동기화와 운영](docs/catalog-sync.md)
- [구현 계획](docs/implementation-plan.md)
- [확장 설계와 현재 한계](docs/extensions.md)
- [AI 활용 기록](docs/ai-usage.md)
- [Architecture Decision Records](docs/adr/README.md)

## 핵심 설계 결정 요약

- Spring MVC 요청 처리와 WebClient 병렬 호출을 조합합니다.
- 외부 호출 중에는 DB 트랜잭션을 유지하지 않습니다.
- 카탈로그 매핑은 저장하지만 실시간 요금과 재고는 저장하지 않습니다.
- 공급사별 마지막 카탈로그 동기화 성공 상태를 저장해 정상적인 빈 카탈로그와 초기화 실패를 구분합니다.
- 내부 ID는 UUID를 사용하고 외부 코드 재조회 시 기존 ID를 유지합니다.
- 서로 다른 공급사의 유사 상품은 자동으로 병합하지 않습니다.
- 가격은 세금을 포함한 전체 숙박 기간 결제 금액을 공통 기준으로 사용합니다.
- 예약 가능 객실 수는 요청한 모든 숙박일의 재고 최솟값입니다.
- 품절 상품은 검색 결과에서 제외합니다.
- 유효한 관측 결과가 있으면 HTTP 200과 필요 시 부분 실패 metadata를 반환합니다.
- 관측 결과가 없으면 C안에 따라 데이터 불능 502, 이용 불가 503, 내부 문제 500 등으로 구분합니다.

각 결정의 배경과 대안은 ADR에서 확인할 수 있습니다.

### 선택의 이유와 손실

| 선택 | 이유 | 감수하는 제약 |
|---|---|---|
| 세금 포함 총액을 기준으로 사용 | 사용자가 실제 지불할 금액의 의미를 통일 | 세액과 일자별 금액이 없는 상품은 세부 비교 불가 |
| 제공된 세부 가격만 보존 | 임의 분배로 잘못된 정밀도를 만들지 않음 | 선택 필드가 `null`일 수 있음 |
| 모든 숙박일의 재고 최솟값 사용, 품절 제외 | 전체 기간을 예약할 수 있는 상품만 탐색하게 함 | 응답만으로 품절 상품 목록은 알 수 없음 |
| 공급사별 상품을 별도 노출 | 조식 등 조건 차이를 보존하고 오병합 방지 | 동일 숙소가 여러 결과에 나타날 수 있음 |
| 시작 시 catalog 동기화 | 검색마다 정적 목록을 재조회하지 않고 안정적인 ID 확보 | 실행 중 변경은 다음 동기화 전까지 반영되지 않음 |
