package net.prominic;

/*
 * Domino WYSIWYG - a Notes rich-text field on the web, edited from both ends
 * https://github.com/prominic/Domino-WYSWYG (README.md)
 * Copyright 2026 Prominic.NET. Licensed under the Apache License, Version 2.0;
 * see the LICENSE file that came with the kit.
 */

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Allowlist HTML sanitizer - half of the rich-text kit (see RichText;
 * guide: RichTextWeb repo, RICHTEXT.md), self-contained: copy it with
 * RichText. The cleaned HTML is stored as MIME and later rendered
 * UNESCAPED via mustache {{&...}}, so this is the only XSS gate on it.
 * A blacklist (strip script/onX) is bypassable; instead we tokenize and
 * re-emit ONLY allowlisted tags and attributes - anything unrecognised is
 * dropped, any stray angle bracket is escaped, so no executable markup
 * can survive by construction.
 *
 * It also has to keep what a Notes rich-text body carries once Domino has
 * converted it to HTML (2026-09-28): embedded pictures, font colour/size/
 * face, alignment, table geometry and a closed list of CSS properties.
 * Every kept value is matched against a pattern that leaves no room for a
 * scheme, url(), expression() or a quote break-out; picture sources are
 * data:image or cid: only - never a remote URL.
 */
public class HtmlSanitizer {

	private static final Set<String> ALLOWED_TAGS = new HashSet<>(Arrays.asList(
			"b", "strong", "i", "em", "u", "s", "strike", "p", "br",
			"h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "a",
			"table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "col", "colgroup",
			"hr", "span", "div", "blockquote", "img", "font", "sup", "sub", "pre", "code",
			"small", "big", "center"));

	private static final Set<String> VOID_TAGS = new HashSet<>(Arrays.asList("br", "hr", "img", "col"));

	/* dropped TOGETHER WITH their content - what is inside them is never
	 * page text (a pasted Word document brings a style sheet, a converted
	 * page a title) */
	private static final Set<String> DROP_CONTENT = new HashSet<>(Arrays.asList(
			"script", "style", "head", "title", "xml", "template", "noscript",
			"iframe", "object", "textarea", "select"));

	/* the tags that may carry align= and the table-geometry attributes */
	private static final Set<String> ALIGN_TAGS = new HashSet<>(Arrays.asList(
			"p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "table", "tr", "td", "th", "caption", "img"));
	private static final Set<String> CELL_TAGS = new HashSet<>(Arrays.asList("td", "th", "tr", "table", "col"));

	/* the CSS properties a style attribute may keep */
	private static final Set<String> STYLE_PROPS = new HashSet<>(Arrays.asList(
			"color", "background-color", "font-family", "font-size", "font-weight", "font-style",
			"text-decoration", "text-align", "text-indent", "vertical-align", "line-height",
			"margin-left", "padding", "padding-left", "padding-right", "padding-top", "padding-bottom",
			"border", "border-top", "border-right", "border-bottom", "border-left",
			"border-color", "border-style", "border-width", "border-collapse", "width", "height"));

	private static final Pattern DATA_IMAGE = Pattern.compile(
			"data:image/(png|gif|jpeg|jpg|bmp|webp);base64,([A-Za-z0-9+/=]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CID = Pattern.compile("cid:[A-Za-z0-9._@$%+=-]{1,200}", Pattern.CASE_INSENSITIVE);
	private static final Pattern COLOR = Pattern.compile("#?[A-Za-z0-9]{1,20}");
	private static final Pattern FONT_SIZE = Pattern.compile("[+-]?[1-7]");
	private static final Pattern FACE = Pattern.compile("[A-Za-z0-9 ,'-]{1,100}");
	private static final Pattern LENGTH = Pattern.compile("[0-9]{1,4}(%|px)?");
	private static final Pattern ALIGN = Pattern.compile("(?i)left|right|center|justify");
	private static final Pattern VALIGN = Pattern.compile("(?i)top|middle|bottom|baseline");
	private static final Pattern MARKER = Pattern.compile("[0-9]{1,6}");
	private static final Pattern KIND = Pattern.compile("[a-z]{1,30}");
	private static final Pattern LIST_TYPE = Pattern.compile("[a-z]{1,12}");
	private static final Pattern NOT_EDITABLE = Pattern.compile("false");
	/* rgb()/rgba() is the only parenthesis a CSS value may hold */
	private static final Pattern CSS_RGB = Pattern.compile("rgba?\\(\\s*[0-9.,%\\s]{1,40}\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern CSS_VALUE = Pattern.compile("[#A-Za-z0-9\\s.,%'\"-]{1,200}");
	/* no negative lengths: content must not be dragged over the page chrome */
	private static final Pattern CSS_NEGATIVE = Pattern.compile("(^|[\\s,])-");

	/* start tags that close an open <p> first, as the HTML parser does */
	private static final Set<String> CLOSES_P = new HashSet<>(Arrays.asList(
			"p", "div", "table", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6",
			"blockquote", "pre", "hr", "center"));

	/*
	 * The output is BALANCED: a close tag with no matching open one is
	 * dropped, and whatever is still open at the end is closed. The body
	 * sits inside the page's own markup, so it must never close one of the
	 * page's elements (a stray </div>) or leave one open (a <table> cut at
	 * the length ceiling would swallow every tab after it).
	 */
	public static String clean(String html) {
		if (html == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		java.util.ArrayDeque<String> open = new java.util.ArrayDeque<>();
		int i = 0;
		int n = html.length();
		while (i < n) {
			char c = html.charAt(i);
			if (c == '<') {
				if (html.startsWith("<!--", i)) {
					// a comment ends at -->, not at its first '>' (Word's
					// conditional comments carry several)
					int end = html.indexOf("-->", i + 4);
					i = end < 0 ? n : end + 3;
					continue;
				}
				int gt = html.indexOf('>', i);
				if (gt < 0) {
					out.append("&lt;");
					i++;
				} else {
					String raw = html.substring(i + 1, gt);
					String dropped = dropContentName(raw);
					if (dropped != null) {
						int close = indexOfIgnoreCase(html, "</" + dropped, gt + 1);
						int closeGt = close < 0 ? -1 : html.indexOf('>', close);
						i = closeGt < 0 ? n : closeGt + 1;
						continue;
					}
					String rebuilt = sanitizeTag(raw);
					if (rebuilt != null) {
						emit(rebuilt, open, out);
					}
					i = gt + 1;
				}
			} else if (c == '>') {
				out.append("&gt;");
				i++;
			} else {
				out.append(c);
				i++;
			}
		}
		while (!open.isEmpty()) {
			out.append("</").append(open.pop()).append('>');
		}
		return out.toString().trim();
	}

	/* one sanitized tag into the output, keeping the open-element stack */
	private static void emit(String tag, java.util.ArrayDeque<String> open, StringBuilder out) {
		boolean closing = tag.startsWith("</");
		int p = closing ? 2 : 1;
		int q = p;
		while (q < tag.length() && isNameChar(tag.charAt(q))) {
			q++;
		}
		String name = tag.substring(p, q);
		if (closing) {
			if (open.contains(name)) {
				String top;
				do {
					top = open.pop();
					out.append("</").append(top).append('>');
				} while (!top.equals(name));
			}
			return;
		}
		// the implicit closes the HTML parser would make anyway - emitting
		// them keeps the rendering the same and the stack honest
		if (CLOSES_P.contains(name)) {
			closeIfTop(open, out, "p");
		}
		if ("li".equals(name)) {
			closeIfTop(open, out, "li");
		}
		if ("td".equals(name) || "th".equals(name) || "tr".equals(name)) {
			closeIfTop(open, out, "td");
			closeIfTop(open, out, "th");
		}
		if ("tr".equals(name)) {
			closeIfTop(open, out, "tr");
		}
		out.append(tag);
		if (!VOID_TAGS.contains(name)) {
			open.push(name);
		}
	}

	private static void closeIfTop(java.util.ArrayDeque<String> open, StringBuilder out, String name) {
		if (name.equals(open.peek())) {
			out.append("</").append(open.pop()).append('>');
		}
	}

	/* the name of an OPENING tag whose content goes with it, else null */
	private static String dropContentName(String raw) {
		String tag = raw.trim();
		int p = 0;
		while (p < tag.length() && isNameChar(tag.charAt(p))) {
			p++;
		}
		String name = tag.substring(0, p).toLowerCase();
		return DROP_CONTENT.contains(name) && !tag.endsWith("/") ? name : null;
	}

	private static int indexOfIgnoreCase(String s, String needle, int from) {
		int last = s.length() - needle.length();
		for (int i = from; i <= last; i++) {
			if (s.regionMatches(true, i, needle, 0, needle.length())) {
				return i;
			}
		}
		return -1;
	}

	/* one tag body (between < and >) -> safe re-emission, or null to drop */
	private static String sanitizeTag(String raw) {
		String tag = raw.trim();
		if (tag.isEmpty() || tag.startsWith("!") || tag.startsWith("?")) {
			return null; // doctype, CDATA, PI - drop
		}
		boolean closing = tag.startsWith("/");
		if (closing) {
			tag = tag.substring(1).trim();
		}
		int p = 0;
		while (p < tag.length() && isNameChar(tag.charAt(p))) {
			p++;
		}
		String name = tag.substring(0, p).toLowerCase();
		if (!ALLOWED_TAGS.contains(name)) {
			return null;
		}
		if (closing) {
			return VOID_TAGS.contains(name) ? null : "</" + name + ">";
		}
		StringBuilder sb = new StringBuilder("<").append(name);
		if ("a".equals(name)) {
			String href = attr(tag, "href");
			if (href != null) {
				// decoded first: the value arrives escaped (&amp;), and
				// escapeAttr would otherwise turn it into &amp;amp;
				href = decodeAttr(href);
				if (isSafeUrl(href)) {
					sb.append(" href=\"").append(escapeAttr(href)).append("\"");
				}
			}
		} else if ("img".equals(name)) {
			// src first and always double-quoted: the rich-body code finds
			// pictures by the literal '<img src="' this emits
			String src = imageSrc(attr(tag, "src"));
			if (src == null) {
				return null; // a picture without a safe source is no picture
			}
			sb.append(" src=\"").append(src).append("\"");
			String alt = attr(tag, "alt");
			if (alt != null) {
				alt = decodeAttr(alt);
				sb.append(" alt=\"").append(escapeAttr(alt.length() > 200 ? alt.substring(0, 200) : alt)).append("\"");
			}
			appendPatternAttr(sb, tag, "width", LENGTH);
			appendPatternAttr(sb, tag, "height", LENGTH);
		} else if ("font".equals(name)) {
			appendPatternAttr(sb, tag, "color", COLOR);
			appendPatternAttr(sb, tag, "size", FONT_SIZE);
			appendPatternAttr(sb, tag, "face", FACE);
		} else if ("td".equals(name) || "th".equals(name)) {
			appendNumericAttr(sb, tag, "colspan");
			appendNumericAttr(sb, tag, "rowspan");
		} else if ("table".equals(name)) {
			appendNumericAttr(sb, tag, "border");
			appendNumericAttr(sb, tag, "cellpadding");
			appendNumericAttr(sb, tag, "cellspacing");
		}
		if (ALIGN_TAGS.contains(name)) {
			appendPatternAttr(sb, tag, "align", ALIGN);
		}
		if (CELL_TAGS.contains(name)) {
			appendPatternAttr(sb, tag, "valign", VALIGN);
			appendPatternAttr(sb, tag, "width", LENGTH);
			appendPatternAttr(sb, tag, "height", LENGTH);
			appendPatternAttr(sb, tag, "bgcolor", COLOR);
		}
		String style = attr(tag, "style");
		if (style != null) {
			String css = cleanStyle(style);
			if (!css.isEmpty()) {
				sb.append(" style=\"").append(escapeAttr(css)).append("\"");
			}
		}
		// RichText's round-trip markers (numbers only): a paragraph's pardef,
		// a picture's place in the item, a caption that belongs to a picture,
		// an island (a Notes-only element kept whole, by number and element
		// name) - and islands are not editable
		if ("div".equals(name) || "p".equals(name) || "li".equals(name)) {
			appendPatternAttr(sb, tag, "data-pd", MARKER);
		} else if ("img".equals(name)) {
			appendPatternAttr(sb, tag, "data-pic", MARKER);
		} else if ("span".equals(name)) {
			appendPatternAttr(sb, tag, "data-cap", MARKER);
			appendPatternAttr(sb, tag, "data-pic", MARKER);
			appendPatternAttr(sb, tag, "data-file", MARKER); // a file the editor attached
		}
		if ("table".equals(name)) {
			appendPatternAttr(sb, tag, "data-tbl", MARKER);
		} else if ("ul".equals(name) || "ol".equals(name)) {
			appendPatternAttr(sb, tag, "data-list", LIST_TYPE); // a Notes list type
		}
		if ("div".equals(name) || "span".equals(name)) {
			appendPatternAttr(sb, tag, "data-keep", MARKER);
			appendPatternAttr(sb, tag, "data-kind", KIND);
			appendPatternAttr(sb, tag, "contenteditable", NOT_EDITABLE);
		}
		if (VOID_TAGS.contains(name)) {
			sb.append(" /");
		}
		return sb.append(">").toString();
	}

	/*
	 * A picture source, normalised, or null when it is not one we render:
	 * an inline data:image (whitespace removed, type lower-cased, jpg ->
	 * jpeg) or a cid: reference to a part of the same MIME body (scheme
	 * lower-cased - the rich-body code matches the literal "cid:").
	 */
	private static String imageSrc(String src) {
		if (src == null) {
			return null;
		}
		String s = src.replaceAll("\\s", "");
		Matcher data = DATA_IMAGE.matcher(s);
		if (data.matches()) {
			String type = data.group(1).toLowerCase();
			return "data:image/" + ("jpg".equals(type) ? "jpeg" : type) + ";base64," + data.group(2);
		}
		return CID.matcher(s).matches() ? "cid:" + escapeAttr(s.substring(4)) : null;
	}

	/* style="" reduced to the allowlisted properties with safe values */
	private static String cleanStyle(String style) {
		String decoded = decodeEntities(style).replace("&quot;", "\"").replace("&apos;", "'");
		StringBuilder out = new StringBuilder();
		for (String decl : decoded.split(";")) {
			int colon = decl.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String prop = decl.substring(0, colon).trim().toLowerCase();
			String value = decl.substring(colon + 1).trim();
			if (STYLE_PROPS.contains(prop) && isSafeCssValue(value)) {
				if (out.length() > 0) {
					out.append("; ");
				}
				out.append(prop).append(": ").append(value);
			}
		}
		return out.toString();
	}

	private static boolean isSafeCssValue(String value) {
		String rest = CSS_RGB.matcher(value).replaceAll("0");
		return CSS_VALUE.matcher(rest).matches() && !CSS_NEGATIVE.matcher(rest).find();
	}

	private static void appendNumericAttr(StringBuilder sb, String tag, String name) {
		String v = attr(tag, name);
		if (v != null && v.matches("[0-9]{1,3}")) {
			sb.append(" ").append(name).append("=\"").append(v).append("\"");
		}
	}

	private static void appendPatternAttr(StringBuilder sb, String tag, String name, Pattern allowed) {
		String v = attr(tag, name);
		if (v != null && allowed.matcher(v.trim()).matches()) {
			sb.append(" ").append(name).append("=\"").append(escapeAttr(v.trim())).append("\"");
		}
	}

	private static boolean isNameChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
	}

	private static String attr(String tag, String name) {
		Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(tag);
		if (m.find()) {
			if (m.group(2) != null) {
				return m.group(2);
			}
			if (m.group(3) != null) {
				return m.group(3);
			}
			return m.group(4);
		}
		return null;
	}

	/*
	 * Allow http/https/mailto and scheme-less (relative, #anchor) URLs,
	 * plus the two a Notes body carries: notes: (doclinks, as the converter
	 * writes them) and file:/ftp: (links to shares) - none of them can run
	 * script. Numeric entities are decoded and control/space chars removed
	 * first, so tricks like "jav&#x09;ascript:" or "javascript&#58;..."
	 * cannot smuggle a scheme past the allowlist.
	 */
	/* package-visible: RichText checks DXL link targets with it too */
	static boolean isSafeUrl(String url) {
		String u = decodeEntities(url).replaceAll("[\\u0000-\\u0020]", "").toLowerCase();
		int colon = u.indexOf(':');
		if (colon < 0) {
			return true; // relative / anchor
		}
		int slash = u.indexOf('/');
		int hash = u.indexOf('#');
		if ((slash >= 0 && slash < colon) || (hash >= 0 && hash < colon)) {
			return true; // path or fragment before any colon -> relative
		}
		String scheme = u.substring(0, colon);
		return scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
				|| scheme.equals("notes") || scheme.equals("file") || scheme.equals("ftp");
	}

	private static String decodeEntities(String s) {
		Matcher m = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);?").matcher(s);
		StringBuffer sb = new StringBuffer();
		while (m.find()) {
			String g = m.group(1);
			String rep = "";
			try {
				int code = (g.charAt(0) == 'x' || g.charAt(0) == 'X')
						? Integer.parseInt(g.substring(1), 16)
						: Integer.parseInt(g);
				rep = String.valueOf((char) code);
			} catch (NumberFormatException e) {
				rep = "";
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(rep));
		}
		m.appendTail(sb);
		return sb.toString().replace("&colon;", ":");
	}

	/* an attribute value as the browser reads it: character references
	 * decoded (&amp; last, so "&amp;lt;" stays the text "&lt;") */
	private static String decodeAttr(String v) {
		return decodeEntities(v).replace("&quot;", "\"").replace("&apos;", "'")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
	}

	/* escape the characters that could break out of a double-quoted value */
	private static String escapeAttr(String v) {
		return v.replace("&", "&amp;").replace("\"", "&quot;")
				.replace("<", "&lt;").replace(">", "&gt;");
	}
}
