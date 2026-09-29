package net.prominic.log;

import java.io.PrintWriter;
import java.io.StringWriter;

import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.Item;
import lotus.domino.MIMEEntity;
import lotus.domino.NotesException;
import lotus.domino.Session;
import lotus.domino.Stream;

public class Logger {
	public static int DEBUG = 0;
	public static int INFO = 1;
	public static int WARNING = 2;
	public static int SEVERE = 3;
	
	public static void log(Database database, String subject, String body) {
		log(database, subject, body, 0);
	}

	public static void log(Database database, String subject) {
		log(database, subject, "", 0);
	}
	
	public static void logSevere(Database database, String subject) {
		log(database, subject, "", Logger.SEVERE);
	}
	
	public static void logSevere(Database database, String subject, Exception e) {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		log(database, subject, sw.toString(), Logger.SEVERE);
	}
	
	public static void logSevere(Database database, Exception e) {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		
		String subject = e.getMessage();
		if (subject == null || subject.isEmpty()) {
			subject = "undefined exception";
		}

		log(database, subject, sw.toString(), Logger.SEVERE);
	}
	
	public static void logWarning(Database database, String subject) {
		log(database, subject, "", Logger.WARNING);
	}
	
	public static void logInfo(Database database, String subject) {
		log(database, subject, "", Logger.INFO);
	}
	
	public static void logInfo(Database database, String subject, String body) {
		log(database, subject, body, Logger.INFO);
	}
	
	public static void logDebug(Database database, String subject) {
		log(database, subject, "", Logger.DEBUG);
	}
	
	/* Body is a text item - what the Log form shows; longer details are cut
	 * there and kept whole in the MIME item Details (details() reads it) */
	public static final int MAX_BODY = 16000;

	/*
	 * A log entry with long details - a report to send the developer. The
	 * details go whole into a text/plain MIME item "Details" (no text-item
	 * size limit) and, cut at MAX_BODY, into Body for the Log form. Returns
	 * the entry's UNID, null when it could not be written.
	 */
	public static String logDetails(Database database, String subject, String details, int level) {
		if (database == null) return null;
		String text = details == null ? "" : details;
		try {
			Session session = database.getParent();
			Document doc = database.createDocument();
			Stream stream = null;
			try {
				doc.replaceItemValue("Form", "Log");
				doc.replaceItemValue("Subject", subject);
				Item body = doc.replaceItemValue("Body", text.length() > MAX_BODY
						? text.substring(0, MAX_BODY) + "\n[cut - the whole text is on the web report page]" : text);
				body.setSummary(false);
				body.recycle();
				doc.replaceItemValue("Level", level);
				boolean convert = session.isConvertMime();
				session.setConvertMime(false);
				try {
					MIMEEntity mime = doc.createMIMEEntity("Details");
					stream = session.createStream();
					stream.writeText(text);
					mime.setContentFromText(stream, "text/plain;charset=UTF-8", MIMEEntity.ENC_NONE);
					doc.closeMIMEEntities(true, "Details");
				} finally {
					session.setConvertMime(convert);
				}
				doc.save();
				return doc.getUniversalID();
			} finally {
				if (stream != null) {
					stream.close();
					stream.recycle();
				}
				doc.recycle();
			}
		} catch (NotesException e) {
			e.printStackTrace();
			return null;
		}
	}

	/* a log entry's details: the Details MIME item, else Body. The item is
	 * MIME only in a document opened while the session does not convert
	 * MIME, so the entry is opened again here under that setting */
	public static String details(Document doc) throws NotesException {
		Database database = doc.getParentDatabase();
		Session session = database.getParent();
		boolean convert = session.isConvertMime();
		session.setConvertMime(false);
		Document fresh = null;
		MIMEEntity mime = null;
		try {
			fresh = database.getDocumentByUNID(doc.getUniversalID());
			mime = fresh.getMIMEEntity("Details");
			if (mime != null) {
				String text = mime.getContentAsText();
				if (text != null) {
					return text;
				}
			}
			return fresh.getItemValueString("Body");
		} finally {
			if (mime != null) mime.recycle();
			if (fresh != null) fresh.recycle();
			session.setConvertMime(convert);
		}
	}

	public static void log(Database database, String subject, String body, int level) {
		if (database == null) return;
		
		try {
			Document doc = database.createDocument();
			doc.replaceItemValue("Form", "Log");
			doc.replaceItemValue("Subject", subject);
			doc.replaceItemValue("Body", body);
			doc.replaceItemValue("Level", level);
			doc.save();
			doc.recycle();
		} catch (NotesException e) {
			e.printStackTrace();
		}
	}
}