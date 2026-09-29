package net.prominic;

/*
 * Domino WYSIWYG - a Notes rich-text field on the web, edited from both ends
 * https://github.com/prominic/Domino-WYSWYG (README.md)
 * Copyright 2026 Prominic.NET. Licensed under the Apache License, Version 2.0;
 * see the LICENSE file that came with the kit.
 */

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import lotus.domino.Base;
import lotus.domino.Document;
import lotus.domino.DxlExporter;
import lotus.domino.Item;
import lotus.domino.MIMEEntity;
import lotus.domino.MIMEHeader;
import lotus.domino.NotesException;
import lotus.domino.RichTextItem;
import lotus.domino.Session;
import lotus.domino.Stream;

/*
 * Rich-text kit (VERSION below): a Notes rich-text item shown in a web page
 * with its formatting and pictures, and the HTML a WYSIWYG editor posts
 * saved back without destroying what Notes stored.
 *
 * SELF-CONTAINED - it needs only the Notes API and HtmlSanitizer (same
 * package). To reuse it, copy both files into any Java script library.
 * The template, JS and CSS halves, the deploy notes and the offline check
 * (Check-RichTextKit.ps1) live with the MASTER COPY of the kit: the
 * RichTextWeb repo (prominic\RichTextWeb, RICHTEXT.md) - improve it
 * there, copy it out.
 *
 *   RichText rich = new RichText(session)             // one per request
 *           .attachments(dbWebUrl)                    // optional, see below
 *           .docLinks(replicaId, webUrlPrefix);       // optional
 *   RichText.Body body = rich.read(doc, "Body");      // body.html, body.locked, body.reason
 *   rich.write(doc, "Body", postedHtml);              // caller saved its other items first
 *   rich.report(doc, "Body");                         // how the item converts, for the developer
 *
 * read(): classic rich text is rendered from its DXL - the one item is
 * exported and turned into HTML element by element, so fonts, colours,
 * alignment, lists, tables, pictures, links, doclinks and attachment
 * icons all stay where Notes has them (Notes bitmaps are converted to GIF
 * by the exporter); nothing is copied or converted on the server. A MIME
 * item is read as it is. Out comes sanitized HTML with the pictures
 * inlined as data: URIs, within a picture budget per instance (default
 * 2 MB: the page holds them about four times over).
 *
 * Notes-only elements - hotspots, buttons, popups, sections, computed
 * text, pass-thru HTML, paragraphs hidden in Notes, pictures the browser
 * cannot show, elements the renderer does not know - are ISLANDS: shown
 * by their visible content, not editable (contenteditable="false",
 * data-keep="N"), and written back exactly as they were; deleting one in
 * the editor deletes it. What cannot even be kept that way locks the body
 * (locked, and reason says why) - read-only costs an edit in Notes, a
 * wrong unlock costs the Notes original.
 *
 * attachments(dbWebUrl): the web URL of the database holding the
 * documents (https://host/dir/db.nsf) - attachment icons then link to
 * their files. docLinks(replicaId, prefix): a doclink into that replica
 * opens prefix + document UNID (the app's own page); any other doclink
 * becomes a notes:// link.
 *
 * write(): the edited HTML goes back as CLASSIC Notes rich text - DXL
 * generated from the HTML, the original elements reused wherever the
 * page's markers point at them (paragraph pardefs, pictures, doclinks,
 * attachment icons), imported into the document with REPLACE and read
 * back (rolled back if wrong). Notes and the web edit the same format,
 * so a body edited at either end looks the same at the other.
 *
 * Construct it BEFORE opening the documents: it switches the session to
 * setConvertMime(false), without which Domino turns MIME items into
 * classic rich text as they are opened.
 */
public class RichText {

	/* one body, rendered: html for {{&...}}; locked = show it read-only, and
	 * reason says why ("" when not locked), e.g. "paragraphs hidden in Notes" */
	public static final class Body {
		public final String html;
		public final boolean locked;
		public final String reason;

		Body(String html, Set<String> reasons) {
			this.html = html;
			this.locked = !reasons.isEmpty();
			this.reason = String.join(", ", reasons);
		}
	}

	/* a plain-text item (or the fallback of a failed conversion) */
	private static final int MAX_TEXT = 100000;

	/* one body's HTML before its pictures go in (formatting markup is
	 * verbose - several times the plain text) */
	private static final int MAX_HTML = 400000;

	/* a classic item larger than this is not exported: its DXL (pictures
	 * as base64) and the parsed tree take about 24 bytes of heap per byte
	 * of it - so at most a share of the heap, 8 MB on a large one */
	private static final long MAX_ITEM_BYTES = Math.min(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 24);

	/* the icon a doclink without text shows (U+1F4C4); write() reads it back
	 * as "no text" */
	private static final String LINK_ICON = "&#128196;";
	private static final String LINK_ICON_CHARS = new String(Character.toChars(0x1F4C4));

	/* a tab: DXL keeps the character, HTML collapses it - two em spaces
	 * show it, and write() turns them back into the tab */
	private static final String TAB_CHARS = "\u2003\u2003";

	private static final String PICTURE_WITHHELD = "<em class=\"rich-note\">"
			+ "[picture not shown here - open the document in Notes to see it]</em>";

	/* the same note for a Notes picture the page does not show but a save
	 * keeps (inside a <span data-pic>) */
	private static final String PICTURE_KEPT = "[picture not shown here - open the document in Notes to see it]";

	/* the data-pic marker among a picture's attributes */
	private static final Pattern DATA_PIC = Pattern.compile("\\bdata-pic=\"([0-9]{1,6})\"");

	private static final List<String> PICTURE_TYPES = Arrays.asList("png", "gif", "jpeg", "bmp", "webp");

	/* a picture as HtmlSanitizer emits it: src first, double-quoted, the
	 * other attributes after it, closed by " />" */
	private static final Pattern CID_IMG = Pattern.compile("<img src=\"cid:([^\"]*)\"([^>]*)>");

	/* links before and after sanitizing: one the sanitizer refused would be
	 * lost by a web save */
	private static final Pattern RAW_LINK = Pattern.compile("(?i)<a\\s[^>]*?\\bhref\\s*=");
	private static final Pattern CLEAN_LINK = Pattern.compile("<a href=\"");

	private final Session m_session;
	private int m_pictureBudget = 2 * 1024 * 1024;
	private String m_fileDbUrl = null;
	private String m_linkReplica = null;
	private String m_linkPrefix = null;
	private String m_linkView = null;
	private DxlExporter m_exporter = null;
	/* files the editor attached: marker -> {file name, base64} */
	private final java.util.LinkedHashMap<String, String[]> m_newFiles = new java.util.LinkedHashMap<>();

	private int m_pictureBytes = 0;
	private int m_conversions = 0;
	private long m_millis = 0;
	private String m_firstError = null;
	private final Set<String> m_unknown = new TreeSet<>();
	/* the islands of the last classic read, by element name - for report() */
	private List<String> m_lastKept = new ArrayList<>();
	/* report() keeps the item DXL of its read, so it is exported once */
	private boolean m_keepDxl = false;
	private String m_lastDxl = null;
	/* the DXL importer's log of the last import - for writeReport() */
	private String m_importLog = "";
	private String m_writeReport = null;

	public RichText(Session session) {
		m_session = session;
		try {
			session.setConvertMime(false);
		} catch (NotesException e) {
			// MIME items may then arrive converted - they still render
		}
	}

	/* raw picture bytes this instance may inline, over all its reads */
	public RichText pictureBudget(int bytes) {
		m_pictureBudget = bytes;
		return this;
	}

	/* the web URL of the database holding the documents - attachment icons
	 * then link to dbWebUrl/0/<unid>/$FILE/<name>; without it they show
	 * without a link */
	public RichText attachments(String dbWebUrl) {
		m_fileDbUrl = dbWebUrl;
		return this;
	}

	/* a doclink into replicaId opens urlPrefix + document UNID (e.g. the
	 * app's own page for it); other doclinks become notes:// links */
	public RichText docLinks(String replicaId, String urlPrefix) {
		m_linkReplica = replicaId == null ? null : replicaId.replace(":", "");
		m_linkPrefix = urlPrefix;
		return this;
	}

	/* the same, and a link the editor makes to urlPrefix + UNID becomes a
	 * doclink through this view (its UNID) - Notes opens doclinks by view */
	public RichText docLinks(String replicaId, String urlPrefix, String viewUnid) {
		m_linkView = viewUnid;
		return docLinks(replicaId, urlPrefix);
	}

	/* a file the editor attached - its HTML shows it as <span data-file=
	 * "marker">; write() stores it as an attachment of the document, with
	 * an icon where the span stands (the name made unique among the
	 * document's files). A marker the HTML no longer shows is not stored. */
	public RichText attach(String marker, String fileName, String base64) {
		m_newFiles.put(marker, new String[] { fileName, base64 });
		return this;
	}

	/* DXL elements met that the renderer does not know - shown by their
	 * content, and the body locked; for a debug line, empty when none */
	public Set<String> unknownElements() {
		return m_unknown;
	}

	/* for a timing/debug line: conversions made, their time, picture bytes */
	public int conversions() {
		return m_conversions;
	}

	public long millis() {
		return m_millis;
	}

	public int pictureBytes() {
		return m_pictureBytes;
	}

	/* the first conversion failure ("item: message"), null when none - the
	 * caller logs it once, so a server-wide failure cannot write a log
	 * entry per body per page view */
	public String firstError() {
		return m_firstError;
	}

	/* after a write() that threw: what it was given, what it made of it and
	 * what Domino said - plain text for the log, to send to the developer;
	 * null when the last write() succeeded or none ran */
	public String writeReport() {
		return m_writeReport;
	}

	/* ---------------------------------------------------------------- read */

	public Body read(Document doc, String item) throws NotesException {
		Set<String> reasons = new java.util.LinkedHashSet<>();
		String html = render(doc, item, reasons);
		return new Body(html, reasons);
	}

	/* reasons: why the body must be read-only - empty when it can be edited */
	private String render(Document doc, String item, Set<String> reasons) throws NotesException {
		Item it = doc.getFirstItem(item);
		if (it == null) {
			return "";
		}
		long start = System.currentTimeMillis();
		try {
			int type = it.getType();
			if (type == Item.MIME_PART || type == Item.RICHTEXT) {
				m_conversions++;
				try {
					String html = type == Item.MIME_PART
							? mimeHtml(doc.getMIMEEntity(item), reasons)
							: classicHtml(doc, item, (RichTextItem) it, reasons);
					if (html != null) {
						if (type == Item.MIME_PART) {
							// a web save would store classic rich text next to the
							// note's MIME flags ($NoteHasNativeMIME) - not verified
							reasons.add("a MIME (mail-style) body");
						}
						return html;
					}
				} catch (Exception e) {
					// the page still renders: plain text, read-only
					if (m_firstError == null) {
						m_firstError = item + ": " + e.getMessage();
					}
				}
				if (reasons.isEmpty()) {
					reasons.add("content the web page could not read");
				}
			}
		} finally {
			recycle(it);
			m_millis += System.currentTimeMillis() - start;
		}
		return plainHtml(doc, item, reasons);
	}

	/*
	 * Classic rich text -> HTML from its DXL. The exporter is restricted to
	 * this one item, so no other item and no file data travels; it reads
	 * the document, it does not change it. null = show it as plain text,
	 * locked.
	 */
	private String classicHtml(Document doc, String item, RichTextItem rt, Set<String> reasons) throws Exception {
		if (itemBytes(doc, item) > MAX_ITEM_BYTES) {
			reasons.add("more than " + (MAX_ITEM_BYTES / 1024 / 1024) + " MB of content");
			return null;
		}
		// attachments round-trip (write() keeps their attachmentref and $FILE);
		// OLE objects do not, and attachments need attachments() to be linked
		for (Object o : rt.getEmbeddedObjects()) {
			lotus.domino.EmbeddedObject embedded = (lotus.domino.EmbeddedObject) o;
			try {
				int type = embedded.getType();
				if (type != lotus.domino.EmbeddedObject.EMBED_ATTACHMENT) {
					reasons.add("an embedded OLE object");
				} else if (m_fileDbUrl == null) {
					reasons.add("attachments without a file link");
				}
			} catch (NotesException dead) {
				reasons.add("a broken attachment"); // a dangling hotspot
			} finally {
				recycle(embedded);
			}
		}
		DxlExporter exporter = exporter();
		Vector<String> names = new Vector<>();
		names.add(item);
		exporter.setRestrictToItemNames(names);
		String dxl = exporter.exportDxl(doc);
		if (m_keepDxl) {
			m_lastDxl = dxl;
		}

		String fileBase = m_fileDbUrl == null ? null : m_fileDbUrl + "/0/" + doc.getUniversalID() + "/$FILE/";
		Object[] r = renderDxl(dxl, item, fileBase, m_linkReplica, m_linkPrefix,
				m_pictureBudget - m_pictureBytes, m_unknown);
		String html = (String) r[0];
		m_pictureBytes += (Integer) r[2];
		@SuppressWarnings("unchecked")
		Set<String> found = (Set<String>) r[1];
		reasons.addAll(found);
		@SuppressWarnings("unchecked")
		List<String> kept = (List<String>) r[3];
		m_lastKept = kept;
		// a safety net: HTML with clearly less text than Notes holds lost
		// something - plain text instead
		if (htmlLetters(html) < letters(rt.getUnformattedText()) * 9 / 10) {
			reasons.add("text the web page could not place");
			return null;
		}
		return html;
	}

	/* a large rich-text field is stored as several items of the same name;
	 * the size is their sum. The wrappers getItems() hands out share their
	 * backend objects with the caller's own RichTextItem, so they are NOT
	 * recycled here - that killed the caller's ("Object has been removed or
	 * recycled", live on 2026-09-29); the session frees them */
	private static long itemBytes(Document doc, String item) throws NotesException {
		long bytes = 0;
		for (Object o : doc.getItems()) {
			Item it = (Item) o;
			if (item.equalsIgnoreCase(it.getName())) {
				bytes += it.getValueLength();
			}
		}
		return bytes;
	}

	private DxlExporter exporter() throws NotesException {
		if (m_exporter == null) {
			m_exporter = m_session.createDxlExporter();
			m_exporter.setOutputDOCTYPE(false);
			m_exporter.setConvertNotesBitmapsToGIF(true);
			m_exporter.setRichTextOption(DxlExporter.DXLRICHTEXTOPTION_DXL);
			m_exporter.setOmitOLEObjects(true);
			m_exporter.setOmitMiscFileObjects(true);
		}
		return m_exporter;
	}

	/*
	 * The DXL of one item -> {html, Set<String> lock reasons, Integer
	 * pictureBytes, List<String> islands by element name}: rendered,
	 * sanitized, pictures inlined. Static and server-free, so RichTextCheck
	 * tests it with DXL fixtures.
	 */
	private static Object[] renderDxl(String dxl, String item, String fileBase, String linkReplica,
			String linkPrefix, int budget, Set<String> unknown) throws Exception {
		DxlBody body = new DxlBody(fileBase, linkReplica, linkPrefix, budget, unknown);
		String html = body.render(dxl, item);
		if (html.length() > MAX_HTML) {
			html = html.substring(0, MAX_HTML);
			body.reasons.add("text too long for the web editor");
		}
		String out = inlinePictures(HtmlSanitizer.clean(html), body.pictures, body.pictures.size(), body.reasons);
		List<String> kept = new ArrayList<>();
		for (Element e : body.keepElements) {
			kept.add(e.getNodeName());
		}
		return new Object[] { out, body.reasons, body.pictureBytes, kept };
	}

	/* Notes' named colours (its "green" is CSS lime, its "darkgreen" CSS green) */
	private static final HashMap<String, String> NOTES_COLORS = new HashMap<>();
	static {
		String[] pairs = { "black", "#000000", "white", "#ffffff", "red", "#ff0000", "green", "#00ff00",
				"blue", "#0000ff", "magenta", "#ff00ff", "yellow", "#ffff00", "cyan", "#00ffff",
				"darkred", "#800000", "darkgreen", "#008000", "darkblue", "#000080", "darkmagenta", "#800080",
				"darkyellow", "#808000", "darkcyan", "#008080", "gray", "#808080", "silver", "#c0c0c0" };
		for (int i = 0; i < pairs.length; i += 2) {
			NOTES_COLORS.put(pairs[i], pairs[i + 1]);
		}
	}

	/* CSS generic families: a font of that name in Notes is the family */
	private static final Set<String> GENERIC_FONTS = new HashSet<>(Arrays.asList(
			"serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui"));

	private static final Set<String> BULLET_LISTS = new HashSet<>(Arrays.asList(
			"bullet", "square", "circle", "check", "uncheck"));
	private static final Set<String> NUMBER_LISTS = new HashSet<>(Arrays.asList(
			"number", "alphaupper", "alphalower", "romanupper", "romanlower"));

	/*
	 * One item's DXL rendered as HTML. Everything it emits is escaped text
	 * or markup it builds itself - and only what HtmlSanitizer keeps, so a
	 * web save round-trips it; the result still goes through the sanitizer.
	 * Pictures are emitted as cid: references with the data kept aside
	 * (inlinePictures puts them in after sanitizing). An element the editor
	 * cannot edit becomes an island (island()), kept whole by write(); an
	 * element it does not know is an island too, and recorded in unknown.
	 * What not even an island can keep adds a lock reason.
	 */
	private static final class DxlBody {
		final StringBuilder out = new StringBuilder();
		final HashMap<String, String> pictures = new HashMap<>();
		final HashMap<String, Element> pardefs = new HashMap<>();
		final String fileBase;
		final String linkReplica;
		final String linkPrefix;
		final Set<String> unknown;
		int budget;
		int pictureBytes = 0;
		/* why the body must stay read-only - empty when the editor may take it */
		final Set<String> reasons = new java.util.LinkedHashSet<>();
		int nextPicture = 0;
		/* Notes-only elements kept whole ("islands"), by their data-keep number */
		final List<Element> keepElements = new ArrayList<>();
		/* whether each island is a block (div) or inline (span) */
		final List<Boolean> keepBlock = new ArrayList<>();
		/* what write() maps the edited HTML back to: pictures by their
		 * data-pic number (index + 1), links by rendered href, attachments
		 * by file name - pardefs are in pardefs by id */
		final List<Element> pictureElements = new ArrayList<>();
		final List<Element> tableElements = new ArrayList<>();
		final HashMap<String, Element> linkElements = new HashMap<>();
		final HashMap<String, Element> attachmentElements = new HashMap<>();

		DxlBody(String fileBase, String linkReplica, String linkPrefix, int budget, Set<String> unknown) {
			this.fileBase = fileBase;
			this.linkReplica = linkReplica;
			this.linkPrefix = linkPrefix;
			this.budget = budget;
			this.unknown = unknown;
		}

		String render(String dxl, String item) throws Exception {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setExpandEntityReferences(false);
			try {
				factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			} catch (Exception e) {
				// a parser without the feature - the exporter writes no DOCTYPE anyway
			}
			DocumentBuilder builder = factory.newDocumentBuilder();
			org.w3c.dom.Document xml = builder.parse(new InputSource(new StringReader(dxl)));

			Element richtext = null;
			org.w3c.dom.NodeList items = xml.getElementsByTagName("item");
			for (int i = 0; i < items.getLength() && richtext == null; i++) {
				Element e = (Element) items.item(i);
				if (item.equalsIgnoreCase(e.getAttribute("name"))) {
					richtext = child(e, "richtext");
				}
			}
			if (richtext == null) {
				return "";
			}
			org.w3c.dom.NodeList defs = richtext.getElementsByTagName("pardef");
			for (int i = 0; i < defs.getLength(); i++) {
				Element def = (Element) defs.item(i);
				pardefs.put(def.getAttribute("id"), def);
			}
			blocks(richtext, false);
			return out.toString();
		}

		/* paragraphs, tables and sections; consecutive list paragraphs share one list */
		void blocks(Element parent, boolean inCell) {
			String list = null;
			for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n.getNodeType() != Node.ELEMENT_NODE) {
					continue; // whitespace between blocks
				}
				Element e = (Element) n;
				String name = e.getNodeName();
				if ("par".equals(name)) {
					Element def = pardefs.get(e.getAttribute("def"));
					if (hidden(def)) {
						// Notes readers do not see it: an island the page CSS
						// hides when reading and shows dimmed in the editor
						if (list != null) {
							out.append("</").append(list, 0, 2).append('>');
							list = null;
						}
						island(e, true, inCell, "");
						continue;
					}
					String want = listTag(def);
					String kind = def == null ? "" : def.getAttribute("list");
					if (want == null ? list != null : !(want + kind).equals(list)) {
						if (list != null) {
							out.append("</").append(list, 0, 2).append('>');
						}
						if (want != null) {
							// data-list: the Notes list type (letters, roman, check...),
							// shown by the page CSS and written back as it is
							out.append('<').append(want).append(kind.matches("[a-z]{1,12}") && !kind.equals(want.equals("ul") ? "bullet" : "number")
									? " data-list=\"" + kind + "\"" : "").append('>');
						}
						list = want == null ? null : want + kind;
					}
					String tag = want == null ? "div" : "li";
					// data-pd: the paragraph's own pardef, so write() gives it
					// back exactly (spacing, tabs, margins) - the editor keeps
					// the attribute, and a paragraph split with Enter inherits it
					String pd = def == null ? "" : def.getAttribute("id");
					out.append('<').append(tag).append(want == null ? parStyle(def, inCell) : "")
							.append(pd.matches("[0-9]{1,6}") ? " data-pd=\"" + pd + "\"" : "").append('>');
					int mark = out.length();
					inline(e);
					if (out.length() == mark) {
						out.append("<br>"); // an empty paragraph keeps its line
					}
					out.append("</").append(tag).append('>');
					continue;
				}
				if (list != null) {
					out.append("</").append(list, 0, 2).append('>');
					list = null;
				}
				if ("pardef".equals(name) || "sectiontitle".equals(name)) {
					continue;
				} else if ("table".equals(name)) {
					table(e);
				} else if ("horizrule".equals(name)) {
					out.append("<hr>");
				} else {
					// a section, or a block the renderer does not know: kept whole
					if (!"section".equals(name)) {
						unknown.add(name);
					}
					island(e, true, inCell, "");
				}
			}
			if (list != null) {
				out.append("</").append(list, 0, 2).append('>');
			}
		}

		void inline(Node parent) {
			for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
				short type = n.getNodeType();
				if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
					text(n.getNodeValue());
					continue;
				}
				if (type != Node.ELEMENT_NODE) {
					continue;
				}
				Element e = (Element) n;
				String name = e.getNodeName();
				switch (name) {
				case "run":
					run(e);
					break;
				case "break":
					out.append("<br>");
					break;
				case "tab":
					out.append("&nbsp;&nbsp;&nbsp;&nbsp;");
					break;
				case "picture":
					picture(e, "");
					break;
				case "urllink":
					urlLink(e);
					break;
				case "doclink":
				case "viewlink":
				case "databaselink":
					notesLink(e);
					break;
				case "attachmentref":
					attachment(e);
					break;
				case "horizrule":
					out.append("<hr>");
					break;
				case "text":
					inline(e); // a section title's words
					break;
				case "font":
				case "code":
				case "popuptext":
				case "area":
					break; // styles its run / no visible content
				case "objectref":
					// its data is left out of the read export, so its place in
					// the island numbering would differ from the save's export
					reasons.add("an embedded OLE object");
					text("[embedded object]");
					break;
				case "computedtext":
					island(e, false, false, "[computed text]");
					break;
				case "anchor":
				case "actionhotspot":
				case "popup":
				case "button":
				case "namedelementlink":
				case "imagemap":
					// the visible part shows, the behaviour stays in Notes -
					// kept whole, written back as it was
					island(e, false, false, "");
					break;
				default:
					unknown.add(name);
					island(e, false, false, "");
				}
			}
		}

		/* text as it is: escaped, runs of spaces kept */
		void text(String s) {
			if (s.indexOf('\n') >= 0 && s.trim().isEmpty()) {
				return; // layout whitespace of the DXL itself
			}
			boolean space = false;
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '\t') {
					// DXL keeps a tab as the character; HTML would collapse it.
					// Two em spaces show it and are read back as a tab (TAB).
					out.append("&#8195;&#8195;");
					space = false;
					continue;
				}
				if (c == '\r' || c == '\n') {
					c = ' ';
				}
				if (c == ' ') {
					out.append(space ? "&nbsp;" : " ");
					space = true;
					continue;
				}
				space = false;
				if (c == '&') {
					out.append("&amp;");
				} else if (c == '<') {
					out.append("&lt;");
				} else if (c == '>') {
					out.append("&gt;");
				} else {
					out.append(c);
				}
			}
		}

		/*
		 * A Notes-only element kept whole: shown by its visible content (or
		 * label, or nothing - the editor CSS then names its data-kind), not
		 * editable, and written back by write() exactly as it was. data-keep
		 * is its place in keepElements; write() also checks data-kind, the
		 * element's name, so a stale number cannot write the wrong element.
		 */
		void island(Element e, boolean block, boolean inCell, String label) {
			keepElements.add(e);
			keepBlock.add(block);
			String name = e.getNodeName();
			String tag = block ? "div" : "span";
			out.append('<').append(tag).append(" data-keep=\"").append(keepElements.size())
					.append("\" data-kind=\"").append(kindOf(e))
					.append("\" contenteditable=\"false\">");
			int mark = out.length();
			if ("section".equals(name)) {
				// shown expanded, the title bold
				Element title = child(e, "sectiontitle");
				if (title != null) {
					out.append("<div><b>");
					inline(title);
					out.append("</b></div>");
				}
				blocks(e, inCell);
			} else if (block && !"par".equals(name)) {
				blocks(e, inCell);
			} else {
				inline(e);
			}
			if (out.length() == mark) {
				text(label);
			}
			out.append("</").append(tag).append('>');
		}

		void run(Element run) {
			if ("true".equals(run.getAttribute("html"))) {
				// pass-thru HTML: its text shows, the flag goes back with it
				island(run, false, false, "");
				return;
			}
			Element font = child(run, "font");
			StringBuilder css = new StringBuilder();
			List<String> tags = new ArrayList<>();
			if (font != null) {
				String face = fontFamily(font.getAttribute("name"));
				if (!face.isEmpty()) {
					css.append("font-family: ").append(face).append("; ");
				}
				String size = font.getAttribute("size");
				if (size.matches("[0-9]{1,3}(\\.[0-9]+)?pt")) {
					css.append("font-size: ").append(size).append("; ");
				}
				String color = color(font.getAttribute("color"));
				if (!color.isEmpty()) {
					css.append("color: ").append(color).append("; ");
				}
				for (String style : font.getAttribute("style").split("\\s+")) {
					String tag = "bold".equals(style) ? "b" : "italic".equals(style) ? "i"
							: "underline".equals(style) ? "u" : "strikethrough".equals(style) ? "s"
							: "superscript".equals(style) ? "sup" : "subscript".equals(style) ? "sub" : null;
					if (tag != null) {
						tags.add(tag);
					}
				}
			}
			String highlight = run.getAttribute("highlight");
			if (!highlight.isEmpty()) {
				String bg = "pink".equals(highlight) ? "#ffc0cb" : "blue".equals(highlight) ? "#add8e6" : "#ffff00";
				css.append("background-color: ").append(bg).append("; ");
			}
			if (css.length() > 0) {
				out.append("<span style=\"").append(css.toString().trim()).append("\">");
			}
			for (String tag : tags) {
				out.append('<').append(tag).append('>');
			}
			inline(run);
			for (int i = tags.size() - 1; i >= 0; i--) {
				out.append("</").append(tags.get(i)).append('>');
			}
			if (css.length() > 0) {
				out.append("</span>");
			}
		}

		/* alt: used when the picture has no alttext of its own. data-pic is
		 * the picture's place in the item, so write() gives the original
		 * picture back (a Notes bitmap stays a Notes bitmap) */
		void picture(Element pic, String alt) {
			pictureElements.add(pic);
			String cid = "p" + (++nextPicture);
			Element data = null;
			String type = null;
			for (Node n = pic.getFirstChild(); n != null && data == null; n = n.getNextSibling()) {
				if (n.getNodeType() == Node.ELEMENT_NODE) {
					String name = n.getNodeName();
					if ("gif".equals(name) || "jpeg".equals(name) || "png".equals(name) || "bmp".equals(name)) {
						data = (Element) n;
						type = name;
					}
				}
			}
			if (data != null) {
				String base64 = data.getTextContent().replaceAll("\\s", "");
				int bytes = base64.length() / 4 * 3;
				if (bytes <= budget && base64.matches("[A-Za-z0-9+/=]+")) {
					budget -= bytes;
					pictureBytes += bytes;
					pictures.put(cid, "data:image/" + type + ";base64," + base64);
				}
			}
			// no entry (over the budget; cgm, imageref, a Notes bitmap left
			// over): inlinePictures shows a note that keeps the picture's
			// data-pic, so write() still gives the picture back
			out.append("<img src=\"cid:").append(cid).append('"');
			String w = px(first(pic.getAttribute("scaledwidth"), pic.getAttribute("width")));
			String h = px(first(pic.getAttribute("scaledheight"), pic.getAttribute("height")));
			if (!w.isEmpty()) {
				out.append(" width=\"").append(w).append('"');
			}
			if (!h.isEmpty()) {
				out.append(" height=\"").append(h).append('"');
			}
			String text = first(pic.getAttribute("alttext"), alt);
			if (!text.isEmpty()) {
				out.append(" alt=\"").append(attr(text)).append('"');
			}
			out.append(" data-pic=\"").append(nextPicture).append("\">");
			Element caption = child(pic, "caption");
			if (caption != null) {
				// part of the picture: data-cap tells write() not to add it again
				out.append("<span data-cap=\"").append(nextPicture).append("\"><br>");
				inline(caption);
				out.append("</span>");
			}
		}

		void urlLink(Element link) {
			String href = link.getAttribute("href").trim();
			if (href.isEmpty() || !HtmlSanitizer.isSafeUrl(href)) {
				// the sanitizer would drop the href: its text shows, kept whole
				island(link, false, false, "");
				return;
			}
			out.append("<a href=\"").append(attr(href)).append("\">");
			inline(link);
			out.append("</a>");
		}

		/* doclink / viewlink / databaselink as a link the browser can follow;
		 * write() maps the href back to the original element */
		void notesLink(Element link) {
			String replica = link.getAttribute("database").replace(":", "");
			String view = link.getAttribute("view");
			String doc = link.getAttribute("document");
			String href;
			if (linkPrefix != null && !doc.isEmpty() && replica.equalsIgnoreCase(linkReplica)) {
				href = linkPrefix + doc;
			} else {
				href = "notes://" + commonName(link.getAttribute("server")) + "/" + replica
						+ (doc.isEmpty() ? (view.isEmpty() ? "" : "/" + view) : "/" + (view.isEmpty() ? "0" : view) + "/" + doc);
			}
			linkElements.put(href, link);
			out.append("<a href=\"").append(attr(href)).append("\">");
			int mark = out.length();
			inline(link);
			if (out.length() == mark) {
				out.append(LINK_ICON); // Notes shows a link icon
			}
			out.append("</a>");
		}

		/* the icon Notes shows, linked to the file when attachments() is set.
		 * The file name is usually drawn into the icon itself (or given as a
		 * caption), so it goes in as alt text, not a second time as text.
		 * write() keeps the original attachmentref for that link, and the
		 * file; without attachments() nothing links it, so it locks. */
		void attachment(Element ref) {
			String name = ref.getAttribute("name");
			String label = first(ref.getAttribute("displayname"), name);
			boolean linked = fileBase != null && !name.isEmpty();
			if (!linked) {
				reasons.add("attachments without a file link");
			}
			attachmentElements.put(name, ref);
			if (linked) {
				out.append("<a href=\"").append(attr(fileBase + urlEncode(name))).append("\">");
			}
			Element pic = child(ref, "picture");
			if (pic == null) {
				out.append("&#128206; ");
				text(label);
			} else {
				picture(pic, label);
			}
			if (linked) {
				out.append("</a>");
			}
		}

		void table(Element table) {
			String border = color(table.getAttribute("cellbordercolor"));
			if (border.isEmpty()) {
				border = "#000000";
			}
			boolean noBorders = "none".equals(table.getAttribute("cellborderstyle"));
			List<String> widths = new ArrayList<>();
			for (Node n = table.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n.getNodeType() == Node.ELEMENT_NODE && "tablecolumn".equals(n.getNodeName())) {
					widths.add(px(((Element) n).getAttribute("width")));
				}
			}
			String type = table.getAttribute("widthtype");
			String width = type.startsWith("fit") ? "100%" : px(table.getAttribute("refwidth"));
			// data-tbl: the table's place in the item, so write() keeps its
			// settings (border style, colours, tabs, margins) that HTML has no
			// place for
			tableElements.add(table);
			out.append("<table style=\"border-collapse: collapse")
					.append(width.isEmpty() ? "" : "; width: " + (width.endsWith("%") ? width : width + "px"))
					.append("\" data-tbl=\"").append(tableElements.size()).append("\">");
			for (Node r = table.getFirstChild(); r != null; r = r.getNextSibling()) {
				if (r.getNodeType() != Node.ELEMENT_NODE || !"tablerow".equals(r.getNodeName())) {
					continue;
				}
				out.append("<tr>");
				int col = 0;
				for (Node c = r.getFirstChild(); c != null; c = c.getNextSibling()) {
					if (c.getNodeType() != Node.ELEMENT_NODE || !"tablecell".equals(c.getNodeName())) {
						continue;
					}
					Element cell = (Element) c;
					int span = number(first(cell.getAttribute("columnspan"), cell.getAttribute("colspan")));
					int rows = number(cell.getAttribute("rowspan"));
					String bw = cell.getAttribute("borderwidth");
					if (noBorders) {
						bw = "0px";
					} else if (!bw.matches("[0-9.]+px( [0-9.]+px){0,3}")) {
						bw = "1px";
					}
					out.append("<td");
					if (span > 1) {
						out.append(" colspan=\"").append(span).append('"');
					}
					if (rows > 1) {
						out.append(" rowspan=\"").append(rows).append('"');
					}
					out.append(" style=\"border-style: solid; border-color: ").append(border)
							.append("; border-width: ").append(bw).append("; padding: 2px 4px; vertical-align: top");
					if (col < widths.size() && !widths.get(col).isEmpty() && span <= 1) {
						out.append("; width: ").append(widths.get(col)).append("px");
					}
					String bg = color(cell.getAttribute("bgcolor"));
					if (!bg.isEmpty()) {
						out.append("; background-color: ").append(bg);
					}
					out.append("\">");
					int mark = out.length();
					blocks(cell, true);
					if (out.length() == mark) {
						out.append("&nbsp;");
					}
					out.append("</td>");
					col += Math.max(span, 1);
				}
				out.append("</tr>");
			}
			out.append("</table>");
		}

		/* an island's data-kind: the element's DXL name */
		static String kindOf(Element e) {
			String name = e.getNodeName();
			return name.matches("[a-z]{1,30}") ? name : "element";
		}

		/* hidden when Notes hides it while reading */
		static boolean hidden(Element def) {
			if (def == null) {
				return false;
			}
			for (String h : def.getAttribute("hide").split("\\s+")) {
				if ("notes".equals(h) || "read".equals(h)) {
					return true;
				}
			}
			return false;
		}

		static String listTag(Element def) {
			if (def == null) {
				return null;
			}
			String list = def.getAttribute("list");
			return BULLET_LISTS.contains(list) ? "ul" : NUMBER_LISTS.contains(list) ? "ol" : null;
		}

		/* alignment, and the indent beyond Notes' default 1in left margin */
		static String parStyle(Element def, boolean inCell) {
			if (def == null) {
				return "";
			}
			StringBuilder css = new StringBuilder();
			String align = def.getAttribute("align");
			if ("center".equals(align) || "right".equals(align)) {
				css.append("text-align: ").append(align);
			} else if ("full".equals(align)) {
				css.append("text-align: justify");
			}
			if (!inCell) {
				double indent = inches(def.getAttribute("leftmargin")) - 1.0;
				if (indent > 0.01) {
					css.append(css.length() > 0 ? "; " : "").append("margin-left: ").append(Math.round(indent * 96)).append("px");
				}
			}
			return css.length() == 0 ? "" : " style=\"" + css + "\"";
		}

		static String fontFamily(String name) {
			if (name == null || name.isEmpty()) {
				return "";
			}
			if ("Default Sans Serif".equals(name)) {
				return "Arial, sans-serif";
			}
			if ("Default Serif".equals(name)) {
				return "'Times New Roman', serif";
			}
			if ("Default Monospace".equals(name)) {
				return "'Courier New', monospace";
			}
			if (GENERIC_FONTS.contains(name.toLowerCase())) {
				return name.toLowerCase(); // quoted it would name a font called "serif"
			}
			return name.matches("[A-Za-z0-9 -]{1,60}") ? "'" + name + "'" : "";
		}

		static String color(String c) {
			if (c == null || c.isEmpty()) {
				return "";
			}
			String named = NOTES_COLORS.get(c.toLowerCase());
			if (named != null) {
				return named;
			}
			return c.matches("#[0-9A-Fa-f]{6}") ? c : "";
		}

		/* a DXL length (in, cm, pt, px) as whole pixels, "" when unreadable */
		static String px(String length) {
			Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(in|cm|pt|px)?").matcher(length == null ? "" : length.trim());
			if (!m.matches()) {
				return "";
			}
			double v = Double.parseDouble(m.group(1));
			String unit = m.group(2) == null ? "px" : m.group(2);
			double px = "in".equals(unit) ? v * 96 : "cm".equals(unit) ? v * 96 / 2.54 : "pt".equals(unit) ? v * 4 / 3 : v;
			long r = Math.round(px);
			return r > 0 && r < 10000 ? String.valueOf(r) : "";
		}

		static double inches(String length) {
			String p = px(length);
			return p.isEmpty() ? 1.0 : Integer.parseInt(p) / 96.0;
		}

		static int number(String s) {
			try {
				return Integer.parseInt(s.trim());
			} catch (Exception e) {
				return 1;
			}
		}

		/* CN=server1/O=Org -> server1 (the server part of a notes:// URL) */
		static String commonName(String server) {
			String s = server == null ? "" : server.trim();
			int slash = s.indexOf('/');
			if (slash >= 0) {
				s = s.substring(0, slash);
			}
			return s.startsWith("CN=") ? s.substring(3) : s;
		}

		static String urlEncode(String s) {
			try {
				return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
			} catch (java.io.UnsupportedEncodingException e) {
				return s;
			}
		}


		static String attr(String v) {
			return v.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
		}

		static Element child(Element parent, String name) {
			for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
					return (Element) n;
				}
			}
			return null;
		}
	}

	/*
	 * A MIME body -> sanitized HTML with its pictures inlined, in one walk
	 * of the entity tree: the first text/html part is the body (text/plain
	 * stands in when there is none), picture parts are kept by Content-ID
	 * and put in place of their cid: references AFTER sanitizing, so the
	 * large data: strings never go through the sanitizer's scans. null when
	 * there is no entity, or pictures but no text part to place them in.
	 *
	 * Locks, because write() puts back only one text/html part and the
	 * pictures it references: a second text/html part, any part that is not
	 * text or a picture (a file), a picture nothing references, a link the
	 * sanitizer refused, and text cut at MAX_HTML.
	 */
	private String mimeHtml(MIMEEntity root, Set<String> reasons) throws NotesException {
		if (root == null) {
			return null;
		}
		String html = null;
		String plain = null;
		int pictureParts = 0;
		HashMap<String, String> pictures = new HashMap<>();
		try {
			// depth-first over the whole tree; the entities go with their
			// document (the scratch copy, or the caller's)
			MIMEEntity e = root;
			while (e != null) {
				String type = lower(e.getContentType());
				String sub = lower(e.getContentSubType());
				if ("text".equals(type) && "html".equals(sub)) {
					if (html == null) {
						html = entityText(e);
					} else {
						reasons.add("a second HTML part");
					}
				} else if ("text".equals(type) && "plain".equals(sub)) {
					if (plain == null) {
						plain = entityText(e);
					}
				} else if ("image".equals(type)) {
					pictureParts++;
					String cid = contentId(e);
					String uri = cid == null ? null : dataUri(e, sub);
					if (uri != null) {
						pictures.put(cid, uri);
					}
				} else if (!"multipart".equals(type)) {
					reasons.add("a file inside the body");
				}
				MIMEEntity next = e.getNextEntity();
				if (e != root) {
					recycle(e);
				}
				e = next;
			}
		} finally {
			recycle(root);
		}
		String body;
		if (html != null) {
			body = html;
		} else if (plain != null) {
			body = escape(plain).replace("\n", "<br>");
		} else {
			return pictureParts == 0 ? "" : null;
		}
		if (body.length() > MAX_HTML) {
			body = body.substring(0, MAX_HTML);
			reasons.add("text too long for the web editor");
		}
		String clean = HtmlSanitizer.clean(body);
		if (count(RAW_LINK, body) > count(CLEAN_LINK, clean)) {
			reasons.add("a link the web cannot hold");
		}
		return inlinePictures(clean, pictures, pictureParts, reasons);
	}

	/* escaped text with line breaks; a reason when it had to be cut */
	private static String plainHtml(Document doc, String item, Set<String> reasons) throws NotesException {
		Item it = doc.getFirstItem(item);
		if (it == null) {
			return "";
		}
		try {
			String text = it.getText();
			if (text == null || text.isEmpty()) {
				return "";
			}
			if (text.length() > MAX_TEXT) {
				text = text.substring(0, MAX_TEXT) + " [...]";
				reasons.add("text too long for the web editor");
			}
			return escape(text).replace("\n", "<br>");
		} finally {
			recycle(it);
		}
	}

	/* a text part as a string; quoted-printable/base64 content is decoded
	 * in memory first, so the result never depends on whether
	 * getContentAsText decodes (nothing here saves the document) */
	private static String entityText(MIMEEntity e) throws NotesException {
		int encoding = e.getEncoding();
		if (encoding == MIMEEntity.ENC_QUOTED_PRINTABLE || encoding == MIMEEntity.ENC_BASE64) {
			e.decodeContent();
		}
		String s = e.getContentAsText();
		return s == null ? "" : s;
	}

	/* the part's Content-ID without its angle brackets, null when absent */
	private static String contentId(MIMEEntity e) throws NotesException {
		MIMEHeader header = e.getNthHeader("Content-ID");
		if (header == null) {
			return null;
		}
		String id = header.getHeaderVal();
		recycle(header);
		if (id == null) {
			return null;
		}
		id = id.trim();
		if (id.startsWith("<") && id.endsWith(">")) {
			id = id.substring(1, id.length() - 1);
		}
		return id.isEmpty() ? null : id;
	}

	/* one picture part as a data: URI, or null when it is not a format a
	 * browser shows or it does not fit the budget */
	private String dataUri(MIMEEntity e, String sub) throws NotesException {
		String type = "jpg".equals(sub) ? "jpeg" : sub;
		if (!PICTURE_TYPES.contains(type)) {
			return null;
		}
		Stream stream = m_session.createStream();
		try {
			e.getContentAsBytes(stream, true);
			int size = stream.getBytes();
			if (size <= 0 || m_pictureBytes + size > m_pictureBudget) {
				return null;
			}
			m_pictureBytes += size;
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(size);
			stream.setPosition(0);
			stream.getContents(bytes);
			return "data:image/" + type + ";base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
		} finally {
			stream.close();
			recycle(stream);
		}
	}

	/* cid: references -> the data: URIs collected for them. A reference
	 * with no picture (withheld by the budget, an unsupported format, a
	 * missing part) becomes a visible note: an island that keeps the
	 * picture's data-pic when it has one (write() then gives the Notes
	 * picture back), else a note that locks - as does a picture part that
	 * no reference shows. */
	private static String inlinePictures(String html, HashMap<String, String> pictures, int pictureParts,
			Set<String> reasons) {
		Matcher m = CID_IMG.matcher(html);
		HashSet<String> shown = new HashSet<>();
		StringBuilder out = null;
		int last = 0;
		while (m.find()) {
			if (out == null) {
				out = new StringBuilder(html.length() + 4096);
			}
			out.append(html, last, m.start());
			String uri = pictures.get(m.group(1));
			Matcher pic = uri == null ? DATA_PIC.matcher(m.group(2)) : null;
			if (pic != null && pic.find()) {
				out.append("<span data-pic=\"").append(pic.group(1)).append("\" contenteditable=\"false\">")
						.append(PICTURE_KEPT).append("</span>");
			} else if (uri == null) {
				reasons.add("a picture the web page cannot show");
				out.append(PICTURE_WITHHELD);
			} else {
				shown.add(m.group(1));
				out.append("<img src=\"").append(uri).append('"').append(m.group(2)).append('>');
			}
			last = m.end();
		}
		if (shown.size() < pictureParts) {
			reasons.add("a picture outside the text");
		}
		if (out == null) {
			return html;
		}
		return out.append(html, last, html.length()).toString();
	}

	/* --------------------------------------------------------------- write */

	/* a document whose attachments total more than this is not re-saved from
	 * the web: the whole document travels as DXL, its files as base64, held
	 * as Java strings several times over (about 25 bytes of heap per byte of
	 * file). Domino's HTTP JVM has 64 MB unless HTTPJVMMaxHeapSize says
	 * more - raise this only after measuring that server */
	private static final int MAX_WRITE_FILE_BYTES = 2 * 1024 * 1024;

	/*
	 * Save posted editor HTML into item AS CLASSIC NOTES RICH TEXT - the
	 * format the Notes client itself writes, so the body is edited from both
	 * ends with the same result:
	 *  1. the whole document is exported as DXL, attachments included;
	 *  2. the HTML becomes a DXL <richtext> (DxlOut) that reuses the ORIGINAL
	 *     element wherever the HTML still points at one - a paragraph's
	 *     pardef (data-pd), a picture (data-pic), a doclink or attachment
	 *     icon (its href) - so what was not edited goes back as it was;
	 *  3. that item is swapped in the DXL, and the $FILE of an attachment
	 *     whose icon was deleted goes too, as when it is deleted in Notes;
	 *  4. the document is imported back with REPLACE and read back: form,
	 *     item and attachments as expected - else the original DXL goes back
	 *     in and the save reports it.
	 * The caller saves its own item changes BEFORE calling this. doc is
	 * recycled here - the import replaces the note under it.
	 */
	public void write(Document doc, String item, String postedHtml) throws Exception {
		m_writeReport = null;
		m_importLog = "";
		String html = null;
		String unid = "";
		String form = "";
		String original = null;
		String richtext = null;
		try {
			html = HtmlSanitizer.clean(postedHtml);
			lotus.domino.Database db = doc.getParentDatabase();
			unid = doc.getUniversalID();
			form = doc.getItemValueString("Form");
			Set<String> filesBefore = requireWithinLimits(doc);
			recycle(doc); // no stale handle may stay open under the import

			// 1. the whole document as DXL, and when that was - from a fresh
			// handle, so the stamp and the export come from the same state
			java.util.Date exportedAt;
			Document fresh = db.getDocumentByUNID(unid);
			try {
				exportedAt = lastModified(fresh);
				original = exportWhole(fresh);
			} finally {
				recycle(fresh);
			}
			requireExportable(original);

			// 2. the HTML as rich text, the originals reused; 3. the item
			// swapped, the files of deleted icons dropped, new files added
			String fileBase = m_fileDbUrl == null ? null : m_fileDbUrl + "/0/" + unid + "/$FILE/";
			DxlBody originals = originals(original, item, fileBase, m_linkReplica, m_linkPrefix);
			DxlOut out = new DxlOut(originals, fileBase, m_linkReplica, m_linkPrefix);
			out.linkView = m_linkView;
			out.newFiles = m_newFiles;
			out.taken.addAll(filesBefore);
			richtext = out.write(html);
			// one buffer for the changed document: the DXL is large
			StringBuilder changed = swapItemIn(original, item, richtext);
			Set<String> expected = new TreeSet<>(filesBefore);
			for (String name : originals.attachmentElements.keySet()) {
				if (!out.files.contains(name)) {
					dropFileIn(changed, name);
					expected.remove(name);
				}
			}
			for (String[] f : out.attached) {
				addFileIn(changed, f[0], f[1]);
				expected.add(f[0]);
			}

			// 4. nobody else saved meanwhile; 5. import, read back, else put back
			requireUnchangedSince(db, unid, exportedAt);
			importOrPutBack(db, unid, form, item, changed.toString(), expected, original, filesBefore);
		} catch (Exception e) {
			m_writeReport = failureReport(e, unid, form, item, html == null ? postedHtml : html, richtext,
					original == null ? null : itemDxl(original, item));
			throw e;
		}
	}

	/* the document's attachments and the files to add must fit the limits;
	 * returns the attachment names the document has now */
	private Set<String> requireWithinLimits(Document doc) throws Exception {
		java.util.TreeMap<String, Long> files = attachments(doc);
		long total = 0;
		for (long size : files.values()) {
			total += size;
		}
		if (total > MAX_WRITE_FILE_BYTES) {
			throw new Exception("This document's attachments are too large to save its rich text from the web"
					+ " (limit " + (MAX_WRITE_FILE_BYTES / 1024 / 1024) + " MB) - edit it in Notes");
		}
		long added = 0;
		for (String[] f : m_newFiles.values()) {
			if (!isBase64(f[1])) {
				throw new Exception("The file " + f[0] + " did not arrive whole - the rich text was not saved; attach it again");
			}
			added += decodedSize(f[1]);
		}
		if (added > MAX_NEW_FILE_BYTES) {
			throw new Exception("The files attached here are larger than " + (MAX_NEW_FILE_BYTES / 1024 / 1024)
					+ " MB together - the rich text was not saved; attach large files in Notes");
		}
		return new TreeSet<>(files.keySet());
	}

	/* the export is held as Java strings several times over on its way
	 * (the item's DOM, the rebuilt copy, the importer's buffer): about
	 * this many bytes of heap per character of it */
	private static final int HEAP_PER_CHAR = 12;

	/* the export must fit the heap, and the document must not be encrypted -
	 * a web user has no key to write it back. Encrypted = it holds a $Seal
	 * or SecretEncryptionKeys item; the seal='true' flag on items only says
	 * "encrypt me when the document is", and every $FILE carries it */
	private static void requireExportable(String original) throws Exception {
		long max = Runtime.getRuntime().maxMemory();
		if (max != Long.MAX_VALUE && (long) original.length() * HEAP_PER_CHAR > max) {
			throw new Exception("This document is too large to save its rich text from the web ("
					+ (original.length() / 1024 / 1024) + " MB as DXL; the server's Java heap is " + (max / 1024 / 1024)
					+ " MB - HTTPJVMMaxHeapSize) - edit it in Notes");
		}
		if (original.contains("<item name='$Seal'") || original.contains("<item name='SecretEncryptionKeys'")) {
			throw new Exception("This document is encrypted, which the web cannot write back - edit it in Notes");
		}
	}

	private static java.util.Date lastModified(Document doc) throws NotesException {
		lotus.domino.DateTime modified = doc.getLastModified();
		try {
			return modified.toJavaDate();
		} finally {
			recycle(modified);
		}
	}

	/* every item, the files, bitmaps as they are: the exporter's defaults */
	private String exportWhole(Document doc) throws NotesException {
		DxlExporter whole = m_session.createDxlExporter();
		try {
			whole.setOutputDOCTYPE(false);
			return whole.exportDxl(doc);
		} finally {
			recycle(whole);
		}
	}

	/* a save from elsewhere since the export (Notes, another browser) must
	 * not be overwritten: the import replaces the whole note */
	private static void requireUnchangedSince(lotus.domino.Database db, String unid, java.util.Date exportedAt)
			throws Exception {
		Document now = db.getDocumentByUNID(unid);
		try {
			if (Math.abs(lastModified(now).getTime() - exportedAt.getTime()) > 1000) {
				throw new Exception("This document was changed by someone else while you were editing"
						+ " - the rich text was not saved. Reload the page and make your changes again.");
			}
		} finally {
			recycle(now);
		}
	}

	/* the changed document imported and read back; anything wrong puts the
	 * original back and throws with what happened */
	private void importOrPutBack(lotus.domino.Database db, String unid, String form, String item, String changed,
			Set<String> expected, String original, Set<String> filesBefore) throws Exception {
		boolean imported = false;
		String problem;
		try {
			importReplace(changed, db, "save");
			imported = true;
			problem = check(db, unid, form, item, expected);
		} catch (Exception e) {
			problem = e.getMessage();
		}
		if (problem == null) {
			return;
		}
		// an import that went through and read back wrong is ALWAYS put
		// back: the note has been replaced, whatever the form and files say.
		// Only an import that threw may have left the note untouched.
		String state;
		if (!imported && check(db, unid, form, null, filesBefore) == null) {
			state = "left as it was";
		} else {
			try {
				importReplace(original, db, "put back");
				String again = check(db, unid, form, item, filesBefore);
				state = again == null ? "put back as it was" : "put back, but " + again;
			} catch (Exception e) {
				state = "NOT put back (" + e.getMessage() + ")";
			}
		}
		throw new Exception("The rich text was not saved: " + problem + " - the document was " + state);
	}

	private void importReplace(String dxl, lotus.domino.Database db, String what) throws Exception {
		lotus.domino.DxlImporter importer = m_session.createDxlImporter();
		try {
			importer.setDocumentImportOption(lotus.domino.DxlImporter.DXLIMPORTOPTION_REPLACE_ELSE_IGNORE);
			importer.setReplicaRequiredForReplaceOrUpdate(false);
			importer.setExitOnFirstFatalError(true);
			try {
				importer.importDxl(dxl, db);
			} catch (NotesException e) {
				throw new Exception("DXL import failed: " + e.text);
			} finally {
				m_importLog += "[" + what + "] " + importer.getLog() + "\n";
			}
			if (importer.getImportedNoteCount() != 1) {
				throw new Exception("DXL import replaced " + importer.getImportedNoteCount() + " documents");
			}
		} finally {
			recycle(importer);
		}
	}

	/* null when the document reads back as expected, else what is wrong;
	 * item null = only form and attachments */
	private String check(lotus.domino.Database db, String unid, String form, String item, Set<String> files) {
		Document again = null;
		try {
			again = db.getDocumentByUNID(unid);
			if (!form.equals(again.getItemValueString("Form"))) {
				return "the form changed";
			}
			if (item != null) {
				Item it = again.getFirstItem(item);
				if (it == null || it.getType() != Item.RICHTEXT) {
					return "the field is not rich text after the import";
				}
			}
			if (!attachments(again).keySet().equals(files)) {
				return "the attachments changed";
			}
			return null;
		} catch (Exception e) {
			return "the document cannot be read back: " + e.getMessage();
		} finally {
			recycle(again);
		}
	}

	/* the document's attachments, name -> size, from its $FILE items - not
	 * from @AttachmentNames/@AttachmentLengths: without attachments they
	 * return "", and @Sum of that fails ("Could not evaluate formula") */
	private static java.util.TreeMap<String, Long> attachments(Document doc) throws NotesException {
		java.util.TreeMap<String, Long> files = new java.util.TreeMap<>();
		Vector<?> items = doc.getItems();
		try {
			for (Object o : items) {
				Item it = (Item) o;
				if (it.getType() != Item.ATTACHMENT) {
					continue;
				}
				String name = it.getValueString();
				if (name == null || name.isEmpty() || files.containsKey(name)) {
					continue;
				}
				lotus.domino.EmbeddedObject file = doc.getAttachment(name);
				try {
					files.put(name, file == null ? 0L : (long) file.getFileSize());
				} finally {
					recycle(file);
				}
			}
		} finally {
			for (Object o : items) {
				recycle((Item) o);
			}
		}
		return files;
	}

	/* the item's original elements, numbered exactly as the page numbered
	 * them: the renderer walks the item's DXL again (no pictures kept) */
	private static DxlBody originals(String dxl, String item, String fileBase, String linkReplica,
			String linkPrefix) throws Exception {
		StringBuilder items = new StringBuilder("<document xmlns='http://www.lotus.com/dxl'>");
		int[] at = { 0 };
		int[] span;
		while ((span = nextItem(dxl, item, at[0])) != null) {
			items.append(dxl, span[0], span[1]);
			at[0] = span[1];
		}
		DxlBody body = new DxlBody(fileBase, linkReplica, linkPrefix, 0, new TreeSet<String>());
		body.render(items.append("</document>").toString(), item);
		return body;
	}

	/* the edited HTML as the item's DXL - static and server-free, tested by
	 * RichTextCheck; dxl is the document (or item) DXL the page was drawn from */
	private static String toDxl(String html, String dxl, String item, String fileBase, String linkReplica,
			String linkPrefix) throws Exception {
		return new DxlOut(originals(dxl, item, fileBase, linkReplica, linkPrefix), fileBase, linkReplica, linkPrefix)
				.write(HtmlSanitizer.clean(html));
	}

	/* the same with files the editor attached, a doclink view and the
	 * document's file names -> {richtext, the document DXL with the new
	 * $FILE items, List<String[]> attached {name, base64}} - for RichTextCheck */
	private static Object[] toDxlWithFiles(String html, String dxl, String item, String fileBase,
			java.util.Map<String, String[]> newFiles, Set<String> taken, String linkReplica, String linkPrefix,
			String linkView) throws Exception {
		DxlOut out = new DxlOut(originals(dxl, item, fileBase, linkReplica, linkPrefix), fileBase, linkReplica, linkPrefix);
		out.newFiles = newFiles;
		out.taken.addAll(taken);
		out.linkView = linkView;
		String richtext = out.write(HtmlSanitizer.clean(html));
		String doc = swapItem(dxl, item, richtext);
		for (String[] f : out.attached) {
			doc = addFile(doc, f[0], f[1]);
		}
		return new Object[] { richtext, doc, out.attached };
	}

	/* [start, end) of the next <item name='item' ...> element from 'from',
	 * or null; found by scanning, not by a regex over megabytes of base64 */
	private static int[] nextItem(String dxl, String item, int from) {
		return nextItem((CharSequence) dxl, item, from);
	}

	private static int[] nextItem(StringBuilder dxl, String item, int from) {
		return nextItem((CharSequence) dxl, item, from);
	}

	private static int[] nextItem(CharSequence dxl, String item, int from) {
		String head = "name='" + xmlAttr(item) + "'";
		int at = from;
		while ((at = indexOf(dxl, "<item", at)) >= 0) {
			int p = at + 5;
			while (p < dxl.length() && Character.isWhitespace(dxl.charAt(p))) {
				p++;
			}
			if (p > at + 5 && p + head.length() <= dxl.length()
					&& dxl.subSequence(p, p + head.length()).toString().equalsIgnoreCase(head)) {
				int gt = indexOf(dxl, ">", p);
				if (gt < 0) {
					return null;
				}
				if (dxl.charAt(gt - 1) == '/') {
					return new int[] { at, gt + 1 };
				}
				int end = indexOf(dxl, "</item>", gt);
				return end < 0 ? null : new int[] { at, end + 7 };
			}
			at = p;
		}
		return null;
	}

	/* String and StringBuilder both have indexOf(String, int); CharSequence has not */
	private static int indexOf(CharSequence s, String what, int from) {
		return s instanceof StringBuilder ? ((StringBuilder) s).indexOf(what, from) : s.toString().indexOf(what, from);
	}

	/* every <item name='item'> replaced by one new rich-text item, in a
	 * buffer the file changes then edit in place */
	private static StringBuilder swapItemIn(String dxl, String item, String richtext) {
		String replacement = "<item name='" + xmlAttr(item) + "'>" + richtext + "</item>";
		StringBuilder out = new StringBuilder(dxl.length() + replacement.length());
		int last = 0;
		boolean placed = false;
		int[] span;
		while ((span = nextItem(dxl, item, last)) != null) {
			out.append(dxl, last, span[0]);
			if (!placed) {
				out.append(replacement);
				placed = true;
			}
			last = span[1];
		}
		out.append(dxl, last, dxl.length());
		if (!placed) {
			out.insert(out.lastIndexOf("</document>"), replacement + "\n");
		}
		return out;
	}

	private static String swapItem(String dxl, String item, String richtext) {
		return swapItemIn(dxl, item, richtext).toString();
	}

	/* the $FILE item holding the file name removed from the DXL */
	private static void dropFileIn(StringBuilder dxl, String name) {
		String want = "name='" + xmlAttr(name) + "'";
		int at = 0;
		int[] span;
		while ((span = nextItem(dxl, "$FILE", at)) != null) {
			int data = dxl.indexOf("<filedata", span[0]);
			int headEnd = data < 0 || data > span[1] ? span[1] : data; // the header, not the base64
			int found = dxl.indexOf(want, span[0] + 8);
			if (found >= 0 && found < headEnd && dxl.lastIndexOf("<file", found) > span[0]) {
				dxl.delete(span[0], span[1]);
				return;
			}
			at = span[1];
		}
	}

	private static String dropFile(String dxl, String name) {
		StringBuilder sb = new StringBuilder(dxl);
		dropFileIn(sb, name);
		return sb.toString();
	}

	/* files attached from the web in one save, decoded: the POST carries
	 * them as base64 and must stay under ~6 MB (see the app's POST limit) */
	private static final int MAX_NEW_FILE_BYTES = 3 * 1024 * 1024;

	/* the icon of a file attached from the web (28x34 PNG): Notes shows
	 * it, the file name under it as the picture's caption */
	private static final String FILE_ICON = "iVBORw0KGgoAAAANSUhEUgAAABwAAAAiCAYAAABMfblJAAABDElEQVR4XmNgAIKshgaenJIaB7JxeZ1nVmltAsgckHkEQV5xrXFOae1/SnFuSd00dLOxApiFR06c/n/rzj2S8ZHjpxGWltbWoJuPAWAWPnr89D85AKQPpH/l2s3EWUotC0H0jj0HCFtKTQtBgKCl1LYQBPBaSgsLQQCnpdSyEFsqX7pqPczSufB8Si0LCeG8krpAqlj44+dPDJ9hy6dAX9pSxUJCABYCIHsGj4VfXr39f3PXYbIxSD9JFoI0NUqak41B+kmykGIfvn5HmoXUBERZSLEPR+MQ3UJqAqIspNiHo3GIbiE1AVEWUuzD0ThEt5CaYARbiK2ZRw0MMherhbTGcAsp7pASiUH2AACWuNF4N56fYwAAAABJRU5ErkJggg==";

	/* the $FILE item of a file attached from the web, before </document> */
	private static void addFileIn(StringBuilder dxl, String name, String base64) {
		String now = new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss',00+00'", java.util.Locale.ROOT) {
			private static final long serialVersionUID = 1L;
			{
				setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
			}
		}.format(new java.util.Date());
		String item = "<item name='$FILE' summary='true'><object><file hosttype='msdos' compression='none'"
				+ " flags='storedindoc' encoding='none' name='" + xmlAttr(name) + "' size='" + decodedSize(base64) + "'>"
				+ "<created><datetime>" + now + "</datetime></created><modified><datetime>" + now + "</datetime></modified>"
				+ "<filedata>" + base64 + "</filedata></file></object></item>\n";
		int end = dxl.lastIndexOf("</document>");
		if (end >= 0) {
			dxl.insert(end, item);
		}
	}

	private static String addFile(String dxl, String name, String base64) {
		StringBuilder sb = new StringBuilder(dxl);
		addFileIn(sb, name, base64);
		return sb.toString();
	}

	private static boolean isBase64(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/' || c == '=')) {
				return false;
			}
		}
		return s.length() % 4 == 0;
	}

	private static long decodedSize(String base64) {
		int pad = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
		return (long) base64.length() / 4 * 3 - pad;
	}

	/* a file name Notes and a URL can both hold: no path, no reserved
	 * characters, at most 100 characters */
	private static String cleanFileName(String name) {
		String n = name == null ? "" : name.replace((char) 92, '/'); // a Windows path
		StringBuilder b = new StringBuilder();
		for (char c : n.substring(n.lastIndexOf('/') + 1).toCharArray()) {
			b.append(c < 32 || c == 34 || ":*?<>|#%&'".indexOf(c) >= 0 ? '_' : c);
		}
		n = b.toString().trim();
		if (n.length() > 100) {
			int dot = n.lastIndexOf('.');
			String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
			n = n.substring(0, 100 - ext.length()) + ext;
		}
		return n.isEmpty() || n.startsWith(".") ? "file" + n : n;
	}

	private static final Set<String> BLOCKS = new HashSet<>(Arrays.asList(
			"div", "p", "center", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "address",
			"ul", "ol", "li", "table", "hr", "thead", "tbody", "tfoot", "tr", "td", "th", "caption"));
	private static final Set<String> VOID_HTML = new HashSet<>(Arrays.asList("br", "hr", "img", "col"));
	private static final Set<String> LINKS = new HashSet<>(Arrays.asList("doclink", "viewlink", "databaselink"));
	private static final Pattern HTML_ATTR = Pattern.compile("([A-Za-z][A-Za-z0-9-]*)=\"([^\"]*)\"");
	private static final Pattern DATA_URI = Pattern.compile("data:image/(png|gif|jpeg);base64,([A-Za-z0-9+/=]+)");
	private static final HashMap<String, String> HEADING_SIZES = new HashMap<>();
	static {
		String[] sizes = { "h1", "18pt", "h2", "16pt", "h3", "14pt", "h4", "12pt", "h5", "10pt", "h6", "8pt" };
		for (int i = 0; i < sizes.length; i += 2) {
			HEADING_SIZES.put(sizes[i], sizes[i + 1]);
		}
	}
	private static final String[] FONT_TAG_SIZES = { "", "8pt", "10pt", "12pt", "14pt", "18pt", "24pt", "36pt" };

	/* one node of the sanitized HTML ("#text" for text) */
	private static final class HNode {
		final String name;
		final HashMap<String, String> attrs = new HashMap<>();
		final List<HNode> kids = new ArrayList<>();
		String text = "";

		HNode(String name) {
			this.name = name;
		}

		String attr(String n) {
			String v = attrs.get(n);
			return v == null ? "" : v;
		}
	}

	/* inline formatting in effect */
	private static final class Style {
		String name = "";
		String size = "";
		String color = "";
		String highlight = "";
		boolean bold, italic, underline, strike, sup, sub;

		Style copy() {
			Style s = new Style();
			s.name = name;
			s.size = size;
			s.color = color;
			s.highlight = highlight;
			s.bold = bold;
			s.italic = italic;
			s.underline = underline;
			s.strike = strike;
			s.sup = sup;
			s.sub = sub;
			return s;
		}

		String key() {
			return name + "|" + size + "|" + color + "|" + highlight + "|" + bold + italic + underline + strike + sup + sub;
		}

		boolean plain() {
			return name.isEmpty() && size.isEmpty() && color.isEmpty() && highlight.isEmpty()
					&& !(bold || italic || underline || strike || sup || sub);
		}

		String font() {
			StringBuilder f = new StringBuilder("<font");
			if (!name.isEmpty()) {
				f.append(" name='").append(xmlAttr(name)).append('\'');
			}
			if (!size.isEmpty()) {
				f.append(" size='").append(size).append('\'');
			}
			if (!color.isEmpty()) {
				f.append(" color='").append(color).append('\'');
			}
			StringBuilder styles = new StringBuilder();
			String[] names = { "bold", "italic", "underline", "strikethrough", "superscript", "subscript" };
			boolean[] on = { bold, italic, underline, strike, sup, sub };
			for (int i = 0; i < names.length; i++) {
				if (on[i]) {
					styles.append(styles.length() > 0 ? " " : "").append(names[i]);
				}
			}
			if (styles.length() > 0) {
				f.append(" style='").append(styles).append('\'');
			}
			return f.append("/>").toString();
		}
	}

	/* where a paragraph stands */
	private static final class Ctx {
		String pd = "";
		String align = "";
		int indentPx = 0;
		String list = "";
		String listType = "";
		int listDepth = -1;
		boolean inCell = false;
		boolean pre = false;
		Style style = new Style();

		Ctx copy() {
			Ctx c = new Ctx();
			c.pd = pd;
			c.align = align;
			c.indentPx = indentPx;
			c.list = list;
			c.listType = listType;
			c.listDepth = listDepth;
			c.inCell = inCell;
			c.pre = pre;
			c.style = style;
			return c;
		}
	}

	/*
	 * Sanitized editor HTML -> DXL <richtext>: the inverse of DxlBody. Each
	 * construct the renderer emits maps back to its DXL element; where the
	 * HTML carries a marker of an original element that element is written
	 * back as it was. Paragraphs open lazily, so text directly in a
	 * container still lands in one.
	 */
	private static final class DxlOut {
		final DxlBody orig;
		final String fileBase;
		final String linkReplica;
		final String linkPrefix;
		final StringBuilder out = new StringBuilder();
		/* attachment names the new text still shows */
		final Set<String> files = new TreeSet<>();
		final HashMap<String, Integer> pardefIds = new HashMap<>();
		final HashMap<Integer, String> pardefXml = new HashMap<>();
		final Set<Integer> pardefWritten = new HashSet<>();
		int parCount = 0;
		StringBuilder par = null;
		int parDef = 0;
		Style runStyle = null;
		final StringBuilder runText = new StringBuilder();
		/* the run's content already written as XML - text before an island,
		 * the island - that runText follows */
		final StringBuilder runXml = new StringBuilder();
		/* a link to linkPrefix + UNID becomes a doclink through this view */
		String linkView = null;
		/* files the editor attached (marker -> {name, base64}), the names the
		 * document's files take, and the attached files the HTML still shows
		 * ({name as stored, base64}; a marker shown twice is one file) */
		java.util.Map<String, String[]> newFiles = new HashMap<>();
		final Set<String> taken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		final List<String[]> attached = new ArrayList<>();
		final HashMap<String, String> attachedNames = new HashMap<>();

		DxlOut(DxlBody orig, String fileBase, String linkReplica, String linkPrefix) {
			this.orig = orig;
			this.fileBase = fileBase;
			this.linkReplica = linkReplica;
			this.linkPrefix = linkPrefix;
		}

		String write(String html) {
			HNode root = parse(html);
			for (HNode k : root.kids) {
				node(k, new Ctx());
			}
			closePar();
			if (parCount == 0) {
				emitPar(pardefFor(new Ctx()), "");
			}
			return "<richtext>" + out + "</richtext>";
		}

		/* ---- the HTML tree ---- */

		static HNode parse(String html) {
			HNode root = new HNode("#root");
			java.util.ArrayDeque<HNode> stack = new java.util.ArrayDeque<>();
			stack.push(root);
			int i = 0;
			int n = html.length();
			while (i < n) {
				int lt = html.indexOf('<', i);
				if (lt < 0) {
					lt = n;
				}
				if (lt > i) {
					HNode t = new HNode("#text");
					t.text = decode(html.substring(i, lt));
					stack.peek().kids.add(t);
				}
				if (lt >= n) {
					break;
				}
				int gt = html.indexOf('>', lt);
				if (gt < 0) {
					break; // cannot happen after HtmlSanitizer.clean()
				}
				String tag = html.substring(lt + 1, gt);
				i = gt + 1;
				if (tag.startsWith("/")) {
					String name = tag.substring(1).trim();
					boolean open = false;
					for (HNode h : stack) {
						open = open || h.name.equals(name);
					}
					while (open && stack.size() > 1 && !stack.pop().name.equals(name)) {
						// pop to the matching element (clean() output is balanced)
					}
					continue;
				}
				boolean selfClosing = tag.endsWith("/");
				if (selfClosing) {
					tag = tag.substring(0, tag.length() - 1);
				}
				int sp = tag.indexOf(' ');
				HNode node = new HNode((sp < 0 ? tag : tag.substring(0, sp)).trim());
				Matcher m = HTML_ATTR.matcher(tag);
				while (m.find()) {
					node.attrs.put(m.group(1), decode(m.group(2)));
				}
				stack.peek().kids.add(node);
				if (!selfClosing && !VOID_HTML.contains(node.name)) {
					stack.push(node);
				}
			}
			return root;
		}

		/* the character references clean() leaves in text and attributes */
		static String decode(String s) {
			if (s.indexOf('&') < 0) {
				return s;
			}
			StringBuilder out = new StringBuilder(s.length());
			int i = 0;
			while (i < s.length()) {
				char c = s.charAt(i);
				int semi = c == '&' ? s.indexOf(';', i) : -1;
				if (semi > i && semi - i <= 10) {
					String ref = s.substring(i + 1, semi);
					String rep = null;
					if ("amp".equals(ref)) {
						rep = "&";
					} else if ("lt".equals(ref)) {
						rep = "<";
					} else if ("gt".equals(ref)) {
						rep = ">";
					} else if ("quot".equals(ref)) {
						rep = "\"";
					} else if ("apos".equals(ref)) {
						rep = "'";
					} else if ("nbsp".equals(ref)) {
						rep = "\u00a0";
					} else if (ref.startsWith("#")) {
						try {
							int code = ref.startsWith("#x") || ref.startsWith("#X")
									? Integer.parseInt(ref.substring(2), 16) : Integer.parseInt(ref.substring(1));
							rep = new String(Character.toChars(code));
						} catch (Exception e) {
							rep = null;
						}
					}
					if (rep != null) {
						out.append(rep);
						i = semi + 1;
						continue;
					}
				}
				out.append(c);
				i++;
			}
			return out.toString();
		}

		/* ---- blocks ---- */

		void node(HNode k, Ctx c) {
			if (BLOCKS.contains(k.name)) {
				closePar();
				block(k, c);
			} else {
				inline(k, c, c.style);
			}
		}

		void block(HNode n, Ctx c) {
			Element island = "div".equals(n.name) ? kept(n, true) : null;
			if (island != null) {
				keepBlock(island);
				return;
			}
			if ("ul".equals(n.name) || "ol".equals(n.name)) {
				Ctx d = c.copy();
				d.list = "ul".equals(n.name) ? "bullet" : "number";
				// an explicit Notes list type (the page's, or the editor's List menu)
				String kind = n.attr("data-list");
				d.listType = ("ul".equals(n.name) ? BULLET_LISTS : NUMBER_LISTS).contains(kind) ? kind : "";
				d.listDepth = c.listDepth + 1;
				for (HNode k : n.kids) {
					if ("li".equals(k.name)) {
						paragraph(k, d);
					} else {
						node(k, d);
					}
				}
				closePar();
			} else if ("li".equals(n.name)) {
				Ctx d = c.copy();
				if (d.list.isEmpty()) {
					d.list = "bullet";
					d.listDepth = 0;
				}
				paragraph(n, d);
			} else if ("table".equals(n.name)) {
				table(n, c);
			} else if ("hr".equals(n.name)) {
				emitPar(pardefFor(c), "<horizrule/>");
			} else {
				// div p center h1-h6 pre blockquote address, and table parts
				// found outside a table
				paragraph(n, c);
			}
		}

		/* an element that is at least one paragraph */
		void paragraph(HNode n, Ctx c) {
			Ctx d = derive(n, c);
			int before = parCount;
			for (HNode k : n.kids) {
				node(k, d);
			}
			closePar();
			if (parCount == before) {
				emitPar(pardefFor(d), "");
			}
		}

		Ctx derive(HNode n, Ctx c) {
			Ctx d = c.copy();
			if (!n.attr("data-pd").isEmpty()) {
				d.pd = n.attr("data-pd");
			}
			HashMap<String, String> css = css(n.attr("style"));
			String align = "center".equals(n.name) ? "center" : first(css.get("text-align"), n.attr("align"));
			if (!align.isEmpty()) {
				d.align = align.toLowerCase();
			}
			Integer ml = cssPx(css.get("margin-left"));
			if (ml != null) {
				d.indentPx = ml;
			}
			if ("blockquote".equals(n.name)) {
				d.indentPx += 48;
			}
			String heading = HEADING_SIZES.get(n.name);
			if (heading != null) {
				d.style = c.style.copy();
				d.style.bold = true;
				d.style.size = heading;
			}
			if ("pre".equals(n.name)) {
				d.style = c.style.copy();
				d.style.name = "Default Monospace";
				d.pre = true;
			}
			return d;
		}

		/* the pardef a paragraph gets: its original (data-pd) with what the
		 * HTML shows laid over it - list, alignment, indent */
		int pardefFor(Ctx d) {
			java.util.LinkedHashMap<String, String> a = new java.util.LinkedHashMap<>();
			StringBuilder kids = new StringBuilder();
			Element base = d.pd.isEmpty() || orig == null ? null : orig.pardefs.get(d.pd);
			if (base != null) {
				copyPardef(base, a, kids);
			}
			if (!d.list.isEmpty()) {
				// the type: the explicit one, else the original's when it is of
				// the same family (a lettered list stays lettered), else the family's
				String was = a.get("list");
				String type = !d.listType.isEmpty() ? d.listType
						: was != null && ("bullet".equals(d.list) ? BULLET_LISTS : NUMBER_LISTS).contains(was) ? was : d.list;
				a.put("list", type);
				if (d.listDepth > 0) {
					a.put("leftmargin", inches(96 * 1.25 + 48 * d.listDepth));
				}
			} else if (a.containsKey("list") && !"none".equals(a.get("list"))) {
				a.remove("list");
			}
			String align = "justify".equals(d.align) ? "full" : d.align;
			if ("left".equals(align)) {
				a.remove("align");
			} else if ("center".equals(align) || "right".equals(align) || "full".equals(align)) {
				a.put("align", align);
			}
			if (!d.inCell && d.list.isEmpty()) {
				// the renderer showed the original's indent as margin-left: the
				// same value back keeps the original string, 1:1
				long shown = base == null ? 0 : Math.max(0, Math.round((DxlBody.inches(base.getAttribute("leftmargin")) - 1) * 96));
				if (d.indentPx != shown) {
					a.put("leftmargin", d.indentPx > 0 ? inches(96 + d.indentPx) : "1in");
				}
			}
			return register(a, kids);
		}

		static void copyPardef(Element base, java.util.LinkedHashMap<String, String> a, StringBuilder kids) {
			org.w3c.dom.NamedNodeMap attrs = base.getAttributes();
			for (int i = 0; i < attrs.getLength(); i++) {
				Node at = attrs.item(i);
				if (!"id".equals(at.getNodeName()) && !at.getNodeName().startsWith("xmlns")) {
					a.put(at.getNodeName(), at.getNodeValue());
				}
			}
			for (Node k = base.getFirstChild(); k != null; k = k.getNextSibling()) {
				if (k.getNodeType() == Node.ELEMENT_NODE) {
					xml(k, kids);
				}
			}
		}

		/* one pardef per distinct definition, numbered in order of first use */
		int register(java.util.LinkedHashMap<String, String> a, StringBuilder kids) {
			StringBuilder sig = new StringBuilder();
			for (java.util.Map.Entry<String, String> e : a.entrySet()) {
				sig.append(' ').append(e.getKey()).append("='").append(xmlAttr(e.getValue())).append('\'');
			}
			String key = sig + "|" + kids;
			Integer id = pardefIds.get(key);
			if (id == null) {
				id = pardefIds.size() + 1;
				pardefIds.put(key, id);
				pardefXml.put(id, "<pardef id='" + id + "'" + sig
						+ (kids.length() == 0 ? "/>" : ">" + kids + "</pardef>"));
			}
			return id;
		}

		void emitPar(int def, String content) {
			define(def, out);
			out.append("<par def='").append(def).append('\'')
					.append(content.isEmpty() ? "/>" : ">" + content + "</par>");
			parCount++;
		}

		void openPar(Ctx c) {
			if (par == null) {
				par = new StringBuilder();
				parDef = pardefFor(c);
			}
		}

		void closePar() {
			if (par == null) {
				return;
			}
			flushRun();
			String content = par.toString();
			if ("<break/>".equals(content)) {
				content = ""; // an empty line: the editor's <div><br></div>
			}
			par = null;
			emitPar(parDef, content);
		}

		/* ---- inline ---- */

		void inline(HNode k, Ctx c, Style s) {
			String n = k.name;
			if ("#text".equals(n)) {
				text(k.text, c, s);
			} else if ("br".equals(n)) {
				openPar(c);
				flushRun();
				par.append("<break/>");
			} else if ("img".equals(n)) {
				openPar(c);
				flushRun();
				picture(k);
			} else if ("a".equals(n)) {
				openPar(c);
				flushRun();
				link(k, c, s);
			} else if ("span".equals(n) && kept(k, false) != null) {
				openPar(c);
				keepInline(kept(k, false), s);
			} else if ("span".equals(n) && !k.attr("data-file").isEmpty()) {
				openPar(c);
				flushRun();
				newFile(k.attr("data-file"));
			} else if ("span".equals(n) && !k.attr("data-pic").isEmpty() && k.attr("data-cap").isEmpty()) {
				// a Notes picture the page did not show: back as it was
				openPar(c);
				flushRun();
				picture(k);
			} else if ("span".equals(n) && !k.attr("data-cap").isEmpty()) {
				// a picture's caption: written with the picture
			} else {
				Style t = styled(k, s);
				for (HNode kid : k.kids) {
					if (BLOCKS.contains(kid.name)) {
						closePar();
						Ctx d = c.copy();
						d.style = t;
						block(kid, d);
					} else {
						inline(kid, c, t);
					}
				}
			}
		}

		/* link content stays inside the link element: blocks flatten */
		void inlineFlat(HNode k, Ctx c, Style s) {
			for (HNode kid : k.kids) {
				if ("#text".equals(kid.name)) {
					text(kid.text, c, s);
				} else if ("br".equals(kid.name)) {
					flushRun();
					par.append("<break/>");
				} else if ("img".equals(kid.name)) {
					flushRun();
					picture(kid);
				} else if ("span".equals(kid.name) && kept(kid, false) != null) {
					keepInline(kept(kid, false), s);
				} else if ("span".equals(kid.name) && !kid.attr("data-pic").isEmpty() && kid.attr("data-cap").isEmpty()) {
					flushRun();
					picture(kid);
				} else if (!"a".equals(kid.name) && !("span".equals(kid.name) && !kid.attr("data-cap").isEmpty())) {
					inlineFlat(kid, c, styled(kid, s));
				}
			}
		}

		/* a file the editor attached: its icon here, the file stored by
		 * write() - a marker with no file posted fails the save (the POST
		 * did not arrive whole), rather than saving without it */
		void newFile(String marker) {
			String[] f = newFiles.get(marker);
			if (f == null) {
				throw new IllegalStateException("A file attached here did not arrive - the rich text was not saved; attach it again");
			}
			String name = attachedNames.get(marker);
			if (name == null) {
				name = cleanFileName(f[0]);
				int dot = name.lastIndexOf('.');
				String stem = dot > 0 ? name.substring(0, dot) : name;
				String ext = dot > 0 ? name.substring(dot) : "";
				for (int i = 2; taken.contains(name); i++) {
					name = stem + " (" + i + ")" + ext; // as Notes names a second copy
				}
				taken.add(name);
				attachedNames.put(marker, name);
				attached.add(new String[] { name, f[1] });
			}
			files.add(name);
			par.append("<attachmentref name='").append(xmlAttr(name)).append("' displayname='").append(xmlAttr(name))
					.append("'><picture width='28px' height='34px'><png>").append(FILE_ICON).append("</png><caption>")
					.append(xmlText(name)).append("</caption></picture></attachmentref>");
		}

		/* ---- islands: Notes-only elements, back exactly as they were ---- */

		/* the original element an island marker points at; null when there
		 * is none (a number out of range, another element's name, a span for
		 * a block) - the marker is then read as the plain HTML it wraps */
		Element kept(HNode k, boolean block) {
			String n = k.attr("data-keep");
			if (orig == null || !n.matches("[0-9]{1,6}")) {
				return null;
			}
			int i = Integer.parseInt(n) - 1;
			if (i < 0 || i >= orig.keepElements.size() || orig.keepBlock.get(i) != block) {
				return null;
			}
			Element e = orig.keepElements.get(i);
			return DxlBody.kindOf(e).equals(k.attr("data-kind")) ? e : null;
		}

		/* a section, a hidden paragraph, an unknown block: where it stands,
		 * with the pardefs its section titles use defined before it */
		void keepBlock(Element e) {
			closePar();
			org.w3c.dom.NodeList titles = e.getElementsByTagName("sectiontitle");
			for (int i = 0; i < titles.getLength(); i++) {
				String def = ((Element) titles.item(i)).getAttribute("pardef");
				if (!def.isEmpty()) {
					define(exactPardef(def), out);
				}
			}
			keptXml(e, out);
			parCount++;
		}

		/* a hotspot, button, computed text...: in the run of the text around
		 * it (the renderer showed it inside that run's styling); a pass-thru
		 * run is a run of its own */
		void keepInline(Element e, Style s) {
			if ("run".equals(e.getNodeName())) {
				flushRun();
				keptXml(e, par);
				return;
			}
			if (runStyle != null && !runStyle.key().equals(s.key())) {
				flushRun();
			}
			runStyle = s;
			runXml.append(xmlText(runText.toString().replace(TAB_CHARS, "\t")));
			runText.setLength(0);
			keptXml(e, runXml);
		}

		/* an island's DXL as it was, but its paragraphs pointing at pardefs of
		 * THIS output (ids are renumbered here, the pardef defined where first
		 * used), and its attachments counted as still shown */
		void keptXml(Node n, StringBuilder sb) {
			if (n.getNodeType() != Node.ELEMENT_NODE) {
				xml(n, sb);
				return;
			}
			Element e = (Element) n;
			String name = e.getNodeName();
			if ("pardef".equals(name)) {
				return; // defined again where a paragraph uses it
			}
			String remap = null;
			String def = "par".equals(name) ? "def" : "sectiontitle".equals(name) ? "pardef" : null;
			if (def != null && !e.getAttribute(def).isEmpty()) {
				int id = exactPardef(e.getAttribute(def));
				if ("par".equals(name)) {
					define(id, sb);
				}
				remap = String.valueOf(id);
			}
			if ("attachmentref".equals(name)) {
				files.add(e.getAttribute("name"));
			}
			sb.append('<').append(name);
			org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
			for (int i = 0; i < attrs.getLength(); i++) {
				Node a = attrs.item(i);
				String an = a.getNodeName();
				if (!an.startsWith("xmlns")) {
					String value = remap != null && an.equals(def) ? remap : a.getNodeValue();
					if ("database".equals(an) && LINKS.contains(name)) {
						value = replica(value); // a doclink inside an island
					}
					sb.append(' ').append(an).append("='").append(xmlAttr(value)).append('\'');
				}
			}
			if (!e.hasChildNodes()) {
				sb.append("/>");
				return;
			}
			sb.append('>');
			for (Node k = e.getFirstChild(); k != null; k = k.getNextSibling()) {
				keptXml(k, sb);
			}
			sb.append("</").append(name).append('>');
		}

		/* the original pardef exactly (id aside) as a pardef of this output */
		int exactPardef(String id) {
			Element base = orig == null ? null : orig.pardefs.get(id);
			if (base == null) {
				return pardefFor(new Ctx());
			}
			java.util.LinkedHashMap<String, String> a = new java.util.LinkedHashMap<>();
			StringBuilder kids = new StringBuilder();
			copyPardef(base, a, kids);
			return register(a, kids);
		}

		void define(int id, StringBuilder sb) {
			if (pardefWritten.add(id)) {
				sb.append(pardefXml.get(id));
			}
		}

		Style styled(HNode k, Style s) {
			String n = k.name;
			Style t = s;
			if ("b".equals(n) || "strong".equals(n) || "i".equals(n) || "em".equals(n) || "u".equals(n)
					|| "s".equals(n) || "strike".equals(n) || "sup".equals(n) || "sub".equals(n)
					|| "code".equals(n) || "span".equals(n) || "font".equals(n)) {
				t = s.copy();
			}
			if ("b".equals(n) || "strong".equals(n)) {
				t.bold = true;
			} else if ("i".equals(n) || "em".equals(n)) {
				t.italic = true;
			} else if ("u".equals(n)) {
				t.underline = true;
			} else if ("s".equals(n) || "strike".equals(n)) {
				t.strike = true;
			} else if ("sup".equals(n)) {
				t.sup = true;
			} else if ("sub".equals(n)) {
				t.sub = true;
			} else if ("code".equals(n)) {
				t.name = "Default Monospace";
			} else if ("font".equals(n)) {
				if (!k.attr("face").isEmpty()) {
					t.name = fontName(k.attr("face"));
				}
				String size = k.attr("size");
				if (size.matches("[1-7]")) {
					t.size = FONT_TAG_SIZES[Integer.parseInt(size)];
				}
				String color = color(k.attr("color"));
				if (!color.isEmpty()) {
					t.color = color;
				}
			}
			HashMap<String, String> css = css(k.attr("style"));
			if (!css.isEmpty()) {
				if (t == s) {
					t = s.copy();
				}
				if (css.containsKey("font-family")) {
					t.name = fontName(css.get("font-family"));
				}
				String size = cssPt(css.get("font-size"));
				if (!size.isEmpty()) {
					t.size = size;
				}
				String color = color(css.get("color"));
				if (!color.isEmpty()) {
					t.color = color;
				}
				if (css.containsKey("background-color")) {
					// an explicit background decides: one of the three Notes
					// highlights, or none (the editor's "No highlight" is transparent)
					String bg = color(css.get("background-color"));
					t.highlight = "#ffff00".equals(bg) ? "yellow" : "#ffc0cb".equals(bg) ? "pink"
							: "#add8e6".equals(bg) ? "blue" : "";
				}
				if ("bold".equals(css.get("font-weight")) || "700".equals(css.get("font-weight"))) {
					t.bold = true;
				}
				if ("italic".equals(css.get("font-style"))) {
					t.italic = true;
				}
				String deco = first(css.get("text-decoration"), "");
				if (deco.contains("underline")) {
					t.underline = true;
				}
				if (deco.contains("line-through")) {
					t.strike = true;
				}
			}
			return t;
		}

		void text(String raw, Ctx c, Style s) {
			String t = c.pre ? raw : raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
			t = t.replace('\u00a0', ' ');
			if (t.trim().isEmpty() && par == null) {
				return; // whitespace between blocks is no paragraph
			}
			if (t.isEmpty()) {
				return;
			}
			openPar(c);
			if (c.pre && t.indexOf('\n') >= 0) {
				String[] lines = t.split("\n", -1);
				for (int i = 0; i < lines.length; i++) {
					if (i > 0) {
						flushRun();
						par.append("<break/>");
					}
					runText(lines[i], s);
				}
				return;
			}
			runText(t, s);
		}

		void runText(String t, Style s) {
			if (t.isEmpty()) {
				return;
			}
			if (runStyle != null && !runStyle.key().equals(s.key())) {
				flushRun();
			}
			runStyle = s;
			runText.append(t);
		}

		void flushRun() {
			if ((runText.length() == 0 && runXml.length() == 0) || par == null) {
				runText.setLength(0);
				runXml.setLength(0);
				return;
			}
			String t = runXml + xmlText(runText.toString().replace(TAB_CHARS, "\t"));
			runXml.setLength(0);
			if (runStyle.plain()) {
				par.append(t);
			} else {
				par.append("<run").append(runStyle.highlight.isEmpty() ? "" : " highlight='" + runStyle.highlight + "'")
						.append('>').append(runStyle.font()).append(t).append("</run>");
			}
			runText.setLength(0);
		}

		/* ---- pictures and links ---- */

		void picture(HNode img) {
			String n = img.attr("data-pic");
			if (!n.isEmpty() && orig != null) {
				int i = Integer.parseInt(n) - 1;
				if (i >= 0 && i < orig.pictureElements.size()) {
					// the picture as Notes had it - at a new size when the editor
					// resized it (scaledwidth/height: the stored picture is untouched).
				// In INCHES: the importer refuses 'px' there ("Length value is
				// invalid", live 2026-09-29) although the DTD allows it
					Element pic = orig.pictureElements.get(i);
					String w = img.attr("width");
					String h = img.attr("height");
					java.util.LinkedHashMap<String, String> over = new java.util.LinkedHashMap<>();
					if (w.matches("[0-9]{1,4}") && h.matches("[0-9]{1,4}")
							&& !(w.equals(DxlBody.px(first(pic.getAttribute("scaledwidth"), pic.getAttribute("width"))))
									&& h.equals(DxlBody.px(first(pic.getAttribute("scaledheight"), pic.getAttribute("height")))))) {
						over.put("scaledwidth", inches(Integer.parseInt(w)));
						over.put("scaledheight", inches(Integer.parseInt(h)));
					}
					xmlWith(pic, over, par);
					return;
				}
			}
			Matcher m = DATA_URI.matcher(img.attr("src"));
			if (!m.matches()) {
				return; // nothing a Notes picture can hold
			}
			par.append("<picture");
			if (img.attr("width").matches("[0-9]{1,4}")) {
				par.append(" width='").append(img.attr("width")).append("px'");
			}
			if (img.attr("height").matches("[0-9]{1,4}")) {
				par.append(" height='").append(img.attr("height")).append("px'");
			}
			if (!img.attr("alt").isEmpty()) {
				par.append(" alttext='").append(xmlAttr(img.attr("alt"))).append('\'');
			}
			par.append("><").append(m.group(1)).append('>').append(m.group(2))
					.append("</").append(m.group(1)).append("></picture>");
		}

		void link(HNode a, Ctx c, Style s) {
			String href = a.attr("href");
			if (fileBase != null && href.startsWith(fileBase)) {
				attachment(a, urlDecode(href.substring(fileBase.length())));
				return;
			}
			Element original = orig == null ? null : orig.linkElements.get(href);
			if (original != null || href.startsWith("notes://")
					|| (linkPrefix != null && href.startsWith(linkPrefix))) {
				notesLink(a, c, s, href, original);
				return;
			}
			if (href.isEmpty() || !HtmlSanitizer.isSafeUrl(href)) {
				inlineFlat(a, c, s); // no usable target: its text
				return;
			}
			par.append("<urllink showborder='false' href='").append(xmlAttr(href)).append("'>");
			inlineFlat(a, c, s);
			flushRun();
			par.append("</urllink>");
		}

		void notesLink(HNode a, Ctx c, Style s, String href, Element original) {
			java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
			String tag;
			if (original != null) {
				tag = original.getNodeName();
				org.w3c.dom.NamedNodeMap map = original.getAttributes();
				for (int i = 0; i < map.getLength(); i++) {
					attrs.put(map.item(i).getNodeName(), map.item(i).getNodeValue());
				}
				if (attrs.containsKey("database")) {
					attrs.put("database", replica(attrs.get("database")));
				}
			} else if (linkPrefix != null && href.startsWith(linkPrefix)) {
				// a link to one of the app's pages: a doclink to that document
				tag = "doclink";
				Matcher unid = Pattern.compile("^[0-9A-Fa-f]{32}").matcher(href.substring(linkPrefix.length()));
				attrs.put("document", unid.find() ? unid.group() : href.substring(linkPrefix.length()));
				if (linkView != null && !linkView.isEmpty()) {
					attrs.put("view", linkView);
				}
				attrs.put("database", replica(linkReplica == null ? "" : linkReplica));
			} else {
				// notes://server/replica[/view[/document]]
				String[] parts = href.substring("notes://".length()).split("/");
				tag = parts.length <= 2 ? "databaselink" : parts.length == 3 ? "viewlink" : "doclink";
				if (parts.length > 3) {
					attrs.put("document", parts[3]);
				}
				if (parts.length > 2 && !"0".equals(parts[2])) {
					attrs.put("view", parts[2]);
				}
				if (parts.length > 1) {
					attrs.put("database", replica(parts[1]));
				}
				if (!parts[0].isEmpty()) {
					attrs.put("server", parts[0]);
				}
			}
			par.append('<').append(tag);
			for (java.util.Map.Entry<String, String> e : attrs.entrySet()) {
				par.append(' ').append(e.getKey()).append("='").append(xmlAttr(e.getValue())).append('\'');
			}
			String shown = textOf(a).replace(LINK_ICON_CHARS, "").trim();
			if (shown.isEmpty()) {
				par.append("/>"); // the icon only - Notes draws it
				return;
			}
			par.append('>');
			inlineFlat(a, c, s);
			flushRun();
			par.append("</").append(tag).append('>');
		}

		void attachment(HNode a, String name) {
			files.add(name);
			Element original = orig == null ? null : orig.attachmentElements.get(name);
			if (original != null) {
				xml(original, par); // the icon as Notes had it
				return;
			}
			// a new icon for a file of this document (copied inside the body)
			par.append("<attachmentref name='").append(xmlAttr(name)).append("' displayname='")
					.append(xmlAttr(name)).append("'>");
			for (HNode k : a.kids) {
				if ("img".equals(k.name)) {
					picture(k);
				}
			}
			par.append("</attachmentref>");
		}

		/* ---- tables ---- */

		void table(HNode t, Ctx c) {
			closePar();
			List<HNode> rows = new ArrayList<>();
			for (HNode k : t.kids) {
				if ("tr".equals(k.name)) {
					rows.add(k);
				} else if ("thead".equals(k.name) || "tbody".equals(k.name) || "tfoot".equals(k.name)) {
					for (HNode r : k.kids) {
						if ("tr".equals(r.name)) {
							rows.add(r);
						}
					}
				}
			}
			int cols = 0;
			for (HNode r : rows) {
				int n = 0;
				for (HNode cell : cells(r)) {
					n += Math.max(1, number(cell.attr("colspan")));
				}
				cols = Math.max(cols, n);
			}
			cols = Math.max(cols, 1);
			Integer[] widths = new Integer[cols];
			if (!rows.isEmpty()) {
				int col = 0;
				for (HNode cell : cells(rows.get(0))) {
					int span = Math.max(1, number(cell.attr("colspan")));
					if (span == 1 && col < cols) {
						widths[col] = cssPx(css(cell.attr("style")).get("width"));
					}
					col += span;
				}
			}
			String w = first(css(t.attr("style")).get("width"), "");
			Integer ref = cssPx(w);
			boolean fit = ref == null;
			// the original table (data-tbl): its own settings come back - all
			// of them when its columns are as they were, its rows' too when
			// the rows are (a tabbed table keeps its tabs)
			Element original = null;
			List<Element> origRows = new ArrayList<>();
			int origCols = 0;
			String tbl = t.attr("data-tbl");
			if (orig != null && tbl.matches("[0-9]{1,6}") && Integer.parseInt(tbl) - 1 < orig.tableElements.size()) {
				original = orig.tableElements.get(Integer.parseInt(tbl) - 1);
				for (Node k = original.getFirstChild(); k != null; k = k.getNextSibling()) {
					if (k.getNodeType() == Node.ELEMENT_NODE && "tablecolumn".equals(k.getNodeName())) {
						origCols++;
					} else if (k.getNodeType() == Node.ELEMENT_NODE && "tablerow".equals(k.getNodeName())) {
						origRows.add((Element) k);
					}
				}
			}
			boolean sameCols = original != null && origCols == cols;
			boolean sameRows = sameCols && origRows.size() == rows.size();
			out.append("<table");
			java.util.LinkedHashMap<String, String> ta = new java.util.LinkedHashMap<>();
			if (original != null) {
				org.w3c.dom.NamedNodeMap map = original.getAttributes();
				for (int i = 0; i < map.getLength(); i++) {
					if (!map.item(i).getNodeName().startsWith("xmlns")) {
						ta.put(map.item(i).getNodeName(), map.item(i).getNodeValue());
					}
				}
			}
			String shown = original == null ? "" : original.getAttribute("widthtype").startsWith("fit") ? "100%"
					: DxlBody.px(original.getAttribute("refwidth"));
			if (original == null || !w.replace("px", "").equals(shown.replace("px", ""))) {
				ta.put("widthtype", fit ? "fitmargins" : "fixedleft");
				if (fit) {
					ta.remove("refwidth");
				} else {
					ta.put("refwidth", inches(ref));
				}
			}
			for (java.util.Map.Entry<String, String> e : ta.entrySet()) {
				out.append(' ').append(e.getKey()).append("='").append(xmlAttr(e.getValue())).append('\'');
			}
			out.append('>');
			if (sameCols) {
				for (Node k = original.getFirstChild(); k != null; k = k.getNextSibling()) {
					if (k.getNodeType() == Node.ELEMENT_NODE && "tablecolumn".equals(k.getNodeName())) {
						xml(k, out);
					}
				}
			} else {
				for (int i = 0; i < cols; i++) {
					int width = widths[i] != null ? widths[i] : (ref != null ? ref : 624) / cols;
					out.append("<tablecolumn width='").append(inches(width)).append("'/>");
				}
			}
			int rowAt = 0;
			for (HNode r : rows) {
				out.append("<tablerow");
				if (sameRows) {
					org.w3c.dom.NamedNodeMap map = origRows.get(rowAt).getAttributes();
					for (int i = 0; i < map.getLength(); i++) {
						if (!map.item(i).getNodeName().startsWith("xmlns")) {
							out.append(' ').append(map.item(i).getNodeName()).append("='")
									.append(xmlAttr(map.item(i).getNodeValue())).append('\'');
						}
					}
				}
				rowAt++;
				out.append('>');
				for (HNode cell : cells(r)) {
					HashMap<String, String> css = css(cell.attr("style"));
					out.append("<tablecell");
					int span = number(cell.attr("colspan"));
					if (span > 1) {
						out.append(" columnspan='").append(span).append('\'');
					}
					int rowspan = number(cell.attr("rowspan"));
					if (rowspan > 1) {
						out.append(" rowspan='").append(rowspan).append('\'');
					}
					String bw = first(css.get("border-width"), "");
					if (bw.matches("[0-9.]+px( [0-9.]+px){0,3}") && !"1px".equals(bw)) {
						out.append(" borderwidth='").append(bw).append('\'');
					}
					String bg = color(first(css.get("background-color"), cell.attr("bgcolor")));
					if (!bg.isEmpty()) {
						out.append(" bgcolor='").append(bg).append('\'');
					}
					String va = first(css.get("vertical-align"), cell.attr("valign"));
					if ("middle".equals(va) || "center".equals(va)) {
						out.append(" valign='center'");
					} else if ("bottom".equals(va)) {
						out.append(" valign='bottom'");
					}
					out.append('>');
					Ctx d = c.copy();
					d.inCell = true;
					d.list = "";
					d.listDepth = -1;
					d.indentPx = 0;
					d.align = "";
					d.pd = "";
					int before = parCount;
					for (HNode k : cell.kids) {
						node(k, d);
					}
					closePar();
					if (parCount == before) {
						emitPar(pardefFor(d), "");
					}
					out.append("</tablecell>");
				}
				out.append("</tablerow>");
			}
			out.append("</table>");
		}

		static List<HNode> cells(HNode row) {
			List<HNode> out = new ArrayList<>();
			for (HNode k : row.kids) {
				if ("td".equals(k.name) || "th".equals(k.name)) {
					out.add(k);
				}
			}
			return out;
		}

		/* ---- small parsers ---- */

		static HashMap<String, String> css(String style) {
			HashMap<String, String> map = new HashMap<>();
			if (style == null) {
				return map;
			}
			for (String decl : style.split(";")) {
				int colon = decl.indexOf(':');
				if (colon > 0) {
					map.put(decl.substring(0, colon).trim().toLowerCase(), decl.substring(colon + 1).trim());
				}
			}
			return map;
		}

		static Integer cssPx(String v) {
			if (v == null) {
				return null;
			}
			Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)px").matcher(v.trim());
			return m.matches() ? (int) Math.round(Double.parseDouble(m.group(1))) : null;
		}

		static String cssPt(String v) {
			if (v == null) {
				return "";
			}
			String t = v.trim();
			if (t.matches("[0-9]{1,3}(\\.[0-9]+)?pt")) {
				return t;
			}
			Integer px = cssPx(t);
			return px == null ? "" : Math.round(px * 0.75) + "pt";
		}

		/* the CSS font the renderer wrote -> the Notes font name */
		static String fontName(String family) {
			String f = family.trim();
			if ("Arial, sans-serif".equals(f)) {
				return "Default Sans Serif";
			}
			if ("'Times New Roman', serif".equals(f)) {
				return "Default Serif";
			}
			if ("'Courier New', monospace".equals(f)) {
				return "Default Monospace";
			}
			String name = f.split(",")[0].trim();
			return name.replaceAll("^['\"]|['\"]$", "");
		}

		/* a colour DXL takes: #rrggbb (rgb() converted, Notes names mapped) */
		static String color(String c) {
			if (c == null) {
				return "";
			}
			String v = c.trim().toLowerCase();
			if (v.matches("#[0-9a-f]{6}")) {
				return v;
			}
			Matcher m = Pattern.compile("rgba?\\(\\s*([0-9]{1,3})\\s*,\\s*([0-9]{1,3})\\s*,\\s*([0-9]{1,3}).*\\)").matcher(v);
			if (m.matches()) {
				return String.format("#%02x%02x%02x", Integer.parseInt(m.group(1)) & 255,
						Integer.parseInt(m.group(2)) & 255, Integer.parseInt(m.group(3)) & 255);
			}
			String named = NOTES_COLORS.get(v);
			return named == null ? "" : named;
		}

		static String inches(double px) {
			return String.format(java.util.Locale.ROOT, "%.4fin", px / 96.0);
		}

		static int number(String s) {
			try {
				return Integer.parseInt(s.trim());
			} catch (Exception e) {
				return 0;
			}
		}

		static String textOf(HNode n) {
			if ("#text".equals(n.name)) {
				return n.text;
			}
			StringBuilder sb = new StringBuilder();
			for (HNode k : n.kids) {
				sb.append(textOf(k));
			}
			return sb.toString();
		}

		/* a replica ID as the importer takes it: 16 hex digits, no colon. The
		 * colon form Notes displays (86258E80:00474322) is refused with
		 * "Hexadecimal number value is invalid or too large" (live 2026-09-29) */
		static String replica(String r) {
			String plain = r.replace(":", "");
			return plain.matches("[0-9A-Fa-f]{1,16}") ? plain : r;
		}

		static String urlDecode(String s) {
			try {
				return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
			} catch (Exception e) {
				return s;
			}
		}

	}

	/* an original element with some attributes replaced or added */
	private static void xmlWith(Element e, java.util.Map<String, String> over, StringBuilder sb) {
		if (over.isEmpty()) {
			xml(e, sb);
			return;
		}
		java.util.LinkedHashMap<String, String> a = new java.util.LinkedHashMap<>();
		org.w3c.dom.NamedNodeMap attrs = e.getAttributes();
		for (int i = 0; i < attrs.getLength(); i++) {
			if (!attrs.item(i).getNodeName().startsWith("xmlns")) {
				a.put(attrs.item(i).getNodeName(), attrs.item(i).getNodeValue());
			}
		}
		a.putAll(over);
		sb.append('<').append(e.getNodeName());
		for (java.util.Map.Entry<String, String> at : a.entrySet()) {
			sb.append(' ').append(at.getKey()).append("='").append(xmlAttr(at.getValue())).append('\'');
		}
		if (!e.hasChildNodes()) {
			sb.append("/>");
			return;
		}
		sb.append('>');
		for (Node k = e.getFirstChild(); k != null; k = k.getNextSibling()) {
			xml(k, sb);
		}
		sb.append("</").append(e.getNodeName()).append('>');
	}

	/* an original DXL element written back as it was */
	private static void xml(Node n, StringBuilder sb) {
		short type = n.getNodeType();
		if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
			sb.append(xmlText(n.getNodeValue()));
			return;
		}
		if (type != Node.ELEMENT_NODE) {
			return;
		}
		sb.append('<').append(n.getNodeName());
		org.w3c.dom.NamedNodeMap attrs = n.getAttributes();
		for (int i = 0; i < attrs.getLength(); i++) {
			Node a = attrs.item(i);
			if (!a.getNodeName().startsWith("xmlns")) {
				sb.append(' ').append(a.getNodeName()).append("='").append(xmlAttr(a.getNodeValue())).append('\'');
			}
		}
		if (!n.hasChildNodes()) {
			sb.append("/>");
			return;
		}
		sb.append('>');
		for (Node k = n.getFirstChild(); k != null; k = k.getNextSibling()) {
			xml(k, sb);
		}
		sb.append("</").append(n.getNodeName()).append('>');
	}

	/* XML text: escaped, and without the characters XML 1.0 cannot hold */
	private static String xmlText(String s) {
		StringBuilder out = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '&') {
				out.append("&amp;");
			} else if (c == '<') {
				out.append("&lt;");
			} else if (c == '>') {
				out.append("&gt;");
			} else if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
				if (c != 0xFFFE && c != 0xFFFF) {
					out.append(c);
				}
			}
		}
		return out.toString();
	}

	private static String xmlAttr(String s) {
		return xmlText(s).replace("'", "&apos;").replace("\"", "&quot;");
	}

	/* ------------------------------------------------------------- reports */

	/* the kit version, first line of every report (kit/CHANGELOG.md) */
	public static final String VERSION = "1.0.1 (2026-09-29)";
	private static final String KIT = "RichText kit " + VERSION;

	/* a report section longer than this is cut */
	private static final int MAX_SECTION = 200000;

	/* base64 as DXL and data: URIs hold it - long runs, line breaks included */
	private static final Pattern LONG_BASE64 = Pattern.compile("[A-Za-z0-9+/=\r\n]{240,}");

	/*
	 * How the item converts, as plain text for the developer: whether the
	 * web can edit it and why not, the elements kept whole, the elements the
	 * renderer does not know, the HTML it makes and the item's DXL (base64
	 * shortened). It holds the document's content: show it only to users who
	 * may edit the document (the app's report page, "copy and send it").
	 */
	public String report(Document doc, String item) throws NotesException {
		StringBuilder r = new StringBuilder();
		r.append(KIT).append(" - rich-text report\n");
		r.append("When:     ").append(now()).append('\n');
		r.append("Document: ").append(doc.getUniversalID()).append("  form ").append(doc.getItemValueString("Form"))
				.append("  item ").append(item).append('\n');
		Item it = doc.getFirstItem(item);
		if (it == null) {
			return r.append("The document has no item ").append(item).append(".\n").toString();
		}
		int type;
		int length;
		try {
			type = it.getType();
			length = it.getValueLength();
		} finally {
			recycle(it);
		}
		r.append("Item:     ").append(type == Item.RICHTEXT ? "classic rich text" : type == Item.MIME_PART ? "MIME"
				: "type " + type).append(", ").append(length).append(" bytes\n");

		// a fresh instance: its unknown set, error and islands are this
		// item's alone; it keeps the DXL it exported, so the item travels once
		RichText probe = new RichText(m_session).attachments(m_fileDbUrl).pictureBudget(m_pictureBudget);
		probe.m_linkReplica = m_linkReplica;
		probe.m_linkPrefix = m_linkPrefix;
		probe.m_keepDxl = true;
		Body body;
		try {
			body = probe.read(doc, item);
		} finally {
			probe.recycle();
		}
		r.append("Web:      ").append(body.locked ? "READ-ONLY - " + body.reason : "editable").append('\n');
		if (type == Item.RICHTEXT) {
			r.append("Kept whole (saved as they are): ").append(counted(probe.m_lastKept)).append('\n');
		}
		r.append("Unknown elements: ").append(probe.m_unknown.isEmpty() ? "none" : String.join(", ", probe.m_unknown)).append('\n');
		r.append("Pictures shown: ").append(probe.m_pictureBytes).append(" bytes\n");
		if (probe.m_firstError != null) {
			r.append("Conversion error: ").append(probe.m_firstError).append('\n');
		}
		section(r, "the item's DXL (base64 shortened)", probe.m_lastDxl == null ? null : itemDxl(probe.m_lastDxl, item));
		section(r, "the HTML the page shows (picture data shortened)", body.html);
		return r.toString();
	}

	/* the exporter this instance made, when the request is over (optional:
	 * an agent session frees it anyway) */
	public void recycle() {
		recycle(m_exporter);
		m_exporter = null;
	}

	/* write()'s account of a failed save - see writeReport() */
	private String failureReport(Exception e, String unid, String form, String item, String html,
			String richtext, String originalItem) {
		StringBuilder r = new StringBuilder();
		r.append(KIT).append(" - rich-text save failed\n");
		r.append("When:     ").append(now()).append('\n');
		r.append("Document: ").append(unid).append("  form ").append(form).append("  item ").append(item).append('\n');
		r.append("Problem:  ").append(e.getMessage()).append('\n');
		for (java.util.Map.Entry<String, String[]> f : m_newFiles.entrySet()) {
			r.append("Attached: ").append(f.getValue()[0]).append(" (marker ").append(f.getKey()).append(", ")
					.append(isBase64(f.getValue()[1]) ? decodedSize(f.getValue()[1]) + " bytes" : "NOT base64").append(")\n");
		}
		java.io.StringWriter trace = new java.io.StringWriter();
		e.printStackTrace(new java.io.PrintWriter(trace));
		section(r, "where", trace.toString());
		section(r, "DXL importer log", m_importLog.isEmpty() ? null : m_importLog);
		section(r, "the posted HTML, sanitized (picture data shortened)", html);
		section(r, "the <richtext> made of it", richtext);
		section(r, "the item before the save (base64 shortened)", originalItem);
		return r.toString();
	}

	private static void section(StringBuilder r, String title, String content) {
		r.append("\n--- ").append(title).append(" ---\n").append(shorten(content, MAX_SECTION)).append('\n');
	}

	/* long base64 runs cut to their first characters and their length */
	private static String shorten(String s, int max) {
		if (s == null) {
			return "(none)";
		}
		StringBuilder out = new StringBuilder();
		Matcher m = LONG_BASE64.matcher(s);
		int last = 0;
		while (out.length() < max && m.find()) {
			out.append(s, last, m.start() + 40).append("...[").append(m.end() - m.start() - 40)
					.append(" base64 characters left out]");
			last = m.end();
		}
		out.append(s, last, Math.min(s.length(), last + max));
		if (out.length() > max) {
			out.setLength(max);
			out.append("\n[cut at ").append(max).append(" characters]");
		}
		return out.toString();
	}

	/* the item's <item> elements out of a document's DXL */
	private static String itemDxl(String dxl, String item) {
		StringBuilder out = new StringBuilder();
		int at = 0;
		int[] span;
		while ((span = nextItem(dxl, item, at)) != null) {
			out.append(dxl, span[0], span[1]).append('\n');
			at = span[1];
		}
		return out.length() == 0 ? dxl : out.toString();
	}

	/* [a, b, a] -> "a x2, b"; "none" when empty */
	private static String counted(List<String> names) {
		java.util.LinkedHashMap<String, Integer> n = new java.util.LinkedHashMap<>();
		for (String s : names) {
			n.put(s, n.containsKey(s) ? n.get(s) + 1 : 1);
		}
		StringBuilder out = new StringBuilder();
		for (java.util.Map.Entry<String, Integer> e : n.entrySet()) {
			out.append(out.length() > 0 ? ", " : "").append(e.getKey()).append(e.getValue() > 1 ? " x" + e.getValue() : "");
		}
		return out.length() == 0 ? "none" : out.toString();
	}

	private static String now() {
		return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT).format(new java.util.Date());
	}

	/* -------------------------------------------------------------- helpers */

	/* a when it has a value, else b, never null */
	private static String first(String a, String b) {
		return a != null && !a.isEmpty() ? a : b == null ? "" : b;
	}

	private static int letters(String s) {
		int n = 0;
		for (int i = 0; s != null && i < s.length(); i++) {
			if (Character.isLetter(s.charAt(i))) {
				n++;
			}
		}
		return n;
	}

	/* letters of the visible text: tags (so data: URIs too) and entities
	 * skipped */
	private static int htmlLetters(String html) {
		int n = 0;
		boolean inTag = false;
		for (int i = 0; i < html.length(); i++) {
			char c = html.charAt(i);
			if (inTag) {
				inTag = c != '>';
			} else if (c == '<') {
				inTag = true;
			} else if (c == '&') {
				int semi = html.indexOf(';', i);
				if (semi > i && semi - i <= 10) {
					i = semi;
				}
			} else if (Character.isLetter(c)) {
				n++;
			}
		}
		return n;
	}

	private static int count(Pattern p, String s) {
		int n = 0;
		Matcher m = p.matcher(s);
		while (m.find()) {
			n++;
		}
		return n;
	}

	private static String lower(String s) {
		return s == null ? "" : s.trim().toLowerCase();
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;").replace("'", "&#39;");
	}

	/* recycle, swallowing failures - a cleanup must not replace a result */
	private static void recycle(Base... objects) {
		for (Base o : objects) {
			if (o != null) {
				try {
					o.recycle();
				} catch (NotesException e) {
					// already gone
				}
			}
		}
	}
}
