"""Pull the code fence / ANSWER line out of each saved response and report
the per-call metrics table. Run: python3 tmp/flash-quality/extract.py
"""
import json, re, pathlib, sys

OUT = pathlib.Path(__file__).parent / "out"
CODE = pathlib.Path(__file__).parent / "code"
CODE.mkdir(exist_ok=True)

CONFIGS = ["flash-think", "flash-nothink", "pro"]


def fence(text):
    """Last ```clojure fence in the reply (models sometimes show a draft first)."""
    blocks = re.findall(r"```(?:clojure|clj)?\s*\n(.*?)```", text, re.S)
    return blocks[-1] if blocks else None


def answer_line(text):
    hits = re.findall(r"ANSWER:\s*([-\d]+)", text)
    return int(hits[-1]) if hits else None


def rows():
    for path in sorted(OUT.glob("*.json")):
        task, cfg = path.stem.split("__")
        d = json.loads(path.read_text())
        msg = d["choices"][0]["message"]
        content = msg.get("content") or ""
        u = d["usage"]
        rt = u.get("completion_tokens_details", {}).get("reasoning_tokens", 0) or 0
        code = fence(content)
        if code:
            (CODE / f"{task}__{cfg}.clj").write_text(code)
        yield dict(task=task, cfg=cfg, wall=d["_wall_s"],
                   prompt=u["prompt_tokens"], completion=u["completion_tokens"],
                   reasoning=rt, answer=answer_line(content),
                   finish=d["choices"][0]["finish_reason"],
                   has_code=bool(code), content=content)


if __name__ == "__main__":
    data = list(rows())
    print(f"{'task':<18}{'config':<15}{'wall_s':>8}{'prompt':>8}"
          f"{'compl':>8}{'reason':>8}{'finish':>10}  answer")
    for r in data:
        print(f"{r['task']:<18}{r['cfg']:<15}{r['wall']:>8}{r['prompt']:>8}"
              f"{r['completion']:>8}{r['reasoning']:>8}{r['finish']:>10}"
              f"  {r['answer']}")
