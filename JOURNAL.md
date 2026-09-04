# Progress Journal

결과만이 아니라 선택 과정, 검토한 대안, 실패와 수정 내용을 기록합니다.

## 2026-09-03 - 저장소 규칙과 실행 기반

### 수행 내용

- 저장소 작업 규칙과 Git workflow를 `AGENTS.md`로 정의했습니다.
- `main`, `dev`, `feat/project-bootstrap` 브랜치 흐름을 준비했습니다.
- Java 21, Spring Boot, PostgreSQL, Flyway, WebClient 기반을 구성했습니다.
- PostgreSQL과 WireMock을 Docker Compose로 실제 기동했습니다.
- Testcontainers PostgreSQL로 Spring context load 테스트를 실행했습니다.
- Spotless와 JaCoCo를 설정했습니다.

### 의사결정

#### Spring Boot 4.0.8

처음에는 익숙한 3.5 계열을 우선 검토했습니다. 버전 확정 전에 [공식 릴리스 정보](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now)를 확인한 결과 3.5.16이 해당 계열의 마지막 OSS 릴리스이며 4.x 전환이 권고되고 있음을 확인했습니다.

검토한 대안:

- 3.5.16: 기존 경험을 활용하기 쉽지만 OSS 지원 종료
- 4.1.1: 최신 안정 버전이지만 출시 직후라 변경 범위와 생태계 적응 비용이 큼
- 4.0.8: 지원 중인 4.x 계열이며 패치가 충분히 누적됨

최종적으로 4.0.8을 제안했고 사용자 승인을 받아 적용했습니다.

#### 카탈로그 준비 상태

활성 매핑 수만으로 카탈로그 준비 여부를 판단하면 정상적으로 빈 카탈로그를 받은 경우와 첫 동기화가 실패한 경우를 구분할 수 없습니다. 공급사별 동기화 상태를 별도 저장하고, 하나 이상의 공급사가 한 번이라도 정상 응답을 반영했을 때 검색을 시작하는 방향으로 설계를 보완했습니다.

#### Spring MVC + WebClient

검색 API 자체는 일반적인 요청-응답 모델이므로 MVC를 유지합니다. 공급사 병렬 호출 구간만 WebClient와 Reactor로 구성해 비동기 I/O의 이점을 사용합니다. 전체 WebFlux 전환은 학습 및 디버깅 범위를 넓히지만 현재 목표에는 직접적인 이점이 적다고 판단했습니다.

#### PostgreSQL + Testcontainers

H2는 실행이 간단하지만 운영 DB와 제약조건, 타입, SQL 동작이 달라질 수 있습니다. 로컬 환경은 Docker Compose, 테스트는 Testcontainers를 사용해 동일한 PostgreSQL 계열에서 검증합니다.

#### WireMock

Mock 자체의 구현 복잡도를 낮추고 운영 애플리케이션을 하나로 유지하기 위해 별도 Spring Boot mock 애플리케이션 대신 WireMock standalone container를 선택했습니다.

### 문제와 해결

#### 공식 Initializr의 버전 표기 차이

메타데이터의 식별자 표기와 실제 생성 API가 기대하는 버전 문자열이 달라 첫 요청이 실패했습니다. 오류 응답에서 BOM 해석 실패를 확인한 뒤 릴리스 버전 `4.0.8`을 사용해 다시 생성했습니다.

#### Testcontainers deprecated API

초기 생성 예제는 이전 패키지의 `PostgreSQLContainer`를 사용해 컴파일 경고가 발생했습니다. `-Xlint:deprecation`을 활성화해 위치를 확인하고 Testcontainers 2.x의 새 패키지인 `org.testcontainers.postgresql` 구현으로 교체했습니다.

#### 작업 트리 줄바꿈

재검증 시 Java 소스의 Windows CRLF와 Spotless의 LF 기대값이 달라 검사가 실패했습니다. Git index는 이미 LF였으므로 동작 변경 없이 `spotlessApply`로 작업 트리를 정규화하고 다시 검사했습니다.

#### JUnit 관리 버전

초안의 JUnit 5 표기와 실제 Boot 4 dependency management가 달랐습니다. `dependencyInsight`로 JUnit Jupiter 6.0.3이 선택됨을 확인하고 문서를 JUnit 6으로 수정했습니다. 별도 버전을 강제하지 않고 Boot가 관리하는 버전을 사용합니다.

#### 로컬 서비스 노출 범위

Compose의 DB와 WireMock 포트를 loopback으로 제한했습니다. WireMock의 verbose 로깅도 제거해 요청 헤더의 불필요한 로그 노출을 줄였습니다. Mock의 관리자 API와 로컬 비밀번호는 운영 설정으로 사용하지 않습니다.

#### 검증 결과

- `spotlessCheck`: 성공
- Spring context test: 성공
- JaCoCo report 생성: 성공
- `build` 및 실행 가능한 Boot JAR 생성: 성공
- PostgreSQL container health: healthy
- WireMock container health: healthy
- WireMock admin API: 정상 응답
- 실행 가능한 JAR smoke: Health `UP`, OpenAPI JSON 정상, Swagger UI HTTP 200
- 공개 문서의 상대 링크와 API JSON 예시 검증: 성공
- Gitleaks staged diff 검사: 누출 발견 없음
- 공개 저장소의 식별 정보와 원문 예시 포함 여부 검사: 발견 없음

현재 자동 테스트는 context load 1개이며 도메인 검증은 아직 구현하지 않았습니다. 위 성공은 기반 구성의 검증 결과입니다. 정적 분석 도구와 의존성 취약점 검사는 아직 구성하지 않았으며 PR 전 별도 확인이 필요합니다.

### AI 활용

AI는 요구사항 구조화, 기술 대안 비교, 공식 문서 확인, 프로젝트 골격 구성과 검증 과정에 사용했습니다.

- 수용: MVC + WebClient, PostgreSQL + Testcontainers, WireMock 선택
- 수정: Spring Boot 3.5 제안을 지원 상태 확인 후 4.0.8로 변경
- 검증: 공식 릴리스 문서, 실제 Gradle 컴파일, Docker 기동, 테스트 실행
- 인간 확인: 기술 스택 및 도메인 정책 전체와 Spring Boot 4.0.8 변경을 사용자에게 별도로 승인받음

상세 기록은 [AI 활용 기록](docs/ai-usage.md)에 이어서 관리합니다.

### 다음 단계

- ERD와 동기화 불변식 검토
- 통합 검색 API 계약 검토
- 타임아웃, 부분 성공 및 전체 실패 의미 검토
- 설계 승인 후 카탈로그 매핑 구현

## 2026-09-04 - 정책 승인과 검색 결과 의미 재검토

### 사용자 결정

- Supplier catalog에서 사라진 mapping은 소프트 삭제하고 재등장 시 기존 ID를 유지하는 정책을 승인받았습니다.
- 일부 Supplier catalog만 준비되어도 검색하고 미준비 Supplier를 응답에 표시하는 정책을 승인받았습니다.
- 모든 offer의 정규화가 실패한 경우는 대안과 근거, 최우선 추천을 먼저 제시해 추가 검토하기로 했습니다.
- 사용자가 구현 보류를 명시했으므로 코드, PR과 병합을 진행하지 않습니다.

### 재검토 결과

기존 초안은 본문을 해석한 batch가 있으면 모든 offer가 잘못되어도 200과 빈 결과를 반환했습니다. JSON을 읽었다는 기술적 성공과 신뢰할 수 있는 검색 결과의 존재를 혼동해, 오류를 정상 무결과로 보일 수 있다고 판단했습니다.

현재 추천은 유효한 offer, 검증된 정상 빈 batch, 검증된 빈 catalog 중 하나라도 있어야 정상적인 관측 결과가 있다고 보는 것입니다. 이런 근거가 있으면 정상 결과를 보존하고 200과 부분 실패 metadata를 반환합니다. 전혀 없다면 외부 데이터 계약 오류 502, 전체 외부 이용 불가 503, catalog 불일치 503, 내부 결함 500처럼 원인별로 구분하는 안을 제시했습니다.

502 도입은 기존 전체 실패 503 원칙에 예외를 추가하므로 사용자 승인 전에는 확정 정책으로 적용하지 않습니다. 206은 HTTP Range 응답이고 207은 WebDAV 다중 상태 응답이므로 일반 JSON 검색의 부분 성공 상태로 사용하지 않는 것을 추천했습니다.

### 문서 변경

- `docs/policy-decisions.md`: 정책 ID, 승인일, 범위와 구현 상태를 기록했습니다.
- `docs/search-response-policy.md`: 세 가지 대안, 16개 시나리오, 추천 판정 순서와 운영·테스트 경계를 기록했습니다.
- 관련 ADR, API, 견고성, 테스트 및 구현 계획에 승인/대기 상태를 연결했습니다.

### 다음 단계

- POL-003 대안과 HTTP 502/503 구분에 대한 사용자 결정
- 결정 후 관련 문서의 이전 초안을 확정 계약으로 정리
- 별도 구현 시작 지시 전까지 도메인 코드 작성 보류

## 2026-09-04 - C안 승인과 구현 재개

사용자가 C안 추천을 채택하고 구현 시작을 명시적으로 요청했습니다. 이전 보류를 해제하고 정책 대장, ADR 0005, API, 견고성, 테스트 계획을 승인된 기준으로 맞췄습니다. 파싱 성공과 유효 관측을 분리하고 데이터 불능 502를 이용 불가 503과 구분합니다. 새 선택 기능까지 승인된 것으로 해석하지 않습니다.

기반 브랜치를 PR로 정리한 뒤 최신 `dev`에서 `feat/catalog-sync`를 생성합니다. 첫 구현 단위는 안정적 ID mapping, 전체 snapshot의 원자적 반영, 비활성화·재활성화, 성공 이력과 실패 격리입니다. 검색 API와 C안의 S01~S16 실제 검증은 후속 통합 검색 단계입니다.

정적 분석은 Gradle 내장 PMD 플러그인과 error-prone 규칙을 사용합니다. 별도 분석 서버 없이 빌드에서 반복할 수 있고, 코드 포맷과 오류 패턴 분석을 분리합니다. Gradle 9.7.1의 지원 범위에 있는 PMD 7.24.0을 고정했습니다. [Gradle PMD 문서](https://docs.gradle.org/current/userguide/pmd_plugin.html)
