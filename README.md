# Memora

> We remember taking the photo. We forget why we took it.

Memora is a personal AI memory assistant for the photos, screenshots, PDFs and documents you've saved. You ask a question in plain language, such as "Find the photo of my warranty card" or "Which product has a two-year warranty, and where is its invoice?". Memora finds the relevant files and answers using only what they contain, and every answer points to the original files as evidence. When the evidence isn't there, it says so instead of guessing.

There are two parts:

- **Windows AI server** (`backend/`, `frontend/`): this does all the AI work, using Gemma 4 through Ollama. It indexes the folders you approve and has a web page for use on the laptop itself.
- **Android app** (`android/`): a native Kotlin and Jetpack Compose app. It connects to the laptop over your Wi-Fi to ask questions, show answers and source files, add laptop folders, and send phone photos for indexing.

Everything stays on your devices. Neither part sends anything to cloud services; the only AI traffic is between the server and Ollama on the same laptop.

```
 Android phone ── Wi-Fi (same network, pairing code) ──► Windows laptop
   Ask / Add files / Settings                             FastAPI server ──► Ollama: Gemma 4 + embeddinggemma
   Photo Picker → JPEG upload                             SQLite: files, chunks, embeddings, keyword index
                                                          approved folders + phone-uploads folder
```

## How search works

```
approved folders ──► extract ──► chunk ──► embed ──► SQLite (files, chunks, embeddings, FTS5 keyword index)
                       │                     │
     photos / scanned PDF pages              embeddinggemma
     → Gemma 4 describes them and transcribes all readable text
question ──► vector search (numpy) + keyword search (FTS5) ──► rank fusion ──► top chunks ──► Gemma 4 ──► answer + [n] citations
```

- **Grounding:** the model sees only numbered excerpts and must cite them, and the server keeps only citations that point at real excerpts. An answer counts as "sufficient" only when the model says so *and* cites something. If nothing matches, the model isn't called at all.
- **Supported files:** JPG, PNG, WEBP, GIF, BMP and TIFF images; PDF (scanned pages are read by Gemma); DOCX; TXT, MD, CSV, JSON and LOG.
- **Server dependencies:** `fastapi`, `uvicorn`, `numpy`, `Pillow`, `pypdf`, `pypdfium2` and `python-docx`. No vector database and no Node.

---

## 1. Windows AI server

Prerequisites: Python 3.11+ and [Ollama](https://ollama.com).

```bash
ollama pull gemma4:e4b
```

```bash
ollama pull embeddinggemma
```

```bash
cd backend && python -m venv .venv && .venv/Scripts/python -m pip install -r requirements.txt
```

**Laptop only** (the default; nothing on the network can connect):

```bash
cd backend && .venv/Scripts/python -m memora
```

**Laptop + Android app** (also listens on your Wi-Fi):

```bash
cd backend && .venv/Scripts/python -m memora --lan
```

The console prints the address and pairing code for the phone, for example:

```
  Memora on this computer: http://127.0.0.1:8000
  Phone app address:       192.168.1.20  port 8000
  Pairing code:            K7QXM-3RT9P
```

Open http://127.0.0.1:8000 on the laptop. In **Library**:
- **Connect your phone** shows the same address and pairing code.
- **+ Pictures**, **+ Documents** and **+ Downloads** add those folders, wherever Windows keeps them (including OneDrive).
- You can also paste any other folder path.

Indexing runs in the background, and photos take roughly 10–60 seconds each while Gemma reads them. After that, only new or changed files are processed.

### Let the phone reach the laptop (Windows Firewall)

Windows blocks incoming connections by default, so you need to do this once.

1. Mark your home Wi-Fi as **Private**: *Settings → Network & internet → Wi-Fi → (your network) → Network profile type → Private network*. Don't make public Wi-Fi private.
2. Allow Memora's port on private networks. In **PowerShell run as Administrator**:

   ```bash
   New-NetFirewallRule -DisplayName "Memora (local network)" -Direction Inbound -Protocol TCP -LocalPort 8000 -Profile Private -Action Allow
   ```

   If Windows shows a firewall pop-up for Python when you start `--lan`, allow it for **private networks only**.

**Networks that block devices from reaching each other:** many campus, hotel, guest and office networks (and Wi-Fi with "AP/client isolation") stop devices on the same Wi-Fi from talking to each other. If the app can't connect, use a home network, or turn on your phone's hotspot and connect the laptop to it. Then use the address `python -m memora --lan` prints on that network.

### Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Phone says *Can't reach Memora* | Check that both devices are on the same Wi-Fi, the server was started with `--lan`, and the firewall rule above is in place. Laptops get a **new address on each network**, so copy it again from the console or **Library → Connect your phone**. |
| Phone says *pairing code is wrong* | Copy the code again (Library page or `backend/data/pairing-code.txt`). Upper/lower case and the dash don't matter. |
| The laptop page and the phone show different things | Another app is using the same port. For example, two uvicorn servers can share port 8000, one on `127.0.0.1` and one on `0.0.0.0`; the browser then reaches one and the phone the other. Start Memora on its own port with `python -m memora --lan --port 8010` and enter `8010` in the app. |
| *AI offline* or files fail with "out-of-memory" | Ollama couldn't load Gemma. `gemma4:e4b` needs about 5–6 GB of free RAM/VRAM (the older `gemma4:12b` about 9 GB) and `embeddinggemma` about 0.7 GB. Close memory-heavy apps (other servers, VMs such as WSL with `wsl --shutdown`, browsers), then click **Rescan** to retry the failed files. |
| The first answer takes about a minute | Ollama unloads models after 5 minutes idle, so the first question loads Gemma again. Later answers take about 10–20 s. |

### Security model

- **Laptop-only by default:** the server binds to `127.0.0.1` unless you pass `--lan`.
- **Pairing code:** in LAN mode, every request that isn't from the laptop itself must carry the pairing code (`X-Memora-Code` header). The code is random, stored in `backend/data/pairing-code.txt`, and compared in constant time. To change it, delete the file and restart.
- **Local network only (by default):** requests from public or VPN/CGNAT addresses are refused even with the right code, including Tailscale's `100.64.0.0/10`. Only if you start the server with `--tailscale` (see *Tailscale* below) are Tailscale addresses accepted too, and the pairing code is still required. Don't port-forward Memora to the internet.
- **What the phone can't do:** a phone gets the API only, never the web page. It can't read the pairing code, remove folders, or add folders other than the suggested Pictures, Documents and Downloads.
- **Laptop web page:** requests from the laptop must use a local hostname (`localhost` / `127.0.0.1`), which blocks DNS-rebinding attacks from web pages.
- **File access:** the server serves file contents only for indexed files under an approved folder. It skips hidden folders and symlinks that leave the folder, and refuses whole drives. Removing a folder deletes only the index.
- **Uploads:** photos must be real JPEG, PNG or WEBP images (checked by decoding them), under 20 MB, and not oversized images. They're saved under new safe names in `backend/data/phone-uploads/YYYY-MM/`, and duplicates are detected by hash.
- **Plain HTTP:** traffic is plain HTTP on your LAN, because there's no certificate for a `192.168.x.x` address. Use networks you trust.
- **Nothing is executed:** uploaded or indexed files are only read (decoded as images, or parsed as text, PDF or DOCX), never run or opened in another program. **Show on laptop** only selects the file in Explorer.
- **Logs:** the request access log is off, so questions (`?q=`) and photo names never reach the console. Only startup lines and file paths of failed files are logged, never file contents.

---

## 2. Android app

Kotlin, Jetpack Compose (Material 3), OkHttp, Kotlin coroutines, and the Android Photo Picker. It runs on Android 9+ (minSdk 28, targetSdk 36).

### Build

Prerequisites: JDK 17 or newer, and the Android SDK with **platform 37** and build-tools 36+. Android Studio is optional; open the `android/` folder in it and press Run.

From the command line, set `sdk.dir` in `android/local.properties` (or `ANDROID_HOME`), then:

```bash
cd android && ./gradlew assembleDebug
```

| APK | Path |
|---|---|
| **Ready to install** (copied from the last build) | `dist/memora-debug.apk` and `dist/memora-release.apk` |
| Debug | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release (minified, ~2.6 MB, signed with the debug key for testing) | `android/app/build/outputs/apk/release/app-release.apk` |

### Install on a phone

1. On the phone, enable **Developer options → USB debugging**, then connect it by USB. Or use **Wireless debugging** and `adb pair` on the same Wi-Fi.
2. Install the APK:

   ```bash
   adb install -r android/app/build/outputs/apk/release/app-release.apk
   ```

   Or copy the APK to the phone and open it; Android asks you to allow installs from that app.

### Use it

1. **Settings:** enter the laptop's Wi-Fi address (e.g. `192.168.1.20`, never `localhost`), the port (`8000`) and the pairing code, then tap **Test & save connection**. You'll see the laptop's name, the file count and whether the local AI is ready. Wrong addresses, wrong codes, firewall blocks, timeouts and "that's not Memora" each get their own message.
2. **Add files:**
   - **Choose photos** opens the system Photo Picker; pick up to 20.
   - **Send & index** prepares each photo on the phone: it decodes HEIC and others, fixes rotation, scales to at most 2048 px, and re-encodes as JPEG, which **removes EXIF/GPS metadata**.
   - It then uploads them, and the status of each photo updates from *Sending* to *AI is reading it* to *Indexed*.
   - Below that, **Laptop folders** shows indexing progress. You can add Pictures, Documents or Downloads, or rescan a folder.
3. **Ask:** type a question.
   - Matching files appear within a second or two, with previews.
   - Then the AI answer appears, with numbered citations and the cited **evidence** files.
   - Tap any file to see it full screen, with its location on the laptop and the text Memora read from it. **Show on laptop** opens that file's folder in Explorer.

### Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Talk to the Memora server on your laptop. Granted at install, with no prompt. |
| *(none for photos)* | The **Photo Picker** gives the app access only to the photos you pick, with no storage or media permission. Memora never scans the phone's gallery. |

The app doesn't index in the background and doesn't read the gallery automatically. It only sends photos you choose, while the app is open. Cleartext HTTP is allowed (`res/xml/network_security_config.xml`) because the laptop has no TLS certificate on the LAN. Backups are disabled, so the saved pairing code isn't copied to cloud backups.

---

## Capability matrix

What is implemented, tested and verified (live and on the phone) is in [docs/CAPABILITIES.md](docs/CAPABILITIES.md).

## Tests

**Server** (uses a fake AI client; no Ollama needed):

```bash
cd backend && .venv/Scripts/python -m pip install -r requirements-dev.txt && .venv/Scripts/python -m pytest -q
```

These 20 tests cover:
- indexing filters and incremental reindexing;
- duplicate handling (an identical or renamed file reuses the earlier AI result instead of re-reading it);
- the AI being unavailable (health reports it, questions and searches return a clear 503, failed files are retried on rescan);
- cited answers and the "insufficient evidence" path;
- folder validation and file-access restrictions;
- the host-header check;
- phone pairing (missing or wrong code, API-only access, no admin actions, suggested folders only);
- refusing non-LAN clients;
- upload validation (empty, non-image, GIF, too large, duplicates), and indexing and searching uploaded photos;
- thumbnails.

**Android networking** (JVM, no device needed):

```bash
cd android && ./gradlew testDebugUnitTest
```

`MemoraApiTest` runs the app's real OkHttp client against an in-process HTTP server. It covers address validation (including `localhost`), the pairing header, 401, 503 and 413 errors, a refused connection, a timeout, non-Memora responses, answer parsing, the raw JPEG upload, and missing thumbnails.

**Live phone workflow against a real server** (optional; needs the server running with `--lan` and Gemma available):

```bash
cd android && MEMORA_IT_HOST=192.168.1.20 MEMORA_IT_CODE=K7QXM-3RT9P ./gradlew testDebugUnitTest --tests "*LiveServerTest*"
```

This uses the laptop's Wi-Fi address, so the server applies its phone rules. It runs the cross-device example from the brief: the **invoice is on the laptop** (`samples/homeware_plus_invoice.docx`, so add the `samples` folder first) and the **warranty card is a phone photo**. It:
1. checks that a wrong code is refused;
2. connects and checks the AI is ready;
3. uploads a generated photo of the kettle's warranty card and waits for Gemma to read it;
4. checks that its thumbnail loads;
5. asks "What is the warranty period of my Zenbrew kettle, and where is its invoice?" and requires an answer that cites **both** the phone photo and the laptop invoice.

## Configuration

These environment variables for the server are all optional.

| Variable | Default | Meaning |
|---|---|---|
| `MEMORA_HOST` | `127.0.0.1` | Bind address (`--lan` sets `0.0.0.0`) |
| `MEMORA_PORT` | `8000` | Port (or `--port`) |
| `MEMORA_CHAT_MODEL` | `gemma4:e4b` | Vision + answering model |
| `MEMORA_EMBED_MODEL` | `embeddinggemma` | Embedding model (after changing it, click Rescan) |
| `MEMORA_OLLAMA_URL` | `http://127.0.0.1:11434` | Local Ollama server. If unset, Ollama's own `OLLAMA_BASE_URL` is used; `MEMORA_OLLAMA_URL` wins when both are set |
| `MEMORA_KEEP_ALIVE` | `30m` | How long Ollama keeps a model loaded after a request (avoids reloads mid-demo) |
| `MEMORA_DATA_DIR` | `backend/data` | Database, pairing code and phone uploads |
| `MEMORA_MAX_UPLOAD_MB` | `20` | Largest photo accepted from the phone |
| `MEMORA_TOP_K` | `8` | Evidence chunks given to the model per question |
| `MEMORA_THINK` | `0` | `1` turns on Gemma 4 "thinking" (about 10x slower) |
| `MEMORA_NUM_CTX` | `8192` | Context window, in tokens, used when answering |
| `MEMORA_MAX_FILE_MB` | `50` | Larger files are skipped when indexing folders |
| `MEMORA_MAX_PDF_IMAGE_PAGES` | `5` | Scanned pages read per PDF |
| `MEMORA_VISION_MODEL` | the chat model | Model that looks at photos. It must report the `vision` capability in Ollama, or photo analysis is skipped with a clear message (nothing is downloaded automatically) |
| `MEMORA_VISION_TIMEOUT` | `300` | Seconds allowed for one photo analysis |
| `MEMORA_VISION_MAX_SIDE` | `1280` | Longest side, in pixels, of the copy sent to the vision model (the original file is never changed) |
| `MEMORA_OCR` | `auto` | `auto` uses Windows' built-in OCR; `off` disables it |

## Photo memories

Photos are first-class memories: **import → keep the original → read metadata → describe → read visible text → index → search → answer with citations → show the original.**

**Importing.** Web: the **Photos** tab (choose files, a folder, or drag and drop; review, import, per-photo progress, cancel, retry). Android: the system photo picker (no storage permission) and the Library import. JPEG, PNG and WebP are accepted. HEIC is refused with a clear message (convert it first). Identical bytes are detected by SHA-256 and reported as "Already in Memora"; the original bytes are stored untouched, under a safe generated filename.

**What is recorded, and what is never guessed.** Capture date/time, GPS, camera and dimensions come only from the photo's EXIF. A photo without them shows "Date not recorded". The capture time, your own event date, the file's modified time, the time it was added to Memora and the time it was analysed are five different things and are shown separately. A time zone is shown only when the file records one. GPS is shown as coordinates; no map lookup is made.

**Understanding.** A real vision model describes each photo as validated JSON (caption, description, objects, visible text, uncertainty). It is told not to name people or invent places and dates. If the configured model has no vision capability, photos are still imported, dated and OCR'd, and the status says analysis is unavailable; they can be analysed later from **Settings → Photo analysis** or `POST /api/photos/reanalyze`. Visible text is read separately by OCR and kept separate from the AI caption. Photos indexed before this feature are not re-analysed automatically.

**Your words.** Caption, notes, tags, event, event date, place name, people and favourite are yours: they are searchable, shown with a "written by you" label, and survive re-analysis and re-indexing. On Android the same fields are kept on the phone and searched offline; a photo downloaded from the laptop also brings the laptop's description, stored apart from your notes.

**Everything stays local.** Photos, analysis and OCR never leave your machines; the only network use is Ollama on localhost.

**Android offline.** The phone OCRs photos on-device (ML Kit) and searches OCR text, your notes and the laptop's description with BM25. It does not describe what a photo shows unless the laptop did. Database migration v1→v2 only adds a table; existing data is kept.

**Tests.** Backend: `cd backend && .venv\Scripts\python -m pytest` (73 tests, including 35 for photos with a fake vision model and fake OCR). Android: `cd android && gradlew testDebugUnitTest` (93 tests incl. 13 for photo notes). There are no automated browser tests for the web UI; it was checked manually in a browser against a real server with real Ollama and Windows OCR.

## Demo check

With Memora running (and the `samples` folder added), this asks the real model the cross-source question and
verifies the answer is cited to a warranty source and an invoice source, then checks that an unanswerable question
is reported as "not enough evidence". It exits non-zero if anything fails:

```
backend\.venv\Scripts\python.exe backend\demo_check.py --url http://127.0.0.1:8010
```

`samples/zenbrew_warranty_card.jpg` stands in for the warranty photo a phone would send; the invoice is
`samples/homeware_plus_invoice.docx`.

## API

In LAN mode, requests from other devices must send `X-Memora-Code: <pairing code>`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/ping` | Connection test: app name, version, laptop name (no AI call) |
| GET | `/api/pairing` | LAN and Tailscale status, addresses, port and pairing code (**laptop only**) |
| GET | `/api/health` | AI status, index stats, indexing progress |
| GET/POST | `/api/folders` | List or approve folders (`{"path": "..."}`; from a phone, suggested folders only) |
| GET | `/api/folders/suggested` | Pictures, Documents and Downloads, and whether each is approved |
| DELETE | `/api/folders/{id}` | Forget a folder, index only (**laptop only**) |
| POST | `/api/folders/{id}/reindex` | Rescan a folder |
| GET | `/api/files` / `/api/files/{id}` | Indexed files / one file's status |
| GET | `/api/files/{id}/content` | The original file |
| GET | `/api/files/{id}/download` | The original file as an attachment, with its SHA-256 in `X-Memora-Sha256`; supports `Range` for resuming |
| GET | `/api/sync/manifest` | Files a phone can download: id, name, size, SHA-256, type (no laptop paths) |
| GET | `/api/files/{id}/meta` | Dates and place stored inside a photo (EXIF capture time as written, GPS), pixel size, plus the file's modified and indexed times. Missing values are `null` |
| GET | `/api/files/{id}/thumbnail?size=320` | JPEG preview of an image or a PDF's first page |
| POST | `/api/files/{id}/reveal` | Show the file in Explorer on the laptop |
| POST | `/api/uploads?filename=…` | Raw JPEG/PNG/WEBP body → saved, indexed in the background (before folder scans) |
| GET | `/api/search?q=` | Matching files, no AI (fast) |
| POST | `/api/ask` | `{"question": "..."}` → `{answer, sufficient, sources[]}` |

## Offline mode: Memora on the phone alone

The phone now has its own memory and its own AI, so it can answer questions with the laptop off and no internet.
**Status: implemented and unit-tested; not yet verified on a real phone** (no device was connected when this was
built; see "Verification status" below).

**How it fits together**

- *Phone library* (`android/.../local/`): original files in app-private storage (`files/memora/library`), metadata and
  text chunks in the phone's own SQLite database (`memora-local.db`; never the laptop's database), and a BM25
  keyword index built from those chunks. Nothing is stored on the SD card or shared storage.
- *On-device AI*: Google's **LiteRT-LM** runtime (`com.google.ai.edge.litertlm:litertlm-android:0.19.0`) runs a
  `.litertlm` model inside the app process (CPU backend, no server). Chosen because it is Google's maintained
  Android runtime, needs no NDK work, and has ungated Apache-2.0 models that fit phones. `.gguf` models are not supported.
- *Reading files offline*: text, CSV, JSON and DOCX are read directly; photos and PDFs go through ML Kit's bundled
  Latin text recognizer (model ships inside the app). PDFs are rendered page by page (first 20 pages) and recognised.
  Anything else is stored and marked "contents can't be searched"; failures are shown and can be retried.
- *Answers*: the question searches the phone's index, the best chunks (with file names) are given to the on-device
  model, and its JSON reply is validated: citation numbers must refer to retrieved sources, "not enough evidence"
  replies cite nothing, malformed output is never shown as supported. Citation checks prove a source exists, not that
  the sentence is true; the evidence excerpt is shown so you can check.
- *Not implemented*: on-device embeddings / vector search (the phone uses keyword BM25 only, so questions must share
  words with the files), image captioning by an on-device vision model (photos are OCR'd, not described), automatic
  background sync.

**Modes** (Settings, always your choice; the top bar shows the current one)

| Mode | Behaviour |
|---|---|
| Phone only | Only the phone's files and AI. The laptop is not contacted at all. |
| Laptop | Existing behaviour: the laptop's AI and files. If it is unreachable you are told; it never falls back silently. |
| Automatic | Laptop when it is reachable, otherwise the phone's own files and AI ("Laptop unavailable — using phone memory"). Reachability is the last known state; no polling. |

**Install the on-device model** (Settings, "AI on this phone"). Models are catalogued with their published size and
SHA-256, downloaded only after you confirm (once, over the internet), resumed if interrupted, verified, then kept
privately on the phone. Alternatively copy a `.litertlm` file to the phone and use "Import a .litertlm file".

| Model | Size | Notes |
|---|---|---|
| Qwen3 0.6B | 0.34 GB | Smallest; weakest answers |
| Qwen3 1.7B | 0.98 GB | Suggested starting point |
| Gemma 4 E2B | 2.6 GB | Best quality; needs a phone with plenty of RAM |

The app checks free storage first (needs the model size + 10%). Which of these actually runs well depends on the
phone's RAM and has **not been measured**; if loading fails the app says so instead of crashing, and you can pick a
smaller model. Only one model is loaded at a time and it is released when Android reports low memory.

**Get files onto the phone**

1. *Import* (Library tab, "Import files"): pick files with the system file picker (no storage permission needed).
2. *Download from the laptop* (Library tab, "Check the laptop for files", or "Download to phone" on any evidence
   card): the laptop's original bytes are streamed, resumed after interruptions, and checked against the laptop's
   SHA-256 and size before they are stored. Identical content is stored once. Then tap "Make searchable offline".
3. *Sync*: the Library tab lists laptop files as "Only on the laptop", "Available offline", "Changed on the laptop"
   or "No longer on the laptop (your copy is kept)". Sync is manual; nothing is ever deleted on the phone or laptop
   by syncing. "Save a copy..." exports a stored file with the system file dialog (which avoids name clashes).

**Test offline operation**

1. Install a model and get at least two files onto the phone, wait until the Library shows them "searchable".
2. Settings: choose **Phone only**.
3. Turn on airplane mode (and leave Wi-Fi off), or switch the laptop off.
4. Ask a question the files answer. The line under the answer reads "Answered on this phone".
5. Ask something not in those files: the app must say the phone's library doesn't contain it.

**What needs the laptop**: searching/answering about laptop-only files, downloading and syncing, "Show on laptop",
adding phone photos to the laptop's index. **What needs the internet**: only the one-time model download.

**Verification status**

| Item | Status |
|---|---|
| Library, import, de-duplication, indexing states, retry | Implemented and unit-tested (JVM, in-memory database) |
| BM25 search, answer validation, insufficient evidence, model failure handling | Implemented and unit-tested with a fake model |
| Downloads: verification, resume, duplicate-in-flight, errors | Implemented and unit-tested with a fake laptop |
| Laptop endpoints `/api/sync/manifest`, `/api/files/{id}/download` | Implemented, backend-tested and checked live on the laptop |
| SQLite database on Android, ML Kit OCR, LiteRT-LM inference, model download, Compose screens | Implemented, compiles, lints clean; **not run on a device** |
| Airplane-mode / laptop-off answers with a real model | **Not verified** |
| Release build with R8 and the native AI libraries | Builds; **runtime not verified** |

**Known limitations / security notes**: files are in app-private storage but not encrypted by the app; answers
from small models can be wrong; keyword-only search; the APK is large (about 97 MB release) because it bundles
native libraries for several CPU types; plain HTTP to the laptop is permitted at the OS level (LAN addresses can't be
listed in Android's network config), so the app itself refuses to connect to anything but private network addresses;
ML Kit's own network behaviour (usage statistics) has not been checked in airplane mode.

**Troubleshooting**: *"model couldn't start"* - the file may be damaged or too large for the phone's free memory:
close other apps or install a smaller model. *Files stuck "not searchable"* - tap "Retry failed / pending" (photos
and PDFs need the on-device text recognizer; scanned pages with poor lighting may read badly). *Download keeps
failing* - check the laptop is reachable (Settings), then retry: partial downloads resume. *Answers say the library
doesn't contain the information* - import or download the relevant files first and wait for "searchable".

## Tailscale: reaching the laptop from another network (optional)

Tailscale gives the phone and the laptop private addresses that work from any network. Memora doesn't embed Tailscale:
you install the **official Tailscale apps** on both devices (same account), and Memora simply uses the laptop's Tailscale
address. Memora's requests, pairing code, downloads and SHA-256 checks are exactly the same as on Wi-Fi.

What it does **not** do: it doesn't work when the laptop is off, asleep or not running Memora, it doesn't replace the
on-device AI, and **Phone only** mode never contacts the laptop whatever is configured.

**Setup**

1. Install Tailscale on the phone and on the laptop and sign in to the same account.
2. On the laptop, start Memora with the extra flag:

   ```bash
   python -m memora --lan --tailscale --port 8010
   ```

   `--tailscale` (or `MEMORA_ALLOW_TAILSCALE=1`) makes the server also accept the paired phone from Tailscale's address range
   `100.64.0.0/10` (and `fd7a:115c:a1e0::/48`). Wi-Fi access keeps working as before. The laptop's *Settings* page shows its
   Tailscale address once it is running.
3. In the Android app: **Settings → Tailscale**, enter the laptop's Tailscale IP (`100.x.x.x`) or MagicDNS name (`*.ts.net`), the
   port (`8010`), and tap **Test connection**. A successful test saves the address and turns the route on. The app says
   "via Tailscale" only after a real request has gone through that route.

**Windows Firewall:** allow the Memora port from the Tailscale range only (PowerShell as Administrator):

```bash
New-NetFirewallRule -DisplayName "Memora (Tailscale)" -Direction Inbound -Protocol TCP -LocalPort 8010 -RemoteAddress 100.64.0.0/10 -Action Allow
```

Don't turn the firewall off and don't open the port to other addresses. Use Tailscale's access controls so that only the
devices you intend (your phone) can reach the laptop; any device in the tailnet that can reach the port **and** knows the
pairing code could use Memora.

**How the app chooses a route**

| Mode | Behaviour |
|---|---|
| Phone only | No laptop request is ever made (no LAN or Tailscale probing, no sync). |
| Laptop | Tries Wi-Fi first, then Tailscale if enabled. If neither answers, it says the laptop is unavailable. It never answers from the phone silently. |
| Automatic | Same order; if neither route answers it uses the phone's files and AI, and the answer is labelled "Answered on this phone". |

Only "can't get there" failures (unreachable, timeout) move on to the next route. A wrong pairing code or a server that
isn't Memora is reported as it is. One check runs at a time, each bounded to a few seconds, and nothing retries by itself;
the app looks again when it starts, when you change mode, and once before answering a question after a failure.

**Security notes and limits:** the Tailscale tunnel is encrypted by WireGuard, so the plain-HTTP request inside it is not
readable on the internet, but it is still HTTP: Memora's own pairing code is the only application-level authentication, and
anyone who can reach the port and has the code gets the API. The pairing code is never put in a URL and not written to logs.

## Design

The Android app and the laptop page share one visual identity: warm ivory (dark: charcoal) surfaces, deep-ink text, a
single restrained indigo accent, a serif for titles and answers, and green / amber / red used only for meaning
(ready, uncertain, error). Android: `ui/Theme.kt` (tokens), `ui/DesignSystem.kt` (reusable components). Web: CSS variables
at the top of `frontend/styles.css`. Navigation is Home, Memories, Search, Library, Settings on both.

Dates are shown only as the file records them. A photo's **captured** date comes from its EXIF metadata and is displayed as
the camera wrote it (never converted between time zones); **modified** and **added to Memora** are separate, labelled
fields. Nothing is called a capture time unless the photo says so, event dates are not guessed, locations are shown only as
GPS coordinates from the file (no map or network lookup), and "details in the text" are lines like `Label: value` read
literally from the extracted text.

**Not implemented (so the UI doesn't pretend):** on-phone image captioning or object detection (the phone reads text with
OCR only), PDF page-by-page navigation, structured invoice/receipt field extraction beyond literal `Label: value` lines,
an in-app light/dark switch (follows the system), related memories on the laptop page, Android "Offline" and "Sync pending"
connection states.

## Known limitations

- **The laptop must be on and running Memora.** The phone app is only a remote control and holds no index of its own. When the server is unreachable, the app says so instead of showing stale results.
- **AI speed and accuracy:** on a 16 GB laptop, Gemma 4 takes roughly 10–60 s per photo to index and 10–20 s per answer, longer when it first loads. Its reading of photos and scans is good but not perfect (in testing it once read "WARRANTY" as "WARRENTY"), and answers are only as good as that transcription.
- **Memory:** Gemma 4 E4B needs about 5–6 GB free (12B needs about 9 GB). Other heavy apps or VMs can make Ollama fail with out-of-memory errors; affected files show as failed and are retried on Rescan.
- **Search scale:** vector search is an in-memory scan, which is fast up to tens of thousands of chunks (a typical personal collection). Very large archives would need a vector index.
- **File types:** no HEIC on the laptop (the phone converts HEIC when uploading), no spreadsheets (XLSX), no audio or video. Only the first 5 scanned pages of a PDF are read by default.
- **Photo search is keyword/semantic over text, not pixels:** a query matches the AI caption, OCR text, your notes and dates. Keyword matching uses OR terms, so "October 2025" can also surface a September 2025 photo. Vision output can be wrong; it is labelled and the original is one tap away.
- **Photo formats and places:** HEIC is not supported; no reverse geocoding, face recognition or people naming.
- **Uploaded photo names:** Android's Photo Picker hides original file names, so phone uploads are stored with names like `1000249508-aebbe230.jpg`.
- **Removing uploads:** there's no "delete from laptop" button in the app yet. Uploaded copies live in `backend/data/phone-uploads/`; delete them there and click **Rescan** on "Phone uploads".
- **Security:** traffic is plain HTTP on the local network, protected by the pairing code. There are no user accounts and no multi-user support.
- **Phone photos are sent one batch at a time while the app is open.** There's no background or automatic gallery sync, which is intentionally out of scope.

## Out of scope for this phase

- Remote access other than the optional Tailscale route below (no port forwarding, no public exposure)
- Cloud sync
- Automatic or background gallery scanning
- Charging-related features
