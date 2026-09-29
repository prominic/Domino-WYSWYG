# Changelog - Domino WYSIWYG

## 1.1.0 - 2026-09-29

- MIME (mail-style) bodies are edited too, and go back as MIME: one `text/html` part, or `multipart/related` with a part per picture - the format the Notes client renders natively. An application whose bodies an earlier web editor saved as MIME keeps them as they are; a classic body stays classic. `Body.mime` says which kind was read. Read-only remains for what such a save could not carry (a second HTML part, a file inside the body, a picture outside the text), and a file cannot be attached to a MIME body from the web.
- Demo: the Import UI agent stores a CSS/JS body over 32K as a non-summary text item (64K) and says so past 64K, instead of failing at Save.

## 1.0.3 - 2026-09-29

- Named colours: DXL names colours as CSS2 does (the exporter writes `#008000` as `green` and `#00ff00` as `lime`); the kit knew only Notes' palette names, so `lime`, `purple`, `fuchsia`, `navy`, `olive`, `teal`, `aqua` and `maroon` were dropped on the page - and by the next web save - and `green` showed in the wrong shade. All sixteen names, `none` and `system` now map and round-trip; a fixture with every name is in the browser cycle.
- The "Edit HTML source" button is gone: the editor offers Notes rich-text operations only, and HTML typed by hand had no place to go.
- A selection that runs from one table cell into another (a column, a block of cells) is formatted as those whole cells, by row and column, as Notes does. The browser's own reading took everything between the two cells in document order, so colouring a column painted the rows between it.
- `demo/ui` shipped three older stamps of the script and stylesheet next to the current one; the export now removes what an earlier export left behind.

## 1.0.2 - 2026-09-29

More of an untouched body comes back exactly, found by probing every rich-text construct the DXL can hold:

- A horizontal rule keeps its colour, width and height (it was rebuilt bare).
- A URL link keeps its border and target frame while its address is unchanged.
- Table cells keep their settings (row headers, alternate colours, backgrounds) while the table's shape is unchanged; a cell's vertical alignment now shows on the page and round-trips.
- Shadow, emboss and extrude on a run round-trip (a marker on the page).
- Raw records and characters XML cannot hold are kept without being reported as unknown, and show nothing.
- The offline checks validate against the newest DXL DTD of the install, chosen by version number (the name order had picked 9.0.1 over 12.0).

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
