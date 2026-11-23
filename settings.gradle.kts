pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
    // Cefrium (bundled Chromium engine: Blink + V8 + Skia) is published only on its
    // own Maven repository. `includeGroup` keeps every other lookup out of it.
    maven("https://maven.cefrium.com") { content { includeGroup("com.cefrium") } }
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // The Chromium engine itself ships from here as com.cefrium:cefrium-sdk.
    maven("https://maven.cefrium.com") { content { includeGroup("com.cefrium") } }
  }
}

rootProject.name = "ADM"

include(":app")
