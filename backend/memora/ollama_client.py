"""Thin client for the local Ollama server. Nothing here talks to any other host."""

import base64
import io
import json
import urllib.error
import urllib.request
from typing import Any

from PIL import Image, ImageOps

from .config import Settings

IMAGE_PROMPT = """You are indexing a personal photo or scanned document so it can be found later by search.
Describe this image factually. Include:
1. What it is (e.g. receipt, invoice, warranty card, ID card, screenshot, product photo, whiteboard, landscape).
2. A verbatim transcription of ALL readable text, preserving names, numbers, dates, amounts, model/serial numbers, and terms such as warranty periods.
3. Key objects, brands, people count, and setting.
Do not guess text you cannot read; write [unreadable] instead. Output plain text only."""

MAX_IMAGE_SIDE = 1600


class OllamaError(RuntimeError):
    pass


def prepare_image(data: bytes, max_side: int = MAX_IMAGE_SIDE) -> bytes:
    """Downscale large photos and normalise to JPEG so the vision model gets a manageable input.
    Only this copy is shrunk; the stored original is never touched. EXIF rotation is applied so text reads upright."""
    with Image.open(io.BytesIO(data)) as img:
        img.seek(0)
        img = ImageOps.exif_transpose(img).convert("RGB")
        img.thumbnail((max_side, max_side))
        out = io.BytesIO()
        img.save(out, format="JPEG", quality=88)
        return out.getvalue()


class OllamaClient:
    def __init__(self, settings: Settings):
        self.settings = settings
        # Ollama can list models yet fail to run them; remember the latest failure so health shows it.
        self.last_error: str | None = None

    def _request(self, path: str, payload: dict[str, Any] | None = None, timeout: float = 600.0) -> dict[str, Any]:
        """GET (no payload) or POST JSON to the local Ollama server and decode the JSON reply."""
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(
            self.settings.ollama_url.rstrip("/") + path, data=data, headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            body = e.read().decode(errors="replace")[:300]
            if e.code >= 500:
                self.last_error = f"Ollama could not run the model: {body}"
                raise OllamaError(self.last_error) from e
            raise OllamaError(f"Ollama {path} failed ({e.code}): {body}") from e
        except (urllib.error.URLError, OSError) as e:
            self.last_error = f"Cannot reach Ollama at {self.settings.ollama_url}: {e}"
            raise OllamaError(self.last_error) from e

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        result = self._request(path, payload)
        self.last_error = None
        return result

    def health(self) -> dict[str, Any]:
        """Report whether Ollama is up and the configured models are installed."""
        try:
            tags = self._request("/api/tags", timeout=5.0)
        except (OllamaError, ValueError) as e:
            return {"ok": False, "error": str(e)}
        names = {m["name"] for m in tags.get("models", [])}

        def installed(model: str) -> bool:
            return model in names or f"{model}:latest" in names

        missing = [m for m in (self.settings.chat_model, self.settings.embed_model) if not installed(m)]
        return {
            "ok": not missing and self.last_error is None,
            "error": self.last_error,
            "chat_model": self.settings.chat_model,
            "embed_model": self.settings.embed_model,
            "missing_models": missing,
            "vision_model": self.settings.vision_model,
        }

    def probe(self) -> dict[str, Any]:
        """Run one tiny real chat and one real embedding and report the model names Ollama says answered.
        Proves the configured models actually run, not just that they are installed."""
        out: dict[str, Any] = {}
        try:
            r = self._post("/api/chat", {
                "model": self.settings.chat_model, "stream": False, "think": False, "keep_alive": self.settings.keep_alive,
                "messages": [{"role": "user", "content": "Reply with the single word: ready"}],
                "options": {"temperature": 0, "num_predict": 16, "num_ctx": 1024}})
            out["chat"] = {"ok": True, "configured": self.settings.chat_model, "answered_by": r.get("model"),
                           "total_ms": round(r.get("total_duration", 0) / 1e6)}
        except OllamaError as e:
            out["chat"] = {"ok": False, "configured": self.settings.chat_model, "error": str(e)[:300]}
        try:
            r = self._post("/api/embed", {"model": self.settings.embed_model, "input": ["task: search result | query: probe"],
                                          "keep_alive": self.settings.keep_alive})
            out["embed"] = {"ok": True, "configured": self.settings.embed_model, "answered_by": r.get("model"),
                            "dimensions": len(r["embeddings"][0])}
        except (OllamaError, KeyError, IndexError) as e:
            out["embed"] = {"ok": False, "configured": self.settings.embed_model, "error": str(e)[:300]}
        return out

    def embed(self, texts: list[str], kind: str = "document") -> list[list[float]]:
        """Embed texts. kind is 'document' for indexed content or 'query' for a search question."""
        if not texts:
            return []
        if "embeddinggemma" in self.settings.embed_model:
            # EmbeddingGemma was trained with task prefixes; using them improves retrieval.
            prefix = "task: search result | query: " if kind == "query" else "title: none | text: "
            texts = [prefix + t for t in texts]
        out: list[list[float]] = []
        for i in range(0, len(texts), 16):
            payload = {"model": self.settings.embed_model, "input": texts[i : i + 16], "keep_alive": self.settings.keep_alive}
            data = self._post("/api/embed", payload)
            out.extend(data["embeddings"])
        return out

    def vision_status(self) -> dict[str, Any]:
        """Is the configured vision model installed and does Ollama say it accepts images? Never loads the model."""
        model = self.settings.vision_model
        try:
            info = self._request("/api/show", {"model": model}, timeout=10.0)
        except OllamaError as e:
            text = str(e)
            if "(404)" in text or "not found" in text.lower():
                return {"ok": False, "model": model, "reason": "missing",
                        "error": f"The vision model '{model}' isn't installed. Install it with: ollama pull {model}"}
            return {"ok": False, "model": model, "reason": "unreachable", "error": text}
        caps = info.get("capabilities") or []
        if "vision" not in caps:
            return {"ok": False, "model": model, "reason": "no_vision",
                    "error": f"'{model}' can't read images (Ollama lists its abilities as {', '.join(caps) or 'none'}). "
                             f"Set MEMORA_VISION_MODEL to a vision model, for example qwen3-vl:8b."}
        return {"ok": True, "model": model, "reason": None, "error": None}

    def analyze_image(self, data: bytes) -> dict[str, Any]:
        """Structured description of one photo from the vision model. Raises OllamaError or InvalidAnalysis."""
        from .vision import ANALYSIS_SCHEMA, SYSTEM_PROMPT, USER_PROMPT, InvalidAnalysis, normalize_analysis

        status = self.vision_status()
        if not status["ok"]:
            raise OllamaError(status["error"])
        b64 = base64.b64encode(prepare_image(data, self.settings.vision_max_side)).decode()
        payload = {
            "model": self.settings.vision_model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": USER_PROMPT, "images": [b64]},
            ],
            "stream": False,
            "format": ANALYSIS_SCHEMA,
            "think": False,
            "keep_alive": self.settings.keep_alive,
            "options": {"temperature": 0, "num_ctx": self.settings.num_ctx},
        }
        last: Exception | None = None
        for _ in range(2):  # one retry if the model's JSON is unusable
            content = self._request("/api/chat", payload, timeout=float(self.settings.vision_timeout))["message"]["content"]
            self.last_error = None
            try:
                return {**normalize_analysis(json.loads(content)), "model": self.settings.vision_model}
            except (ValueError, InvalidAnalysis) as e:
                last = e
        raise OllamaError(f"The vision model's answer was unusable: {last}")

    def describe_image(self, data: bytes) -> str:
        b64 = base64.b64encode(prepare_image(data)).decode()
        resp = self._post(
            "/api/chat",
            {
                "model": self.settings.chat_model,
                "messages": [{"role": "user", "content": IMAGE_PROMPT, "images": [b64]}],
                "stream": False,
                "think": self.settings.think,
                "keep_alive": self.settings.keep_alive,
                "options": {"temperature": 0},
            },
        )
        return resp["message"]["content"].strip()

    def chat_json(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        resp = self._post(
            "/api/chat",
            {
                "model": self.settings.chat_model,
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                "stream": False,
                "format": schema,
                "think": self.settings.think,
                "keep_alive": self.settings.keep_alive,
                "options": {"temperature": 0, "num_ctx": self.settings.num_ctx},
            },
        )
        content = resp["message"]["content"]
        try:
            return json.loads(content)
        except json.JSONDecodeError as e:
            raise OllamaError(f"Model returned invalid JSON: {content[:300]}") from e
