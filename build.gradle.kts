import org.allaymc.gradle.plugin.tasks.RunServerTask
import org.allaymc.gradle.plugin.tasks.ShadowJarTask

plugins {
    id("java-library")
    id("org.allaymc.gradle.plugin") version "0.2.1"
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

tasks.withType<ShadowJarTask>().configureEach {
    manifest.attributes["Multi-Release"] = "true"

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/*.EC")
    exclude("META-INF/SIG-*")
}

tasks.withType<RunServerTask>().configureEach {
    doNotTrackState("build/run contains live server files that Gradle cannot snapshot")
}
