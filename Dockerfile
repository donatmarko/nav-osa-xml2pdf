# syntax=docker/dockerfile:1

# An invoice's PDF drawn from the InvoiceData XML NAV gives of it, by NAV's own template
# (template/): Saxon HE (XSLT 2.0) and Apache FOP behind a small HTTP service
# (src/NavOsaXml2PdfServer.java), on the internal network only. Built from the repository root:
#   docker build .

FROM eclipse-temurin:21-jdk-alpine AS build

ARG SAXON_VERSION=12.10
ARG SAXON_SHA256=b571af282f25d7301059f788b9a149aab8b5cdc14ef3d212dc5425d3dcbb9a97
ARG XMLRESOLVER_VERSION=5.3.3
ARG XMLRESOLVER_SHA256=1fe4d5b92f708dcdb82dbce12919e0171e6b5ca62c6dca6220483625098feb5f
ARG XMLRESOLVER_DATA_SHA256=b0c487ad2f3e558be8d829c916d2458d10aca6a5bafa7a4d0524b70845e48a5c
ARG FOP_VERSION=2.12
ARG FOP_SHA256=f43bf5227557c2653105f4494bb2c3c4e7715cef21997286a16e9847399b81a4

WORKDIR /opt/nav-osa-xml2pdf

# Pinned and checked: Saxon HE 12 (NAV's README runs 11; the output is the same), with the resolver
# version its POM names; FOP's jars.
RUN apk add --no-cache curl \
    && mkdir -p saxon fop classes /tmp/fop \
    && curl -fsSL -o saxon/saxon-he.jar "https://repo1.maven.org/maven2/net/sf/saxon/Saxon-HE/${SAXON_VERSION}/Saxon-HE-${SAXON_VERSION}.jar" \
    && curl -fsSL -o saxon/xmlresolver.jar "https://repo1.maven.org/maven2/org/xmlresolver/xmlresolver/${XMLRESOLVER_VERSION}/xmlresolver-${XMLRESOLVER_VERSION}.jar" \
    && curl -fsSL -o saxon/xmlresolver-data.jar "https://repo1.maven.org/maven2/org/xmlresolver/xmlresolver/${XMLRESOLVER_VERSION}/xmlresolver-${XMLRESOLVER_VERSION}-data.jar" \
    && printf '%s  %s\n' "${SAXON_SHA256}" saxon/saxon-he.jar "${XMLRESOLVER_SHA256}" saxon/xmlresolver.jar "${XMLRESOLVER_DATA_SHA256}" saxon/xmlresolver-data.jar | sha256sum -c - \
    && curl -fsSL -o /tmp/fop.tar.gz "https://archive.apache.org/dist/xmlgraphics/fop/binaries/fop-${FOP_VERSION}-bin.tar.gz" \
    && echo "${FOP_SHA256}  /tmp/fop.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/fop.tar.gz -C /tmp/fop \
    && cp /tmp/fop/fop-${FOP_VERSION}/fop/build/*.jar /tmp/fop/fop-${FOP_VERSION}/fop/lib/*.jar fop/ \
    && mkdir -p licenses/fop \
    && cp /tmp/fop/fop-${FOP_VERSION}/LICENSE /tmp/fop/fop-${FOP_VERSION}/NOTICE \
        /tmp/fop/fop-${FOP_VERSION}/fop/lib/*LICENSE* \
        /tmp/fop/fop-${FOP_VERSION}/fop/lib/*NOTICE* /tmp/fop/fop-${FOP_VERSION}/fop/lib/*README* licenses/fop/ \
    && rm -rf /tmp/fop /tmp/fop.tar.gz

COPY src/NavOsaXml2PdfServer.java /tmp/src/
RUN javac --release 21 -cp "saxon/*:fop/*" -d classes /tmp/src/NavOsaXml2PdfServer.java

FROM eclipse-temurin:21-jre-alpine

# DejaVu Sans for the template's Tahoma (template/fop.xconf).
RUN apk add --no-cache font-dejavu fontconfig \
    && addgroup -S app && adduser -S -G app app

WORKDIR /opt/nav-osa-xml2pdf

COPY --from=build /opt/nav-osa-xml2pdf/saxon ./saxon
COPY --from=build /opt/nav-osa-xml2pdf/fop ./fop
COPY --from=build /opt/nav-osa-xml2pdf/classes ./classes
COPY --from=build /opt/nav-osa-xml2pdf/licenses/fop ./licenses/fop
COPY template/InvoiceDataTemplate_XSLT_FO.xslt template/fop.xconf ./
COPY LICENSE THIRD-PARTY-NOTICES.md ./
COPY licenses/ ./licenses/
COPY template/LICENCE.md ./licenses/NAV-template-LICENCE.md

USER app

# The version the Server header names (the release's, given by the workflow); dev where built by hand.
ARG VERSION=dev
ENV NAV_OSA_XML2PDF_VERSION=${VERSION}

# Port 80 as the unprivileged app user: Docker (20.10+) lets a container's processes bind ports
# below 1024 (net.ipv4.ip_unprivileged_port_start=0). Elsewhere, set PORT to one above 1023.
ENV PORT=80
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${PORT}/health" || exit 1

CMD ["java", "-Djava.awt.headless=true", "-Xmx512m", "-Xss8m", "-cp", "/opt/nav-osa-xml2pdf/classes:/opt/nav-osa-xml2pdf/saxon/*:/opt/nav-osa-xml2pdf/fop/*", "NavOsaXml2PdfServer"]

LABEL maintainer="MARKO Donat <donat.marko@donatus.hu>"
LABEL description="PDFs of NAV Online Számla invoice data (InvoiceData XML) by NAV's own template (Saxon HE, Apache FOP)"
LABEL org.opencontainers.image.title="NAV OSA XML2PDF"
LABEL org.opencontainers.image.description="PDFs of NAV Online Számla invoice data (InvoiceData XML) by NAV's own template (Saxon HE, Apache FOP)"
LABEL org.opencontainers.image.url="https://www.donatus.hu"
LABEL org.opencontainers.image.source="https://github.com/donatmarko/nav-osa-xml2pdf"
LABEL org.opencontainers.image.authors="MARKO Donat <donat.marko@donatus.hu>"
LABEL org.opencontainers.image.vendor="donatus"
LABEL org.opencontainers.image.licenses="MIT AND MPL-2.0 AND Apache-2.0 AND Bitstream-Vera"
