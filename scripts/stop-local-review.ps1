$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$envFile = Join-Path $repoRoot ".env.local-review"
if (-not (Test-Path -LiteralPath $envFile)) {
    Write-Host "No local review environment file exists; nothing to stop."
    exit 0
}

$composeArgs = @(
    "compose",
    "--project-name", "ledger-phase61-review",
    "--env-file", $envFile,
    "-f", "compose.yaml",
    "-f", "compose.local-review.yaml",
    "down"
)
& docker @composeArgs
exit $LASTEXITCODE
