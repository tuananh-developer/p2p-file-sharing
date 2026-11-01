plugins {
    java
    application
}

group = "com.tanh"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

application {
    mainClass.set("com.tanh.file_sharing.FileSharingApplication")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useUnitPlatform()
}
