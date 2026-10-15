#!/usr/bin/env bash
# Loads the full demo data set: every table, real product pictures in MinIO.
#
#   scripts/seed-full.sh            # into an empty database (fresh from the migrations)
#   scripts/seed-full.sh --reset    # wipe the database's data first, then load
#   scripts/seed-full.sh --skip-images   # reload the data only; pictures are already in MinIO
#
# Needs the postgres and minio containers from docker-compose.yaml running, and the
# API started once so Flyway has created the tables. Reads POSTGRES_DB,
# POSTGRES_USER, MINIO_BUCKET and MINIO_PUBLIC_ENDPOINT from .env when they are set.
# Downloading the pictures needs internet access; one that fails gets a placeholder
# and is retried on the next run.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep /tmp paths for the containers as they are

root="$(cd "$(dirname "$0")/.." && pwd)"
seed_dir="$root/src/test/resources/db/full-seed"
# docker.exe on Windows needs a Windows path, not Git Bash's /d/... form.
if command -v cygpath >/dev/null 2>&1; then seed_src="$(cygpath -w "$seed_dir")"; else seed_src="$seed_dir"; fi
reset="${RESET:-0}"
skip_images=0

for arg in "$@"; do
    case "$arg" in
        --reset) reset=1 ;;
        --skip-images) skip_images=1 ;;
        -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

die() { echo "seed-full: $*" >&2; exit 1; }

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

echo "Database: $db   Bucket: $bucket   Public MinIO: $public   Reset: $([ "$reset" = 1 ] && echo yes || echo no)"

for container in "$pg" "$minio"; do
    docker exec "$container" rm -rf /tmp/pegasus-full-seed
    docker cp "$seed_src" "$container:/tmp/pegasus-full-seed" >/dev/null
done

if [ "$skip_images" = 0 ]; then
    echo "==> Uploading pictures to MinIO (the first run downloads about 60 MB)"
    docker exec "$minio" sh /tmp/pegasus-full-seed/upload-images.sh "$bucket"
fi

echo "==> Loading the data (one transaction; nothing changes if any check fails)"
docker exec "$pg" sh /tmp/pegasus-full-seed/run-seed.sh "$db" "$user" "$base" "$reset"

echo "==> Checking that every picture the data points at is in MinIO"
keys="$(docker exec "$pg" psql -U "$user" -d "$db" -At -v minio_base="$base" -f /tmp/pegasus-full-seed/image-keys.sql)"
objects="$(docker exec "$minio" sh -c 'mc alias set seed http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --recursive "seed/$1"' sh "$bucket" | awk '{print $NF}')"
missing="$(comm -23 <(printf '%s\n' "$keys" | sort -u) <(printf '%s\n' "$objects" | sort -u))"
if [ -n "$missing" ]; then
    echo "Missing from MinIO ($(printf '%s\n' "$missing" | wc -l | tr -d ' ')):" >&2
    printf '  %s\n' $missing | head -n 20 >&2
    die "run again without --skip-images to upload them"
fi
echo "All $(printf '%s\n' "$keys" | wc -l | tr -d ' ') pictures are in MinIO."

docker exec "$pg" rm -rf /tmp/pegasus-full-seed
docker exec "$minio" rm -rf /tmp/pegasus-full-seed

cat <<'EOF'

Done. Every account signs in with Password123!
  admin@example.com (ADMIN)           support@example.com (SUPPORT)
  store@pegasus.example.com, cardkingdom@example.com, tcgcorner@example.com,
  mintvault@example.com, seller@example.com, grandline@example.com (sellers)
  mai@example.com, ton@example.com, buyer@example.com, ploy@example.com (buyers)
See the README section "Full demo data" for what each account shows.
EOF
