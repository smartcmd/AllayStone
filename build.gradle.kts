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
    compileOnly(group = "org.projectlombok", name = "lombok", version = "1.18.34")
    annotationProcessor(group = "org.projectlombok", name = "lombok", version = "1.18.34")
}
