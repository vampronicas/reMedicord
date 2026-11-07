// Only two official repositories are allowed:
//  - Google Maven (Android / AndroidX / Google libraries), restricted to Google groups
//  - Maven Central (JetBrains Kotlin, Google Dagger/Hilt/KSP, Zetetic SQLCipher, Robolectric)
// The Gradle Plugin Portal is deliberately NOT used.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "reMedicord"
include(":app")
