#!/usr/bin/env bash
# Downloads the static OPA binary for OPA_VERSION and verifies it against opa.sha256 (pinned digests, not the
# checksum file published next to the asset). Installs to ${1:-$HOME/.local/bin}/opa. Used by the workflows.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT/OPA_VERSION")"
DEST_DIR="${1:-$HOME/.local/bin}"
os="$(uname -s | tr '[:upper:]' '[:lower:]')"
arch="$(uname -m)"
case "$arch" in x86_64|amd64) arch=amd64 ;; arm64|aarch64) arch=arm64 ;; *) echo "unsupported arch $arch" >&2; exit 1 ;; esac
asset="opa_${os}_${arch}_static"
expected="$(awk -v a="$asset" '$2 == a {print $1}' "$ROOT/opa.sha256")"
if [ -z "$expected" ]; then echo "no pinned digest for $asset in opa.sha256" >&2; exit 1; fi
tmp="$(mktemp -d)"
curl -sSfL --retry 3 -o "$tmp/opa" "https://github.com/open-policy-agent/opa/releases/download/v${VERSION}/${asset}"
actual="$(sha256sum "$tmp/opa" 2>/dev/null | awk '{print $1}' || shasum -a 256 "$tmp/opa" | awk '{print $1}')"
if [ "$actual" != "$expected" ]; then
  echo "checksum mismatch for $asset: expected $expected, got $actual" >&2
  exit 1
fi
mkdir -p "$DEST_DIR"
install -m 0755 "$tmp/opa" "$DEST_DIR/opa"
"$DEST_DIR/opa" version | head -1
echo "installed OPA ${VERSION} (${asset}, sha256 verified) to $DEST_DIR/opa"
