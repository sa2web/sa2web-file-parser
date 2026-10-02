package app.safile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FileServerTest {
    static byte[] document(String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        switch (format) {
            case "docx" -> {
                try (var doc = new XWPFDocument()) { doc.createParagraph().createRun().setText("DLP_TEST_SECRET confidential document"); doc.write(output); }
            }
            case "xlsx" -> {
                try (var book = new XSSFWorkbook()) { book.createSheet("Data").createRow(0).createCell(0).setCellValue("DLP_TEST_SECRET confidential spreadsheet"); book.write(output); }
            }
            case "pptx" -> {
                try (var slides = new XMLSlideShow()) { slides.createSlide().createTextBox().setText("DLP_TEST_SECRET confidential slides"); slides.write(output); }
            }
            default -> throw new IllegalArgumentException(format);
        }
        return output.toByteArray();
    }

    @Test
    void officeFilesExtractWithTikaAndPoi() throws Exception {
        Files.createDirectories(Path.of("build", "fixtures"));
        for (String format : new String[]{"docx", "xlsx", "pptx"}) {
            Path fixture = Path.of("build", "fixtures", "sensitive." + format);
            Files.write(fixture, document(format));
            Map<String, Object> result = ParseWorker.extract(fixture, fixture.getFileName().toString(), 10000);
            assertEquals("SUCCESS", result.get("status"), format);
            assertTrue(result.get("text").toString().contains("DLP_TEST_SECRET"), format);
        }
    }

    @Test
    void httpMultipartAuthenticationAndLimits() throws Exception {
        FileServer server = new FileServer("127.0.0.1", 0, "test-token", 1024 * 1024, 100000, 25, 1);
        server.start();
        try {
            byte[] bytes = document("docx");
            var result = request(server.port(), bytes, "sensitive.docx", "test-token");
            assertEquals(200, result.statusCode());
            Map<?, ?> body = new ObjectMapper().readValue(result.body(), Map.class);
            assertEquals("SUCCESS", body.get("status"), result.body());
            assertTrue(body.get("text").toString().contains("DLP_TEST_SECRET"));
            assertEquals(401, request(server.port(), bytes, "sensitive.docx", "wrong").statusCode());
            assertEquals(413, request(server.port(), new byte[1024 * 1024 + 1], "large.bin", "test-token").statusCode());
        } finally { server.stop(); }
    }

    @Test
    void textLimitDoesNotReturnPartialSuccess() throws Exception {
        FileServer server = new FileServer("127.0.0.1", 0, "", 1024 * 1024, 3, 25, 1);
        server.start();
        try {
            var result = request(server.port(), document("docx"), "sample.docx", "");
            assertFalse(result.body().contains("\"SUCCESS\""), result.body());
        } finally { server.stop(); }
    }

    @Test
    void generateOcrFixture() throws Exception {
        BufferedImage image = new BufferedImage(1200, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1200, 220);
        graphics.setColor(Color.BLACK); graphics.setFont(new Font("SansSerif", Font.BOLD, 52));
        graphics.drawString("DLP TEST SECRET", 40, 120); graphics.dispose();
        Files.createDirectories(Path.of("build", "fixtures"));
        ImageIO.write(image, "png", Path.of("build", "fixtures", "sensitive.png").toFile());
    }

    private static HttpResponse<String> request(int port, byte[] file, String name, String token) throws Exception {
        String boundary = "sa-file-test-boundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/parse"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
