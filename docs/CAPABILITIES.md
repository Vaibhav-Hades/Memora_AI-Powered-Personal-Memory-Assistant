# Memora capability matrix

Evidence levels: **Unit** = automated tests with fakes; **Live** = real Ollama (gemma4:e4b, embeddinggemma); **Phone** = seen on the physical phone (I2407); **Not verified** = code exists, never run for real.

| Capability | Status | Evidence |
|---|---|---|
| Configured chat/vision model really used (not just installed) | Live | `GET /api/health?probe=1` reports `answered_by` gemma4 / embeddinggemma (test_models_really_run) |
| Text Q&A over an invoice (Test A) | Live | answer "INR 3,499.00 ... HomeMart [n]", cited invoice_zenbrew.txt |
| Embeddings, semantic retrieval (Test B) | Live | "what should I bring on my holiday in the south" ranks trip_notes.txt first |
| Image understanding indexed (Test C) | Live | vision status ok, model gemma4:e4b, visible text read from the PNG, searchable by serial |
| Cross-source answer (Test D) | Live | invoice + warranty photo both cited |
| Insufficient evidence (Test K) | Live + Unit | "passport number" -> sufficient=false, no cited sources |
| Embedding model tracking, stale detection, re-embed | Unit | test_capabilities.py (incl. legacy DB migration) |
| Number-support check on answers | Unit | `unsupported_figures`; limits: numbers only, not claim-to-fact binding |
| Laptop paths hidden from phone responses | Unit | test_phone_responses_do_not_expose_laptop_paths |
| Manifest, hashed download, resume, integrity | Unit | existing + SyncRunnerTest |
| Background sync (WorkManager, opt-in) | Unit + Phone | "Sync now" on the phone against a samples-only laptop server: 4 new / 0 failed; repeat run: 0 new (idempotent). The periodic 6-hour schedule and the automatic-sync toggle were not exercised (only the one-time run). |
| Phone Only on-device answer, laptop off (Test F) | Phone | "What is the warranty period of my kettle" -> "2 years [1]", labelled "Answered on this phone" (Gemma 4 E2B via LiteRT-LM) |
| Phone OCR + local search (Test E, airplane mode) | Unit + earlier Phone run; airplane mode **not verified** (wireless adb drops in airplane mode) |
| On-device image captioning (Test G) | Not supported | on-device model is text-only; not simulated |
| Download a laptop file on the phone (Test H) | Phone (partial) | techmart_invoice.docx downloaded over LAN, app reported "Downloaded and verified"; laptop SHA-256 78a9f727...e6e6, 36911 bytes matches manifest. File stayed in the phone library with the laptop stopped, labelled "Stored, not searchable". The phone's copy was not hashed independently and the file was not opened in a viewer. |
| Automatic mode with laptop up/down (Test I) | Phone | Laptop up: "Answered by the laptop via LAN" with two cited files (warranty card + invoice, Test D on phone). Laptop stopped: the first question failed with a timeout error because the app still thought the laptop was connected; after the pill changed to "Laptop unavailable" the retry was "Answered on this phone". Fixed afterwards (AutoFallback): in Automatic mode a timeout or unreachable laptop now falls back to the phone on the first question; re-tested on the phone with the laptop stopped while the pill still said "Laptop via LAN" -> "Answered on this phone", no error. Laptop mode still shows the error (no silent fallback). The duplicate error notice is de-duplicated in code; not seen on screen. |
| Background sync retry/recovery (Test J) | Phone | Laptop stopped: run reported "The laptop could not be reached. Will retry when the laptop is reachable."; after the laptop returned, WorkManager's own retry ran again (12:54) and finished cleanly with no tap. A file added meanwhile arrived on the next run ("1 new", "Stored, not searchable"). Interrupted-download resume and hash failure are unit-tested only. |
| Release build keeps LiteRT-LM and WorkManager classes | Unit (rule present) + Phone (app launches, no crash) |

## Commands
- Backend: `cd backend && python -m pytest -q tests` (88; the live tests skip, not pass, if Ollama or a model is missing)
- Live only: `python -m pytest tests/test_live_ollama.py -v -s`
- Android: `cd android && gradlew.bat --no-daemon testDebugUnitTest assembleRelease` (110 tests, 1 skipped)

## Findings from the real runs
- PDF/DOCX indexing fails on this laptop's Python: `pypdf`, `pypdfium2` and `python-docx` (all in requirements.txt) are not installed, so 3 of 6 sample files showed "error". The message is now actionable ("Run: pip install -r requirements.txt") and `/api/health` lists `missing_packages`. The packages have NOT been installed (needs approval).
- Airplane mode (Tests E/F offline claims) cannot be tested over wireless debugging; Phone only mode was used instead, which stops the app from contacting the laptop but is not network isolation.
