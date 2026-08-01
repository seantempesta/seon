"""Codebase-interrogation modality: ask flash (thinking vs non-thinking) to
find REAL defects in real Seon namespaces, in a strict claim format so every
claim can be graded CONFIRMED / FALSIFIED / UNVERIFIABLE.

Source is pasted WITH line numbers so claims cite verifiable locations.
Run: python3 tmp/flash-quality/interrogate.py
"""
import json, os, sys, pathlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run as driver

ROOT = pathlib.Path("/Users/sean/src/seon")
OUT = pathlib.Path(__file__).parent / "interro"
OUT.mkdir(exist_ok=True)

NAMESPACES = [
    "src/seon/render/value.cljc",
    "src/seon/sci/admit.clj",
    "src/seon/cluster/loop.cljc",
    "src/seon/flow.clj",
    "src/seon/schema.cljc",
]

CFG = {
    "flash-think":   {"model": "deepseek-v4-flash", "thinking": {"type": "enabled"}},
    "flash-nothink": {"model": "deepseek-v4-flash", "thinking": {"type": "disabled"}},
}

PROMPT = """You are auditing one namespace from the Seon codebase (Clojure, JVM,
Datahike + core.async.flow + SCI). The complete source follows, with line
numbers.

Find REAL DEFECTS. Concentrate on: edge cases (nil, empty collections, zero,
boundary sizes), off-by-one errors, race conditions between concurrent procs
or threads, non-terminating recursion or loops, and mismatches between a
function's stated contract (docstring or :malli/schema) and what it actually
does.

Rules for your answer:
- Report AT MOST 6 claims, ranked most severe first.
- Do NOT report style, naming, formatting, docstring wording, or performance
  preferences. Only defects that produce WRONG BEHAVIOUR.
- Every claim must be checkable against the source shown.
- If you genuinely find no real defect, reply with exactly: NO DEFECTS FOUND

Format each claim EXACTLY like this, with no extra prose between claims:

CLAIM <n>
LINES: <the line number or range from the listing>
FN: <the enclosing function name>
KIND: <edge-case|off-by-one|race|termination|contract-mismatch>
MECHANISM: <1-2 sentences: precisely what goes wrong and why>
REPRO: <a concrete input, and the wrong output or behaviour it produces>

Source of `{path}`:

```clojure
{src}
```
"""


def numbered(path):
    text = (ROOT / path).read_text().splitlines()
    return "\n".join(f"{i+1:5d}  {l}" for i, l in enumerate(text))


def main():
    driver.MAX_TOKENS = 65536
    for path in NAMESPACES:
        src = numbered(path)
        for cfg_name, cfg in CFG.items():
            slug = path.replace("/", "_").replace(".", "-")
            out = OUT / f"{slug}__{cfg_name}.json"
            if out.exists():
                print(f"skip {out.name}")
                continue
            print(f"--> {path} / {cfg_name} ...", flush=True)
            try:
                payload = driver.call(cfg, PROMPT.format(path=path, src=src))
            except Exception as e:
                print(f"    ERROR {e}")
                continue
            out.write_text(json.dumps(payload, indent=1))
            u = payload["usage"]
            rt = u.get("completion_tokens_details", {}).get("reasoning_tokens", 0)
            print(f"    {payload['_wall_s']}s prompt={u['prompt_tokens']} "
                  f"completion={u['completion_tokens']} reasoning={rt} "
                  f"finish={payload['choices'][0]['finish_reason']}")


if __name__ == "__main__":
    main()
