# sa2web-file-parser

A standalone Java 21 project utilizing Apache Tika 3.3.2, its underlying Office parsing engine Apache POI, PDFBox, and Tesseract OCR. It can be built and run independently, providing text extraction capabilities for files to sa2web or other callers via an HTTP interface.


## Build & Run

The following build, startup, Docker, and interface testing commands should all be executed in the root directory of this project. Ensure you have JDK 21 and Gradle 8.5 prepared; the project does not currently include a Gradle wrapper, so please verify your setup with `gradle --version`.

```sh
cd sa2web-file-parser
export JAVA_HOME=/path/to/jdk21
export PATH="$JAVA_HOME/bin:$PATH"
gradle test installDist
SA_FILE_TOKEN=your-test-token build/install/sa_file/bin/sa_file
```

By default, it only listens on `127.0.0.1:4190`. The service supports `GET /health` and POST /parse. Authentication uses either a Bearer token; if no token is configured, listening is restricted to loopback addresses only and should not be exposed directly. The token in the command is for demonstration purposes—replace it with your own secret when deploying.

### HTTP Protocol

`POST /parse` uses `multipart/form-data` for uploads with the following fields:

| Field | Description |
| --- | --- |
| file | Required. The file binary. |
| filename | Optional. Original filename; if not provided, the filename from the file part is used. |
| mimeType | Optional. Caller-supplied MIME hint; however, the service currently relies on its own content detection results. |

A successful response is returned in JSON format:

```json
{"status":"SUCCESS","text":"Extracted text","mimeType":"text/plain","truncated":false}
```

Failure statuses include `UNSUPPORTED`, `ENCRYPTED`, `LIMIT_EXCEEDED`, and `FAILED`, which may come with an `errorCode`. Callers must check the status field and cannot rely solely on an HTTP 200 code to determine success. Authentication failures return HTTP 401, server busy returns 429, and oversized files return 413. Truncated text will never be returned as a successful result. The health check does not require authentication and does not guarantee that OCR language data files are ready.

Environment Variables:

| Variable | Default Value |
| --- | --- |
| SA_FILE_HOST / SA_FILE_PORT | 127.0.0.1 / 4190 |
| SA_FILE_TOKEN | Empty; must be configured for non-loopback listening |
| SA_FILE_MAX_BYTES | 20971520 |
| SA_FILE_MAX_CHARS | 1000000 |
| SA_FILE_TIMEOUT_SECONDS | 25 |
| SA_FILE_CONCURRENCY | 2 |
| SA_FILE_OCR_LANG | eng；use eng+chi_sim for Chinese and ensure language data is installed, see more at https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html#simplest-invocation-to-ocr-an-image  |

Original files and output are saved in a request-scoped temporary directory and cleaned up upon completion; no audit or persistent files are kept. Each document processing task spawns an isolated JVM with 256 MiB heap memory; timeouts will terminate the child process along with any descendant OCR processes. If output exceeds the limit, LIMIT_EXCEEDED is returned rather than partial success. Unreadable encrypted files return ENCRYPTED. If no text is extracted from images or scanned PDFs, a failure is returned—they are not quietly treated as safe, empty documents.

## OCR & Containers

or local execution, Tesseract (along with required language packs) must be installed beforehand and added to the PATH. Standard text in Office documents does not depend on OCR. PDFs use an automatic OCR strategy; image quality and OCR accuracy will affect recognition results, and complete accuracy for scanned text cannot be guaranteed.

```sh
# Build the base image containing JRE, Tesseract, and any other language packs (typically only needs to be run once)
docker build -t sa2web-file-parser:base -f docker/Dockerfile .
# The main Dockerfile depends on the above image and copies the build output from installDist
docker build -t sa2web-file-parser:latest .
docker run --rm --name sa-file-test --read-only --tmpfs /tmp:size=256m \
  --memory=1g --cpus=2 --pids-limit=128 \
  -p 4190:4190 -e SA_FILE_TOKEN=your-test-token sa2web-file-parser:latest
```

Complete `gradle test installDist` prior to building the application image; otherwise, there will be no distribution directory to copy. `docker/Dockerfile` installs English and Simplified Chinese language packs, and the root `Dockerfile` runs the service as a non-root user. If only Java code is modified, simply rebuild the distribution directory and application image.


## Integration Testing with sa2web

1. Start the service and verify with `curl http://127.0.0.1:4190/health`.

2. Go to the sa2web Admin Console -> DLP -> File Parsing Service: set the URL to `http://127.0.0.1:4190/parse`, select BEARER authentication, and enter the token. Set the OCR timeout to 30,000 ms and select "Block on Failure".

3. Associate the policy with a test user group, add file upload/download actions, and bind this parsing service.

4. Create a FILE/MIXED rule with a `CONTENT_KEYWORD` condition `{"values":["DLP_TEST_SECRET"]}`, and bind it to the BLOCK action.
5. Test using `build/fixtures/sensitive.docx`, `.xlsx`, and `.pptx`; these are generated by test code and contain no real user data.
6. For the image `build/fixtures/sensitive.png`, its content is `DLP TEST SECRET`. Configure this keyword separately for OCR testing.
7. Verify behavior for encryption, timeout, size limit exceeding, and invalid authentication. Enabling "Pass on Failure" means parsing faults will not trigger mandatory blocking, though other rules can still block the traffic.

API Testing:

```sh
curl -H 'Authorization: Bearer your-test-token' \
  -F 'file=@build/fixtures/sensitive.docx' \
  -F 'filename=sensitive.docx' http://127.0.0.1:4190/parse
```
