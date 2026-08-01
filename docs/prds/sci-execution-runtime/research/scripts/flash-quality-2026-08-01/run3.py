"""Effort sweep: flash + thinking at reasoning_effort `low` vs the default
`high`, on the most decision-relevant tasks.

Per the vendor capture, flash maps low->low and high->high (only pro
collapses low to high), so `low` is a real dial on flash. The default the
main matrix measured was `high`.

Note: temperature is silently ignored in thinking mode, so it is NOT sent
here (sending it would be a lie about control we do not have).

Run: python3 tmp/flash-quality/run3.py
"""
import json, os, sys, pathlib
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tasks import TASKS
import run as driver

OUT = driver.OUT
# t1/t2/t4/t5 are where effort=high ran away; t7 is where thinking earned it.
SWEEP = ["t1-transducer", "t2-chunking", "t4-datalog-malli",
         "t5-debug", "t7-longctx"]


def main():
    driver.MAX_TOKENS = 65536
    for task in SWEEP:
        path = OUT / f"{task}__flash-think-low.json"
        if path.exists():
            print(f"skip {path.name}")
            continue
        cfg = {"model": "deepseek-v4-flash",
               "thinking": {"type": "enabled"},
               "reasoning_effort": "low"}
        print(f"--> {task} / flash-think-low ...", flush=True)
        try:
            payload = driver.call(cfg, TASKS[task])
        except Exception as e:
            print(f"    ERROR {e}")
            continue
        path.write_text(json.dumps(payload, indent=1))
        u = payload["usage"]
        rt = u.get("completion_tokens_details", {}).get("reasoning_tokens", 0)
        print(f"    {payload['_wall_s']}s completion={u['completion_tokens']} "
              f"reasoning={rt} finish={payload['choices'][0]['finish_reason']}")


if __name__ == "__main__":
    main()
