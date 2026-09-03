# AI Usage Log

AI 활용 여부보다 제안을 어떻게 검증하고 판단했는지를 기록합니다. AI가 만든 결과는 공식 문서, 실행 결과, 테스트 또는 사용자 검토 중 하나 이상으로 확인합니다.

## 원칙

- AI가 제안한 버전과 API는 공식 문서에서 다시 확인합니다.
- 설계 선택은 대안과 손실을 기록한 뒤 사용자가 승인합니다.
- 생성된 코드는 컴파일, 테스트, 포맷 검사로 검증합니다.
- 이해하거나 설명할 수 없는 코드는 수용하지 않습니다.
- 외부 원문을 저장소 문서에 복사하지 않습니다.
- 검증 결과와 남은 불확실성을 사실대로 기록합니다.

## 2026-09-03

| 활동 | AI 제안 또는 지원 | 최종 판단 | 검증 |
|---|---|---|---|
| 요구 구조화 | catalog mapping, adapter, unified search, partial failure로 구분 | 수용 | 사용자와 필수/선택 범위 검토 |
| 저장소 이름 | `lodging-integration-hub` | 사용자 선택 | 공개 저장소 생성 확인 |
| 작업 규칙 | 브랜치, 커밋, API, 보안, 테스트 규칙 초안 | 수정 후 수용 | `AGENTS.md` 검토 및 최초 커밋 |
| Framework | 처음에는 Spring Boot 3.5 계열 제안 | 거부하고 4.0.8로 변경 | Spring 공식 릴리스 및 지원 상태 확인, 사용자 재승인 |
| Web stack | MVC + WebClient | 수용 | 요구되는 병렬 I/O와 구현 복잡도 비교 |
| Database | PostgreSQL + Flyway + Testcontainers | 수용 | 실제 container context test 통과 |
| Supplier mock | 별도 WireMock container | 수용 | Compose 기동 및 admin API 확인 |
| Testcontainers API | 기존 package를 사용한 초기 코드 | 수정 | deprecation compiler warning 확인 후 2.x package로 교체 |
| JUnit 표기 | 기존 계획의 JUnit 5 표기 | JUnit 6으로 수정 | `dependencyInsight`로 Boot 관리 버전 6.0.3 확인 |
| Catalog readiness | 활성 mapping 존재 여부로 판별 | 별도 sync state로 보완 | 정상 빈 catalog와 최초 실패 반례 검토 |
| Invalid offer | batch 전체 실패 또는 조용히 제외 가능 | 유효 offer 유지, 거절 건수 공개 | 부분 성공 원칙과 API 정합성 검토 |
| 설계 문서 | ERD, API, 장애 처리, 테스트 계획 초안 | 검토 대기 | Gate 2 사용자 검토 예정 |

## 수용하지 않은 접근

### Spring Boot 3.5.16 유지

익숙한 계열이라는 장점이 있었지만 마지막 OSS 릴리스라는 공식 안내를 확인했습니다. 새 프로젝트의 유지보수성과 기술 선택 근거를 고려해 사용하지 않았습니다.

### 전면 WebFlux

외부 호출 외의 요청 처리와 DB 접근까지 reactive로 전환할 필요가 없었습니다. JPA를 유지하면서 전체 stack을 WebFlux로 구성하면 blocking 경계가 복잡해지므로 채택하지 않았습니다.

### H2 기반 테스트

빠르지만 PostgreSQL과 다른 타입 및 제약조건 동작으로 인해 매핑 무결성 검증의 신뢰도가 떨어질 수 있어 사용하지 않았습니다.

### 별도 Spring Boot mock 애플리케이션

동적 제어는 편리하지만 애플리케이션 수와 유지 범위를 늘립니다. WireMock scenario와 fixture만으로 필요한 실패 상태를 재현하기로 했습니다.

## 향후 기록 방식

각 구현 단계마다 다음을 추가합니다.

1. AI에게 요청한 문제와 목적
2. 제안된 선택지
3. 수용, 수정 또는 거부한 내용
4. 판단 근거
5. 실행 또는 테스트 결과
6. 남은 불확실성
