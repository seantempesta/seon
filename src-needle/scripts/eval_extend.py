#!/usr/bin/env python3
"""Needle-extension eval battery (post-train, all local).

Arms over the HELD-OUT v2 rows (data/tune/acme-2026-07-12-v2.jsonl —
mined turns, never trained on), each: encoder = context + <tools> +
json_tools, trie-CONSTRAINED greedy decode (B1: unconstrained garbles
keys), then the INVERTIBLE BRIDGE back to Clojure, then the bb scorer:

  stock-1024          stock checkpoint, native envelope (tools truncate)
  stock-2048-pi       stock checkpoint, rope scale 2 @2048 (zero-shot
                      position interpolation — isolates train vs scale)
  trained-2048        the extension finetune (checkpoints/extended-2048)
  trained-2048-max    trained + the tools slot topped up with as many
                      fn-index tools as fit @2048 (whole-graph arm,
                      coordinator 2026-07-12)
  trained-4096-max    trained served at rope scale 4 @4096 (zero-shot
                      scale extrapolation — 4096 TRAINING exceeds the
                      8 GB envelope, measured), full-index top-up

Bridge inversion (the design's "invertible bridge does JSON<->Clojure at
the boundary both directions"): tool snake name -> original sym via
build_v2's own card translation (reused, not re-derived); argument snake
key -> original keyword via the param description (fn_to_tool writes
str(orig_key) there — the invertibility hook); values: ":kw" strings ->
keywords, EDN-looking strings (leading ( [ { ' #) embedded raw, other
strings quoted. A prediction that does not read scores 0 (KT3 rule).

Scoring: KT3-redux extended mode when the extended kt3_score.clj is
available (set-union best-match over the turn's FULL form set =
target_bundle, + the decomp block), legacy mode vs the next-form target
as the secondary lens. Falls back to legacy-only + a Python-side
decomposition if the extended scorer is absent/broken at run time.

Run (from src-needle/):
  .venv/bin/python scripts/eval_extend.py bridge   [arm ...]
  .venv/bin/python scripts/eval_extend.py probe    [stock|trained ...]
  .venv/bin/python scripts/eval_extend.py bfcl     [stock|trained ...]
  .venv/bin/python scripts/eval_extend.py latency
Outputs under data/exteval/ (gitignored; the research file quotes them).
Sizes/speeds in TOKENS, always.
"""

import json
import random
import statistics
import subprocess
import sys
import time
from pathlib import Path

PKG_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PKG_ROOT / "src"))

import mlx.core as mx  # noqa: E402

# Bound the Metal buffer cache (train_extend._pad's lesson: unbounded
# cached buffers wired 103 GB and jetsam-killed the machine's daemons).
mx.set_cache_limit(2 * 1024 ** 3)

from seon_needle import config  # noqa: E402
from seon_needle.build_v2 import card_tools, run_splitter  # noqa: E402
from seon_needle.lint_probe import (constrained_generate_batch, load_index,  # noqa: E402
                                    parse_calls, cmd_run, cmd_calibrate)
from seon_needle.model import load_model  # noqa: E402
from seon_needle.tokenizer import load_tokenizer  # noqa: E402
from seon_needle.train_extend import load_extended, CKPT_DIR  # noqa: E402

REPO_ROOT = config.repo_root()
V2 = REPO_ROOT / "data" / "tune" / "acme-2026-07-12-v2.jsonl"
V1 = REPO_ROOT / "data" / "tune" / "acme-2026-07-12.jsonl"
OUT_DIR = PKG_ROOT / "data" / "exteval"
SCORER = PKG_ROOT / "scripts" / "kt3_score.clj"

NS_MOVE = {"in-ns", "ns", "require", "use", "refer", "load-file"}


# ---------------------------------------------------------------------------
# Bridge: snake name/key -> Clojure form
# ---------------------------------------------------------------------------

def snake_sym_maps():
    """(per-row snake->sym using v1 cards via build_v2's card translation,
    global snake->sym over the fn index)."""
    v1_rows = [json.loads(l) for l in V1.read_text().splitlines() if l.strip()]
    distinct = []
    seen = set()
    for r in v1_rows:
        for c in r["cards"]:
            if c not in seen:
                seen.add(c)
                distinct.append(c)
    split = run_splitter([], distinct)
    tools_by_card, _stats = card_tools(distinct, split["cards"])

    from seon_needle.lint_probe import to_snake_case
    per_card = {c: (to_snake_case(sym), sym) for c, (_t, sym, _n) in tools_by_card.items()}

    row_maps = []
    for r in v1_rows:
        m = {}
        for c in r["cards"]:
            snake, sym = per_card[c]
            m[snake] = sym
        row_maps.append(m)

    _, index_tools, _gaps = load_index()
    global_map = {to_snake_case(sym): sym for sym in index_tools}
    return row_maps, global_map, index_tools


def clj_value(v):
    if v is None:
        return "nil"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return repr(v)
    if isinstance(v, str):
        s = v.strip()
        if s.startswith(":") and " " not in s and "\n" not in s:
            return s
        if s[:1] in "([{'#":  # edn-fallback copy (quoted queries, symbols)
            return s
        return json.dumps(v)
    if isinstance(v, list):
        return "[" + " ".join(clj_value(x) for x in v) + "]"
    if isinstance(v, dict):
        return "{" + " ".join(f"{clj_key(k)} {clj_value(x)}"
                              for k, x in v.items()) + "}"
    return json.dumps(str(v))


def clj_key(k):
    return k if str(k).startswith(":") else ":" + str(k)


def bridge_calls(calls, tools_in_slot, snake_map, global_map):
    """[{name, arguments}] -> multi-form Clojure text ('' = abstention)."""
    param_kw = {}
    for t in tools_in_slot:
        for p, spec in (t.get("parameters") or {}).items():
            desc = (spec.get("description") or "").split(" — ")[0].strip()
            if desc.startswith(":"):
                param_kw.setdefault(t["name"], {})[p] = desc
    forms = []
    for c in calls:
        snake = c.get("name") or ""
        sym = snake_map.get(snake) or global_map.get(snake) or snake
        args = c.get("arguments") or {}
        if not args:
            forms.append(f"({sym})")
            continue
        kws = param_kw.get(snake, {})
        pairs = " ".join(f"{kws.get(k, clj_key(k))} {clj_value(v)}"
                         for k, v in args.items())
        forms.append(f"({sym} {{{pairs}}})")
    return "\n".join(forms)


# ---------------------------------------------------------------------------
# Arms
# ---------------------------------------------------------------------------

def load_v2():
    return [json.loads(l) for l in V2.read_text().splitlines() if l.strip()]


def top_up_tools(row_tools, index_tools, tokenizer, context, budget, rng):
    """Row's own tools + as many fn-index tools as fit in `budget` total
    encoder tokens (whole-graph arm). Index tools appended in a seeded
    shuffle; own tools keep their slots."""
    own_names = {t["name"] for t in row_tools}
    tools = list(row_tools)
    base = len(tokenizer.encode(context)) + 3 + len(
        tokenizer.encode(json.dumps(tools, separators=(",", ":"))))
    items = list(index_tools.values())  # normalized snake-name index tools
    rng.shuffle(items)
    total = base
    for t in items:
        if t["name"] in own_names:
            continue
        tl = len(tokenizer.encode(json.dumps(t, separators=(",", ":")))) + 1
        if total + tl > budget:
            continue
        tools.append(t)
        own_names.add(t["name"])
        total += tl
    return tools


def normalized_index_tools():
    """fn-index tools with snake names (the serving shape)."""
    from seon_needle.lint_probe import normalize_tools
    _, tools, _ = load_index()
    tj, name_map = normalize_tools(json.dumps(list(tools.values()),
                                              separators=(",", ":")))
    norm = json.loads(tj)
    return {name_map[t["name"]]: t for t in norm}, \
        {t["name"]: name_map[t["name"]] for t in norm}


ARMS = ("stock-1024", "stock-2048-pi", "trained-2048",
        "trained-2048-max", "trained-4096-max")


def get_model(arm):
    if arm.startswith("stock"):
        m = load_model()
        m.config.enc_rope_scale = 2.0 if "2048" in arm else 1.0
    else:
        m = load_extended()
        m.config.enc_rope_scale = 4.0 if "4096" in arm else 2.0
    return m


def arm_max_enc(arm):
    return 1024 if "1024" in arm else (4096 if "4096" in arm else 2048)


def run_bridge_arm(arm, rows, tokenizer, row_maps, global_map, batch=8):
    model = get_model(arm)
    max_enc = arm_max_enc(arm)
    if max_enc >= 4096:
        batch = 4  # keep the f32 encoder-attention transient under 8 GB
    index_norm, _sym_to_snake = normalized_index_tools()
    global_snake_to_sym = {snake: sym for sym, snake in _sym_to_snake.items()}

    preds_path = OUT_DIR / f"preds-{arm}.jsonl"
    done = {}
    if preds_path.exists():
        for line in preds_path.read_text().splitlines():
            d = json.loads(line)
            done[d["row"]] = d

    todo = [i for i in range(len(rows)) if i not in done]
    print(f"{arm}: {len(rows)} rows, {len(todo)} to generate", flush=True)
    with preds_path.open("a") as fh:
        for start in range(0, len(todo), batch):
            idx = todo[start:start + batch]
            contexts, tools_slots, slot_tools = [], [], []
            for i in idx:
                r = rows[i]
                tools = r["json_tools"]
                if arm.endswith("-max"):
                    rng = random.Random(f"{arm}|{i}")
                    tools = top_up_tools(tools, index_norm, tokenizer,
                                         r["context"], max_enc - 8, rng)
                contexts.append(r["context"])
                tools_slots.append(json.dumps(tools, separators=(",", ":")))
                slot_tools.append(tools)
            out = constrained_generate_batch(model, tokenizer, contexts,
                                             tools_slots, max_enc_len=max_enc)
            for j, i in enumerate(idx):
                r = rows[i]
                calls, parsed = parse_calls(out["texts"][j])
                v1_row = r["meta"]["v1_row"]
                clj = bridge_calls(calls, slot_tools[j], row_maps[v1_row],
                                   {**global_map, **global_snake_to_sym}) if parsed else ""
                rec = {"row": i, "v1_row": v1_row,
                       "raw": out["texts"][j], "json_parsed": parsed,
                       "n_calls": len(calls), "clojure": clj,
                       "n_tools_in_slot": len(slot_tools[j]),
                       "tools_cut_tokens": out["tools_cut_tokens"][j],
                       "prefill_tok_s": round(out["prefill_tokens"] / out["prefill_s"]),
                       "decode_tok_s": round(out["decode_tok_s"])}
                fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
                done[i] = rec
            fh.flush()
            print(f"  [{len(done)}/{len(rows)}] decode {out['decode_tok_s']:.0f} tok/s",
                  flush=True)
    del model
    mx.clear_cache()
    return done


# ---------------------------------------------------------------------------
# Scoring (extended scorer when available; legacy + python decomp fallback)
# ---------------------------------------------------------------------------

def _scorer_path():
    """Working-tree scorer if its LEGACY mode passes a smoke; else the
    committed HEAD version extracted to data/exteval/ (the parallel
    KT3-redux extension may be mid-edit at eval time)."""
    legacy = [{"id": 0, "target": "(f {:a 1})", "prediction": "(f {:a 1})"}]
    try:
        proc = subprocess.run(["bb", str(SCORER)], input=json.dumps(legacy),
                              capture_output=True, text=True, timeout=120)
        if proc.returncode == 0 and json.loads(proc.stdout)[0]["useful"] == 1.0:
            return SCORER
    except Exception:
        pass
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    fallback = OUT_DIR / "kt3_score_head.clj"
    src = subprocess.run(["git", "-C", str(REPO_ROOT), "show",
                          "HEAD:src-needle/scripts/kt3_score.clj"],
                         capture_output=True, text=True, check=True).stdout
    fallback.write_text(src)
    print("WARNING: working-tree scorer failed legacy smoke — using HEAD copy",
          flush=True)
    return fallback


def scorer_extended_ok(scorer):
    """Probe the scorer's extended mode with a 1-row input."""
    payload = {"index-syms": ["my.plan/done!"],
               "rows": [{"id": 0, "target": '(my.plan/done! {:my.plan/id "x"})',
                         "prediction": '(my.plan/done! {:my.plan/id "x"})',
                         "context": "", "card-names": ["my.plan/done!"]}]}
    try:
        proc = subprocess.run(["bb", str(scorer)], input=json.dumps(payload),
                              capture_output=True, text=True, timeout=120)
        if proc.returncode != 0:
            return False
        out = json.loads(proc.stdout)
        return isinstance(out, list) and "decomp" in out[0]
    except Exception:
        return False


def score_rows(pairs, scorer, extended_ctx=None):
    """pairs: [{"id","target","prediction"(,"context","card-names")}] ->
    scorer output list. extended_ctx: {"index-syms": [...]} to use mode 2."""
    if extended_ctx is not None:
        payload = {"index-syms": extended_ctx["index-syms"], "rows": pairs}
        inp = json.dumps(payload)
    else:
        inp = json.dumps([{k: p[k] for k in ("id", "target", "prediction")}
                          for p in pairs])
    proc = subprocess.run(["bb", str(scorer)], input=inp,
                          capture_output=True, text=True, check=True,
                          timeout=600)
    return json.loads(proc.stdout)


def py_decomp(scored_row):
    """Fallback decomposition from legacy scorer counts (same categories,
    minus the wrong-fn vs hallucinated split the extended scorer owns)."""
    matched = scored_row.get("matched") or 0
    n_target = scored_row.get("n-target") or 0
    n_pred = scored_row.get("n-pred") or 0
    return {"matched": matched, "missing": max(0, n_target - matched),
            "unmatched-pred": max(0, n_pred - matched)}


def tranche(cov):
    return "lo_lt_.25" if cov < 0.25 else ("mid_.25-.75" if cov < 0.75 else "hi_ge_.75")


def summarize_bridge(arm, rows, preds, scored_bundle, scored_next):
    ids = sorted(preds)
    by_id_b = {s["id"]: s for s in scored_bundle}
    by_id_n = {s["id"]: s for s in scored_next}
    js_ids = [i for i in ids if rows[i]["json_target"] is not None]

    def agg(id_list, by_id):
        if not id_list:
            return None
        useful = [by_id[i]["useful"] for i in id_list]
        out = {"n": len(id_list),
               "useful_mean": round(sum(useful) / len(useful), 3),
               "useful_ge_0.5": round(sum(1 for u in useful if u >= 0.5) / len(useful), 3),
               "parse_rate": round(sum(1 for i in id_list if by_id[i]["parsed"]) / len(id_list), 3)}
        subst = [by_id[i]["substantive"]["useful"] for i in id_list
                 if by_id[i].get("substantive")]
        out["substantive_mean"] = round(sum(subst) / len(subst), 3) if subst else None
        kinds = {}
        for i in id_list:
            for k, v in (by_id[i].get("kinds") or {}).items():
                a = kinds.setdefault(k, {"n": 0, "matched": 0, "credit_sum": 0.0})
                a["n"] += v["n"]
                a["matched"] += v["matched"]
                a["credit_sum"] += v["credit-sum"]
        for a in kinds.values():
            a["mean_credit"] = round(a["credit_sum"] / a["n"], 3)
            a["credit_sum"] = round(a["credit_sum"], 2)
        out["kinds"] = kinds
        buckets = {}
        for i in id_list:
            buckets.setdefault(tranche(rows[i]["meta"]["coverage"]), []).append(by_id[i]["useful"])
        out["coverage_buckets"] = {k: {"n": len(v), "useful_mean": round(sum(v) / len(v), 3)}
                                   for k, v in sorted(buckets.items())}
        # KT3-redux decomposition: count target/pred call outcomes
        tout, pout, conf = {}, {}, 0
        for i in id_list:
            d = by_id[i].get("decomp")
            if d is None:
                d = py_decomp(by_id[i])
                for k, v in d.items():
                    tout[k] = tout.get(k, 0) + v
                continue
            for t in d.get("target-outcomes", []):
                tout[t["outcome"]] = tout.get(t["outcome"], 0) + 1
            for t in d.get("pred-outcomes", []):
                pout[t["outcome"]] = pout.get(t["outcome"], 0) + 1
            conf += len(d.get("confusions", []))
        out["decomp"] = {"target_outcomes": tout, "pred_outcomes": pout,
                         "confusion_pairs": conf}
        return out

    abstained = sum(1 for i in ids if preds[i]["clojure"] == "" and preds[i]["json_parsed"])
    return {
        "arm": arm,
        "bundle_set_union": {"all_rows": agg(ids, by_id_b),
                             "json_translatable": agg(js_ids, by_id_b)},
        "next_form": {"all_rows": agg(ids, by_id_n),
                      "json_translatable": agg(js_ids, by_id_n)},
        "abstention_rate": round(abstained / len(ids), 3),
        "n_tools_in_slot_median": statistics.median(preds[i]["n_tools_in_slot"] for i in ids),
        "tools_cut_rows": sum(1 for i in ids if preds[i]["tools_cut_tokens"] > 0),
        "prefill_tok_s_median": statistics.median(preds[i]["prefill_tok_s"] for i in ids),
        "decode_tok_s_median": statistics.median(preds[i]["decode_tok_s"] for i in ids),
    }


def cmd_bridge(arms):
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    rows = load_v2()
    tokenizer = load_tokenizer()
    print("building bridge maps (bb card translation)…", flush=True)
    row_maps, global_map, _ = snake_sym_maps()

    scorer = _scorer_path()
    ext_ok = scorer_extended_ok(scorer)
    print(f"scorer: {scorer.name}, extended mode: {ext_ok}", flush=True)
    _, index_tools, _ = load_index()
    index_syms = sorted(index_tools)

    summaries = {}
    for arm in arms:
        preds = run_bridge_arm(arm, rows, tokenizer, row_maps, global_map)
        ids = sorted(preds)

        def mk_pairs(target_key):
            out = []
            for i in ids:
                r = rows[i]
                p = {"id": i, "target": r[target_key],
                     "prediction": preds[i]["clojure"]}
                if ext_ok:
                    p["context"] = r["context"]
                    # qualified syms (the scorer grounds hallucination
                    # checks against these, not snake names)
                    p["card-names"] = sorted(set(
                        row_maps[r["meta"]["v1_row"]].values()))
                out.append(p)
            return out

        scored_b = score_rows(mk_pairs("target_bundle"), scorer,
                              {"index-syms": index_syms} if ext_ok else None)
        scored_n = score_rows(mk_pairs("target"), scorer,
                              {"index-syms": index_syms} if ext_ok else None)
        (OUT_DIR / f"scored-{arm}-bundle.json").write_text(json.dumps(scored_b, indent=1))
        (OUT_DIR / f"scored-{arm}-next.json").write_text(json.dumps(scored_n, indent=1))
        s = summarize_bridge(arm, rows, preds, scored_b, scored_n)
        s["scorer_mode"] = "extended" if ext_ok else "legacy+py-decomp"
        summaries[arm] = s
        (OUT_DIR / f"summary-{arm}.json").write_text(json.dumps(s, indent=1))
        print(json.dumps({k: s[k] for k in ("arm", "bundle_set_union",
                                            "abstention_rate")}, indent=1),
              flush=True)
    (OUT_DIR / "bridge_summaries.json").write_text(json.dumps(summaries, indent=1))
    print("wrote", OUT_DIR / "bridge_summaries.json")


# ---------------------------------------------------------------------------
# Probe / BFCL / latency
# ---------------------------------------------------------------------------

def cmd_probe(which):
    """Lint-probe re-run at menus 8/16/32/64. `stock` = native serving
    (1024, tools truncate at 16+). `stock-pi` = the UNTRAINED control at
    the trained model's exact serving config (scale 2 @2048 for <=32,
    zero-shot scale 4 @4096 for 64) — isolates finetune vs interpolation.
    `trained` = the extension checkpoint at the same config."""
    sizes = (7, 15, 31, 63)
    for tag in which:
        if tag == "stock":
            model = load_model()
            enc_by = {7: 1024, 15: 1024, 31: 1024, 63: 1024}
        elif tag == "stock-pi":
            model = load_model()
            enc_by = {7: 2048, 15: 2048, 31: 2048, 63: 4096}
        else:
            model = load_extended()
            enc_by = {7: 2048, 15: 2048, 31: 2048, 63: 4096}
        tokenizer = load_tokenizer()
        for nd in sizes:
            if tag in ("stock-pi", "trained"):
                model.config.enc_rope_scale = 4.0 if nd == 63 else 2.0
            cmd_run(menu_sizes=(nd,), model=model, tokenizer=tokenizer,
                    max_enc_by_size=enc_by, tag=tag,
                    out_name=f"probe-{tag}-menu{nd + 1}.json")
        del model
        mx.clear_cache()


def cmd_bfcl(which):
    for tag in which:
        if tag == "stock":
            model, max_enc = load_model(), 1024
        else:
            model, max_enc = load_extended(), 2048
        tokenizer = load_tokenizer()
        cmd_calibrate(model=model, tokenizer=tokenizer, max_enc_len=max_enc,
                      out_name=f"bfcl-{tag}.json", tag=tag)
        del model
        mx.clear_cache()


def cmd_latency():
    from seon_needle.model import make_padding_mask
    tokenizer = load_tokenizer()
    results = {}
    for tag, mk in (("stock", load_model), ("trained", load_extended)):
        model = mk()
        for L, scale in ((1024, 1.0), (2048, 2.0), (4096, 4.0)):
            if tag == "stock" and L > 1024:
                continue
            model.config.enc_rope_scale = scale if tag == "trained" else 1.0
            enc = mx.random.randint(10, 8000, (1, L), dtype=mx.int32)
            src_mask = make_padding_mask(enc, 0)
            # warmup + timed prefill
            out, m = model.encode(enc, src_mask=src_mask)
            mx.eval(out)
            t0 = time.perf_counter()
            for _ in range(5):
                out, m = model.encode(enc, src_mask=src_mask)
                mx.eval(out)
            prefill_s = (time.perf_counter() - t0) / 5
            # decode 64 tokens steady-state
            caches = model.new_caches()
            tokens = mx.full((1, 1), 1, dtype=mx.int32)
            t0 = time.perf_counter()
            for pos in range(64):
                logits = model.decode(tokens, out, cross_mask=m,
                                      offset=pos, caches=caches)
                tokens = mx.argmax(logits[:, -1, :], axis=-1)[:, None].astype(mx.int32)
                mx.eval(tokens)
            decode_s = time.perf_counter() - t0
            results[f"{tag}@{L}"] = {
                "prefill_tok_s": round(L / prefill_s),
                "prefill_ms": round(prefill_s * 1e3, 1),
                "decode_tok_s": round(64 / decode_s),
            }
            print(f"{tag}@{L}: prefill {L / prefill_s:,.0f} tok/s "
                  f"({prefill_s * 1e3:.1f} ms), decode {64 / decode_s:.0f} tok/s",
                  flush=True)
        del model
        mx.clear_cache()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "latency.json").write_text(json.dumps(results, indent=1))


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "bridge"
    rest = sys.argv[2:]
    if cmd == "bridge":
        cmd_bridge(rest or list(ARMS))
    elif cmd == "probe":
        cmd_probe(rest or ["stock", "stock-pi", "trained"])
    elif cmd == "bfcl":
        cmd_bfcl(rest or ["stock", "trained"])
    elif cmd == "latency":
        cmd_latency()
    else:
        raise SystemExit(f"unknown command {cmd!r}")


if __name__ == "__main__":
    main()
