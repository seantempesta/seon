"""Serial driver: 3 configs x N tasks against api.deepseek.com.

Reads DEEPSEEK_API_KEY from the environment (never logs or stores it).
Writes one raw JSON per (task, config) into out/ so grading is reproducible
without re-spending. Run: python3 tmp/flash-quality/run.py [task ...]
"""
import json, os, sys, time, urllib.request, pathlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tasks import TASKS

KEY = os.environ["DEEPSEEK_API_KEY"]
URL = "https://api.deepseek.com/chat/completions"
OUT = pathlib.Path(__file__).parent / "out"
OUT.mkdir(exist_ok=True)

# The three compared configurations. Thinking is toggled by the `thinking`
# struct: {"type": "enabled"|"disabled"|"adaptive"}. Default is ON.
CONFIGS = {
    "flash-think":   {"model": "deepseek-v4-flash", "thinking": {"type": "enabled"}},
    "flash-nothink": {"model": "deepseek-v4-flash", "thinking": {"type": "disabled"}},
    "pro":           {"model": "deepseek-v4-pro",   "thinking": {"type": "enabled"}},
}

MAX_TOKENS = 16384  # generous, so truncation never confounds a quality verdict


def call(cfg, prompt):
    body = dict(cfg)
    body["messages"] = [{"role": "user", "content": prompt}]
    body["max_tokens"] = MAX_TOKENS
    # temperature is SILENTLY IGNORED in thinking mode (vendor capture
    # 2026-08-01), so only send it where it actually controls anything.
    if cfg.get("thinking", {}).get("type") == "disabled":
        body["temperature"] = 0
    req = urllib.request.Request(
        URL, data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + KEY,
                 "Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=1800) as r:
        payload = json.load(r)
    payload["_wall_s"] = round(time.time() - t0, 1)
    return payload


def main():
    wanted = sys.argv[1:] or list(TASKS)
    for task in wanted:
        for cfg_name, cfg in CONFIGS.items():
            path = OUT / f"{task}__{cfg_name}.json"
            if path.exists():
                print(f"skip {path.name} (exists)")
                continue
            print(f"--> {task} / {cfg_name} ...", flush=True)
            try:
                payload = call(cfg, TASKS[task])
            except Exception as e:
                print(f"    ERROR {e}")
                continue
            path.write_text(json.dumps(payload, indent=1))
            u = payload["usage"]
            rt = u.get("completion_tokens_details", {}).get("reasoning_tokens", 0)
            print(f"    {payload['_wall_s']}s  completion={u['completion_tokens']}"
                  f"  reasoning={rt}  prompt={u['prompt_tokens']}")
            time.sleep(1)  # serial discipline


if __name__ == "__main__":
    main()
