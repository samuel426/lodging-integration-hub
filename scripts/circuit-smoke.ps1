param([string]$AppUrl = 'http://localhost:8080', [string]$MockUrl = 'http://localhost:9090')
$ErrorActionPreference = 'Stop'
$query = '/api/v1/stays/search?checkIn=2026-10-10&checkOut=2026-10-12&adults=2&children=0'
function Set-State([string]$Supplier, [string]$State) {
  Invoke-RestMethod -TimeoutSec 8 -Method Put -Uri "$MockUrl/__admin/scenarios/availability-$Supplier/state" -ContentType 'application/json' -Body (@{ state = $State } | ConvertTo-Json -Compress) | Out-Null
}
function Search { Invoke-RestMethod -TimeoutSec 8 "$AppUrl$query" }
function Wait-Healthy {
  $deadline = [DateTime]::UtcNow.AddSeconds(35)
  do {
    try { $result = Search }
    catch {
      if ([int]$_.Exception.Response.StatusCode -ne 503) { throw }
      Start-Sleep -Milliseconds 500
      continue
    }
    if (-not $result.meta.partial -and $result.data.offers.Count -eq 2) { return }
    Start-Sleep -Milliseconds 500
  } while ([DateTime]::UtcNow -lt $deadline)
  throw 'Normal search did not recover within 35 seconds'
}
try {
  Set-State 'a' 'Started'
  Set-State 'b' 'Started'
  Wait-Healthy
  # Close remaining probe states and establish a successful rolling window.
  for ($i = 0; $i -lt 10; $i++) { Search | Out-Null }
  Set-State 'b' 'error'
  $blocked = $false
  for ($i = 0; $i -lt 11; $i++) {
    $result = Search
    if ($result.data.offers.Count -ne 1 -or -not $result.meta.partial) { throw 'Healthy supplier result was not preserved' }
    if ($result.meta.supplierFailures[0].category -eq 'CIRCUIT_OPEN') { $blocked = $true; break }
  }
  if (-not $blocked) { throw 'Supplier B circuit did not open' }
  Write-Output 'PASS: supplier B blocked while supplier A remains available'
}
finally {
  Set-State 'a' 'Started'
  Set-State 'b' 'Started'
  Wait-Healthy
  $restored = Search
  if ($restored.meta.partial -or $restored.data.offers.Count -ne 2) { throw 'Recovery probes did not restore normal search' }
}
Write-Output 'PASS: recovery probes restored both suppliers'
