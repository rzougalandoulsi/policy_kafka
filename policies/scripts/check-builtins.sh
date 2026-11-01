#!/usr/bin/env bash
# Policies must be pure functions of input and bundle data: no network (http.send) and no runtime introspection
# (opa.runtime). The platform principle is that Governance supplies every piece of context through input.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
status=0
for builtin in 'http\.send' 'opa\.runtime' 'net\.lookup_ip_addr'; do
  if grep -rn --include='*.rego' -E "(^|[^a-zA-Z_.])${builtin}\(" bundles tests; then
    echo "forbidden builtin ${builtin//\\/} used"; status=1
  fi
done
[ $status -eq 0 ] && echo "no forbidden builtins"
exit $status
