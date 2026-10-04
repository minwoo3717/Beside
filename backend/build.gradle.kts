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
    implementation("org.projectlombok:lombok:1.18.28")
    compileOnly("org.projectlombok:lombok:1.18.28")
    annotationProcessor("org.projectlombok:lombok:1.18.28")
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
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("jar") {
    enabled = true
}
