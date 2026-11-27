plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "coneckin.id.biz.id"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "coneckin.id.biz.id"
        minSdk = 29
        targetSdk = 37
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Laporan coverage unit test: ./gradlew createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
        release {
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Daftar eksplisit kelas logika non-UI yang diwajibkan mencapai >= 80% line coverage.
// Layar Compose (MainActivity, ManualLoginScreen, ui.theme.*) serta pembungkus hardware/OS
// Android (PortalLogin, WifiBinder, CryptoManager, AccountStore, AppLock, LoginTileService,
// RemoteCache, ReportSettings, Api) diuji lewat instrumented test / uji manual terpisah.
val logicCoverageClasses = setOf(
    "coneckin/id/biz/id/ModelsKt",
    "coneckin/id/biz/id/Account",
    "coneckin/id/biz/id/Portal",
    "coneckin/id/biz/id/AppData",
    "coneckin/id/biz/id/InputCheck",
    "coneckin/id/biz/id/InputCheck\$Valid",
    "coneckin/id/biz/id/InputCheck\$MissingFields",
    "coneckin/id/biz/id/InputCheck\$InvalidHost",
    "coneckin/id/biz/id/EditInputCheck",
    "coneckin/id/biz/id/EditInputCheck\$Valid",
    "coneckin/id/biz/id/EditInputCheck\$MissingFields",
    "coneckin/id/biz/id/EditInputCheck\$InvalidHost",
    "coneckin/id/biz/id/AccountRepository",
    "coneckin/id/biz/id/AppDataJson",
    "coneckin/id/biz/id/PortalSelectionKt",
    "coneckin/id/biz/id/PortalPick",
    "coneckin/id/biz/id/PortalPick\$Found",
    "coneckin/id/biz/id/PortalPick\$Unknown",
    "coneckin/id/biz/id/PortalPick\$Failed",
    "coneckin/id/biz/id/LoginMappingKt",
    "coneckin/id/biz/id/LoginResult",
    "coneckin/id/biz/id/LoginResult\$AlreadyOnline",
    "coneckin/id/biz/id/LoginResult\$Success",
    "coneckin/id/biz/id/LoginResult\$NoWifi",
    "coneckin/id/biz/id/LoginResult\$UnknownPortal",
    "coneckin/id/biz/id/LoginResult\$Failed",
    "coneckin/id/biz/id/RemoteKt",
    "coneckin/id/biz/id/RemoteConfig",
    "coneckin/id/biz/id/DirectoryPortal",
    "coneckin/id/biz/id/RemoteCacheStore",
    "coneckin/id/biz/id/ReportQueue",
    "coneckin/id/biz/id/ReporterKt",
    "coneckin/id/biz/id/PinManager",
    "coneckin/id/biz/id/PinCheck",
    "coneckin/id/biz/id/PinCheck\$Success",
    "coneckin/id/biz/id/PinCheck\$WrongPin",
    "coneckin/id/biz/id/PinCheck\$Locked"
)

abstract class VerifyLogicCoverageTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Internal
    abstract val coverageDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val targetClasses: org.gradle.api.provider.SetProperty<String>

    @get:org.gradle.api.tasks.Input
    abstract val minLineCoverage: org.gradle.api.provider.Property<Double>

    @org.gradle.api.tasks.TaskAction
    fun verify() {
        val dir = coverageDir.get().asFile
        val xmlFile = dir.walkTopDown().firstOrNull { it.isFile && it.extension == "xml" }
            ?: throw org.gradle.api.GradleException(
                "Laporan XML JaCoCo tidak ditemukan di ${dir.absolutePath}. " +
                        "Pastikan createDebugUnitTestCoverageReport berjalan sukses."
            )

        val classesToCheck = targetClasses.get()
        val threshold = minLineCoverage.get()

        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(xmlFile)
        val classNodes = doc.getElementsByTagName("class")

        var totalMissed = 0
        var totalCovered = 0
        val matched = mutableListOf<String>()

        println("=== Verifikasi Line Coverage Logika Non-UI Coneckin ===")
        for (i in 0 until classNodes.length) {
            val el = classNodes.item(i) as org.w3c.dom.Element
            val name = el.getAttribute("name")
            if (name !in classesToCheck) continue

            var missed = 0
            var covered = 0
            val children = el.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j)
                if (child is org.w3c.dom.Element && child.tagName == "counter" &&
                    child.getAttribute("type") == "LINE"
                ) {
                    missed = child.getAttribute("missed").toInt()
                    covered = child.getAttribute("covered").toInt()
                    break
                }
            }
            val total = missed + covered
            val pct = if (total == 0) 100.0 else (covered * 100.0 / total)
            totalMissed += missed
            totalCovered += covered
            matched.add(name)
            println(String.format("  %-45s : %3d/%-3d (%5.1f%%)", name.substringAfterLast('/'), covered, total, pct))
        }

        if (matched.isEmpty()) {
            throw org.gradle.api.GradleException("Tidak ada kelas logika yang cocok ditemukan di ${xmlFile.name}.")
        }
        val grandTotal = totalMissed + totalCovered
        val ratio = if (grandTotal == 0) 0.0 else (totalCovered * 100.0 / grandTotal)
        println(
            String.format(
                "TOTAL LOGIKA NON-UI (%d kelas): %d/%d baris (%.2f%%) [minimal %.1f%%]",
                matched.size, totalCovered, grandTotal, ratio, threshold
            )
        )

        if (ratio < threshold) {
            throw org.gradle.api.GradleException(
                String.format(
                    "Line coverage logika non-UI %.2f%% kurang dari ambang minimal %.1f%%.",
                    ratio, threshold
                )
            )
        }
    }
}

tasks.register<VerifyLogicCoverageTask>("verifyLogicCoverage") {
    group = "verification"
    description = "Memverifikasi line coverage kode logika non-UI minimal 80% dari laporan JaCoCo AGP."
    dependsOn("createDebugUnitTestCoverageReport")
    coverageDir.set(layout.buildDirectory.dir("reports/coverage/test/debug"))
    targetClasses.set(logicCoverageClasses)
    minLineCoverage.set(80.0)
}
