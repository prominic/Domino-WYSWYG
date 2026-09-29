// Drives the real WYSIWYG toolbar in headless Edge (Chromium) and checks
// the HTML each control produces - the HTML RichText.write turns into
// Notes rich text, so this is where "what the browser really writes" is
// pinned down (RichTextCheck then covers HTML -> DXL).
//
//   node editor-check.js [app-script.js]
//
// Needs Edge (Windows 11 has it) and Node 22+ (built-in WebSocket). The
// script defaults to the app script in ../UI (<app>-YYYYMMDD-N.js).
"use strict";
const { spawn, execSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

/* Edge or Chrome, wherever Windows put them, or RICH_KIT_BROWSER */
const PF = [process.env["ProgramFiles"], process.env["ProgramFiles(x86)"], "C:/Program Files", "C:/Program Files (x86)"].filter(Boolean);
const EDGE = [process.env.RICH_KIT_BROWSER]
  .concat(PF.map((d) => path.join(d, "Microsoft", "Edge", "Application", "msedge.exe")))
  .concat(PF.map((d) => path.join(d, "Google", "Chrome", "Application", "chrome.exe")))
  .filter(Boolean).find((p) => fs.existsSync(p));
if (!EDGE) {
  console.log("editor check: SKIPPED - no Edge or Chrome found (set RICH_KIT_BROWSER to a browser executable)");
  process.exit(0);
}

let script = process.argv[2];
if (!script) {
  const web = path.join(__dirname, "..", "web", "rich-text-kit.js");
  if (fs.existsSync(web)) {
    script = web; // a kit copy (kit/tools): the kit's own file
  } else {
    const ui = path.join(__dirname, "..", "UI");
    const stamp = (f) => f.replace(/^.*-(\d{8}-\d+)\.js$/, "$1");
    const js = fs.readdirSync(ui).filter((f) => /-\d{8}-\d+\.js$/.test(f)).sort((a, b) => stamp(a).localeCompare(stamp(b)));
    script = path.join(ui, js[js.length - 1]);
  }
}
const work = fs.mkdtempSync(path.join(os.tmpdir(), "rtw-editor-"));
const page = path.join(work, "editor.html");
const url = (p) => "file:///" + p.replace(/\\/g, "/");

// the page: one toolbar + editor as page.page.html renders them, then the
// steps - select a word, use a control - with a pause for selectionchange
fs.writeFileSync(page, `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
<div class="rich-toolbar" data-editor="ed"></div>
<div id="ed" class="rich-editor rich" contenteditable="true" data-target="h"><div data-pd="1">hello world again</div><div data-pd="1">second line</div><div>a <span data-keep="1" data-kind="actionhotspot" contenteditable="false"><b>hot</b></span> z</div><div>tab<span id="tabhere"></span>bed <a href="https://old.test/">oldlink</a> and <a href="https://gone.test/">gonelink</a> <img id="pic" src="data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==" width="100" height="50" data-pic="1"></div><ol><li>first</li></ol><table><tbody><tr><td style="width: 50px">c1</td><td>c2</td></tr></tbody></table></div>
<form id="page-form"><input type="hidden" id="h" name="body" disabled></form>
<pre id="out"></pre>
<script src="${url(script)}"></script>
<script>
window.addEventListener("load", function () {
  var ed = document.getElementById("ed"), bar = document.querySelector(".rich-toolbar"), log = {};
  // a dialog would block the headless page: record it instead
  window.alert = function (m) { log.alerts = (log.alerts || "") + m + " | "; };
  window.prompt = function () { return window.__answer === undefined ? null : window.__answer; };
  window.__log = log;
  function select(word) {
    var w = document.createTreeWalker(ed, NodeFilter.SHOW_TEXT), n;
    while ((n = w.nextNode())) {
      var i = n.nodeValue.indexOf(word);
      if (i >= 0) { var r = document.createRange(); r.setStart(n, i); r.setEnd(n, i + word.length);
        var s = getSelection(); s.removeAllRanges(); s.addRange(r); ed.focus(); return; }
    }
  }
  function pick(i, v) { var s = bar.querySelectorAll("select")[i]; s.value = v; s.dispatchEvent(new Event("change")); }
  function press(t) { bar.querySelector("button[title='" + t + "']").click(); }
  var steps = [
    function () { log.controls = bar.querySelectorAll("select").length + "/" + bar.querySelectorAll("button").length + "/" + bar.querySelectorAll("input[type=color]").length; select("hello"); },
    function () { pick(0, "Georgia"); }, function () { select("world"); }, function () { pick(1, "18"); },
    function () { select("again"); }, function () { pick(2, "#ffff00"); },
    function () { select("second"); }, function () { var c = bar.querySelector("input[type=color]"); c.value = "#ff0000"; c.dispatchEvent(new Event("change")); },
    function () { select("line"); }, function () { press("Center"); },
    function () { select("hello"); }, function () { press("Bold"); },
    function () { log.fontShown = bar.querySelectorAll("select")[0].value; log.boldLit = bar.querySelector("button[title='Bold']").classList.contains("active"); select("line"); },
    function () { press("Indent"); },
    function () { select("c1"); }, function () { pick(4, "rowBelow"); },
    function () { select("c2"); }, function () { pick(4, "colRight"); },
    function () { var t = ed.querySelector("table"); log.grown = t.rows.length + "x" + t.rows[0].cells.length; select("c1"); },
    function () { pick(4, "delCol"); },
    function () { var t = ed.querySelector("table"); log.shrunk = t.rows.length + "x" + t.rows[0].cells.length; select("again"); getSelection().collapseToEnd(); },
    function () { var dt = new DataTransfer(); dt.items.add(new File(["hello"], "a.txt", { type: "text/plain" }));
      var input = bar.querySelector("input[data-rich-attach]"); input.files = dt.files; input.dispatchEvent(new Event("change")); },
    function () { var f = document.querySelector("input[name=newfile1]"); log.file = f ? f.value : "";
      log.chip = !!ed.querySelector("span[data-file='1'][contenteditable='false']"); select("z"); getSelection().collapseToEnd(); },
    function () { var dt = new DataTransfer();
      dt.setData("text/html", '<span data-keep="1" data-kind="actionhotspot" contenteditable="false" onclick="x()">PASTED</span>');
      ed.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true })); },
    function () { var s = getSelection(); s.removeAllRanges(); var r = document.createRange(); r.setStartAfter(document.getElementById("tabhere")); r.collapse(true); s.addRange(r); ed.focus(); },
    function () { ed.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab", bubbles: true, cancelable: true })); },
    function () { select("first"); }, function () { pick(3, "romanupper"); },
    function () { log.listShown = bar.querySelectorAll("select")[3].value; select("oldlink"); },
    function () { window.__answer = "https://new.test/"; press("Link: a web address, a notes:// link or another page (edits the link at the cursor)"); },
    function () { select("gonelink"); }, function () { press("Remove link"); },
    function () { document.getElementById("pic").dispatchEvent(new MouseEvent("click", { bubbles: true })); },
    function () { window.__answer = "50"; press("Picture size (click a picture first)"); },
    function () { document.getElementById("page-form").dispatchEvent(new Event("submit"));
      var h = document.getElementById("h"); log.enabled = !h.disabled; log.posted = h.value;
      try { log.draft = sessionStorage.getItem("rich-draft:ed:" + location.pathname) === h.value; } catch (e) { log.draft = "no storage"; }
      document.getElementById("out").textContent = JSON.stringify(log); }
  ];
  var i = 0;
  (function next() { if (i < steps.length) { try { steps[i++](); } catch (e) { log.error = (log.error || "") + "step " + i + ": " + e; } setTimeout(next, 80); } })();
});
</script></body></html>`);

const port = 9300 + Math.floor(Math.random() * 500);
const child = spawn(EDGE, ["--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
  "--allow-file-access-from-files", "--remote-debugging-port=" + port, "--user-data-dir=" + path.join(work, "profile"), url(page)],
  { stdio: "ignore" });

/* Edge re-parents its helpers, so killing the launcher's tree is not
   enough: stop every msedge.exe that runs on this check's own profile
   folder (nothing else - the user's browser is never touched) */
function finish(code) {
  try { execSync("taskkill /PID " + child.pid + " /T /F", { stdio: "ignore" }); } catch (e) { /* gone */ }
  const tag = path.basename(work);
  try {
    execSync("powershell -NoProfile -Command \"Get-CimInstance Win32_Process -Filter \\\"Name = 'msedge.exe' OR Name = 'chrome.exe'\\\" | " +
      "Where-Object { $_.CommandLine -like '*" + tag + "*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }\"",
      { stdio: "ignore" });
  } catch (e) { /* none left */ }
  setTimeout(() => { try { fs.rmSync(work, { recursive: true, force: true }); } catch (e) { /* locked */ } process.exit(code); }, 500);
}
setTimeout(() => { console.log("editor check: TIMEOUT"); finish(2); }, 60000);

async function run() {
  let target;
  for (let i = 0; i < 100 && !target; i++) {
    try {
      const list = await (await fetch("http://127.0.0.1:" + port + "/json/list")).json();
      target = list.find((x) => x.type === "page" && x.url.startsWith("file:"));
    } catch (e) { /* not up yet */ }
    if (!target) await new Promise((r) => setTimeout(r, 200));
  }
  if (!target) throw new Error("Edge did not open the page");
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  ws.onmessage = (m) => { const msg = JSON.parse(m.data); if (pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); } };
  await new Promise((r) => (ws.onopen = r));
  const evaluate = (expression) => new Promise((r) => { id++; pending.set(id, r); ws.send(JSON.stringify({ id, method: "Runtime.evaluate", params: { expression, returnByValue: true } })); });
  for (let i = 0; i < 150; i++) {
    const res = await evaluate("document.getElementById('out').textContent");
    const text = res.result && res.result.result ? res.result.result.value : "";
    if (text) { ws.close(); return JSON.parse(text); }
    await new Promise((r) => setTimeout(r, 200));
  }
  const where = await evaluate("JSON.stringify(window.__log)");
  throw new Error("the steps did not finish - " + (where.result && where.result.result ? where.result.result.value : "?"));
}

run().then((log) => {
  let fails = 0;
  const check = (name, ok) => { console.log((ok ? "  PASS  " : "  FAIL  ") + name); if (!ok) fails++; };
  const has = (s) => (log.posted || "").indexOf(s) >= 0;
  console.log("editor check: " + script);
  check("toolbar built: 5 selects, 24 buttons, 1 colour (" + log.controls + ")", log.controls === "5/24/1");
  check("font -> <font face>", has("<font face=\"Georgia\">"));
  check("bold -> <b>", has("<b>hello</b>"));
  check("size -> font-size in points", has("<span style=\"font-size: 18pt;\">world</span>"));
  check("highlight -> background-color", has("<span style=\"background-color: rgb(255, 255, 0);\">again</span>"));
  check("colour -> <font color>", has("<font color=\"#ff0000\">second</font>"));
  check("center -> text-align on the paragraph, pardef marker kept", has("<div data-pd=\"1\" style=\"text-align: center;\">"));
  check("indent -> blockquote", has("<blockquote"));
  check("the toolbar shows the font and bold at the cursor", log.fontShown === "Georgia" && log.boldLit === true);
  check("the changed editor is posted", log.enabled === true);
  check("a draft of the posted HTML is kept for a failed save (" + log.draft + ")", log.draft === true);
  check("an island is posted as it came",
    has("<span data-keep=\"1\" data-kind=\"actionhotspot\" contenteditable=\"false\"><b>hot</b></span>"));
  check("table: a row below and a column right (" + log.grown + ")", log.grown === "2x3");
  check("table: a column deleted (" + log.shrunk + ")", log.shrunk === "2x2");
  check("attach: the file rides newfile1 as name|base64 (" + log.file + ")", log.file === "a.txt|aGVsbG8=");
  check("attach: the body shows it as span data-file", log.chip === true && has("data-file=\"1\""));
  check("Tab types a tab (two em spaces)", /tab\u2003\u2003(<span id="tabhere"><\/span>)?bed/.test(log.posted || ""));
  check("List menu: roman type on the list, shown in the menu (" + log.listShown + ")", has("<ol data-list=\"romanupper\">") && log.listShown === "romanupper");
  check("Link button edits the link at the cursor", has("<a href=\"https://new.test/\">oldlink</a>"));
  check("Remove link", has(" and gonelink ") && !has("gone.test"));
  check("Picture size: width 50, height follows, picture kept by reference", has("<img id=\"pic\" src=\"cid:p1\" width=\"50\" height=\"25\" data-pic=\"1\">"));
  check("paste: markers and handlers of pasted HTML are dropped",
    has("PASTED") && ((log.posted || "").match(/data-keep="1"/g) || []).length === 1 && !has("onclick"));
  if (log.error) check("no script error: " + log.error, false);
  if (log.alerts) check("no alert: " + log.alerts, false);
  if (process.env.EDITOR_DUMP) console.log("POSTED " + log.posted);
  console.log(fails === 0 ? "ALL PASS" : fails + " FAILED");
  finish(fails === 0 ? 0 : 1);
}).catch((e) => { console.log("editor check: ERROR " + e.message); finish(3); });
