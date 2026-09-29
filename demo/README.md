# The demo application

Domino WYSIWYG running in a small Domino application: one form, `Page`, with a text, a date, a number and a rich-text field, a view of the pages, and the kit wired in exactly as [the guide](../README.md) describes. Build it in Designer, open it in a browser, edit a body from Notes and from the web, and see each end pick up the other's changes.

It is the reference application the kit is developed in, exported here as an on-disk project (`nsf/`) and its web templates (`ui/`).

## Build it - about ten minutes

1. **Server.** In the server's notes.ini set `HTTPJVMMaxHeapSize=256M` (or more) and restart the HTTP task. The kit sizes its limits from the Java heap; Domino's default of 64 MB is small.
2. **The NSF.** In Domino Designer: *File > New > Application*, on the server, e.g. `domino-wysiwyg-demo.nsf`, blank template. Then in the Package Explorer right-click the new application: *Team Development > Associate with Existing On-Disk Project*, choose the `nsf` folder here, and *Team Development > Sync with On-Disk Project* (import). Sign the design with an ID the server trusts (*Tools > Sign* or the Domino Administrator).
3. **Access.** In the ACL give your users **Editor** (the kit's save needs the right to change the document); readers of the pages need Reader; give the server itself Manager. Anonymous is set to No Access.
4. **Configuration.** Open the application in Notes, view *09. Config*, create the one Config document and set **BaseURL** to the application's web address, no trailing slash: `https://host/dir/domino-wysiwyg-demo.nsf`.
5. **The web templates.** They are documents in the NSF: in notes.ini of your Notes client set `DominoWysiwygUI=<path to this demo's ui folder>`, then in the application run the agent *9. Admin > 2. Import UI* (Actions menu). Run it again after changing a template.
6. **Open it.** `https://host/dir/domino-wysiwyg-demo.nsf/router?openagent` - sign in as an Editor, *New page*, and start typing. Create another page in Notes (form `Page`) with a picture, a table and a doclink, and open it on the web.

The agents `(router)`, `2. Import UI` and `1. Export UI` run as the signer where they need to; the router runs as the web user (*Run as web user*), so Domino's ACL decides what each visitor may do.

## What is where

| Route | What |
|---|---|
| `router?openagent&req=home` | the pages |
| `...&req=page&unid=<unid>` | a page; `&edit=1` edits it (Editor access) |
| `...&req=report&unid=<unid>` | how its rich text converts - the text to send with a problem |
| `...&req=page.save` (POST) | the save |

Design: the `utils` script library holds the kit's two classes; `req`, `req.get`, `req.post` the handlers (also in `../example/`); `web.request` and `mustache` the request and template plumbing of the reference application (JMustache 1.15, Apache License 2.0, in `Code/Jars`); the forms `Page`, `Config`, `Log`, `Template`, `CSS`, `JS`; the views `($pages)`, `($configs)`, `($templates)`, `($css)`, `($js)`, `08. Logs`.

## Take it further

The demo is a starting point, not a framework: copy what you need. To use the kit in your own application, follow [the guide](../README.md) - the two classes, the script, the stylesheet, the template markup, and two handlers.
