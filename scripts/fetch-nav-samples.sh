#!/usr/bin/env bash
# Downloads NAV's sample invoices (InvoiceData XML, interface 3.0, anonymised data) into
# tests/nav-samples, checked against a pinned SHA-256: NAV publishes them for developers without a
# licence, so they are fetched, not kept in this repository. File names are lower-cased, with
# hyphens for spaces. Does nothing where they are already there. Needs curl and unzip.
set -euo pipefail

cd "$(dirname "$0")/.."

URL=https://onlineszamla.nav.gov.hu/api/files/container/download/Peldaszamlak_v3.0.zip
SHA256=360a9b11466267762a79f80e48e534d0384ab4ffad1637995a13c1b334ee0f33
TARGET=tests/nav-samples

if compgen -G "${TARGET}/*.xml" >/dev/null; then
    exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --retry 3 -o "$tmp/samples.zip" "$URL"
echo "${SHA256}  $tmp/samples.zip" | sha256sum -c --quiet -
unzip -q -j "$tmp/samples.zip" '*.xml' -d "$tmp/xml"

mkdir -p "$TARGET"
for file in "$tmp"/xml/*.xml; do
    name=$(basename "$file" .xml | sed -E 's/ +$//; s/ +/-/g' | tr '[:upper:]' '[:lower:]')
    cp "$file" "${TARGET}/${name}.xml"
done

echo "$(find "$TARGET" -name '*.xml' | wc -l) NAV sample invoices in ${TARGET}."
