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

## Visual gate

Inspect actual rendered Android screenshots for each main page, both themes, compact/landscape/≥600dp and 100/130/200% font scale. Capture controls visible/hidden, long filenames, all errors and track sheets. Fix clipping, weak hierarchy, density and inconsistent spacing before release. Emulator images validate UI only; hardware decoding/HDR needs devices.
