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

