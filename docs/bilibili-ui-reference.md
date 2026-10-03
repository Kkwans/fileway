# Android UI rebuild from supplied official screenshots

The user's three official Bilibili Android screenshots supplied on 2026-10-03
are the primary visual targets. They supersede the rejected 29122b2 layout.
Original screenshots stay in private evidence; they are not app assets or public
repository content. The current implementation is not visually accepted.

## Page and layout contract

| Target | NAS client implementation |
| --- | --- |
| Screenshot 1, home | Default cover grid: two columns on phones, approximately 4:3 cropped covers, shallow corners, white cards on a quiet grey canvas, two readable title lines and compact metadata. Large directory placeholders become compact navigation entries. |
| Screenshot 2, search | Large-image detailed list: landscape preview on the left, title and genuine file/source metadata on the right. Search uses the same card primitive; its existing incremental/cancel/retry semantics remain intact. |
| Screenshot 3, personal page | Settings: current server/account header, working shortcuts and grouped settings for appearance, subtitles/playback, cache and embedded networking. |
| Existing regular list | Dense file rows with small type/preview icon, two-line name, size/type and details action. |
| NAS web compact grid | Smaller file tiles with responsive columns; directory/file icons and readable labels, without large media-cover reservations. |

Expose four explicit modes: 封面网格 / 大图列表 / 常规列表 / 紧凑网格.
Keep the mode selector inside one layout menu. The top bar combines server
switching, rounded search entry and small actions; directory breadcrumbs sit
below it. Use icon-and-label navigation for 文件 / 最近 / 设置.

Reference hierarchy: white/charcoal canvas, pink selected state, neutral outline
icons, restrained card corners, tight gutters and clear cover/title/metadata
spacing. Retain 48dp action targets and scaled text. Measure actual text layout
for two-line titles rather than guessing fixed heights from sp conversions.
Status bars and navigation gestures remain system-owned.

## Source reuse

- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus), inspected commit
  `334d758127c8` (full SHA retained in private reference provenance): GPL-3.0,
  `lib/common/widgets/video_card/video_card_v.dart` and `video_card_h.dart`.
  Reuse cover/caption/badge composition and horizontal-card structure as
  reference when translating into the existing native Compose app.
- [bilimiao](https://github.com/10miaomiao/bilimiao2), inspected commit
  `0d96e13dc4a8`: GPL-3.0, native Compose `VideoItemBox.kt` and
  `MiniVideoItemBox.kt`. These provide practical native cover/text layouts.
- [BiliPai](https://github.com/jay3-yy/BiliPai) is another native Compose
  reference. Direct GitHub API reads of current main README and LICENSE both
  confirm GPLv3; older indexed README/fork licensing text is not authoritative.
  Pin and inspect each selected native card component before porting it.

Record original copyright/license when porting source. Use app-owned icons and
NAS media, not Bilibili branding, screenshots, user avatars or feed content.
No framework/player/network replacement is required for this UI rebuild.

## Real covers are part of the implementation

Reuse NAS `/api/preview/thumb/{path}` through an immutable session-bound
localhost asset lease. Keep original wire encoding, base paths, X-Auth renewal,
streaming and cancellation. The image loader receives a token-free capability
URL; it never receives a source JWT. Close/revoke leases on disposal or source
switch. Preview generation is recorded separately from native raw playback.

Cover cache identity must include server/account and validated file identity.
Unknown duration/resolution is omitted rather than invented. Missing previews
use a small consistent unavailable/loading state, not a fake image or a large
extension-labelled cover. Actual authenticated cover loading and late responses
during source changes require integration tests.

## Completion evidence

Inspect installed native pages against the supplied screenshot proportions:
all four file modes, shared search card, settings and player controls. Include
actual covers, long names, portrait/landscape, light/dark, 100/130/200% text,
large screens, loading/error/empty and navigation. Check title clipping,
metadata alignment, visible content density and thumbnail identity/cancellation.
Code gates and static type placeholders do not constitute visual acceptance.

Deliver this UI in independently tested/pushed slices. The next preview includes
the rebuilt browser with actual previews and full-screen correction; final
release still requires the original hardware/media/network/upgrade gates.

## User revision: grouped rounded cards

The2026-10-03 follow-up explicitly requires restrained rounded cards for every
file and one settings card per category, with one action per row. This overrides
the earlier borderless regular/detail rows and flat settings sections. Reuse
Material3 Surface/AlertDialog;10dp card corners,8dp file gutters and neutral
canvas,without heavy shadows. Preserve four layouts and native functionality.
