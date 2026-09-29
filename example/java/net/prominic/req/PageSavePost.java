package net.prominic.req;

import java.text.SimpleDateFormat;

import lotus.domino.Database;
import lotus.domino.DateTime;
import lotus.domino.Document;
import lotus.domino.Session;
import net.prominic.DominoUtils;
import net.prominic.log.Logger;
import net.prominic.web.WebRequest;

/*
 * POST req=page.save[&unid=<unid>] - saves the page form and redirects
 * back to the page (303). No unid = a new Page document.
 *
 *   text   -> Field1 (text)
 *   date   -> Field2 (yyyy-MM-dd from <input type=date>, stored date-only;
 *             empty clears it, like an emptied Notes date field)
 *   number -> Field3 (a number; empty clears it)
 *   body   -> field4 through the rich-text kit, as classic Notes rich
 *             text - ONLY when posted: the page posts it only when the
 *             editor changed, so a save of the other fields never touches it
 *   newfileN -> "name|base64" of a file attached in the editor, stored as
 *             an attachment where the body shows <span data-file="N">
 *
 * The three simple fields are saved first; the rich text then re-imports
 * the whole document (RichText.write), so it must see them saved.
 * A validation problem goes back to the edit form as &err=.
 */
public class PageSavePost extends BaseAbstract {
	/* files one save may attach (the page numbers them 1..) */
	private static final int MAX_NEW_FILES = 20;

	public PageSavePost(Session session, Database database, WebRequest web) {
		super(session, database, web);
	}

	@Override
	public void run() throws Exception {
		String unid = "";
		net.prominic.RichText writer = null;
		try {
			// before anything parses the body: a cut POST can end inside a
			// %XX escape and fail the parse; the unid rides the action URL
			unid = queryUnid();
			requireCompletePost();
			unid = safeUnid(m_web.getParam("unid"));
			if (!canEdit()) {
				throw new Exception("You need Editor access to save a page");
			}

			Document doc = null;
			try {
				if (unid.isEmpty()) {
					doc = m_database.createDocument();
					doc.replaceItemValue("Form", "Page");
				} else {
					doc = getPage(unid);
					requireUnchanged(doc);
				}

				doc.replaceItemValue("Field1", param("text"));
				saveDate(doc, "Field2", param("date"));
				saveNumber(doc, "Field3", param("number"));
				doc.save(true, false);
				unid = doc.getUniversalID();

				String body = m_web.getParam("body");
				if (body != null) {
					// re-imports the document and recycles doc - nothing
					// may use it after this line
					writer = rich();
					// files attached in the editor: newfileN = "name|base64",
					// shown in the body as <span data-file="N">
					for (int n = 1; n <= MAX_NEW_FILES; n++) {
						String file = m_web.getParam("newfile" + n);
						if (file == null) {
							continue;
						}
						int bar = file.lastIndexOf('|');
						if (bar > 0) {
							writer.attach(String.valueOf(n), file.substring(0, bar), file.substring(bar + 1).trim());
						}
					}
					writer.write(doc, "field4", body);
				}
			} finally {
				DominoUtils.recycle(doc);
				recycleRich();
			}

			setStatus(303);
			setLocation(getBaseURL() + "/router?openagent&req=page&unid=" + unid + "&saved=1");
		} catch (Exception e) {
			// a failed rich-text save logs what it was given and made of it -
			// the page links that entry for the user to send to the developer
			String details = writer == null ? null : writer.writeReport();
			String log = null;
			if (details != null) {
				log = Logger.logDetails(m_database, "page.save: " + e.getMessage(), details, Logger.WARNING);
			} else {
				Logger.logWarning(m_database, "page.save: " + e.getMessage());
			}
			setStatus(303);
			setLocation(getBaseURL() + "/router?openagent&req=page&edit=1"
					+ (unid.isEmpty() ? "" : "&unid=" + unid) + (log == null ? "" : "&log=" + log)
					+ "&err=" + java.net.URLEncoder.encode(e.getMessage() == null ? "Unexpected error" : e.getMessage(), "UTF-8"));
		}
	}

	/* the form carries the page's last-modified stamp: a different one now
	 * means someone else saved since the page was opened - never last write wins */
	private void requireUnchanged(Document doc) throws Exception {
		String shown = param("lastmod");
		if (shown.isEmpty()) {
			return;
		}
		lotus.domino.DateTime modified = doc.getLastModified();
		long current = modified.toJavaDate().getTime();
		DominoUtils.recycle(modified);
		if (Math.abs(current - Long.parseLong(shown)) > 2000) {
			throw new Exception("This page was changed by someone else while you were editing"
					+ " - nothing was saved. Reload it and make your changes again.");
		}
	}

	private void saveDate(Document doc, String item, String iso) throws Exception {
		if (iso.isEmpty()) {
			doc.replaceItemValue(item, "");
			return;
		}
		java.util.Date date;
		try {
			SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
			format.setLenient(false);
			date = format.parse(iso);
		} catch (java.text.ParseException e) {
			throw new Exception("Not a date: " + iso);
		}
		DateTime dt = m_session.createDateTime(date);
		try {
			dt.setAnyTime(); // date only, like the form's field
			doc.replaceItemValue(item, dt);
		} finally {
			DominoUtils.recycle(dt);
		}
	}

	private static void saveNumber(Document doc, String item, String text) throws Exception {
		if (text.isEmpty()) {
			doc.replaceItemValue(item, "");
			return;
		}
		try {
			doc.replaceItemValue(item, Double.valueOf(text.replace(',', '.')));
		} catch (NumberFormatException e) {
			throw new Exception("Not a number: " + text);
		}
	}

	/*
	 * WebRequest reassembles at most 100 Request_Content parts (~6.4 MB). A
	 * longer POST - a body with very large pasted pictures - arrives cut,
	 * and saving it would store whichever field was cut in half.
	 */
	private void requireCompletePost() throws Exception {
		long declared;
		try {
			declared = Long.parseLong(m_web.getDC().getItemValueString("Content_Length").trim());
		} catch (NumberFormatException e) {
			return; // no declared length - nothing to compare against
		}
		if (m_web.getRequestContent().length() + 1024 < declared) {
			throw new Exception("The changes are too large to save in one go - usually very large pasted"
					+ " pictures. Nothing was saved; use smaller pictures and save again.");
		}
	}

	/* the unid from the query string alone - readable when the body is not */
	private String queryUnid() throws Exception {
		for (String pair : m_web.getDC().getItemValueString("Query_String").split("&")) {
			if (pair.startsWith("unid=")) {
				return safeUnid(pair.substring(5));
			}
		}
		return "";
	}
}
