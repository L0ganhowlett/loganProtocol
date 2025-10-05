plugins {
    java
}

group = "org.logan"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    dependencies {
        testImplementation(platform("org.junit:junit-bom:5.10.0"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")

        //AWS
        // --- AWS SDK (Bedrock, Netty, STS) ---
        implementation("software.amazon.awssdk:bedrockruntime:2.25.64")
        implementation("software.amazon.awssdk:netty-nio-client:2.25.64")
        implementation("software.amazon.awssdk:sts:2.25.64")
    }

    tasks.test {
        useJUnitPlatform()
    }
}
