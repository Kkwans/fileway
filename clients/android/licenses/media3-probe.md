# Android native media dependencies

Media3 1.11.1, the FFmpeg audio extension and ass-kt 0.5.1 are production runtime
dependencies. The historical `probe` filenames remain part of the pinned native
build contract; they do not indicate a second product player or a test-only engine.
libVLC remains an instrumentation reference dependency, excluded from the app APK.

- Media3: Android Open Source Project, Apache-2.0;
  <https://github.com/androidx/media/tree/1.11.1>. Its exact license is bundled by
  the FFmpeg extension AAR as `assets/media3-APACHE-2.0.txt`.
- FFmpeg 9.0.2: LGPL-2.1-or-later, with GPL/version3/nonfree components disabled.
  The extension is audio-only. `native/media3-ffmpeg.lock.json` pins its input
  sources; `scripts/prepare-ffmpeg-probe.py` and `package-ffmpeg-probe.py` preserve
  the recipe, configuration, licenses and corresponding source archive. Each
  public preview distributing it includes `fileway-media3-ffmpeg-sources.tar.gz`.
- ass-kt/ass 0.5.1: MIT;
  <https://github.com/peerless2012/libass-android/tree/504f64a325059ab5188c4c9d0eddc99c9356a23a>.
  The native wrapper also statically links libass, FriBidi, FreeType, HarfBuzz,
  Fontconfig, Expat and libunibreak; the wrapper's MIT license alone does not cover
  these dependencies. Their exact upstream notices are packaged in
  `assets/licenses/libass-native-NOTICES.txt`. FreeType uses FTL; FriBidi uses
  LGPL-2.1-or-later. Preview releases include `fileway-libass-0.5.1-sources.tar.gz`
  with the wrapper, CMake recipe, all pinned native sources and placement instructions.

The libass native recipe is pinned at
<https://github.com/peerless2012/libass-cmake/tree/d3f00a43ca66e42a2c34de964b1a7dbbfa9dbc8b>.
Its recorded dependency commits are:

| Dependency | Commit |
| --- | --- |
| libass | bbb3c7f1570a4a021e52683f3fbdf74fe492ae84 |
| Expat | f9a3eeb3e09fbea04b1c451ffc422ab2f1e45744 |
| Fontconfig | daa175d234b8a362eedd4c18c33537cc2d19cd98 |
| FreeType | 42608f77f20749dd6ddc9e0536788eaad70ea4b5 |
| FriBidi | 68162babff4f39c4e2dc164a5e825af93bda9983 |
| HarfBuzz | c3fcbffa651cea70400552f2a8bd695ad11023c1 |
| libunibreak | 304585d8e2d63187507368d612c3d5fff1486368 |

Fileway Android's GPLv3 source and build scripts remain available at the preview's
exact repository SHA. Native dependency archives accompany the APK instead of
requiring a user to infer corresponding sources from unrelated upstream HEADs.
