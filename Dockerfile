# ==========================================
# Stage 1: Model Export & Quantization (Python)
# ==========================================
FROM python:3.11-slim AS model-builder

WORKDIR /build

# Benötigte Tools für HuggingFace Optimum installieren
RUN pip install --no-cache-dir \
    optimum[onnxruntime] \
    transformers \
    torch --extra-index-url https://download.pytorch.org/whl/cpu

# Modell herunterladen und nach ONNX exportieren (optimiert)
# Für mxbai-embed-large: docker build --build-arg HF_TOKEN=<token>
# Ohne Token wird sentence-transformers/all-MiniLM-L6-v2 verwendet (384-dim)
ARG HF_TOKEN=""
ENV HUGGING_FACE_HUB_TOKEN=${HF_TOKEN}

RUN if [ -n "$HUGGING_FACE_HUB_TOKEN" ]; then \
        optimum-cli export onnx \
            --model mixedbread-ai/mxbai-embed-large \
            --task feature-extraction \
            --optimize O2 \
            /build/onnx_model; \
    else \
        optimum-cli export onnx \
            --model sentence-transformers/all-MiniLM-L6-v2 \
            --task feature-extraction \
            --optimize O2 \
            /build/onnx_model; \
    fi

# ==========================================
# Stage 2: Java Application Build (Gradle/Maven)
# ==========================================
FROM eclipse-temurin:21-jdk-jammy AS app-builder

WORKDIR /app
COPY . .
RUN ./gradlew :mcp-server:shadowJar --no-daemon

# ==========================================
# Stage 3: Final Runtime Image (Java + Python Embedding Server)
# ==========================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Python für den lokalen Embedding-Server installieren
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    libgomp1 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Python-Dependencies für den Embedding-Server
RUN pip3 install --no-cache-dir \
    fastapi \
    uvicorn[standard] \
    onnxruntime \
    transformers

# 1. Das ONNX-Modell & Tokenizer aus Stage 1 kopieren
COPY --from=model-builder /build/onnx_model/model.onnx /app/models/mxbai-embed-large.onnx
COPY --from=model-builder /build/onnx_model/tokenizer.json /app/models/tokenizer.json
COPY --from=model-builder /build/onnx_model/vocab.txt /app/models/vocab.txt

# 2. Das gebaute Java Fat-JAR aus Stage 2 kopieren
COPY --from=app-builder /app/mcp-server/build/libs/mcp-server-all.jar /app/mcp-server.jar

# 3. Embedding-Server-Skript kopieren
COPY scripts/embedding-server.py /app/scripts/embedding-server.py

# 4. application.yaml (im shadowJar excluded, muss separat kopiert werden)
COPY application.yaml /app/application.yaml

# Data-Volume für den Lucene-Index
VOLUME /app/data/lucene-index

# Konfiguration via Environment Variables (wie in application.yaml referenziert)
ENV EMBEDDING_STORE_TYPE=lucene
ENV EMBEDDING_MODEL_TYPE=onnx_local
ENV LUCENE_INDEX_PATH=/data/lucene-index
ENV LUCENE_VECTOR_DIMENSION=1024

# JVM Tuning für Container-Umgebung
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "python3 /app/scripts/embedding-server.py --model-path /app/models/mxbai-embed-large.onnx --models-dir /app/models & sleep 5 && java $JAVA_OPTS -jar /app/mcp-server.jar"]
