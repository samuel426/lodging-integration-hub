param(
  [string]$AppUrl = 'http://localhost:8080',
  [string]$MockUrl = 'http://localhost:9090'
)
$ErrorActionPreference = 'Stop'
$searchPath = '/api/v1/stays/search?checkIn=2026-10-10&checkOut=2026-10-12&adults=2&children=0'
function Set-MockState([string]$Supplier, [string]$State) {
  $body = @{ state = $State } | ConvertTo-Json -Compress
  Invoke-RestMethod -Method Put -Uri "$MockUrl/__admin/scenarios/availability-$Supplier/state" -ContentType 'application/json' -Body $body | Out-Null
}
function Assert-True([bool]$Condition, [string]$Message) {
  if (-not $Condition) { throw $Message }
}
try {
  Set-MockState 'a' 'Started'
  Set-MockState 'b' 'Started'
  $normal = Invoke-RestMethod "$AppUrl$searchPath"
  Assert-True ($normal.data.offers.Count -eq 2 -and -not $normal.meta.partial) 'Normal search failed'
  Assert-True ($normal.data.offers[0].price.totalAmount -eq 220000 -and $normal.data.offers[1].price.totalAmount -eq 236000) 'Gross price mismatch'
  $schema = Invoke-RestMethod "$AppUrl/v3/api-docs"
  Assert-True ($null -ne $schema.paths.'/api/v1/stays/search'.get) 'OpenAPI search path missing'
  Set-MockState 'b' 'timeout'
  $watch = [System.Diagnostics.Stopwatch]::StartNew()
  $partial = Invoke-RestMethod "$AppUrl$searchPath"
  $watch.Stop()
  Assert-True ($partial.data.offers.Count -eq 1 -and $partial.meta.partial -and $partial.meta.supplierFailures[0].category -eq 'TIMEOUT') 'Partial timeout contract failed'
  Assert-True ($watch.Elapsed.TotalSeconds -lt 6) 'Timeout deadline exceeded'
  Set-MockState 'b' 'error'
  $bodyFailure = Invoke-RestMethod "$AppUrl$searchPath"
  Assert-True ($bodyFailure.meta.partial -and $bodyFailure.meta.supplierFailures[0].category -eq 'UPSTREAM_ERROR') 'Supplier body failure was hidden'
  Set-MockState 'a' 'error'
  $status = 0
  try { Invoke-RestMethod "$AppUrl$searchPath" | Out-Null }
  catch {
    if ($null -eq $_.Exception.Response) { throw }
    $status = [int]$_.Exception.Response.StatusCode
  }
  Assert-True ($status -eq 503) 'All suppliers unavailable must return 503'
  Write-Output 'PASS: normal gross prices, OpenAPI, partial timeout, body error, all unavailable'
}
finally {
  Set-MockState 'a' 'Started'
  Set-MockState 'b' 'Started'
}
