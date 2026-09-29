import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/*
 * The end-to-end cycle, offline: Notes rich text -> the page -> the REAL
 * editor (headless Edge) -> what it posts -> Notes rich text again.
 *
 *   java CycleCheck render <dir>   writes <dir>/<fixture>.html: each fixture
 *                                  of RichTextCheck.CYCLE rendered as the
 *                                  page renders it
 *   node cycle-check.js <dir>      loads each into the editor and writes
 *                                  <fixture>.posted.html (submitted as it
 *                                  came) and <fixture>.edited.html (a word
 *                                  typed, a paragraph added, a word bolded)
 *   java CycleCheck verify <dir>   the posted body written back renders
 *                                  exactly as the original did; the edited
 *                                  body holds the edits, still holds every
 *                                  original construct, and is valid DXL
 *
 * RichTextCheck proves HTML -> DXL on hand-written HTML; this proves it on
 * what a browser really serialises out of a rendered body (attribute
 * order, entities, whitespace, the DOM's own normalisation), which is the
 * HTML the server sees. Run by Check-RichTextKit.ps1.
 */
public class CycleCheck {
	static int fails = 0;

	static void check(String name, boolean ok, String detail) {
		System.out.println((ok ? "  PASS  " : "  FAIL  ") + name + (ok ? "" : "\n        " + detail));
		if (!ok) fails++;
	}

	public static void main(String[] a) throws Exception {
		if (a.length != 2 || !("render".equals(a[0]) || "verify".equals(a[0]))) {
			System.out.println("usage: CycleCheck render|verify <dir>");
			System.exit(2);
		}
		File dir = new File(a[1]);
		dir.mkdirs();
		Class<?> kit = Class.forName("net.prominic.RichText");
		Method render = kit.getDeclaredMethod("renderDxl", String.class, String.class, String.class, String.class,
				String.class, int.class, java.util.Set.class);
		render.setAccessible(true);
		Method toDxl = kit.getDeclaredMethod("toDxl", String.class, String.class, String.class, String.class,
				String.class, String.class);
		toDxl.setAccessible(true);

		for (String[] fixture : RichTextCheck.CYCLE) {
			String name = fixture[0];
			String doc = RichTextCheck.dxl("Body", fixture[1]);
			String html = (String) RichTextCheck.render(render, doc, 2 * 1024 * 1024)[0];
			File page = new File(dir, name + ".html");
			if ("render".equals(a[0])) {
				Files.write(page.toPath(), html.getBytes(StandardCharsets.UTF_8));
				System.out.println("  wrote " + page);
				continue;
			}
			System.out.println("CYCLE - " + name + ": rendered -> the real editor -> posted -> Notes rich text");
			File posted = new File(dir, name + ".posted.html");
			File edited = new File(dir, name + ".edited.html");
			if (!posted.exists() || !edited.exists()) {
				check(name + ": the editor produced both files", false, "missing " + posted + " or " + edited);
				continue;
			}
			// untouched: what the browser posts must write back to rich text
			// that renders exactly as the original did
			String back = (String) toDxl.invoke(null, read(posted), doc, "Body", RichTextCheck.FILES,
					"86258E200059AA43", RichTextCheck.PAGE);
			String again = (String) RichTextCheck.render(render, RichTextCheck.dxlRich("Body", back), 2 * 1024 * 1024)[0];
			check(name + ": untouched in the editor -> the same rich text (renders identically)", again.equals(html),
					"posted: " + read(posted) + "\n        back: " + back + "\n        again: " + again + "\n        first: " + html);
			for (int i = 2; i < fixture.length; i++) {
				check(name + ": untouched keeps [" + fixture[i] + "]", back.contains(fixture[i]), back);
			}
			RichTextCheck.validate(name + " untouched", back);

			// edited: the edits are there, every original construct still is
			back = (String) toDxl.invoke(null, read(edited), doc, "Body", RichTextCheck.FILES,
					"86258E200059AA43", RichTextCheck.PAGE);
			// the word takes the font of the text it was typed into (as in Notes)
			// plus bold; the new paragraph is its own par, bold carried on as
			// browsers and Notes both do
			check(name + ": the typed word is in the rich text, bold, in the font around it",
					back.matches("(?s).*<run><font [^>]*style='[^']*bold[^']*'/>EDITED</run>.*"), back);
			check(name + ": the new paragraph is a paragraph of its own",
					back.matches("(?s).*<par def='[0-9]+'>(<run><font [^>]*/>)?NEWPAR(</run>)?</par>.*"), back);
			for (int i = 2; i < fixture.length; i++) {
				check(name + ": edited keeps [" + fixture[i] + "]", back.contains(fixture[i]), back);
			}
			RichTextCheck.validate(name + " edited", back);
		}
		if ("verify".equals(a[0])) {
			System.out.println(fails == 0 ? "CYCLE ALL PASS" : fails + " FAILED");
			System.exit(fails == 0 ? 0 : 1);
		}
	}

	static String read(File f) throws Exception {
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}
}
