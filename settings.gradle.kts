pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // ProjectM-TV's core engine (nl.neerdael.projectm.core), attached as an AAR to each
        // ProjectM-TV GitHub Release: version X.Y.Z is download/vX.Y.Z/projectM-TV-core-X.Y.Z.aar, and
        // version "latest" is latest/download/projectM-TV-core.aar (the newest stable release).
        // -PprojectmCoreRepo=<dir or URL> points at the same layout elsewhere, e.g. a locally built core.
        ivy {
            name = "ProjectMTvReleases"
            url =
                uri(
                    providers
                        .gradleProperty("projectmCoreRepo")
                        .getOrElse("https://github.com/johnneerdael/ProjectM-TV/releases"),
                )
            patternLayout {
                artifact("download/v[revision]/[module]-[revision].[ext]")
                artifact("[revision]/download/[module].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("nl.neerdael.projectm", "projectM-TV-core") }
        }
    }
}

rootProject.name = "Milkbeat"
include(":app")
include(":plugin-api")
include(":spike-plugin-runtime")
include(":benchmark")

include(":media3-sabr")

include(":media3-sabr-protocol")
