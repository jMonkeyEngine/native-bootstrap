import java.util.Locale

plugins { `java-library` }
description = "jMonkeyEngine native library extraction and loading with ordered directory fallback"
dependencies {
    "api"(project(":native-directories"))
    "testImplementation"(project(":native-os"))
}

val fixtureDir = layout.buildDirectory.dir("generated/testNative")
val hostOs = System.getProperty("os.name").lowercase(Locale.ROOT)
val windows = hostOs.startsWith("win")
val mac = hostOs.contains("mac") || hostOs.contains("darwin")
val fixtureName = System.mapLibraryName("nativebootstrap_probe")
val compiler = javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
val compileTestNative by tasks.registering(Exec::class) {
    inputs.file("src/test/native/probe.c")
    outputs.file(fixtureDir.map { it.file("native/$fixtureName") })
    doFirst {
        val output = fixtureDir.get().dir("native").asFile
        output.mkdirs()
        val includes = compiler.get().metadata.installationPath.dir("include").asFile
        val source = file("src/test/native/probe.c").absolutePath
        workingDir(output)
        if (windows) commandLine("cl", "/nologo", "/LD", "/I$includes", "/I${includes.resolve("win32")}", source,
            "/Fe:${output.resolve(fixtureName)}")
        else commandLine("cc", if (mac) "-dynamiclib" else "-shared", "-fPIC", "-I$includes",
            "-I${includes.resolve(if (mac) "darwin" else "linux")}", source, "-o", output.resolve(fixtureName))
    }
}
sourceSets.test { resources.srcDir(fixtureDir) }
tasks.processTestResources { dependsOn(compileTestNative); include("**/*.dll", "**/*.so", "**/*.dylib", "**/*.bin") }
