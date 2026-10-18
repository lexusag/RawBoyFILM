plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The shared RawBoy engine: native decoding (LibRaw, DNG SDK, lens opcodes, blemish
 * healing), the develop shader, LUTs, MediaPipe skin detection and export.
 * Used by the apps (app-pro, app-auto, app-camera).
 *
 * Editions (flavour dimension "edition"):
 * - standard: every app, with the Adobe DNG SDK (when present) and licenses/THIRD_PARTY_NOTICES.txt.
 * - film: RawBoy Film, which lives in its own GPLv3 repository (RawBoy plus shaders/film, app-camera/src/film and
 *   licenses/THIRD_PARTY_NOTICES_FILM.txt). The flavour only exists where shaders/film does, so this repository
 *   builds the standard edition alone. Both editions bundle the stocks' LUTs (luts/spektrafilm, CC BY-SA 4.0).
 */
val hasFilmEdition = rootProject.file("shaders/film").isDirectory

android {
    namespace = "app.rawboy.engine"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += providers.gradleProperty("rawboy.abis").get().split(",")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                // 16 KB page-size compatible native libraries (required by Google Play).
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "FILM_EDITION", "false")
        }
        if (hasFilmEdition) create("film") {
            dimension = "edition"
            buildConfigField("boolean", "FILM_EDITION", "true")
            // GPLv3: no Adobe DNG SDK (CMake option, see src/main/cpp/CMakeLists.txt).
            externalNativeBuild { cmake { arguments += "-DRAWBOY_USE_DNG_SDK=OFF" } }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // MediaPipe memory-maps the model; it must be stored uncompressed.
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.exifinterface)
    implementation(libs.mediapipe.tasks.vision)
    // JVM unit tests (./gradlew :engine:testDebugUnitTest); org.json is only a stub in android.jar.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

/**
 * Bundles <repo>/luts into the engine as assets/luts (only .cube and .png), plus luts/spektrafilm (the film stocks:
 * cubes, stocks.json, formation.json, and the CC BY-SA licence and changelog that must travel with them).
 */
abstract class CopyLutsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lutDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("luts")
        out.deleteRecursively()
        out.mkdirs()
        lutDir.get().asFile.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("cube", "png") }
            ?.forEach { it.copyTo(out.resolve(it.name)) }
        val film = out.resolve("spektrafilm").apply { mkdirs() }
        lutDir.get().asFile.resolve("spektrafilm").listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("cube", "json", "txt") }
            ?.forEach { it.copyTo(film.resolve(it.name)) }
    }
}

val copyLuts = tasks.register<CopyLutsTask>("copyLuts") {
    lutDir.set(rootProject.layout.projectDirectory.dir("luts"))
}

/** Bundles the edition's notices (licenses/THIRD_PARTY_NOTICES*.txt) into the engine as assets/third_party_notices.txt. */
abstract class CopyNoticesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notices: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        notices.get().asFile.copyTo(outputDir.get().asFile.resolve("third_party_notices.txt"), overwrite = true)
    }
}

val copyNotices = tasks.register<CopyNoticesTask>("copyNotices") {
    notices.set(rootProject.layout.projectDirectory.file("licenses/THIRD_PARTY_NOTICES.txt"))
}
val copyNoticesFilm = tasks.register<CopyNoticesTask>("copyNoticesFilm") {
    notices.set(rootProject.layout.projectDirectory.file("licenses/THIRD_PARTY_NOTICES_FILM.txt"))
}

/**
 * Standard release builds must include the Adobe DNG SDK (JPEG XL DNGs, and the notices assume it's there).
 * Debug builds still work without it; the film edition never has it.
 */
val checkDngSdk = tasks.register("checkDngSdk") {
    val sdk = rootProject.layout.projectDirectory.file("third_party/dng_sdk/dng_sdk/source/dng_host.cpp").asFile
    doLast {
        if (!sdk.exists()) {
            throw GradleException("Release builds need the Adobe DNG SDK in third_party/dng_sdk; see third_party/README.md")
        }
    }
}
tasks.matching { it.name == "preStandardReleaseBuild" }.configureEach { dependsOn(checkDngSdk) }

/**
 * Turns <repo>/shaders into app/rawboy/gl/ShaderSources.kt: one `const val` per file, named after it
 * (develop.glsl -> DEVELOP, develop.vert -> DEVELOP_VERT). The .glsl files are the source of truth,
 * shared with the web build; generating Kotlin keeps shader setup free of Context and asset IO.
 * The film edition adds shaders/film (FILM); standard builds get an empty FILM, so no film code.
 */
abstract class GenerateShaderSourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val shaderDir: DirectoryProperty

    @get:Input
    abstract val withFilm: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = shaderDir.get().asFile
        fun shaders(d: java.io.File) = d.listFiles()
            ?.filter { it.isFile && it.extension in setOf("glsl", "vert", "frag") }
            ?.sortedBy { it.name }
            .orEmpty()
        val files = shaders(dir) + if (withFilm.get()) shaders(dir.resolve("film")) else emptyList()
        val out = outputDir.get().asFile.resolve("app/rawboy/gl")
        out.deleteRecursively()
        out.mkdirs()
        val text = buildString {
            append("// Generated from <repo>/shaders by generateShaderSources. Do not edit.\n")
            append("package app.rawboy.gl\n\n")
            append("internal object ShaderSources {\n")
            for (file in files) {
                val source = file.readText()
                require("\"\"\"" !in source) { "${file.name}: triple quotes can't go in a Kotlin raw string" }
                // The JVM limits a string constant to 65535 bytes (modified UTF-8).
                require(source.toByteArray().size < 65_000) { "${file.name} is too long for one constant; split it" }
                val name = file.nameWithoutExtension.uppercase() + if (file.extension == "glsl") "" else "_" + file.extension.uppercase()
                val escaped = source.replace("$", "\${'$'}")
                append("    const val $name = \"\"\"$escaped\"\"\"\n\n")
            }
            if (files.none { it.nameWithoutExtension == "film" }) append("    const val FILM = \"\"\n")
            append("}\n")
        }
        out.resolve("ShaderSources.kt").writeText(text)
    }
}

fun registerShaderSources(name: String, film: Boolean) = tasks.register<GenerateShaderSourcesTask>(name) {
    shaderDir.set(rootProject.layout.projectDirectory.dir("shaders"))
    withFilm.set(film)
}
val generateShaderSources = registerShaderSources("generateShaderSources", film = false)
val generateShaderSourcesFilm = registerShaderSources("generateShaderSourcesFilm", film = true)

androidComponents {
    onVariants { variant ->
        val film = variant.flavorName == "film"
        variant.sources.assets?.addGeneratedSourceDirectory(copyLuts, CopyLutsTask::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(if (film) copyNoticesFilm else copyNotices, CopyNoticesTask::outputDir)
        // Registered as a java source dir: KGP 2.1 compiles .kt files there but not in AGP's kotlin sources.
        variant.sources.java?.addGeneratedSourceDirectory(
            if (film) generateShaderSourcesFilm else generateShaderSources, GenerateShaderSourcesTask::outputDir,
        )
    }
}
