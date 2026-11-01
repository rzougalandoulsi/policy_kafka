#!/usr/bin/env bash
# While the policies live inside policy_kafka, Governance's pinned schema copy (../contracts/opa) and this
# repository's contracts/ must be identical. After the extraction the directory does not exist and the check is a no-op
# (Governance then pins a published contracts version instead).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
other="../contracts/opa"
if [ ! -d "$other" ]; then echo "contract sync: no sibling Governance copy, skipped"; exit 0; fi
status=0
for f in input.schema.json output.schema.json; do
  if ! cmp -s "contracts/$f" "$other/$f"; then echo "contracts/$f differs from $other/$f"; status=1; fi
done
[ $status -eq 0 ] && echo "contracts in sync with ../contracts/opa"
exit $status
