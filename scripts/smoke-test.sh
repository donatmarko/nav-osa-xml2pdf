#!/usr/bin/env bash
# Runs the given image, waits for /health, and draws every NAV sample invoice (tests/nav-samples,
# downloaded by scripts/fetch-nav-samples.sh) in every language: fails unless each answers a PDF.
# Usage: scripts/smoke-test.sh <image>
set -euo pipefail

cd "$(dirname "$0")/.."

scripts/fetch-nav-samples.sh

image=${1:?usage: scripts/smoke-test.sh <image>}
name=nav-osa-xml2pdf-smoke-$$
port=${SMOKE_PORT:-18080}

docker run -d --rm --name "$name" -p "127.0.0.1:${port}:80" "$image" >/dev/null
out=$(mktemp)
trap 'rm -f "$out"; docker stop "$name" >/dev/null 2>&1 || true' EXIT

for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:${port}/health" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
curl -fsS "http://127.0.0.1:${port}/health" >/dev/null

drawn=0
failed=0
for sample in tests/nav-samples/*.xml; do
    for lang in HU EN DE; do
        status=$(curl -sS -m 120 -o "$out" -w '%{http_code}' -X POST -H 'Content-Type: application/xml' \
            --data-binary @"$sample" "http://127.0.0.1:${port}/render?lang=${lang}" || true)
        if [[ "$status" == 200 && "$(head -c 5 "$out")" == "%PDF-" ]]; then
            drawn=$((drawn + 1))
        else
            failed=$((failed + 1))
            echo "No PDF for ${sample} (lang=${lang}, HTTP ${status}): $(head -c 300 "$out")" >&2
        fi
    done
done

# An XML with a DOCTYPE (here an entity reading a file of the container) is refused, not drawn.
doctype=$(mktemp)
trap 'rm -f "$out" "$doctype"; docker stop "$name" >/dev/null 2>&1 || true' EXIT
{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<!DOCTYPE InvoiceData [<!ENTITY file SYSTEM "file:///etc/hostname">]>'
    tr -d '\r' <tests/nav-samples/belfoldi-termekertekesites.xml | tail -n +2 \
        | sed 's|<invoiceNumber>[^<]*</invoiceNumber>|<invoiceNumber>\&file;</invoiceNumber>|'
} >"$doctype"
status=$(curl -sS -m 120 -o "$out" -w '%{http_code}' -X POST -H 'Content-Type: application/xml' \
    --data-binary @"$doctype" "http://127.0.0.1:${port}/render?lang=HU" || true)
if [[ "$status" == 422 ]]; then
    echo "DOCTYPE refused (HTTP 422)."
else
    failed=$((failed + 1))
    echo "An XML with a DOCTYPE was not refused (HTTP ${status})." >&2
fi

echo "${drawn} PDFs drawn, ${failed} failed."
[[ "$failed" == 0 ]]
