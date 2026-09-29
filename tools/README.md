# The checks

Everything here runs offline, against the kit's files (or your application's copy of them), and none of it needs a Domino server. Run it after copying the kit, and after every change to it.

    powershell Check-RichTextKit.ps1
    powershell Check-RichTextKit.ps1 -Library <app>\...\utils.javalib -Script <app>\...\app.js
    powershell Check-RichTextKit.ps1 -JavaDir <app>\src -Script <app>\...\app.js [-NotesRoot <install>]

Expected last line: `RICH-TEXT KIT: ALL OK`.

## What you need

- **Windows** with a **Notes client or Domino server install**: its JVM, `Notes.jar` and `xmlschemas\domino_*.dtd`. Found by itself in the usual places, else set `NOTES_ROOT` or pass `-NotesRoot`. A Notes client also brings the Eclipse compiler; without it, a `javac` (JDK 8 or later) on the PATH is used.
- **Node 22+** for the browser checks (they are skipped without it).
- **Microsoft Edge or Google Chrome** for the editor and cycle checks (skipped without one; `RICH_KIT_BROWSER` names another Chromium executable). The checks start their own headless instance on a scratch profile and stop only that.

## What runs

| Stage | File | Proves |
|---|---|---|
| 1 | `Check-RichTextKit.ps1` | `HtmlSanitizer` and `RichText` compile **on their own** at Java 8 against `Notes.jar` - no dependency has crept in. |
| 2 | `RichTextCheck.java` | The sanitizer (including hostile input), picture inlining, the DXL renderer on fixtures, the writer: render → write → render is the identity, edits land where they should, islands go back verbatim, attached files become `$FILE` items, untouched list types, table settings and picture sizes are kept - and every generated `<richtext>` is **valid against Domino's own DTD**. |
| 3 | `richtext-js-check.js` | The script's kit block, cut out and run alone: untouched bodies are not posted, changed ones are. |
| 4a | `editor-check.js` | Every toolbar control driven in the real browser, and the HTML each one writes checked. `EDITOR_DUMP=1` prints the posted HTML, to paste into `RichTextCheck` as a fixture when the editor changes. |
| 4b | `CycleCheck.java` + `cycle-check.js` | The **full cycle**: each fixture rendered, put into the real editor, submitted untouched and after an edit, and what the browser posted written back - untouched renders identically and keeps every construct; edited holds the edits and keeps the rest. |

Each stage can be run by hand; the script prints the exact commands' output on a failure.

## Adding a fixture

A fixture is one rich-text item's DXL (what `DxlExporter` writes for it) as a Java string in `RichTextCheck` - `SCREENSHOT`, `ISLANDS`, `FIDELITY` are the models. Add it to `CYCLE` with the DXL strings a save must still write back, and it is covered by stages 2 and 4b at once. The report page of the reference application prints an item's DXL, base64 shortened, ready to paste.
