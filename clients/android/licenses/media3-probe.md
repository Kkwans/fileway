# Media3 comparison dependency

The instrumentation APK uses `androidx.media3:media3-exoplayer:1.11.1` and its
Media3 transitive modules. They are not product debug/release runtime dependencies.
Source and license: https://github.com/androidx/media/tree/1.11.1 .
Copyright The Android Open Source Project; Apache-2.0.

The exact upstream root `LICENSE` is packaged in the test APK as
`assets/licenses/media3-APACHE-2.0.txt`; SHA256
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`.
The pinned upstream root has no separate NOTICE file.

This baseline includes no FFmpeg or libass extension and makes no claim about
their eventual binary license/dependency obligations. Audit those separately
when building the enhanced comparison candidate.
