plugins {
    java
}

group = "sk.adamko"
version = rootProject.version

java {
    // Paper 26.x runs on Java 25.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.essentialsx.net/releases/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.258-stable")
    compileOnly("net.essentialsx:EssentialsX:2.21.2") { isTransitive = false }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val ver = project.version.toString()
    inputs.property("version", ver)
    filesMatching("plugin.yml") {
        expand("version" to ver)
    }
}

tasks.jar {
    archiveFileName.set("AdamkoVeloSpawn-Companion-${project.version}.jar")
}
