# SHDMotion

SHDMotion is an offline Android native library for classical video-editing computer vision:

- Sparse Lucas-Kanade point tracking
- Pixel-matching bounding-box tracking
- Affine motion estimation with moving-average stabilization
- Optical-flow deformation tracking for rotoscoping polygons

## Use the latest `main` commit with JitPack

JitPack builds public GitHub repositories without requiring a manually downloaded AAR. Add JitPack to the consuming Android project:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then use the branch snapshot:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.satyabratadey10-a11y:SHDMotion:main-SNAPSHOT")
}
```

`main-SNAPSHOT` tracks the latest commit on `main`. After pushing a change, refresh dependencies or run:

```bash
./gradlew --refresh-dependencies
```

## Use a reproducible release

For an immutable dependency, use a Git tag such as `v1.0.0`:

```kotlin
implementation("com.github.satyabratadey10-a11y:SHDMotion:v1.0.0")
```

Release tags are recommended for production builds; `main-SNAPSHOT` is intended for development.

## Direct-buffer API

The Kotlin API accepts direct `java.nio.ByteBuffer` instances. Frames must use the declared stride and pixel format:

```kotlin
val points = MotionEngine.initializeTrackingPoints(
    frame = rgbaDirectBuffer,
    width = 1920,
    height = 1080,
    stride = 1920 * 4
)
```

Supported formats are `RGBA`, `GRAY`, and `YUV420` (the native implementation samples the luminance plane for `YUV420`).
