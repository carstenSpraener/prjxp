# ==========================================
# Stage 1: Java Application Build (Gradle)
# ==========================================
FROM eclipse-temurin:21-jdk-jammy AS app-builder

WORKDIR /app
COPY . .
RUN ./gradlew :chunk-norris:shadowJar :tibed:shadowJar :mcp-server:shadowJar --no-daemon

# ==========================================
# Stage 2: Final Runtime Image (Java + TEI)
# ==========================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 1. Alle drei Fat-JARs aus Stage 1 kopieren
COPY --from=app-builder /app/chunk-norris/build/libs/chunk-norris-all.jar /app/
COPY --from=app-builder /app/tibed/build/libs/tibed-all.jar /app/
COPY --from=app-builder /app/mcp-server/build/libs/mcp-server-all.jar /app/

# 3. Entrypoint-Skript bereitstellen
COPY prjxp-common/docker/entry.sh /app/entry.sh
RUN chmod +x /app/entry.sh

# 4. Docker-Konfiguration ins Image backen (application.yaml.docker -> application.yaml).
# Fallback-Konfiguration: Wird genutzt, wenn unter /app-source keine eigene
# application.yml/.yaml vorhanden ist.
COPY application.yaml.docker /app/application.yaml

# 5. Hub-Modus Verzeichnisse (DockerHub Phase 05) — existieren auch ohne Volume-Mounts
RUN mkdir -p /import /projects

# Data-Volumes (werden zur Laufzeit gemountet)
VOLUME /app-source
VOLUME /app-source/.prjxp-data/lucene-index

# Default-Fallback, wird zur Laufzeit im ENTRYPOINT ueberschrieben wenn
# /app-source/application.yml oder /app-source/application.yaml existiert.
ENV SPRING_CONFIG_LOCATION=file:/app/

# Konfiguration via Environment Variables (wie in application.yaml referenziert)
ENV LUCENE_INDEX_PATH=.prjxp-data/lucene-index
ENV LUCENE_VECTOR_DIMENSION=1024

# JVM Tuning fuer Container-Umgebung
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 --add-modules jdk.incubator.vector"
ENV SERVER_PORT=7007

# Working Directory ist /app-source (wo .env und projektspezifische Config liegt)
WORKDIR /app-source

ENTRYPOINT ["/app/entry.sh"]
CMD ["serve"]
