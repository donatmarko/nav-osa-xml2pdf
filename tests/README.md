# Tests

`scripts/smoke-test.sh <image>` draws NAV's sample invoices (InvoiceData XML, interface 3.0,
anonymised data) in every language, and checks that an XML with a DOCTYPE is refused.

The samples are not kept in this repository: NAV publishes them for developers
(<https://onlineszamla.nav.gov.hu/api/files/container/download/Peldaszamlak_v3.0.zip>, dated
2020-12-07) without a licence. `scripts/fetch-nav-samples.sh` downloads them into
`tests/nav-samples/` (ignored by git), checked against a pinned SHA-256; the smoke test runs it first.
