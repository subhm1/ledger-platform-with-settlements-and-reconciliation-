param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ $_ -gt 0 })]
    [long]$InitialBalancePaise
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot
$envFile = Join-Path $repoRoot ".env.local-review"
$sqlFile = Join-Path $repoRoot "src/main/resources/db/local-review/provision-demo-wallets.sql"
$container = "ledger-phase61-review-postgres"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "The local review environment file is missing. Start or restore the existing review stack first."
}
if (-not (Test-Path -LiteralPath $sqlFile)) {
    throw "Demo wallet provisioning SQL is missing: $sqlFile"
}

$containerJson = (docker inspect $container 2>$null) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0) {
    throw "Refusing provisioning: cannot inspect the expected review PostgreSQL container."
}
$containerInfo = @(ConvertFrom-Json -InputObject $containerJson)
if ($containerInfo.Count -ne 1) {
    throw "Refusing provisioning: Docker returned unexpected inspection data for the review database."
}
$containerInfo = $containerInfo[0]
if ($containerInfo.Config.Labels.'com.docker.compose.project' -ne "ledger-phase61-review" -or
    $containerInfo.Config.Labels.'com.docker.compose.service' -ne "postgres" -or
    $containerInfo.State.Running -ne $true) {
    throw "Refusing provisioning: target must be the running postgres service in ledger-phase61-review."
}

$sql = Get-Content -LiteralPath $sqlFile -Raw -Encoding ascii
$sql | docker exec -i $container psql -U ledger_bootstrap -d ledger `
    -v ON_ERROR_STOP=1 -v "initial_balance_paise=$InitialBalancePaise"
if ($LASTEXITCODE -ne 0) {
    throw "Demo wallet provisioning failed. The SQL transaction was rolled back; inspect the review database before retrying."
}

Write-Host "Five demo INR wallets are provisioned with the requested simulated opening balance of $InitialBalancePaise paise each."
Write-Host "No real bank funds were moved. Repeating with the same amount is safe; a changed amount is rejected by posting idempotency."
