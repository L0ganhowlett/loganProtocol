plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
}

group = "org.logan"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/release") } // ✅ Needed for Spring Cloud artifacts
}

dependencies {
    implementation(project(":shared"))

    // --- Spring Boot ---
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")

    // --- ✅ Spring Cloud (Eureka Client) ---
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2023.0.3"))
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    // --- OpenAI Java SDK ---
    implementation("com.openai:openai-java:0.21.1")

    // --- AWS SDK (Bedrock, Netty, STS) ---
    implementation("software.amazon.awssdk:bedrockruntime:2.25.64")
    implementation("software.amazon.awssdk:netty-nio-client:2.25.64")
    implementation("software.amazon.awssdk:sts:2.25.64")

    // --- JSON handling ---
    implementation("org.json:json:20240303")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // --- Commons / ICU ---
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.apache.commons:commons-text:1.12.0")
    implementation("com.ibm.icu:icu4j:75.1")

    // --- Testing ---
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
