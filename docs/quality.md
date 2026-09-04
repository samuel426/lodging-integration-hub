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

예외적으로 `SupplierCatalogSyncState.succeed`의 `PMD.NullAssignment`만 메서드 범위에서 억제합니다. 성공 후 실패 분류를 `null`로 지우는 것이 승인된 DB 정책이기 때문입니다. 전체 규칙을 비활성화하지 않으며 나머지 생성자 호출·명명·중복 리터럴 지적은 수정했습니다.

## Secret scan

저장소 루트에서 실행합니다. Docker 마운트 경로는 현재 저장소의 절대 경로를 사용합니다.

```bash
docker run --rm -v "$PWD:/repo:ro" -w /repo zricethezav/gitleaks:v8.27.2 dir --redact --no-banner /repo
docker run --rm -v "$PWD:/repo:ro" -w /repo zricethezav/gitleaks:v8.27.2 git --redact --no-banner
```

실제 비밀값을 검사 결과나 문서에 복사하지 않습니다. 첫 명령은 작업 파일, 두 번째는 Git 이력을 검사합니다.

Gradle 실행 중에는 생성된 cache lock 파일 읽기가 실패할 수 있습니다. 빌드 종료 후 재검사하고, PR에 들어갈 파일은 `git --staged --redact --no-banner`로 별도 검사합니다. 종료 코드 0만 보고 파일 읽기 오류를 무시하지 않습니다.

## Runtime dependency vulnerabilities

실행 JAR를 먼저 빌드한 후 저장소 루트에서 실행합니다.

```bash
docker run --rm -v "$PWD/backend/build/libs:/scan:ro" -v lodging-hub-trivy-cache:/root/.cache/trivy aquasec/trivy:0.67.2 rootfs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --no-progress /scan
```

Trivy의 `fs`는 빌드 전 manifest/lockfile 검사이며 JAR 검사에는 `rootfs`를 사용합니다. 결과에 실제 JAR 대상이 있는지 확인합니다. `Supported files ... not found`와 빈 결과는 취약점 없음이 아닙니다. [Trivy 지원 대상](https://trivy.dev/docs/latest/coverage/language/), [Java 아카이브 검사](https://trivy.dev/docs/latest/coverage/language/java/)

검사 범위는 실행 JAR 안의 runtime 의존성입니다. JDK, PostgreSQL/WireMock 컨테이너 이미지, 테스트 및 빌드 도구의 전체 의존성은 이 명령의 대상이 아닙니다. 사용한 취약점 DB 시점에 알려진 HIGH/CRITICAL을 확인하는 것이며 모든 취약점 부재를 보장하지 않습니다. 캐시 volume에는 공개 검사 DB가 남습니다.

## API contracts

Catalog 단계에서는 Supplier HTTP 계약을 검사합니다. 공개 검색 Controller/OpenAPI가 추가되면 Controller 테스트와 문서 계약 검사를 같은 PR에 포함합니다. 아직 없는 API를 검사 완료로 기록하지 않습니다.

## 2026-09-04 dependency remediation

Trivy가 실행 JAR의 Tomcat 11.0.24에서 CVE-2026-65182, CVE-2026-65905, CVE-2026-68525를 CRITICAL로 탐지했습니다. 실제 사용 버전은 Gradle `dependencyInsight`로 확인했습니다. 탐지는 현재 설정에서의 악용 가능성이 입증되었다는 뜻은 아닙니다. 공급자의 영향도 등급과 스캐너의 점수 체계도 다를 수 있습니다.

Apache의 수정 버전 안내에 따라 Spring Boot 4.0.8은 유지하고 `tomcat.version=11.0.25`로 Tomcat 모듈 전체를 일관되게 올렸습니다. 해당 패치 이상을 관리하는 Boot BOM으로 갱신할 때 override 제거를 검토합니다. [Apache Tomcat 11 보안 안내](https://tomcat.apache.org/security-11.html)

패치 후 `spotlessCheck test build`(PMD 포함)가 통과했고 실제 실행 JAR 대상의 재검사에서 HIGH/CRITICAL 0건을 확인했습니다. 기반 단계 테스트는 PostgreSQL context 1건이며 검색 기능 검증이나 높은 기능 커버리지를 의미하지 않습니다.
