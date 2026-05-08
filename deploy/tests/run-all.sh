#!/usr/bin/env bash
# Run all four tiers. Exits non-zero on first failure.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for tier in tier-a-liveness tier-b-schema tier-c-e2e tier-d-ha-off; do
  echo "════════════════════════════════════════════════════════════════"
  echo "  ${tier}"
  echo "════════════════════════════════════════════════════════════════"
  bash "${HERE}/${tier}.sh"
  echo
done

echo "════════════════════════════════════════════════════════════════"
echo "  ✅ ALL TIERS PASS"
echo "════════════════════════════════════════════════════════════════"
