plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "com.momentory"
version = "0.0.1-SNAPSHOT"
description = "Momentory backend"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("com.github.ben-manes.caffeine:caffeine")
    // Boot 4 의 OTLP 메트릭 자동 설정은 spring-boot-opentelemetry 가 있어야 켜진다(트레이싱은 넣지 않는다).
    implementation("org.springframework.boot:spring-boot-opentelemetry")
    runtimeOnly("io.micrometer:micrometer-registry-otlp")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// >>> eval: 안전성 평가 하네스(src/test/.../eval, @Tag("eval"))는 기본 test 에서 빼고 evalB0 로만 돌린다.
tasks.test {
    useJUnitPlatform { excludeTags("eval") }
}

tasks.register<Test>("evalB0") {
    description = "B0 재생 하네스: ./gradlew evalB0 -Peval.data=<jsonl> -Peval.out=<jsonl> [-Peval.system=B0-pre] [-Peval.mode=mock|real]"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("eval") }
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    listOf("eval.data", "eval.out", "eval.system", "eval.mode").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
// <<< eval
