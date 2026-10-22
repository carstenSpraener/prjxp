# ==========================================
# Stage 1: Gradle Wrapper (Distribution Download)
# ==========================================
# Nur die Wrapper-Dateien: Diese Layer wird nur invalide, wenn sich der
# Wrapper selbst aendert. Der Download der Gradle-Distribution wird damit
# in einer eigenen, cachebaren Layer gehalten.
FROM eclipse-temurin:21-jdk-jammy AS gradle-wrapper

WORKDIR /app
COPY gradlew ./
COPY gradle/wrapper/ ./gradle/wrapper/
RUN ./gradlew --version --no-daemon

# ==========================================
# Stage 2: Gradle Dependency Cache
# ==========================================
# Nur die Build-Skripte (keine Quellen): Diese Layer wird nur invalide,
# wenn sich settings.gradle, build.gradle oder libs.versions.toml aendern.
# warmUpDependencyCache (Init-Script) loest alle Konfigurationen aller
# Module auf und laed damit alle JARs in den Gradle-Dependency-Cache.
FROM gradle-wrapper AS gradle-deps

COPY settings.gradle build.gradle ./
COPY gradle/libs.versions.toml gradle/cache-warmup.init.gradle ./gradle/
COPY prjxp-common/build.gradle prjxp-common/
COPY chunk-norris/build.gradle chunk-norris/
COPY tibed/build.gradle tibed/
COPY golden-retriever/build.gradle golden-retriever/
COPY mcp-server/build.gradle mcp-server/
COPY docpipe/build.gradle docpipe/
COPY lucene-store/build.gradle lucene-store/
COPY mcp-proxy/build.gradle mcp-proxy/
# prjxp-launcher: leeres Platzhalter-Modul ohne build.gradle

RUN ./gradlew --no-daemon -I gradle/cache-warmup.init.gradle warmUpDependencyCache

# ==========================================
# Stage 3: Java Application Build (Gradle)
# ==========================================
# Volle Quellen: Diese Layer aendert sich bei jedem Commit, aber der Build
# selbst muss weder die Gradle-Distribution noch die Dependencies laden.
FROM gradle-deps AS app-builder

COPY . .
RUN ./gradlew :chunk-norris:shadowJar :tibed:shadowJar :mcp-server:shadowJar --no-daemon

# ==========================================
# Stage 4: Final Runtime Image (Java + TEI)
# ==========================================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 \
    ca-certificates \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 1. Alle drei Fat-JARs aus der Build-Stage (app-builder) kopieren
COPY --from=app-builder /app/chunk-norris/build/libs/chunk-norris-all.jar /app/
COPY --from=app-builder /app/tibed/build/libs/tibed-all.jar /app/
COPY --from=app-builder /app/mcp-server/build/libs/mcp-server-all.jar /app/

# 3. Entrypoint-Skript bereitstellen
COPY prjxp-common/docker/entry.sh /app/entry.sh
RUN chmod +x /app/entry.sh

# 4. Docker-Konfiguration ins Image backen (docker/application.yaml.docker -> application.yaml).
# Fallback-Konfiguration: Wird genutzt, wenn unter /app-source keine eigene
# application.yml/.yaml vorhanden ist.
COPY docker/application.yaml.docker /app/application.yaml

# 5. Hub-Modus Verzeichnisse (DockerHub Phase 05) — existieren auch ohne Volume-Mounts
RUN mkdir -p /import /projects

# Data-Volumes (werden zur Laufzeit gemountet)
VOLUME /app-source
VOLUME /app-source/.prjxp-data/lucene-index

# Language Pack Plugins (WP-1): werden zur Laufzeit gemountet, nie ins Image
# gebacken. Die Apps laufen mit WORKDIR /app-source, daher loest der
# Default-Plugin-Ordner (prjxp.language-packs.dir, Default ./language-packs)
# auf /app-source/language-packs auf. Override: PRJXP_LANGUAGE_PACKS_DIR.
RUN mkdir -p /app-source/language-packs
VOLUME /app-source/language-packs

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
