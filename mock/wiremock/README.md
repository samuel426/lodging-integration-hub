# Supplier mock workspace

WireMock runs on port `9090` through the root `compose.yaml` file.

- `mappings/`: request matching and response behavior
- `__files/`: reusable response bodies

Catalog fixtures use independently authored data: two stays and three room types in total.

- A: `GET /a/v1/hotels`, header `X-Api-Key: local-mock-a`
- B: `GET /b/api/properties`, header `X-Api-Key: local-mock-b`

These values are disposable mock credentials, not live secrets. Unknown/missing headers do not match the fixtures. WireMock remains bound to loopback through Compose. The admin API has no authentication and must not be exposed publicly.

Contract tests create isolated mappings in a separate WireMock Testcontainer; they do not reset the local Compose mock. Availability fixtures and selectable runtime failure scenarios will be added in the availability phase. See [catalog operations](../../docs/catalog-sync.md).
