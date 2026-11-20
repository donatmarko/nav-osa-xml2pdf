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
a file or reach a URL into the PDF. So is one whose root is not `InvoiceData` in the namespace of
interface 3.0 (`http://schemas.nav.gov.hu/OSA/3.0/data`): the template would draw an empty invoice of
it. An XML the template cannot draw also answers 422 (and is logged
on stderr); one larger than 32 MB answers 413, an empty request 400, any other path 404.

Every answer carries `Server: nav-osa-xml2pdf/<version>`, the image's version (`dev` where built by
hand).

## Running

```bash
docker run -d --restart unless-stopped -p 8080:80 ghcr.io/donatmarko/nav-osa-xml2pdf
```

Out of memory (the heap is 512 MB), the JVM exits rather than going on half-broken: the restart
policy brings it back.

With NAV's sample invoices downloaded (`scripts/fetch-nav-samples.sh`):

```bash
curl -fsS -X POST -H 'Content-Type: application/xml' --data-binary @tests/nav-samples/belfoldi-termekertekesites.xml 'http://localhost:8080/render?lang=HU' -o invoice.pdf
```

| Variable               | Default               | Meaning                                  |
| ---------------------- | --------------------- | ---------------------------------------- |
| `PORT`                 | `80`                  | Port the server listens on (below 1024 needs Docker 20.10+, see `Dockerfile`). |
| `THREADS`              | `2`                   | Requests drawn at once.                  |
| `NAV_OSA_XML2PDF_HOME` | `/opt/nav-osa-xml2pdf` | Where the template and `fop.xconf` are. |
| `SENTRY_DSN`           | (none)                | GlitchTip's (or Sentry's) DSN; without it, nothing is reported. |
| `SENTRY_ENVIRONMENT`   | `production`          | The environment the reports name.        |
| `SENTRY_TRACES_SAMPLE_RATE` | `0`             | Share of `/render` requests timed (`0` to `1`); `0`, none. |

In a Docker Compose stack, next to the service that calls it (by its name, here
`http://nav-osa-xml2pdf/render`), with no port published:

```yaml
services:
  nav-osa-xml2pdf:
    image: ghcr.io/donatmarko/nav-osa-xml2pdf:1
    restart: unless-stopped
    environment:
      SENTRY_DSN: https://<key>@glitchtip.example/<project>
```

### Error reports

With `SENTRY_DSN` set, the server reports to GlitchTip (or Sentry):

- a 500 (a fault of this server) as an error, with its stack trace;
- a 422 (invoice data that cannot be drawn) as a warning: one issue for each kind of fault, the
  message without what varies by invoice (quoted values, namespaces, numbers), the whole message in
  the event's `message` extra, the language as a tag.

The request's XML is never sent, nor who sent it (no IP address). The message of a 422 can quote a
value of the invoice data, as the 422's answer does. The release is `nav-osa-xml2pdf@<version>`.
Out of memory, the server's JVM can report nothing: just before it exits, it runs a second, small
one (a heap of 32 MB, so the container needs that much to spare) that reports it as fatal.

### Timings

With `SENTRY_TRACES_SAMPLE_RATE` above `0`, that share of `/render` requests is sent as a transaction
(`POST /render`, tagged with its language and HTTP status) in two spans: reading the XML, and
drawing it. Saxon streams the XSL-FO into FOP as it goes, so the XSLT and the PDF are one span, not
two. A 422 or a 500 of a timed request is linked to its transaction.

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
General. A pull request opened with the workflow's own `GITHUB_TOKEN` runs no other workflow, so the
`Docker` checks do not run on it: set a `NAV_TEMPLATE_TOKEN` secret (a fine-grained token with
Contents and Pull requests: read and write on this repository) and the pull request is opened with
it, its checks run. A new template reaches an image only by that pull request being merged and released.

## Releases

`.github/workflows/docker.yml` builds the image on every push and pull request, checks it with the
smoke test, and pushes it to
`ghcr.io/donatmarko/nav-osa-xml2pdf` on a published release (tagged `1.2.3`, `1.2`, `1`, and `latest`
unless a prerelease) or by hand (workflow_dispatch with a version).

The base images are pinned by digest and the actions by commit; Dependabot (`.github/dependabot.yml`)
opens a pull request a week where a newer one is out. Saxon HE and FOP are pinned by checksum in the
`Dockerfile`, updated by hand.

## Licence

MIT, see `LICENSE`. The image also carries NAV's template (MIT), Saxon HE (MPL-2.0), Apache FOP and
its libraries (Apache-2.0), XML Resolver (Apache-2.0), the Sentry Java SDK (MIT) and the DejaVu fonts
(Bitstream Vera licence), each under its own licence: see `THIRD-PARTY-NOTICES.md`, and
`/opt/nav-osa-xml2pdf/licenses/` in the image.
