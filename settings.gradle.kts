plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
rootProject.name = "native-bootstrap"
include("native-common", "native-os", "native-directories", "native-loader")
