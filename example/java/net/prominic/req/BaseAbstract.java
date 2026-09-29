package net.prominic.req;

import lotus.domino.Database;
import lotus.domino.Document;
import lotus.domino.NotesException;
import lotus.domino.Session;
import net.prominic.web.WebRequest;

/*
 * One request: what the router hands every handler (session, this
 * database, the parsed request, the Config document) and what the handler
 * hands back (content type, status, location, cookie, content).
 *
 * The router runs as the web user, so m_session and m_database carry the
 * browser user's own access - Domino enforces the ACL and Readers/Authors
 * items, the code does not re-implement them.
 */
public abstract class BaseAbstract {
	protected Session m_session;
	protected Database m_database;
	protected WebRequest m_web;
	private Document m_config;
	private Object m_content;
	private String m_contentType;
	private String m_location;
	private int m_status;
	private String m_cookie;
	private String m_baseURL;

	public BaseAbstract(Session session, Database database, WebRequest web) {
		m_session = session;
		m_database = database;
		m_web = web;
	}

	public void setContentType(String contentType) {
		m_contentType = contentType;
	}
	public String getContentType() {
		return m_contentType;
	}

	public void setContent(Object content) {
		m_content = content;
	}
	public Object getContent() {
		return m_content;
	}

	public void setLocation(String location) {
		m_location = location;
	}
	public String getLocation() {
		return m_location;
	}

	public void setStatus(int status) {
		m_status = status;
	}
	public int getStatus() {
		return m_status;
	}

	public void setCookie(String cookie) {
		m_cookie = cookie;
	}
	public String getCookie() {
		return m_cookie;
	}

	public void setConfig(Document doc) {
		m_config = doc;
	}
	public Document getConfig() {
		return m_config;
	}

	public String getBaseURL() {
		if (m_baseURL != null) {
			return m_baseURL;
		}

		try {
			m_baseURL = getConfig().getItemValueString("BaseURL");
			return m_baseURL;
		} catch (NotesException e) {
			e.printStackTrace();
		}
		return "";
	}

	/* a request parameter, trimmed, "" when absent */
	protected String param(String name) throws Exception {
		String value = m_web.getParam(name);
		return value == null ? "" : value.trim();
	}

	/* a UNID is 32 hex characters; anything else is stripped before the
	 * value can reach a Location header or a lookup */
	protected static String safeUnid(String unid) {
		if (unid == null) {
			return "";
		}
		String out = unid.replaceAll("[^0-9A-Fa-f]", "");
		return out.length() > 32 ? out.substring(0, 32) : out;
	}

	/* a Page document or a clear "not found" - never another form's document */
	protected Document getPage(String unid) throws Exception {
		rich(); // before the document is opened: the kit sets convertMime(false)
		Document doc;
		try {
			doc = m_database.getDocumentByUNID(unid);
		} catch (Exception e) {
			throw new Exception("Page not found: " + unid);
		}
		if (!"Page".equals(doc.getItemValueString("Form"))) {
			doc.recycle();
			throw new Exception("Page not found: " + unid);
		}
		return doc;
	}

	/* the Page form's fields need Editor access (a Page has no Authors item);
	 * the router runs as the web user, so this is the browser user's level */
	protected boolean canEdit() throws Exception {
		return m_database.getCurrentAccessLevel() >= lotus.domino.ACL.LEVEL_EDITOR;
	}

	/* the rich-text kit (RICHTEXT.md), one per request - it carries the
	 * picture budget. Attachment icons link to this database's files; a
	 * doclink to a Page opens that page here, and a link the editor makes
	 * to a page's address becomes a doclink (through the ($pages) view). */
	private net.prominic.RichText m_rich;

	protected net.prominic.RichText rich() throws Exception {
		if (m_rich == null) {
			String viewUnid = null;
			lotus.domino.View pages = m_database.getView("($pages)");
			if (pages != null) {
				viewUnid = pages.getUniversalID();
				pages.recycle();
			}
			m_rich = new net.prominic.RichText(m_session).attachments(dbWebUrl())
					.docLinks(m_database.getReplicaID(), getBaseURL() + "/router?openagent&req=page&unid=", viewUnid);
		}
		return m_rich;
	}

	/* the kit's own objects, when the request is over */
	protected void recycleRich() {
		if (m_rich != null) {
			m_rich.recycle();
		}
	}

	/* this database's web URL: BaseURL up to and including ".nsf" */
	protected String dbWebUrl() {
		String base = getBaseURL();
		int nsf = base.toLowerCase().indexOf(".nsf");
		return nsf < 0 ? base : base.substring(0, nsf + 4);
	}

	public abstract void run() throws Exception;
}
