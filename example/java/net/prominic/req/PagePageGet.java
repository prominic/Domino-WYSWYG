package net.prominic.req;

import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Vector;

import lotus.domino.Database;
import lotus.domino.DateTime;
import lotus.domino.Document;
import lotus.domino.Session;
import net.prominic.DominoUtils;
import net.prominic.RichText;
import net.prominic.web.WebRequest;

/*
 * GET req=page&unid=<unid>[&edit=1] - one Page document with the four
 * field types the app supports, read, and edited with Editor access:
 *
 *   Field1  text       Field2  date (the form shows the date only)
 *   Field3  number     field4  rich text - through the rich-text kit
 *
 * req=page&edit=1 without a unid = a new page.
 */
public class PagePageGet extends BasePageGet {
	/* SimpleDateFormat is not thread-safe; web agents may run concurrently */
	private static final ThreadLocal<SimpleDateFormat> SHOW = new ThreadLocal<SimpleDateFormat>() {
		@Override
		protected SimpleDateFormat initialValue() {
			return new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH);
		}
	};
	private static final ThreadLocal<SimpleDateFormat> ISO = new ThreadLocal<SimpleDateFormat>() {
		@Override
		protected SimpleDateFormat initialValue() {
			return new SimpleDateFormat("yyyy-MM-dd");
		}
	};

	public PagePageGet(Session session, Database database, WebRequest web) {
		super(session, database, web);
	}

	static String formatDate(java.util.Date d) {
		return d == null ? "" : SHOW.get().format(d);
	}

	@Override
	protected void ownTags(HashMap<String, Object> data) throws Exception {
		String unid = safeUnid(param("unid"));
		boolean edit = canEdit() && "1".equals(param("edit"));
		data.put("edit", edit);
		data.put("saved", "1".equals(param("saved")));
		data.put("err", param("err"));
		// a failed save logged its details: the page links them (req=report&log=)
		data.put("log", safeUnid(param("log")));

		HashMap<String, Object> page = new HashMap<>();
		data.put("page", page);
		if (unid.isEmpty()) {
			if (!edit) {
				throw new Exception("Missing parameter: unid");
			}
			page.put("isnew", true);
			return;
		}

		Document doc = getPage(unid);
		try {
			page.put("unid", doc.getUniversalID());
			DateTime modified = doc.getLastModified();
			page.put("lastmod", String.valueOf(modified.toJavaDate().getTime()));
			DominoUtils.recycle(modified);

			// Field1 - text
			page.put("text", doc.getItemValueString("Field1"));

			// Field2 - date: shown formatted, edited as yyyy-MM-dd
			java.util.Date date = dateValue(doc, "Field2");
			page.put("date", formatDate(date));
			page.put("dateIso", date == null ? "" : ISO.get().format(date));

			// Field3 - number (general format: no trailing .0)
			page.put("number", numberText(doc, "Field3"));

			// field4 - rich text: formatting, pictures, links, attachments
			RichText.Body body = rich().read(doc, "field4");
			page.put("bodyHtml", body.html);
			page.put("bodyLocked", body.locked);
			page.put("bodyLockReason", body.reason);
		} finally {
			doc.recycle();
		}
	}

	/* the first value when it is a date/time, else null (empty, or text) */
	private static java.util.Date dateValue(Document doc, String item) throws Exception {
		Vector<?> values = doc.getItemValue(item);
		java.util.Date out = null;
		for (Object v : values) {
			if (out == null && v instanceof DateTime) {
				out = ((DateTime) v).toJavaDate();
			}
			if (v instanceof DateTime) {
				DominoUtils.recycle((DateTime) v);
			}
		}
		return out;
	}

	private static String numberText(Document doc, String item) throws Exception {
		Vector<?> values = doc.getItemValue(item);
		return values.isEmpty() ? "" : formatNumber(values.get(0));
	}

	/* 3.0 -> "3", 2.50 -> "2.5"; "" when empty or not a number (a view
	 * column hands the same Double as the item) */
	static String formatNumber(Object value) {
		if (!(value instanceof Number)) {
			return "";
		}
		return java.math.BigDecimal.valueOf(((Number) value).doubleValue()).stripTrailingZeros().toPlainString();
	}
}
