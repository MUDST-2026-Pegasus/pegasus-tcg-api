#!/bin/sh
# Puts the seed pictures into MinIO under <bucket>/seed/, the keys home_seed.sql
# points at. Runs inside the pegasus-tcg-minio container, which already has mc and
# the root login in its environment:
#
#   docker cp src/test/resources/db/seed-images/. pegasus-tcg-minio:/tmp/seed-images
#   docker exec pegasus-tcg-minio sh -c "sh /tmp/seed-images/upload.sh pegasus"
#
# Safe to run again: existing objects are overwritten with the same picture.
set -e

bucket="${1:-pegasus}"
here="$(cd "$(dirname "$0")" && pwd)"

mc alias set seed http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "seed/$bucket" >/dev/null

for dir in games categories products banners; do
    mc cp --recursive --quiet "$here/$dir/" "seed/$bucket/seed/$dir/" >/dev/null
done

echo "Uploaded $(mc ls --recursive "seed/$bucket/seed/" | wc -l) seed pictures to $bucket/seed/"
