plugins {
    id("org.springframework.boot") version "3.2.0"
    id("io.spring.dependency-management") version "1.1.0"
    java
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 작업·Idempotency-Key 저장소: H2 파일 DB (storage/db). 테스트는 컨텍스트마다 인메모리 H2.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    // API v1 스펙 생성 (/v3/api-docs, /swagger-ui). 계약 테스트가 docs/api/openapi.yaml 과 비교한다.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")
    // 운영 지표 (docs/METRICS.md §5): Actuator 는 관리 포트(management.server.port, 기본 8081)에만, Prometheus 형식으로.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // 대표 사진 검사·전처리(preprocess): ImageIO 가 WEBP 사진도 읽도록 하는 플러그인 (ImageIO 서비스로 자동 등록).
    // 검토 브랜치 review/api-v1.1 의 썸네일과 같은 버전.
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3")
    // 대표 사진 검사·전처리: YOLOX(개·고양이 감지)와 U2Net(배경 제거)을 CPU 로 실행. jar 에 모든 플랫폼 네이티브가 들어 있다.
    // 모델 파일은 리포 밖(scripts/fetch_models.*, backend/models/README.md).
    implementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    implementation("org.projectlombok:lombok:1.18.34")
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // 계약 테스트에서 openapi.yaml 을 읽는다.
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
}

tasks.withType(JavaCompile::class.java) {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // 계약 테스트의 문서 경로 재정의: ./gradlew.bat test -Dbeside.openapi=<path> (기본 ../docs/api/openapi.yaml)
    System.getProperty("beside.openapi")?.let { systemProperty("beside.openapi", it) }
    // 모델 테스트(@Tag("models"))의 모델 폴더: -Dbeside.models=<dir> 또는 환경변수 PREPROCESS_MODELSDIR. 없으면 건너뛴다.
    System.getProperty("beside.models")?.let { systemProperty("beside.models", it) }
    System.getenv("PREPROCESS_MODELSDIR")?.let { environment("PREPROCESS_MODELSDIR", it) }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("jar") {
    enabled = true
}

// The batch tool below has a main() too: name the server's for bootJar/bootRun.
springBoot {
    mainClass.set("com.example.mockbackend.MockBackendApplication")
}

// 대표 사진 검사·전처리를 사진 폴더에 일괄로 돌려 전/후 이미지·report.csv·index.html 을 만든다(backend/README.md).
//   .\gradlew.bat preprocessBatch --args="--in <폴더> --out <폴더> [--models <dir>] [--set key=value]"
// 모델 폴더: --models 또는 PREPROCESS_MODELSDIR. Windows 는 msvcp140 14.40+ JDK 필요(backend/models/README.md).
tasks.register<JavaExec>("preprocessBatch") {
    group = "application"
    description = "Runs the first-photo check and both cutouts on a folder of photos (before/after, report.csv)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.example.mockbackend.tool.PreprocessBatch")
    workingDir = projectDir
    maxHeapSize = "2g"
    System.getenv("PREPROCESS_MODELSDIR")?.let { environment("PREPROCESS_MODELSDIR", it) }
}
