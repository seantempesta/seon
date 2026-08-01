"""Follow-up probes after the main 16K matrix.

Owner correction 2026-08-01: max_tokens 16384 let thinking eat the whole
budget. Re-run at 65536 so no correctness grade comes from a truncated
response, and measure the third `thinking.type` enum value, `adaptive`.

Writes `<task>__<cfg>-65k.json`. Run: python3 tmp/flash-quality/run2.py
"""
import json, os, sys, pathlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tasks import TASKS
import run as driver

OUT = driver.OUT
BIG = 65536

CFG = {
    "flash-think":    {"model": "deepseek-v4-flash", "thinking": {"type": "enabled"}},
    "flash-nothink":  {"model": "deepseek-v4-flash", "thinking": {"type": "disabled"}},
    "pro":            {"model": "deepseek-v4-pro",   "thinking": {"type": "enabled"}},
    "flash-adaptive": {"model": "deepseek-v4-flash", "thinking": {"type": "adaptive"}},
}


def truncated_cells():
    """Every cell from the 16K matrix whose finish_reason was `length`."""
    out = []
    for p in sorted(OUT.glob("*.json")):
        if p.stem.endswith("-65k"):
            continue
        task, cfg = p.stem.split("__")
        d = json.loads(p.read_text())
        if d["choices"][0]["finish_reason"] == "length":
            out.append((task, cfg))
    return out


def run(task, cfg_name):
    path = OUT / f"{task}__{cfg_name}-65k.json"
    if path.exists():
        print(f"skip {path.name}")
        return
    print(f"--> {task} / {cfg_name} @ {BIG} ...", flush=True)
    driver.MAX_TOKENS = BIG
    try:
        payload = driver.call(CFG[cfg_name], TASKS[task])
    except Exception as e:
        print(f"    ERROR {e}")
        return
    path.write_text(json.dumps(payload, indent=1))
    u = payload["usage"]
    rt = u.get("completion_tokens_details", {}).get("reasoning_tokens", 0)
    print(f"    {payload['_wall_s']}s  completion={u['completion_tokens']}"
          f"  reasoning={rt}  finish={payload['choices'][0]['finish_reason']}")


def main():
    cells = truncated_cells()
    print(f"truncated cells to regrade at {BIG}: {cells}")
    for task, cfg in cells:
        run(task, cfg)
    # adaptive is a fresh config: measure it on every task at the same budget
    for task in TASKS:
        run(task, "flash-adaptive")


if __name__ == "__main__":
    main()
