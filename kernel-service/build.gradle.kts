plugins {
    id("org.springframework.boot") version "3.3.2"
    id("io.spring.dependency-management") version "1.1.5"
    java
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/release") } // ✅ Required for Spring Cloud
}

dependencies {
    implementation(project(":shared"))

    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // ✅ MySQL driver
    runtimeOnly("com.mysql:mysql-connector-j")

    // ✅ Flyway (latest compatible with Boot 3.3.x)
    implementation("org.flywaydb:flyway-core:10.10.0")
    implementation("org.flywaydb:flyway-mysql:10.10.0")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // JSON + OpenAI client
    implementation("com.theokanning.openai-gpt3-java:service:0.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // ✅ Spring Cloud BOM import (ensures version compatibility)
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2023.0.3"))

    // ✅ Eureka client (compatible with Spring Boot 3.3.x)
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    // Test dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
