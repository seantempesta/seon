"""Measure needle-tokenizer efficiency on Clojure vs its native English/JSON.

The needle tokenizer (SentencePiece BPE 8192, byte-fallback) was trained on
English tool-calling JSON. This measures chars/token on REAL seon Clojure
source and a `my.plan/reconcile!` markdown heredoc, against the English-JSON
baseline — deciding the design.md open question (retrain the tokenizer only
if MEASURED to blow the 1024-enc/512-dec budget). Sizes in TOKENS, always.
"""

from pathlib import Path

from . import config
from .tokenizer import DEFAULT_MAX_DEC_LEN, DEFAULT_MAX_ENC_LEN, load_tokenizer

# real source, the projection/target distribution B2 trains toward
CLJS_SAMPLE_FILES = ["db.cljs", "ctx.cljs", "agent.cljs", "eval.cljs", "warn.cljs"]

# a realistic flagship decoder target: plan markdown -> reconcile!
RECONCILE_HEREDOC = '''(my.plan/reconcile!
  {:my.plan/markdown #code/markdown <<PLAN
# Ship the turn exporter
- [ ] Register :seon.tune/rating + :seon.tune/tag curation attrs
- [ ] Projection fn pure over a db value
  - [ ] Render bands: position, warnings, recent tail, inbound guidance
  - [ ] Cap each band to its token budget
- [ ] Walk agent-turns, render at rendered-as-of, emit JSONL
- [ ] Prove on the acme store, report row counts
PLAN
})'''

# typical single REPL forms (decoder-target shaped)
REPL_FORMS = [
    '(schema/register! ::rating [:int {:min 1 :max 5}])',
    '(db/query \'[:find ?e ?title :where [?e :my.plan/title ?title]])',
    '(db/transact! :seon [{:my.kb/id "src-42" :my.kb/rating 4}])',
    '(defn open-steps [db] (->> (db/query db \'[:find [?e ...] :where '
    '[?e :my.plan/status :open]]) (map #(db/pull db \'[*] %))))',
    '(my.plan/done! {:my.plan/id "step-7"})',
    "(require '[seon.agent.inspect :as inspect])",
]


def measure(tokenizer, name, text):
    toks = tokenizer.encode(text)
    n = max(len(toks), 1)
    return {"name": name, "chars": len(text), "tokens": len(toks),
            "chars_per_token": len(text) / n}


def clojure_source_texts():
    src = config.repo_root() / "src" / "seon"
    out = []
    for fname in CLJS_SAMPLE_FILES:
        p = src / fname
        if p.exists():
            out.append((fname, p.read_text()))
    return out


def english_json_texts():
    """needle's native distribution: English queries + tools JSON."""
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tests"))
    from parity_inputs import PARITY_INPUTS

    queries = " ".join(q for q, _ in PARITY_INPUTS[:14])
    tools = "".join(t for _, t in PARITY_INPUTS[:14])
    return [("english queries", queries), ("tools JSON", tools)]


def main():
    tokenizer = load_tokenizer()
    rows = []
    for name, text in english_json_texts():
        rows.append(measure(tokenizer, f"baseline: {name}", text))
    for fname, text in clojure_source_texts():
        rows.append(measure(tokenizer, f"clojure: src/seon/{fname}", text))
    rows.append(measure(tokenizer, "clojure: reconcile! heredoc", RECONCILE_HEREDOC))
    for i, form in enumerate(REPL_FORMS):
        rows.append(measure(tokenizer, f"form[{i}]: {form[:40]}...", form))

    print(f"{'corpus':<44} {'chars':>8} {'tokens':>8} {'chars/tok':>10}")
    for r in rows:
        print(f"{r['name']:<44} {r['chars']:>8} {r['tokens']:>8} "
              f"{r['chars_per_token']:>10.2f}")

    baseline = [r for r in rows if r["name"].startswith("baseline")]
    clojure = [r for r in rows if r["name"].startswith("clojure")]
    forms = [r for r in rows if r["name"].startswith("form")]
    b_cpt = sum(r["chars"] for r in baseline) / sum(r["tokens"] for r in baseline)
    c_cpt = sum(r["chars"] for r in clojure) / sum(r["tokens"] for r in clojure)
    f_tokens = [r["tokens"] for r in forms]

    print(f"\nEnglish/JSON baseline: {b_cpt:.2f} chars/token")
    print(f"Clojure (source + heredoc): {c_cpt:.2f} chars/token "
          f"({b_cpt / c_cpt:.2f}x more tokens per char than baseline)")
    print(f"\nBudget fit ({DEFAULT_MAX_ENC_LEN} enc / {DEFAULT_MAX_DEC_LEN} dec):")
    print(f"  encoder budget holds ~{int(DEFAULT_MAX_ENC_LEN * c_cpt):,} chars "
          f"of Clojure-ish projection")
    print(f"  decoder budget holds ~{int(DEFAULT_MAX_DEC_LEN * c_cpt):,} chars "
          f"of Clojure forms")
    print(f"  typical single REPL forms: {min(f_tokens)}-{max(f_tokens)} tokens "
          f"-> ~{DEFAULT_MAX_DEC_LEN // (sum(f_tokens) // len(f_tokens))} "
          f"average forms per decoder budget")
    heredoc = next(r for r in rows if "heredoc" in r["name"])
    print(f"  reconcile! heredoc example: {heredoc['tokens']} tokens "
          f"({heredoc['tokens'] / DEFAULT_MAX_DEC_LEN:.0%} of decoder budget)")


if __name__ == "__main__":
    main()
