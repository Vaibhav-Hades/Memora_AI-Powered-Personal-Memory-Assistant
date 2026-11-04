// Memora UI: plain JavaScript, no build step. All text from files is inserted with textContent, never as HTML.

const PREVIEWABLE = new Set([".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"]);
const $ = (id) => document.getElementById(id);

// Build a DOM element: el("a", { href: "/x", class: "y" }, "text", childNode)
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? "" : value);
  }
  node.append(...children.filter((c) => c != null && c !== false));
  return node;
}

async function api(path, options = {}) {
  const res = await fetch(`/api${path}`, { headers: { "Content-Type": "application/json" }, ...options });
  const body = res.headers.get("content-type")?.includes("json") ? await res.json() : null;
  if (!res.ok) {
    const detail = Array.isArray(body?.detail) ? body.detail.map((d) => d.msg).join("; ") : body?.detail;
    throw new Error(detail || `Request failed (${res.status})`);
  }
  return body;
}

const contentUrl = (fileId) => `/api/files/${fileId}/content`;
const thumbUrl = (fileId, size = 160) => `/api/files/${fileId}/thumbnail?size=${size}`;

function show(node, visible) {
  node.hidden = !visible;
}

// ---------- Dates ----------
// Dates are shown the way the source states them. Epoch seconds come from file records; EXIF values are wall-clock
// strings that are formatted without any time-zone conversion so they can't slip to another day.

const fmtDateTime = (epochSeconds) =>
  new Date(epochSeconds * 1000).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
const fmtDay = (epochSeconds) => new Date(epochSeconds * 1000).toLocaleDateString(undefined, { dateStyle: "medium" });

function fmtCalendarDate(iso) {
  const [y, m, d] = iso.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(undefined, { dateStyle: "long", timeZone: "UTC" });
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
}

// ---------- Tabs ----------

const TABS = ["home", "photos", "memories", "search", "library", "settings"];
let allFiles = [];

function setTab(name) {
  document.querySelectorAll(".nav button").forEach((b) => {
    const on = b.dataset.tab === name;
    b.classList.toggle("active", on);
    if (on) b.setAttribute("aria-current", "page");
    else b.removeAttribute("aria-current");
  });
  TABS.forEach((t) => show($(`tab-${t}`), t === name));
  if (name === "library" || name === "settings") refreshLibrary();
  if (name === "home" || name === "memories") refreshFiles();
  if (name === "photos" && window.onPhotosTab) window.onPhotosTab();
  if (name === "settings" && window.onSettingsTab) window.onSettingsTab();
  if (name === "search") $("question").focus();
}

document.querySelectorAll("[data-tab]").forEach((b) => b.addEventListener("click", () => setTab(b.dataset.tab)));
document.querySelectorAll("[data-goto]").forEach((b) => b.addEventListener("click", () => setTab(b.dataset.goto)));

// ---------- Health / status (polled) ----------

let lastIndexing = {};

async function pollHealth() {
  let health;
  try {
    health = await api("/health");
  } catch (e) {
    $("status").textContent = "Offline";
    $("status").className = "pill pill-bad";
    $("health-banner").textContent = `Can't reach the Memora backend. Is it running? (${e.message})`;
    show($("health-banner"), true);
    return;
  }
  const { model, indexing, stats } = health;
  const status = $("status");

  if (!model.ok) {
    status.textContent = "AI offline";
    status.className = "pill pill-bad";
    $("health-banner").textContent =
      model.error ||
      `Install the missing model(s) with: ${model.missing_models.map((m) => `ollama pull ${m}`).join(" && ")}`;
  } else if (indexing.running) {
    status.textContent = `Indexing ${indexing.done}/${indexing.total}`;
    status.className = "pill pill-busy";
  } else {
    status.textContent = `${stats.indexed} files indexed · local`;
    status.className = "pill pill-ok";
  }
  show($("health-banner"), !model.ok);
  show($("empty-banner"), stats.files === 0);
  show($("overview"), stats.files > 0);
  $("n-photos").textContent = stats.images;
  $("n-docs").textContent = stats.documents;
  $("n-indexed").textContent = stats.indexed;
  $("n-failed").textContent = stats.errors;

  renderProgress(indexing);
  // Refresh lists while indexing moves forward or right after it finishes.
  if (indexing.done !== lastIndexing.done || indexing.running !== lastIndexing.running) {
    if (!$("tab-library").hidden) refreshLibrary();
    if (!$("tab-home").hidden || !$("tab-memories").hidden) refreshFiles();
    if (!$("tab-photos").hidden && window.refreshPhotos) window.refreshPhotos();
  }
  lastIndexing = indexing;
}

function renderProgress(indexing) {
  show($("progress"), !!indexing.running);
  if (!indexing.running) return;
  $("progress-label").textContent =
    `Indexing ${indexing.folder} — ${indexing.done}/${indexing.total}` + (indexing.current ? ` · ${indexing.current}` : "");
  $("progress-bar").style.width = `${indexing.total ? (100 * indexing.done) / indexing.total : 0}%`;
}

// ---------- Thumbnails and rows ----------

function thumb(file, size) {
  const box = el("span", { class: "thumb", style: `width:${size}px;height:${size}px` });
  const badge = () => box.replaceChildren(el("span", { class: "ext" }, (file.ext || "").replace(".", "").toUpperCase() || "FILE"));
  if (file.kind === "image" || file.ext === ".pdf") {
    const img = el("img", { src: thumbUrl(file.id, size * 2), alt: "", loading: "lazy" });
    img.addEventListener("error", badge);
    box.append(img);
  } else {
    badge();
  }
  return box;
}

function memoryRow(file, dateText) {
  return el(
    "li",
    {},
    el(
      "button",
      { class: "row", onclick: () => openDetail(file) },
      thumb(file, 56),
      el(
        "span",
        { class: "row-body" },
        el("span", { class: "row-title" }, file.name),
        file.summary && el("span", { class: "row-sub" }, file.summary),
        el("span", { class: "row-meta" }, dateText, file.status !== "indexed" && el("span", { class: "tag tag-warn" }, file.status)),
      ),
    ),
  );
}

async function refreshFiles() {
  try {
    allFiles = await api("/files?limit=2000");
  } catch (e) {
    allFiles = [];
  }
  renderHome();
  renderTimeline();
}

// ---------- Home ----------

function greeting() {
  const h = new Date().getHours();
  return h < 5 ? "Good evening" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
}

function renderHome() {
  $("greeting").textContent = greeting();
  $("today").textContent = new Date().toLocaleDateString(undefined, { dateStyle: "full" });
  const recent = [...allFiles].sort((a, b) => (b.indexed_at ?? 0) - (a.indexed_at ?? 0)).slice(0, 6);
  show($("recent-wrap"), recent.length > 0);
  $("recent").replaceChildren(
    ...recent.map((f) => memoryRow(f, f.indexed_at ? `Added to Memora ${fmtDay(f.indexed_at)}` : "Not indexed yet")),
  );
}

$("home-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const q = $("home-question").value.trim();
  if (!q) return;
  setTab("search");
  ask(q);
});

// ---------- Memories (timeline) ----------

const tl = { kind: "all", group: "month", dateKey: "mtime", newestFirst: true, query: "" };

function groupLabel(epoch) {
  const d = new Date(epoch * 1000);
  if (tl.group === "year") return String(d.getFullYear());
  if (tl.group === "month") return d.toLocaleDateString(undefined, { month: "long", year: "numeric" });
  return d.toLocaleDateString(undefined, { dateStyle: "full" });
}

function renderTimeline() {
  const q = tl.query.trim().toLowerCase();
  const items = allFiles
    .filter((f) => f[tl.dateKey])
    .filter((f) => tl.kind === "all" || (tl.kind === "photos") === (f.kind === "image"))
    .filter((f) => !q || f.name.toLowerCase().includes(q))
    .sort((a, b) => (tl.newestFirst ? b[tl.dateKey] - a[tl.dateKey] : a[tl.dateKey] - b[tl.dateKey]));
  const box = $("timeline");
  if (!allFiles.length) {
    box.replaceChildren(el("div", { class: "empty" }, el("h2", {}, "No memories yet"), el("p", { class: "muted" }, "Add a folder in Library and they will appear here.")));
    return;
  }
  if (!items.length) {
    box.replaceChildren(el("p", { class: "muted" }, "Nothing matches this filter."));
    return;
  }
  const label = tl.dateKey === "mtime" ? "File modified" : "Added to Memora";
  const nodes = [];
  let current = null;
  let list = null;
  for (const f of items) {
    const g = groupLabel(f[tl.dateKey]);
    if (g !== current) {
      current = g;
      nodes.push(el("h2", { class: "group-title" }, g));
      list = el("ul", { class: "rows" });
      nodes.push(list);
    }
    list.append(memoryRow(f, `${label} ${tl.group === "day" ? new Date(f[tl.dateKey] * 1000).toLocaleTimeString(undefined, { timeStyle: "short" }) : fmtDay(f[tl.dateKey])}`));
  }
  box.replaceChildren(...nodes);
}

function segment(attr, apply) {
  document.querySelectorAll(`[data-${attr}]`).forEach((b) =>
    b.addEventListener("click", () => {
      document.querySelectorAll(`[data-${attr}]`).forEach((x) => x.classList.toggle("active", x === b));
      apply(b.dataset[attr === "datekind" ? "datekind" : attr]);
      renderTimeline();
    }),
  );
}
segment("kind", (v) => (tl.kind = v));
segment("group", (v) => (tl.group = v));
segment("datekind", (v) => (tl.dateKey = v));
$("tl-query").addEventListener("input", (e) => {
  tl.query = e.target.value;
  renderTimeline();
});
$("tl-sort").addEventListener("click", () => {
  tl.newestFirst = !tl.newestFirst;
  $("tl-sort").textContent = tl.newestFirst ? "Newest first" : "Oldest first";
  renderTimeline();
});

// ---------- Detail panel ----------

function metaRow(label, value, note) {
  return [
    el("dt", {}, label),
    el("dd", {}, value ?? el("span", { class: "na" }, "Not available"), note && el("span", { class: "note" }, note)),
  ];
}

async function openDetail(file) {
  // Photos have their own richer view (photos.js); fall back to the generic one if it isn't available.
  if (file.kind === "image" && window.openPhoto && (await window.openPhoto(file.id))) return;
  const body = $("detail-body");
  body.replaceChildren(el("p", { class: "muted" }, "Loading…"));
  show($("detail"), true);
  $("detail").dataset.opener = document.activeElement?.id || "";
  const isPhoto = file.kind === "image";
  let meta = null;
  try {
    meta = await api(`/files/${file.id}/meta`);
  } catch (e) {
    meta = null; // original moved or deleted: the file record is still shown
  }
  const url = contentUrl(file.id);
  const err = el("span", { class: "error-text" });
  const hero = el("div", { class: "hero" });
  if (isPhoto && PREVIEWABLE.has(file.ext)) {
    // Whole picture in its own proportions; opens the original on click.
    hero.append(el("a", { href: url, target: "_blank", rel: "noreferrer" }, el("img", { src: url, alt: file.name })));
  } else if (file.ext === ".pdf") {
    hero.append(el("img", { src: thumbUrl(file.id, 800), alt: `First page of ${file.name}` }));
  } else {
    hero.append(el("span", { class: "ext" }, (file.ext || "").replace(".", "").toUpperCase() || "FILE"));
  }
  const cap = meta?.captured;
  const dates = [];
  if (isPhoto) {
    dates.push(
      ...metaRow("Captured", cap ? fmtCalendarDate(cap.date) : null, cap ? "From the photo's metadata" : "The photo doesn't record when it was taken"),
      ...metaRow("Capture time", cap?.time ?? null),
      ...metaRow("Time zone", cap?.offset ? `UTC${cap.offset}` : null, cap && !cap.offset ? "Not recorded in the file; shown as the camera wrote it" : null),
    );
  }
  dates.push(
    ...metaRow("File modified", file.mtime ? fmtDateTime(file.mtime) : null, "From this computer's file record. Not when a photo was taken."),
    ...metaRow("Added to Memora", file.indexed_at ? fmtDateTime(file.indexed_at) : null),
  );
  const loc = meta?.location;
  const details = [
    ...metaRow("Original filename", file.name),
    ...metaRow("File type", file.ext ? file.ext.replace(".", "").toUpperCase() : null),
    ...metaRow("File size", formatSize(file.size)),
    isPhoto ? metaRow("Dimensions", meta?.width ? `${meta.width} × ${meta.height} px` : null) : [],
    ...metaRow("Location on this computer", file.path),
    ...metaRow("Indexing", file.status === "error" ? `Failed: ${file.error ?? "unknown error"}` : file.status),
  ].flat();

  body.replaceChildren(
    hero,
    el("h2", { id: "detail-title" }, file.name),
    file.summary &&
      el("section", {}, el("h3", {}, isPhoto ? "AI description" : "Summary"), el("p", {}, file.summary),
        el("span", { class: "note" }, "Written by the local AI model. It can be wrong, so check it against the original.")),
    el("section", {}, el("h3", {}, "Date & time"), el("dl", { class: "meta" }, ...dates),
      el("span", { class: "note" }, "Memora shows only dates stored in the file or its record. It never guesses when something happened.")),
    isPhoto &&
      el("section", {}, el("h3", {}, "Location"),
        el("dl", { class: "meta" }, ...metaRow("Where", loc ? `${Math.abs(loc.lat).toFixed(4)}° ${loc.lat >= 0 ? "N" : "S"}, ${Math.abs(loc.lon).toFixed(4)}° ${loc.lon >= 0 ? "E" : "W"}` : null,
          loc ? "From the photo's GPS metadata. No map lookup is made." : null))),
    el("details", {}, el("summary", {}, "File details"), el("dl", { class: "meta" }, ...details)),
    el("section", { class: "actions", style: "margin-top:20px" },
      el("a", { class: "primary", href: url, target: "_blank", rel: "noreferrer" }, el("button", { class: "primary" }, "Open original")),
      el("button", { onclick: () => api(`/files/${file.id}/reveal`, { method: "POST" }).catch((e) => (err.textContent = e.message)) }, "Show in folder"),
      err),
  );
  body.querySelector("a > button.primary")?.focus();
}

function closeDetail() {
  show($("detail"), false);
}
document.querySelectorAll("[data-close]").forEach((n) => n.addEventListener("click", closeDetail));
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("detail").hidden) closeDetail();
});

// ---------- Search / answers ----------

// Turn "[2]" markers in the answer into links to the matching evidence card.
function withCitations(text, refs, className) {
  const p = el("p", { class: className });
  for (const part of text.split(/(\[\d+\])/g)) {
    const m = part.match(/^\[(\d+)\]$/);
    p.append(m && refs.has(Number(m[1])) ? el("a", { class: "cite", href: `#src-${m[1]}` }, m[1]) : part);
  }
  return p;
}

// First sentence is the direct answer; the rest explains it. Wording is never changed.
function splitAnswer(text) {
  const t = text.trim();
  const m = t.match(/(?<=[.!?])(?:\[\d+\])*\s+/);
  if (!m) return [t, ""];
  return [t.slice(0, m.index).trim() || t, t.slice(m.index + m[0].length).trim()];
}

function sourceCard(s) {
  const url = contentUrl(s.file_id);
  const error = el("span", { class: "error-text" });
  const reveal = () => api(`/files/${s.file_id}/reveal`, { method: "POST" }).catch((e) => (error.textContent = e.message));
  const file = { id: s.file_id, name: s.name, ext: s.ext, kind: s.kind };

  return el(
    "article",
    { id: `src-${s.ref}`, class: `source${s.cited ? " cited" : ""}` },
    thumb(file, 88),
    el(
      "div",
      { class: "source-body" },
      el("div", { class: "source-head" }, el("span", { class: "ref" }, `[${s.ref}]`), el("strong", { class: "source-name" }, s.name), s.cited && el("span", { class: "tag tag-ok" }, "cited")),
      el("div", { class: "path", title: s.path }, s.path),
      s.photo &&
        el("p", { class: "muted small" },
          s.photo.caption || "No caption yet", " · ",
          s.photo.captured_date ? `Taken ${fmtCalendarDate(s.photo.captured_date)}` : "Capture date not recorded in the photo"),
      el("p", { class: "snippet" }, s.snippet),
      el("div", { class: "actions" },
        s.photo && window.openPhoto && el("button", { class: "link", onclick: () => window.openPhoto(s.file_id) }, "Photo details"),
        el("a", { href: url, target: "_blank", rel: "noreferrer" }, "Open original"),
        el("button", { class: "link", onclick: reveal }, "Show in folder"),
        error),
    ),
  );
}

function renderAnswer(result) {
  const area = $("answer-area");
  const cited = result.sources.filter((s) => s.cited);
  const others = result.sources.filter((s) => !s.cited);
  const refs = new Set(result.sources.map((s) => s.ref));
  const [direct, explanation] = splitAnswer(result.answer);

  const othersList = el("div", { class: "sources", hidden: true }, ...others.map(sourceCard));
  const toggle = el("button", { class: "link" });
  const label = () => `${othersList.hidden ? "Show" : "Hide"} ${others.length} ${result.sufficient ? "related" : "closest"} ${others.length === 1 ? "file" : "files"}`;
  toggle.textContent = label();
  toggle.addEventListener("click", () => {
    othersList.hidden = !othersList.hidden;
    toggle.textContent = label();
  });

  area.replaceChildren(
    el(
      "div",
      { class: `answer${result.sufficient ? "" : " answer-weak"}` },
      !result.sufficient && el("span", { class: "answer-note" }, "Not enough evidence"),
      !result.sufficient && el("p", { class: "muted" }, "Memora couldn't find enough in your files to answer this confidently. Here is what it found:"),
      withCitations(direct, refs, "answer-direct"),
      explanation && withCitations(explanation, refs, "answer-explain"),
      el("span", { class: "answer-where" }, "Answered on this computer with a local model" + (result.sufficient ? ". Check the originals for anything important." : "")),
    ),
    cited.length > 0 && el("h3", { class: "section-title" }, cited.length === 1 ? "Evidence" : `Evidence · ${cited.length} files`),
    el("div", { class: "sources" }, ...cited.map(sourceCard)),
    others.length > 0 && el("h3", { class: "section-title" }, result.sufficient ? "Related memories" : "Closest matches"),
    others.length > 0 && toggle,
    others.length > 0 && othersList,
  );
}

async function ask(question) {
  question = question.trim();
  if (!question) return;
  $("question").value = question;
  $("ask-button").disabled = true;
  $("ask-button").textContent = "Thinking…";
  show($("examples"), false);
  $("answer-area").replaceChildren(el("div", { class: "skeleton" }, "Searching your files and reading the evidence locally…"));
  try {
    renderAnswer(await api("/ask", { method: "POST", body: JSON.stringify({ question }) }));
  } catch (e) {
    $("answer-area").replaceChildren(el("div", { class: "banner banner-bad" }, e.message));
  } finally {
    $("ask-button").disabled = false;
    $("ask-button").textContent = "Ask";
  }
}

$("ask-form").addEventListener("submit", (e) => {
  e.preventDefault();
  ask($("question").value);
});
document.querySelectorAll("#examples .chip").forEach((chip) => chip.addEventListener("click", () => ask(chip.textContent)));

// ---------- Library ----------

let selectedFolder = null;

function libraryError(message) {
  $("library-error").textContent = message || "";
  show($("library-error"), !!message);
}

async function refreshLibrary() {
  try {
    const [folders, files, suggested, pairing] = await Promise.all([
      api("/folders"),
      api(selectedFolder ? `/files?folder_id=${selectedFolder}` : "/files"),
      api("/folders/suggested"),
      api("/pairing"),
    ]);
    renderFolders(folders);
    renderFiles(files);
    renderSuggested(suggested);
    renderPairing(pairing);
  } catch (e) {
    libraryError(e.message);
  }
}

function renderPairing(p) {
  show($("phone-off"), !p.lan);
  show($("phone-on"), p.lan);
  $("phone-addresses").textContent = p.addresses.length ? p.addresses.join("  or  ") : "No network address found";
  $("phone-port").textContent = $("phone-port-2").textContent = $("ts-port").textContent = String(p.port);
  $("phone-code").textContent = p.code;
  const ts = $("ts-state");
  ts.textContent = p.tailscale ? "on" : "off";
  ts.className = p.tailscale ? "tag tag-ok" : "tag";
  show($("ts-off"), !p.tailscale);
  show($("ts-on"), !!p.tailscale);
  $("ts-addresses").textContent = p.tailscale_addresses?.length
    ? p.tailscale_addresses.join("  or  ")
    : "Not found. Is Tailscale running on this computer? Copy the address from the Tailscale app.";
}

function renderSuggested(suggested) {
  const todo = suggested.filter((s) => !s.approved);
  $("suggested").replaceChildren(
    ...todo.map((s) => el("button", { class: "chip", title: s.path, onclick: () => addFolder(s.path) }, `+ ${s.name}`)),
  );
}

async function addFolder(path) {
  libraryError(null);
  try {
    await api("/folders", { method: "POST", body: JSON.stringify({ path }) });
    await refreshLibrary();
    pollHealth();
  } catch (err) {
    libraryError(err.message);
  }
}

function renderFolders(folders) {
  const list = $("folders");
  if (!folders.length) {
    list.replaceChildren(el("li", { class: "muted" }, "No folders yet."));
    return;
  }
  list.replaceChildren(
    ...folders.map((f) =>
      el(
        "li",
        { class: selectedFolder === f.id ? "selected" : "" },
        el(
          "button",
          {
            class: "folder-path",
            title: "Show this folder's files",
            onclick: () => {
              selectedFolder = selectedFolder === f.id ? null : f.id;
              refreshLibrary();
            },
          },
          f.is_uploads ? `Phone uploads  (${f.path})` : f.path,
        ),
        el(
          "span",
          { class: "muted" },
          `${f.indexed_count ?? 0}/${f.file_count} indexed`,
          f.error_count ? el("span", { class: "error-text" }, ` · ${f.error_count} failed`) : null,
        ),
        el("button", { onclick: () => api(`/folders/${f.id}/reindex`, { method: "POST" }).then(pollHealth) }, "Rescan"),
        !f.is_uploads && el("button", { class: "danger", onclick: () => removeFolder(f) }, "Remove"),
      ),
    ),
  );
}

function renderFiles(files) {
  $("files-title").textContent = selectedFolder ? "Files in folder" : "Recent files";
  const body = $("files");
  if (!files.length) {
    body.replaceChildren(el("tr", {}, el("td", { colspan: 4, class: "muted" }, "No files indexed yet.")));
    return;
  }
  body.replaceChildren(
    ...files.map((f) =>
      el(
        "tr",
        {},
        el(
          "td",
          {},
          el("button", { class: "link", title: f.path, onclick: () => openDetail(f) }, f.name),
          f.summary && el("div", { class: "muted small clamp" }, f.summary),
        ),
        el("td", {}, f.kind),
        el("td", {}, formatSize(f.size)),
        el("td", {}, f.status === "error" ? el("span", { class: "error-text", title: f.error }, "failed") : f.status),
      ),
    ),
  );
}

async function removeFolder(folder) {
  const ok = confirm(
    `Stop indexing ${folder.path}?\n\nThis removes it from Memora's index only. Your files are not touched.`,
  );
  if (!ok) return;
  try {
    await api(`/folders/${folder.id}`, { method: "DELETE" });
    if (selectedFolder === folder.id) selectedFolder = null;
  } catch (e) {
    libraryError(e.message);
  }
  refreshLibrary();
  pollHealth();
}

$("folder-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const button = e.submitter;
  button.disabled = true;
  await addFolder($("folder-path").value);
  if ($("library-error").hidden) $("folder-path").value = "";
  button.disabled = false;
});

// ---------- Start ----------

pollHealth();
refreshFiles();
setInterval(pollHealth, 2500);
