import com.android.build.api.artifact.SingleArtifact
import java.util.Properties
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
}

// Coverage gate (PLAN.md §11): business rules must stay >= 90% covered by JVM tests.
kover {
    currentProject {
        createVariant("gate") { add("debug") }
    }
    reports {
        variant("gate") {
            filters { includes { packages("com.remedicord.domain") } }
            verify { rule { minBound(90) } }
        }
    }
}

// Room schema history (committed). Also packaged into androidTest assets for migration tests.
room {
    schemaDirectory("$projectDir/schemas")
}

// Release signing key lives in keystore/ (git-ignored). See docs/PLAN.md §3.2.
val keystorePropsFile: File = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.remedicord"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.remedicord"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Debug builds install side by side with the real app and never touch its data.
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        // We pin versions >= 2 weeks old on purpose, so "newer version available" is expected.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Headless emulator started/stopped automatically by Gradle for instrumented tests (PLAN.md §11).
        // "aosp-atd" = Google's slimmed Automated Test Device image.
        managedDevices {
            localDevices {
                create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Encrypted database: Room on SQLCipher (PLAN.md §2, §3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// ---------------------------------------------------------------------------------------------
// Security gate: inspects the FINAL merged manifest (after all libraries are merged in) and
// fails the build if anything could expose the app to the network or to other apps.
// See docs/PLAN.md §12.2 (1).
// ---------------------------------------------------------------------------------------------
abstract class VerifyManifestSecurityTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input abstract val allowedPermissions: SetProperty<String>
    @get:Input abstract val allowedExported: SetProperty<String>
    @get:Input abstract val allowedProviders: SetProperty<String>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val ns = "http://schemas.android.com/apk/res/android"
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(mergedManifest.get().asFile)
        val problems = mutableListOf<String>()
        fun Element.attr(name: String) = getAttributeNS(ns, name)
        fun elements(tag: String): List<Element> =
            doc.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

        // 1. Permissions: exact allow-list (no INTERNET, no network, no storage, nothing else).
        val perms = (elements("uses-permission") + elements("uses-permission-sdk-23")).map { it.attr("name") }
        perms.filterNot { it in allowedPermissions.get() }.forEach { problems += "Permission not allowed: $it" }

        // 2. Application flags.
        val app = elements("application").single()
        if (app.attr("debuggable") == "true") problems += "android:debuggable=true"
        if (app.attr("allowBackup") != "false") problems += "android:allowBackup must be false"
        if (app.attr("usesCleartextTraffic") == "true") problems += "usesCleartextTraffic=true"
        if (doc.documentElement.hasAttributeNS(ns, "sharedUserId")) problems += "sharedUserId is not allowed"

        // 3. No services at all; providers only from the allow-list and never exported.
        elements("service").forEach { problems += "Service not allowed: ${it.attr("name")}" }
        elements("provider").forEach {
            val name = it.attr("name")
            if (name !in allowedProviders.get()) problems += "Provider not allowed: $name"
        }

        // 4. Exported components: only the launcher activity.
        listOf("activity", "activity-alias", "receiver", "service", "provider").flatMap { elements(it) }.forEach {
            if (it.attr("exported") == "true" && it.attr("name") !in allowedExported.get()) {
                problems += "Exported component not allowed: <${it.tagName}> ${it.attr("name")}"
            }
        }

        // 5. No deep links / URL handlers / share targets: no <data> in any intent-filter.
        elements("intent-filter").forEach { filter ->
            if (filter.getElementsByTagName("data").length > 0) {
                problems += "intent-filter with <data> (deep link / share target) is not allowed"
            }
        }

        val text = buildString {
            appendLine("Manifest security check: ${mergedManifest.get().asFile}")
            appendLine("Permissions: ${perms.sorted()}")
            if (problems.isEmpty()) appendLine("RESULT: PASS") else problems.forEach { appendLine("FAIL: $it") }
        }
        report.get().asFile.apply { parentFile.mkdirs(); writeText(text) }
        if (problems.isNotEmpty()) throw GradleException(text)
        logger.lifecycle(text.trim())
    }
}

androidComponents {
    // Strict gate on the RELEASE build (the only APK ever installed on the phone). Debug builds
    // legitimately contain debug tooling (Compose preview activities, debuggable=true) and are emulator-only.
    onVariants(selector().withBuildType("release")) { variant ->
        val appId = variant.applicationId
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val verify = tasks.register<VerifyManifestSecurityTask>("verify${cap}ManifestSecurity") {
            group = "verification"
            description = "Fails if the merged ${variant.name} manifest exposes anything (see PLAN.md §12)."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            allowedPermissions.set(
                appId.map { id ->
                    setOf(
                        // Final list from PLAN.md §5 (added as features arrive):
                        "android.permission.POST_NOTIFICATIONS",
                        "android.permission.USE_EXACT_ALARM",
                        "android.permission.SCHEDULE_EXACT_ALARM",
                        "android.permission.USE_FULL_SCREEN_INTENT",
                        "android.permission.RECEIVE_BOOT_COMPLETED",
                        "android.permission.VIBRATE",
                        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                        // AndroidX internal, signature-level: protects the app's own non-exported receivers.
                        "$id.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                    )
                }
            )
            allowedExported.set(setOf("com.remedicord.MainActivity"))
            // AndroidX's internal startup initializer (non-exported; needed by Lifecycle/Compose).
            allowedProviders.set(setOf("androidx.startup.InitializationProvider"))
            report.set(layout.buildDirectory.file("reports/manifest-security/${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$cap" }.configureEach { dependsOn(verify) }
    }
}

// One command for everything that runs without an emulator (PLAN.md §11).
tasks.register("verifyFast") {
    group = "verification"
    description = "Unit tests + coverage gate + lint + manifest security checks."
    dependsOn("testDebugUnitTest", "koverVerifyGate", "lintRelease", "verifyReleaseManifestSecurity")
}

// Everything, including instrumented tests on the managed emulator. Required to finish a phase.
tasks.register("verifyAll") {
    group = "verification"
    description = "verifyFast + instrumented tests on the Gradle Managed Device + signed release build."
    dependsOn("verifyFast", "pixel6Api34DebugAndroidTest", "assembleRelease")
}
