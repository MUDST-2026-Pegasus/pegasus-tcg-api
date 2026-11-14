#!/bin/sh
# Loads the demo seed in one transaction. Runs inside the pegasus-tcg-postgres
# container; scripts/seed-demo.sh and seed-demo.ps1 copy this folder to
# /tmp/pegasus-demo-seed and full-seed/ to /tmp/pegasus-full-seed first:
#
#   sh /tmp/pegasus-demo-seed/run-demo-seed.sh <database> <user> <minio base url>
#
# It always starts by emptying every application table (full-seed/00_reset.sql):
# accounts, catalogue, listings, orders, money, everything except the migration
# history and the role list. If any step or check fails, the transaction rolls
# back and the database is left exactly as it was.
#
# From full-seed/ it reuses the reset, the shared clock and settings, the catalogue
# and the replay engine; the people, listings and orders are the demo's own.
set -e

db="$1"
user="$2"
base="$3"
full=/tmp/pegasus-full-seed
here="$(cd "$(dirname "$0")" && pwd)"

{
    echo '\set ON_ERROR_STOP on'
    echo 'SET client_min_messages = warning;'
    echo '\o /dev/null'
    echo 'BEGIN;'
    cat "$full/00_reset.sql" "$full/01_setup.sql" \
        "$here/01_accounts.sql" \
        "$full/03_catalog.sql" "$here/02_catalog_trim.sql" \
        "$full/04_engine.sql" \
        "$here/03_market.sql" "$here/04_orders.sql" "$here/05_extras.sql"
    echo '\o'
    cat "$here/99_verify.sql"
    echo 'COMMIT;'
} | psql -U "$user" -d "$db" -q -v ON_ERROR_STOP=1 -v minio_base="$base"
