#!/usr/bin/env python3
"""LoRA data-gen step 2 — DeepSeek drafts the next form(s) per situation.

Frontier-draft mode (design.md §Data sources): DeepSeek-v4-pro drafts what a
driven model would do in each staged situation. Prompt = the exact KT3 shape
(context + cards bracket + the terse instruction) — the SAME framing the
signal-ceiling bar was measured under, so drafts land in the drivers' real
output distribution. Thinking disabled (the KT3 gotcha: DeepSeek's API
defaults thinking ON and burns the token cap with empty content).

Drafts are RAW MATERIAL, never gold — lora_curate.py applies the mechanical
gates (parse, fn-exists, arg-key vocabulary, id-ingredients) downstream.

Abstain situations are skipped (their target is minted empty, no draft).

Usage:
  python3 src-needle/scripts/lora_draft_deepseek.py [--limit N] [--concurrency 6]

Output: src-needle/data/lora/drafts-raw.jsonl (resumable cache, gitignored).
"""

import argparse
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SITS = REPO / "src-needle/data/lora/situations.jsonl"
OUT = REPO / "src-needle/data/lora/drafts-raw.jsonl"

INSTRUCTION = (
    "You are a Clojure REPL agent in the seon system. Given this situation, "
    "emit ONLY the next REPL form(s) you would evaluate — no prose."
)

PROV = {
    "url": "https://api.deepseek.com/chat/completions",
    "model": "deepseek-v4-pro",
    "key_env": "DEEPSEEK_API_KEY",
    "price_in": 0.435, "price_out": 0.87,  # $/M (llm-adapters.md catalog)
    "extra": {"thinking": {"type": "disabled"}},
}


def load_env_key(name: str) -> str:
    for envfile in (REPO / ".env", REPO / ".env.acme"):
        if envfile.exists():
            for line in envfile.read_text().splitlines():
                line = line.strip()
                if line.startswith(f"{name}=") and not line.startswith("#"):
                    return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} not found in .env/.env.acme")


def build_prompt(sit: dict) -> str:
    cards = "\n".join(sit["cards"])
    return (
        f"{sit['context']}\n\n"
        f";;; ┌─ cards ─ available functions ─\n"
        f"{cards}\n"
        f";;; └─ end cards ─\n\n"
        f"{INSTRUCTION}"
    )


FENCE_RE = re.compile(r"```(?:clojure|clj|edn)?\s*\n(.*?)```", re.DOTALL)


def clean_reply(text: str) -> str:
    blocks = FENCE_RE.findall(text)
    return "\n".join(b.strip() for b in blocks) if blocks else text.strip()


_print_lock = threading.Lock()


def call_model(api_key: str, prompt: str, sid: str) -> dict:
    payload = {
        "model": PROV["model"],
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.0,
        "max_tokens": 2048,
        **PROV["extra"],
    }
    delay = 2.0
    for attempt in range(6):
        req = urllib.request.Request(
            PROV["url"],
            data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {api_key}"},
        )
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                body = json.load(resp)
            msg = body["choices"][0]["message"]
            usage = body.get("usage", {})
            return {"sid": sid,
                    "raw": msg.get("content") or "",
                    "prompt_tokens": usage.get("prompt_tokens", 0),
                    "completion_tokens": usage.get("completion_tokens", 0)}
        except urllib.error.HTTPError as ex:
            detail = ex.read().decode(errors="replace")[:300]
            if ex.code in (429, 500, 502, 503, 504) and attempt < 5:
                with _print_lock:
                    print(f"  {sid}: HTTP {ex.code}, retry in {delay:.0f}s", file=sys.stderr)
                time.sleep(delay)
                delay *= 2
                continue
            raise SystemExit(f"{sid}: HTTP {ex.code}: {detail}")
        except (urllib.error.URLError, TimeoutError, OSError) as ex:
            if attempt < 5:
                time.sleep(delay)
                delay *= 2
                continue
            raise SystemExit(f"{sid}: {ex}")
    raise SystemExit(f"{sid}: retries exhausted")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--concurrency", type=int, default=6)
    args = ap.parse_args()

    api_key = load_env_key(PROV["key_env"])
    sits = [json.loads(l) for l in SITS.read_text().splitlines()]
    todo_sits = [s for s in sits if not s["abstain"]]
    if args.limit:
        todo_sits = todo_sits[: args.limit]

    done = {}
    if OUT.exists():
        for l in OUT.read_text().splitlines():
            d = json.loads(l)
            done[d["sid"]] = d
    todo = [s for s in todo_sits if s["sid"] not in done]
    print(f"{len(todo_sits)} draftable situations, {len(todo)} to fetch (resume {len(done)})")

    write_lock = threading.Lock()
    with OUT.open("a") as fh:
        def work(sit):
            res = call_model(api_key, build_prompt(sit), sit["sid"])
            res["clean"] = clean_reply(res["raw"])
            with write_lock:
                fh.write(json.dumps(res, ensure_ascii=False) + "\n")
                fh.flush()
                done[sit["sid"]] = res
                if len(done) % 25 == 0:
                    print(f"  {len(done)}/{len(todo_sits)} done")
            return res

        with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
            list(ex.map(work, todo))

    p_in = sum(d["prompt_tokens"] for d in done.values())
    p_out = sum(d["completion_tokens"] for d in done.values())
    spend = p_in / 1e6 * PROV["price_in"] + p_out / 1e6 * PROV["price_out"]
    print(json.dumps({"drafts": len(done), "prompt_tokens": p_in,
                      "completion_tokens": p_out, "spend_usd": round(spend, 4)}))


if __name__ == "__main__":
    main()
