# Availability adapters

실시간 조회는 `SupplierAvailabilityClient`를 통해 공급사별 DTO를 공통 `SupplierBatchOutcome`으로 변환합니다. 요금과 재고를 DB에 저장하지 않습니다.

- `SupplierBatches`는 숙소 목록을 최대 50개씩 분할합니다. 빈 목록은 HTTP 요청을 만들지 않습니다.
- query parameter는 URI template로 인코딩합니다. CSV 구분자를 포함한 외부 코드는 요청 전에 `INVALID_REQUEST`로 분류합니다.
- A는 숙박일별 순액과 세액을 안전하게 합산합니다. B는 전체 세금 포함 금액을 보존하며 제공되지 않은 세액과 일자별 금액은 null입니다.
- 체크인 포함·체크아웃 제외 기간에서 각 숙박일은 정확히 한 번 있어야 하며 전체 기간 재고는 일별 최솟값입니다.
- 잘못된 envelope는 batch 실패, 잘못된 개별 상품은 해당 상품 거절로 구분합니다. 명시적 빈 목록도 별도로 기록합니다.
- 숫자 문자열, 소수 금액, 음수, 필수 값 누락, 잘못된 날짜와 합산 overflow를 거절합니다. 예상하지 못한 내부 예외를 외부 데이터 오류로 숨기지 않습니다.
- Reactor Netty의 연결 자동 재시도도 비활성화했습니다. 연결·응답·전체 요청 제한과 본문 크기 제한은 공통 HTTP 설정을 사용합니다.

`AvailabilityMapperTest`, `AvailabilityHttpContractTest`, `SupplierHttpBoundaryTest`에서 정규화와 실제 HTTP 경로·인코딩·헤더·상태·본문 실패를 검사합니다. catalog의 WireMock/Testcontainers 검증도 회귀 실행합니다.

## 로컬 장애 재현

Compose WireMock 정상 fixture는 `2026-10-10`부터 `2026-10-12`까지 지원합니다. A 220000 KRW, B 236000 KRW를 반환합니다. 다른 날짜는 정상 응답으로 가장하지 않습니다.

WireMock admin API의 `PUT /__admin/scenarios/availability-a/state` 또는 `availability-b/state`에 `{"state":"timeout"}`을 보내면 10초 지연, `{"state":"error"}`이면 A HTTP 503 또는 B 본문 E503을 재현합니다. `{"state":"Started"}`로 정상 복구합니다. 이 제어 API는 로컬 mock 용도입니다.

검색 orchestration, 내부 UUID 연결, 업무 필터와 공개 응답 판정은 `StaySearchService`와 `SearchAggregation`이 담당합니다. [Circuit Breaker](circuit-breaker.md)는 availability adapter를 호출하기 전에 공급사별 허가를 판정합니다. mock을 정상 상태로 돌려도 이미 OPEN인 회로는 대기와 복구 확인을 거친 뒤 닫힙니다.
