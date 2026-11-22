plugins {
    java
}

group = "sk.adamko"
version = "1.0.0"

java {
    // Velocity 4.x API jars are compiled for Java 25, so compile with a JDK 25 toolchain,
    // but emit Java 21 bytecode (options.release below) as the plan asks.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    disableAutoTargetJvm()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Matches the proxy build.
val velocityVersion = "4.3.2-SNAPSHOT"

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityVersion")
    compileOnly("net.luckperms:api:5.4")

    testImplementation("com.velocitypowered:velocity-api:$velocityVersion")
    testImplementation("org.mockito:mockito-core:5.24.0")
    mockitoAgent("org.mockito:mockito-core:5.24.0") { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    // Mockito's inline mock maker needs its agent attached explicitly on JDK 21+.
    jvmArgs("-javaagent:${mockitoAgent.asPath}", "-Xshare:off")
}

// Bake the project version into the @Plugin annotation.
val generateBuildConstants = tasks.register("generateBuildConstants") {
    val outDir = layout.buildDirectory.dir("generated/sources/buildconstants")
    val ver = project.version.toString()
    inputs.property("version", ver)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("sk/adamko/velospawn/BuildConstants.java").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package sk.adamko.velospawn;

            public final class BuildConstants {
                public static final String VERSION = "$ver";
                private BuildConstants() {}
            }
            """.trimIndent()
        )
    }
}

sourceSets.main {
    java.srcDir(generateBuildConstants)
}

tasks.jar {
    archiveFileName.set("AdamkoVeloSpawn-${project.version}.jar")
    from("THIRD_PARTY_NOTICES.md")
}
