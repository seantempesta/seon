#!/usr/bin/env python3
"""LoRA data-gen step 3 — mechanical curation of DeepSeek drafts into pairs.

Drafts are raw material, never gold (design.md §Data sources, frontier-draft
mode). Gates, all mechanical:

  parse          must read via edamame (bb lora_forms.clj — the KT3 reader)
  defn-scope     any top-level def/defn => reject (v0 target-kind exclusion)
  square-peg     imperative/OO reflex heads anywhere (swap!/reset!/atom/set!/
                 throw/try/println/…) => reject
  fn-exists      every top-level call head must alias-resolve to the fn index,
                 an ns-move, or clojure.core; nested heads likewise => reject
                 (hallucinated-fn)
  spurious-id    every id-shaped string must appear in the situation context
                 (the ingredients rule — directly attacks KT3b's 4x
                 exemplar-leaked ids) => reject
  plan-keys      my.plan/* call arg-map keys must be in the my.plan keyword
                 vocabulary (computed from src/my/plan.cljs, not hand-listed);
                 bare keys in plan args => reject
  tx-shape       transact! taking a bare vector is WRAPPED to
                 {:seon.db/tx-data [...]} (repair); bare keyword keys inside
                 tx maps => reject
  register-shape schema/register! attr must be a namespaced keyword; at
                 schema/stuck stages a non-per-attribute draft is REPLACED by
                 the mechanical per-attribute bundle (repair)
  intent         at least one kept head must match the situation's intended
                 heads; arc stages with a mechanical fallback target get the
                 fallback (repair), others reject
  junk / length  non-call top-level forms dropped; assembled target >2000
                 chars => reject

Assembly: gold_prefix (in-ns/require boilerplate, fresh turns) + kept forms,
byte-exact source slices. Abstain rows mint target "" directly.

Outputs:
  data/tune/drafts-deepseek-2026-07-12.jsonl   ALL rows incl. rejects, v2-ish
                                               shape (context/cards/target/
                                               meta) — committed
  src-needle/data/lora/train.jsonl,valid.jsonl mlx chat format (kept rows)
  src-needle/data/lora/train_completions.jsonl,valid_completions.jsonl
                                               base-cont arm: cont prompt ->
                                               FIRST form (next-form shape)
"""

import hashlib
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "src-needle/scripts"
sys.path.insert(0, str(SCRIPTS))
from lora_draft_deepseek import build_prompt  # noqa: E402  (the ONE prompt builder)

SITS = REPO / "src-needle/data/lora/situations.jsonl"
DRAFTS = REPO / "src-needle/data/lora/drafts-raw.jsonl"
FN_INDEX = REPO / "src-needle/data/fn-index.json"
PLAN_SRC = REPO / "src/my/plan.cljs"
OUT_DRAFTS = REPO / "data/tune/drafts-deepseek-2026-07-12.jsonl"
OUT_DIR = REPO / "src-needle/data/lora"

SQUARE_PEG = {"swap!", "reset!", "atom", "set!", "vswap!", "vreset!", "throw",
              "try", "println", "prn", "print", "spit", "slurp", "doseq",
              "dotimes", "while", "alter-var-root", "binding", "future",
              "promise", "delay", "declare"}
NS_MOVE = {"in-ns", "ns", "require", "use", "refer", "load-file"}
DEF_FORMS = {"defn", "defn-", "def", "defonce", "defmacro", "defmethod", "defmulti"}
ID_RE = re.compile(r"[A-Za-z0-9]{3}-26\d{8}")


def bb_analyze(items):
    payload = json.dumps([{"sid": k, "text": v} for k, v in items])
    proc = subprocess.run(["bb", str(SCRIPTS / "lora_forms.clj")],
                          input=payload, capture_output=True, text=True, check=True)
    return {r["sid"]: r for r in json.loads(proc.stdout)}


def clojure_core_publics():
    proc = subprocess.run(
        ["bb", "-e", "(print (clojure.string/join \" \" (map name (keys (ns-publics 'clojure.core)))))"],
        capture_output=True, text=True, check=True)
    return set(proc.stdout.split())


def plan_vocab():
    toks = set(re.findall(r"::([A-Za-z_?!*+<>=-]+[A-Za-z0-9_?!*+<>=-]*)", PLAN_SRC.read_text()))
    return toks


def load_index_aliases():
    idx = json.loads(FN_INDEX.read_text())
    full, pairs, names = set(), set(), set()
    for f in idx["fns"]:
        sym = f["seon.fn/sym"]
        ns, name = sym.rsplit("/", 1)
        full.add(sym)
        pairs.add((ns.rsplit(".", 1)[-1], name))
        names.add(name)
    return full, pairs, names


def head_matches(head, target_alias):
    """Alias-insensitive: 'plan/done!' matches head {ns:'plan'|None, name:'done!'}."""
    tns, tname = target_alias.split("/", 1)
    return head["name"] == tname and (head["ns"] is None or head["ns"] == tns)


def head_known(head, full, pairs, names, core):
    if head["name"] in NS_MOVE or head["name"] in DEF_FORMS:
        return True
    if head["full"] in full:
        return True
    if head["ns"] is None:
        return head["name"] in names or head["name"] in core
    return (head["ns"], head["name"]) in pairs


class Reject(Exception):
    def __init__(self, reason, detail=""):
        self.reason, self.detail = reason, detail


def curate_one(sit, analysis, vocab, core, index):
    full, pairs, names = index
    if not analysis["parsed"]:
        raise Reject("parse", analysis.get("error") or "")
    ctx_ids = set(ID_RE.findall(sit["context"]))
    kept, repairs, dropped_junk = [], [], 0

    for f in analysis["forms"] or []:
        if not f["call"]:
            dropped_junk += 1
            continue
        head = f["head"]
        if head["name"] in DEF_FORMS:
            raise Reject("defn-out-of-scope", f["src"][:80])
        if head["name"] in SQUARE_PEG:
            raise Reject("square-peg", head["name"])
        for nh in f["nested-heads"]:
            if nh["name"] in SQUARE_PEG:
                raise Reject("square-peg", nh["name"])
            if not head_known(nh, full, pairs, names, core):
                raise Reject("hallucinated-fn", nh["full"])
        if not head_known(head, full, pairs, names, core):
            raise Reject("hallucinated-fn", head["full"])
        bad_ids = [i for i in f["ids"] if i not in ctx_ids]
        if bad_ids:
            raise Reject("spurious-id", ",".join(bad_ids[:3]))

        src = f["src"]
        # keyword-valued ids where a STRING id belongs (`:seon.agent/id
        # :my.agent.X…` / `:my.plan/id :X…`) — mechanical value repair
        fixed = re.sub(r"(:seon\.agent/id|:my\.plan/id)\s+:(?:my\.agent[./])?([A-Za-z0-9]{3}-26\d{8})",
                       r'\1 "\2"', src)
        if fixed != src:
            src = fixed
            repairs.append("id-string")
        if f["kind"] == "plan":
            for k in f["top-arg-map-keys"]:
                if k["ns"] is None:
                    raise Reject("bare-key-plan-arg", k["kw"])
                if k["ns"].endswith("my.plan") or k["ns"] == "my.plan":
                    if k["name"] not in vocab:
                        raise Reject("unknown-plan-key", k["kw"])
                elif k["ns"] not in ("seon.agent",):
                    raise Reject("unknown-plan-key", k["kw"])
        if f["kind"] == "transact":
            if f["first-arg"] and f["first-arg"]["type"] == "vector":
                src = f"(db/transact! {{:seon.db/tx-data {src[src.index('['):src.rindex(']') + 1]}}})"
                repairs.append("tx-wrap")
            if re.search(r"\{\s*:tx-data\b", src):
                src = re.sub(r"\{\s*:tx-data\b", "{:seon.db/tx-data", src)
                repairs.append("tx-data-ns")
            for k in f["all-map-keys"]:
                if k["ns"] is None and k["name"] not in ("tx-data", "query", "args"):
                    raise Reject("bare-key-tx", k["kw"])
        if f["kind"] == "register":
            fa = f["first-arg"]
            if not fa or fa["type"] != "keyword":
                raise Reject("register-shape", f["src"][:60])
            if fa["ns"] is None or not fa["ns"].startswith("my."):
                raise Reject("register-unqualified", fa["kw"])
            if re.search(r"\[\s*:map[\s\]]", src):
                # entity-map style — the repo idiom is per-attribute register!
                raise Reject("register-entity-map", f["src"][:60])
        kept.append({"src": src, "head": head, "kind": f["kind"]})

    substantive = [f for f in kept if f["kind"] != "ns-move"]
    if not substantive:
        raise Reject("no-call", "")

    intended = sit["intended_heads"]
    if intended and not any(head_matches(f["head"], t) for f in kept for t in intended):
        raise Reject("wrong-intent",
                     ",".join(sorted({f["head"]["full"] for f in substantive})[:3]))

    # assembly: gold_prefix, minus draft-side duplicates of it
    prefix = sit.get("gold_prefix") or ""
    body = [f for f in kept
            if not (prefix and f["kind"] == "ns-move"
                    and f["head"]["name"] in ("in-ns", "require"))]
    if len(body) < len(kept):
        repairs.append("prefix-dedupe")
    target = (prefix + "\n" if prefix else "") + "\n".join(f["src"] for f in body)
    if len(target) > 2000:
        raise Reject("too-long", str(len(target)))
    return target.strip(), repairs, dropped_junk


def main():
    sits = [json.loads(l) for l in SITS.read_text().splitlines()]
    drafts = {json.loads(l)["sid"]: json.loads(l)
              for l in DRAFTS.read_text().splitlines()}
    vocab = plan_vocab()
    core = clojure_core_publics()
    index = load_index_aliases()
    gen_sha = subprocess.run(["git", "-C", str(REPO), "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True).stdout.strip()

    analyses = bb_analyze([(s["sid"], drafts[s["sid"]]["clean"])
                           for s in sits if not s["abstain"] and s["sid"] in drafts])

    rows, stats, reject_reasons = [], Counter(), Counter()
    for sit in sits:
        meta = {"sid": sit["sid"], "family": sit["family"], "domain": sit["domain"],
                "stage": sit["stage"], "agent": sit["agent"],
                "intended_heads": sit["intended_heads"],
                "generator_sha": gen_sha, "source": "deepseek-v4-pro drafts, mechanical curation"}
        if sit["abstain"]:
            rows.append({"context": sit["context"], "cards": sit["cards"],
                         "target": "", "meta": {**meta, "status": "abstain"}})
            stats["abstain"] += 1
            continue
        draft = drafts.get(sit["sid"])
        if draft is None:
            stats["missing-draft"] += 1
            continue
        meta["draft_raw"] = draft["clean"]
        try:
            target, repairs, junk = curate_one(sit, analyses[sit["sid"]], vocab, core, index)
            status = "kept-repaired" if repairs else "kept"
            meta.update({"status": status, "repairs": repairs, "junk_forms_dropped": junk})
            rows.append({"context": sit["context"], "cards": sit["cards"],
                         "target": target, "meta": meta})
            stats[status] += 1
        except Reject as r:
            # a stage with a mechanical fallback keeps its correct-by-
            # construction gold whatever the draft did; the draft stays
            # recorded (negative example) either way
            if sit.get("mech_target"):
                prefix = sit.get("gold_prefix") or ""
                target = ((prefix + "\n" if prefix else "") + sit["mech_target"]).strip()
                meta.update({"status": "kept-mech",
                             "mech_reason": f"{r.reason}:{r.detail}"[:120]})
                rows.append({"context": sit["context"], "cards": sit["cards"],
                             "target": target, "meta": meta})
                stats["kept-mech"] += 1
                reject_reasons[f"mech<-{r.reason}"] += 1
            else:
                meta.update({"status": "rejected", "reject_reason": r.reason,
                             "reject_detail": r.detail})
                rows.append({"context": sit["context"], "cards": sit["cards"],
                             "target": None, "meta": meta})
                stats["rejected"] += 1
                reject_reasons[r.reason] += 1

    OUT_DRAFTS.parent.mkdir(parents=True, exist_ok=True)
    with OUT_DRAFTS.open("w") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    # ---- mlx training splits (kept + abstain rows only)
    train_rows = [r for r in rows if r["target"] is not None]
    chat, completions = [], []
    for r in train_rows:
        sit = {"context": r["context"], "cards": r["cards"]}
        prompt = build_prompt(sit)
        chat.append({"messages": [{"role": "user", "content": prompt},
                                  {"role": "assistant", "content": r["target"]}]})
        # base-cont arm: cards before transcript, next-form (first form) target
        ctx = r["context"]
        i = ctx.index(";;; ┌─ transcript ─")
        j = ctx.rindex(";;; └─ end transcript ─")
        cont_prompt = (ctx[:i]
                       + ";;; ┌─ cards ─ available functions ─\n"
                       + "\n".join(r["cards"]) + "\n;;; └─ end cards ─\n\n"
                       + ctx[i:j].rstrip("\n") + "\n")
        first_form = r["target"].split("\n(")[0] if r["target"] else ""
        if r["target"].startswith("("):
            depth = 0
            for pos, ch in enumerate(r["target"]):
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                    if depth == 0:
                        first_form = r["target"][:pos + 1]
                        break
        completions.append({"prompt": cont_prompt, "completion": first_form})

    def split(rows_):
        tr, va = [], []
        for i, r in enumerate(rows_):
            key = hashlib.sha1(json.dumps(train_rows[i]["meta"]["sid"]).encode()).hexdigest()
            (va if int(key[:8], 16) % 20 == 0 else tr).append(r)
        return tr, va

    for name, data in (("", chat), ("_completions", completions)):
        tr, va = split(data)
        for part, rows_ in (("train", tr), ("valid", va)):
            p = OUT_DIR / f"{part}{name}.jsonl"
            with p.open("w") as fh:
                for r in rows_:
                    fh.write(json.dumps(r, ensure_ascii=False) + "\n")
            print(f"{p.name}: {len(rows_)}")

    print("\nstatus:", dict(stats))
    print("reject reasons:", dict(reject_reasons))
    by_stage = Counter((r["meta"]["stage"], r["meta"]["status"]) for r in rows)
    stages = sorted({s for s, _ in by_stage})
    for s in stages:
        line = {st: by_stage.get((s, st), 0)
                for st in ("kept", "kept-repaired", "kept-mech", "rejected", "abstain")}
        print(f"  {s:22s} {line}")
    lens = sorted(len(r["target"]) // 4 for r in train_rows if r["target"])
    if lens:
        print(f"target tokens p50={lens[len(lens)//2]} p90={lens[int(.9*len(lens))]} max={lens[-1]}")


if __name__ == "__main__":
    main()
