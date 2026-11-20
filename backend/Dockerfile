# Beside backend (Spring Boot 3.2 / Java 17) image. Built by deploy/docker-compose.yml and CI (docker build backend).
#   docker build -t beside-backend backend
#   docker run --rm -p 8080:8080 -v beside-storage:/app/storage beside-backend            # profile mock (default)
#   docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=real,prod -e INFERENCE_BASEURL=http://100.x.y.z:8001 ... beside-backend
#     (prod does not start without its H2 file: add -e STORAGE_DB_CREATEIFMISSING=true on a new, empty volume only)
# Storage paths are relative to the working directory (storage/uploads, storage/results, ./storage/db/...), so
# WORKDIR is /app and everything the server writes lives in the /app/storage volume.

# ---- build: bootJar only; tests run in CI (.github/workflows/backend-ci.yml), not here ----
FROM eclipse-temurin:17.0.20.1_1-jdk-noble AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# A Windows checkout may hand us gradlew with CRLF (.gitattributes keeps it LF, this is the safety net).
# Resolving dependencies before copying src/ keeps them in a cached layer: code-only changes rebuild fast.
RUN sed -i 's/\r$//' gradlew \
 && chmod +x gradlew \
 && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
# jar { enabled = true } in build.gradle.kts would add a *-plain.jar; only bootJar runs here, the filter is a guard.
RUN ./gradlew --no-daemon bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain\.jar$')" /src/app.jar

# ---- run: JRE only, non-root ----
FROM eclipse-temurin:17.0.20.1_1-jre-noble
RUN groupadd --system --gid 10001 beside \
 && useradd --system --uid 10001 --gid beside --home-dir /app --no-create-home --shell /usr/sbin/nologin beside \
 && mkdir -p /app/storage \
 && chown beside:beside /app/storage
WORKDIR /app
# The jar stays root-owned (read-only for the server); /app/storage is owned by beside, and a new named volume
# copies that ownership on first mount.
COPY --from=build /src/app.jar /app/app.jar
USER beside
VOLUME /app/storage
EXPOSE 8080
# Heap follows the container memory limit (mem_limit in deploy/docker-compose.yml): 40 %, because the preprocessing
# models (ONNX Runtime, mounted at /app/models) live outside the heap. Extra JVM flags: JAVA_TOOL_OPTIONS (a flag
# given here wins over it, so change this line to change the share). ONNX Runtime unpacks its native library to /tmp.
# jdk.httpclient.keepalive.timeout=60: the JDK HttpClient keeps an idle connection for 1200 s by default, longer than
# the Runpod inference service's 75 s (deploy/runpod/start_pod.sh). The client must give a connection up first, or a
# POST /infer/upload can go out on one the Pod already closed (or lost when the Pod stopped without a FIN).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=40", "-Djdk.httpclient.keepalive.timeout=60", "-jar", "/app/app.jar"]
