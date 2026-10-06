# Native Bootstrap

Helpers for native libraries extraction and loading in Java 8+

## Usage

- `native-common`: only the `OperatingSystem` enum, with no module dependencies.
- `native-os`: optional JVM detector; depends only on `native-common`.
- `native-directories`: directory suppliers; depends only on `native-common`.
- `native-loader`: extraction and loading; depends on `native-directories`.

```kotlin
// Optional detector; omit when adapting your own platform detection.
implementation("org.jmonkeyengine:native-os:0.1.0-SNAPSHOT")

// Directory selection only; no loader or detector dependency.
implementation("org.jmonkeyengine:native-directories:0.1.0-SNAPSHOT")

// Includes native-directories transitively.
implementation("org.jmonkeyengine:native-loader:0.1.0-SNAPSHOT")
```

## Directory suppliers

```java
import com.jme3.nativebootstrap.directories.NativeDirectories;
import com.jme3.nativebootstrap.directories.DirectoryCandidate;
import com.jme3.nativebootstrap.os.OperatingSystems;
import java.nio.file.Path;
import java.util.List;

List<DirectoryCandidate> candidates = NativeDirectories.candidates(".my-app", "my-library", OperatingSystems::detect);
Path directory = candidates.get(0).get();
```


## System properties overrides

Can be used to override the default behavior

| Property | Description |
| --- | --- |
| `natives.tempDir` | Temporary directory for native extraction |
| `natives.userHome` | User home directory for native extraction |
| `natives.cacheDir` | Cache directory for native extraction  |
| `natives.namespace` | Namespace for native extraction within the home directory |
| `natives.noAdditionalChecks` | Disables external permission helpers |
| `natives.preferOsLibraries` | Tries `System.loadLibrary` before classpath extraction (default `false`) |
| `natives.withOsLibraries` | Enables OS library fallback for missing resources, extraction failures and load failures (default `true`); `false` also disables `preferOsLibraries` |



## Native loading

```java
import com.jme3.nativebootstrap.loader.NativeLoader;
import com.jme3.nativebootstrap.loader.NativeResource;
import java.util.Arrays;

Path loadedFrom = new NativeLoader(path -> System.load(path.toString())).load(
    NativeDirectories.candidates("my-library", OperatingSystems::detect),
    Arrays.asList(
        NativeResource.fromClasspath(MyBindings.class, "/native/" + System.mapLibraryName("dependency")),
        NativeResource.fromClasspath(MyBindings.class, "/native/" + System.mapLibraryName("bindings"))
    )
);
```