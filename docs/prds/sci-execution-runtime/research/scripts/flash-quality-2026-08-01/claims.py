"""Extract the structured CLAIM blocks from the codebase-interrogation
responses so each can be graded CONFIRMED / FALSIFIED / UNVERIFIABLE by hand
against source or the REPL.

Run: python3 tmp/flash-quality/claims.py [--full]
"""
import json, re, sys, pathlib

INTERRO = pathlib.Path(__file__).parent / "interro"
FULL = "--full" in sys.argv

BLOCK = re.compile(
    r"CLAIM\s+(\d+)\s*\n"
    r"\s*LINES:\s*(.*?)\n"
    r"\s*FN:\s*(.*?)\n"
    r"\s*KIND:\s*(.*?)\n"
    r"\s*MECHANISM:\s*(.*?)\n"
    r"\s*REPRO:\s*(.*?)(?=\n\s*CLAIM\s+\d|\Z)", re.S)


def main():
    total = {}
    for p in sorted(INTERRO.glob("*.json")):
        slug, cfg = p.stem.split("__")
        d = json.loads(p.read_text())
        msg = d["choices"][0]["message"]
        content = (msg.get("content") or "").strip()
        u = d["usage"]
        rt = u.get("completion_tokens_details", {}).get("reasoning_tokens", 0) or 0
        fin = d["choices"][0]["finish_reason"]
        claims = BLOCK.findall(content)
        total.setdefault(cfg, 0)
        total[cfg] += len(claims)
        print(f"\n{'='*72}\n{slug}  [{cfg}]  {d['_wall_s']}s  "
              f"prompt={u['prompt_tokens']} completion={u['completion_tokens']} "
              f"reasoning={rt} finish={fin}  claims={len(claims)}")
        if not content:
            print("  <<EMPTY CONTENT — thinking consumed the whole budget>>")
        if "NO DEFECTS FOUND" in content:
            print("  NO DEFECTS FOUND")
        for n, lines, fn, kind, mech, repro in claims:
            print(f"  --- CLAIM {n}  lines={lines.strip()}  fn={fn.strip()}  "
                  f"kind={kind.strip()}")
            print(f"      MECH:  {' '.join(mech.split())[:400 if not FULL else 10000]}")
            print(f"      REPRO: {' '.join(repro.split())[:400 if not FULL else 10000]}")
    print("\n\nclaim totals per config:", total)


if __name__ == "__main__":
    main()
