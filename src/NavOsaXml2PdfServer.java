import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.SpanStatus;
import io.sentry.TransactionOptions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SAXDestination;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.Xslt30Transformer;
import net.sf.saxon.s9api.XsltExecutable;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * NAV's invoice data template (InvoiceDataTemplate_XSLT_FO.xslt, XSLT 2.0) run by Saxon HE, its
 * XSL-FO drawn into a PDF by Apache FOP, as NAV's README runs them, in one JVM that compiles the
 * template once: POST /render?lang=HU with an InvoiceData XML (interface 3.0) answers its PDF;
 * GET /health answers ok. Only on the internal network. An XML with a DOCTYPE is refused (NAV's
 * InvoiceData never has one), so that no entity reads a file or reaches a URL into the PDF; so is
 * one whose root is not an InvoiceData of interface 3.0.
 */
public final class NavOsaXml2PdfServer {
    private static final Set<String> LANGUAGES = Set.of("HU", "EN", "DE");

    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    /** InvoiceData's namespace in interface 3.0, the one NAV's template draws. */
    private static final String INVOICE_DATA_NAMESPACE = "http://schemas.nav.gov.hu/OSA/3.0/data";

    private static final SAXParserFactory PARSERS = parserFactory();

    private static final String VERSION = env("NAV_OSA_XML2PDF_VERSION", "dev");

    /** Every answer names the service and its version (the image's, set at build time). */
    private static final String SERVER = "nav-osa-xml2pdf/" + VERSION;

    private NavOsaXml2PdfServer() {}

    public static void main(String[] args) throws Exception {
        String dsn = env("SENTRY_DSN", "");

        // Errors go to GlitchTip (or Sentry) only where a DSN is given, and never the invoice data
        // itself: the request's XML is not sent, nor anything of who sent it.
        if (!dsn.isEmpty()) {
            Sentry.init(options -> {
                options.setDsn(dsn);
                options.setRelease("nav-osa-xml2pdf@" + VERSION);
                options.setEnvironment(env("SENTRY_ENVIRONMENT", "production"));
                options.setSendDefaultPii(false);

                double tracesSampleRate = Double.parseDouble(env("SENTRY_TRACES_SAMPLE_RATE", "0"));

                if (tracesSampleRate > 0) {
                    options.setTracesSampleRate(tracesSampleRate);
                }
            });
        }

        // Run by the server's JVM as it runs out of memory (-XX:OnOutOfMemoryError, Dockerfile), just
        // before it exits: that JVM can report nothing more, so this one says it, and is gone.
        if (args.length > 0 && args[0].equals("--report-oom")) {
            Sentry.captureMessage("Out of memory: the server exits, to be restarted.", SentryLevel.FATAL);
            Sentry.close();
            return;
        }

        File home = new File(env("NAV_OSA_XML2PDF_HOME", "/opt/nav-osa-xml2pdf"));
        Processor processor = new Processor(false);
        XsltExecutable template = processor.newXsltCompiler().compile(new StreamSource(new File(home, "InvoiceDataTemplate_XSLT_FO.xslt")));
        FopFactory fopFactory = FopFactory.newInstance(new File(home, "fop.xconf"));

        HttpServer server = HttpServer.create(new InetSocketAddress(Integer.parseInt(env("PORT", "80"))), 0);
        // One context for every path (a context matches by prefix), so that only these two answer.
        server.createContext("/", exchange -> {
            try {
                switch (exchange.getRequestURI().getPath()) {
                    case "/health" -> respond(exchange, 200, "text/plain", "ok");
                    case "/render" -> {
                        TransactionOptions transactionOptions = new TransactionOptions();
                        transactionOptions.setBindToScope(true);
                        ITransaction transaction = Sentry.startTransaction(exchange.getRequestMethod() + " /render", "http.server", transactionOptions);

                        try {
                            render(exchange, template, fopFactory, transaction);
                        } finally {
                            int status = exchange.getResponseCode();
                            transaction.setTag("http.status_code", Integer.toString(status));
                            transaction.finish(spanStatus(status));
                        }
                    }
                    default -> respond(exchange, 404, "text/plain", "Not found: POST /render or GET /health.");
                }
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(Integer.parseInt(env("THREADS", "2"))));
        server.start();
        System.out.println(SERVER + " listening on " + server.getAddress());
    }

    private static void render(HttpExchange exchange, XsltExecutable template, FopFactory fopFactory, ITransaction transaction) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respond(exchange, 405, "text/plain", "POST an InvoiceData XML.");
                return;
            }

            String lang = query(exchange, "lang", "HU");

            if (!LANGUAGES.contains(lang)) {
                respond(exchange, 400, "text/plain", "lang is one of HU, EN, DE.");
                return;
            }

            transaction.setTag("lang", lang);

            ISpan read = transaction.startChild("http.server.read", "Read the InvoiceData XML");
            byte[] xml;

            try {
                xml = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                read.setData("bytes", xml.length);
            } finally {
                read.finish();
            }

            if (xml.length == 0) {
                respond(exchange, 400, "text/plain", "POST an InvoiceData XML: the request is empty.");
                return;
            }

            if (xml.length > MAX_BODY_BYTES) {
                respond(exchange, 413, "text/plain", "The XML is larger than 32 MB.");
                return;
            }

            ByteArrayOutputStream pdf = new ByteArrayOutputStream();
            // Saxon streams its XSL-FO into FOP as it goes: the two run as one, so one span.
            ISpan draw = transaction.startChild("render", "Saxon HE (XSLT) into Apache FOP (PDF)");

            try {
                Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdf);
                Xslt30Transformer transformer = template.load30();
                transformer.setStylesheetParameters(Map.of(new QName("lang"), new XdmAtomicValue(lang)));
                transformer.transform(invoiceData(xml), new SAXDestination(fop.getDefaultHandler()));
                draw.setData("pdf_bytes", pdf.size());
                draw.finish(SpanStatus.OK);
            } catch (SaxonApiException | SAXException exception) { // FOPException is a SAXException
                draw.finish(SpanStatus.INVALID_ARGUMENT);
                // The XML, or what the template made of it (FOP's validation too), not this server.
                System.err.println("422 lang=" + lang + " (" + xml.length + " bytes): " + exception.getMessage());
                report422(exception, lang, xml.length);
                respond(exchange, 422, "text/plain", "Could not draw the invoice: " + exception.getMessage());
                return;
            } catch (RuntimeException exception) {
                draw.finish(SpanStatus.INTERNAL_ERROR);
                throw exception;
            }

            respond(exchange, 200, "application/pdf", pdf.toByteArray());
        } catch (RuntimeException exception) {
            System.err.println("500:");
            exception.printStackTrace();
            Sentry.captureException(exception);
            respond(exchange, 500, "text/plain", "Could not draw the invoice: " + exception);
        }
    }

    /** A 4xx is the request's fault, a 5xx this server's; no status at all, the answer never sent. */
    private static SpanStatus spanStatus(int status) {
        if (status < 100) {
            return SpanStatus.UNKNOWN_ERROR;
        }

        return SpanStatus.fromHttpStatusCode(status, status < 500 ? SpanStatus.INVALID_ARGUMENT : SpanStatus.INTERNAL_ERROR);
    }

    /**
     * A 422 is the ERP sending invoice data that cannot be drawn: a warning, one issue for each kind
     * of fault. The kind is the message without what varies by invoice (quoted values, namespaces,
     * numbers and positions), so that the same fault in many invoices is one issue, not many.
     */
    private static void report422(Exception exception, String lang, int bytes) {
        String message = String.valueOf(exception.getMessage());
        String kind = message.replaceAll("\"[^\"]*\"|'[^']*'|\\{[^}]*}|\\b\\d+(?:[.:]\\d+)*\\b", "…").replaceAll("\\s+", " ");

        Sentry.captureMessage("422: " + kind, SentryLevel.WARNING, scope -> {
            scope.setFingerprint(List.of("422", kind));
            scope.setTag("lang", lang);
            scope.setExtra("message", message);
            scope.setExtra("bytes", Integer.toString(bytes));
        });
    }

    /** The request's XML, read by a parser that refuses any DOCTYPE, and so any entity. */
    private static SAXSource invoiceData(byte[] xml) throws SAXException {
        XMLReader reader;

        // A SAXParserFactory is not promised to be thread-safe; a parser is cheap to make.
        try {
            synchronized (PARSERS) {
                reader = PARSERS.newSAXParser().getXMLReader();
            }
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException(exception);
        }

        return new SAXSource(new InvoiceDataOnly(reader), new InputSource(new ByteArrayInputStream(xml)));
    }

    /**
     * Lets through only an XML whose root is an InvoiceData of interface 3.0: the template draws an
     * empty invoice of anything else, which would answer a PDF for an XML that is not one.
     */
    private static final class InvoiceDataOnly extends XMLFilterImpl {
        private boolean root = true;

        InvoiceDataOnly(XMLReader parent) {
            super(parent);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (root) {
                root = false;

                if (!INVOICE_DATA_NAMESPACE.equals(uri) || !"InvoiceData".equals(localName)) {
                    throw new SAXException("Not an InvoiceData of interface 3.0: the root element is {" + uri + "}" + localName
                            + ", not {" + INVOICE_DATA_NAMESPACE + "}InvoiceData.");
                }
            }

            super.startElement(uri, localName, qName, attributes);
        }
    }

    private static SAXParserFactory parserFactory() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);

        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (ParserConfigurationException | SAXException exception) {
            throw new IllegalStateException("The XML parser cannot be made to refuse DOCTYPEs.", exception);
        }

        return factory;
    }

    private static String query(HttpExchange exchange, String name, String fallback) {
        String query = exchange.getRequestURI().getRawQuery();

        if (query != null) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);

                if (parts.length == 2 && parts[0].equals(name)) {
                    return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                }
            }
        }

        return fallback;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        respond(exchange, status, contentType + "; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Server", SERVER);
        exchange.getResponseHeaders().set("Content-Type", contentType);

        // A HEAD answer has no body: the JDK's server refuses one written to it.
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }

        exchange.sendResponseHeaders(status, body.length);

        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);

        return value == null || value.isBlank() ? fallback : value;
    }
}
