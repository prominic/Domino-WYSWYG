import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.prominic.HtmlSanitizer;

/*
 * Offline check of the rich-text kit (../RICHTEXT.md): the sanitizer, and
 * the server-free parts of RichText - picture inlining, picture
 * extraction, the letter count - through reflection. It compiles against
 * the two kit files and nothing else, so it also proves the kit is still
 * self-contained. Run it with Check-RichTextKit.ps1. The Domino
 * conversion itself needs a server (RICHTEXT.md, "Verify").
 */
public class RichTextCheck {
	static int fails = 0;

	static void check(String name, boolean ok, String detail) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + (ok ? "" : "\n        " + detail));
		if (!ok) fails++;
	}

	static void has(String name, String out, String... needles) {
		for (String n : needles) check(name + " has [" + n + "]", out.contains(n), out);
	}

	static void lacks(String name, String out, String... needles) {
		for (String n : needles) check(name + " lacks [" + n + "]", !out.toLowerCase().contains(n.toLowerCase()), out);
	}

	public static void main(String[] a) throws Exception {
		System.out.println("Sanitizer - what Domino's converter emits");
		String notes = "<html><head><meta http-equiv=Content-Type content=\"text/html\"><title>Memo</title>"
				+ "<style>p{color:red}</style></head><body>"
				+ "<font size=2 face=\"sans-serif\">Hello </font><font size=2 color=red face=\"sans-serif\"><b>world</b></font><br>"
				+ "<img src=cid:_1_0C2A@notes style=\"border:0px solid;\" width=320 height=200>"
				+ "<IMG SRC=\"CID:_2_0D3B\" ALT=\"chart\">"
				+ "<table width=100% border=1 cellpadding=2 style=\"border-collapse:collapse;\"><tr valign=top>"
				+ "<td width=50% bgcolor=#C0C0C0 style=\"border-style:solid;border-color:#000000;border-width:1px 1px 1px 1px;padding:0px 2px;\">cell</td></tr></table>"
				+ "<div align=center><span style=\"font-size:10pt;font-family:&quot;Default Sans Serif&quot;\">centered</span></div>"
				+ "<!--[if gte mso 9]><xml><o:x>junk</o:x></xml><![endif]-->tail"
				+ "</body></html>";
		String out = HtmlSanitizer.clean(notes);
		System.out.println("        -> " + out);
		has("notes", out, "<font size=\"2\" face=\"sans-serif\">Hello </font>",
				"<font color=\"red\" size=\"2\" face=\"sans-serif\"><b>world</b></font>",
				"<img src=\"cid:_1_0C2A@notes\" width=\"320\" height=\"200\" style=\"border: 0px solid\" />",
				"<img src=\"cid:_2_0D3B\" alt=\"chart\" />",
				"<table border=\"1\" cellpadding=\"2\" width=\"100%\" style=\"border-collapse: collapse\">",
				"<tr valign=\"top\">",
				"<td width=\"50%\" bgcolor=\"#C0C0C0\" style=\"border-style: solid; border-color: #000000; border-width: 1px 1px 1px 1px; padding: 0px 2px\">cell</td>",
				"<div align=\"center\">", "font-family: &quot;Default Sans Serif&quot;", "tail");
		lacks("notes", out, "Memo", "p{color", "junk", "<html", "<head", "<body", "<meta");

		System.out.println("Sanitizer - hostile input");
		String[][] hostile = {
				{ "<img src=x onerror=alert(1)>", "<img" },
				{ "<img src=\"javascript:alert(1)\">", "<img" },
				{ "<img src=\"data:image/svg+xml;base64,PHN2Zz4=\">", "<img" },
				{ "<img src=\"http://evil/track.gif\">", "<img" },
				{ "<img src=\"data:image/png;base64,QUJD\" onerror=\"alert(1)\">", "onerror" },
				{ "<span style=\"background:url(javascript:alert(1))\">x</span>", "url(" },
				{ "<span style=\"color:expression(alert(1))\">x</span>", "expression" },
				{ "<span style=\"color: red; position: fixed; top:0\">x</span>", "position" },
				{ "<div style=\"margin-left:-9999px\">x</div>", "-9999" },
				{ "<font color=\"red&quot; onmouseover=&quot;x\">t</font>", "onmouseover" },
				{ "<font face=\"x' onmouseover='alert(1)\">t</font>", "onmouseover" },
				{ "<script>alert(1)</script>ok", "alert" },
				{ "<SCRIPT >alert(1)</SCRIPT >ok", "alert" },
				{ "<style>body{display:none}</style>ok", "display" },
				{ "<a href=\"javascript:alert(1)\">x</a>", "javascript" },
				{ "<p style=\"color:red\\;x:expression(1)\">x</p>", "expression" },
				{ "<td style=\"width:100px\" onclick=\"x()\">c</td>", "onclick" },
				{ "<iframe src=\"http://evil\"><b>inside</b></iframe>ok", "inside" },
				{ "<!-- a > b -->ok", "a > b" },
				{ "<img src=\"data:image/png;base64,QUJD\\\" onerror=alert(1) x=\\\"\">", "onerror" } };
		for (String[] h : hostile) {
			String o = HtmlSanitizer.clean(h[0]);
			lacks(h[0], o, h[1]);
		}
		String kept = HtmlSanitizer.clean("<img src=\"data:image/PNG;base64,QU JD\n\" onerror=\"alert(1)\">");
		has("data image kept, normalised", kept, "<img src=\"data:image/png;base64,QUJD\" />");
		has("jpg -> jpeg", HtmlSanitizer.clean("<img src='data:image/jpg;base64,QUJD'>"), "data:image/jpeg;base64,QUJD");
		has("unclosed style drops the rest", "[" + HtmlSanitizer.clean("before<style>x{}") + "]", "[before]");
		has("rgb kept", HtmlSanitizer.clean("<span style=\"color: rgb(255, 0, 0)\">r</span>"), "color: rgb(255, 0, 0)");
		has("plain editor html unchanged", HtmlSanitizer.clean("<p><b>bold</b> and <i>it</i></p><ul><li>one</li></ul>"),
				"<p><b>bold</b> and <i>it</i></p><ul><li>one</li></ul>");

		System.out.println("Sanitizer - output is balanced");
		String[][] balance = {
				{ "</div></div>text", "text" },
				{ "<table><tr><td>cut", "<table><tr><td>cut</td></tr></table>" },
				{ "<p>a<p>b", "<p>a</p><p>b</p>" },
				{ "<ul><li>a<li>b</ul>", "<ul><li>a</li><li>b</li></ul>" },
				{ "<div><p>a</div>after", "<div><p>a</p></div>after" },
				{ "<table><tr><td>1<td>2<tr><td>3</table>", "<table><tr><td>1</td><td>2</td></tr><tr><td>3</td></tr></table>" },
				{ "<p>para<table><tr><td>x</td></tr></table>", "<p>para</p><table><tr><td>x</td></tr></table>" },
				{ "<b>bold</i></b>", "<b>bold</b>" } };
		for (String[] b : balance) {
			String o = HtmlSanitizer.clean(b[0]);
			check(b[0] + " -> " + b[1], o.equals(b[1]), o);
		}
		has("an & in a link is escaped once", HtmlSanitizer.clean("<a href=\"x?a=1&amp;b=2\">l</a><a href='y?c=3&d=4'>m</a>"),
				"href=\"x?a=1&amp;b=2\"", "href=\"y?c=3&amp;d=4\"");
		lacks("an & in a link is escaped once", HtmlSanitizer.clean("<a href=\"x?a=1&amp;b=2\">l</a>"), "&amp;amp;");
		lacks("an escaped scheme is still refused", HtmlSanitizer.clean("<a href=\"&amp;#106;avascript:alert(1)\">x</a>"), "avascript");
		has("doclink kept",HtmlSanitizer.clean("<a href=\"notes://srv/852580/0/ABCD\">doc</a>"), "href=\"notes://srv/852580/0/ABCD\"");
		has("file link kept", HtmlSanitizer.clean("<a href=\"file://share/x.xls\">x</a>"), "href=\"file://share/x.xls\"");

		System.out.println("GET - cid: references become data: URIs after sanitizing");
		Class<?> kit = Class.forName("net.prominic.RichText");
		Method inline = kit.getDeclaredMethod("inlinePictures", String.class, HashMap.class, int.class, java.util.Set.class);
		inline.setAccessible(true);
		HashMap<String, String> pics = new HashMap<>();
		pics.put("_1_0C2A@notes", "data:image/gif;base64,R0lG");
		java.util.Set<String> locked = new java.util.LinkedHashSet<>();
		String in = (String) inline.invoke(null, out, pics, 2, locked);
		System.out.println("        -> " + in);
		has("inline", in, "<img src=\"data:image/gif;base64,R0lG\" width=\"320\" height=\"200\" style=\"border: 0px solid\" />",
				"[picture not shown here");
		lacks("inline", in, "cid:_1_0C2A", "cid:_2_0D3B");
		check("missing picture locks the body", locked.contains("a picture the web page cannot show"), locked.toString());
		java.util.Set<String> locked2 = new java.util.LinkedHashSet<>();
		String none = "<p>no pictures</p>";
		check("no pictures: same string back", inline.invoke(null, none, pics, 0, locked2) == none && locked2.isEmpty(), "changed or locked");
		java.util.Set<String> locked3 = new java.util.LinkedHashSet<>();
		inline.invoke(null, none, pics, 1, locked3);
		check("a picture part nothing references locks the body", locked3.contains("a picture outside the text"), locked3.toString());
		java.util.Set<String> locked4 = new java.util.LinkedHashSet<>();
		HashMap<String, String> one = new HashMap<>();
		one.put("a", "data:image/png;base64,QUJD");
		inline.invoke(null, "<p><img src=\"cid:a\" /></p>", one, 1, locked4);
		check("every part shown: not locked", locked4.isEmpty(), locked4.toString());
		java.util.Set<String> locked5 = new java.util.LinkedHashSet<>();
		String keptPic = (String) inline.invoke(null, "<p><img src=\"cid:p3\" width=\"9\" data-pic=\"3\" /></p>", new HashMap<String, String>(), 0, locked5);
		check("a Notes picture not shown keeps its place: not locked", locked5.isEmpty()
				&& keptPic.equals("<p><span data-pic=\"3\" contenteditable=\"false\">[picture not shown here - open the document in Notes to see it]</span></p>"), keptPic);

		Method hl = kit.getDeclaredMethod("htmlLetters", String.class);
		hl.setAccessible(true);
		int letters = (Integer) hl.invoke(null, "<p class=\"x\">ab<img src=\"data:image/png;base64,QUJDZGVm\" />&amp;&eacute;c</p>");
		check("htmlLetters counts visible letters only (3)", letters == 3, String.valueOf(letters));

		System.out.println("Scale: a 1.5 MB pasted picture goes through the sanitizer");
		StringBuilder big = new StringBuilder("<p>x</p><img src=\"data:image/png;base64,");
		for (int i = 0; i < 2000000 / 4; i++) big.append("QUJD");
		big.append("\">");
		long t = System.currentTimeMillis();
		String bigClean = HtmlSanitizer.clean(big.toString());
		long ms = System.currentTimeMillis() - t;
		check("big picture kept (" + ms + " ms)", bigClean.length() > 2000000 && ms < 3000, ms + " ms");
		has("round-trip markers survive", HtmlSanitizer.clean("<div data-pd=\"3\" onclick=\"x\">a</div><img src=\"cid:p2\" data-pic=\"2\">"
				+ "<span data-cap=\"2\">c</span><div data-pd=\"x1\">b</div>"),
				"<div data-pd=\"3\">a</div>", "<img src=\"cid:p2\" data-pic=\"2\" />", "<span data-cap=\"2\">c</span>", "<div>b</div>");
		String islands = HtmlSanitizer.clean("<span data-keep=\"4\" data-kind=\"actionhotspot\" contenteditable=\"false\">h</span>"
				+ "<div data-keep=\"5\" data-kind=\"section\" contenteditable=\"false\">s</div><span data-pic=\"6\" contenteditable=\"false\">p</span>"
				+ "<span data-keep=\"x\" data-kind=\"Bad1\" contenteditable=\"true\">t</span><p data-keep=\"7\" contenteditable=\"false\">q</p>");
		has("island markers survive", islands, "<span data-keep=\"4\" data-kind=\"actionhotspot\" contenteditable=\"false\">h</span>",
				"<div data-keep=\"5\" data-kind=\"section\" contenteditable=\"false\">s</div>",
				"<span data-pic=\"6\" contenteditable=\"false\">p</span>", "<span>t</span>", "<p>q</p>");

		dxlChecks(kit);
		writerChecks(kit);
		islandChecks(kit);
		fileChecks(kit);
		fidelityChecks(kit);

		System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
		System.exit(fails == 0 ? 0 : 1);
	}

	static final String UNID = "0123456789ABCDEF0123456789ABCDEF";
	static final String FILES = "https://host/dir/data.nsf/0/" + UNID + "/$FILE/";
	static final String PAGE = "https://host/dir/app.nsf/router?openagent&req=page&unid=";

	/* one item's DXL as the exporter writes it (no DOCTYPE) */
	static String dxl(String item, String richtext) {
		return "<?xml version='1.0' encoding='utf-8'?>\n"
				+ "<document xmlns='http://www.lotus.com/dxl' version='12.0' form='Product'>\n"
				+ "<noteinfo noteid='8fa' unid='" + UNID + "' sequence='5'/>\n"
				+ "<item name='" + item + "'><richtext>\n" + richtext + "\n</richtext></item>\n</document>\n";
	}

	/* Domino's DXL DTD (the newest domino_N_N.dtd) in a Notes or Domino
	 * install: -Dnotes.root, NOTES_ROOT, or the usual places; null when none */
	static final String DTD = findDtd();

	static String findDtd() {
		List<String> roots = new ArrayList<>();
		if (System.getProperty("notes.root") != null) roots.add(System.getProperty("notes.root"));
		if (System.getenv("NOTES_ROOT") != null) roots.add(System.getenv("NOTES_ROOT"));
		roots.addAll(java.util.Arrays.asList("C:/Program Files/HCL/Notes", "C:/Program Files/HCL/Domino",
				"C:/Program Files (x86)/HCL/Notes", "C:/Program Files/IBM/Notes", "C:/Program Files (x86)/IBM/Notes",
				"C:/Program Files/IBM/Domino", "/opt/hcl/domino/notes/latest/linux"));
		for (String root : roots) {
			java.io.File dir = new java.io.File(root, "xmlschemas");
			java.io.File[] files = dir.listFiles();
			String best = null;
			long bestVersion = -1;
			if (files != null) {
				for (java.io.File f : files) {
					String n = f.getName();
					if (!n.startsWith("domino_") || !n.endsWith(".dtd")) continue;
					// domino_12_0.dtd, domino_9_0_1.dtd, domino_14_5_1.dtd: the highest version, numerically
					long version = 0;
					for (String part : n.substring(7, n.length() - 4).split("_")) {
						version = version * 1000 + (part.matches("[0-9]+") ? Integer.parseInt(part) : 0);
					}
					while (version < 1000000) version *= 1000; // 12_0 and 9_0_1 on one scale
					if (version > bestVersion) { bestVersion = version; best = n; }
				}
			}
			if (best != null) return new java.io.File(dir, best).getPath().replace('\\', '/');
		}
		return null;
	}

	/* the richtext the writer made, inside a document, against the Domino 12 DTD */
	static void validate(String name, String richtext) throws Exception {
		validateItems(name, "<item name='field4'>" + richtext + "</item>");
	}

	/* items of a document, inside one, against the Domino 12 DTD */
	static void validateItems(String name, String items) throws Exception {
		if (DTD == null) {
			System.out.println("  SKIP  " + name + " - no Domino DTD found (set NOTES_ROOT or -Dnotes.root)");
			return;
		}
		String doc = "<?xml version='1.0' encoding='utf-8'?>\n<!DOCTYPE document SYSTEM 'file:///" + DTD.replace(" ", "%20") + "'>\n"
				+ "<document xmlns='http://www.lotus.com/dxl' version='12.0' form='Page'>" + items + "</document>";
		javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
		f.setValidating(true);
		javax.xml.parsers.DocumentBuilder b = f.newDocumentBuilder();
		final List<String> errors = new ArrayList<>();
		b.setErrorHandler(new org.xml.sax.ErrorHandler() {
			public void warning(org.xml.sax.SAXParseException e) {
			}

			public void error(org.xml.sax.SAXParseException e) {
				errors.add(e.getMessage());
			}

			public void fatalError(org.xml.sax.SAXParseException e) {
				errors.add("FATAL " + e.getMessage());
			}
		});
		try {
			b.parse(new org.xml.sax.InputSource(new java.io.StringReader(doc)));
		} catch (org.xml.sax.SAXException e) {
			// already collected
		}
		check(name + " is valid DXL (" + new java.io.File(DTD).getName() + ")", errors.isEmpty(), String.join(" | ", errors));
	}

	static void writerChecks(Class<?> kit) throws Exception {
		Method render = kit.getDeclaredMethod("renderDxl", String.class, String.class, String.class, String.class,
				String.class, int.class, java.util.Set.class);
		render.setAccessible(true);
		Method toDxl = kit.getDeclaredMethod("toDxl", String.class, String.class, String.class, String.class,
				String.class, String.class);
		toDxl.setAccessible(true);
		String doc = dxl("Body", SCREENSHOT);
		String html = (String) render(render, doc, 2 * 1024 * 1024)[0];

		System.out.println("WRITE - an untouched body goes back as the same rich text");
		String rt = (String) toDxl.invoke(null, html, doc, "Body", FILES, "86258E200059AA43", PAGE);
		System.out.println("        -> " + (rt.length() > 900 ? rt.substring(0, 900) + " ..." : rt));
		String again = (String) render(render, dxlRich("Body", rt), 2 * 1024 * 1024)[0];
		check("render(write(render(DXL))) == render(DXL)", again.equals(html), again + "\n        vs " + html);
		has("originals written back", rt, "description='Product'", "server='CN=server/O=Example'",
				"<attachmentref displayname='brochure.png' name='brochure.png'>", "<caption>brochure.png</caption>",
				"<jpeg>/9j/4AAQSkZJRgABAQ==</jpeg>", "leftmargin='1.5in'", "a\ttab",
				"<font name='Times New Roman' size='12pt' style='bold'/>dasd",
				"<urllink href='https://example.com' showborder='false'>");
		lacks("originals written back", rt, "data-pd", "data-pic", "&#128196;", "cid:");
		validate("untouched body", rt);

		System.out.println("WRITE - edits: a word, a deleted attachment icon, a typed paragraph, a pasted picture");
		int a = html.indexOf("<a href=\"" + FILES);
		String edited = html.substring(0, a) + html.substring(html.indexOf("</a>", a) + 4);
		edited = edited.replace("<b>dasd</b>", "<b>EDITED</b>")
				+ "<div>new line <i>typed</i></div><p><img src=\"data:image/png;base64,iVBORw0KGgo=\" width=\"20\" height=\"10\"></p>";
		rt = (String) toDxl.invoke(null, edited, doc, "Body", FILES, "86258E200059AA43", PAGE);
		has("edits", rt, "style='bold'/>EDITED", "new line <run><font style='italic'/>typed</run>",
				"<picture width='20px' height='10px'><png>iVBORw0KGgo=</png></picture>", "description='Product'");
		lacks("edits", rt, "brochure.png");
		validate("edited body", rt);

		System.out.println("WRITE - what a web editor makes by itself");
		String fresh = "<h3>Title</h3><div>one<br>two</div><ul><li>a</li><li>b<ul><li>b1</li></ul></li></ul>"
				+ "<table><tbody><tr><td>x</td><td><br></td></tr></tbody></table><div><br></div>"
				+ "<div style=\"text-align: center\">mid</div><blockquote>quoted</blockquote>"
				+ "<div><span style=\"color: rgb(255, 0, 0); background-color: #ffff00\">red on yellow</span> &amp; <a href=\"https://x.test/?a=1&amp;b=2\">link</a></div>"
				+ "<div><a href=\"notes://srv/1111222233334444/0/ABCD\">&#128196;</a></div>";
		rt = (String) toDxl.invoke(null, fresh, dxl("Body", ""), "Body", FILES, "86258E200059AA43", PAGE);
		System.out.println("        -> " + rt);
		has("fresh", rt, "<font size='14pt' style='bold'/>Title", "one<break/>two", "list='bullet'", "<table widthtype='fitmargins'>",
				"<tablecolumn width=", "align='center'", "leftmargin='1.5000in'", "<run highlight='yellow'><font color='#ff0000'/>red on yellow</run>",
				" &amp; ", "href='https://x.test/?a=1&amp;b=2'", "<doclink document='ABCD' database='1111222233334444' server='srv'/>");
		validate("editor-made body", rt);
		System.out.println("WRITE - what the toolbar controls make (execCommand output as Chrome writes it)");
		String controls = "<div><font face=\"Arial, sans-serif\" color=\"#ff0000\">def</font> <font face=\"Georgia\">geo</font></div>"
				+ "<div><span style=\"font-size: 18pt;\">big</span></div>"
				+ "<div><span style=\"background-color: rgb(255, 255, 0);\">hi <span style=\"background-color: transparent;\">off</span></span></div>"
				+ "<blockquote style=\"margin: 0 0 0 40px; border: none; padding: 0px;\"><div>ind</div></blockquote>"
				+ "<div style=\"text-align: right;\">r</div>"
				+ "<div><img src=\"data:image/png;base64,iVBORw0KGgo=\" width=\"120\" height=\"80\"></div>";
		rt = (String) toDxl.invoke(null, controls, dxl("Body", ""), "Body", FILES, null, null);
		System.out.println("        -> " + rt);
		has("controls", rt, "<run><font name='Default Sans Serif' color='#ff0000'/>def</run>", "<run><font name='Georgia'/>geo</run>",
				"<run><font size='18pt'/>big</run>", "<run highlight='yellow'><font/>hi </run>off",
				"leftmargin='1.5000in'", "align='right'", "<picture width='120px' height='80px'><png>iVBORw0KGgo=</png></picture>");
		validate("toolbar-made body", rt);
		// what headless Edge (Chromium) actually posted after driving the real
		// toolbar on 2026-09-28: font, bold, size, highlight, colour, center, indent
		String edge = "<div data-pd=\"1\"><font face=\"Georgia\"><b>hello</b></font> <span style=\"font-size: 18pt;\">world</span> "
				+ "<span style=\"background-color: rgb(255, 255, 0);\">again</span></div><blockquote style=\"margin: 0 0 0 40px; border: none; padding: 0px;\">"
				+ "<div data-pd=\"1\" style=\"text-align: center;\"><font color=\"#ff0000\">second</font> line</div></blockquote>";
		rt = (String) toDxl.invoke(null, edge, dxl("Body", "<pardef id='1'/><par def='1'>hello world again</par>"),
				"Body", FILES, null, null);
		System.out.println("        -> " + rt);
		has("as Edge posted it", rt, "<run><font name='Georgia' style='bold'/>hello</run>", "<run><font size='18pt'/>world</run>",
				"<run highlight='yellow'><font/>again</run>", "align='center'", "leftmargin='1.5000in'", "<run><font color='#ff0000'/>second</run> line");
		validate("Edge-made body", rt);
		String empty = (String) toDxl.invoke(null, "", dxl("Body", ""), "Body", FILES, null, null);
		check("an emptied body is one empty paragraph", empty.matches("<richtext><pardef id='1'/><par def='1'/></richtext>"), empty);

		System.out.println("WRITE - replica ids and the encryption guard, as the importer wants them");
		String links = (String) toDxl.invoke(null, "<div><a href=\"notes://srv/1111222233334444/0/ABCD\">a</a></div>",
				dxl("Body", "<par><doclink server='CN=s/O=x' database='86258E20:0059AA43' document='DDDD'/></par>"), "Body", FILES, null, null);
		check("a new doclink's replica id has no colon", links.contains("database='1111222233334444'"), links);
		String kept = (String) toDxl.invoke(null, "<div><a href=\"notes://s/86258E200059AA43/0/DDDD\">x</a></div>",
				dxl("Body", "<par><doclink server='CN=s/O=x' database='86258E20:0059AA43' document='DDDD'/></par>"), "Body", FILES, null, null);
		check("an original doclink's colon form is written without the colon", kept.contains("database='86258E200059AA43'") && !kept.contains(":0059"), kept);
		Method exportable = kit.getDeclaredMethod("requireExportable", String.class);
		exportable.setAccessible(true);
		try {
			exportable.invoke(null, "<document><item name='$FILE' summary='true' sign='true' seal='true'><object/></item></document>");
			check("a $FILE's seal flag does not stop a save", true, "");
		} catch (java.lang.reflect.InvocationTargetException e) {
			check("a $FILE's seal flag does not stop a save", false, String.valueOf(e.getCause()));
		}
		try {
			exportable.invoke(null, "<document><item name='$Seal'><text>x</text></item></document>");
			check("an encrypted document ($Seal) stops a save", false, "no exception");
		} catch (java.lang.reflect.InvocationTargetException e) {
			check("an encrypted document ($Seal) stops a save", String.valueOf(e.getCause().getMessage()).contains("encrypted"), String.valueOf(e.getCause()));
		}

		System.out.println("WRITE - the document around it");
		Method swap = kit.getDeclaredMethod("swapItem", String.class, String.class, String.class);
		swap.setAccessible(true);
		Method drop = kit.getDeclaredMethod("dropFile", String.class, String.class);
		drop.setAccessible(true);
		String full = "<document xmlns='http://www.lotus.com/dxl'><noteinfo unid='X'/><item name='Field1'><text>keep</text></item>\n"
				+ "<item name='field4' sign='true'><richtext><par>old</par></richtext></item>\n"
				+ "<item name='$FILE' summary='true'><object><file hosttype='msdos'\n name='a.png' size='3'><filedata>AAAA</filedata></file></object></item>\n"
				+ "<item name='$FILE' summary='true'><object><file name='b.pdf'><filedata>BBBB</filedata></file></object></item></document>";
		String swapped = (String) swap.invoke(null, full, "field4", "<richtext><par>new</par></richtext>");
		has("swap", swapped, "<item name='field4'><richtext><par>new</par></richtext></item>", "<text>keep</text>", "a.png", "b.pdf");
		lacks("swap", swapped, "old");
		String added = (String) swap.invoke(null, full.replace("field4", "other"), "field4", "<richtext/>");
		has("swap when the item is new", added, "<item name='field4'><richtext/></item>\n</document>");
		String dropped = (String) drop.invoke(null, full, "a.png");
		has("drop a file", dropped, "b.pdf", "<text>keep</text>");
		lacks("drop a file", dropped, "a.png", "AAAA");
	}

	/* Notes-only elements: hotspot, hidden paragraph, sections (one in a
	 * cell), computed text, pass-thru HTML, popup, a cgm picture, an anchor */
	static final String ISLANDS = "<pardef id='1'/><pardef id='2' hide='notes'/><pardef id='3' leftmargin='2in'/>\n"
			+ "<par def='1'>before <actionhotspot hotspotstyle='none'><code event='click'><formula>@Command([FileSave])</formula></code>"
			+ "<run><font style='bold'/>Save it</run></actionhotspot> after</par>\n"
			+ "<par def='2'>hidden text</par>\n"
			+ "<section><sectiontitle pardef='3' color='blue'><font style='bold'/><text>Section title</text></sectiontitle>"
			+ "<par def='3'>inside section</par></section>\n"
			+ "<par def='1'><run><font color='red'/>red <computedtext><code event='value'><formula>@Now</formula></code></computedtext> text</run></par>\n"
			+ "<par def='1'><run html='true'>&lt;b&gt;pass&lt;/b&gt;</run> and <popup show='onclick' hotspotstyle='none'><popuptext>tip</popuptext>"
			+ "<run>hover</run></popup></par>\n"
			+ "<par def='1'>pic <picture width='10px' height='10px'><cgm>AAAA</cgm></picture> anchor<anchor name='here'/></par>\n"
			+ "<table widthtype='fitmargins'><tablecolumn width='3in'/><tablerow><tablecell><section><sectiontitle><text>In a cell</text></sectiontitle>"
			+ "<par def='1'>cell section</par></section></tablecell></tablerow></table>";

	/* every par def / sectiontitle pardef points at a pardef defined before it */
	static String danglingPardef(String rt) {
		java.util.Set<String> defined = new java.util.HashSet<>();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("<pardef id='([0-9]+)'|<par def='([0-9]+)'|<sectiontitle pardef='([0-9]+)'").matcher(rt);
		while (m.find()) {
			if (m.group(1) != null) {
				defined.add(m.group(1));
			} else {
				String ref = m.group(2) != null ? m.group(2) : m.group(3);
				if (!defined.contains(ref)) {
					return "pardef " + ref + " used at " + m.start() + " before it is defined";
				}
			}
		}
		return null;
	}

	static void islandChecks(Class<?> kit) throws Exception {
		Method render = kit.getDeclaredMethod("renderDxl", String.class, String.class, String.class, String.class,
				String.class, int.class, java.util.Set.class);
		render.setAccessible(true);
		Method toDxl = kit.getDeclaredMethod("toDxl", String.class, String.class, String.class, String.class,
				String.class, String.class);
		toDxl.setAccessible(true);

		System.out.println("ISLANDS - Notes-only elements show, cannot be edited, and go back exactly as they were");
		String doc = dxl("Body", ISLANDS);
		Object[] r = render(render, doc, 1000);
		String html = (String) r[0];
		System.out.println("        -> " + html);
		check("islands: the body stays editable", reasons(r).isEmpty(), reasons(r).toString());
		check("islands: counted in order", String.valueOf(r[3]).equals("[actionhotspot, par, section, computedtext, run, popup, anchor, section]"),
				String.valueOf(r[3]));
		has("islands", html, "before <span data-keep=\"1\" data-kind=\"actionhotspot\" contenteditable=\"false\"><b>Save it</b></span> after",
				"<div data-keep=\"2\" data-kind=\"par\" contenteditable=\"false\">hidden text</div>",
				"<div data-keep=\"3\" data-kind=\"section\" contenteditable=\"false\"><div><b>Section title</b></div><div style=\"margin-left: 96px\" data-pd=\"3\">inside section</div></div>",
				"<span style=\"color: #ff0000\">red <span data-keep=\"4\" data-kind=\"computedtext\" contenteditable=\"false\">[computed text]</span> text</span>",
				"<span data-keep=\"5\" data-kind=\"run\" contenteditable=\"false\">&lt;b&gt;pass&lt;/b&gt;</span>",
				"<span data-keep=\"6\" data-kind=\"popup\" contenteditable=\"false\">hover</span>",
				"<span data-pic=\"1\" contenteditable=\"false\">[picture not shown here",
				"<span data-keep=\"7\" data-kind=\"anchor\" contenteditable=\"false\"></span>",
				"<td style=\"border-style: solid; border-color: #000000; border-width: 1px; padding: 2px 4px; vertical-align: top; width: 288px\"><div data-keep=\"8\" data-kind=\"section\"");
		lacks("islands", html, "@Command", "@Now", "tip", "<b>pass");

		String rt = (String) toDxl.invoke(null, html, doc, "Body", FILES, null, null);
		System.out.println("        -> " + rt);
		has("islands written back", rt,
				"before <actionhotspot hotspotstyle='none'><code event='click'><formula>@Command([FileSave])</formula></code><run><font style='bold'/>Save it</run></actionhotspot> after",
				" hide='notes'/><par def=", "><section><sectiontitle color='blue' pardef='",
				"<run><font color='#ff0000'/>red <computedtext><code event='value'><formula>@Now</formula></code></computedtext> text</run>",
				"<run html='true'>&lt;b&gt;pass&lt;/b&gt;</run>", "<popup hotspotstyle='none' show='onclick'><popuptext>tip</popuptext><run>hover</run></popup>",
				"<picture height='10px' width='10px'><cgm>AAAA</cgm></picture>", "<anchor name='here'/>",
				"<tablecell><section><sectiontitle><text>In a cell</text></sectiontitle>");
		lacks("islands written back", rt, "data-keep", "contenteditable", "[computed text]", "picture not shown", "<tablecell><pardef");
		String dangling = danglingPardef(rt);
		check("islands: every pardef reference defined first", dangling == null, dangling + "\n        " + rt);
		String again = (String) render(render, dxlRich("Body", rt), 1000)[0];
		check("islands: render(write(render(DXL))) == render(DXL)", again.equals(html), again + "\n        vs " + html);
		validate("body with islands", rt);

		System.out.println("ISLANDS - deleting one deletes it; stale or forged markers are plain HTML");
		int a = html.indexOf("<span data-keep=\"1\"");
		String edited = html.substring(0, a) + html.substring(html.indexOf("</span>", html.indexOf("</b>", a)) + 7);
		edited = edited.replace(" after", " AFTER");
		rt = (String) toDxl.invoke(null, edited, doc, "Body", FILES, null, null);
		lacks("hotspot deleted", rt, "actionhotspot", "@Command");
		has("hotspot deleted", rt, "before  AFTER", "<section><sectiontitle", "<popup ");
		validate("body with a deleted island", rt);
		String forged = "<div>a <span data-keep=\"99\" data-kind=\"actionhotspot\" contenteditable=\"false\">ghost</span>"
				+ " <span data-keep=\"1\" data-kind=\"section\" contenteditable=\"false\">wrongkind</span></div>"
				+ "<div data-keep=\"1\" data-kind=\"actionhotspot\" contenteditable=\"false\">blockspan</div>";
		rt = (String) toDxl.invoke(null, forged, doc, "Body", FILES, null, null);
		has("forged markers", rt, "ghost", "wrongkind", "blockspan");
		lacks("forged markers", rt, "<actionhotspot", "<section");
		validate("body with forged markers", rt);
	}

	/* what the writer must keep on UNTOUCHED content although the HTML has
	 * no place for it: list types, a table's own settings, a picture's size */
	static void fidelityChecks(Class<?> kit) throws Exception {
		Method render = kit.getDeclaredMethod("renderDxl", String.class, String.class, String.class, String.class,
				String.class, int.class, java.util.Set.class);
		render.setAccessible(true);
		Method toDxl = kit.getDeclaredMethod("toDxl", String.class, String.class, String.class, String.class,
				String.class, String.class);
		toDxl.setAccessible(true);
		System.out.println("FIDELITY - untouched lists, tables and pictures keep what HTML cannot show");
		String rt = FIDELITY;
		String doc = dxl("Body", rt);
		Object[] r = render(render, doc, 1000000);
		String html = (String) r[0];
		System.out.println("        -> " + html.replaceAll("base64,[^\"]*", "base64,..."));
		has("fidelity render", html, "<ol data-list=\"alphaupper\">", "<ul data-list=\"check\">", "<table style=\"border-collapse: collapse; width: 100%\" data-tbl=\"1\">",
				"width=\"200\" height=\"100\" data-pic=\"1\"");
		String back = (String) toDxl.invoke(null, html, doc, "Body", FILES, null, null);
		System.out.println("        -> " + back.replaceAll("<gif>[^<]*</gif>", "<gif>...</gif>"));
		has("fidelity written back", back, "list='alphaupper'", "list='check'",
				"<table bgcolor='#ffffcc' cellborderstyle='ridge' colorstyle='solid' leftmargin='0.5in' rowdisplay='tabs' widthtype='fitmargins'><tablecolumn width='2in'/><tablecolumn width='1in'/><tablerow tablabel='Tab one'>",
				"<picture height='50px' scaledheight='1.0417in' scaledwidth='2.0833in' width='100px'>");
		validate("fidelity body", back);
		String again = (String) render(render, dxlRich("Body", back), 1000000)[0];
		check("fidelity: render(write(render)) == render", again.equals(html), again);

		System.out.println("COLORS - every named colour DXL writes shows in its CSS2 value and comes back");
		Object[] cr = (Object[]) render.invoke(null, dxl("Body", COLORS), "Body", FILES, null, null, 1000000, new java.util.TreeSet<String>());
		String chtml = (String) cr[0];
		has("colours render", chtml, "<span style=\"color: #008000\">green</span>", "<span style=\"color: #00ff00\">lime</span>",
				"<span style=\"color: #800080\">purple</span>", "<span style=\"color: #ff00ff\">fuchsia</span>", "<span style=\"color: #000080\">navy</span>",
				"<span style=\"color: #800000\">maroon</span>", "<span style=\"color: #123456\">hex</span>", " none ", " system</div>");
		lacks("colours render", chtml, "color: none", "color: system");
		String cback = (String) toDxl.invoke(null, chtml, dxl("Body", COLORS), "Body", FILES, null, null);
		String[] colorsRow = cycleRow("colors");
		for (int i = 2; i < colorsRow.length; i++) {
			check("colours: keeps [" + colorsRow[i] + "]", cback.contains(colorsRow[i]), cback);
		}
		validate("colours body", cback);

		System.out.println("EXTRAS - rules, link attributes, effects, raw data, cell settings: shown, kept, and edited around");
		java.util.TreeSet<String> extrasUnknown = new java.util.TreeSet<>();
		Object[] xr = (Object[]) render.invoke(null, dxl("Body", EXTRAS), "Body", FILES, null, null, 1000000, extrasUnknown);
		String xhtml = (String) xr[0];
		System.out.println("        -> " + xhtml);
		has("extras render", xhtml, "<hr data-hr=\"1\" />", "<a href=\"https://example.com\">", "<span data-fx=\"shadow\"><b>shadowed</b></span>",
				"raw<span data-keep=\"1\" data-kind=\"compositedata\" contenteditable=\"false\"></span>data<span data-keep=\"2\" data-kind=\"nonxmlchar\" contenteditable=\"false\"></span>x",
				"vertical-align: middle");
		lacks("extras render", xhtml, "Yg4BAIQ", "cellbackground", "bg.gif");
		check("extras: nothing unknown, editable", extrasUnknown.isEmpty() && reasons(xr).isEmpty(), extrasUnknown + " " + reasons(xr));
		String xback = (String) toDxl.invoke(null, xhtml, dxl("Body", EXTRAS), "Body", FILES, null, null);
		System.out.println("        -> " + xback);
		String[] extrasRow = cycleRow("extras");
		for (int i = 2; i < extrasRow.length; i++) {
			check("extras: untouched keeps [" + extrasRow[i] + "]", xback.contains(extrasRow[i]), xback);
		}
		validate("extras body", xback);
		String extrasAgain = (String) ((Object[]) render.invoke(null, dxlRich("Body", xback), "Body", FILES, null, null, 1000000,
				new java.util.TreeSet<String>()))[0];
		check("extras: render(write(render)) == render", extrasAgain.equals(xhtml), extrasAgain);
		String extrasEdited = xhtml.replace("<a href=\"https://example.com\">", "<a href=\"https://example.org/\">").replace("vertical-align: middle", "vertical-align: bottom")
				.replace("<span data-fx=\"shadow\"><b>shadowed</b></span>", "<b>plain now</b>");
		xback = (String) toDxl.invoke(null, extrasEdited, dxl("Body", EXTRAS), "Body", FILES, null, null);
		has("extras edits", xback, "<urllink showborder='false' href='https://example.org/'>", "valign='bottom'", "<run><font style='bold'/>plain now</run>",
				"rowheader='true'", "<cellbackground repeat='tile'>");
		lacks("extras edits", xback, "targetframe='_blank'", "shadow");
		validate("extras edited body", xback);

		System.out.println("FIDELITY - what the editor changes: a list type, a resized picture, a column added, a wider table");
		String edited = html.replace("<ol data-list=\"alphaupper\">", "<ol data-list=\"romanlower\">").replace("<ul data-list=\"check\">", "<ul data-list=\"bullet\">")
				.replace("width=\"200\" height=\"100\" data-pic=\"1\"", "width=\"50\" height=\"25\" data-pic=\"1\"")
				.replace("<div data-pd=\"3\">y</div></td></tr>", "<div data-pd=\"3\">y</div></td><td>new</td></tr>");
		back = (String) toDxl.invoke(null, edited, doc, "Body", FILES, null, null);
		has("fidelity edits", back, "list='romanlower'", "list='bullet'", "scaledwidth='0.5208in'", "scaledheight='0.2604in'",
				"rowdisplay='tabs'", "<tablecolumn width='", "<tablerow><tablecell");
		lacks("fidelity edits", back, "check", "tablabel", "width='2in'");
		validate("fidelity edited body", back);
		String wider = html.replace("border-collapse: collapse; width: 100%", "border-collapse: collapse; width: 300px");
		back = (String) toDxl.invoke(null, wider, doc, "Body", FILES, null, null);
		has("fidelity: a table made fixed-width keeps its other settings", back, "widthtype='fixedleft'", "refwidth='3.1250in'", "cellborderstyle='ridge'", "tablabel='Tab one'");
		validate("fidelity fixed-width body", back);
	}

	static void fileChecks(Class<?> kit) throws Exception {
		Method m = kit.getDeclaredMethod("toDxlWithFiles", String.class, String.class, String.class, String.class,
				java.util.Map.class, java.util.Set.class, String.class, String.class, String.class);
		m.setAccessible(true);
		System.out.println("FILES - attached in the editor, stored as attachments; links to pages become doclinks");
		java.util.Map<String, String[]> files = new java.util.HashMap<>();
		files.put("1", new String[] { "C:" + (char) 92 + "fakepath" + (char) 92 + "report.pdf", "JVBERi0xLjQK" });
		files.put("2", new String[] { "a.png", "iVBORw0KGgo=" });
		files.put("3", new String[] { "unused.txt", "QUJD" });
		files.put("4", new String[] { "x'<y>|z.txt", "QUJD" });
		java.util.Set<String> taken = new java.util.TreeSet<>();
		taken.add("A.PNG");
		String html = "<div>see <span data-file=\"1\" contenteditable=\"false\">&#128206; report.pdf</span> and "
				+ "<span data-file=\"2\" contenteditable=\"false\">a.png</span> <span data-file=\"4\">x</span></div>"
				+ "<div><a href=\"" + PAGE.replace("&", "&amp;") + "ABCDEF0123456789ABCDEF0123456789&amp;edit=1\">other page</a> "
				+ "<a href=\"notes://notesserver/86258E8000474322/0/ABCDEF0123456789ABCDEF0123456789\">notes</a></div>";
		Object[] r = (Object[]) m.invoke(null, html, dxl("field4", ""), "field4", FILES, files, taken, "86258E8000474322", PAGE,
				"0123456789ABCDEF0123456789ABCDEF");
		String rt = (String) r[0];
		String doc = (String) r[1];
		System.out.println("        -> " + rt);
		has("files", rt, "see <attachmentref name='report.pdf' displayname='report.pdf'><picture width='28px' height='34px'><png>iVBOR",
				"<caption>report.pdf</caption></picture></attachmentref> and <attachmentref name='a (2).png'",
				"<attachmentref name='x__y__z.txt'",
				"<doclink document='ABCDEF0123456789ABCDEF0123456789' view='0123456789ABCDEF0123456789ABCDEF' database='86258E8000474322'>other page</doclink>",
				"<doclink document='ABCDEF0123456789ABCDEF0123456789' database='86258E8000474322' server='notesserver'>notes</doclink>");
		has("files stored", doc, "<item name='$FILE' summary='true'><object><file hosttype='msdos' compression='none' flags='storedindoc'"
				+ " encoding='none' name='report.pdf' size='9'>", "<filedata>JVBERi0xLjQK</filedata>", "name='a (2).png' size='8'",
				"name='x__y__z.txt' size='3'");
		lacks("files stored", doc, "unused.txt", "fakepath");
		check("files: three stored", ((List<?>) r[2]).size() == 3, String.valueOf(((List<?>) r[2]).size()));
		validateItems("document with attached files", doc.substring(doc.indexOf("<item "), doc.lastIndexOf("</document>")));
		try {
			m.invoke(null, "<div><span data-file=\"9\">lost</span></div>", dxl("field4", ""), "field4", FILES, files, taken, null, null, null);
			check("a file marker with no file fails the save", false, "saved");
		} catch (java.lang.reflect.InvocationTargetException e) {
			check("a file marker with no file fails the save", String.valueOf(e.getCause().getMessage()).contains("did not arrive"),
					String.valueOf(e.getCause()));
		}
		// what headless Edge posted on 2026-09-29 after driving the real toolbar
		// (editor-check.js, EDITOR_DUMP=1): table rows/columns added and one
		// deleted, a file attached, an island kept, marked HTML pasted
		String edge = "<div data-pd=\"1\"><font face=\"Georgia\"><b>hello</b></font> <span style=\"font-size: 18pt;\">world</span> "
				+ "<span style=\"background-color: rgb(255, 255, 0);\">again</span></div><span data-file=\"1\" contenteditable=\"false\">&#128206; a.txt</span>&nbsp;"
				+ "<blockquote style=\"margin: 0 0 0 40px; border: none; padding: 0px;\"><div data-pd=\"1\" style=\"text-align: center;\"><font color=\"#ff0000\">second</font> line</div></blockquote>"
				+ "<div>a <span data-keep=\"1\" data-kind=\"actionhotspot\" contenteditable=\"false\"><b>hot</b></span> zPASTED</div>"
				+ "<table><tbody><tr><td>c2</td><td><br></td></tr><tr><td><br></td><td><br></td></tr></tbody></table>";
		String edgeDxl = dxl("field4", "<pardef id='1'/><par def='1'>hello world again</par><par def='1'>second line</par>"
				+ "<par def='1'>a <actionhotspot hotspotstyle='none'><code event='click'><formula>1</formula></code><run><font style='bold'/>hot</run></actionhotspot> z</par>"
				+ "<table><tablecolumn width='1in'/><tablecolumn width='1in'/><tablerow><tablecell><par def='1'>c1</par></tablecell><tablecell><par def='1'>c2</par></tablecell></tablerow></table>");
		java.util.Map<String, String[]> one = new java.util.HashMap<>();
		one.put("1", new String[] { "a.txt", "aGVsbG8=" });
		r = (Object[]) m.invoke(null, edge, edgeDxl, "field4", FILES, one, new java.util.TreeSet<String>(), null, null, null);
		rt = (String) r[0];
		System.out.println("        -> " + rt.replaceAll("<png>[^<]*</png>", "<png>...</png>"));
		has("as Edge posted it", rt, "<attachmentref name='a.txt' displayname='a.txt'>",
				"a <actionhotspot hotspotstyle='none'><code event='click'><formula>1</formula></code><run><font style='bold'/>hot</run></actionhotspot> zPASTED",
				"<tablerow><tablecell><par def='1'>c2</par></tablecell><tablecell><par def='1'/></tablecell></tablerow><tablerow>");
		has("as Edge posted it, stored", (String) r[1], "name='a.txt' size='5'", "<filedata>aGVsbG8=</filedata>");
		validateItems("the Edge-made body with a file", ((String) r[1]).substring(((String) r[1]).indexOf("<item "), ((String) r[1]).lastIndexOf("</document>")));
	}

	/* a document holding one item with the writer's richtext */
	static String dxlRich(String item, String richtext) {
		return "<?xml version='1.0' encoding='utf-8'?>\n<document xmlns='http://www.lotus.com/dxl' version='12.0'>"
				+ "<item name='" + item + "'>" + richtext + "</item></document>";
	}

	static final String GIF = "R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==";

	/* lists of Notes types, a tabbed table with its settings, a scaled picture */
	static final String FIDELITY = "<pardef id='1' list='alphaupper' leftmargin='1.25in'/><par def='1'>one</par><par def='1'>two</par>"
			+ "<pardef id='2' list='check'/><par def='2'>done</par><pardef id='3'/><par def='3'>plain</par>"
			+ "<table widthtype='fitmargins' cellborderstyle='ridge' colorstyle='solid' bgcolor='#ffffcc' rowdisplay='tabs' leftmargin='0.5in'>"
			+ "<tablecolumn width='2in'/><tablecolumn width='1in'/><tablerow tablabel='Tab one'><tablecell borderwidth='0px' bgcolor='#eeeeee'><par def='3'>x</par></tablecell>"
			+ "<tablecell><par def='3'>y</par></tablecell></tablerow></table>"
			+ "<par def='3'>pic <picture width='100px' height='50px' scaledwidth='2.0833in' scaledheight='1.0417in'><gif>" + GIF + "</gif></picture></par>";

	/* the reference test body of 2026-09-28, as its DXL would read, plus a tab */
	static final String SCREENSHOT = "<pardef id='1'/>\n"
			+ "<par def='1'><run><font size='12pt' name='Times New Roman'/>asd sa </run><run><font size='12pt' style='bold' name='Times New Roman'/>dasd</run>"
			+ "<run><font size='12pt' name='Times New Roman'/> sad sa </run><run><font size='12pt' style='underline' name='Times New Roman'/>XXX 111</run></par>\n"
			+ "<par def='1'><urllink showborder='false' href='https://example.com'><run><font size='12pt' style='underline' color='blue' name='Times New Roman'/>XXXX</run></urllink></par>\n"
			+ "<par def='1'><run><font size='12pt' name='Times New Roman'/>XXXXXXXXXXXXXXX</run></par>\n"
			+ "<par def='1'/>\n"
			+ "<pardef id='2' leftmargin='1.5in'/>\n"
			+ "<par def='2'><attachmentref name='brochure.png' displayname='brochure.png'>"
			+ "<picture height='36px' width='96px'><gif>" + GIF + "</gif><caption>brochure.png</caption></picture></attachmentref></par>\n"
			+ "<par def='1'><run><font size='12pt' name='Times New Roman'/>See notes link: </run>"
			+ "<doclink server='CN=server/O=Example' database='86258E20:0059AA43' view='11111111111111111111111111111111' document='22222222222222222222222222222222' description='Product'/></par>\n"
			+ "<par def='1'><picture height='145px' width='145px'><jpeg>/9j/4AAQSkZJRgABAQ==</jpeg></picture></par>\n"
			+ "<par def='1'>a\ttab</par>\n"
			+ "<table widthtype='fixedleft' refwidth='3.9in'><tablecolumn width='1.6in'/><tablecolumn width='2.3in'/>\n"
			+ "<tablerow><tablecell><pardef id='3' leftmargin='0.0417in'/><par def='3'><run><font size='12pt' style='bold' name='Times New Roman'/>First Name</run></par></tablecell>"
			+ "<tablecell><par def='3'><run><font style='bold'/>LastName</run></par></tablecell></tablerow>\n"
			+ "<tablerow><tablecell><par def='3'><run><font size='12pt' name='Times New Roman'/>Jane</run></par></tablecell>"
			+ "<tablecell><par def='3'>Doe</par></tablecell></tablerow></table>\n"
			+ "<par def='1'/>";

	@SuppressWarnings("unchecked")
	static java.util.Set<String> reasons(Object[] r) {
		return (java.util.Set<String>) r[1];
	}

	/* a rule with its settings, a URL link with a border and target, a
	 * shadowed run, raw data, an unprintable character, a cell with border
	 * style and colour, a background and a vertical alignment */
	static final String EXTRAS = "<pardef id='1'/><par def='1'>rule below</par>"
			+ "<par def='1'><horizrule color='red' width='50%' height='0.05in' use3dshading='false'/></par>"
			+ "<par def='1'><urllink showborder='true' targetframe='_blank' href='https://example.com'><run><font color='blue'/>site</run></urllink>"
			+ " and <run><font style='bold shadow'/>shadowed</run></par>"
			+ "<par def='1'>raw<compositedata type='98' prevtype='65418'>Yg4BAIQAAAAAAAAAAAA=</compositedata>data<nonxmlchar value='0x1'/>x</par>"
			+ "<table widthtype='fitmargins'><tablecolumn width='2in'/><tablecolumn width='1in'/>"
			+ "<tablerow><tablecell rowheader='true' altbgcolor='#eeeeee' borderwidth='2px' valign='center'>"
			+ "<cellbackground repeat='tile'><imageref name='bg.gif'/></cellbackground><pardef id='2' align='center'/><par def='2'>x</par></tablecell>"
			+ "<tablecell><par def='2'>y</par></tablecell></tablerow></table>";

	/* every named colour DXL writes (the CSS2 names), a colour of none, a system colour */
	static final String COLORS = "<pardef id='1'/><par def='1'>"
			+ "<run><font color='black'/>black</run> <run><font color='white'/>white</run> <run><font color='red'/>red</run> "
			+ "<run><font color='lime'/>lime</run> <run><font color='green'/>green</run> <run><font color='blue'/>blue</run> "
			+ "<run><font color='navy'/>navy</run> <run><font color='fuchsia'/>fuchsia</run> <run><font color='purple'/>purple</run> "
			+ "<run><font color='yellow'/>yellow</run> <run><font color='olive'/>olive</run> <run><font color='aqua'/>aqua</run> "
			+ "<run><font color='teal'/>teal</run> <run><font color='gray'/>gray</run> <run><font color='silver'/>silver</run> "
			+ "<run><font color='maroon'/>maroon</run> <run><font color='#123456'/>hex</run> <run><font color='none'/>none</run> "
			+ "<run><font color='system'/>system</run></par>";

	/* the fixtures the end-to-end cycle (CycleCheck + cycle-check.js) pushes
	 * through the real editor: name, item DXL, and the DXL strings an
	 * untouched or edited save must still write back */
	static final String[][] CYCLE = {
			{ "screenshot", SCREENSHOT, "description='Product'", "server='CN=server/O=Example'",
					"<attachmentref displayname='brochure.png' name='brochure.png'>",
					"<caption>brochure.png</caption>", "<jpeg>/9j/4AAQSkZJRgABAQ==</jpeg>", "leftmargin='1.5in'", "a\ttab",
					"<font name='Times New Roman' size='12pt' style='bold'/>dasd", "<urllink href='https://example.com' showborder='false'>" },
			{ "islands", ISLANDS,
					"<actionhotspot hotspotstyle='none'><code event='click'><formula>@Command([FileSave])</formula></code><run><font style='bold'/>Save it</run></actionhotspot>",
					" hide='notes'/><par def=", "><section><sectiontitle color='blue' pardef='",
					"<computedtext><code event='value'><formula>@Now</formula></code></computedtext>",
					"<run html='true'>&lt;b&gt;pass&lt;/b&gt;</run>", "<popup hotspotstyle='none' show='onclick'><popuptext>tip</popuptext><run>hover</run></popup>",
					"<picture height='10px' width='10px'><cgm>AAAA</cgm></picture>", "<anchor name='here'/>",
					"<tablecell><section><sectiontitle><text>In a cell</text></sectiontitle>" },
			{ "extras", EXTRAS, "<horizrule color='red' height='0.05in' use3dshading='false' width='50%'/>",
					"<urllink href='https://example.com' showborder='true' targetframe='_blank'>", "<font color='#0000ff'/>site</run>",
					"<run><font style='bold shadow'/>shadowed</run>",
					"raw<compositedata prevtype='65418' type='98'>Yg4BAIQAAAAAAAAAAAA=</compositedata>data<nonxmlchar value='0x1'/>x",
					"<tablecell altbgcolor='#eeeeee' borderwidth='2px' rowheader='true' valign='center'><cellbackground repeat='tile'><imageref name='bg.gif'/></cellbackground>" },
			{ "colors", COLORS, "<font color='#00ff00'/>lime", "<font color='#008000'/>green", "<font color='#800080'/>purple",
					"<font color='#ff00ff'/>fuchsia", "<font color='#000080'/>navy", "<font color='#808000'/>olive", "<font color='#008080'/>teal",
					"<font color='#00ffff'/>aqua", "<font color='#800000'/>maroon", "<font color='#c0c0c0'/>silver", "<font color='#123456'/>hex" },
			{ "fidelity", FIDELITY, "list='alphaupper'", "list='check'",
					"<table bgcolor='#ffffcc' cellborderstyle='ridge' colorstyle='solid' leftmargin='0.5in' rowdisplay='tabs' widthtype='fitmargins'><tablecolumn width='2in'/><tablecolumn width='1in'/><tablerow tablabel='Tab one'>",
					"<picture height='50px' scaledheight='1.0417in' scaledwidth='2.0833in' width='100px'>" } };

	/* a CYCLE row by its fixture name */
	static String[] cycleRow(String name) {
		for (String[] row : CYCLE) {
			if (row[0].equals(name)) return row;
		}
		throw new IllegalArgumentException(name);
	}

	static Object[] render(Method m, String dxl, int budget) throws Exception {
		return (Object[]) m.invoke(null, dxl, "Body", FILES, "86258E200059AA43", PAGE, budget,
				new java.util.TreeSet<String>());
	}

	static void dxlChecks(Class<?> kit) throws Exception {
		Method m = kit.getDeclaredMethod("renderDxl", String.class, String.class, String.class, String.class,
				String.class, int.class, java.util.Set.class);
		m.setAccessible(true);

		System.out.println("DXL - the body from the 2026-09-28 screenshot: formatting, link, attachment, doclink, picture, table");
		Object[] r = render(m, dxl("Body", SCREENSHOT), 2 * 1024 * 1024);
		String html = (String) r[0];
		System.out.println("        -> " + html);
		has("screenshot", html,
				"<div data-pd=\"1\"><span style=\"font-family: 'Times New Roman'; font-size: 12pt\">asd sa </span>",
				"<b>dasd</b>", "<u>XXX 111</u>",
				"<a href=\"https://example.com\"><span style=\"font-family: 'Times New Roman'; font-size: 12pt; color: #0000ff\"><u>XXXX</u></span></a>",
				"<div data-pd=\"1\"><br /></div>",
				"<div style=\"margin-left: 48px\" data-pd=\"2\"><a href=\"" + FILES + "brochure.png\"><img src=\"data:image/gif;base64," + GIF
						+ "\" alt=\"brochure.png\" width=\"96\" height=\"36\" data-pic=\"1\" /><span data-cap=\"1\"><br />brochure.png</span></a></div>",
				"See notes link: </span><a href=\"" + PAGE.replace("&", "&amp;") + "22222222222222222222222222222222\">&#128196;</a>",
				"<img src=\"data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ==\" width=\"145\" height=\"145\" data-pic=\"2\" />",
				"<table style=\"border-collapse: collapse; width: 374px\" data-tbl=\"1\"><tr><td style=\"border-style: solid; border-color: #000000; border-width: 1px; padding: 2px 4px; vertical-align: top; width: 154px\">",
				"<b>First Name</b>", "<div data-pd=\"3\">Doe</div></td></tr></table>",
				"a&#8195;&#8195;tab");
		lacks("screenshot", html, "cid:", "picture not shown", "margin-left: 4px");
		check("attachment + doclink: editable now (both go back as they were)", reasons(r).isEmpty(), reasons(r).toString());

		System.out.println("DXL - lists, alignment, hidden paragraphs, doclinks elsewhere, plain body stays editable");
		String lists = "<pardef id='1'/><pardef id='2' list='bullet'/><pardef id='3' list='number'/><pardef id='4' align='center'/>"
				+ "<pardef id='5' hide='notes web'/>\n"
				+ "<par def='2'>one</par><par def='2'>two</par><par def='3'>first</par><par def='1'>after</par>"
				+ "<par def='4'>middle</par><par def='5'>secret</par>";
		r = render(m, dxl("Body", lists), 1000);
		html = (String) r[0];
		System.out.println("        -> " + html);
		has("lists", html, "<ul><li data-pd=\"2\">one</li><li data-pd=\"2\">two</li></ul><ol><li data-pd=\"3\">first</li></ol><div data-pd=\"1\">after</div>",
				"<div style=\"text-align: center\" data-pd=\"4\">middle</div>");
		has("a hidden paragraph is an island (the page CSS hides it when reading)", html,
				"<div data-keep=\"1\" data-kind=\"par\" contenteditable=\"false\">secret</div>");
		check("a hidden paragraph no longer locks", reasons(r).isEmpty(), reasons(r).toString());
		r = render(m, dxl("Body", "<pardef id='1'/><par def='1'><run><font style='bold italic'/>plain</run> body</par>"), 1000);
		check("formatted text only: editable", reasons(r).isEmpty() && ((String) r[0]).contains("<b><i>plain</i></b> body"), (String) r[0]);
		r = render(m, dxl("Body", "<par><doclink server='CN=other/O=X' database='11112222:33334444' document='ABCD'/></par>"), 1000);
		has("foreign doclink -> notes URL", (String) r[0], "<a href=\"notes://other/1111222233334444/0/ABCD\">");

		System.out.println("DXL - limits and hostile content");
		r = render(m, dxl("Body", "<par><picture width='10px' height='10px'><png>" + "QUJD".replace("QUJD", "QUJDRA==") + "</png></picture></par>"), 2);
		has("picture over the budget: note", (String) r[0], "[picture not shown here");
		has("picture over the budget: kept by its place", (String) r[0], "<span data-pic=\"1\" contenteditable=\"false\">");
		check("picture over the budget: editable around it", reasons(r).isEmpty(), reasons(r).toString());
		r = render(m, dxl("Body", "<par><picture><cgm>AAAA</cgm></picture>text</par>"), 1000);
		check("unsupported picture format: note, kept, editable", ((String) r[0]).contains("<span data-pic=\"1\" contenteditable=\"false\">[picture not shown here")
				&& reasons(r).isEmpty(), r[0] + " " + reasons(r));
		r = render(m, dxl("Body", "<par><urllink href='javascript:alert(1)'><run>click</run></urllink>"
				+ "<run>&lt;script&gt;alert(2)&lt;/script&gt;</run></par>"), 1000);
		html = (String) r[0];
		lacks("hostile", html, "javascript", "<script");
		has("hostile text stays text", html, "click", "&lt;script&gt;alert(2)&lt;/script&gt;");
		has("refused link: an island showing its text", html, "<span data-keep=\"1\" data-kind=\"urllink\" contenteditable=\"false\">click</span>");
		check("refused link: editable around it", reasons(r).isEmpty(), reasons(r).toString());
		java.util.TreeSet<String> unknown = new java.util.TreeSet<>();
		r = (Object[]) m.invoke(null, dxl("Body", "<par><futurething><run>inside</run></futurething></par>"),
				"Body", null, null, null, 1000, unknown);
		check("unknown element: an island showing its content, recorded",
				((String) r[0]).contains("<span data-keep=\"1\" data-kind=\"futurething\" contenteditable=\"false\">inside</span>")
				&& unknown.contains("futurething") && reasons(r).isEmpty(), r[0] + " " + unknown);
		r = render(m, dxl("OtherItem", "<par>x</par>"), 1000);
		check("item not in the DXL: empty", "".equals(r[0]), (String) r[0]);
	}
}
