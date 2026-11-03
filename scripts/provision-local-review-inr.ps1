$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$envFile = Join-Path $repoRoot ".env.local-review"
$sqlFile = Join-Path $repoRoot "src/main/resources/db/local-review/provision-inr.sql"
$container = "ledger-phase61-review-postgres"

if (-not (Test-Path -LiteralPath $envFile)) {
    throw "The local review environment file is missing. Start or restore the existing review stack first."
}
if (-not (Test-Path -LiteralPath $sqlFile)) {
    throw "INR provisioning SQL is missing: $sqlFile"
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
$projectLabel = $containerInfo.Config.Labels.'com.docker.compose.project'
if ($projectLabel -ne "ledger-phase61-review") {
    throw "Refusing provisioning: $container is not the ledger-phase61-review Compose database."
}
$serviceLabel = $containerInfo.Config.Labels.'com.docker.compose.service'
if ($serviceLabel -ne "postgres") {
    throw "Refusing provisioning: the target container is not the review PostgreSQL service."
}
if ($containerInfo.State.Running -ne $true) {
    throw "Refusing provisioning: the review PostgreSQL container is not running."
}

$sql = Get-Content -LiteralPath $sqlFile -Raw -Encoding ascii
$sql | docker exec -i $container psql -U ledger_bootstrap -d ledger -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) {
    throw "INR provisioning failed. The transaction was rolled back; inspect the database state before retrying."
}

Write-Host "INR is present and enabled in the isolated local review database (minor unit exponent 2)."
