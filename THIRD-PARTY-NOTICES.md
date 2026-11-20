# Third-party notices

This repository's own code (`src/`, `scripts/`, the `Dockerfile`, the workflows, `template/fop.xconf`)
is under the MIT licence in `LICENSE`. The repository and the image also carry, or are built from,
the following. Their licence texts are in `licenses/` and, in the image, in
`/opt/nav-osa-xml2pdf/licenses/`.

| Component | Where | Licence | Licence text |
| --- | --- | --- | --- |
| NAV invoice data template (`InvoiceDataTemplate_XSLT_FO.xslt`), © 2019 Nemzeti Adó- és Vámhivatal, [nav-gov-hu/Online-Invoice-XSLT](https://github.com/nav-gov-hu/Online-Invoice-XSLT) | repository (`template/`), image | MIT | `template/LICENCE.md` (in the image: `licenses/NAV-template-LICENCE.md`) |
| [Saxon HE](https://www.saxonica.com) 12, © Saxonica Limited | image (jar) | MPL-2.0; parts derived from James Clark's XPath parser under his notice | `licenses/Saxon-HE-LICENSE.txt`, `licenses/Saxon-HE-JAMESCLARK.txt` |
| [XML Resolver](https://xmlresolver.org) 5, © Norman Walsh | image (jars) | Apache-2.0; its W3C catalogue data under the W3C licence (inside the jar, `org/xmlresolver/notices/`) | `licenses/Apache-2.0.txt` |
| [Apache FOP](https://xmlgraphics.apache.org/fop/) 2.12 and the libraries it ships: Batik, XML Graphics Commons, Commons IO, Commons Logging, PDFBox FontBox and IO, XML Commons Externals | image (jars) | Apache-2.0; XML Commons Externals also W3C Software / Document licences and SAX (public domain) | in the image: `licenses/fop/` (FOP's own `LICENSE`, `NOTICE` and `lib/` licence files, copied from its distribution at build); PDFBox FontBox and IO carry theirs inside their jars (`META-INF/`); `licenses/Apache-2.0.txt` |
| [Sentry Java SDK](https://github.com/getsentry/sentry-java) 8 (`io.sentry:sentry`), © Sentry, for error reports to GlitchTip or Sentry | image (jar) | MIT; the code it carries from others under their licences | `licenses/Sentry-Java-LICENSE.txt`; the others' notices inside its jar (`META-INF/SENTRY_THIRD_PARTY_NOTICES.md`) |
| [DejaVu fonts](https://dejavu-fonts.github.io) 2.37 (Alpine `font-dejavu`), embedded as subsets into every PDF | image | Bitstream Vera licence, Arev fonts licence; DejaVu changes public domain | `licenses/DejaVu-LICENSE.txt` |
| [Eclipse Temurin](https://adoptium.net) 21 JRE and Alpine Linux (base image `eclipse-temurin:21-jre-alpine`) | image | GPL-2.0 with Classpath Exception (JRE); various (Alpine packages) | in the image: `/opt/java/openjdk/legal/`, Alpine's per-package licences |

NAV's sample invoices, used by the smoke test, are not part of the repository or the image: they
are downloaded from NAV when the test runs (`scripts/fetch-nav-samples.sh`, `tests/README.md`).

Saxon HE's source is available from Saxonica ([Saxonica/Saxon-HE](https://github.com/Saxonica/Saxon-HE)),
FOP's from the Apache Software Foundation; the image uses their released binaries unchanged.
