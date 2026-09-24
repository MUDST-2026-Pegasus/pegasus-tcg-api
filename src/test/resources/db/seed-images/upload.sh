#!/bin/sh
# Puts the seed pictures into MinIO under <bucket>/seed/, the keys home_seed.sql
# points at. Runs inside the pegasus-tcg-minio container, which already has mc,
# curl and the root login in its environment:
#
#   docker cp src/test/resources/db/seed-images/. pegasus-tcg-minio:/tmp/seed-images
#   docker exec pegasus-tcg-minio sh -c "sh /tmp/seed-images/upload.sh pegasus"
#
# Two passes. The generated pictures in this folder go up first, so every key
# has something. Then the official card scans listed in card-images.txt are
# downloaded over them; those are not kept in git, since they belong to their
# publishers. A scan that cannot be fetched leaves the generated picture there,
# and one fetched on an earlier run is not fetched again: YGOPRODeck in
# particular asks for each image to be downloaded once and hosted by the user.
#
# Safe to run again.
set -e

bucket="${1:-pegasus}"
here="$(cd "$(dirname "$0")" && pwd)"

mc alias set seed http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "seed/$bucket" >/dev/null

for dir in games categories products banners; do
    for file in "$here/$dir"/*; do
        target="seed/$bucket/seed/$dir/$(basename "$file")"
        # keep a scan fetched on an earlier run rather than putting the drawing back over it
        case "$(mc stat "$target" 2>/dev/null)" in
            *Source*) continue ;;
        esac
        mc cp --quiet "$file" "$target" >/dev/null
    done
done
echo "Uploaded the generated pictures to $bucket/seed/"

fetched=0
kept=0
failed=0
while read -r key url; do
    case "$key" in ''|'#'*) continue ;; esac
    target="seed/$bucket/seed/$key"

    case "$(mc stat "$target" 2>/dev/null)" in
        *"$url"*) kept=$((kept + 1)); continue ;;
    esac

    case "$url" in
        *.png|*.png\?*) type=image/png ;;
        *) type=image/jpeg ;;
    esac

    if curl -fsSL --max-time 30 -A "PegasusTCG-seed/1.0" -o /tmp/seed-card "$url"; then
        mc cp --quiet --attr "Content-Type=$type;X-Amz-Meta-Source=$url" /tmp/seed-card "$target" >/dev/null
        fetched=$((fetched + 1))
        sleep 0.2
    else
        echo "  could not fetch $url, keeping the generated picture for $key"
        failed=$((failed + 1))
    fi
done < "$here/card-images.txt"
rm -f /tmp/seed-card

echo "Card scans: $fetched downloaded, $kept already there, $failed kept as generated"
