plugins { `java-library` }
description = "Lazy private extraction directories for jMonkeyEngine native libraries"
dependencies {
    "api"(project(":native-common"))
    "testImplementation"(project(":native-os"))
}
