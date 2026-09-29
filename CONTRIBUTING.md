# Contributing to Domino WYSIWYG

Thank you for looking under the hood. A few things that keep this kit sound:

## Before you change anything

- Run `tools\Check-RichTextKit.ps1` and keep it at `RICH-TEXT KIT: ALL OK`. It is the kit's proof; a change that needs a check loosened needs a conversation first.
- The two Java classes must keep compiling **alone** against `Notes.jar` (stage 1 checks it). No logging framework, no utility class, no third-party library.
- Java 8 syntax and bytecode: Domino's agent JVM.
- ASCII only in the JS and CSS (they travel through Notes documents on some servers); a character above 127 goes in as an escape.

## The one rule of the writer

**What the user did not touch goes back exactly as it was.** Every construct the renderer emits either carries a marker to its original element (`data-pd`, `data-pic`, `data-tbl`, `data-keep`, `data-list`, `data-cap`, `data-file`) or maps back to a DXL element one-to-one. When you teach the renderer a new element, teach the writer the way back in the same change, add a fixture that holds it, and make the cycle check keep it. If it cannot go back, make it an island (kept verbatim) rather than a lossy conversion.

## The one rule of the reader

**If in doubt, lock.** A body the writer could not hand back intact must be shown read-only with a reason, never editable. Read-only costs an edit in Notes; a wrong unlock costs the Notes original.

## Reporting a problem

Open the report page for the document (or the "Details for the developer" link after a failed save), copy the text and attach it: it holds the kit version, the reasons, the islands, the Notes source of the body (base64 shortened) and what the page made of it. For a rendering difference, a screenshot of Notes next to the browser says the rest.

For anything that looks like a way past the sanitizer or the markers, see [SECURITY.md](SECURITY.md) instead of a public issue.
