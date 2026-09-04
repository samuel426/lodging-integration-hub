# Quality Checks

검사 성공 여부와 실제 검사 범위를 함께 기록합니다. 대상 파일이 0개인 검사는 통과로 취급하지 않습니다.

## Build, formatting, static analysis and coverage

JDK 21과 Docker를 준비하고 `backend/`에서 실행합니다. Windows에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다.

```bash
./gradlew spotlessCheck test build
```

- Spotless: Java와 Gradle Kotlin DSL 포맷, backend 문서·설정 공백 검사
- PMD 7.24.0: production/test Java의 `category/java/errorprone.xml`. `build`의 `check`에 연결
- Testcontainers: 폐기 가능한 PostgreSQL에서 schema와 DB 경계 검증
- JaCoCo: `backend/build/reports/jacoco/test/html/index.html`
- PMD: `backend/build/reports/pmd/main.html`, `test.html`

커버리지 수치만으로 정확성을 주장하지 않습니다. 아직 구현하지 않은 기능은 커버리지 분모에도 없으므로 테스트 시나리오 충족 여부를 별도로 추적합니다. [Gradle PMD 문서](https://docs.gradle.org/current/userguide/pmd_plugin.html)

## Secret scan

저장소 루트에서 실행합니다. Docker 마운트 경로는 현재 저장소의 절대 경로를 사용합니다.

```bash
docker run --rm -v "$PWD:/repo:ro" -w /repo zricethezav/gitleaks:v8.27.2 dir --redact --no-banner /repo
docker run --rm -v "$PWD:/repo:ro" -w /repo zricethezav/gitleaks:v8.27.2 git --redact --no-banner
```

실제 비밀값을 검사 결과나 문서에 복사하지 않습니다. 첫 명령은 작업 파일, 두 번째는 Git 이력을 검사합니다.

## Runtime dependency vulnerabilities

실행 JAR를 먼저 빌드한 후 저장소 루트에서 실행합니다.

```bash
docker run --rm -v "$PWD/backend/build/libs:/scan:ro" -v lodging-hub-trivy-cache:/root/.cache/trivy aquasec/trivy:0.67.2 rootfs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --no-progress /scan
```

Trivy의 `fs`는 빌드 전 manifest/lockfile 검사이며 JAR 검사에는 `rootfs`를 사용합니다. 결과에 실제 JAR 대상이 있는지 확인합니다. `Supported files ... not found`와 빈 결과는 취약점 없음이 아닙니다. [Trivy 지원 대상](https://trivy.dev/docs/latest/coverage/language/), [Java 아카이브 검사](https://trivy.dev/docs/latest/coverage/language/java/)

검사 범위는 실행 JAR 안의 runtime 의존성입니다. JDK, PostgreSQL/WireMock 컨테이너 이미지, 테스트 및 빌드 도구의 전체 의존성은 이 명령의 대상이 아닙니다. 사용한 취약점 DB 시점에 알려진 HIGH/CRITICAL을 확인하는 것이며 모든 취약점 부재를 보장하지 않습니다. 캐시 volume에는 공개 검사 DB가 남습니다.

## API contracts

Catalog 단계에서는 Supplier HTTP 계약을 검사합니다. 공개 검색 Controller/OpenAPI가 추가되면 Controller 테스트와 문서 계약 검사를 같은 PR에 포함합니다. 아직 없는 API를 검사 완료로 기록하지 않습니다.
