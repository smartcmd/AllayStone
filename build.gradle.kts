import org.allaymc.gradle.plugin.tasks.RunServerTask
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.kotlin.dsl.support.serviceOf
import org.gradle.process.ExecOperations
import org.graalvm.python.pyinterfacegen.J2PyiTask
import java.io.File
import java.util.jar.JarFile

plugins {
    id("java-library")
    id("org.allaymc.gradle.plugin") version "0.2.1"
    id("org.graalvm.python.pyinterfacegen")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "org.allaymc.allaystone"
description = "A python plugin loader & runtime for AllayMC using GraalPython, inspired by Endstone"
version = "0.1.5-SNAPSHOT"

val allayApiVersion = "0.27.0"
val graalVersion = "25.0.2"
val lombokVersion = "1.18.34"
val ruffVersion = "0.15.8"
val semverVersion = "6.0.0"

val execOperations = project.serviceOf<ExecOperations>()
val delombok by configurations.creating

val allayApiSourceDir = layout.projectDirectory.dir("external/Allay/api/src/main/java")
val pythonHelperSourceDir = layout.projectDirectory.dir("src/main/resources/python/src")

val generatedResourcesDir = layout.buildDirectory.dir("generated/resources")
val generatedPythonSourceDir = generatedResourcesDir.map { it.dir("python/src") }
val pythonResourceListFile = generatedResourcesDir.map { it.file("python/resource-list.txt") }

val allayApiDelombokedSourceDir = layout.buildDirectory.dir("generated/delombok/allay-api")
val allayApiStubModuleDir = layout.buildDirectory.dir("generated/allay-api-stubs")
val allayApiRuntimeStubModuleDir = layout.buildDirectory.dir("generated/allay-api-runtime-stubs")
val allayApiFormattedStubModuleDir = layout.buildDirectory.dir("generated/allay-api-formatted-stubs")
val pythonStubPackageDir = layout.buildDirectory.dir("generated/python-stub-package")

val pythonToolDir = layout.buildDirectory.dir("python-tools")
val ruffInstallDir = pythonToolDir.map { it.dir("ruff") }

val configuredPythonExecutable = providers.gradleProperty("pythonExecutable")
    .orElse(providers.environmentVariable("PYTHON"))
    .orNull
val defaultPythonCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
    listOf("py", "-3")
} else {
    listOf("python3")
}

fun pythonCommand(vararg args: String): List<String> {
    val prefix = configuredPythonExecutable?.let(::listOf) ?: defaultPythonCommand
    return prefix + args
}

fun prependPythonPath(extraPath: String): String {
    val existingPythonPath = System.getenv("PYTHONPATH")
    return if (existingPythonPath.isNullOrBlank()) {
        extraPath
    } else {
        "$extraPath${File.pathSeparator}$existingPythonPath"
    }
}

fun collectNestedClassNames(jars: Iterable<File>): Map<String, String> {
    val nestedClassNames = linkedMapOf<String, String>()
    jars.filter { it.isFile && it.extension == "jar" }
        .sortedBy { it.name }
        .forEach { jarFile ->
            JarFile(jarFile).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith("org/allaymc/api/") || !entry.name.endsWith(".class") || '$' !in entry.name) {
                        continue
                    }

                    val binaryName = entry.name.removeSuffix(".class").replace('/', '.')
                    val nestedSegments = binaryName.substringAfter("org.allaymc.api.").split('$').drop(1)
                    if (nestedSegments.isEmpty() || nestedSegments.any { segment ->
                            segment.isEmpty() ||
                                !Character.isJavaIdentifierStart(segment[0]) ||
                                segment.any { ch -> !Character.isJavaIdentifierPart(ch) }
                        }) {
                        continue
                    }

                    nestedClassNames.putIfAbsent(binaryName.replace('$', '.'), binaryName)
                }
            }
        }
    return nestedClassNames
}

fun patchStubPackagePyproject(pyprojectFile: File) {
    val original = pyprojectFile.readText()
    val packagesLine = Regex("""packages = \[(.*)]""").find(original)
        ?: throw GradleException("Unable to patch generated pyproject.toml packages list.")
    val packageDataLine = Regex("^\"\\*\" = \\[(.*)]$", RegexOption.MULTILINE).find(original)
        ?: throw GradleException("Unable to patch generated pyproject.toml package-data list.")
    val packages = packagesLine.groupValues[1]
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toMutableList()
    val packageData = packageDataLine.groupValues[1]
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toMutableList()
    if (!packages.contains("\"allaystone\"")) {
        packages += "\"allaystone\""
    }
    if (!packageData.contains("\"py.typed\"")) {
        packageData += "\"py.typed\""
    }

    var updated = original.replace(packagesLine.value, "packages = [${packages.joinToString(", ")}]")
    updated = updated.replace(packageDataLine.value, "\"*\" = [${packageData.joinToString(", ")}]")
    pyprojectFile.writeText(updated)
}

fun mergeServiceDefinitions(jars: Iterable<File>): Map<String, LinkedHashSet<String>> {
    val merged = linkedMapOf<String, LinkedHashSet<String>>()
    jars.filter { it.isFile && it.extension == "jar" }
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
    return merged
}

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
    implementation("org.graalvm.polyglot:polyglot:$graalVersion")
    implementation("org.graalvm.python:python-embedding:$graalVersion") {
        exclude(group = "org.graalvm.python", module = "python")
    }
    implementation("org.graalvm.python:python-language:$graalVersion")
    implementation("org.graalvm.python:python-resources:$graalVersion")
    implementation("org.graalvm.truffle:truffle-runtime:$graalVersion")

    compileOnly("org.semver4j:semver4j:$semverVersion")
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    delombok("org.projectlombok:lombok:$lombokVersion")
}

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

val delombokAllayApi = tasks.register<JavaExec>("delombokAllayApi") {
    val outputDir = allayApiDelombokedSourceDir.get().asFile

    dependsOn(verifyAllaySubmodule)
    inputs.dir(allayApiSourceDir)
    inputs.files(configurations.compileClasspath)
    outputs.dir(allayApiDelombokedSourceDir)

    classpath(delombok)
    mainClass.set("lombok.launch.Main")
    args(
        "delombok",
        allayApiSourceDir.asFile.absolutePath,
        "--target",
        outputDir.absolutePath,
        "--classpath",
        configurations.compileClasspath.get().asPath,
        "--encoding",
        "UTF-8"
    )

    doFirst {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
    }
}

val generateAllayApiPythonStubs = tasks.register<J2PyiTask>("generateAllayApiPythonStubs") {
    dependsOn(delombokAllayApi)
    source = fileTree(allayApiDelombokedSourceDir) {
        include("org/allaymc/api/**/*.java")
    }
    classpath = configurations.compileClasspath.get()
    setDestinationDir(allayApiStubModuleDir.get().asFile)
    packageMap.set("org.allaymc.api=allay.api")
    moduleName.set("allay-api")
    moduleVersion.set(allayApiVersion)

    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xj2pyi-propertySynthesis", "false")
    }
}

val fixGeneratedAllayApiRuntimeStubs = tasks.register("fixGeneratedAllayApiRuntimeStubs") {
    val outputDir = allayApiRuntimeStubModuleDir.get().asFile

    dependsOn(generateAllayApiPythonStubs)
    inputs.dir(allayApiStubModuleDir)
    inputs.files(configurations.compileClasspath)
    outputs.dir(allayApiRuntimeStubModuleDir)

    doLast {
        outputDir.deleteRecursively()
        copy {
            from(allayApiStubModuleDir)
            into(outputDir)
        }

        val nestedClassNames = collectNestedClassNames(configurations.compileClasspath.get().files)
        fileTree(outputDir) {
            include("**/__init__.py")
        }.forEach { stubFile ->
            val original = stubFile.readText()
            var updated = original
            nestedClassNames.forEach { (canonicalName, binaryName) ->
                updated = updated.replace("java.type(\"$canonicalName\")", "java.type(\"$binaryName\")")
            }
            if (updated != original) {
                stubFile.writeText(updated)
            }
        }
    }
}

generateAllayApiPythonStubs.configure {
    finalizedBy(fixGeneratedAllayApiRuntimeStubs)
}

val installRuff = tasks.register("installRuff") {
    val outputDir = ruffInstallDir.get().asFile

    inputs.property("pythonCommand", configuredPythonExecutable ?: defaultPythonCommand.joinToString(" "))
    inputs.property("ruffVersion", ruffVersion)
    outputs.dir(ruffInstallDir)

    doLast {
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        execOperations.exec {
            commandLine(
                pythonCommand(
                    "-m",
                    "pip",
                    "install",
                    "--disable-pip-version-check",
                    "--quiet",
                    "--target",
                    outputDir.absolutePath,
                    "ruff==$ruffVersion"
                )
            )
        }
    }
}

val formatGeneratedAllayApiPythonStubs = tasks.register("formatGeneratedAllayApiPythonStubs") {
    val outputDir = allayApiFormattedStubModuleDir.get().asFile

    dependsOn(fixGeneratedAllayApiRuntimeStubs, installRuff)
    inputs.dir(allayApiRuntimeStubModuleDir)
    inputs.dir(ruffInstallDir)
    inputs.property("pythonCommand", configuredPythonExecutable ?: defaultPythonCommand.joinToString(" "))
    outputs.dir(allayApiFormattedStubModuleDir)

    doLast {
        outputDir.deleteRecursively()
        copy {
            from(allayApiRuntimeStubModuleDir)
            into(outputDir)
        }

        execOperations.exec {
            environment("PYTHONPATH", prependPythonPath(ruffInstallDir.get().asFile.absolutePath))
            commandLine(pythonCommand("-m", "ruff", "format", outputDir.absolutePath))
        }
    }
}

val syncGeneratedPythonStubs = tasks.register<Sync>("syncGeneratedPythonStubs") {
    dependsOn(formatGeneratedAllayApiPythonStubs)
    from(allayApiFormattedStubModuleDir) {
        include("allay/**")
    }
    into(generatedPythonSourceDir)
}

val preparePythonStubPackage = tasks.register<Sync>("preparePythonStubPackage") {
    dependsOn(formatGeneratedAllayApiPythonStubs)
    from(allayApiFormattedStubModuleDir) {
        include("allay/**")
        include("pyproject.toml")
    }
    from(pythonHelperSourceDir) {
        include("allaystone/**")
    }
    into(pythonStubPackageDir)

    doLast {
        val outputDir = pythonStubPackageDir.get().asFile
        patchStubPackagePyproject(File(outputDir, "pyproject.toml"))
        File(outputDir, "allay/api/py.typed").apply {
            parentFile.mkdirs()
            writeText("")
        }
        File(outputDir, "allaystone/py.typed").apply {
            parentFile.mkdirs()
            writeText("")
        }
    }
}

val generatePythonResourceList = tasks.register("generatePythonResourceList") {
    inputs.dir(pythonHelperSourceDir)
    inputs.dir(generatedPythonSourceDir)
    outputs.file(pythonResourceListFile)
    dependsOn(syncGeneratedPythonStubs)

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

tasks.named("processResources") {
    dependsOn(generatePythonResourceList)
}

val mergedServiceFilesDir = layout.buildDirectory.dir("generated/merged-service-files")

val mergeRuntimeServiceFiles = tasks.register("mergeRuntimeServiceFiles") {
    val outputDir = mergedServiceFilesDir.get().asFile

    inputs.files(configurations.runtimeClasspath)
    outputs.dir(mergedServiceFilesDir)

    doLast {
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        mergeServiceDefinitions(configurations.runtimeClasspath.get().files).forEach { (path, lines) ->
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
