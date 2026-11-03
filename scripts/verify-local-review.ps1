$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$envFile = Join-Path $repoRoot ".env.local-review"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Run scripts/start-local-review.ps1 before verification."
}

$project = "ledger-phase61-review"
$composeArgs = @(
    "compose",
    "--project-name", $project,
    "--env-file", $envFile,
    "-f", "compose.yaml",
    "-f", "compose.local-review.yaml"
)

foreach ($container in @(
    "ledger-phase61-review-postgres",
    "ledger-phase61-review-backend-1",
    "ledger-phase61-review-frontend-1"
)) {
    $health = docker inspect --format '{{.State.Health.Status}}' $container
    if ($LASTEXITCODE -ne 0 -or $health.Trim() -ne "healthy") {
        throw "$container health is '$health', expected 'healthy'."
    }
    Write-Host "HEALTH $container => $($health.Trim())"
}

$baseUrl = "http://127.0.0.1:3000"
foreach ($route in @("/transactions", "/reconciliation", "/system-health")) {
    $response = Invoke-WebRequest -Uri "$baseUrl$route" -Method Get
    if ($response.StatusCode -ne 200) { throw "$route returned $($response.StatusCode)." }
    Write-Host "UI $route => $($response.StatusCode)"
}

$settings = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] }
}
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

try {
    Invoke-RestMethod -Uri "$baseUrl/api/ledger/api/v1/transactions?page=0&size=25" | Out-Null
    throw "Unauthenticated financial read unexpectedly succeeded."
} catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($statusCode -ne 401) { throw }
}
Write-Host "Unauthenticated financial read => 401"
try {
    Invoke-WebRequest -Uri "$baseUrl/api/ledger/api/v1/settlement-batches" -Method Post `
        -Headers @{ Origin = $baseUrl } -ErrorAction Stop | Out-Null
    throw "Unauthenticated journal posting unexpectedly succeeded."
} catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($statusCode -ne 401) { throw }
}
Write-Host "Unauthenticated financial write => 401"

$null = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post -WebSession $session `
    -ContentType "application/json" -Headers @{ Origin = $baseUrl } `
    -Body (@{ username = $settings.LEDGER_OPERATOR_USERNAME; password = $settings.LEDGER_OPERATOR_PASSWORD } | ConvertTo-Json -Compress)
$operatorHeaders = @{ Origin = $baseUrl }

try {
    Invoke-WebRequest -Uri "$baseUrl/api/ledger/api/v1/settlement-batches/00000000-0000-4000-8000-000000000000/reconciliation-runs" `
        -Method Post -WebSession $session -Headers $operatorHeaders -ErrorAction Stop | Out-Null
    throw "Authorized request for a missing batch unexpectedly succeeded."
} catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($statusCode -ne 404) { throw }
}
Write-Host "Authenticated same-origin mutation reaches API => expected 404 for absent batch"

$healthResponse = Invoke-RestMethod -Uri "$baseUrl/api/ledger/actuator/health/readiness" -WebSession $session
if ($healthResponse.status -ne "UP") { throw "Readiness API is not UP." }
Write-Host "API readiness => $($healthResponse.status)"

foreach ($path in @(
    "api/v1/transactions?page=0&size=25",
    "api/v1/settlement-batches?page=0&size=25"
)) {
    $response = Invoke-RestMethod -Uri "$baseUrl/api/ledger/$path" -WebSession $session
    if ($response.page -ne 0 -or $response.size -ne 25) {
        throw "Paged API returned unexpected pagination for $path."
    }
    Write-Host "API $path => page=$($response.page), size=$($response.size)"
}

try {
    Invoke-WebRequest -Uri "$baseUrl/api/ledger/api/v1/settlement-batches/00000000-0000-4000-8000-000000000000/reconciliation-runs" `
        -Method Post -WebSession $session -Headers @{ Origin = "http://evil.example" } -ErrorAction Stop | Out-Null
    throw "Invalid-origin mutation unexpectedly succeeded."
} catch {
    $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    if ($statusCode -ne 403) { throw }
}
Write-Host "Authenticated invalid-origin mutation => 403"



try {
    $unsupported = Invoke-WebRequest -Uri "$baseUrl/api/ledger/api/v1/not-allowed" -UseBasicParsing -WebSession $session
    throw "Proxy allowed an unsupported route; expected HTTP 404, got $($unsupported.StatusCode)."
}
catch {
    if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) {
        Write-Host "Proxy rejects unsupported paths => 404"
    }
    else {
        throw
    }
}




$frontendBindings = (docker inspect ledger-phase61-review-frontend-1 --format '{{json .HostConfig.PortBindings}}' | ConvertFrom-Json)
$uiBinding = $frontendBindings.'3000/tcp'
if ($uiBinding.Count -ne 1 -or $uiBinding[0].HostIp -ne "127.0.0.1") {
    throw "UI must publish only on 127.0.0.1."
}
$dbBindings = docker inspect ledger-phase61-review-postgres --format '{{json .HostConfig.PortBindings}}'
if ($dbBindings.Trim() -ne "{}") { throw "Review PostgreSQL must not publish a host port." }
Write-Host "Port binding => UI 127.0.0.1:$($uiBinding[0].HostPort); PostgreSQL unpublished"

$migrations = docker exec ledger-phase61-review-postgres psql -U ledger_bootstrap -d ledger -X -At -c "SELECT string_agg(version, ',' ORDER BY installed_rank) FROM public.flyway_schema_history;"
if ($migrations.Trim() -ne "1,2,3,4,5,6,7") { throw "Expected V1-V7; found '$migrations'." }
$runtimeSuperuser = docker exec ledger-phase61-review-postgres psql -U ledger_bootstrap -d ledger -X -At -c "SELECT rolsuper FROM pg_roles WHERE rolname='ledger_runtime';"
if ($runtimeSuperuser.Trim() -ne "f") { throw "ledger_runtime must not be a superuser." }
Write-Host "Database migrations => $($migrations.Trim()); ledger_runtime superuser => false"

& docker @composeArgs ps
