# NAV OSA XML2PDF

The PDF view of an invoice's data as reported to NAV's Online Számla system (OSA, Online Számla
Adatszolgáltatás): an InvoiceData XML (interface 3.0) in, a PDF out, drawn by NAV's own template.
Saxon HE runs the XSLT 2.0 template into XSL-FO, Apache FOP draws the PDF. One JVM that compiles the
template once, behind a small HTTP server. Meant for an internal network only.

Not a NAV product: it only runs the template NAV publishes in
[nav-gov-hu/Online-Invoice-XSLT](https://github.com/nav-gov-hu/Online-Invoice-XSLT). The PDF shows
the invoice's data; it is not the invoice itself.

Made for its author's own use and shared as is: no support, and no promise of answers to issues or
pull requests.

## API

| Request                                      | Answer                                    |
| -------------------------------------------- | ----------------------------------------- |
| `POST /render?lang=HU` with InvoiceData XML  | `application/pdf` (`lang`: `HU`, `EN`, `DE`) |
| `GET /health`                                | `ok`                                      |

An XML with a DOCTYPE is refused (HTTP 422): NAV's InvoiceData never has one, and no entity may read
a file or reach a URL into the PDF. An XML the template cannot draw also answers 422; one larger than
32 MB answers 413.

## Running

```bash
docker run --rm -p 8080:80 ghcr.io/donatmarko/nav-osa-xml2pdf
```

With NAV's sample invoices downloaded (`scripts/fetch-nav-samples.sh`):

```bash
curl -fsS -X POST -H 'Content-Type: application/xml' --data-binary @tests/nav-samples/belfoldi-termekertekesites.xml 'http://localhost:8080/render?lang=HU' -o invoice.pdf
```

| Variable               | Default               | Meaning                                  |
| ---------------------- | --------------------- | ---------------------------------------- |
| `PORT`                 | `80`                  | Port the server listens on (below 1024 needs Docker 20.10+, see `Dockerfile`). |
| `THREADS`              | `2`                   | Requests drawn at once.                  |
| `NAV_OSA_XML2PDF_HOME` | `/opt/nav-osa-xml2pdf` | Where the template and `fop.xconf` are. |

## Layout

- `src/NavOsaXml2PdfServer.java`: the HTTP server.
- `template/`: NAV's template (MIT, `template/LICENCE.md`) and FOP's configuration; see `template/README.md`
  for where it comes from and how to take a newer one.
- `Dockerfile`: pinned, checksummed Saxon HE and FOP; `docker build .`
- `scripts/smoke-test.sh <image>`: runs the image and draws NAV's sample invoices in every language; see `tests/README.md`.
- `scripts/fetch-nav-samples.sh`: downloads NAV's sample invoices into `tests/nav-samples/` (not kept in git).
- `scripts/update-template.sh`: takes NAV's newest template into `template/` (commit in `template/UPSTREAM`).

## NAV's template

The `NAV template` workflow (`.github/workflows/nav-template.yml`) runs every Monday: where NAV's
template has changed, it builds the image with the new one, checks it, and opens a pull request. It
needs "Allow GitHub Actions to create and approve pull requests" switched on in Settings › Actions ›
General. A new template reaches an image only by that pull request being merged and released.

## Releases

`.github/workflows/docker.yml` builds the image on every push and pull request, checks it with the
smoke test, and pushes it to
`ghcr.io/donatmarko/nav-osa-xml2pdf` on a published release (tagged `1.2.3`, `1.2`, `1`, and `latest`
unless a prerelease) or by hand (workflow_dispatch with a version).

## Licence

MIT, see `LICENSE`. The image also carries NAV's template (MIT), Saxon HE (MPL-2.0), Apache FOP and
its libraries (Apache-2.0), XML Resolver (Apache-2.0) and the DejaVu fonts (Bitstream Vera licence),
each under its own licence: see `THIRD-PARTY-NOTICES.md`, and `/opt/nav-osa-xml2pdf/licenses/` in the
image.
