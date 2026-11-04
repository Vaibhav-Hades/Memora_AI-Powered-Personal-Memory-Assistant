"""Repeatable demo check against the RUNNING Memora server and real Ollama (no mocks).

    python demo_check.py [--url http://127.0.0.1:8010] [--question "..."]

Checks Ollama and the configured model, asks the cross-source kettle question, and verifies that the answer is
cited to a warranty source and an invoice source and that its warranty period appears in the cited evidence.
Also asks a question the data cannot answer and expects an honest "not enough evidence". Exit code 0 = all passed.
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

QUESTION = "What is the warranty period of my Zenbrew kettle, and where is its invoice?"
UNANSWERABLE = "What is the serial number of my passport?"
PERIOD = re.compile(r"\b(\d+|one|two|three|five|ten)[\s-]*\(?\w*\)?[\s-]*(year|month)s?\b", re.I)

failures: list[str] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    print(f"  {'PASS' if ok else 'FAIL'}  {label}" + (f"  ({detail})" if detail else ""))
    if not ok:
        failures.append(label)


def get(url: str, timeout: float = 10) -> dict:
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read())


def ask(base: str, question: str) -> dict:
    req = urllib.request.Request(
        base + "/api/ask", data=json.dumps({"question": question}).encode(), headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://127.0.0.1:8010", help="running Memora server (laptop address)")
    ap.add_argument("--question", default=QUESTION)
    args = ap.parse_args()
    base = args.url.rstrip("/")

    print("1. Server and Ollama")
    try:
        health = get(base + "/api/health")
    except (urllib.error.URLError, OSError) as e:
        print(f"  FAIL  Memora not reachable at {base}: {e}")
        return 1
    model = health["model"]
    check(model.get("ok", False), "Ollama reachable and models usable", str(model.get("error") or model.get("missing_models") or ""))
    print(f"  chat model={model.get('chat_model')}  embedding model={model.get('embed_model')}")
    check(health["stats"]["indexed"] > 0, "library has indexed files", f"{health['stats']['indexed']} indexed")
    check(health["stats"]["errors"] == 0, "no files in error state")

    print(f"2. Question: {args.question}")
    res = ask(base, args.question)
    print(f"  ANSWER: {res['answer']}")
    for s in res["sources"]:
        print(f"  [{s['ref']}] {'CITED' if s['cited'] else '     '} {s['name']}  {s['path']}")
    cited = [s for s in res["sources"] if s["cited"]]
    check(res["sufficient"], "answer marked as supported by evidence")
    check(len({s["file_id"] for s in cited}) >= 2, "answer cites at least two different files", ", ".join(s["name"] for s in cited))
    check(any("warranty" in s["snippet"].lower() for s in cited), "a cited source talks about the warranty")
    check(any("invoice" in (s["name"] + s["snippet"]).lower() for s in cited), "a cited source is an invoice")
    periods = {m.group(0).lower().replace("-", " ") for m in PERIOD.finditer(res["answer"])}
    evidence = " ".join(s["snippet"] for s in cited).lower().replace("-", " ")
    check(bool(periods) and all(p in evidence or p.split()[0] in evidence for p in periods), "warranty period in the answer appears in the cited evidence", ", ".join(sorted(periods)))
    for s in cited:
        try:
            with urllib.request.urlopen(f"{base}/api/files/{s['file_id']}/content", timeout=20) as r:
                check(r.status == 200 and len(r.read()) > 0, f"original file opens: {s['name']}")
        except (urllib.error.URLError, OSError) as e:
            check(False, f"original file opens: {s['name']}", str(e))

    print(f"3. Insufficient evidence: {UNANSWERABLE}")
    res = ask(base, UNANSWERABLE)
    print(f"  ANSWER: {res['answer']}")
    check(not res["sufficient"], "reported as not enough evidence")
    check(not any(s["cited"] for s in res["sources"]), "no source presented as evidence")

    print("\nRESULT:", "FAILED: " + "; ".join(failures) if failures else "ALL CHECKS PASSED")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
