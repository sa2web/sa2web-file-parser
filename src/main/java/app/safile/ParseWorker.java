package app.safile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.exception.WriteLimitReachedException;
import java.nio.file.*;
import java.util.Map;

/** One short-lived JVM per document: memory and process time are bounded by the HTTP parent. */
public final class ParseWorker {
    public static void main(String[] args) throws Exception {
        Map<String, Object> result;
        try {
            result = extract(Path.of(args[0]), args[1], Integer.parseInt(args[2]));
        } catch (Exception e) {
            String status = e instanceof org.apache.tika.exception.EncryptedDocumentException ? "ENCRYPTED" : "FAILED";
            if (WriteLimitReachedException.isWriteLimitReached(e)) status = "LIMIT_EXCEEDED";
            // Never expose exception messages: parsers may put document text or paths in them.
            result = Map.of("status", status, "errorCode", e.getClass().getSimpleName());
        }
        new ObjectMapper().writeValue(Path.of(args[3]).toFile(), result);
    }

    static Map<String, Object> extract(Path file, String filename, int maxChars) throws Exception {
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(maxChars);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        ParseContext context = new ParseContext();
        AutoDetectParser parser = new AutoDetectParser();
        context.set(Parser.class, parser);
        OfficeParserConfig office = new OfficeParserConfig();
        office.setExtractMacros(false);
        context.set(OfficeParserConfig.class, office);
        TesseractOCRConfig ocr = new TesseractOCRConfig();
        ocr.setLanguage(System.getenv().getOrDefault("SA_FILE_OCR_LANG", "eng"));
        ocr.setTimeoutSeconds(20);
        ocr.setMaxFileSizeToOcr(20L * 1024 * 1024);
        context.set(TesseractOCRConfig.class, ocr);
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.AUTO);
        context.set(PDFParserConfig.class, pdf);
        BodyContentHandler handler = new BodyContentHandler(maxChars);
        try (var stream = Files.newInputStream(file)) { parser.parse(stream, handler, metadata, context); }
        String mime = metadata.get(Metadata.CONTENT_TYPE);
        String text = handler.toString();
        if (mime == null || mime.equals("application/octet-stream")) return Map.of("status", "UNSUPPORTED");
        if (text.isBlank() && (mime.startsWith("image/") || mime.equals("application/pdf"))) {
            return Map.of("status", "FAILED", "errorCode", "NO_EXTRACTABLE_TEXT_OR_OCR_UNAVAILABLE");
        }
        return Map.of("status", "SUCCESS", "text", text, "mimeType", mime, "truncated", false);
    }
}
