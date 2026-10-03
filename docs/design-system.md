# Android visual direction

User requirement: modern, beautiful, refined UI is a release gate, not optional polish.

## Subject and signature

A personal file and film library. Let actual covers and filenames lead. The signature is a disciplined media row: a large proportional cover, readable two-line title, quiet technical metadata, and a precise resume marker. File navigation remains fast and calm.

Do not use generic dashboard statistics, oversized gradients, repeated card containers, fake media, or development terminology in product flows. Native Material components are primitives, not the finished visual design.

## Tokens

| Role | Light | Dark |
| --- | --- | --- |
| Canvas | #F5F7FA | #10141B |
| Surface | #FFFFFF | #1B2230 |
| Primary text | #172235 | #EEF3FA |
| Secondary text | #536176 | #AAB8CE |
| Accent | #235ED7 | #9AB9FF |
| Divider | #DCE3EE | #344157 |

Native sans family with deliberate roles: 28sp semibold page title, 20sp medium section title, 16sp medium file title/body, 13sp metadata; tabular numerals for times and sizes. Respect system font scale. Normal text contrast ≥4.5:1.

Spacing: 4/8/12/16/24/32dp. Phone page gutters 20dp; list items 16dp internal spacing. Corners: 12dp on media, 16dp on forms, restrained pill shapes for status only. Outline icons form a consistent family; controls ≥48dp.

## Layout

Files: compact server switch → prominent directory name/breadcrumb → search/sort controls → content. Folders are quiet rows; videos receive covers. Grid cell size responds to width, retains readable titles and never stretches previews.

Recent: real resume entries, no fake hero. Server editor: a focused sheet/form with field examples, a visible connection mode and one primary action. Settings: grouped rows with clear current values.

Player: black video stage, unambiguous source title, restrained floating controls. Subtitles keep their own visible area; timelines and track sheets have space to breathe. Buffers/failures use inline feedback, not stacked toast overlays.

Motion: 160–220ms focus/navigation transitions; respects system animation scale. No entrance spectacle. Light/dark, empty/error/loading/buffering and large-font layouts receive equal scrutiny.

Connection implementation: compact media-library introduction, two accessible network choices, server-address group, service-account group and one primary connection action. At ≥840dp it uses a quiet introductory column beside the form; larger font scales retain the scrolling single-column form. Surface roles and their foreground colors are explicitly defined to keep the slate/blue palette throughout native components. Official Material outline vectors are pinned and attributed under `licenses/`.

## Visual gate

Inspect actual rendered Android screenshots for each main page, both themes, compact/landscape/≥600dp at default font scale. The user explicitly excluded further 130%/200% checks; historical enlarged-font results below remain historical evidence, not a requirement to repeat them. Capture controls visible/hidden, long filenames, all errors and track sheets. Fix clipping, weak hierarchy, density and inconsistent spacing before release. Emulator images validate UI only; hardware decoding/HDR needs devices.

## Player revision: Bilibili / Artplayer direction

The user rejected the prototype's large default buttons, thick blue footer and unused portrait space. Keep Compose as the native rendering/interaction toolkit; Material's default component styling is not a product design requirement. Official Compose custom-design-system guidance supports independent colors, typography and components. Traditional Views can be embedded when a proven component fits; switching rendering frameworks alone does not improve hierarchy or spacing.

Primary design reference: https://artplayer.org/document/ and the MIT-licensed Artplayer source pinned in `licenses/artplayer.md`. Reuse its actual play/pause/volume/fullscreen/check/close vectors, compact control composition, thin progress and accent/check selection. Reuse Compose's tested slider/dialog/selectable interaction and accessibility instead of introducing a custom gesture/keyboard framework. Artplayer itself is a JavaScript/HTML5 player, not a Kotlin UI library; do not replace native libVLC to import its DOM.

BiliPai is a third-party Compose video app, not official Bilibili source. Its player UI contains application-specific account, danmaku, video-quality and Media3 integration. It is a reference, not a drop-in independent libVLC controller. General component libraries such as Miuix are also not complete video-control systems; no blanket UI-framework migration is needed for this slice. No claim is made about current proprietary Bilibili/YouTube/iQiyi/Tencent implementation details.

Portrait: proportional video at the top, compact transport, then complete filename, quiet server identity, real playback metadata and track rows. Landscape: large native viewport, gradient title, thin timeline and a single compact transport/options row. The native subtitle viewport reserves the transport area so PGS cannot sit under controls; viewport size stays stable on idle HUD hide.

Player palette: canvas #141416, panel #202023, white controls, secondary #B5B5BE and one pink accent #FF80A6. Use 22dp icons inside 48dp targets, a 3dp timeline with a 10dp thumb inside a 48dp interaction region. Selection uses a subtle accent fill and right-side check, without oversized radio controls or a Material drag handle. Portrait menus emerge from the bottom; landscape menus occupy a bounded right panel. Preserve real state, IDs, duration, accessible slider semantics and reduced interaction hiding under TalkBack.

This is an implemented direction, not visual acceptance. Actual screenshots, rotation, control visibility, native picture, track interaction, large-font clipping and foreground recovery must be checked before release.

## Search page

Search follows the player's restrained pink direction with a white light canvas and charcoal dark canvas. It inherits the app theme rather than independently forcing system color mode. Accent: #C63262 / #FF80A6; secondary text: #63636D / #B5B5BE. Measured text-on-canvas contrast: 5.21/7.79 for accent and 5.94/9.04 for secondary text. Native sans uses the existing 16sp body/file title and 13sp metadata roles. The result title remains two lines with full name/location in a scrollable long-press detail dialog.

Use a compact BasicTextField/IME search action, 48dp clear/back targets, flat underlined scope controls and a quiet result row. Header context/status/details belong to the scrolling results area so landscape and 200% fonts remain usable. Result status reflects actual emitted rows and termination; no invented popular keywords, covers or traversal percentage. Reuse the pinned Material folder vector and existing Artplayer close icon, with original licenses; Compose input/selection/dialog/lazy-list primitives supply native editing, focus and accessibility actions. File-page search/up/refresh actions wrap at narrow/large-font widths.

Owned API35 screenshots and actual control tests cover 100% light, 130% light and 200% dark, portrait/landscape and long-file-name detail scrolling. These are fixture visuals, not user aesthetic approval, all light/dark/font combinations, a ≥600dp device, physical TalkBack or the whole app's visual acceptance.

Video layout correction: libVLC 3.7.6 `VideoHelper.updateVideoSurfaces` otherwise considers Activity orientation and swaps a wide embedded viewport in a portrait activity. `MediaPlayer.setUseOrientationFromBounds(true)` is used so aspect fitting follows the real native viewport. The owned 16:9 fixture test requires both decoded PixelCopy content and video Surface width fitting that viewport.

Fullscreen correction: landscape no longer reserves the portrait transport strip. Native video/subtitle surfaces use the full stage height; controls overlay the picture and hide without resizing it. Aspect fitting retains natural letterboxing. The actual owned landscape fixture must occupy at least 95% of window height. Subtitle/control overlap, large fonts, insets and physical-device coverage remain part of final player acceptance.

Next preview priorities from the user's installed first-preview feedback: compact file header/navigation, aligned dense rows, switchable responsive grid and authenticated real image/video thumbnails. Those features are not completed by the fullscreen fix and must be validated independently before the next UI preview release. Formal hardware/media/network gates remain mandatory for the final release.

File browser implementation: compact server/path/action rows replace stacked large headings; a 64dp-minimum list and adaptive 152dp-minimum grid share two-line names and long-press selectable details. Grid title slots use two scaled text line heights so metadata aligns regardless of wrapping. View-mode buttons stay paired during wrapping. White/charcoal surfaces and pink accents match the search/player direction. Current artwork is explicitly a type placeholder; authenticated thumbnails and durable view preferences are subsequent slices. Actual phone/API35 coverage includes normal list occupancy >=60% of screen height, grid/details/navigation/search regression, 130% light and 200% dark. File landscape, >=600dp, complete theme/font combinations and physical TalkBack remain open.


## Recent playback cards

Recent playback reuses FileEntry's detail-card composition and the existing authenticated NAS thumbnail/Coil capability path. Canvas, 10dp cards, 8dp spacing, 12dp content gutters, two-line titles and 16sp file names match the library. The header uses the normal 20sp title role. Long press reuses the same selectable full-name/path dialog as files; no unknown file size is fabricated.

Each card shows its stored source position, known full duration and real synchronization status. Unknown duration is labelled pending confirmation and has no progress bar. A changed file labels the stored time as an old record and has no bar presented as current progress. Opening still uses the existing identity/progress verification; no new playback API or implicit conversion is added. Empty history offers actual file navigation. Account-scoped history and preview capabilities remain bound to the current session.

The thin progress bar retains Material 3's existing implementation and semantics, with its [documented color, gap and stop-indicator parameters](https://developer.android.com/reference/kotlin/androidx/compose/material3/LinearProgressIndicator.composable) set to the library's pink/neutral continuous styling. This does not create another canvas-based progress component.

TX5Pro debug build/test-APK/lint gates passed; the local unit task has no sources. Nine installed API35 tests passed in 95.142s (recent cards, three native preview gates, browser, three search interactions and theme recreation). After the progress styling correction, the affected recent-card gate passed again in 17.770s. Actual Room history drives these cards; generated owned PNG bytes pass authenticated JNI/HTTP into Coil and visible compositor pixels. Light/dark cards, details and account-isolated empty state were captured at default font scale. The real open action reaches the existing playback identity request and cancellation path. These are owned-fixture/emulator checks, not actual NAS media-thumbnail, physical TalkBack, hardware/HDR or signed-release acceptance. No enlarged-font checks were performed. Public 0.2.1-preview is unchanged; rollback source for this UI slice is `b7c876f`.
