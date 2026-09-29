// Offline check of the JS half of the rich-text kit (../RICHTEXT.md).
//
//   node richtext-js-check.js [script.js]
//
// Cuts the block between the "==== rich-text kit" and "==== end of
// rich-text kit" markers out of the script and runs it ON ITS OWN against
// a minimal fake DOM - which is also the proof that the block can be
// copied into another application as it is. Defaults to the app script
// in ../UI (<app>-YYYYMMDD-N.js - the newest when there are several).
"use strict";
const fs = require("fs");
const path = require("path");

let file = process.argv[2];
if (!file) {
  const web = path.join(__dirname, "..", "web", "rich-text-kit.js");
  if (fs.existsSync(web)) {
    file = web; // a kit copy (kit/tools): the kit's own file
  } else {
    const ui = path.join(__dirname, "..", "UI");
    const stamp = (f) => f.replace(/^.*-(\d{8}-\d+)\.js$/, "$1");
    const js = fs.readdirSync(ui).filter((f) => /-\d{8}-\d+\.js$/.test(f))
      .sort((a, b) => stamp(a).localeCompare(stamp(b)));
    file = path.join(ui, js[js.length - 1]);
  }
}
const source = fs.readFileSync(file, "utf8");
const start = source.indexOf("/* ==== rich-text kit");
const end = source.indexOf("/* ==== end of rich-text kit");
if (start < 0 || end < 0) {
  console.error(file + ": rich-text kit markers not found");
  process.exit(2);
}
const block = source.slice(start, end);

// just enough DOM for the block: attributes, listeners, getElementById
function element(id, html) {
  const listeners = {};
  return {
    id: id, innerHTML: html || "", value: "", disabled: true, attrs: {},
    getAttribute(n) { return n in this.attrs ? this.attrs[n] : null; },
    setAttribute(n, v) { this.attrs[n] = String(v); },
    addEventListener(t, f) { (listeners[t] = listeners[t] || []).push(f); },
    fire(t) { (listeners[t] || []).forEach((f) => f({})); }
  };
}
const byId = {};
const document = { getElementById: (id) => byId[id] || null };
const kit = new Function("document", block +
  "; return { richTrackChanges: richTrackChanges, richEdit: richEdit };")(document);

let fails = 0;
function check(name, ok) {
  console.log((ok ? "  PASS  " : "  FAIL  ") + name);
  if (!ok) fails++;
}

function page() {
  const editors = [];
  ["a", "b", "c"].forEach((k) => {
    const ed = element("ed-" + k, "<p>" + k + "</p>");
    ed.attrs["data-target"] = "h-" + k;
    byId["h-" + k] = element("h-" + k);
    editors.push(ed);
  });
  const form = element("form");
  kit.richTrackChanges(editors, form, (ed) => ed.innerHTML);
  return { editors: editors, form: form };
}

console.log("rich-text kit JS: " + file);
let p = page();
p.form.fire("submit");
check("untouched editors stay disabled - nothing is posted",
  ["a", "b", "c"].every((k) => byId["h-" + k].disabled && byId["h-" + k].value === ""));

p = page();
p.editors[1].innerHTML = "<p>b edited</p>";
p.editors[1].fire("input");
p.form.fire("submit");
check("a typed-in editor is posted with its HTML",
  !byId["h-b"].disabled && byId["h-b"].value === "<p>b edited</p>");
check("the others are still not posted", byId["h-a"].disabled && byId["h-c"].disabled);

p = page();
kit.richEdit(p.editors[0], () => { p.editors[0].innerHTML = "<p><b>a</b></p>"; });
kit.richEdit(p.editors[2], () => { /* a command that changed nothing */ });
p.form.fire("submit");
check("a toolbar command that changed the HTML posts it", !byId["h-a"].disabled && byId["h-a"].value === "<p><b>a</b></p>");
check("a toolbar command that changed nothing does not", byId["h-c"].disabled);

console.log(fails === 0 ? "ALL PASS" : fails + " FAILED");
process.exit(fails === 0 ? 0 : 1);
