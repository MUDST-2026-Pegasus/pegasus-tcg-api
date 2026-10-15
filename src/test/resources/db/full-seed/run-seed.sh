#!/bin/sh
# Loads the full seed in one transaction. Runs inside the pegasus-tcg-postgres
# container (scripts/seed-full.sh and seed-full.ps1 copy this folder in first):
#
#   sh /tmp/pegasus-full-seed/run-seed.sh <database> <user> <minio base url> <reset 0|1>
#
# minio base url is what a browser uses to reach the bucket, e.g.
# http://localhost:9000/pegasus; game and card-set logos are stored as URLs under it.
#
# Without reset the seed refuses a database that already has accounts, products
# or orders. Either way the tables are emptied and their ids restarted before
# loading, so every run produces the same ids.
set -e

db="$1"
user="$2"
base="$3"
reset="${4:-0}"
here="$(cd "$(dirname "$0")" && pwd)"

{
    echo '\set ON_ERROR_STOP on'
    echo 'SET client_min_messages = warning;'
    echo '\o /dev/null'
    echo 'BEGIN;'
    [ "$reset" = 1 ] || cat "$here/00_guard.sql"
    cat "$here/00_reset.sql" "$here/01_setup.sql" "$here/02_accounts.sql" "$here/03_catalog.sql" \
        "$here/04_engine.sql" "$here/05_market.sql" "$here/05_offers.sql" "$here/06_orders.sql" \
        "$here/07_extras.sql" "$here/07_collectors.sql"
    echo '\o'
    cat "$here/99_verify.sql"
    echo 'COMMIT;'
} | psql -U "$user" -d "$db" -q -v ON_ERROR_STOP=1 -v minio_base="$base"
