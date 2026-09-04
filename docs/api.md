# Search API

상태: Proposed - Gate 2 review

2026-09-04: 소프트 삭제와 catalog 부분 준비 정책은 [POL-001/002](policy-decisions.md)로 승인되었습니다. 전부 정규화 실패한 경우의 200 응답은 아래에 남겨둔 이전 초안이며 확정 계약이 아닙니다. [POL-003 대안 검토](search-response-policy.md)와 HTTP 502 도입 여부는 승인 대기입니다.

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

하나 이상의 batch가 성공하고 일부 batch가 실패하면 HTTP 200을 반환합니다. 개별 offer만 잘못된 경우에도 나머지 유효한 offer는 유지하며 `rejectedOfferCount`로 제외 사실을 알립니다.

- 성공한 상품은 그대로 제공합니다.
- 같은 Supplier의 다른 성공 batch도 버리지 않습니다.
- 실패 정보는 Supplier와 분류, 실패 batch 수까지만 노출합니다.
- 외부 URL, API key, 외부 오류 본문, 숙소 코드 목록은 노출하지 않습니다.

이전 제안(미승인)은 정상적으로 해석한 batch의 offer가 모두 잘못된 경우에도 HTTP 200, 빈 `offers`, `partial=true`와 거절 건수를 반환하는 방식입니다. 현재는 유효한 관측 결과가 전혀 없으면 5xx로 구분하는 방향을 [재검토](search-response-policy.md)하고 있습니다. 어느 안에서도 품절이나 수용 인원 조건 불일치로 제외한 정상 상품은 거절 건수에 넣지 않습니다.

일부 Supplier에 catalog 성공 이력이 없으면 준비된 Supplier로 검색을 진행하고 `unavailableCatalogSuppliers`와 `partial=true`로 누락된 검색 범위를 알립니다. 기존 성공 snapshot이 있는 Supplier의 refresh 실패는 이 목록에 넣지 않고 운영 지표로 관찰합니다.

## 오류 응답

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

모든 Supplier의 catalog 동기화가 한 번도 성공하지 않았을 때 사용합니다. 정상적으로 빈 catalog를 반영한 성공 이력이 있다면 활성 매핑이 0개여도 HTTP 200과 빈 결과를 반환합니다.

### 모든 외부 조회 실패

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
