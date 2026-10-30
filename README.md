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
| SA_FILE_MAX_BYTES | 20971520; supported range: 1–268435456 bytes (256 MiB) |
| SA_FILE_MAX_CHARS | 1000000 |
| SA_FILE_TIMEOUT_SECONDS | 25 |
| SA_FILE_CONCURRENCY | 2 |
| SA_FILE_OCR_LANG | eng; use eng+chi_sim for Chinese and ensure language data is installed, see more at https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html#simplest-invocation-to-ocr-an-image  |

Original files and output are saved in a request-scoped temporary directory and cleaned up upon completion; no audit or persistent files are kept. Each document processing task spawns an isolated JVM with 256 MiB heap memory; timeouts will terminate the child process along with any descendant OCR processes. If output exceeds the limit, LIMIT_EXCEEDED is returned rather than partial success. Unreadable encrypted files return ENCRYPTED. If no text is extracted from images or scanned PDFs, a failure is returned—they are not quietly treated as safe, empty documents.

## OCR & Containers

or local execution, Tesseract (along with required language packs) must be installed beforehand and added to the PATH. Standard text in Office documents does not depend on OCR. PDFs use an automatic OCR strategy; image quality and OCR accuracy will affect recognition results, and complete accuracy for scanned text cannot be guaranteed.

```sh
# Docker compiles and tests the application; no host JDK or Gradle is required
docker build -t sa2web-file-parser:latest .
docker run --rm --name sa-file-test --read-only --tmpfs /tmp:size=256m \
  --memory=1g --cpus=2 --pids-limit=128 \
  -p 4190:3906 -e SA_FILE_TOKEN=your-test-token sa2web-file-parser:latest
```

The root `Dockerfile` supports Linux amd64 and arm64. The `application` stage
uses Gradle and JDK 21 on the build machine to produce portable Java bytecode;
`builder` installs a target-platform JRE and Tesseract with English and Simplified
Chinese language packs. `runtime` inherits those dependencies, copies the tested
application, and runs as a non-root user. No separate base image or local
`installDist` output is required. Docker caches OCR dependencies independently of
application source, and a BuildKit cache mount preserves Gradle downloads on the
same builder. A fresh builder, deleted cache, or changed dependencies can require
downloads again.

For a multi-platform registry image, use
`docker buildx build --platform linux/amd64,linux/arm64 -t <registry>/sa2web-file-parser:latest --push .`.
Cross-building requires emulation for target-platform package installation;
native builds automatically use the host architecture.


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

## License and third-party software

The original project code is licensed under [Apache License 2.0](LICENSE).
Third-party libraries and container components retain their own licenses.

The container includes GPL/LGPL components, including the OpenJDK runtime
(GPL-2.0 with the Classpath Exception), GNU system tools, and native libraries.
Some Java dependencies offer GPL/LGPL alternatives alongside other license options;
this does not mean GPL is their only available license. Tesseract itself is
Apache-2.0.

See [Third-party licenses](THIRD_PARTY_LICENSES.md) for the reviewed components,
license options, scope and redistribution requirements. Publishing this project's
source and distributing a complete binary/container image have different obligations;
this notice alone does not fulfill all third-party source distribution requirements.
