# NAV invoice data template

`InvoiceDataTemplate_XSLT_FO.xslt` and `LICENCE.md` are NAV's own, from
[nav-gov-hu/Online-Invoice-XSLT](https://github.com/nav-gov-hu/Online-Invoice-XSLT)
(`template/3.0/` and the repository root), under the MIT licence in `LICENCE.md`: unchanged but for
line endings (LF). The commit they were taken at is in `UPSTREAM`.

The template turns an InvoiceData XML (interface 3.0) into XSL-FO; Apache FOP draws the PDF from it,
with `fop.xconf` (DejaVu Sans for the template's Tahoma). The template is XSLT 2.0, run by Saxon HE.
Both run in this repository's service (`src/NavOsaXml2PdfServer.java`, `Dockerfile`).

To take a newer one: `scripts/update-template.sh` (needs `curl` and `jq`). The `NAV template`
workflow runs it every week and opens a pull request where NAV's template has changed.
