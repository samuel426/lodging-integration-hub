# Search API

상태: 승인된 설계 - 검색 API 미구현

2026-09-04: [POL-001~003](policy-decisions.md)이 승인되었습니다. [C안](search-response-policy.md)에 따라 유효한 관측 결과가 있을 때만 200을 반환하며, 외부 데이터 불능은 502로 구분합니다.

## Endpoint

```http
GET /api/v1/stays/search?checkIn=2026-10-10&checkOut=2026-10-12&adults=2&children=0
```

검색 대상은 활성 catalog mapping에 등록된 전체 숙소입니다. 지역, 키워드, 정렬 및 페이징은 지원하지 않습니다.

## 요청 파라미터

| 이름 | 타입 | 필수 | 검증 |
|---|---|---:|---|
| `checkIn` | `YYYY-MM-DD` | O | 유효한 날짜 |
| `checkOut` | `YYYY-MM-DD` | O | `checkIn`보다 이후 |
| `adults` | integer | O | 1 이상 |
| `children` | integer | O | 0 이상 |

과거 날짜를 API 검증에서 거부하지 않습니다. 검색 계약과 장애 시나리오를 재현할 수 있게 하고, 실제 판매 정책은 별도 정책 계층으로 확장합니다.

## 성공 응답

```json
{
  "data": {
    "offers": [
      {
        "stayId": "a6ec54d7-0137-4f2f-a452-d0c9a7dbb667",
        "stayName": "Harbor View Hotel",
        "roomTypeId": "9b3fd571-6a79-4273-a73e-d4ef4808a66a",
        "roomTypeName": "Ocean Twin",
        "maxOccupancy": 2,
        "availableRoomCount": 1,
        "supplier": "SUPPLIER_A",
        "breakfastIncluded": false,
        "price": {
          "totalAmount": 242000,
          "currency": "KRW",
          "taxIncluded": true,
          "taxAmount": 22000,
          "nightlyBreakdown": [
            {
              "date": "2026-10-10",
              "amount": 121000
            },
            {
              "date": "2026-10-11",
              "amount": 121000
            }
          ]
        }
      }
    ]
  },
  "meta": {
    "partial": true,
    "rejectedOfferCount": 1,
    "unavailableCatalogSuppliers": [],
    "supplierFailures": [
      {
        "supplier": "SUPPLIER_B",
        "category": "TIMEOUT",
        "failedBatchCount": 1
      }
    ]
  }
}
```

### 필드 정의

| 필드 | 의미 |
|---|---|
| `stayId` | 외부 코드가 아닌 내부 숙소 ID |
| `roomTypeId` | 외부 코드가 아닌 내부 객실 타입 ID |
| `availableRoomCount` | 요청 기간 모든 숙박일을 예약할 수 있는 객실 수 |
| `supplier` | offer의 출처 |
| `totalAmount` | 전체 숙박 기간의 세금 포함 결제 금액 |
| `taxAmount` | Supplier가 세액을 제공할 때만 값이 있으며 그 외에는 `null` |
| `nightlyBreakdown` | Supplier가 일자별 금액을 제공할 때만 값이 있으며 그 외에는 `null` |
| `partial` | batch 실패, 개별 offer 거절 또는 준비되지 않은 Supplier catalog 중 하나라도 있는지 여부 |
| `rejectedOfferCount` | 외부 응답은 도착했지만 검증 또는 정규화에 실패해 제외한 offer 수 |
| `unavailableCatalogSuppliers` | 성공한 catalog snapshot이 없어 검색하지 못한 Supplier 목록 |
| `supplierFailures` | 외부 원문을 노출하지 않는 공통 실패 요약 |

품절 offer는 응답에서 제외하므로 반환된 `availableRoomCount`는 항상 1 이상입니다.

## 전체 성공

모든 Supplier batch가 성공하면 다음 metadata를 반환합니다.

```json
{
  "partial": false,
  "rejectedOfferCount": 0,
  "unavailableCatalogSuppliers": [],
  "supplierFailures": []
}
```

성공한 호출이 빈 상품 목록을 반환한 경우에도 호출 자체는 성공입니다. 따라서 모든 성공 응답이 빈 목록이면 HTTP 200과 빈 `offers`를 반환합니다.

## 부분 성공

하나 이상의 유효한 관측 결과가 있고 일부 범위가 실패하면 HTTP 200을 반환합니다. 유효한 관측은 검증된 offer(업무 필터링 전), 명시적 빈 availability batch, 정상 빈 catalog입니다. 개별 offer만 잘못된 경우 나머지 유효한 offer를 유지하며 `rejectedOfferCount`로 제외 사실을 알립니다.

- 성공한 상품은 그대로 제공합니다.
- 같은 Supplier의 다른 성공 batch도 버리지 않습니다.
- 실패 정보는 Supplier와 분류, 실패 batch 수까지만 노출합니다.
- 외부 URL, API key, 외부 오류 본문, 숙소 코드 목록은 노출하지 않습니다.

본문 파싱에 성공해도 모든 offer가 잘못되고 정상 빈 관측도 없으면 200이 아닙니다. 아래 오류 코드와 [판정 순서](search-response-policy.md#5-최종-응답의-판정-순서)를 적용합니다. 품절이나 수용 인원 조건 불일치로 제외한 검증된 정상 상품은 거절 건수에 넣지 않습니다.

일부 Supplier에 catalog 성공 이력이 없으면 준비된 Supplier로 검색을 진행하고 `unavailableCatalogSuppliers`와 `partial=true`로 누락된 검색 범위를 알립니다. 기존 성공 snapshot이 있는 Supplier의 refresh 실패는 이 목록에 넣지 않고 운영 지표로 관찰합니다.

## 오류 응답

| HTTP | code | 조건 |
|---|---|---|
| 400 | `INVALID_SEARCH_CONDITION` | 고객 요청 검증 실패 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 내부 결함 |
| 503 | `CATALOG_NOT_READY` | 모든 Supplier catalog 성공 이력 없음 |
| 500 | `INTEGRATION_CONFIGURATION_ERROR` | 유효한 관측 없이 연동 요청·인증 오류 |
| 503 | `CATALOG_MAPPING_UNAVAILABLE` | 유효한 관측 없이 내부 mapping 불일치 |
| 502 | `NO_VALID_SUPPLIER_DATA` | 유효한 관측 없이 외부 데이터 계약 위반 |
| 503 | `ALL_SUPPLIERS_UNAVAILABLE` | 유효한 관측 없이 외부 이용 불가 |

오류는 모두 `error.code`, 안전한 `message`, `fieldErrors` 배열, `traceId`를 사용합니다. 상품 0건이라는 이유만으로 5xx를 반환하지 않습니다. 내부 결함과 catalog 미준비를 먼저 판정하고, 나머지 혼합 오류는 위 표의 순서를 따릅니다. Supplier 인증 실패를 고객의 401로 전달하지 않습니다.

### 잘못된 요청

```http
HTTP/1.1 400 Bad Request
```

```json
{
  "error": {
    "code": "INVALID_SEARCH_CONDITION",
    "message": "검색 조건이 올바르지 않습니다.",
    "fieldErrors": [
      {
        "field": "checkOut",
        "reason": "체크아웃 날짜는 체크인 날짜보다 이후여야 합니다."
      }
    ]
  },
  "traceId": "01991c5f2d8278c18bd6dd02a3a8ef34"
}
```

### Catalog 미준비

```http
HTTP/1.1 503 Service Unavailable
```

```json
{
  "error": {
    "code": "CATALOG_NOT_READY",
    "message": "검색 가능한 상품 정보가 아직 준비되지 않았습니다.",
    "fieldErrors": []
  },
  "traceId": "01991c5f2d8278c18bd6dd02a3a8ef34"
}
```

모든 Supplier의 catalog 동기화가 한 번도 성공하지 않았을 때 사용합니다. 준비된 catalog가 모두 정상 빈 snapshot이면 HTTP 200과 빈 결과를 반환하며, 나머지 미준비 Supplier가 있다면 부분 성공 metadata를 포함합니다.

### 모든 외부 조회 이용 불가

```http
HTTP/1.1 503 Service Unavailable
```

```json
{
  "error": {
    "code": "ALL_SUPPLIERS_UNAVAILABLE",
    "message": "현재 숙박 상품을 조회할 수 없습니다.",
    "fieldErrors": []
  },
  "traceId": "01991c5f2d8278c18bd6dd02a3a8ef34"
}
```

## 실패 분류

| category | 의미 |
|---|---|
| `TIMEOUT` | 연결 또는 응답 제한 시간 초과 |
| `CONNECTION_ERROR` | 연결 거부, DNS 또는 네트워크 오류 |
| `RATE_LIMITED` | 외부 호출 한도 초과 |
| `AUTHENTICATION_ERROR` | Supplier 인증 실패 |
| `INVALID_REQUEST` | Supplier가 요청을 거부 |
| `INVALID_RESPONSE` | 본문 실패, 역직렬화 또는 정규화 실패 |
| `UPSTREAM_ERROR` | 외부 서버 오류 또는 일시적 장애 |

내부 로그에는 원인 예외와 operation을 남기되 고객 응답에는 안정적인 분류만 제공합니다.

## 계약상 보장

- `checkOut`은 숙박일에 포함되지 않습니다.
- `stayId`와 `roomTypeId`는 동일 외부 상품에 대해 안정적입니다.
- 금액은 통화의 최소 단위 정수입니다.
- `taxAmount`와 `nightlyBreakdown`이 `null`이어도 `totalAmount`는 전체 결제 금액입니다.
- `partial=false`는 모든 Supplier에 성공한 catalog snapshot이 있고, 모든 요청 batch가 성공했으며, 거절된 offer가 없다는 의미입니다. 상품 존재나 catalog의 실시간 최신성은 보장하지 않습니다.
- 응답 순서는 비즈니스 계약으로 보장하지 않습니다.
