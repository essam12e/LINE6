// NITAAQ Mirror — "lite" pipeline (no Android Gradle Plugin). Used for local compile checks and the JVM tests
// where Google's hosts are unreachable. The APKs that are delivered are built by CI with the official toolchain.
//
//   aapt2 compile/link  ->  kotlinc (against android.jar)  ->  dx  ->  zip + 4-byte align  ->  apksig (v1+v2)
//
// It builds the very same sources as ../app (the regular Android Studio / AGP project).
// The SDK location is read from NITAAQ_SDK_LITE (default /opt/android-sdk-lite); create it with
// ./setup-sdk-lite.sh, or point it at a normal Android SDK that has platforms/android-35 and an aapt2.

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.FileInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("com.android.tools.build:apksig:2.3.0") }
}

plugins {
    kotlin("jvm") version "2.0.21"
}

val applicationId = "sa.nitaaq.mirror"
val versionCode = 5
val versionName = "0.1.4"
val minSdk = 26
val targetSdk = 35

val sdkDir = file(System.getenv("NITAAQ_SDK_LITE") ?: "/opt/android-sdk-lite")
val androidJar = File(sdkDir, "platforms/android-35/android.jar")
val aapt2 = listOf(File(sdkDir, "aapt2"), File(sdkDir, "build-tools/35.0.0/aapt2")).firstOrNull { it.canExecute() }
    ?: File(sdkDir, "aapt2")
val appMain = file("../app/src/main")
val genDir = layout.buildDirectory.dir("generated/r").get().asFile
val resOut = layout.buildDirectory.dir("res").get().asFile
val outDir = layout.buildDirectory.dir("outputs").get().asFile

val dx: Configuration by configurations.creating
val smali: Configuration by configurations.creating
val proguard: Configuration by configurations.creating

dependencies {
    compileOnly(files(androidJar))
    implementation("com.google.zxing:core:3.5.3")
    dx("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    smali("org.smali:baksmali:2.5.2")
    proguard("com.guardsquare:proguard-base:7.6.1")
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // dx cannot desugar invokedynamic, so lambdas / SAM conversions must be real classes.
        freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class")
    }
}

sourceSets {
    main {
        kotlin.srcDir(appMain.resolve("java"))
        kotlin.srcDir(file("../app/src/full/java"))
        java.srcDir(genDir)
        java.srcDir(layout.buildDirectory.dir("generated/buildconfig"))
    }
    test {
        kotlin.srcDir(file("../app/src/test/java"))
    }
}

// App code must see only android.jar's java.* (what exists on the phone), not the build machine's JDK.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.freeCompilerArgs.add("-no-jdk")
}

// The test JVM has no android.jar: only the platform-free classes (protocol, mapping, planner, ...) are tested.
tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(listOf("-bootclasspath", androidJar.path, "-Xlint:-options"))
}

val aapt2Compile by tasks.registering(Exec::class) {
    inputs.dir(appMain.resolve("res"))
    outputs.file(File(resOut, "compiled.zip"))
    doFirst { resOut.mkdirs() }
    commandLine(aapt2.path, "compile", "--dir", appMain.resolve("res").path, "-o", File(resOut, "compiled.zip").path)
}

/** AGP takes the package from `namespace`; aapt2 needs it in the manifest, so inject it into a copy. */
val prepareManifest by tasks.registering {
    val src = appMain.resolve("AndroidManifest.xml")
    val dst = File(resOut, "AndroidManifest.xml")
    inputs.file(src)
    outputs.file(dst)
    doLast {
        resOut.mkdirs()
        val text = src.readText()
        // Package attribute + fully-qualified component names, as AGP's manifest merger emits them.
        val withPkg = if (text.contains(" package=")) text else text.replaceFirst("<manifest ", "<manifest package=\"$applicationId\" ")
        dst.writeText(withPkg.replace("android:name=\".", "android:name=\"$applicationId."))
    }
}

val aapt2Link by tasks.registering(Exec::class) {
    dependsOn(aapt2Compile, prepareManifest)
    inputs.file(File(resOut, "AndroidManifest.xml"))
    inputs.file(File(resOut, "compiled.zip"))
    outputs.file(File(resOut, "resources.ap_"))
    outputs.dir(genDir)
    doFirst { genDir.deleteRecursively(); genDir.mkdirs() }
    commandLine(
        aapt2.path, "link",
        "-I", androidJar.path,
        "--manifest", File(resOut, "AndroidManifest.xml").path,
        "--min-sdk-version", "$minSdk", "--target-sdk-version", "$targetSdk",
        "--version-code", "$versionCode", "--version-name", versionName,
        "--java", genDir.path,
        "-o", File(resOut, "resources.ap_").path,
        File(resOut, "compiled.zip").path,
    )
}
val buildConfig by tasks.registering {
    val out = layout.buildDirectory.file("generated/buildconfig/sa/nitaaq/mirror/BuildConfig.java").get().asFile
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        out.writeText("package sa.nitaaq.mirror;\npublic final class BuildConfig {\n  public static final boolean TOUCH_CONTROL = true;\n}\n")
    }
}
tasks.named("compileKotlin") { dependsOn(aapt2Link, buildConfig) }
tasks.named("compileJava") { dependsOn(aapt2Link, buildConfig) }

/** Jars on the runtime classpath, with META-INF/versions/9 module descriptors stripped (dx can't parse them). */
val dexInputs by tasks.registering {
    dependsOn("classes")
    val out = layout.buildDirectory.dir("dexin").get().asFile
    inputs.files(configurations.runtimeClasspath)
    outputs.dir(out)
    doLast {
        out.deleteRecursively(); out.mkdirs()
        configurations.runtimeClasspath.get().files.filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { zin ->
                ZipOutputStream(File(out, jar.name).outputStream()).use { zout ->
                    for (e in zin.entries()) {
                        if (!e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                        zout.putNextEntry(ZipEntry(e.name))
                        zin.getInputStream(e).use { it.copyTo(zout) }
                        zout.closeEntry()
                    }
                }
            }
        }
    }
}

/** Fails if app code contains invokedynamic (would crash on ART without D8 desugaring). */
val checkAppBytecode by tasks.registering(JavaExec::class) {
    dependsOn("classes")
    classpath = dx
    mainClass.set("com.android.dx.command.Main")
    val probe = layout.buildDirectory.file("tmp/app-check.dex").get().asFile
    doFirst { probe.parentFile.mkdirs() }
    args("--dex", "--min-sdk-version=24", "--output=${probe.path}",
        layout.buildDirectory.dir("classes/kotlin/main").get().asFile.path,
        layout.buildDirectory.dir("classes/java/main").get().asFile.path)
}

/**
 * Tree-shake app + libraries with ProGuard (no obfuscation, no optimisation). This drops the unused Kotlin stdlib
 * helpers that contain invokedynamic, so the final dex has none, and shrinks the APK.
 */
val shrink by tasks.registering(JavaExec::class) {
    dependsOn(dexInputs, checkAppBytecode)
    classpath = proguard
    mainClass.set("proguard.ProGuard")
    val outJar = layout.buildDirectory.file("shrunk/app.jar").get().asFile
    inputs.dir(layout.buildDirectory.dir("classes/kotlin/main"))
    inputs.dir(layout.buildDirectory.dir("dexin"))
    inputs.file(file("proguard-lite.pro"))
    outputs.file(outJar)
    doFirst {
        outJar.parentFile.mkdirs()
        val injars = listOf(
            layout.buildDirectory.dir("classes/kotlin/main").get().asFile,
            layout.buildDirectory.dir("classes/java/main").get().asFile,
        ).filter { it.exists() } + layout.buildDirectory.dir("dexin").get().asFile.listFiles()!!.sorted()
        args = injars.flatMap { listOf("-injars", it.path) } + listOf(
            "-outjars", outJar.path,
            "-libraryjars", androidJar.path,
            "@" + file("proguard-lite.pro").path,
        )
    }
}

val dexApp by tasks.registering(JavaExec::class) {
    dependsOn(shrink)
    classpath = dx
    mainClass.set("com.android.dx.command.Main")
    maxHeapSize = "2g"
    val dexOut = layout.buildDirectory.file("dex/classes.dex").get().asFile
    inputs.file(layout.buildDirectory.file("shrunk/app.jar"))
    outputs.file(dexOut)
    doFirst {
        dexOut.parentFile.mkdirs()
        // --min-sdk-version=24 makes dx *fail* on any invokedynamic left after shrinking.
        args = listOf("--dex", "--min-sdk-version=24", "--output=${dexOut.path}",
            layout.buildDirectory.file("shrunk/app.jar").get().asFile.path)
    }
}

fun writeAligned(zout: ZipOutputStream, counter: CountingStream, name: String, data: ByteArray) {
    // Every entry STORED with sizes in the local header: no data descriptors, no compression quirks.
    val e = ZipEntry(name)
    e.method = ZipEntry.STORED
    e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
    e.crc = CRC32().apply { update(data) }.value
    // Well-formed alignment extra field (ID 0xD935, as zipalign -p / apksigner use): id, size, alignment, zeros.
    // It appears in both the local header and the central directory, so it must be a valid record there too.
    val align = if (name.endsWith(".so")) 4096 else 4
    val headerEnd = counter.count + 30 + name.toByteArray(Charsets.UTF_8).size
    val pad = ((align - ((headerEnd + 6) % align)) % align).toInt()
    val extra = ByteArray(6 + pad)
    extra[0] = 0x35; extra[1] = 0xD9.toByte()
    extra[2] = (2 + pad).toByte(); extra[3] = ((2 + pad) shr 8).toByte()
    extra[4] = align.toByte(); extra[5] = (align shr 8).toByte()
    e.extra = extra
    zout.putNextEntry(e)
    zout.write(data)
    zout.closeEntry()
}

class CountingStream(private val out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
    var count = 0L
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
}

val packageApk by tasks.registering {
    dependsOn(aapt2Link, dexApp)
    val unsigned = File(outDir, "nitaaq-mirror-unsigned.apk")
    inputs.file(File(resOut, "resources.ap_"))
    inputs.file(layout.buildDirectory.file("dex/classes.dex"))
    outputs.file(unsigned)
    doLast {
        outDir.mkdirs()
        val fos = unsigned.outputStream().buffered()
        val counter = CountingStream(fos)
        ZipOutputStream(counter).use { zout ->
            ZipFile(File(resOut, "resources.ap_")).use { res ->
                // AndroidManifest first, resources.arsc stored+aligned (required for targetSdk >= 30).
                val entries = res.entries().toList().sortedBy { if (it.name == "AndroidManifest.xml") 0 else 1 }
                for (e in entries) {
                    val bytes = res.getInputStream(e).use { it.readBytes() }
                    writeAligned(zout, counter, e.name, bytes)
                }
            }
            writeAligned(zout, counter, "classes.dex", layout.buildDirectory.file("dex/classes.dex").get().asFile.readBytes())
        }
    }
}

/**
 * Signing key: NITAAQ_KEYSTORE (PKCS12) + NITAAQ_KEYSTORE_PASS + NITAAQ_KEY_ALIAS when set (release),
 * otherwise a per-machine debug key in ~/.nitaaq/debug.p12 generated with keytool on first use.
 * No key material is stored in the repository.
 */
val signApk by tasks.registering {
    dependsOn(packageApk)
    val signed = File(outDir, "nitaaq-mirror.apk")
    inputs.file(File(outDir, "nitaaq-mirror-unsigned.apk"))
    outputs.file(signed)
    doLast {
        val envKs = System.getenv("NITAAQ_KEYSTORE")
        val ksFile: File
        val pass: String
        val alias: String
        if (envKs != null) {
            ksFile = file(envKs); pass = System.getenv("NITAAQ_KEYSTORE_PASS") ?: error("NITAAQ_KEYSTORE_PASS missing")
            alias = System.getenv("NITAAQ_KEY_ALIAS") ?: "nitaaq"
        } else {
            ksFile = File(System.getProperty("user.home"), ".nitaaq/debug.p12"); pass = "android"; alias = "nitaaqdebug"
            if (!ksFile.exists()) {
                ksFile.parentFile.mkdirs()
                project.exec {
                    commandLine("keytool", "-genkeypair", "-keystore", ksFile.path, "-storetype", "PKCS12",
                        "-storepass", pass, "-keypass", pass, "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                        "-validity", "10000", "-dname", "CN=NITAAQ Mirror Debug,O=NITAAQ,C=SA")
                }
            }
        }
        val ks = KeyStore.getInstance("PKCS12").apply { FileInputStream(ksFile).use { load(it, pass.toCharArray()) } }
        val key = ks.getKey(alias, pass.toCharArray()) as PrivateKey
        val certs = ks.getCertificateChain(alias).map { it as X509Certificate }
        val cfg = ApkSigner.SignerConfig.Builder("NITAAQ", key, certs).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(File(outDir, "nitaaq-mirror-unsigned.apk"))
            .setOutputApk(signed)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(false) // v2 is what Android 7+ verifies; minSdk is 26
            .setV2SigningEnabled(true)
            .build().sign()
        val result = ApkVerifier.Builder(signed).build().verify()
        check(result.isVerified) { "APK signature verification failed: ${result.errors}" }
        println("Signed APK: ${signed.path}  (v1=${result.isVerifiedUsingV1Scheme}, v2=${result.isVerifiedUsingV2Scheme})")
    }
}

tasks.register("assembleApk") {
    group = "build"
    description = "Builds and signs build/outputs/nitaaq-mirror.apk"
    dependsOn(signApk)
}

/**
 * Sanity check of the produced APK: dex parses (baksmali), app classes are present, and app code has no
 * invoke-custom (only unreachable Kotlin stdlib helpers may contain it).
 */
val verifyApk by tasks.registering(JavaExec::class) {
    dependsOn(signApk)
    classpath = smali
    mainClass.set("org.jf.baksmali.Main")
    val outSmali = layout.buildDirectory.dir("smali").get().asFile
    doFirst { outSmali.deleteRecursively() }
    args("disassemble", "-o", outSmali.path, layout.buildDirectory.file("dex/classes.dex").get().asFile.path)
    doLast {
        val app = File(outSmali, "sa/nitaaq/mirror")
        val files = app.walkTopDown().filter { it.isFile }.toList()
        check(files.size > 50) { "app classes missing from dex" }
        val indy = files.filter { it.readText().contains("invoke-custom") }
        check(indy.isEmpty()) { "invoke-custom in app code: $indy" }
        // Those stdlib methods must stay unreachable from app code.
        val forbidden = listOf("ComparisonsKt;->compareBy([", "ComparisonsKt;->nullsFirst(", "ComparisonsKt;->nullsLast(", "Lkotlin/streams/", "Lkotlin/uuid/")
        val bad = files.filter { f -> val t = f.readText(); forbidden.any { t.contains(it) } }
        check(bad.isEmpty()) { "app code calls stdlib helpers that use invokedynamic: $bad" }
        val anyIndy = outSmali.walkTopDown().filter { it.isFile && it.readText().contains("invoke-custom") }.map { it.relativeTo(outSmali).path }.toList()
        check(anyIndy.isEmpty()) { "invoke-custom in dex: $anyIndy" }
        println("dex OK: ${files.size} app classes, ${outSmali.walkTopDown().count { it.isFile }} classes total, no invoke-custom")
    }
}
