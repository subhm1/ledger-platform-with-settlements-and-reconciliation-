$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$envFile = Join-Path $repoRoot ".env.local-review"
if (-not (Test-Path -LiteralPath $envFile)) {
    docker volume inspect "ledger-phase61-review_postgres_data" *> $null
    if ($LASTEXITCODE -eq 0) {
        throw "The isolated review database volume already exists but .env.local-review is missing. Restore the matching credentials file before starting; do not create new credentials for an existing volume."
    }

    function New-LocalSecret {
        $bytes = [byte[]]::new(32)
        $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
        return [BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant()
    }

    @(
        "POSTGRES_PASSWORD=$(New-LocalSecret)"
        "LEDGER_DB_PASSWORD=$(New-LocalSecret)"
        "LEDGER_FLYWAY_PASSWORD=$(New-LocalSecret)"
        "LEDGER_UI_PORT=3000"
    ) | Set-Content -LiteralPath $envFile -Encoding ascii
}

function Add-MissingLocalSetting([string]$Name, [string]$Value) {
    $lines = Get-Content -LiteralPath $envFile
    if (-not ($lines | Where-Object { $_ -match "^$([regex]::Escape($Name))=" })) {
        Add-Content -LiteralPath $envFile -Value "$Name=$Value" -Encoding ascii
    }
}

function New-LocalPassword {
    $bytes = [byte[]]::new(32)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($bytes).Replace("-", "").ToLowerInvariant()
}

Add-MissingLocalSetting "LEDGER_OPERATOR_USERNAME" "ledger-operator"
Add-MissingLocalSetting "LEDGER_OPERATOR_PASSWORD" (New-LocalPassword)
Add-MissingLocalSetting "LEDGER_SESSION_SECRET" (New-LocalPassword)
Add-MissingLocalSetting "LEDGER_APP_ORIGIN" "http://127.0.0.1:3000"

function Invoke-LocalReviewCompose([string[]]$ActionArgs) {
    $composeArgs = @(
        "compose",
        "--project-name", "ledger-phase61-review",
        "--env-file", $envFile,
        "-f", "compose.yaml",
        "-f", "compose.local-review.yaml"
    ) + $ActionArgs
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Build sequentially to keep Docker Desktop's peak resource use predictable.
Invoke-LocalReviewCompose @("build", "postgres")
Invoke-LocalReviewCompose @("build", "backend")
Invoke-LocalReviewCompose @("build", "frontend")
Invoke-LocalReviewCompose @("up", "-d")

Write-Host "Local review stack is starting. UI: http://localhost:3000"
Write-Host "Check readiness with: docker compose --project-name ledger-phase61-review --env-file .env.local-review -f compose.yaml -f compose.local-review.yaml ps"
