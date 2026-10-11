# Modifications

This fork is based on **[FossifyOrg/Camera](https://github.com/FossifyOrg/Camera)** version
**1.5.0** (`versionCode 11`, released 2026-01-30, commit of the `main` branch at that version).

Everything below is what this fork changes on top of that upstream release. Nothing else was
touched. The project stays under the **GNU General Public License v3.0** (see [LICENSE](LICENSE));
the upstream copyright notices are kept.

---

## 1. Timestamp watermark on photos (new feature)

### `helpers/WatermarkUtils.kt` — new file

Draws `yyyy-MM-dd HH:mm:ss` into the bottom right corner of a captured JPEG.

- White, bold text with a black outline plus a soft drop shadow, so it stays readable on both
  very bright and very dark photos. A plain shadow alone is not enough on a white photo.
- Text size and margin are a ratio of the image width, so any resolution scales proportionally.
- The photo is rotated upright using the EXIF orientation, falling back to
  `ImageInfo.rotationDegrees` when the JPEG carries no orientation tag (which happens whenever
  the image was re-encoded, e.g. while cropping). Only one of the two sources is used, so the
  photo can never be rotated twice.
- If decoding fails or runs out of memory, the original bytes are returned unchanged so a
  capture is never lost.

### `helpers/ImageSaver.kt`

- Calls `WatermarkUtils.addTimestampWatermark()` before writing the temp file.
- After baking the rotation into the pixels, the EXIF orientation is reset to `ORIENTATION_NORMAL`
  so viewers do not rotate the image a second time. When the watermark is disabled the pixels are
  untouched and the original orientation is preserved instead.
- The now unused `ExifRotationAvailability` workaround was removed; the rotation decision lives in
  `WatermarkUtils` where the actual JPEG bytes are available.

### Settings toggle

- `helpers/Config.kt`, `helpers/Constants.kt` — new `addTimestampWatermark` preference
  (default `true`).
- `res/layout/activity_settings.xml`, `activities/SettingsActivity.kt` — new switch
  "Add date and time watermark to photos" in the *Saving* section.
- `res/values/strings.xml`, `res/values-zh-rCN/strings.xml` — the new string.

### `AndroidManifest.xml`

- `android:largeHeap="true"`, because the watermark decodes the full resolution image.

---

## 2. Photo/video mode switch no longer restarts the camera (performance fix)

`implementations/CameraXPreview.kt`

**Problem.** Switching between photo and video mode called `unbindAll()` and rebound, which makes
CameraX close and reopen the camera device. Measured on a vivo X90 (Android 16) with the `CXCP`
log tag:

```
Closing Camera 0        <- camera closed
+236 ms
Opening CameraId-0
Opened CameraId-0 in 39.9 ms
Configured CaptureSessionState in 22.9 ms
```

About **370 ms** of frozen preview on every switch.

**Fix.** `Preview`, `ImageCapture` and `VideoCapture` are bound together in one
`UseCaseGroup`, so a mode switch only changes which capture use case is used and never touches
the camera. If the device rejects the combination, the bind falls back to the previous
per-mode behaviour. On the test device the combined bind is accepted, and the switch now costs
about 12-20 ms of UI work with **zero** camera close/open events.

The crop for the "full screen" photo option is applied with `ImageCapture.setCropAspectRatio()`
instead of a `ViewPort`. A `ViewPort` crops **every** use case of its group, which silently cut
the video down to the screen aspect ratio (2160x3840 became 1728x3840).

---

## 3. Capture orientation when the device is held in landscape (bug fix)

`implementations/CameraXPreview.kt`

The activity is locked to portrait, so `previewView.display.rotation` is always `ROTATION_0` and
was useless for orienting the capture. A photo taken while holding the phone in landscape was
therefore saved in portrait whenever the camera was rebuilt (mode switch, camera switch,
returning to the app). The rotation tracked by the existing `OrientationEventListener` is used
instead.

---

## 4. Changing the photo aspect ratio no longer restarts the camera (performance fix)

`implementations/CameraXPreview.kt`, `helpers/ImageQualityManager.kt`

**Problem.** Picking another aspect ratio called `startCamera()`, which produced the same ~341 ms
camera close/reopen as in section 2.

**Fix.**

- `ImageQualityManager.getMaxPhotoResolution()` is new. The still capture is always bound at the
  largest supported resolution, so every aspect ratio can be produced by cropping alone.
- `applyPhotoAspectRatio()` maps the selected entry to a crop rectangle, expressed in the current
  display orientation (a portrait display needs the taller ratio). It is re-applied when the
  device turns.
- `onResolutionChanged()` replaces the `startCamera()` call for photo aspect changes. Video
  qualities still select a different recording resolution and keep the full rebind.
- The crop is applied **after** binding on purpose: CameraX sizes the still capture stream from
  the crop aspect ratio present while binding, so setting a narrow aspect first would make it
  allocate a smaller stream for every later aspect ratio.

`CameraXPreviewListener.adjustPreviewView()` gained a `displayAspectRatio` parameter and
`MainActivity` shapes the `PreviewView` with it, so the viewfinder matches the region that is
actually captured.

**Measured on the test device:** 10 consecutive aspect switches, 9 applied, **0** camera
close/open events, **0** rebinds, no crashes.

**Trade-off.** With three concurrent streams (preview + JPEG + UHD video) the sensor caps the
still capture at 3264x2448 (8 MP); a still capture bound on its own reaches 4096x3072 (12.6 MP),
but that brings the ~340 ms black screen back. Resulting photo sizes on the test device:

| Option | Size |
| --- | --- |
| Full screen | 1469x3264 |
| 16:9 | 1836x3264 |
| 4:3 | 2448x3264 |
| 1:1 | 2448x2448 |

---

## 5. Smaller APK (build configuration)

`app/build.gradle.kts`

- `androidResources.localeFilters` keeps only `en`, `zh-rCN`, `zh-rTW` and `zh-rHK` out of the
  124 locales upstream ships. `resources.arsc` drops from 2,491,136 to 767,156 bytes and the APK
  from 9,480,183 to 7,753,763 bytes (-18.2 %). Note that the qualifiers must be the exact resource
  ones: there is no `values-zh`, so a plain `"zh"` would silently drop all Chinese resources.
- The release signing config enables the v1, v2 and v3 signature schemes. AGP emits a v2-only
  APK for `minSdk 29`, which some device installers reject with "problem parsing the package".
- Test dependencies (JUnit, Robolectric), `testOptions.unitTests.isIncludeAndroidResources` and
  the Robolectric runtime repository for the tests below.

`gradle/libs.versions.toml` — versions for the two test dependencies.
`settings.gradle.kts` — Aliyun Maven mirrors are listed before the official repositories, because
Maven Central and `services.gradle.org` stall on some networks. Remove them if you do not need
them.

## 6. Tests

`app/src/test/kotlin/org/fossify/camera/helpers/WatermarkUtilsTest.kt` — new file.
Five Robolectric tests that run the real `WatermarkUtils` with native graphics and assert the
rendered pixels: rotation by EXIF orientation, the `rotationDegrees` fallback, an upright photo,
legibility on white and black backgrounds, and that undecodable input is passed through.

```
./gradlew :app:testFossDebugUnitTest
```

## 7. Debug logging

`CameraXPreview.kt`, `ImageQualityManager.kt`, `VideoQualityManager.kt`, `MainActivity.kt` and
`WatermarkUtils.kt` contain timing `Log.i` calls tagged `CameraModeSwitch` and `WatermarkUtils`.
They are informational only and are removed from release builds by R8, so they cost nothing in a
published APK. Note that some ROMs (the vivo one used for testing sets `log.tag=I`) drop
`Log.d` entirely, which is why the info level is used.

---

## Not changed

Camera quality settings, resolutions, frame rates, autofocus, exposure, video recording, EXIF
handling and every other upstream feature are untouched.
