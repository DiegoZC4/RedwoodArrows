# Redwood Review: UI Changes (Oct 29–30, 2026)

A short guide to the user-interface work in this local trial (`org.redwoodarrows.review`),
for reviewing what to bring upstream. Full detail, test results and install receipts are in
the dated "(Claude, UI Only)" sections at the end of `REVIEW_HANDOFF.md`.

**Scope.** Presentation and navigation only. No changes to detection, training, the
detector model, `Store` formats, sync, statistics, review certification or target
assignment logic. Every build passed the host and on-device suites (including the 36
touch/render checks), and every install verified all saved app files byte-identical.

## Look and feel
- One accent color (soft mint `#A9D4C2`, already used for Take photo). Shooter colors
  (Iris purple, Diego blue) are used for names, dots, chart series and the shooter chip.
- Two surface levels: filled panels for content; outlined controls (dropdowns, chips,
  secondary buttons). Matching rounded dropdowns everywhere; 8 dp spacing rhythm.
- Consistent top bars (title left, quiet icons right); clear selected-tab state.

## Home
- Shooter cards: latest spread, change since the previous day, "≈ N cm from group
  center", where the group sits relative to the bullseye, date and range.
- Activity: days shooting, weekly streak, arrows recorded (per person + unassigned),
  tightest day (12+ arrows, 2+ rounds), distances practiced.
- Needs-attention strip (unchecked photos, detection running, unmeasured arrows).
- Chart: tighter labeled y-axis, thicker lines, latest value at each line's end.

## Photos
- Compact cards (square map, "6 arrows"), dark target thumbnails, Needs review chip,
  "Step through / Review N unchecked photos" queue. Capture bar removed (lives on Home).

## Photo review editor
- Queue navigation (‹ › with "k of N"); auto-zoom to the arrows; zoom follows selection.
- Hold-to-peek eye button hides markers; labels avoid all crosshairs and keep their spot
  across tabs; Targets tab draws every fitted ring; select-all toggles.
- Numbered arrow chips only on Targets; one-line status; consistent "Dots checked".

## Grouping and Progress
- Grouping: shooter-colored dots, non-overlapping number pills, stats card.
- Progress: shooter + range dropdowns in one row, "100-arrow trend" toggle, visual key,
  more date labels, tighter axis, plain-language notes ("Likely 8.5–10.4 mrad").
  The Filters dialog was removed because no grouping records equipment or position
  verification; an Equipment picker appears automatically if 2+ setups exist.

## Files touched (UI)
`ActivityStats` (new, with `tests/ActivityStatsTest.java`), `ReviewQueue` (new),
`ConsistencyChart`, `ConsistencyUi`, `GroupDetails`, `GroupingGallery`, `HomeUi`,
`LibraryFilters`, `MainActivity`, `PhotoAssignmentUi`, `PhotoCanvas`, `ProgressUi`,
`QuickCapture`, `QuickReviewUi`, `ReviewControls`, `ShooterPager`, `SweepView`,
`TargetCanvas`, `res/drawable/review_eye.xml`; `ActivityStats` added to the host test list in
`tools/build_review.ps1`. Several of these files also contain Codex's earlier trial edits.
