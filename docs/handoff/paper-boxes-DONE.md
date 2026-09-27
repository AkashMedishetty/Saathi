# Paper forms with character boxes — part 2

Branch: `codex/e2e`, after Part 1 commit `bab494b` (merged main at `0643e9c`).
Changes: `forms/PaperForm.kt`, `forms/PaperBoxesTest.kt`, and this note. No protected files or phone access.

## Behavior and integration

Existing call remains unchanged:

```kotlin
PaperForm.analyse(lines: List<OcrLine>, w: Int, h: Int, p: FormProfile,
                  today: LocalDate = LocalDate.now()): List<PaperField>
```

Continue passing ML Kit lines and their `OcrWord` bounds where available. No new wiring or dependencies.

- Recognizes `| | | |`, `I I I I`, `口口口`, `[ ][ ]`, and `LLLL` as empty-grid OCR runs.
  They split labels from write areas and no longer count as handwritten answers.
- A known label uses the grid's bounds when the grid is in the same OCR line, in a separate line on the same
  row, or in the next nearby row below. Word bounds give precise inline edges; otherwise character positions
  are estimated proportionally within the line. Boxes are clipped to the photo.
- An explicitly empty OCR span can supply its rectangle. When OCR returns only a known label, the helper
  infers a blank region to its right, bounded by adjacent content or the page margin. Nearby handwriting is
  excluded. Adjacent fields do not borrow the next field's grid.
- Existing sensitive-field handling is unchanged: Aadhaar and account numbers have no suggested value and
  say to write it yourself; signature remains “Sign here.” Postal PIN code remains distinct from secret PIN.
  Office-only sections remain excluded.

## Verification

`source ~/dev/android-env.sh` then `./gradlew testDebugUnitTest assembleDebug`: **PASS**.
**1,133 JVM tests**, zero failures/errors/skips, including **18 new PaperBoxes tests**.
`git diff --check`: PASS.

Tests cover each of the five OCR readings on a separate same-row line and inline with word bounds; below-label
placement; no recognized grid; empty spans; name/account/postal PIN and secret PIN behavior; Aadhaar/account/
signature privacy; real handwriting and look-alike words; mixed writing plus grid; adjacent columns and inline
labels; clipping; distant grids; and office sections.

## Limits

This is OCR-text/geometry inference, not image-based detection of individual square outlines. If OCR provides
no box bounds, the returned blank region is an estimate: exact physical grid edges cannot be recovered from
label text alone. Whole-token `LLLL` or spaced `I I I I` can be ambiguous with unusual actual writing. Ordinary
names such as Lillian, BILL, III, and writing mixed with grid symbols remain treated as filled. Below-label
grids must be on the next row within two line heights; complex layouts, perspective, and heavily damaged OCR
still need real-photo review. No phone/OCR-camera validation was performed here.
