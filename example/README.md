# Worked examples

The reference application (RichTextWeb) wired to the kit, exactly as [the guide](../README.md) describes. Copies, regenerated from the master with every release; read them next to the guide, adapt them to your own application - they are not a framework to install.

## Java

| File | What it shows |
|---|---|
| `java/net/prominic/req/BaseAbstract.java` | `rich()`: one `RichText` per request, built **before any document is opened** (`getPage()` calls it first), with `attachments()` and `docLinks()` set from the application's own URLs; `recycleRich()` at the end of the request. Also the guards every handler shares: a UNID sanitised, a document checked for its form, Editor access. |
| `java/net/prominic/req/PagePageGet.java` | The GET handler: reads the four fields of the `Page` form - text, a date, a number and the rich text - and hands the template `bodyHtml`, `bodyLocked`, `bodyLockReason`. |
| `java/net/prominic/req/PageSavePost.java` | The POST handler: refuses a truncated POST *before* reading a parameter, saves the plain fields, then `RichText.write()` with the files attached in the editor (`newfileN`); on a failure logs `writeReport()` and links it from the page. |
| `java/net/prominic/req/ReportPageGet.java` | The report page: `RichText.report()` for a document, or the details of a logged failure - Editor access only, since both hold document content. |
| `java/net/prominic/log/Logger.java` | A small log-document logger; `logDetails()` keeps a long text whole in a MIME item, `details()` reads it back (opening the entry under `setConvertMime(false)`). |

The handlers extend a base class of the reference application (`BaseAbstract`) and use its `WebRequest` for parameters; both are ordinary Domino web-agent plumbing - replace them with yours.

## Templates (Mustache)

| File | What it shows |
|---|---|
| `templates/page.page.html` | A page in read and edit mode: the toolbar and editor markup, the hidden field rendered `disabled`, the read-only branch with the lock reason, the "Report" action and the "Details for the developer" link after a failed save. |
| `templates/page.report.html` | The report: a `<pre>` with the text and a Copy button (`data-copy`, handled by a few lines in the application script). |

The templates are logic-less Mustache (JMustache on the server); the same markup works with any template engine as long as the body is printed unescaped and only kit output ever is.
