/* RichTextWeb - client JS (served as a JS document via ($js)).
   Uses the `config` object injected by page.bottom. */
(function () {
  "use strict";

  function showSpinner(show) {
    var overlay = document.getElementById("overlay");
    if (!overlay) return;
    overlay.classList.toggle("d-none", !show);
    overlay.classList.toggle("d-flex", show);
  }

  /* ==== rich-text kit: post a body only when its editor changed ========
     Self-contained - copy this block as it is (guide: RICHTEXT.md in
     the RichTextWeb repo). Markup: each editor element names its hidden
     field in data-target, and that field is rendered DISABLED, so an
     untouched body is never sent: a save elsewhere on the page must not
     rewrite what Notes stored.
       richTrackChanges(editors, form, htmlOf)  once, at page load
       richEdit(editor, fn)                     around each toolbar command
       richHtml(editor)                         the HTML to post (htmlOf)
     Typing, pasting, deleting and source-mode edits fire "input"; a
     toolbar command is compared by hand, because not every browser fires
     "input" for execCommand. */
  function richMarkChanged(editor) {
    editor.setAttribute("data-rich-changed", "1");
  }

  function richEdit(editor, fn) {
    var before = editor.innerHTML;
    fn();
    if (editor.innerHTML !== before) {
      richMarkChanged(editor);
    }
  }

  /* the editor's HTML for posting: a picture the page brought (data-pic)
     goes as its reference, not its bytes - the server writes the original
     picture back, and the POST stays small */
  function richHtml(editor) {
    var copy = editor.cloneNode(true);
    Array.prototype.forEach.call(copy.querySelectorAll("img[data-pic]"), function (img) {
      img.setAttribute("src", "cid:p" + img.getAttribute("data-pic"));
    });
    return copy.innerHTML;
  }

  function richTrackChanges(editors, form, htmlOf) {
    Array.prototype.forEach.call(editors, function (editor) {
      editor.addEventListener("input", function () {
        richMarkChanged(editor);
      });
    });
    form.addEventListener("submit", function () {
      Array.prototype.forEach.call(editors, function (editor) {
        var target = document.getElementById(editor.getAttribute("data-target"));
        if (target && editor.getAttribute("data-rich-changed") === "1") {
          target.value = htmlOf(editor);
          target.disabled = false;
        }
      });
    });
  }
  /* ==== end of rich-text kit =========================================== */

  /* ==== WYSIWYG editor ==================================================
     contenteditable + execCommand, dependency-free. Each .rich-toolbar
     names its editor via data-editor; each .rich-editor names its hidden
     field via data-target, and the rich-text kit above decides what is
     posted. Every control makes only what RichText.write maps back to
     Notes rich text: fonts by their Notes names, sizes in points, colours,
     the three Notes highlights, paragraph alignment, lists, indents,
     links (web, notes://, another page = a doclink), tables with their rows
     and columns, pictures (PNG/GIF/JPEG) and attached files. Notes-only
     elements (hotspots, sections...) are islands the editor leaves alone:
     contenteditable="false", written back as they were. Sanitized
     server-side. */

  /* [CSS family, label]: the three Notes defaults first - write() turns
     these families back into "Default Sans Serif" and friends */
  var RICH_FONTS = [
    ["Arial, sans-serif", "Default Sans Serif"],
    ["'Times New Roman', serif", "Default Serif"],
    ["'Courier New', monospace", "Default Monospace"],
    ["Arial", "Arial"],
    ["Calibri", "Calibri"],
    ["Courier New", "Courier New"],
    ["Georgia", "Georgia"],
    ["Tahoma", "Tahoma"],
    ["Times New Roman", "Times New Roman"],
    ["Verdana", "Verdana"]
  ];
  var RICH_SIZES = [8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 36];
  /* Notes has exactly three highlights */
  var RICH_HIGHLIGHTS = [
    ["transparent", "No highlight"],
    ["#ffff00", "Yellow"],
    ["#ffc0cb", "Pink"],
    ["#add8e6", "Blue"]
  ];
  /* one picture's bytes; the whole save must stay under ~6 MB */
  var RICH_PICTURE_MAX = 2 * 1024 * 1024;
  /* files attached in one save, together (RichText.MAX_NEW_FILE_BYTES),
     and how many (PageSavePost.MAX_NEW_FILES) */
  var RICH_FILE_MAX = 3 * 1024 * 1024;
  var RICH_FILE_COUNT = 20;

  /* [value, label] of the Table menu */
  var RICH_TABLE_OPS = [
    ["insert", "Insert table..."],
    ["rowAbove", "Insert row above"],
    ["rowBelow", "Insert row below"],
    ["colLeft", "Insert column left"],
    ["colRight", "Insert column right"],
    ["delRow", "Delete row"],
    ["delCol", "Delete column"],
    ["delTable", "Delete table"]
  ];

  /* [command, icon, title] - "|" separates groups */
  var RICH_BUTTONS = [
    ["bold", "type-bold", "Bold"],
    ["italic", "type-italic", "Italic"],
    ["underline", "type-underline", "Underline"],
    ["strikeThrough", "type-strikethrough", "Strikethrough"],
    ["superscript", "superscript", "Superscript"],
    ["subscript", "subscript", "Subscript"],
    "|",
    ["justifyLeft", "text-left", "Align left"],
    ["justifyCenter", "text-center", "Center"],
    ["justifyRight", "text-right", "Align right"],
    ["justifyFull", "justify", "Justify"],
    "|",
    ["insertUnorderedList", "list-ul", "Bullet list"],
    ["insertOrderedList", "list-ol", "Numbered list"],
    ["outdent", "text-indent-left", "Outdent"],
    ["indent", "text-indent-right", "Indent"],
    "|",
    ["createLink", "link-45deg", "Link: a web address, a notes:// link or another page (edits the link at the cursor)"],
    ["unlink", "link", "Remove link"],
    ["rich-picture", "image", "Insert picture"],
    ["rich-picture-size", "aspect-ratio", "Picture size (click a picture first)"],
    ["rich-file", "paperclip", "Attach file"],
    ["insertHorizontalRule", "hr", "Horizontal rule"],
    "|",
    ["removeFormat", "eraser", "Clear formatting"],
    ["undo", "arrow-counterclockwise", "Undo"],
    ["redo", "arrow-clockwise", "Redo"],
    ["rich-source", "code-slash", "Edit HTML source"]
  ];

  /* the commands whose button lights up while the cursor is in them */
  var RICH_STATE = ["bold", "italic", "underline", "strikeThrough", "superscript", "subscript", "justifyLeft",
    "justifyCenter", "justifyRight", "justifyFull", "insertUnorderedList", "insertOrderedList"];

  /* [Notes list type, label] of the List menu: the bullet and number
     buttons make the plain kinds, this menu the others (data-list on the
     list, which write() stores as the Notes type and the CSS shows) */
  var RICH_LIST_TYPES = [
    ["bullet", "Bullet"],
    ["number", "Numbered"],
    ["alphaupper", "Letters A B C"],
    ["alphalower", "Letters a b c"],
    ["romanupper", "Roman I II III"],
    ["romanlower", "Roman i ii iii"],
    ["check", "Check marks"],
    ["uncheck", "Empty check boxes"],
    ["circle", "Circles"],
    ["square", "Squares"]
  ];
  var RICH_BULLET_TYPES = ["bullet", "circle", "square", "check", "uncheck"];

  /* a tab as the page shows one: two em spaces, which write() turns back
     into the tab character (RichText.TAB_CHARS) */
  var RICH_TAB = "\u2003\u2003";

  function escapeHtml(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  /* the element of that name around the cursor, inside the editor */
  function closestAt(state, selector) {
    var node = state.range ? state.range.startContainer : null;
    var el = node && (node.nodeType === 1 ? node : node.parentElement);
    var found = el && el.closest ? el.closest(selector) : null;
    return found && state.editor.contains(found) ? found : null;
  }

  /* the list at the cursor becomes the Notes type; no list yet: one is made */
  function setListType(state, type) {
    var bullet = RICH_BULLET_TYPES.indexOf(type) !== -1;
    var list = closestAt(state, "ul,ol");
    if (!list || (list.tagName === "UL") !== bullet) {
      exec(state, bullet ? "insertUnorderedList" : "insertOrderedList");
      list = closestAt(state, "ul,ol");
    }
    if (!list) {
      return;
    }
    // always explicit: a list without data-list keeps the type it had in
    // Notes, so "Bullet" chosen on a check list must say so
    richEdit(state.editor, function () {
      list.setAttribute("data-list", type);
    });
  }

  /* the picture last clicked in the editor, resized by width (the height
     follows); write() keeps the Notes picture and stores the new size */
  function pictureSize(state) {
    var img = state.picture;
    if (!img || !state.editor.contains(img)) {
      window.alert("Click a picture first.");
      return;
    }
    var width = parseInt(window.prompt("Picture width in pixels:", img.getAttribute("width") || img.naturalWidth), 10);
    if (!(width > 0 && width < 10000)) {
      return;
    }
    var ratio = (parseInt(img.getAttribute("height"), 10) || img.naturalHeight) /
      (parseInt(img.getAttribute("width"), 10) || img.naturalWidth || 1);
    richEdit(state.editor, function () {
      img.setAttribute("width", String(width));
      img.setAttribute("height", String(Math.max(1, Math.round(width * ratio))));
    });
  }

  /* the Link button: a new link, or the target of the link at the cursor */
  function editLink(state) {
    var link = closestAt(state, "a");
    var url = window.prompt("Link to - a web address, a notes:// link, or another page's address:",
      link ? link.getAttribute("href") : "https://");
    if (!url || url === "https://") {
      return;
    }
    restoreRange(state);
    if (link) {
      richEdit(state.editor, function () {
        link.setAttribute("href", url);
      });
    } else if (window.getSelection().isCollapsed) {
      exec(state, "insertHTML", "<a href=\"" + escapeHtml(url) + "\">" + escapeHtml(url) + "</a>");
    } else {
      exec(state, "createLink", url);
    }
  }

  function toggleSource(editor, btn) {
    if (editor.classList.contains("rich-source")) {
      editor.innerHTML = editor.textContent;
      editor.classList.remove("rich-source");
      btn.classList.remove("active");
    } else {
      editor.textContent = editor.innerHTML;
      editor.classList.add("rich-source");
      btn.classList.add("active");
    }
  }

  function editorHtml(editor) {
    if (editor.classList.contains("rich-source")) {
      return editor.textContent;
    }
    return richHtml(editor);
  }

  /* the editor's last selection: a select or the colour picker takes the
     focus, the command must still act on what was selected */
  function saveRange(state) {
    var sel = window.getSelection();
    if (sel.rangeCount && state.editor.contains(sel.getRangeAt(0).commonAncestorContainer)) {
      state.range = sel.getRangeAt(0).cloneRange();
    }
  }

  function restoreRange(state) {
    state.editor.focus();
    if (state.range) {
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(state.range);
    }
  }

  function exec(state, command, value) {
    if (state.editor.classList.contains("rich-source")) {
      return; // formatting is inert while editing raw HTML
    }
    restoreRange(state);
    richEdit(state.editor, function () {
      // tags (<b>, <font>) for everything but the highlight, which the
      // browser only makes as a background-colour span
      document.execCommand("styleWithCSS", false, command === "hiliteColor");
      document.execCommand(command, false, value);
    });
    saveRange(state);
    refresh(state);
  }

  /* sizes in points: the browser's fontSize only knows 1-7, so size 7 is
     applied and turned straight into font-size: Npt */
  function setSize(state, pt) {
    if (state.editor.classList.contains("rich-source")) {
      return;
    }
    restoreRange(state);
    richEdit(state.editor, function () {
      document.execCommand("styleWithCSS", false, false);
      document.execCommand("fontSize", false, "7");
      Array.prototype.forEach.call(state.editor.querySelectorAll("font[size='7']"), function (font) {
        var span = document.createElement("span");
        span.style.fontSize = pt + "pt";
        while (font.firstChild) {
          span.appendChild(font.firstChild);
        }
        font.parentNode.replaceChild(span, font);
      });
    });
    refresh(state);
  }

  /* a picture file as an inline picture at its own size */
  function insertPicture(state, file) {
    if (!/^image\/(png|gif|jpeg)$/.test(file.type)) {
      window.alert("Pictures can be PNG, GIF or JPEG.");
      return;
    }
    if (file.size > RICH_PICTURE_MAX) {
      window.alert("That picture is larger than 2 MB - make it smaller first.");
      return;
    }
    var reader = new FileReader();
    reader.onload = function () {
      var probe = new Image();
      probe.onload = function () {
        restoreRange(state);
        richEdit(state.editor, function () {
          document.execCommand("insertHTML", false, "<img src=\"" + reader.result + "\" width=\"" +
            probe.naturalWidth + "\" height=\"" + probe.naturalHeight + "\">");
        });
      };
      probe.src = reader.result;
    };
    reader.readAsDataURL(file);
  }

  function insertTable(state) {
    var rows = parseInt(window.prompt("Rows:", "3"), 10);
    var cols = parseInt(window.prompt("Columns:", "2"), 10);
    if (rows > 0 && cols > 0) {
      var html = "<table><tbody>";
      for (var r = 0; r < rows; r++) {
        html += "<tr>";
        for (var c = 0; c < cols; c++) {
          html += "<td><br></td>";
        }
        html += "</tr>";
      }
      exec(state, "insertHTML", html + "</tbody></table><div><br></div>");
    }
  }

  /* ---- table rows and columns, at the cell the cursor is in. The writer
     rebuilds a Notes table from whatever rows and cells the HTML has, so
     plain DOM edits are enough; a spanned cell is widened or narrowed. */
  function currentCell(state) {
    var node = state.range ? state.range.startContainer : null;
    var el = node && (node.nodeType === 1 ? node : node.parentElement);
    var cell = el && el.closest ? el.closest("td,th") : null;
    return cell && state.editor.contains(cell) ? cell : null;
  }

  function columnOf(cell) {
    var col = 0;
    for (var c = cell.parentNode.firstElementChild; c && c !== cell; c = c.nextElementSibling) {
      col += c.colSpan || 1;
    }
    return col;
  }

  function cellAtColumn(row, col) {
    var at = 0;
    for (var c = row.firstElementChild; c; c = c.nextElementSibling) {
      var span = c.colSpan || 1;
      if (col < at + span) {
        return c;
      }
      at += span;
    }
    return null;
  }

  /* an empty cell styled like its neighbour (borders, background); a new
     column's cells take no width - the writer shares the table out */
  function newCell(like, keepWidth) {
    var cell = document.createElement(like && like.tagName === "TH" ? "th" : "td");
    if (like && like.getAttribute("style")) {
      cell.setAttribute("style", like.getAttribute("style"));
      if (!keepWidth) {
        cell.style.width = "";
      }
    }
    cell.innerHTML = "<br>";
    return cell;
  }

  function tableOp(state, op) {
    if (state.editor.classList.contains("rich-source")) {
      return;
    }
    if (op === "insert") {
      insertTable(state);
      return;
    }
    var cell = currentCell(state);
    if (!cell) {
      window.alert("Put the cursor in a table cell first.");
      return;
    }
    var row = cell.parentNode;
    var table = cell.closest("table");
    var col = columnOf(cell);
    var rows = Array.prototype.filter.call(table.querySelectorAll("tr"), function (r) {
      return r.closest("table") === table; // not the rows of a table inside a cell
    });
    richEdit(state.editor, function () {
      if (op === "rowAbove" || op === "rowBelow") {
        var tr = document.createElement("tr");
        Array.prototype.forEach.call(row.children, function (c) {
          var n = newCell(c, true);
          if (c.colSpan > 1) {
            n.colSpan = c.colSpan;
          }
          tr.appendChild(n);
        });
        row.parentNode.insertBefore(tr, op === "rowAbove" ? row : row.nextSibling);
      } else if (op === "colLeft" || op === "colRight") {
        rows.forEach(function (r) {
          var at = cellAtColumn(r, col);
          if (at) {
            r.insertBefore(newCell(at, false), op === "colLeft" ? at : at.nextSibling);
          } else {
            r.appendChild(newCell(r.lastElementChild, false));
          }
        });
      } else if (op === "delRow") {
        row.parentNode.removeChild(row);
      } else if (op === "delCol") {
        rows.forEach(function (r) {
          var at = cellAtColumn(r, col);
          if (at && at.colSpan > 1) {
            at.colSpan -= 1;
          } else if (at) {
            r.removeChild(at);
          }
          if (!r.children.length) {
            r.parentNode.removeChild(r);
          }
        });
      }
      if (op === "delTable" || !table.querySelector("td,th")) {
        table.parentNode.removeChild(table);
      }
    });
    state.range = null;
  }

  /* ---- a draft across a failed save: what the editor held is stored in
     the browser's session storage when the form is submitted, and put
     back when the page comes back with an error (a save the server
     refused) - the user does not lose the work. A page that loads without
     an error drops the draft. Files attached in the draft are gone (their
     bytes are not kept): their chips are removed and the note says so. */
  function draftKey(editor, form) {
    return "rich-draft:" + editor.id + ":" + (form.getAttribute("action") || location.pathname);
  }

  function storeDraft(editor, form, html) {
    try {
      sessionStorage.setItem(draftKey(editor, form), html);
    } catch (e) {
      /* storage unavailable or full: the draft is simply not kept */
    }
  }

  function restoreDraft(editor, form, failed) {
    var key = draftKey(editor, form), draft = null;
    try {
      draft = sessionStorage.getItem(key);
      sessionStorage.removeItem(key);
    } catch (e) {
      return;
    }
    if (!draft || !failed) {
      return;
    }
    var files = /data-file=/.test(draft);
    editor.innerHTML = draft.replace(/<span[^>]*data-file=[^>]*>.*?<\/span>(&nbsp;| )?/g, "");
    richMarkChanged(editor);
    var note = document.createElement("p");
    note.className = "form-text rich-draft-note";
    note.textContent = "Your unsaved changes are back in the editor." +
      (files ? " The files you attached must be attached again." : "");
    var toolbar = document.querySelector(".rich-toolbar[data-editor='" + editor.id + "']");
    (toolbar || editor).parentNode.insertBefore(note, toolbar || editor);
  }

  /* ---- a file attached where the cursor is: the editor shows an icon
     (<span data-file="N">), the bytes ride a hidden field newfileN =
     "name|base64" - the server stores the file as an attachment there */
  var richFiles = { next: 1, bytes: 0 };

  function attachFile(state, file) {
    // the form that posts the editor's field (data-target) carries the file
    var target = document.getElementById(state.editor.getAttribute("data-target"));
    var form = (target && target.form) || state.editor.closest("form");
    if (!form) {
      return;
    }
    if (richFiles.next > RICH_FILE_COUNT) {
      window.alert("One save can attach " + RICH_FILE_COUNT + " files - save, then attach more.");
      return;
    }
    if (richFiles.bytes + file.size > RICH_FILE_MAX) {
      window.alert("Files attached here can be 3 MB together - attach larger files in Notes.");
      return;
    }
    var reader = new FileReader();
    reader.onload = function () {
      var n = richFiles.next++;
      richFiles.bytes += file.size;
      var input = document.createElement("input");
      input.type = "hidden";
      input.name = "newfile" + n;
      input.value = file.name + "|" + String(reader.result).replace(/^data:[^,]*,/, "");
      form.appendChild(input);
      restoreRange(state);
      richEdit(state.editor, function () {
        document.execCommand("insertHTML", false, "<span data-file=\"" + n + "\" contenteditable=\"false\">&#128206; " +
          escapeHtml(file.name) + "</span>&nbsp;");
      });
    };
    reader.readAsDataURL(file);
  }

  /* pasted HTML carrying this app's markers (copied from a page, maybe
     another document's): the markers go - a data-pic or data-keep would
     point at THIS document's picture or hotspot of that number - and with
     them anything that could run */
  var RICH_MARKERS = /^data-(keep|kind|pic|cap|pd|file|tbl)$/;

  function cleanPasted(html) {
    var box = document.createElement("template"); // inert: nothing loads or runs
    box.innerHTML = html;
    Array.prototype.forEach.call(box.content.querySelectorAll(
      "script,style,iframe,object,embed,link,meta,form,input,button,textarea,select"), function (e) {
      e.parentNode.removeChild(e);
    });
    Array.prototype.forEach.call(box.content.querySelectorAll("*"), function (e) {
      Array.prototype.slice.call(e.attributes).forEach(function (a) {
        var name = a.name.toLowerCase();
        if (name.indexOf("on") === 0 || name === "contenteditable" || RICH_MARKERS.test(name) ||
            ((name === "href" || name === "src") && /^\s*javascript:/i.test(a.value))) {
          e.removeAttribute(a.name);
        }
      });
    });
    return box.innerHTML;
  }

  /* the font the cursor is in, as one of RICH_FONTS ("" when none) */
  function matchFont(family) {
    var first = function (f) {
      return f.split(",")[0].replace(/['"]/g, "").trim().toLowerCase();
    };
    for (var i = 0; i < RICH_FONTS.length; i++) {
      if (first(RICH_FONTS[i][0]) === first(family)) {
        return RICH_FONTS[i][0];
      }
    }
    return "";
  }

  /* the toolbar shows what the cursor is in, as the Notes toolbar does */
  function refresh(state) {
    var focused = document.activeElement === state.editor &&
      !state.editor.classList.contains("rich-source");
    state.buttons.forEach(function (pair) {
      pair[1].classList.toggle("active", focused && document.queryCommandState(pair[0]));
    });
    var sel = window.getSelection();
    if (!focused || !sel.rangeCount) {
      return;
    }
    var node = sel.getRangeAt(0).startContainer;
    var el = node.nodeType === 1 ? node : node.parentElement;
    if (!el || !state.editor.contains(el)) {
      return;
    }
    var css = window.getComputedStyle(el);
    state.font.value = matchFont(css.fontFamily);
    var pt = Math.round(parseFloat(css.fontSize) * 0.75);
    state.size.value = RICH_SIZES.indexOf(pt) >= 0 ? String(pt) : "";
    var list = el.closest("ul,ol");
    state.list.value = !list || !state.editor.contains(list) ? ""
      : list.getAttribute("data-list") || (list.tagName === "UL" ? "bullet" : "number");
  }

  function makeSelect(title, first, options, onChange) {
    var select = document.createElement("select");
    select.className = "form-select form-select-sm w-auto";
    select.title = title;
    select.setAttribute("aria-label", title);
    [[""].concat(first)].concat(options).forEach(function (o) {
      var option = document.createElement("option");
      option.value = o[0];
      option.textContent = o[1];
      select.appendChild(option);
    });
    select.addEventListener("change", function () {
      if (select.value !== "") {
        onChange(select.value);
      }
    });
    return select;
  }

  function buildToolbar(toolbar, state) {
    var group = function () {
      var g = document.createElement("div");
      g.className = "btn-group btn-group-sm";
      g.setAttribute("role", "group");
      toolbar.appendChild(g);
      return g;
    };

    state.font = makeSelect("Font", "Font", RICH_FONTS, function (v) {
      exec(state, "fontName", v);
    });
    state.size = makeSelect("Size", "Size", RICH_SIZES.map(function (s) {
      return [String(s), s + " pt"];
    }), function (v) {
      setSize(state, v);
    });
    toolbar.appendChild(state.font);
    toolbar.appendChild(state.size);

    var color = document.createElement("input");
    color.type = "color";
    color.value = "#000000";
    color.className = "form-control form-control-sm form-control-color";
    color.title = "Text colour";
    color.setAttribute("aria-label", "Text colour");
    color.addEventListener("change", function () {
      exec(state, "foreColor", color.value);
    });
    toolbar.appendChild(color);

    var highlight = makeSelect("Highlight", "Highlight", RICH_HIGHLIGHTS, function (v) {
      exec(state, "hiliteColor", v);
      highlight.value = "";
    });
    toolbar.appendChild(highlight);

    state.list = makeSelect("List type", "List", RICH_LIST_TYPES, function (v) {
      setListType(state, v);
    });
    toolbar.appendChild(state.list);

    var table = makeSelect("Table", "Table", RICH_TABLE_OPS, function (v) {
      table.value = "";
      tableOp(state, v);
    });
    toolbar.appendChild(table);

    var attach = document.createElement("input");
    attach.type = "file";
    attach.className = "d-none";
    attach.setAttribute("data-rich-attach", "1");
    attach.addEventListener("change", function () {
      if (attach.files.length) {
        attachFile(state, attach.files[0]);
      }
      attach.value = "";
    });
    toolbar.appendChild(attach);

    var file = document.createElement("input");
    file.type = "file";
    file.accept = "image/png,image/gif,image/jpeg";
    file.className = "d-none";
    file.addEventListener("change", function () {
      if (file.files.length) {
        insertPicture(state, file.files[0]);
      }
      file.value = "";
    });
    toolbar.appendChild(file);

    var g = group();
    RICH_BUTTONS.forEach(function (def) {
      if (def === "|") {
        g = group();
        return;
      }
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "btn btn-outline-secondary";
      btn.title = def[2];
      btn.setAttribute("aria-label", def[2]);
      btn.innerHTML = "<i class='bi bi-" + def[1] + "'></i>";
      btn.addEventListener("click", function () {
        if (def[0] === "rich-source") {
          toggleSource(state.editor, btn);
        } else if (def[0] === "rich-picture") {
          saveRange(state);
          file.click();
        } else if (def[0] === "rich-file") {
          saveRange(state);
          attach.click();
        } else if (def[0] === "createLink") {
          // a web address stays a URL link; a notes:// link or another
          // page's address becomes a Notes doclink when saved
          editLink(state);
        } else if (def[0] === "rich-picture-size") {
          pictureSize(state);
        } else {
          exec(state, def[0]);
        }
      });
      if (RICH_STATE.indexOf(def[0]) !== -1) {
        state.buttons.push([def[0], btn]);
      }
      g.appendChild(btn);
    });
  }

  function initRichEditors() {
    var editors = document.querySelectorAll(".rich-editor");
    if (!editors.length) {
      return;
    }

    var states = [];
    document.querySelectorAll(".rich-toolbar").forEach(function (toolbar) {
      var editor = document.getElementById(toolbar.getAttribute("data-editor"));
      if (!editor) {
        return;
      }
      var state = { editor: editor, range: null, buttons: [], picture: null };
      buildToolbar(toolbar, state);
      // Tab types a tab, as in Notes (Shift+Tab still leaves the editor)
      editor.addEventListener("keydown", function (e) {
        if (e.key === "Tab" && !e.shiftKey && !editor.classList.contains("rich-source")) {
          e.preventDefault();
          exec(state, "insertText", RICH_TAB);
        }
      });
      // the picture last clicked is what "Picture size" resizes
      editor.addEventListener("click", function (e) {
        state.picture = e.target && e.target.tagName === "IMG" ? e.target : null;
      });
      // a pasted screenshot comes in as a picture file, not as HTML; HTML
      // copied from a page of this app loses its markers (cleanPasted)
      editor.addEventListener("paste", function (e) {
        var files = e.clipboardData && e.clipboardData.files;
        if (files && files.length && /^image\//.test(files[0].type)) {
          e.preventDefault();
          saveRange(state);
          insertPicture(state, files[0]);
          return;
        }
        var html = e.clipboardData && e.clipboardData.getData("text/html");
        if (html && /data-(keep|kind|pic|cap|pd|file|tbl)|contenteditable/i.test(html)) {
          e.preventDefault();
          saveRange(state);
          exec(state, "insertHTML", cleanPasted(html));
        }
      });
      states.push(state);
    });

    document.addEventListener("selectionchange", function () {
      states.forEach(function (state) {
        saveRange(state);
        refresh(state);
      });
    });

    // each editor posts through the form of its own hidden field
    var forms = [];
    Array.prototype.forEach.call(editors, function (editor) {
      var target = document.getElementById(editor.getAttribute("data-target"));
      var form = target && target.form;
      if (!form) {
        return;
      }
      var entry = forms.filter(function (f) { return f.form === form; })[0];
      if (!entry) {
        entry = { form: form, editors: [] };
        forms.push(entry);
      }
      entry.editors.push(editor);
    });
    var failed = /[?&]err=/.test(location.search);
    forms.forEach(function (entry) {
      richTrackChanges(entry.editors, entry.form, editorHtml);
      entry.editors.forEach(function (editor) {
        restoreDraft(editor, entry.form, failed);
      });
      entry.form.addEventListener("submit", function () {
        entry.editors.forEach(function (editor) {
          if (editor.getAttribute("data-rich-changed") === "1") {
            storeDraft(editor, entry.form, editorHtml(editor));
          }
        });
      });
    });
  }

  /* ==== end of WYSIWYG editor ============================================ */

  /* a Copy button: data-copy names the element whose text it copies */
  function initCopy() {
    document.querySelectorAll("[data-copy]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var el = document.getElementById(btn.getAttribute("data-copy"));
        if (!el) {
          return;
        }
        var done = function () {
          btn.innerHTML = "<i class='bi bi-check-lg me-1'></i>Copied";
        };
        var fallback = function () {
          var range = document.createRange();
          range.selectNodeContents(el);
          var sel = window.getSelection();
          sel.removeAllRanges();
          sel.addRange(range);
          if (document.execCommand("copy")) {
            done();
          }
        };
        if (navigator.clipboard && window.isSecureContext) {
          navigator.clipboard.writeText(el.textContent).then(done, fallback);
        } else {
          fallback();
        }
      });
    });
  }

  /* busy overlay while a form posts - the redirect reloads the page */
  function initForms() {
    document.querySelectorAll("form[method=post]").forEach(function (form) {
      form.addEventListener("submit", function () {
        showSpinner(true);
      });
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    initRichEditors();
    initForms();
    initCopy();
  });
  // back button (bfcache): the page comes back as it was left - overlay up
  window.addEventListener("pageshow", function () {
    showSpinner(false);
  });
})();
