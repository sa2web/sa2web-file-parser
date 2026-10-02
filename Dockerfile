FROM sa2web-file-parser:base
COPY build/install/sa_file /opt/sa_file
ENV SA_FILE_HOST=0.0.0.0 SA_FILE_PORT=4190 SA_FILE_OCR_LANG=eng+chi_sim
USER 10001:10001
EXPOSE 4190
ENTRYPOINT ["/opt/sa_file/bin/sa_file"]
