FROM eclipse-temurin:21-jre-jammy AS builder

# Keep OCR dependencies in a separate layer so application rebuilds reuse them.
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr tesseract-ocr-eng tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*

FROM builder AS runtime

COPY build/install/sa_file /opt/sa_file
ENV SA_FILE_HOST=0.0.0.0 SA_FILE_PORT=3906 SA_FILE_OCR_LANG=eng+chi_sim
USER 10001:10001
EXPOSE 3906
ENTRYPOINT ["/opt/sa_file/bin/sa_file"]
