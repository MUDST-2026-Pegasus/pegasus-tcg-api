#!/bin/sh
# Puts every picture the full seed refers to into MinIO. Runs inside the
# pegasus-tcg-minio container, which has mc, curl and the root login in its
# environment; scripts/seed-full.sh and seed-full.ps1 copy this folder in and call it:
#
#   sh /tmp/pegasus-full-seed/upload-images.sh <bucket>
#
# 1. images/ holds the seed's own drawings (avatars, SAMPLE slips, bank books,
#    postage receipts, return photos). They are copied as they are.
# 2. image-sources.txt lists the real product pictures. They are downloaded from
#    their publishers' and brands' servers, not kept in git. A picture already
#    fetched from the same URL is not fetched again; one that cannot be fetched
#    gets a placeholder and is tried again on the next run.
# 3. Game and card-set logos are made readable without a signature, because the
#    database stores them as plain URLs (the API signs every other key itself).
#
# Safe to run again. Uses only sh, mc, curl and sha1sum: the image has no grep or awk.
set -e

bucket="${1:-pegasus}"
here="$(cd "$(dirname "$0")" && pwd)"
cr="$(printf '\r')"
agent="PegasusTCG-seed/1.0 (+https://github.com/MUDST-2026-Pegasus/pegasus-tcg-api)"

mc alias set seed http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mb --ignore-existing "seed/$bucket" >/dev/null

mc cp --recursive --quiet "$here/images/" "seed/$bucket/" >/dev/null
echo "Seed documents uploaded to $bucket/"

fetched=0
kept=0
failed=0
while read -r key url kind; do
    case "$key" in ''|'#'*) continue ;; esac
    kind="${kind%"$cr"}"
    target="seed/$bucket/$key"
    sum="$(printf '%s' "$url" | sha1sum)"
    sum="${sum%% *}"

    case "$(mc stat "$target" 2>/dev/null)" in
        *"$sum"*) kept=$((kept + 1)); continue ;;
    esac

    if ctype="$(curl -fsSL --max-time 60 -A "$agent" -o /tmp/seed-image -w '%{content_type}' "$url")"; then
        ctype="${ctype%%;*}"
        case "$ctype" in
            image/*)
                mc cp --quiet --attr "Content-Type=$ctype;X-Amz-Meta-Source-Sha1=$sum" /tmp/seed-image "$target" >/dev/null
                fetched=$((fetched + 1))
                continue
                ;;
        esac
    fi
    echo "  could not fetch $url, using a placeholder for $key"
    mc cp --quiet --attr "Content-Type=image/png" "$here/placeholders/$kind.png" "$target" >/dev/null
    failed=$((failed + 1))
done < "$here/image-sources.txt"
rm -f /tmp/seed-image

mc anonymous set download "seed/$bucket/seed/games" >/dev/null
mc anonymous set download "seed/$bucket/seed/sets" >/dev/null

echo "Product pictures: $fetched downloaded, $kept already there, $failed placeholder(s)"
