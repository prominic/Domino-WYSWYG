# Security - Domino WYSIWYG

## What the kit protects

- **The page.** Everything printed unescaped comes out of `HtmlSanitizer.clean()`: an allowlist of elements, attributes, URL schemes and CSS properties, with balanced output. The renderer's own output goes through it as well. Anything else printed raw into a page is outside the kit's protection - keep it that way in your templates.
- **Other documents.** The page markers (`data-pd`, `data-pic`, `data-tbl`, `data-keep`) are indexes into the document being saved, never identifiers of other documents; an index out of range or a `data-kind` that does not match is treated as plain text. HTML pasted into the editor loses its markers.
- **The document.** A save is a whole-note DXL import as the browser user, under Domino's ACL. It is refused when the document changed meanwhile, when its size would not fit the heap, or when it holds an encrypted item; a failed import is rolled back and reported.

## What is your application's

- Access control is Domino's: the kit never widens it. The report page shows document content - keep it behind the same access as editing.
- CSRF protection of the POST route, request size limits and login are the application's; the reference application shows the POST-size guard only.
- The `HTTPJVMMaxHeapSize` of the server (256 MB or more): the kit sizes its limits from the heap it is given.

## Reporting a vulnerability

Please do not open a public issue for a way past the sanitizer, the markers or the access checks. Send the report page's text (it holds the kit version and the body's source, shortened) with the steps to reproduce to the maintainers' security contact named in the repository description, and allow a reasonable time for a fix before disclosure. Reports are acknowledged within five working days.
