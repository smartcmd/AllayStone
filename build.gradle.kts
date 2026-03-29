import org.allaymc.gradle.plugin.tasks.RunServerTask
import java.util.jar.JarFile

plugins {
    id("java-library")
    id("org.allaymc.gradle.plugin") version "0.2.1"
    id("com.gradleup.shadow") version "9.4.1"
}

group = "org.allaymc.allaystone"
description = "A python plugin loader & runtime for AllayMC using GraalPython, inspired by Endstone"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allay {
    api = "0.27.0"

    plugin {
        entrance = ".AllayStone"
        authors += "daoge_cmd"
        website = "https://github.com/smartcmd/AllayStone"
    }
}

dependencies {
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.python:python-embedding:25.0.2") {
        exclude(group = "org.graalvm.python", module = "python")
    }
    implementation("org.graalvm.python:python-language:25.0.2")
    implementation("org.graalvm.python:python-resources:25.0.2")
    implementation("org.graalvm.truffle:truffle-runtime:25.0.2")

    compileOnly(group = "org.projectlombok", name = "lombok", version = "1.18.34")
    annotationProcessor(group = "org.projectlombok", name = "lombok", version = "1.18.34")
}

val mergedServiceFilesDir = layout.buildDirectory.dir("generated/merged-service-files")

val mergeRuntimeServiceFiles = tasks.register("mergeRuntimeServiceFiles") {
    inputs.files(configurations.runtimeClasspath)
    outputs.dir(mergedServiceFilesDir)

    doLast {
        val outputDir = mergedServiceFilesDir.get().asFile
        delete(outputDir)
        outputDir.mkdirs()

        val merged = linkedMapOf<String, LinkedHashSet<String>>()
        configurations.runtimeClasspath.get().files
            .filter { it.isFile && it.extension == "jar" }
            .sortedBy { it.name }
            .forEach { jarFile ->
                JarFile(jarFile).use { jar ->
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.startsWith("META-INF/services/")) {
                            continue
                        }

                        val lines = jar.getInputStream(entry).bufferedReader().useLines { sequence ->
                            sequence
                                .map(String::trim)
                                .filter(String::isNotEmpty)
                                .toList()
                        }
                        if (lines.isEmpty()) {
                            continue
                        }

                        merged.getOrPut(entry.name) { linkedSetOf() }.addAll(lines)
                    }
                }
            }

        merged.forEach { (path, lines) ->
            val destination = outputDir.resolve(path)
            destination.parentFile.mkdirs()
            destination.writeText(
                lines.joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator())
            )
        }
    }
}

tasks.shadowJar {
    archiveClassifier.set("shaded")
    dependsOn(mergeRuntimeServiceFiles)
    manifest.attributes["Multi-Release"] = "true"

    val mergedServiceRoot = mergedServiceFilesDir.get().asFile.toPath()
    exclude {
        it.path.startsWith("META-INF/services/") && !it.file.toPath().startsWith(mergedServiceRoot)
    }
    from(mergedServiceFilesDir)

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.EC")
    exclude("META-INF/SIG-*")
}

tasks.withType<RunServerTask>().configureEach {
    doNotTrackState("build/run contains live server files that Gradle cannot snapshot")
}
