"""Build JSONL corpora {id, text} from the Seon checkout (reads files only).
  python -I corpus.py forms  ROOT OUT.jsonl     every top-level form of src/**/*.clj (a form starts at a column-0 '(')
  python -I corpus.py docs   ROOT OUT.jsonl     every ##-section of docs/seon/**/*.md
  python -I corpus.py parity ROOT OUT.jsonl     ~200 mixed texts (prose, forms of all lengths, doc sections), fixed seed"""
import sys, json, random
from pathlib import Path

def forms(root):
    for f in sorted(Path(root, "src").rglob("*.clj")):
        cur, start = [], 0
        for n, line in enumerate(f.read_text(errors="replace").splitlines(), 1):
            if line.startswith("(") and any(l.startswith("(") for l in cur):
                yield {"id": f"{f.relative_to(root)}:{start}", "text": "\n".join(cur).strip()}; cur, start = [], n
            if not cur: start = n
            cur.append(line)
        if "".join(cur).strip(): yield {"id": f"{f.relative_to(root)}:{start}", "text": "\n".join(cur).strip()}

def docs(root):
    for f in sorted(Path(root, "docs/seon").rglob("*.md")):
        cur, head = [], "top"
        for line in f.read_text(errors="replace").splitlines():
            if line.startswith("## ") and cur:
                yield {"id": f"{f.relative_to(root)}#{head}", "text": "\n".join(cur).strip()}; cur, head = [], line[3:].strip()
            cur.append(line)
        if cur: yield {"id": f"{f.relative_to(root)}#{head}", "text": "\n".join(cur).strip()}

def parity(root):
    r = random.Random(7)
    prose = [p.strip() for p in Path(root, "CLAUDE.md").read_text().split("\n\n") if len(p.strip()) > 40]
    fs = [x for x in forms(root) if x["text"]]; ds = [x for x in docs(root) if len(x["text"]) > 200]
    fs.sort(key=lambda x: len(x["text"]))
    k = min(50, len(prose)); picked = r.sample(prose, k) + [fs[int(i * (len(fs) - 1) / 109)] for i in range(110)] + r.sample(ds, 90 - k)
    return [x if isinstance(x, dict) else {"id": f"CLAUDE.md#{i}", "text": x} for i, x in enumerate(picked)]

mode, root, out = sys.argv[1:4]
rows = {"forms": forms, "docs": docs, "parity": parity}[mode](root)
with open(out, "w") as w:
    k = 0
    for x in rows:
        if x["text"]: w.write(json.dumps(x) + "\n"); k += 1
print(out, k)
