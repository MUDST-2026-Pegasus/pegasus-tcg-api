#!/usr/bin/env bash
# Loads the small demo data set for testers: one admin, two shops, two buyers and a
# shop waiting for approval; Pokemon and One Piece only; a few real orders.
#
#   scripts/seed-demo.sh                 # wipe the database's data, then load
#   scripts/seed-demo.sh --skip-images   # reload the data only; pictures are already in MinIO
#
# ALWAYS EMPTIES THE DATABASE FIRST (accounts, products, orders, everything except
# the migration history and the role list). Loading is one transaction: if it
# fails, nothing is changed.
#
# Needs the postgres and minio containers from docker-compose.yaml running, and the
# API started once so Flyway has created the tables. Reads POSTGRES_DB,
# POSTGRES_USER, MINIO_BUCKET and MINIO_PUBLIC_ENDPOINT from .env when they are set.
# The first run downloads about 100 product pictures (internet needed).
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep /tmp paths for the containers as they are

root="$(cd "$(dirname "$0")/.." && pwd)"
full_dir="$root/src/test/resources/db/full-seed"
demo_dir="$root/src/test/resources/db/demo-seed"
# docker.exe on Windows needs a Windows path, not Git Bash's /d/... form.
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
skip_images=0

for arg in "$@"; do
    case "$arg" in
        --skip-images) skip_images=1 ;;
        -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

die() { echo "seed-demo: $*" >&2; exit 1; }

env_value() {
    local value=""
    if [ -f "$root/.env" ]; then
        value="$(grep -E "^$1=" "$root/.env" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
    fi
    echo "${value:-$2}"
}

db="${SEED_DB:-$(env_value POSTGRES_DB pegasus_tcg)}"
user="$(env_value POSTGRES_USER postgres)"
bucket="$(env_value MINIO_BUCKET pegasus)"
public="$(env_value MINIO_PUBLIC_ENDPOINT "$(env_value MINIO_ENDPOINT http://localhost:9000)")"
base="${public%/}/$bucket"
pg="${PG_CONTAINER:-pegasus-tcg-postgres}"
minio="${MINIO_CONTAINER:-pegasus-tcg-minio}"

running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]; }
running "$pg" || die "container $pg is not running. Start it with: docker compose up -d postgres minio"
running "$minio" || die "container $minio is not running. Start it with: docker compose up -d postgres minio"

ready="$(docker exec "$pg" psql -U "$user" -d "$db" -Atc \
    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'inventory_movement' AND column_name = 'stock_in_batch_uid'")" \
    || die "cannot open database $db as $user"
[ "$ready" = "1" ] || die "database $db is missing the latest tables. Start the API once (./gradlew bootRun) so Flyway applies the migrations, then run this again."

echo "Database: $db   Bucket: $bucket   Public MinIO: $public"
echo "This wipes every account, product and order in $db before loading."

for container in "$pg" "$minio"; do
    docker exec "$container" rm -rf /tmp/pegasus-full-seed /tmp/pegasus-demo-seed
    docker cp "$(winpath "$full_dir")" "$container:/tmp/pegasus-full-seed" >/dev/null
done
docker cp "$(winpath "$demo_dir")" "$pg:/tmp/pegasus-demo-seed" >/dev/null

echo "==> Cleaning the database and loading the demo data (one transaction; nothing changes if any check fails)"
docker exec "$pg" sh /tmp/pegasus-demo-seed/run-demo-seed.sh "$db" "$user" "$base"

keys="$(docker exec "$pg" psql -U "$user" -d "$db" -At -v minio_base="$base" -f /tmp/pegasus-full-seed/image-keys.sql)"

if [ "$skip_images" = 0 ]; then
    # Only the product pictures this data points at, out of the full seed's list.
    echo "==> Uploading pictures to MinIO"
    tmp="$(mktemp)"
    awk 'NR == FNR { want[$1] = 1; next } /^#/ || NF == 0 { next } ($1 in want)' \
        <(printf '%s\n' "$keys") "$full_dir/image-sources.txt" > "$tmp"
    docker cp "$(winpath "$tmp")" "$minio:/tmp/pegasus-full-seed/image-sources.txt" >/dev/null
    rm -f "$tmp"
    docker exec "$minio" sh /tmp/pegasus-full-seed/upload-images.sh "$bucket"

    # Transfer slips, parcel receipts and payout slips of these orders are drawn from the
    # data in a throwaway python container. Pictures already in MinIO are skipped.
    echo "==> Drawing SAMPLE slips and receipts (the first run takes a minute or two)"
    render="pegasus-seed-render"
    network="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$minio")"
    docker rm -f "$render" >/dev/null 2>&1 || true
    docker create --name "$render" -i --network "$network" \
        -e MINIO_URL="$minio:9000" -e BUCKET="$bucket" \
        -e MINIO_USER="$(docker exec "$minio" printenv MINIO_ROOT_USER)" \
        -e MINIO_PASSWORD="$(docker exec "$minio" printenv MINIO_ROOT_PASSWORD)" \
        "${RENDER_IMAGE:-python:3.12-slim-bookworm}" sh /render/run.sh >/dev/null
    docker cp "$(winpath "$full_dir/render")" "$render:/render" >/dev/null
    if ! docker exec "$pg" psql -U "$user" -d "$db" -q -f /tmp/pegasus-full-seed/render-manifest.sql \
            | docker start -ai "$render"; then
        docker rm -f "$render" >/dev/null 2>&1 || true
        die "drawing the pictures failed (see above). The data is loaded; fix the problem and run again"
    fi
    docker rm -f "$render" >/dev/null 2>&1 || true
fi

echo "==> Checking that every picture the data points at is in MinIO"
objects="$(docker exec "$minio" sh -c 'mc alias set seed http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --recursive "seed/$1"' sh "$bucket" | awk '{print $NF}')"
missing="$(comm -23 <(printf '%s\n' "$keys" | sort -u) <(printf '%s\n' "$objects" | sort -u))"
if [ -n "$missing" ]; then
    echo "Missing from MinIO ($(printf '%s\n' "$missing" | wc -l | tr -d ' ')):" >&2
    printf '  %s\n' $missing | head -n 20 >&2
    die "run again without --skip-images to upload them"
fi
echo "All $(printf '%s\n' "$keys" | wc -l | tr -d ' ') pictures are in MinIO."

docker exec "$pg" rm -rf /tmp/pegasus-full-seed /tmp/pegasus-demo-seed
docker exec "$minio" rm -rf /tmp/pegasus-full-seed

cat <<'EOF'

Done. Every account signs in with Password123!
  admin@example.com      admin
  seller@example.com     shop "Demo Seller Shop" (Pokemon)
  tcgcorner@example.com  shop "TCG Corner" (One Piece)
  buyer@example.com      buyer
  mai@example.com        buyer
  newbie@example.com     shop waiting for the admin's approval
DEMO_SEED.md says what to try with each one.
EOF
