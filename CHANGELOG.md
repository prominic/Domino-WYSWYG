# Changelog - Domino WYSIWYG

## 1.0.1 - 2026-09-29

- A picture resized in the editor could not be saved: the scaled size was written in pixels, which Domino's DXL importer refuses ("Length value is invalid") although the DTD allows it. It is written in inches, as the exporter writes it.
- A doclink made on the web could not be saved: its replica ID was written in the colon form Notes displays (`XXXXXXXX:XXXXXXXX`), which the importer refuses ("Hexadecimal number value is invalid"); the importer takes 16 plain hex digits, as the exporter writes them. Applied to new doclinks, originals and islands alike.
- Every document with an attachment was refused with "encrypted field": the guard keyed on the `seal='true'` flag every `$FILE` item carries. It now looks for a `$Seal` or `SecretEncryptionKeys` item.
- After a save the server refused, the editor's content is put back from a draft kept in session storage instead of being lost.
- Read: the item's size is summed over its segments without recycling the wrappers `getItems()` hands out, which had invalidated the reader's own item and made every classic body read-only.

## 1.0.0 - 2026-09-29

First shareable release.

- Classic rich text rendered from DXL: fonts, colours, highlights, alignment, indents, lists of every Notes type, tabs, tables, pictures, URL links, doclinks, view and database links, attachment icons; MIME bodies read as they are.
- Saved back as classic rich text through a DXL import; untouched content reused element by element (paragraph definitions, pictures, links, icons, table settings, list types).
- Islands: hotspots, buttons, popups, sections, computed text, pass-thru HTML, hidden paragraphs, unknown elements kept verbatim and editable around.
- Bodies are posted only when their editor changed.
- WYSIWYG editor: fonts, sizes, colours, highlights, styles, alignment, lists and list types, Tab, links (URL, `notes://`, page = doclink), pictures and picture size, attached files, tables with row and column operations, rules, undo/redo, source.
- Reports: how a body converts, and every failed save, as text for the developer.
- A last-modified check before the import; size limits; an allowlist sanitizer.
- Offline checks: compile-alone, fixtures, render-write-render identity, DTD validation, the editor and the full cycle driven in headless Edge.
- demo/: the reference application as an on-disk project and its templates, buildable in Designer in minutes; docs/editor.png; the checks find the Notes install by themselves (-NotesRoot, NOTES_ROOT) and accept Chrome as well as Edge.
- Review round before release: a failed import is always rolled back once it went through; sizes measured against the Java heap; the document exported from a fresh handle; one buffer for the changed document; MIME bodies read-only; sealed fields block a web save; `recycle()`.

Before 1.0.0 the kit shipped inside one application (2026-09-28) with a MIME-based save; that design is superseded.
