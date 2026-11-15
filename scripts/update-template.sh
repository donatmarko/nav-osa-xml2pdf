#!/usr/bin/env bash
# Takes NAV's newest invoice data template (and its licence) from nav-gov-hu/Online-Invoice-XSLT
# into template/, line endings normalised to LF, and notes the commit in template/UPSTREAM.
# Changes nothing where the template is already the newest. GITHUB_TOKEN, where set, is used
# for the GitHub API (higher rate limit). Prints the commit taken.
set -euo pipefail

cd "$(dirname "$0")/.."

REPOSITORY=nav-gov-hu/Online-Invoice-XSLT
TEMPLATE_PATH=template/3.0/InvoiceDataTemplate_XSLT_FO.xslt

auth=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

latest=$(curl -fsSL "${auth[@]}" -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/${REPOSITORY}/commits?path=${TEMPLATE_PATH}&per_page=1")
commit=$(jq -r '.[0].sha' <<<"$latest")
date=$(jq -r '.[0].commit.committer.date' <<<"$latest")

if [[ ! "$commit" =~ ^[0-9a-f]{40}$ ]]; then
    echo "No commit found for ${TEMPLATE_PATH} in ${REPOSITORY}." >&2
    exit 1
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

raw="https://raw.githubusercontent.com/${REPOSITORY}/${commit}"
curl -fsSL -o "$tmp/template.xslt" "${raw}/${TEMPLATE_PATH}"
curl -fsSL -o "$tmp/LICENCE.md" "${raw}/LICENCE.md"

tr -d '\r' <"$tmp/template.xslt" >template/InvoiceDataTemplate_XSLT_FO.xslt
tr -d '\r' <"$tmp/LICENCE.md" >template/LICENCE.md

cat >template/UPSTREAM <<UPSTREAM
# NAV's template this repository carries, as scripts/update-template.sh last took it.
repository=${REPOSITORY}
path=${TEMPLATE_PATH}
commit=${commit}
date=${date}
UPSTREAM

echo "$commit"
