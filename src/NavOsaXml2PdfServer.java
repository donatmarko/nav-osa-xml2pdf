import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * NAV's invoice data template (InvoiceDataTemplate_XSLT_FO.xslt, XSLT 2.0) run by Saxon HE, its
 * XSL-FO drawn into a PDF by Apache FOP, as NAV's README runs them, in one JVM that compiles the
 * template once: POST /render?lang=HU with an InvoiceData XML (interface 3.0) answers its PDF;
 * GET /health answers ok. Only on the internal network. An XML with a DOCTYPE is refused (NAV's
 * InvoiceData never has one), so that no entity reads a file or reaches a URL into the PDF.
 */
public final class NavOsaXml2PdfServer {
    private static final Set<String> LANGUAGES = Set.of("HU", "EN", "DE");

    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

    private static final SAXParserFactory PARSERS = parserFactory();

    private NavOsaXml2PdfServer() {}

    public static void main(String[] args) throws Exception {
        File home = new File(env("NAV_OSA_XML2PDF_HOME", "/opt/nav-osa-xml2pdf"));
        Processor processor = new Processor(false);
        XsltExecutable template = processor.newXsltCompiler().compile(new StreamSource(new File(home, "InvoiceDataTemplate_XSLT_FO.xslt")));
        FopFactory fopFactory = FopFactory.newInstance(new File(home, "fop.xconf"));

        HttpServer server = HttpServer.create(new InetSocketAddress(Integer.parseInt(env("PORT", "80"))), 0);
        server.createContext("/health", exchange -> respond(exchange, 200, "text/plain", "ok"));
        server.createContext("/render", exchange -> render(exchange, template, fopFactory));
        server.setExecutor(Executors.newFixedThreadPool(Integer.parseInt(env("THREADS", "2"))));
        server.start();
        System.out.println("NAV OSA XML2PDF listening on " + server.getAddress());
    }

    private static void render(HttpExchange exchange, XsltExecutable template, FopFactory fopFactory) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "text/plain", "POST an InvoiceData XML.");
                return;
            }

            String lang = query(exchange, "lang", "HU");

            if (!LANGUAGES.contains(lang)) {
                respond(exchange, 400, "text/plain", "lang is one of HU, EN, DE.");
                return;
            }

            byte[] xml = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);

            if (xml.length == 0 || xml.length > MAX_BODY_BYTES) {
                respond(exchange, 413, "text/plain", "The XML is empty or larger than 32 MB.");
                return;
            }

            ByteArrayOutputStream pdf = new ByteArrayOutputStream();

            try {
                Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdf);
                Xslt30Transformer transformer = template.load30();
                transformer.setStylesheetParameters(Map.of(new QName("lang"), new XdmAtomicValue(lang)));
                transformer.transform(invoiceData(xml), new SAXDestination(fop.getDefaultHandler()));
            } catch (SaxonApiException | SAXException exception) { // FOPException is a SAXException
                respond(exchange, 422, "text/plain", "Could not draw the invoice: " + exception.getMessage());
                return;
            }

            respond(exchange, 200, "application/pdf", pdf.toByteArray());
        } catch (RuntimeException exception) {
            exception.printStackTrace();
            respond(exchange, 500, "text/plain", "Could not draw the invoice: " + exception);
        } finally {
            exchange.close();
        }
    }

    /** The request's XML, read by a parser that refuses any DOCTYPE, and so any entity. */
    private static SAXSource invoiceData(byte[] xml) throws SAXException {
        XMLReader reader;

        try {
            reader = PARSERS.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException(exception);
        }

        return new SAXSource(reader, new InputSource(new ByteArrayInputStream(xml)));
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
        exchange.getResponseHeaders().set("Content-Type", contentType);
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
