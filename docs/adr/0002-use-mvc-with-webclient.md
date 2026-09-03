# ADR 0002: Spring MVC와 WebClient 조합

- 상태: Accepted
- 결정일: 2026-09-03

## 배경

검색 요청 하나가 여러 Supplier와 여러 batch를 동시에 조회해야 합니다. 반면 영속성은 JPA를 사용하며 API 경계 자체를 reactive로 제공할 요구는 없습니다.

## 결정

Controller는 Spring MVC를 사용하고 외부 HTTP I/O는 WebClient로 병렬화합니다. 검색 서비스는 모든 외부 작업을 합성한 뒤 MVC 경계에서 한 번만 기다립니다. 외부 호출 중에는 DB 트랜잭션을 유지하지 않습니다.

## 결과

- 일반적인 MVC와 JPA 개발 모델을 유지합니다.
- 병렬 외부 호출은 non-blocking I/O의 이점을 사용합니다.
- 동시성 상한과 타임아웃을 명시해야 합니다.
- event-loop에서 blocking DB 작업을 수행하지 않도록 경계를 테스트합니다.

## 검토한 대안

- 순차 MVC 호출: 단순하지만 Supplier 수와 batch 수에 비례해 지연이 누적됩니다.
- 전면 WebFlux: JPA와 섞일 때 blocking 경계가 늘고 현재 범위 이상의 복잡성을 만듭니다.
