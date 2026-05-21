plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.ittai.debugbridge"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.modelcontextprotocol.sdk:mcp-core:1.1.2")
    implementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:1.1.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.ittai.debugbridge.App")
    applicationDefaultJvmArgs = listOf("--add-modules", "jdk.jdi")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.jdi"))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--add-modules", "jdk.jdi")
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes(
            "Main-Class" to "com.ittai.debugbridge.App",
            "Add-Modules" to "jdk.jdi"
        )
    }
    mergeServiceFiles()
}

tasks.jar {
    enabled = false
}

tasks.named("startScripts") {
    dependsOn(tasks.shadowJar)
}
