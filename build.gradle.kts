plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    // Declared here so the build-tool fixes below apply to its classpath too.
    alias(libs.plugins.kover) apply false
}

// Security fixes for BUILD TOOLS only (none of these ship in the app). The latest stable AGP (9.4.1/9.5.0)
// and Kover (0.9.11) still pull in versions with published advisories (OSV scan, 2026-11-11), so the build
// requires the patched releases instead. Remove a line once the plugin itself ships the fixed version.
buildscript {
    dependencies {
        constraints {
            // GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-wg6q-6289-32hp (AGP signing/sdk-common). Keep all three modules aligned.
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            classpath("com.google.guava:guava:33.7.2-jre") // GHSA-xxph-c9ww-hj94 (AGP)
            classpath("org.apache.commons:commons-lang3:3.21.0") // GHSA-j288-q9x7-2f5v (AGP, via commons-compress)
            classpath("org.bitbucket.b_c:jose4j:0.9.7") // GHSA-3677-xxcr-wjqv (AGP bundletool)
            classpath("org.jdom:jdom2:2.0.6.1") // GHSA-2363-cqg2-863c XXE (AGP jetifier)
            classpath("org.freemarker:freemarker:2.3.35") // GHSA-27j2-h3m2-8237 (Kover report generator)
        }
    }
}

// Keep build output OUT of OneDrive (thousands of temp files would be synced and locked).
// On Windows: %LOCALAPPDATA%\reMedicord-build. Elsewhere (e.g. Linux/macOS clones): default build/ dirs.
System.getenv("LOCALAPPDATA")?.let { localAppData ->
    allprojects {
        layout.buildDirectory.set(file("$localAppData/reMedicord-build/${project.name}"))
    }
}
