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
# Refused for its DOCTYPE, not for any other reason the template could not draw it.
if [[ "$status" == 422 ]] && grep -q 'DOCTYPE is disallowed' "$out"; then
    echo "DOCTYPE refused (HTTP 422)."
else
    failed=$((failed + 1))
    echo "An XML with a DOCTYPE was not refused for it (HTTP ${status}): $(head -c 300 "$out")" >&2
fi

# An XML that is not an InvoiceData of interface 3.0 (here another root, and the InvoiceData of
# interface 2.0) is refused, not drawn as an empty invoice.
for xml in '<x/>' '<InvoiceData xmlns="http://schemas.nav.gov.hu/OSA/2.0/data"/>'; do
    status=$(curl -sS -m 30 -o "$out" -w '%{http_code}' -X POST -H 'Content-Type: application/xml' \
        --data-binary "$xml" "http://127.0.0.1:${port}/render?lang=HU" || true)
    if [[ "$status" == 422 ]] && grep -q 'Not an InvoiceData of interface 3.0' "$out"; then
        echo "Not an InvoiceData, refused (HTTP 422): ${xml}"
    else
        failed=$((failed + 1))
        echo "${xml} was not refused as not an InvoiceData (HTTP ${status}): $(head -c 300 "$out")" >&2
    fi
done

# What answers other than a PDF: method, path, body (curl's own options) and the status expected.
check() {
    local expected=$1 path=$2
    shift 2
    status=$(curl -sS -m 30 -o /dev/null -w '%{http_code}' "$@" "http://127.0.0.1:${port}${path}" || true)
    if [[ "$status" != "$expected" ]]; then
        failed=$((failed + 1))
        echo "${path} ($*) answered HTTP ${status}, not ${expected}." >&2
    fi
}
check 200 /health --head
check 404 /healthz
check 404 /render/x -X POST --data-binary @tests/nav-samples/belfoldi-termekertekesites.xml
check 405 /render
check 400 /render -X POST -H 'Content-Type: application/xml' --data-binary ''
check 400 '/render?lang=hu' -X POST --data-binary @tests/nav-samples/belfoldi-termekertekesites.xml

echo "${drawn} PDFs drawn, ${failed} failed."
[[ "$failed" == 0 ]]
