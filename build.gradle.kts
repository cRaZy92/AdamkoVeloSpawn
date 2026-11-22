plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
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

    // Discord bot for Discord -> Minecraft chat. Bundled (shaded) into the jar; voice support left out.
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java")
        exclude(group = "com.google.crypto.tink")
    }

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

// The plugin jar is the shaded one; the plain jar would be missing JDA.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveFileName.set("AdamkoVeloSpawn-${project.version}.jar")
    archiveClassifier.set("")
    from("THIRD_PARTY_NOTICES.md")
    // Velocity already provides SLF4J (JDA logs through it); everything else is moved into our package
    // so it can never clash with another plugin's copy.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
    }
    val base = "sk.adamko.velospawn.lib"
    relocate("net.dv8tion", "$base.jda")
    relocate("okhttp3", "$base.okhttp3")
    relocate("okio", "$base.okio")
    relocate("kotlin", "$base.kotlin")
    relocate("com.fasterxml", "$base.fasterxml")
    relocate("com.neovisionaries", "$base.neovisionaries")
    relocate("gnu.trove", "$base.trove")
    relocate("org.apache.commons", "$base.commons")
    relocate("org.jetbrains", "$base.jetbrains")
    relocate("org.intellij", "$base.intellij")
    mergeServiceFiles()
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
