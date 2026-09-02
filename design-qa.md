# Modern Dark Android UI — Design QA

## Evidence

- Source visual truth: `/home/kylin1993/sources/codex-remote-android/docs/design/modern-dark-reference.png`
- Implementation screenshot: `/home/kylin1993/sources/codex-remote-android/docs/design/modern-dark-implemented.png`
- Source pixel dimensions: `853 × 1844`
- Implementation pixel dimensions: `1080 × 2400`
- Target device: Android `1080 × 2400 @ 420 dpi`
- Intended logical viewport: approximately `390 × 844`
- Source aspect ratio maps closely to `390 × 843`; the native Android capture includes system chrome, so its exact dp conversion is only approximate.
- Capture density:
  - Source: generated design raster; no CSS `deviceScaleFactor`.
  - Implementation: native Android physical-pixel screenshot; no browser `deviceScaleFactor`.
- State:
  - Both show the connected conversation screen, “会话” selected, one completed user request, completed process items, an assistant response, and the composer.
  - Source composer is empty; implementation composer contains a draft.
  - Source response body is schematic placeholder content; implementation contains real Markdown.
  - Source process items are collapsed inside one process group; implementation uses separate cards and leaves file-change summary content exposed.
  - Literal dynamic content differences were not treated as fidelity defects.

## Density Normalization

- Source resized proportionally from `853 × 1844` to `1080 × 2335`, then trimmed by 25 px at the bottom.
- Implementation cropped from `(0, 60)` through `(1080, 2370)`, removing approximately 60 px of status bar and the bottommost 30 px capture strip.
- Each normalized image is `1080 × 2310`.
- The Android gesture indicator remains visible because the composer is laid out behind the navigation inset; this is evaluated separately as a layout issue rather than mistaken for source artwork.
- Full comparison: `/tmp/codex-remote-design-qa/full-comparison.png`
- Full comparison dimensions: `2184 × 2374`, including a 24 px separator and labels.
- Focused comparisons:
  - `/tmp/codex-remote-design-qa/topbar-tabs-comparison.png`
  - `/tmp/codex-remote-design-qa/timeline-process-comparison.png`
  - `/tmp/codex-remote-design-qa/message-composer-comparison.png`
- All three focused comparisons and the full comparison were opened and visually inspected as true side-by-side combined images.

## Findings

- [P1] Process timeline has the wrong grouping and information density
  - Location: conversation timeline; `ConversationHistory`, `TimelineItem`, `ProcessCard`, and `FileChangeCard` in `MainActivity.kt`.
  - Fidelity surfaces: spacing, layout, content hierarchy, interaction state.
  - Evidence: the source shows one “Codex 正在处理你的请求” timeline node followed by a single compact container containing three divided rows. The implementation creates a new timeline node, avatar, border, outer gap, and tall card for every process item. The file-change card also exposes its summary and action while the source row is collapsed.
  - The implementation therefore uses roughly twice the vertical space for the process phase and pushes the assistant result substantially farther down the first viewport.
  - Code evidence: `ConversationHistory` renders every flat entry separately with `18.dp` outer spacing; each process item invokes its own `TimelineRow`. `FileChangeCard` has no whole-row collapsed state.
  - Impact: the main conversation becomes slower to scan and normal tool-heavy turns will require excessive scrolling. It also loses the source design’s clear hierarchy of “request → grouped work → answer.”
  - Fix:
    1. Group sequential non-message items by `turnId` before rendering.
    2. Render one process timeline node and one bordered `ProcessGroupCard`.
    3. Use compact 56–64 dp rows with internal dividers and no outer gap between rows.
    4. Put type icon, title, meaningful summary, completion icon/status, and chevron in each row.
    5. Keep completed rows collapsed; retain automatic expansion only for running and failed rows.
    6. Make file changes use the same row-level expansion pattern, with “查看差异” inside the expanded body.

- [P1] Composer diverges from the selected design and does not respect the navigation inset
  - Location: persistent composer at the bottom of `ConversationScreen`, `MainActivity.kt:675-700`.
  - Fidelity surfaces: layout, spacing, shape, interaction, accessibility.
  - Evidence: the source uses one continuous rounded capsule with the send control visually inset inside its right edge. The implementation presents a separate outlined field and detached circular button on a rectangular footer. The native gesture indicator remains inside the same footer area and the send button sits close to the gesture edge.
  - Code evidence: the root uses `imePadding()` but no `navigationBarsPadding()` or navigation-bar window inset. The composer is a plain `Row`; the field and button each own separate shapes.
  - Impact: this is a persistent, highly visible mismatch and places the primary action unnecessarily close to system navigation.
  - Fix:
    1. Apply `navigationBarsPadding()` or `windowInsetsPadding(WindowInsets.navigationBars)` to the composer container.
    2. Build one full-width `Surface` with a 26–30 dp radius and subtle outline.
    3. Place a borderless text input and a 48–52 dp send/stop button inside the shared surface with 4–6 dp internal padding.
    4. Preserve the existing 48 dp or larger touch target and disabled/running states.

- [P2] Typography is too large and heavy for the source hierarchy
  - Location: top bar, tabs, timeline labels, process titles, assistant label; `CodexRemoteTheme.kt:51-61` and relevant `Text` calls in `MainActivity.kt`.
  - Fidelity surface: fonts and typography.
  - Evidence: the implementation title, selected tab, “你”, “Codex”, and process titles are visibly heavier and larger than the source at equal normalized width. This amplifies the already-loose density and makes secondary process information compete with the conversation itself.
  - The use of native sans-serif is directionally correct, but `SemiBold` on CJK text renders optically closer to bold than the source.
  - Impact: hierarchy feels coarse rather than modern and compact; fewer conversation details fit above the fold.
  - Fix:
    - Top title: approximately `18.sp / 24.sp`, `Medium` or carefully checked `SemiBold`.
    - Timeline identity label: `16.sp / 22.sp`, `Medium`.
    - Process title: `15.sp / 20.sp`, `Medium`.
    - Secondary status/summary: `12–13.sp / 18.sp`, regular.
    - Keep response body near `16.sp / 24.sp`.
    - Check long Chinese titles and accessibility font scaling after adjustment.

- [P2] Top-bar horizontal and vertical rhythm is compressed relative to the source
  - Location: `ConversationScreen` top bar and tabs, `MainActivity.kt:614-649`.
  - Fidelity surface: spacing and layout rhythm.
  - Evidence: after system-chrome normalization, the implementation back/title cluster sits higher and closer to the left edge; the back icon and title have less optical separation. The source uses a calmer top inset and wider horizontal breathing room.
  - Code evidence: the row uses only `8.dp` horizontal padding and relies on a fixed `26.dp` top padding under edge-to-edge rendering.
  - Impact: the first screen region feels crowded while the process region below is overly spacious, producing inconsistent vertical rhythm.
  - Fix:
    - Use status-bar window insets instead of compensating only with a fixed top value.
    - Increase effective left/right content margin to `14–16.dp`.
    - Use an explicit 72–76 dp app-bar content height.
    - Keep title/subtitle spacing tight, but give the back icon and title block approximately 10–12 dp optical separation.
    - Preserve the existing two-tab structure and page indicator, which match the source direction.

- [P2] Dark tokens are flatter and harsher than the source
  - Location: `CodexRemoteTheme.kt:16-49`.
  - Fidelity surface: colors and visual tokens.
  - Evidence: sampled source regions use a softly layered dark range around `#101018`, `#14171C`, `#181820`, and approximately `#23252B` in the composer. The implementation uses a dominant flat `#0B0E13` background with `#121720` cards.
  - Indigo and green accents are close, but the implementation loses the source’s subtle luminous/vignette depth and makes the background read nearly black.
  - Impact: surfaces feel more utilitarian and less polished; container hierarchy depends too heavily on borders.
  - Fix:
    - Raise the base dark surface toward `#10141A`.
    - Use a restrained vertical or radial background transition toward `#0B0E13`.
    - Use raised surfaces around `#161C25` and composer surface around `#181E27`.
    - Retain the current indigo and green unless post-fix screenshots show contrast drift.
    - Keep borders low contrast around `#2C3440`; avoid adding shadows not present in the target.

- [P2] Fenced Markdown code is rendered as glyph backgrounds instead of a code block
  - Location: assistant result; `MarkdownText.kt:46-60` and `MarkdownText.kt:102-107`.
  - Fidelity surfaces: content hierarchy, typography, shape, spacing.
  - Evidence: the source presents code as a full-width inset block with padding and a stable rectangular surface. In the implementation, background color is applied only behind each line’s characters, producing disconnected dark strips with no block padding.
  - Code evidence: all Markdown blocks are flattened into one `AnnotatedString`; fenced code uses only `SpanStyle(background = ...)`.
  - Impact: multiline code is harder to distinguish and looks unfinished, especially for longer output.
  - Fix:
    1. Render Markdown as block composables rather than one flattened `Text`.
    2. Render fenced and indented code inside a full-width `Surface` with 8–10 dp radius and 10–12 dp padding.
    3. Use monospace around `14.sp / 20.sp`, preserve whitespace, and permit horizontal scrolling for long lines.
    4. Keep inline code as an inline span; do not apply the block treatment to inline code.

- [P2] Selected tab state is visible but not announced semantically
  - Location: `ConversationTab`, `MainActivity.kt:199-215`.
  - Fidelity surfaces: accessibility and state readability.
  - Evidence: visually, selected state is clear through indigo text and underline. However, the implementation uses a generic `clickable` column without tab role or selected-state semantics.
  - Impact: assistive technology cannot reliably announce which tab is active.
  - Fix: replace generic `clickable` with `selectable(selected = selected, role = Role.Tab, ...)` and expose the selected state in semantics. Keep the current 52 dp height, which is an adequate touch target.

## Open Questions

- The source uses “分析 / Bash / 文件修改”; the implementation uses “思考过程 / 命令 / 文件变更.” The implementation wording is coherent and better aligned with a Chinese-only generic client. Treat this as intentional unless exact mock copy is required.
- The empty source composer versus populated implementation composer is a state difference, not a defect.
- The schematic response body in the source cannot support literal copy comparison; only its container and code-block hierarchy were compared.
- The conversation overflow icon appears enabled in the source but disabled in implementation. If no conversation-level action exists yet, hiding it is clearer than showing a dead affordance.

## Required Fidelity Surface Summary

- Fonts and typography: **actionable P2 drift**. Native sans-serif family is appropriate, but optical weight and scale are too strong.
- Spacing and layout: **actionable P1/P2 drift**. Process grouping, composer construction, and top-bar rhythm do not yet match.
- Colors and tokens: **actionable P2 drift**. Accent mapping is close; base and raised surfaces are too flat and dark.
- Image quality and asset fidelity: **no raster image assets are present in this screen**. Code uses standard Material `Icon` vectors, not emoji, handcrafted SVG, or text-glyph approximations. The implementation’s rounded/filled icon family is heavier than the source’s outline family; switch the most visible back/send/process icons to the closest standard outlined variants as P3 polish.
- Copy and content: static Chinese copy is coherent. Missing process-group heading is part of the P1 hierarchy issue; other literal differences are dynamic-state or intentional localization differences.

## Code and Interaction Read-Only Check

- `MainActivity`, `CodexRemoteTheme`, and `MarkdownText` correspond to the captured implementation:
  - flat dark tokens come from `CodexRemoteTheme`;
  - separate process timeline cards come from per-entry `TimelineItem` rendering;
  - character-only code backgrounds come from `AnnotatedString` span styling.
- Standard icons are used throughout; no emoji is used for the reviewed screen.
- Back and expand controls use standard 48 dp `IconButton` targets.
- Send/stop uses a 54 dp target.
- Tabs are 52 dp tall.
- Completed/running/failed process states include text as well as color, so they are not color-only.
- Expand buttons have readable content descriptions.
- Remaining interaction concerns are the missing navigation-bar inset and missing tab selected semantics described above.
- Interaction behavior beyond the captured state was not executed in this read-only screenshot QA.

## Full-view Comparison Result

The broad dark developer-workbench direction is recognizable, and the implementation is substantially more coherent than a default Material screen. It does not yet match the selected visual closely enough for handoff: the process region, response/code hierarchy, and composer materially change the first-screen density and composition.

## Focused Comparison Result

- Top bar/tabs: structure matches; typography, inset, and optical spacing drift.
- Timeline/process: major grouping and density mismatch.
- Message/result: hierarchy is present, but response/code surfaces are under-designed.
- Composer: major structural mismatch and navigation-inset concern.

## Comparison History

### Iteration 1

- Evidence:
  - `/tmp/codex-remote-design-qa/full-comparison.png`
  - `/tmp/codex-remote-design-qa/topbar-tabs-comparison.png`
  - `/tmp/codex-remote-design-qa/timeline-process-comparison.png`
  - `/tmp/codex-remote-design-qa/message-composer-comparison.png`
- Findings: two P1 issues and five P2 issues remain.
- Fixes made: none; this was an independent read-only audit.
- Post-fix evidence: unavailable until the implementation agent applies fixes and captures the same state again.
- Result: blocked.

## Implementation Checklist

1. Group completed process items by turn and render the compact, divided process container.
2. Rebuild the composer as one inset capsule and apply navigation-bar padding.
3. Add block-aware fenced-code rendering.
4. Tune typography and top-bar spacing against the focused comparison.
5. Adjust base/raised dark tokens while preserving the current accent colors.
6. Add tab role and selected-state semantics.
7. Capture the same connected/completed-turn state at `1080 × 2400 @ 420 dpi`.
8. Repeat normalized full and focused comparisons; do not pass while any P0–P2 issue remains.

## Follow-up Polish

- [P3] Use the closest standard outlined icon variants for back, send, and process icons to better match the source’s stroke language.
- [P3] Hide the disabled overflow action until it has a real interaction, or implement its intended menu.
- [P3] After density fixes, fine-tune timeline connector termination so the line does not visually extend farther than the related turn.

final result: blocked

## Implementation Follow-up

The implementation agent applied the Iteration 1 remediation list without declaring a QA result:

- Consecutive non-message process items are grouped by `turnId` into one timeline node and one divided process card; missing `turnId` items remain isolated and ordered.
- Completed process rows default to collapsed. Running and failed rows remain expanded even when their disclosure control is tapped.
- File-change details and the nested diff action are available only after expanding the file-change row.
- The composer is one outlined 28 dp capsule containing a borderless text field and a 50 dp send/stop action, with IME and navigation-bar insets applied.
- Fenced and indented Markdown code is rendered as an independent full-width, padded, horizontally scrollable monospace surface. Inline code remains inline.
- Typography, solid dark tokens, status-bar inset handling, outlined process controls, tab selection semantics, and timeline connector termination were revised.
- Deterministic coverage was added for grouping/order safety, unknown and missing-turn items, forced-open process detail, collapsed file changes, Markdown block separation, inline code, tab selection, long Chinese text at `1.3` font scale, and IME/navigation inset behavior.

### Iteration 2

- Post-fix screenshot: `/home/kylin1993/sources/codex-remote-android/docs/design/modern-dark-implemented-iteration2.png`
- Screenshot dimensions: `1080 × 2400 @ 420 dpi`
- Screenshot SHA-256: `d5a0c89794b5bfd5919df6785c059190db804791123682bc3ded6c9d5aa9a2c1`
- State: connected conversation, selected conversation tab, one completed user request, three collapsed completed process rows in one group, completed assistant response with fenced code, empty composer.
- Independent visual comparison: completed by the same independent auditor that performed Iteration 1.

#### Iteration 2 Closure

| Severity | Open findings | Closure evidence |
| --- | ---: | --- |
| P0 | 0 | No blocking functional or visual regression was found in the same-state comparison. |
| P1 | 0 | Process grouping and forced-open state handling now preserve the required hierarchy; the composer is one inset-safe integrated surface. |
| P2 | 0 | Code blocks, typography, top-bar rhythm, tonal layers, and tab selection semantics satisfy the reviewed fidelity surface. |

Comparison evidence:

- Selected reference: `/home/kylin1993/sources/codex-remote-android/docs/design/modern-dark-reference.png`
- Iteration 2 implementation: `/home/kylin1993/sources/codex-remote-android/docs/design/modern-dark-implemented-iteration2.png`
- Both images represent the connected/completed conversation state at the target phone viewport; the implementation capture is `1080 × 2400 @ 420 dpi`.
- The independent auditor rechecked the full view and the focused top-bar/tabs, process timeline, Markdown response, and composer surfaces after the remediation.

Non-blocking follow-up:

- [P3] Minor optical tuning may continue as the rest of the Android screens gain real content, but no P3 item blocks this modern dark UI milestone.

final result: passed
