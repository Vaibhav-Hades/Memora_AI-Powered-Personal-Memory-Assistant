// Photos: import, gallery and the photo detail view. Plain JavaScript, loaded after app.js (it shares el, api, $ and show).
// Everything that comes from a photo, a model or the user is inserted with textContent, never as HTML.

(() => {
  const UPLOADABLE = new Set([".jpg", ".jpeg", ".png", ".webp"]);
  const MAX_MB = 20; // matches the backend default; the server has the final say
  const PAGE = 48;

  const ext = (name) => (name.match(/\.[^.]+$/)?.[0] || "").toLowerCase();
  const STAGES = { pending: "Waiting", metadata: "Reading details", ocr: "Reading text", vision: "Describing", indexing: "Indexing", ready: "Ready", failed: "Failed" };
  const stageDone = (s) => s === "ready" || s === "failed";

  // ---------- Status of the AI pieces ----------

  async function loadVisionStatus() {
    let v;
    try {
      v = await api("/vision");
    } catch (e) {
      return null;
    }
    const banner = $("photo-status");
    const problems = [];
    if (!v.ok) problems.push(`Photos are imported and searchable by file name, but not described yet: ${v.error}`);
    if (!v.ocr.available) problems.push("Text inside photos can't be read: text recognition isn't available on this computer.");
    banner.textContent = problems.join(" ");
    show(banner, problems.length > 0);
    return v;
  }

  // ---------- Import ----------

  const imp = { items: [], running: false, cancelled: false, xhr: null, nextId: 1 };

  const input = $("photo-input");
  $("pick-files").addEventListener("click", () => input.click());
  input.addEventListener("change", () => {
    addToQueue([...input.files]);
    input.value = "";
  });
  // Choosing a whole folder is a convenience: only offered where the browser supports it.
  if ("webkitdirectory" in document.createElement("input")) {
    show($("pick-folder"), true);
    $("pick-folder").addEventListener("click", () => $("photo-folder-input").click());
    $("photo-folder-input").addEventListener("change", (e) => {
      addToQueue([...e.target.files]);
      e.target.value = "";
    });
  }
  const drop = $("drop");
  ["dragenter", "dragover"].forEach((t) =>
    drop.addEventListener(t, (e) => {
      e.preventDefault();
      drop.classList.add("over");
    }),
  );
  ["dragleave", "drop"].forEach((t) => drop.addEventListener(t, () => drop.classList.remove("over")));
  drop.addEventListener("drop", (e) => {
    e.preventDefault();
    addToQueue([...(e.dataTransfer?.files || [])]);
  });
  drop.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      input.click();
    }
  });

  function addToQueue(files) {
    if (imp.running) return;
    const known = new Set(imp.items.map((i) => `${i.file.name}|${i.file.size}|${i.file.lastModified}`));
    for (const file of files) {
      const key = `${file.name}|${file.size}|${file.lastModified}`;
      if (known.has(key)) continue;
      known.add(key);
      const item = { id: imp.nextId++, file, state: "ready", note: "", progress: 0, fileId: null, stage: null };
      if (!UPLOADABLE.has(ext(file.name))) {
        item.state = "unsupported";
        item.note = [".heic", ".heif"].includes(ext(file.name))
          ? "HEIC/HEIF can't be read here. Convert it to JPEG, or import it with the Android app."
          : "Only JPEG, PNG and WebP photos can be imported.";
      } else if (file.size > MAX_MB * 1024 * 1024) {
        item.state = "unsupported";
        item.note = `Larger than ${MAX_MB} MB.`;
      }
      imp.items.push(item);
    }
    renderQueue();
  }

  const STATE_LABEL = {
    ready: "Ready", uploading: "Uploading", processing: "Processing", done: "Imported", duplicate: "Already in Memora",
    unsupported: "Skipped", failed: "Upload failed", cancelled: "Cancelled",
  };

  function renderQueue() {
    const items = imp.items;
    show($("import-panel"), items.length > 0);
    const count = (s) => items.filter((i) => i.state === s).length;
    const ready = count("ready") + count("failed") + count("cancelled");
    $("import-title").textContent = imp.running ? "Importing…" : ready ? `${ready} photo${ready === 1 ? "" : "s"} selected` : "Import finished";
    $("import-start").textContent = items.some((i) => i.state === "failed" || i.state === "cancelled") ? `Import ${ready} (retry)` : `Import ${ready}`;
    $("import-start").disabled = imp.running || ready === 0;
    $("import-cancel").textContent = imp.running ? "Cancel import" : "Remove selection";
    show($("import-clear"), !imp.running && ready === 0);
    show($("import-cancel"), imp.running || ready > 0);

    const parts = [];
    for (const [s, label] of [["done", "imported"], ["processing", "being analysed"], ["duplicate", "already in Memora"], ["unsupported", "skipped"], ["failed", "failed"], ["cancelled", "cancelled"]]) {
      if (count(s)) parts.push(`${count(s)} ${label}`);
    }
    $("import-summary").textContent = parts.join(" · ");

    const doneCount = items.filter((i) => !["ready", "uploading"].includes(i.state)).length;
    show($("import-bar-wrap"), imp.running);
    $("import-bar").style.width = `${items.length ? (100 * doneCount) / items.length : 0}%`;

    $("import-queue").replaceChildren(
      ...items.map((i) =>
        el(
          "li",
          { class: `queue-item q-${i.state}` },
          el("span", { class: "q-name" }, i.file.name),
          el("span", { class: "muted small" }, formatSize(i.file.size)),
          el(
            "span",
            { class: "q-state" },
            i.state === "uploading" ? `Uploading ${Math.round(i.progress * 100)}%` : i.state === "processing" ? (STAGES[i.stage] || "Waiting") + "…" : STATE_LABEL[i.state],
            i.note && el("span", { class: "note" }, i.note),
          ),
          i.state === "failed" && !imp.running && el("button", { class: "link", onclick: () => retryUpload(i) }, "Retry"),
          i.state === "processing" || i.state === "done" ? (i.stage === "failed" && el("button", { class: "link", onclick: () => retryAnalysis(i) }, "Retry analysis")) : null,
          !imp.running && i.state === "ready" && el("button", { class: "link", onclick: () => removeItem(i) }, "Remove"),
        ),
      ),
    );
  }

  function removeItem(item) {
    imp.items = imp.items.filter((i) => i !== item);
    renderQueue();
  }

  function uploadOne(item) {
    return new Promise((resolve) => {
      const xhr = new XMLHttpRequest();
      imp.xhr = xhr;
      xhr.open("POST", `/api/uploads?filename=${encodeURIComponent(item.file.name)}`);
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          item.progress = e.loaded / e.total;
          renderQueue();
        }
      };
      xhr.onload = () => {
        let body = null;
        try {
          body = JSON.parse(xhr.responseText);
        } catch (e) {
          /* not JSON */
        }
        if (xhr.status === 201) {
          item.fileId = body.id;
          item.state = body.duplicate ? "duplicate" : "processing";
          item.stage = body.duplicate ? null : "pending";
        } else {
          item.state = xhr.status === 415 ? "unsupported" : "failed";
          item.note = body?.detail || `Upload failed (${xhr.status}).`;
        }
        resolve();
      };
      xhr.onerror = () => {
        item.state = "failed";
        item.note = "Couldn't reach Memora.";
        resolve();
      };
      xhr.onabort = () => {
        item.state = "cancelled";
        resolve();
      };
      item.state = "uploading";
      item.progress = 0;
      renderQueue();
      xhr.send(item.file);
    });
  }

  async function runImport() {
    imp.running = true;
    imp.cancelled = false;
    renderQueue();
    for (const item of imp.items) {
      if (!["ready", "failed", "cancelled"].includes(item.state)) continue;
      if (imp.cancelled) {
        item.state = "cancelled";
        continue;
      }
      item.note = "";
      await uploadOne(item);
    }
    imp.running = false;
    renderQueue();
    refreshPhotos();
    watchProcessing();
  }

  async function retryUpload(item) {
    if (imp.running) return;
    imp.running = true;
    renderQueue();
    await uploadOne(item);
    imp.running = false;
    renderQueue();
    refreshPhotos();
    watchProcessing();
  }

  async function retryAnalysis(item) {
    try {
      await api(`/photos/${item.fileId}/reanalyze`, { method: "POST", body: JSON.stringify({ vision: true, ocr: true }) });
      item.state = "processing";
      item.stage = "pending";
      renderQueue();
      watchProcessing();
    } catch (e) {
      item.note = e.message;
      renderQueue();
    }
  }

  $("import-start").addEventListener("click", runImport);
  $("import-cancel").addEventListener("click", () => {
    if (imp.running) {
      imp.cancelled = true;
      imp.xhr?.abort();
    } else {
      imp.items = imp.items.filter((i) => !["ready", "failed", "cancelled"].includes(i.state));
      renderQueue();
    }
  });
  $("import-clear").addEventListener("click", () => {
    imp.items = [];
    renderQueue();
  });

  let watching = false;
  async function watchProcessing() {
    if (watching) return;
    watching = true;
    try {
      for (let round = 0; round < 600; round++) {
        const open = imp.items.filter((i) => i.state === "processing" && i.fileId != null);
        if (!open.length) break;
        let rows = [];
        try {
          rows = await api(`/photos/status?ids=${open.map((i) => i.fileId).join(",")}`);
        } catch (e) {
          break;
        }
        for (const r of rows) {
          const item = open.find((i) => i.fileId === r.file_id);
          if (!item) continue;
          item.stage = r.stage;
          if (stageDone(r.stage)) {
            item.state = "done";
            item.note = r.stage === "failed" ? r.error || "Processing failed." : r.vision_status !== "ok" && r.vision_status ? `Imported, but not described (${r.vision_status}).` : "";
          }
        }
        renderQueue();
        refreshPhotos();
        await new Promise((r) => setTimeout(r, 1500));
      }
    } finally {
      watching = false;
    }
  }

  // ---------- Gallery ----------

  const g = { q: "", sort: "captured", desc: true, fav: false, undated: false, attn: false, from: "", to: "", items: [], total: 0, loading: false, token: 0 };

  function params(offset, limit) {
    const p = new URLSearchParams({ sort: g.sort, order: g.desc ? "desc" : "asc", limit, offset });
    if (g.q.trim()) p.set("q", g.q.trim());
    if (g.fav) p.set("favorites", "true");
    if (g.undated) p.set("undated", "true");
    if (g.attn) p.set("stage", "failed");
    if (g.from) p.set("date_from", g.from);
    if (g.to) p.set("date_to", g.to);
    return p;
  }

  async function refreshPhotos(append = false) {
    if (g.loading && append) return;
    const token = ++g.token;
    g.loading = true;
    // Refreshing in place re-reads everything already loaded, so the grid doesn't jump back to the first page.
    const offset = append ? g.items.length : 0;
    const limit = append ? PAGE : Math.min(200, Math.max(PAGE, g.items.length));
    try {
      const data = await api(`/photos?${params(offset, limit)}`);
      if (token !== g.token) return;
      g.items = append ? [...g.items, ...data.items] : data.items;
      g.total = data.total;
      renderGrid();
    } catch (e) {
      $("photo-count").textContent = e.message;
    } finally {
      g.loading = false;
    }
  }
  window.refreshPhotos = refreshPhotos;

  function tileDate(p) {
    if (p.captured_date) return fmtCalendarDate(p.captured_date);
    if (p.event_date) return `${fmtCalendarDate(p.event_date)} (event)`;
    return "Date not recorded";
  }

  function badge(p) {
    if (p.file_status === "error" || p.stage === "failed") return el("span", { class: "tag tag-bad" }, "Failed");
    if (!stageDone(p.stage) && p.stage !== "ready") return el("span", { class: "tag tag-warn" }, STAGES[p.stage] || "Processing");
    if (p.vision_status === "unavailable") return el("span", { class: "tag tag-warn", title: "The AI model wasn't available" }, "Not described");
    if (p.vision_status === "failed") return el("span", { class: "tag tag-bad" }, "Description failed");
    if (p.vision_status === "legacy") return el("span", { class: "tag", title: "Described by an older version; open it to analyse again" }, "Older description");
    return null;
  }

  function tile(p) {
    const img = el("img", { src: `${p.thumbnail}?size=320`, alt: "", loading: "lazy", decoding: "async" });
    return el(
      "button",
      { class: "tile", onclick: () => openPhoto(p.file_id), "aria-label": `${p.caption || p.name}. ${tileDate(p)}` },
      el("span", { class: "tile-img" }, img, p.favorite && el("span", { class: "fav", "aria-hidden": "true" }, "★")),
      el("span", { class: "tile-cap" }, p.caption || p.name),
      el("span", { class: "tile-meta" }, tileDate(p), badge(p)),
    );
  }

  function renderGrid() {
    const grid = $("photo-grid");
    const filtered = g.q || g.fav || g.undated || g.attn || g.from || g.to;
    $("photo-count").textContent = g.total ? `${g.total} photo${g.total === 1 ? "" : "s"}${filtered ? " match" : ""}` : "";
    if (!g.items.length) {
      grid.replaceChildren(
        el("div", { class: "empty" },
          el("h2", {}, filtered ? "No photos match" : "No photos yet"),
          el("p", { class: "muted" }, filtered ? "Try different words or clear a filter." : "Import photos above, or add a folder in Library."),
          !filtered && el("button", { class: "primary", onclick: () => $("pick-files").click() }, "Choose photos")),
      );
    } else {
      grid.replaceChildren(...g.items.map(tile));
    }
    show($("photo-more"), g.items.length < g.total);
    schedulePoll();
  }

  let pollTimer = null;
  function schedulePoll() {
    clearTimeout(pollTimer);
    if ($("tab-photos").hidden) return;
    if (g.items.some((p) => !stageDone(p.stage))) pollTimer = setTimeout(() => refreshPhotos(), 3000);
  }

  $("photo-more").addEventListener("click", () => refreshPhotos(true));
  let qTimer = null;
  $("photo-q").addEventListener("input", (e) => {
    clearTimeout(qTimer);
    qTimer = setTimeout(() => {
      g.q = e.target.value;
      refreshPhotos();
    }, 250);
  });
  $("photo-sort").addEventListener("change", (e) => {
    g.sort = e.target.value;
    updateOrderLabel();
    refreshPhotos();
  });
  function updateOrderLabel() {
    const labels = { captured: ["Newest first", "Oldest first"], imported: ["Newest first", "Oldest first"], name: ["Z to A", "A to Z"] }[g.sort];
    $("photo-order").textContent = g.desc ? labels[0] : labels[1];
  }
  $("photo-order").addEventListener("click", () => {
    g.desc = !g.desc;
    updateOrderLabel();
    refreshPhotos();
  });
  for (const [id, key] of [["photo-fav", "fav"], ["photo-undated", "undated"], ["photo-attn", "attn"]]) {
    $(id).addEventListener("change", (e) => {
      g[key] = e.target.checked;
      refreshPhotos();
    });
  }
  for (const [id, key] of [["photo-from", "from"], ["photo-to", "to"]]) {
    $(id).addEventListener("change", (e) => {
      g[key] = e.target.value;
      refreshPhotos();
    });
  }
  window.onPhotosTab = () => {
    loadVisionStatus();
    refreshPhotos();
  };

  // ---------- Photo detail ----------

  const squash = (t) => t.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const fmtWhen = (epoch) => (epoch ? fmtDateTime(epoch) : null);
  const NA = (note) => ({ na: true, note });

  function row(label, value, note) {
    const isNA = value == null || value === "";
    return [
      el("dt", {}, label),
      el("dd", {}, isNA ? el("span", { class: "na" }, "Not available") : value, note && el("span", { class: "note" }, note)),
    ];
  }

  function field(label, control, hint) {
    return el("label", { class: "field" }, el("span", {}, label), control, hint && el("span", { class: "note" }, hint));
  }

  async function openPhoto(fileId) {
    let d;
    try {
      d = await api(`/photos/${fileId}`);
    } catch (e) {
      return false; // not a photo record: the generic detail view handles it
    }
    show($("detail"), true);
    renderPhoto(d);
    $("detail-body").querySelector("button")?.focus();
    return true;
  }
  window.openPhoto = openPhoto;

  function renderPhoto(d, notice) {
    const body = $("detail-body");
    const cap = d.dates.captured;
    const msg = el("span", { class: "muted small", role: "status" }, notice || "");
    const reload = async () => renderPhoto(await api(`/photos/${d.file_id}`));
    const act = (fn) => async () => {
      try {
        await fn();
      } catch (e) {
        msg.textContent = e.message;
        msg.className = "error-text small";
      }
    };

    const favBtn = el("button", {
      class: "fav-btn", "aria-pressed": String(d.user.favorite), title: "Favorite",
      onclick: act(async () => {
        await api(`/photos/${d.file_id}`, { method: "PATCH", body: JSON.stringify({ favorite: !d.user.favorite }) });
        await reload();
        refreshPhotos();
      }),
    }, d.user.favorite ? "★ Favorite" : "☆ Favorite");

    // Edit form: the user's own words, kept apart from metadata and from AI output.
    const f = {
      caption: el("input", { value: d.user.caption || "", maxlength: 300, placeholder: d.ai.caption || "Add a caption" }),
      notes: el("textarea", { rows: 4, maxlength: 5000, placeholder: "Anything you want to remember about this photo" }),
      tags: el("input", { value: d.user.tags.join(", "), placeholder: "family, trip, receipts" }),
      title: el("input", { value: d.user.event_title || "", maxlength: 120, placeholder: "e.g. Ravi's wedding" }),
      date: el("input", { type: "date", value: d.user.event_date || "" }),
      place: el("input", { value: d.user.location_label || "", maxlength: 120, placeholder: "e.g. Mysuru" }),
      people: el("input", { value: d.user.people.join(", "), placeholder: "Names you choose to add" }),
    };
    f.notes.value = d.user.notes || "";
    const split = (v) => v.split(",").map((x) => x.trim()).filter(Boolean);
    const save = el("button", { class: "primary", type: "submit" }, "Save");
    const form = el("form", {
      class: "edit",
      onsubmit: async (e) => {
        e.preventDefault();
        save.disabled = true;
        try {
          await api(`/photos/${d.file_id}`, {
            method: "PATCH",
            body: JSON.stringify({
              user_caption: f.caption.value, user_notes: f.notes.value, user_tags: split(f.tags.value), event_title: f.title.value,
              event_date: f.date.value, location_label: f.place.value, people: split(f.people.value),
            }),
          });
          renderPhoto(await api(`/photos/${d.file_id}`), "Saved. Your notes are searchable in a moment.");
          refreshPhotos();
        } catch (err) {
          msg.textContent = err.message;
          msg.className = "error-text small";
        } finally {
          save.disabled = false;
        }
      },
    },
      field("Caption", f.caption), field("Notes", f.notes), field("Tags", f.tags, "Separate with commas"),
      field("Event", f.title), field("Event date", f.date, "Only if you know it. This is separate from when the photo was taken."),
      field("Place", f.place, "A label you type. Memora never looks places up."),
      field("People", f.people, "Memora never recognises faces or suggests names."),
      el("div", { class: "actions" }, save, msg));

    const objects = d.ai.objects.length ? el("ul", { class: "chips" }, ...d.ai.objects.map((o) => el("li", {}, o))) : null;
    const aiBlock = (() => {
      if (d.ai.status === "ok") {
        return [
          d.ai.description && el("p", {}, d.ai.description),
          d.ai.uncertainty && el("p", { class: "muted" }, `Unclear: ${d.ai.uncertainty}`),
          d.ai.context && el("p", { class: "muted" }, d.ai.context),
          el("span", { class: "note" }, `Written by ${d.ai.model || "a local model"}${d.dates.analyzed_at ? ` on ${fmtDateTime(d.dates.analyzed_at)}` : ""}. It can be wrong, so check it against the photo.${d.ai.outdated ? " A newer analysis is available." : ""}`),
        ];
      }
      const why = {
        pending: "Not described yet.", legacy: "Described by an older version of Memora. Run the description again for the full details.",
        unavailable: `Not described: ${d.ai.error || "the AI model isn't available."}`, failed: `Description failed: ${d.ai.error || "unknown error"}`,
      }[d.ai.status] || "Not described yet.";
      return [el("p", { class: "muted" }, why)];
    })();

    const loc = d.location;
    const where = loc
      ? `${Math.abs(loc.lat).toFixed(4)}° ${loc.lat >= 0 ? "N" : "S"}, ${Math.abs(loc.lon).toFixed(4)}° ${loc.lon >= 0 ? "E" : "W"}${loc.altitude != null ? `, ${loc.altitude} m` : ""}`
      : null;

    const dates = [
      ...row("Captured", cap ? fmtCalendarDate(cap.date) : null, cap ? "From the photo's own metadata" : "This photo doesn't record when it was taken"),
      ...row("Capture time", cap?.time || null),
      ...row("Time zone", cap?.offset ? `UTC${cap.offset}` : null, cap && !cap.timezone_known ? "Not recorded; shown exactly as the camera wrote it" : null),
      ...row("Event date", d.dates.event_date ? fmtCalendarDate(d.dates.event_date) : null, "Set by you"),
      ...row("File modified", fmtWhen(d.dates.file_modified), "From the computer's file record. Not when the photo was taken."),
      ...row("Added to Memora", fmtWhen(d.dates.imported_at)),
      ...row("Analysed", fmtWhen(d.dates.analyzed_at)),
    ];
    const info = [
      ...row("File name", d.name), ...row("Format", d.image.format), ...row("File size", formatSize(d.size)),
      ...row("Dimensions", d.image.width ? `${d.image.width} × ${d.image.height} px` : null),
      ...row("Camera", [d.image.camera_make, d.image.camera_model].filter(Boolean).join(" ") || null),
      ...row("Checksum (SHA-256)", d.sha256),
      ...row("Status", d.stage === "failed" ? `Failed: ${d.error || "unknown error"}` : `${STAGES[d.stage] || d.stage}`),
    ];

    const ocrState = { done: null, empty: "No text was found in this photo.", failed: `Text recognition failed: ${d.ocr.error || "unknown error"}`, unavailable: "Text recognition isn't available on this computer.", pending: "Text hasn't been read yet." }[d.ocr.status];
    const related = el("ul", { class: "rows" });
    api(`/photos/${d.file_id}/related`).then((items) => {
      related.replaceChildren(...(items.length ? items : []).map((r) =>
        el("li", {}, el("button", { class: "row", onclick: () => (r.kind === "image" ? openPhoto(r.file_id) : openDetail({ id: r.file_id, name: r.name, kind: r.kind, ext: "" })) },
          el("span", { class: "thumb", style: "width:48px;height:48px" }, r.kind === "image" ? el("img", { src: `/api/files/${r.file_id}/thumbnail?size=96`, alt: "", loading: "lazy" }) : el("span", { class: "ext" }, "DOC")),
          el("span", { class: "row-body" }, el("span", { class: "row-title" }, r.caption || r.name), el("span", { class: "row-meta" }, r.captured_date ? fmtCalendarDate(r.captured_date) : "Date not recorded"))))));
      if (!items.length) related.replaceChildren(el("li", { class: "muted small" }, "Nothing related found."));
    }).catch(() => related.replaceChildren(el("li", { class: "muted small" }, "Related memories need the AI model.")));

    body.replaceChildren(
      el("div", { class: "hero" }, el("a", { href: d.urls.content, target: "_blank", rel: "noreferrer" }, el("img", { src: d.urls.content, alt: d.caption }))),
      el("h2", { id: "detail-title" }, d.caption),
      el("div", { class: "actions" }, favBtn,
        el("a", { href: d.urls.content, target: "_blank", rel: "noreferrer" }, "Open original"),
        el("a", { href: d.urls.download }, "Download"),
        el("button", { class: "link", onclick: act(() => api(`/files/${d.file_id}/reveal`, { method: "POST" })) }, "Show in folder")),
      el("section", {}, el("h3", {}, "What's in the photo"), ...aiBlock, objects),
      // The model's own reading of the text is shown only when it adds something to what text recognition found.
      d.ai.visible_text && squash(d.ai.visible_text) !== squash(d.ocr.text || "") &&
        el("section", {}, el("h3", {}, "Text the AI read"), el("pre", { class: "ocr" }, d.ai.visible_text)),
      el("section", {}, el("h3", {}, "Text found by text recognition"),
        d.ocr.text ? el("pre", { class: "ocr" }, d.ocr.text) : el("p", { class: "muted" }, ocrState)),
      el("section", {}, el("h3", {}, "Date & time"), el("dl", { class: "meta" }, ...dates),
        el("span", { class: "note" }, "Memora shows only dates stored in the file or typed by you. It never guesses when something happened.")),
      el("section", {}, el("h3", {}, "Location"),
        el("dl", { class: "meta" }, ...row("From the photo", where, where ? "GPS stored in the photo. Used only to show it here; no map or lookup service is contacted." : "No GPS stored in this photo"),
          ...row("Your label", d.location_label))),
      el("section", {}, el("h3", {}, "Your notes"), form),
      el("section", {}, el("h3", {}, "Related memories"), related),
      el("details", {}, el("summary", {}, "File and processing details"), el("dl", { class: "meta" }, ...info),
        d.error && el("p", { class: "error-text" }, d.error),
        el("div", { class: "actions", style: "margin-top:12px" },
          el("button", { onclick: act(async () => { await api(`/photos/${d.file_id}/reanalyze`, { method: "POST", body: JSON.stringify({ vision: true, ocr: false }) }); msg.textContent = "Describing again. Your notes are kept."; refreshPhotos(); }) }, "Describe again"),
          el("button", { onclick: act(async () => { await api(`/photos/${d.file_id}/reanalyze`, { method: "POST", body: JSON.stringify({ vision: false, ocr: true }) }); msg.textContent = "Reading text again."; refreshPhotos(); }) }, "Read text again"))),
    );
  }

  // ---------- Settings: photo analysis ----------

  window.onSettingsTab = async () => {
    const v = await loadVisionStatus();
    if (!v) return;
    $("vision-state").textContent = v.ok ? "ready" : "not available";
    $("vision-state").className = v.ok ? "tag tag-ok" : "tag tag-warn";
    $("vision-detail").textContent = v.ok
      ? `Model: ${v.model}. Text recognition: ${v.ocr.available ? "Windows built-in" : "not available"}.`
      : v.error;
  };
  for (const [id, only] of [["vision-run", "unanalyzed"], ["vision-retry", "failed"]]) {
    $(id).addEventListener("click", async () => {
      try {
        const r = await api(`/photos/reanalyze?only=${only}`, { method: "POST" });
        $("vision-msg").textContent = r.queued ? `${r.queued} photo${r.queued === 1 ? "" : "s"} queued. This can take a while; they're processed one at a time.` : "Nothing to do.";
      } catch (e) {
        $("vision-msg").textContent = e.message;
      }
    });
  }
})();
