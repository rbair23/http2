plugins {
    id("java")
    id("de.jjohannes.extra-java-module-info") version "0.15"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.twitter:hpack:1.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.9.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.0")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

extraJavaModuleInfo {
    automaticModule("com.twitter:hpack", "com.twitter.hpack")
    failOnMissingModuleInfo.set(false)
}