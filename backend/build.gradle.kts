import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "com.mnirog"
version = "0.1.0"

application {
    mainClass.set("com.mnirog.backend.ApplicationKt")
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:3.1.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.2")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.2")
    implementation("io.ktor:ktor-server-auth-jvm:3.1.2")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:3.1.2")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.1.2")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.1.2")
    implementation("io.ktor:ktor-client-core-jvm:3.1.2")
    implementation("io.ktor:ktor-client-cio-jvm:3.1.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:6.2.1")
    testImplementation(kotlin("test-junit"))
}
