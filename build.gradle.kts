plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Keep build output OUT of OneDrive (thousands of temp files would be synced and locked).
// On Windows: %LOCALAPPDATA%\reMedicord-build. Elsewhere (e.g. Linux/macOS clones): default build/ dirs.
System.getenv("LOCALAPPDATA")?.let { localAppData ->
    allprojects {
        layout.buildDirectory.set(file("$localAppData/reMedicord-build/${project.name}"))
    }
}
