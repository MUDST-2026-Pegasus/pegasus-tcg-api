<#
.SYNOPSIS
Loads the small demo data set for testers: one admin, two shops, two buyers and a
shop waiting for approval; Pokemon and One Piece only; a few real orders.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\seed-demo.ps1
Wipes the database's data, then loads the demo data.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File scripts\seed-demo.ps1 -SkipImages
Reloads the data only; the pictures are already in MinIO.

.NOTES
ALWAYS EMPTIES THE DATABASE FIRST (accounts, products, orders, everything except the
migration history and the role list). Loading is one transaction: if it fails,
nothing is changed. Needs the postgres and minio containers from docker-compose.yaml
running, and the API started once so Flyway has created the tables. Same steps as
scripts/seed-demo.sh.
#>
param(
    [switch]$SkipImages
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$fullDir = Join-Path $root 'src\test\resources\db\full-seed'
$demoDir = Join-Path $root 'src\test\resources\db\demo-seed'

function Fail([string]$message) {
    Write-Host "seed-demo: $message" -ForegroundColor Red
    exit 1
}

function Invoke-Docker {
    # Runs docker and stops on a non-zero exit, returning what it printed.
    $output = & docker @args
    if ($LASTEXITCODE -ne 0) { Fail "docker $($args -join ' ') failed" }
    return $output
}

function Invoke-DockerQuiet {
    # Runs docker without letting its stderr stop the script (Windows PowerShell turns
    # a native command's stderr into an error under -ErrorAction Stop). Returns stdout.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { return (& docker @args 2>$null) } finally { $ErrorActionPreference = $old }
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

foreach ($container in @($pg, $minio)) {
    $state = Invoke-DockerQuiet inspect -f '{{.State.Running}}' $container
    if ($LASTEXITCODE -ne 0 -or $state -ne 'true') {
        Fail "container $container is not running. Start it with: docker compose up -d postgres minio"
    }
}

$ready = & docker exec $pg psql -U $user -d $db -Atc "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'inventory_movement' AND column_name = 'stock_in_batch_uid'"
if ($LASTEXITCODE -ne 0) { Fail "cannot open database $db as $user" }
if ("$ready".Trim() -ne '1') {
    Fail "database $db is missing the latest tables. Start the API once (gradlew.bat bootRun) so Flyway applies the migrations, then run this again."
}

Write-Host "Database: $db   Bucket: $bucket   Public MinIO: $public"
Write-Host "This wipes every account, product and order in $db before loading."

foreach ($container in @($pg, $minio)) {
    Invoke-Docker exec $container rm -rf /tmp/pegasus-full-seed /tmp/pegasus-demo-seed | Out-Null
    Invoke-Docker cp $fullDir "${container}:/tmp/pegasus-full-seed" | Out-Null
}
Invoke-Docker cp $demoDir "${pg}:/tmp/pegasus-demo-seed" | Out-Null

Write-Host '==> Cleaning the database and loading the demo data (one transaction; nothing changes if any check fails)'
& docker exec $pg sh /tmp/pegasus-demo-seed/run-demo-seed.sh $db $user $base
if ($LASTEXITCODE -ne 0) { Fail 'the data did not load; the database was left as it was' }

$keys = @(Invoke-Docker exec $pg psql -U $user -d $db -At -v "minio_base=$base" -f /tmp/pegasus-full-seed/image-keys.sql)

if (-not $SkipImages) {
    # Only the product pictures this data points at, out of the full seed's list.
    Write-Host '==> Uploading pictures to MinIO'
    $want = @{}
    foreach ($k in $keys) { if ($k) { $want["$k".Trim()] = $true } }
    $lines = Get-Content -Encoding UTF8 (Join-Path $fullDir 'image-sources.txt') | Where-Object {
        $_ -and -not $_.StartsWith('#') -and $want.ContainsKey(($_ -split '\s+')[0])
    }
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding $false))
    Invoke-Docker cp $tmp "${minio}:/tmp/pegasus-full-seed/image-sources.txt" | Out-Null
    Remove-Item $tmp
    & docker exec $minio sh /tmp/pegasus-full-seed/upload-images.sh $bucket
    if ($LASTEXITCODE -ne 0) { Fail 'uploading pictures failed' }

    # Transfer slips, parcel receipts and payout slips of these orders are drawn from the
    # data in a throwaway python container. Pictures already in MinIO are skipped.
    Write-Host '==> Drawing SAMPLE slips and receipts (the first run takes a minute or two)'
    $render = 'pegasus-seed-render'
    $image = if ($env:RENDER_IMAGE) { $env:RENDER_IMAGE } else { 'python:3.12-slim-bookworm' }
    $network = Invoke-Docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' $minio
    $minioUser = Invoke-Docker exec $minio printenv MINIO_ROOT_USER
    $minioPassword = Invoke-Docker exec $minio printenv MINIO_ROOT_PASSWORD
    Invoke-DockerQuiet rm -f $render | Out-Null
    Invoke-Docker create --name $render -i --network "$network".Trim() -e "MINIO_URL=${minio}:9000" -e "BUCKET=$bucket" `
        -e "MINIO_USER=$("$minioUser".Trim())" -e "MINIO_PASSWORD=$("$minioPassword".Trim())" $image sh /render/run.sh | Out-Null
    Invoke-Docker cp (Join-Path $fullDir 'render') "${render}:/render" | Out-Null
    # cmd keeps the manifest a byte stream between the two docker commands.
    & cmd /c "docker exec $pg psql -U $user -d $db -q -f /tmp/pegasus-full-seed/render-manifest.sql | docker start -ai $render"
    $renderExit = $LASTEXITCODE
    Invoke-DockerQuiet rm -f $render | Out-Null
    if ($renderExit -ne 0) { Fail 'drawing the pictures failed (see above). The data is loaded; fix the problem and run again' }
}

Write-Host '==> Checking that every picture the data points at is in MinIO'
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
Write-Host "All $($keys.Count) pictures are in MinIO."

Invoke-Docker exec $pg rm -rf /tmp/pegasus-full-seed /tmp/pegasus-demo-seed | Out-Null
Invoke-Docker exec $minio rm -rf /tmp/pegasus-full-seed | Out-Null

Write-Host ''
Write-Host 'Done. Every account signs in with Password123!'
Write-Host '  admin@example.com      admin'
Write-Host '  seller@example.com     shop "Demo Seller Shop" (Pokemon)'
Write-Host '  tcgcorner@example.com  shop "TCG Corner" (One Piece)'
Write-Host '  buyer@example.com      buyer'
Write-Host '  mai@example.com        buyer'
Write-Host '  newbie@example.com     shop waiting for the admin''s approval'
Write-Host 'DEMO_SEED.md says what to try with each one.'
