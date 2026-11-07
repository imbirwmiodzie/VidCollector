#!/usr/bin/env bash
# Smoke test against a running Upload Box.
#   ./scripts/smoke-test.sh                      # via adb forward (USB / emulator)
#   ./scripts/smoke-test.sh http://192.168.43.1:8080   # directly over Wi-Fi
# Generates a random test file (default 300 MB, SIZE_MB=...), uploads it, and compares SHA-256.
set -euo pipefail
BASE="${1:-}"
SIZE_MB="${SIZE_MB:-300}"
if [ -z "$BASE" ]; then
  adb forward tcp:8080 tcp:8080 >/dev/null
  BASE="http://127.0.0.1:8080"
fi
BASE="${BASE%/}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
F="$TMP/smoke-${SIZE_MB}MB.bin"

echo "== GET $BASE/health"; curl -fsS "$BASE/health"; echo
echo "== creating ${SIZE_MB} MB random test file"
head -c "$((SIZE_MB * 1024 * 1024))" /dev/urandom > "$F"
if command -v sha256sum >/dev/null; then LOCAL=$(sha256sum "$F" | cut -d' ' -f1); else LOCAL=$(shasum -a 256 "$F" | cut -d' ' -f1); fi
echo "local sha256: $LOCAL"
echo "== uploading (streamed from disk)"
# -T streams the file (PUT); 'Expect:' skips curl's 1 s wait for "100 Continue".
REPLY=$(curl -fsS -H 'Expect:' -T "$F" -w '\n%{speed_upload}' "$BASE/upload?name=$(basename "$F")")
BODY=$(echo "$REPLY" | head -n1); SPEED=$(echo "$REPLY" | tail -n1)
echo "$BODY"
awk -v s="$SPEED" 'BEGIN { printf "client-measured speed: %.1f MB/s\n", s/1e6 }'
if echo "$BODY" | grep -q "\"sha256\":\"$LOCAL\""; then echo "CHECKSUM OK"; else echo "CHECKSUM MISMATCH"; exit 1; fi
echo "== GET $BASE/files"; curl -fsS "$BASE/files"; echo
