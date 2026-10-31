$ErrorActionPreference = "Stop"
$backend = Join-Path $PSScriptRoot "backend"
if (-not $env:MONGODB_URI) {
  Write-Host "Set MONGODB_URI in this PowerShell session before running this script." -ForegroundColor Yellow
  Write-Host '$env:MONGODB_URI = "mongodb+srv://<user>:<URL-encoded-password>@<cluster-host>/saburi_travel?retryWrites=true&w=majority"'
  exit 1
}
if (-not $env:MONGODB_DATABASE) { $env:MONGODB_DATABASE = "saburi_travel" }
Set-Location $backend
# Do not force IPv4 or alter MAVEN_OPTS: network routing must be diagnosed from
# the actual MongoDB driver error rather than hidden by a speculative JVM flag.
Write-Host "Java runtime:" -ForegroundColor Cyan
java -version
Write-Host "Running clean verification before starting the app..." -ForegroundColor Cyan
mvn clean verify
if ($LASTEXITCODE -ne 0) { Write-Error "Maven verification failed."; exit $LASTEXITCODE }
Write-Host "Starting Saburi Swami Travel..." -ForegroundColor Cyan
mvn spring-boot:run
exit $LASTEXITCODE
