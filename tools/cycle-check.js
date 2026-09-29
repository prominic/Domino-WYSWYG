// The browser leg of the end-to-end cycle (see CycleCheck.java): each
// <fixture>.html in the folder - a body as the page renders it - is put
// into the real WYSIWYG editor in headless Edge and submitted twice:
//
//   <fixture>.posted.html  as it came (only marked changed, so the kit posts it)
//   <fixture>.edited.html  " EDITED" typed at the end of the first line
//                          and made bold, Enter, "NEWPAR" typed
//
// What lands in the hidden field is what the server would receive.
//
//   node cycle-check.js <dir> [app-script.js]
//
// Needs Edge and Node 22+. Only the Edge processes it started are stopped.
"use strict";
const { spawn, execSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const dir = process.argv[2];
if (!dir || !fs.existsSync(dir)) {
  console.log("usage: node cycle-check.js <dir> [app-script.js]");
  process.exit(2);
}
/* Edge or Chrome, wherever Windows put them, or RICH_KIT_BROWSER */
const PF = [process.env["ProgramFiles"], process.env["ProgramFiles(x86)"], "C:/Program Files", "C:/Program Files (x86)"].filter(Boolean);
const EDGE = [process.env.RICH_KIT_BROWSER]
  .concat(PF.map((d) => path.join(d, "Microsoft", "Edge", "Application", "msedge.exe")))
  .concat(PF.map((d) => path.join(d, "Google", "Chrome", "Application", "chrome.exe")))
  .filter(Boolean).find((p) => fs.existsSync(p));
if (!EDGE) {
  console.log("cycle check: SKIPPED - no Edge or Chrome found (set RICH_KIT_BROWSER to a browser executable)");
  process.exit(0);
}
let script = process.argv[3];
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
const fixtures = fs.readdirSync(dir).filter((f) => /^[a-z0-9-]+\.html$/.test(f));
if (!fixtures.length) {
  console.log("cycle check: no <fixture>.html in " + dir);
  process.exit(2);
}
const work = fs.mkdtempSync(path.join(os.tmpdir(), "rtw-cycle-"));
const url = (p) => "file:///" + p.replace(/\\/g, "/");

// one page per fixture and mode; the editor markup is page.page.html's
function pageFor(fixture, mode) {
  const body = fs.readFileSync(path.join(dir, fixture), "utf8");
  const page = path.join(work, fixture.replace(/\.html$/, "") + "." + mode + ".html");
  fs.writeFileSync(page, `<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>
<div class="rich-toolbar" data-editor="ed-body"></div>
<form id="page-form" method="post">
<div id="ed-body" class="rich-editor rich" contenteditable="true" spellcheck="true" data-target="h-body">${body}</div>
<input type="hidden" id="h-body" name="body" disabled>
</form>
<pre id="out"></pre>
<script src="${url(script)}"></script>
<script>
window.addEventListener("load", function () {
  var ed = document.getElementById("ed-body"), log = {};
  window.alert = function (m) { log.alerts = (log.alerts || "") + m + " | "; };
  function caretAtEndOfFirstLine() {
    var first = ed.querySelector("div, li, td") || ed;
    var w = document.createTreeWalker(first, NodeFilter.SHOW_TEXT), n, last = null;
    while ((n = w.nextNode())) { if (n.nodeValue.trim()) { last = n; } }
    var r = document.createRange();
    if (last) { r.setStart(last, last.nodeValue.length); } else { r.selectNodeContents(first); r.collapse(false); }
    var s = getSelection(); s.removeAllRanges(); s.addRange(r); ed.focus();
  }
  function selectWord(word) {
    var w = document.createTreeWalker(ed, NodeFilter.SHOW_TEXT), n;
    while ((n = w.nextNode())) {
      var i = n.nodeValue.indexOf(word);
      if (i >= 0) { var r = document.createRange(); r.setStart(n, i); r.setEnd(n, i + word.length);
        var s = getSelection(); s.removeAllRanges(); s.addRange(r); ed.focus(); return; }
    }
  }
  var steps = ${mode === "posted" ? `[
    function () { ed.dispatchEvent(new Event("input")); }
  ]` : `[
    function () { caretAtEndOfFirstLine(); },
    function () { document.execCommand("insertText", false, " EDITED"); },
    function () { selectWord("EDITED"); },
    function () { document.execCommand("styleWithCSS", false, false); document.execCommand("bold"); },
    function () { var s = getSelection(); s.collapseToEnd(); },
    function () { document.execCommand("insertParagraph"); },
    function () { document.execCommand("insertText", false, "NEWPAR"); },
    function () { ed.dispatchEvent(new Event("input")); }
  ]`};
  steps.push(function () {
    document.getElementById("page-form").dispatchEvent(new Event("submit"));
    var h = document.getElementById("h-body");
    log.enabled = !h.disabled; log.posted = h.value;
    document.getElementById("out").textContent = JSON.stringify(log);
  });
  var i = 0;
  (function next() { if (i < steps.length) { try { steps[i++](); } catch (e) { log.error = (log.error || "") + "step " + i + ": " + e; } setTimeout(next, 80); } })();
});
</script></body></html>`);
  return page;
}

const port = 9300 + Math.floor(Math.random() * 500);
const first = pageFor(fixtures[0], "posted");
const child = spawn(EDGE, ["--headless=new", "--disable-gpu", "--no-first-run", "--no-default-browser-check",
  "--allow-file-access-from-files", "--remote-debugging-port=" + port, "--user-data-dir=" + path.join(work, "profile"), url(first)],
  { stdio: "ignore" });

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
setTimeout(() => { console.log("cycle check: TIMEOUT"); finish(2); }, 120000);

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
  const send = (method, params) => new Promise((r) => { id++; pending.set(id, r); ws.send(JSON.stringify({ id, method, params })); });
  const evaluate = (expression) => send("Runtime.evaluate", { expression, returnByValue: true });
  const result = async () => {
    for (let i = 0; i < 150; i++) {
      const res = await evaluate("document.getElementById('out') && document.getElementById('out').textContent");
      const text = res.result && res.result.result ? res.result.result.value : "";
      if (text) return JSON.parse(text);
      await new Promise((r) => setTimeout(r, 200));
    }
    throw new Error("the steps did not finish");
  };
  let fails = 0;
  let firstPage = true;
  for (const fixture of fixtures) {
    for (const mode of ["posted", "edited"]) {
      const page = firstPage ? first : pageFor(fixture, mode);
      firstPage = false;
      if (page !== first) {
        await send("Page.navigate", { url: url(page) });
      }
      const log = await result();
      const name = fixture.replace(/\.html$/, "") + " " + mode;
      if (log.error || log.alerts || !log.enabled) {
        console.log("  FAIL  " + name + ": " + (log.error || log.alerts || "nothing posted"));
        fails++;
        continue;
      }
      fs.writeFileSync(path.join(dir, fixture.replace(/\.html$/, "") + "." + mode + ".html"), log.posted, "utf8");
      console.log("  PASS  " + name + " (" + log.posted.length + " chars)");
    }
  }
  ws.close();
  return fails;
}

run().then((fails) => {
  console.log(fails === 0 ? "EDITOR LEG ALL PASS" : fails + " FAILED");
  finish(fails === 0 ? 0 : 1);
}).catch((e) => { console.log("cycle check: ERROR " + e.message); finish(3); });
