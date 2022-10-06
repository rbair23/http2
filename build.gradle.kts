plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.twitter:hpack:1.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testImplementation("com.squareup.okhttp3:okhttp:4.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}