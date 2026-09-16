# ==========================================
# Stage 1: Java Application Build (Gradle)
# ==========================================
FROM eclipse-temurin:21-jdk-jammy AS app-builder

WORKDIR /app
COPY . .
RUN ./gradlew :chunk-norris:shadowJar :tibed:shadowJar :mcp-server:shadowJar --no-daemon

# ==========================================
# Stage 2: Final Runtime Image (Java + Python Embedding Server)
# ==========================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Python fuer den lokalen Embedding-Server installieren
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    libgomp1 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Python-Dependencies fuer den Embedding-Server
RUN pip3 install --no-cache-dir \
    fastapi \
    uvicorn[standard] \
    hypercorn \
    onnxruntime \
    transformers

# 1. Alle drei Fat-JARs aus Stage 1 kopieren
COPY --from=app-builder /app/chunk-norris/build/libs/chunk-norris-all.jar /app/
COPY --from=app-builder /app/tibed/build/libs/tibed-all.jar /app/
COPY --from=app-builder /app/mcp-server/build/libs/mcp-server-all.jar /app/

# 2. Embedding-Server-Skript kopieren
COPY prjxp-common/embedding-server/scripts/embedding-server.py /app/prjxp-common/embedding-server/scripts/embedding-server.py

# 3. ONNX-Modell & Tokenizer aus dem Repo kopieren
COPY prjxp-common/embedding-server/models/model.onnx /app/prjxp-common/embedding-server/models/model.onnx
COPY prjxp-common/embedding-server/models/tokenizer.json /app/prjxp-common/embedding-server/models/tokenizer.json
COPY prjxp-common/embedding-server/models/vocab.txt /app/prjxp-common/embedding-server/models/vocab.txt
COPY prjxp-common/embedding-server/models/tokenizer_config.json /app/prjxp-common/embedding-server/models/tokenizer_config.json
COPY prjxp-common/embedding-server/models/special_tokens_map.json /app/prjxp-common/embedding-server/models/special_tokens_map.json
COPY prjxp-common/embedding-server/models/config.json /app/prjxp-common/embedding-server/models/config.json
COPY prjxp-common/embedding-server/models/ort_config.json /app/prjxp-common/embedding-server/models/ort_config.json

# 4. Docker-Konfiguration ins Image backen (application.yaml.docker -> application.yaml).
# Fallback-Konfiguration: Wird genutzt, wenn unter /app-source keine eigene
# application.yml/.yaml vorhanden ist.
COPY application.yaml.docker /app/application.yaml

# Data-Volumes (werden zur Laufzeit gemountet)
VOLUME /app-source
VOLUME /app-source/.prjxp-data/lucene-index

# Default-Fallback, wird zur Laufzeit im ENTRYPOINT ueberschrieben wenn
# /app-source/application.yml oder /app-source/application.yaml existiert.
ENV SPRING_CONFIG_LOCATION=file:/app/

# Konfiguration via Environment Variables (wie in application.yaml referenziert)
ENV EMBEDDING_STORE_TYPE=lucene
ENV EMBEDDING_MODEL_TYPE=onnx_local
ENV LUCENE_INDEX_PATH=.prjxp-data/lucene-index
ENV LUCENE_VECTOR_DIMENSION=1024

# JVM Tuning fuer Container-Umgebung
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"
ENV SERVER_PORT=7007

# Working Directory ist /app-source (wo .env und projektspezifische Config liegt)
WORKDIR /app-source

ENTRYPOINT ["sh", "-c", "case \"$0\" in \
  chunk) \
    if [ -f /app-source/application.yml ] || [ -f /app-source/application.yaml ]; then CFG='optional:file:/app-source/,optional:file:/app/'; else CFG='file:/app/'; fi; \
    java $JAVA_OPTS -Dspring.config.location=\"$CFG\" -jar /app/chunk-norris-all.jar ;; \
  embed) \
    if [ -f /app-source/application.yml ] || [ -f /app-source/application.yaml ]; then CFG='optional:file:/app-source/,optional:file:/app/'; else CFG='file:/app/'; fi; \
    java $JAVA_OPTS -Dspring.config.location=\"$CFG\" -jar /app/tibed-all.jar ;; \
  serve|*) \
    if [ -f /app-source/application.yml ] || [ -f /app-source/application.yaml ]; then CFG='optional:file:/app-source/,optional:file:/app/'; else CFG='file:/app/'; fi; \
    java $JAVA_OPTS -Dspring.config.location=\"$CFG\" -jar /app/mcp-server-all.jar ;; \
esac"]
