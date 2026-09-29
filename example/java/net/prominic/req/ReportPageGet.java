package net.prominic.req;

import java.util.HashMap;

import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.Session;
import net.prominic.log.Logger;
import net.prominic.web.WebRequest;

/*
 * GET req=report&unid=<page> - how the page's rich text converts
 *                              (RichText.report): read-only or not and
 *                              why, the elements kept whole or unknown,
 *                              the HTML made and the item's DXL
 * GET req=report&log=<unid>  - a logged failure with its details
 *                              (Logger.logDetails, e.g. a failed save)
 *
 * The text is meant to be copied and sent to the developer. Editor access
 * only: both hold the document's content.
 */
public class ReportPageGet extends BasePageGet {
	public ReportPageGet(Session session, Database database, WebRequest web) {
		super(session, database, web);
	}

	@Override
	protected void ownTags(HashMap<String, Object> data) throws Exception {
		if (!canEdit()) {
			throw new Exception("You need Editor access to see a report");
		}
		HashMap<String, Object> report = new HashMap<>();
		data.put("report", report);

		String log = safeUnid(param("log"));
		if (!log.isEmpty()) {
			Document doc;
			try {
				doc = m_database.getDocumentByUNID(log);
			} catch (Exception e) {
				throw new Exception("Log entry not found: " + log);
			}
			try {
				if (!"Log".equals(doc.getItemValueString("Form"))) {
					throw new Exception("Log entry not found: " + log);
				}
				report.put("title", doc.getItemValueString("Subject"));
				report.put("text", Logger.details(doc));
			} finally {
				doc.recycle();
			}
			return;
		}

		String unid = safeUnid(param("unid"));
		Document doc = getPage(unid);
		try {
			report.put("unid", unid);
			report.put("title", "Rich text of " + doc.getItemValueString("Field1"));
			report.put("text", rich().report(doc, "field4"));
		} finally {
			doc.recycle();
		}
	}
}
