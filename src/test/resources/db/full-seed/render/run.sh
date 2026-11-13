#!/bin/sh
# Entry point of the throwaway python container that draws the seed's own pictures.
# stdin: the manifest from render-manifest.sql. Needs internet for pip (and apt, optional).
set -e
cat > /tmp/manifest.jsonl
# libraqm shapes Thai text properly; without it the renderer falls back to basic layout.
(apt-get update -qq && apt-get install -y -qq --no-install-recommends libraqm0) </dev/null >/dev/null 2>&1 \
    || echo "render: libraqm not installed, Thai text uses basic layout"
pip install --quiet --disable-pip-version-check --root-user-action=ignore pillow==11.3.0 minio==7.2.15 </dev/null
exec python /render/render.py < /tmp/manifest.jsonl
