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

## Test with the demo Android app

The `demo` module builds a debug APK. Install it on an Android device, tap **Choose MP4 and
track**, select a video through the system document picker, and wait for processing. The app
samples frames with Android's `MediaMetadataRetriever`, detects the light-blue region in the
first frame, tracks it with the native bounding-box matcher, shows the final annotated frame,
and writes a CSV of `time_ms,x,y,width,height` in the app cache directory.

The APK is built by GitHub Actions and uploaded together with the AAR:

```text
demo/build/outputs/apk/debug/demo-debug.apk
```

The demo intentionally processes frames off the UI thread and requires no storage permission;
the Android document picker grants read access to the selected MP4.

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
