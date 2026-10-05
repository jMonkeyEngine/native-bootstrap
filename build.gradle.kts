import io.github.danielliu1123.deployer.PublishingType
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    base
    id("com.diffplug.spotless") version "8.10.3"
    id("io.github.danielliu1123.deployer") version "0.5.2"
}

spotless {
    java {
        target("**/src/**/*.java")
        eclipse().configFile(rootProject.file(".vscode/JME_style.xml"))
        trimTrailingWhitespace()
    }
}

val releaseVersion = providers.gradleProperty("releaseVersion").orNull
allprojects {
    group = "org.jmonkeyengine"
    version = releaseVersion ?: rootProject.providers.gradleProperty("version").get()
    repositories { mavenCentral() }
}
val signingKey = providers.environmentVariable("SIGNING_KEY")
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orElse("")
val centralUsername = providers.environmentVariable("CENTRAL_USERNAME")
val centralPassword = providers.environmentVariable("CENTRAL_PASSWORD")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        withSourcesJar()
        withJavadocJar()
    }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
    tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
    val testRuntimeClasspath = extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    val testJavaVersion = providers.gradleProperty("testJavaVersion").orElse("8").get().toInt()
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        doFirst {
            systemProperty("test.classpath", testRuntimeClasspath.asPath)
        }
        javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
            // Zulu supplies a native Java 8 runtime on both Intel and Apple Silicon macOS.
            if (testJavaVersion == 8) vendor.set(JvmVendorSpec.AZUL)
        })
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "mavenCentralSnapshots"
                url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                credentials {
                    username = centralUsername.orNull
                    password = centralPassword.orNull
                }
            }
            maven { name = "staging"; url = layout.buildDirectory.dir("repo").get().asFile.toURI() }
        }
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set("jMonkeyEngine ${project.name}")
                    description.set(provider { project.description })
                    url.set("https://github.com/jMonkeyEngine/native-bootstrap")
                    licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                    developers { developer {
                        id.set("riccardobl"); name.set("Riccardo Balbo"); email.set("os@rblb.it")
                    } }
                    scm {
                        url.set("https://github.com/jMonkeyEngine/native-bootstrap")
                        connection.set("scm:git:https://github.com/jMonkeyEngine/native-bootstrap.git")
                        developerConnection.set("scm:git:git@github.com:jMonkeyEngine/native-bootstrap.git")
                    }
                }
            }
        }
    }
    extensions.configure<SigningExtension> {
        isRequired = signingKey.isPresent || !version.toString().endsWith("-SNAPSHOT")
        if (signingKey.isPresent) useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(project.extensions.getByType<PublishingExtension>().publications)
    }
    val clearStaging by tasks.registering(Delete::class) { delete(layout.buildDirectory.dir("repo")) }
    tasks.withType<PublishToMavenRepository>().configureEach {
        if (repository.name == "staging") dependsOn(clearStaging)
        if (repository.name == "mavenCentralSnapshots") doFirst {
            check(version.toString().endsWith("-SNAPSHOT")) { "Snapshot repository requires a SNAPSHOT version" }
        }
    }
}

deploy {
    dirs = provider { subprojects.map { it.layout.buildDirectory.dir("repo").get().asFile } }
    username = centralUsername
    password = centralPassword
    publishingType = PublishingType.WAIT_FOR_PUBLISHED
}
tasks.deploy {
    dependsOn(subprojects.map { "${it.path}:publishMavenPublicationToStagingRepository" })
    doFirst { check(!version.toString().endsWith("-SNAPSHOT")) { "Use publishSnapshots for snapshot versions" } }
}
tasks.register("publishSnapshots") {
    group = "publishing"
    dependsOn(subprojects.map { "${it.path}:publishMavenPublicationToMavenCentralSnapshotsRepository" })
}
tasks.register("mavenCentralDeploy") {
    group = "publishing"
    dependsOn(if (version.toString().endsWith("-SNAPSHOT")) "publishSnapshots" else "deploy")
}
tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn("spotlessCheck")
}
tasks.named("assemble") { dependsOn(subprojects.map { "${it.path}:assemble" }) }

tasks.register("format") {
    group = "formatting"
    description = "Formats all Java sources with the JME Eclipse formatter"
    dependsOn("spotlessApply")
}

tasks.register("stageMavenArtifacts") {
    group = "publishing"
    description = "Stages all module publications locally without uploading"
    dependsOn(subprojects.map { "${it.path}:publishMavenPublicationToStagingRepository" })
}
