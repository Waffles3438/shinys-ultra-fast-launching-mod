plugins {
    id("java")
}

group = "org.codeberg.awruff"
version = "1.0.0"

repositories {
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}

dependencies {
    compileOnly("net.fabricmc:fabric-loader:0.15.11")
    compileOnly("net.fabricmc:sponge-mixin:0.13.3+mixin.0.8.5")
    compileOnly("org.ow2.asm:asm-tree:9.6")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 8
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:-options")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
