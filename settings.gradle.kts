fun requireSubmodule(path: String) {
    check(file(path).isDirectory) {
        "Missing required submodule path `$path`. Run `git submodule update --init --recursive`."
    }
}

requireSubmodule("external/Allay")
requireSubmodule("external/graalpy-extensions/pyinterfacegen")
requireSubmodule("external/graalpy-extensions/pyinterfacegen/gradle-plugin")
requireSubmodule("external/JOML")
requireSubmodule("external/joml-primitives")
requireSubmodule("external/nbt")

pluginManagement {
    includeBuild("external/graalpy-extensions/pyinterfacegen/gradle-plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

includeBuild("external/graalpy-extensions/pyinterfacegen") {
    dependencySubstitution {
        substitute(module("org.graalvm.python.pyinterfacegen:j2pyi-doclet"))
            .using(project(":doclet"))
    }
}

rootProject.name = "AllayStone"

