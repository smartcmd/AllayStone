import org.graalvm.python.pyinterfacegen.J2PyiTask
import org.allaymc.gradle.plugin.tasks.RunServerTask
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import java.util.jar.JarFile

plugins {
    id("java-library")
    id("org.allaymc.gradle.plugin") version "0.2.1"
    id("org.graalvm.python.pyinterfacegen")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "org.allaymc.allaystone"
description = "A python plugin loader & runtime for AllayMC using GraalPython, inspired by Endstone"
version = "0.1.0"
val allayApiVersion = "0.27.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allay {
    api = allayApiVersion

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

    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
}

val allayApiSourceDir = layout.projectDirectory.dir("external/Allay/api/src/main/java")
val pythonHelperSourceDir = layout.projectDirectory.dir("src/main/resources/python/src")
val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")
val generatedPythonSourceDir = generatedResourcesDir.map { it.dir("python/src") }
val allayApiStubModuleDir = layout.buildDirectory.dir("generated/allay-api-stubs")
val pythonStubPackageDir = layout.buildDirectory.dir("generated/python-stub-package")
val pythonResourceListFile = generatedResourcesDir.map { it.file("python/resource-list.txt") }

sourceSets {
    main {
        resources.srcDir(generatedResourcesDir)
    }
}

val verifyAllaySubmodule = tasks.register("verifyAllaySubmodule") {
    doLast {
        require(allayApiSourceDir.asFile.isDirectory) {
            "Allay submodule is missing. Run `git submodule update --init --recursive`."
        }
    }
}

val generateAllayApiPythonStubs = tasks.register<J2PyiTask>("generateAllayApiPythonStubs") {
    dependsOn(verifyAllaySubmodule)
    source = fileTree(allayApiSourceDir) {
        include("org/allaymc/api/**/*.java")
    }
    classpath = configurations.compileClasspath.get()
    setDestinationDir(allayApiStubModuleDir.get().asFile)
    packageMap.set("org.allaymc.api=allay.api")
    moduleName.set("allay-api")
    moduleVersion.set(allayApiVersion)

    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
    }
}

val syncGeneratedPythonStubs = tasks.register<Sync>("syncGeneratedPythonStubs") {
    dependsOn(generateAllayApiPythonStubs)
    from(allayApiStubModuleDir) {
        include("allay/**")
    }
    into(generatedPythonSourceDir)
}

val preparePythonStubPackage = tasks.register<Sync>("preparePythonStubPackage") {
    dependsOn(generateAllayApiPythonStubs)
    from(allayApiStubModuleDir) {
        include("allay/**")
        include("pyproject.toml")
    }
    from(pythonHelperSourceDir) {
        include("allaystone/**")
    }
    into(pythonStubPackageDir)

    doLast {
        val pyprojectFile = pythonStubPackageDir.get().file("pyproject.toml").asFile
        val original = pyprojectFile.readText()
        val packagesLine = Regex("""packages = \[(.*)]""").find(original)
            ?: throw GradleException("Unable to patch generated pyproject.toml packages list.")
        val packages = packagesLine.groupValues[1]
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toMutableList()
        if (!packages.contains("\"allaystone\"")) {
            packages += "\"allaystone\""
        }
        pyprojectFile.writeText(
            original.replace(packagesLine.value, "packages = [${packages.joinToString(", ")}]")
        )
    }
}

val generatePythonResourceList = tasks.register("generatePythonResourceList") {
    dependsOn(syncGeneratedPythonStubs)
    inputs.dir(pythonHelperSourceDir)
    inputs.dir(generatedPythonSourceDir)
    outputs.file(pythonResourceListFile)

    doLast {
        val outputFile = pythonResourceListFile.get().asFile
        val entries = sequenceOf(
            pythonHelperSourceDir.asFile,
            generatedPythonSourceDir.get().asFile
        )
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { it.isFile }
                    .map { file -> "python/src/${file.relativeTo(root).invariantSeparatorsPath}" }
                    .asSequence()
            }
            .distinct()
            .sorted()
            .toList()

        outputFile.parentFile.mkdirs()
        outputFile.writeText(entries.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
    }
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

tasks.named("processResources") {
    dependsOn(generatePythonResourceList)
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
