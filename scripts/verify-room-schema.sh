#!/usr/bin/env bash
# Verifies the Room schema that KSP exported.
#
# Three failures this catches, in order of how quietly they would otherwise ship:
#
#  1. Schema export silently switched off. `room.schemaLocation` is a KSP argument;
#     a build-file change can drop it, and then no migration can ever be written
#     against a known baseline.
#  2. A table disappearing. Renaming an entity or forgetting to register it in
#     @Database compiles fine and loses data on the next release.
#  3. A user-data table losing a column. Favourites, watch progress and provider
#     credentials cannot be rebuilt from anywhere, so their columns are asserted
#     by name.
#
# It also diffs against a committed baseline when one exists, which turns any
# unintended schema change into a red build rather than a surprise migration.
set -euo pipefail

SCHEMA_DIR="data/database/schemas"

if [ ! -d "$SCHEMA_DIR" ]; then
  echo "::error::No schema directory at $SCHEMA_DIR — KSP did not export the Room schema."
  echo "Check that data/database/build.gradle.kts still sets room.schemaLocation."
  exit 1
fi

SCHEMA_FILE=$(find "$SCHEMA_DIR" -name '*.json' | sort | tail -1)
if [ -z "${SCHEMA_FILE:-}" ]; then
  echo "::error::No exported schema JSON under $SCHEMA_DIR."
  exit 1
fi
echo "Exported schema: $SCHEMA_FILE"

required_tables=(media media_fts media_group programme favorite playback_progress source)
for table in "${required_tables[@]}"; do
  if ! grep -q "\"tableName\": \"$table\"" "$SCHEMA_FILE"; then
    echo "::error::Table '$table' is missing from the exported schema."
    exit 1
  fi
done
echo "All ${#required_tables[@]} tables present."

# User data is the part no re-import can restore, so its columns are named here.
required_columns=(
  "media_id"        # favorite, playback_progress
  "position_ms"     # playback_progress
  "added_at"        # favorite
  "content_hash"    # source — sync state
  "last_import_at"  # source — sync state
  "provider_ref"    # media — needed for short EPG and catch-up
  "search_text"     # media + media_fts — the search index
)
for column in "${required_columns[@]}"; do
  if ! grep -q "\"columnName\": \"$column\"" "$SCHEMA_FILE"; then
    echo "::error::Column '$column' is missing from the exported schema."
    exit 1
  fi
done
echo "All ${#required_columns[@]} guarded columns present."

VERSION=$(grep -o '"version": [0-9]*' "$SCHEMA_FILE" | head -1 | grep -o '[0-9]*')
HASH=$(grep -o '"identityHash": "[a-f0-9]*"' "$SCHEMA_FILE" | head -1 | cut -d'"' -f4)
echo "Schema version $VERSION, identity hash ${HASH:-unknown}"

{
  echo "### Room schema"
  echo
  echo "- File: \`${SCHEMA_FILE#./}\`"
  echo "- Version: \`$VERSION\`"
  echo "- Identity hash: \`${HASH:-unknown}\`"
  echo "- Tables: ${required_tables[*]}"
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

# A committed baseline turns an unintended change into a failure rather than a
# surprise. Until one is committed, this reports and passes.
if git ls-files --error-unmatch "$SCHEMA_FILE" >/dev/null 2>&1; then
  if ! git diff --exit-code -- "$SCHEMA_FILE"; then
    echo "::error::The exported schema differs from the committed baseline."
    echo "If the change is intended, commit the regenerated schema together with a migration."
    exit 1
  fi
  echo "Schema matches the committed baseline."
else
  echo "::notice::No committed baseline for $SCHEMA_FILE yet — it is uploaded as an artifact."
  echo "Commit it to turn future schema changes into a build failure."
fi
