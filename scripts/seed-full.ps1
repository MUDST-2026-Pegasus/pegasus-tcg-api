<#
.SYNOPSIS
Loads the full demo data set: every table, real product pictures in MinIO.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\seed-full.ps1
Loads into an empty database (fresh from the migrations).

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\seed-full.ps1 -Reset
Wipes the database's data first, then loads.

.NOTES
Needs the postgres and minio containers from docker-compose.yaml running, and the
API started once so Flyway has created the tables. Reads POSTGRES_DB, POSTGRES_USER,
MINIO_BUCKET and MINIO_PUBLIC_ENDPOINT from .env when they are set. Downloading the
pictures needs internet access; one that fails gets a placeholder and is retried
on the next run. Same steps as scripts/seed-full.sh.
#>
param(
    [switch]$Reset,
    [switch]$SkipImages
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$seedDir = Join-Path $root 'src\test\resources\db\full-seed'

function Fail([string]$message) {
    Write-Host "seed-full: $message" -ForegroundColor Red
    exit 1
}

function Invoke-Docker {
    # Runs docker and stops on a non-zero exit, returning what it printed.
    $output = & docker @args
    if ($LASTEXITCODE -ne 0) { Fail "docker $($args -join ' ') failed" }
    return $output
}

$envValues = @{}
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $envValues[$Matches[1]] = $Matches[2].Trim() }
    }
}
function EnvValue([string]$key, [string]$default) {
    if ($envValues.ContainsKey($key) -and $envValues[$key] -ne '') { return $envValues[$key] }
    return $default
}

$db = if ($env:SEED_DB) { $env:SEED_DB } else { EnvValue 'POSTGRES_DB' 'pegasus_tcg' }
$user = EnvValue 'POSTGRES_USER' 'postgres'
$bucket = EnvValue 'MINIO_BUCKET' 'pegasus'
$public = EnvValue 'MINIO_PUBLIC_ENDPOINT' (EnvValue 'MINIO_ENDPOINT' 'http://localhost:9000')
$base = $public.TrimEnd('/') + '/' + $bucket
$pg = if ($env:PG_CONTAINER) { $env:PG_CONTAINER } else { 'pegasus-tcg-postgres' }
$minio = if ($env:MINIO_CONTAINER) { $env:MINIO_CONTAINER } else { 'pegasus-tcg-minio' }
$resetFlag = if ($Reset -or $env:RESET -eq '1') { '1' } else { '0' }

foreach ($container in @($pg, $minio)) {
    $state = & docker inspect -f '{{.State.Running}}' $container 2>$null
    if ($LASTEXITCODE -ne 0 -or $state -ne 'true') {
        Fail "container $container is not running. Start it with: docker compose up -d postgres minio"
    }
}

$ready = & docker exec $pg psql -U $user -d $db -Atc "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'inventory_movement' AND column_name = 'stock_in_batch_uid'"
if ($LASTEXITCODE -ne 0) { Fail "cannot open database $db as $user" }
if ("$ready".Trim() -ne '1') {
    Fail "database $db is missing the latest tables. Start the API once (gradlew.bat bootRun) so Flyway applies the migrations, then run this again."
}

Write-Host "Database: $db   Bucket: $bucket   Public MinIO: $public   Reset: $(if ($resetFlag -eq '1') { 'yes' } else { 'no' })"

foreach ($container in @($pg, $minio)) {
    Invoke-Docker exec $container rm -rf /tmp/pegasus-full-seed | Out-Null
    Invoke-Docker cp $seedDir "${container}:/tmp/pegasus-full-seed" | Out-Null
}

if (-not $SkipImages) {
    Write-Host '==> Uploading pictures to MinIO (the first run downloads about 60 MB)'
    & docker exec $minio sh /tmp/pegasus-full-seed/upload-images.sh $bucket
    if ($LASTEXITCODE -ne 0) { Fail 'uploading pictures failed' }
}

Write-Host '==> Loading the data (one transaction; nothing changes if any check fails)'
& docker exec $pg sh /tmp/pegasus-full-seed/run-seed.sh $db $user $base $resetFlag
if ($LASTEXITCODE -ne 0) { Fail 'the data did not load; the database was left as it was' }

Write-Host '==> Checking that every picture the data points at is in MinIO'
$keys = Invoke-Docker exec $pg psql -U $user -d $db -At -v "minio_base=$base" -f /tmp/pegasus-full-seed/image-keys.sql
$listing = Invoke-Docker exec $minio sh -c 'mc alias set seed http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --recursive "seed/$1"' sh $bucket
$objects = @{}
foreach ($line in $listing) {
    $parts = "$line".Trim() -split '\s+'
    if ($parts.Count -gt 0) { $objects[$parts[-1]] = $true }
}
$missing = @($keys | Where-Object { $_ -and -not $objects.ContainsKey($_) })
if ($missing.Count -gt 0) {
    Write-Host "Missing from MinIO ($($missing.Count)):" -ForegroundColor Red
    $missing | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" }
    Fail 'run again without -SkipImages to upload them'
}
Write-Host "All $(@($keys).Count) pictures are in MinIO."

Invoke-Docker exec $pg rm -rf /tmp/pegasus-full-seed | Out-Null
Invoke-Docker exec $minio rm -rf /tmp/pegasus-full-seed | Out-Null

Write-Host ''
Write-Host 'Done. Every account signs in with Password123!'
Write-Host '  admin@example.com (ADMIN)           support@example.com (SUPPORT)'
Write-Host '  store@pegasus.example.com, cardkingdom@example.com, tcgcorner@example.com,'
Write-Host '  mintvault@example.com, seller@example.com, grandline@example.com (sellers)'
Write-Host '  mai@example.com, ton@example.com, buyer@example.com, ploy@example.com (buyers)'
Write-Host 'See the README section "Full demo data" for what each account shows.'
