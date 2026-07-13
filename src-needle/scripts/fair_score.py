#!/usr/bin/env python3
"""Fair scorer driver (repl-autosuggest) — layered columns, no single hard gate.

Owner directive 2026-07-12: "make sure the judges are fair and we are
encouraging creative solutions and not just hard gating exact text."
Design + acceptance results:
docs/prds/repl-autosuggest/research/fair-scoring-2026-07-12.md

Layers (every column reported; nothing hides inside the headline):

  L0 parse    prediction reads (edamame; the existing :parsed).
  L1 valid    every substantive call's head is a real fn from the model's
              visible world AND its map-arg key names are inside the fn's
              known request keys (from the index arglists) — report-only.
  L2 eval     the prediction's forms evaluate against the row's STAGED
              world (the data-audit lane's harness,
              src-needle/audit/seon/needle_lora_audit_test.cljs, run in the
              PINNED worktree). Hard eval failures (throws, unresolvable
              symbols) count against; error ENVELOPES do not (errors are
              values — the system's own history is full of them).
              SELF-CALIBRATING GATE: rows where the historical TARGET
              itself hard-fails in the staged world are staging defects by
              construction (the target ran in the real world) — those rows
              are UNGATED (multiplier 1.0, reported).
  L3 productive  the mechanical prescribed-act accept-set, STATE-GUARDED
              per the FN audit (kt3_score.clj :fair block): active! on the
              plan block's next-ready/✉-open id when nothing is ▶ active;
              done! on the ▶ step; plan read probes on context-grounded
              ids; PLUS eval-confirmed effect-advances (fresh register! /
              transact! that store schema-valid data in the staged world).
              Bundle purity: ANY void call (hallucinated head, ungrounded
              id, guard-violating plan mutation, re-register, failed
              write) suppresses L3 for the row.
  L4 history  the existing set-union best-match F1 vs the mined bundle
              (scoring v2) — kept for cross-day comparability, demoted
              from headline.

  HEADLINE  fair_useful = gate × max(L4, L3-credit)
    gate      = prediction's hard-clean form fraction on GATED rows, 1.0
                on ungated rows (and rows without eval evidence).
    L3-credit = 2p/(1+p) with p = accepted/substantive calls (recall
                treated as 1: the prescribed act IS a complete useful
                suggestion), 0 unless (accepted ≥ 1 and voids = 0).

Usage (from the repo root; harness runs from the PINNED worktree):

  python3 src-needle/scripts/fair_score.py manifest [--shards N]
  python3 src-needle/scripts/fair_score.py run --shard K   # node harness
  python3 src-needle/scripts/fair_score.py score [--arms a,b]
  python3 src-needle/scripts/fair_score.py acceptance
  python3 src-needle/scripts/fair_score.py selftest
  python3 src-needle/scripts/fair_score.py report

Outputs under src-needle/data/fair/ (gitignored, derived):
  manifest-K.jsonl / harness-out-K.jsonl / evalmap.json
  scored-fair-<arm>.json / summary-fair-<arm>.json / report.md
"""

import argparse
import hashlib
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import lora_audit_manifest as lam  # the staging machinery — REUSED, not forked

REPO = SCRIPTS.parents[1]
PIN = Path("/Users/sean/src/seon-pin")
V1 = REPO / "data/tune/acme-2026-07-12.jsonl"
V2 = REPO / "data/tune/acme-2026-07-12-v2.jsonl"
FN_INDEX = REPO / "src-needle/data/fn-index.json"
DATA = REPO / "src-needle/data"
FAIR = DATA / "fair"
SCORER = SCRIPTS / "kt3_score.clj"

JUNK_V1 = {31, 179, 184}          # prose-as-calls targets (FN audit hygiene)
CARD_NAME_RE = re.compile(r"^\(defn ([^\s]+) ")

# The FN audit's 40 judged cases (src-needle/data/fn-audit/) — the
# acceptance contract. RA cases MUST score >0; the instr-few 14 MUST stay 0.
AUDIT_ARM_TO_FAIR = {
    "deepseek": "kt3:deepseek",
    "instr-few": "kt3b:qwen25c-1.5b-instr-instr-few",
    "starcoder2-cont": "kt3b:starcoder2-3b-cont",
}
RA_CASES = {"deepseek:1", "deepseek:2", "deepseek:27", "deepseek:31",
            "deepseek:42", "deepseek:128", "deepseek:184", "deepseek:187"}
MUST_ZERO_CASES = {  # the 14 judged instr-few zeros — all non-RA
    "instr-few:0", "instr-few:47", "instr-few:54", "instr-few:59",
    "instr-few:81", "instr-few:94", "instr-few:99", "instr-few:110",
    "instr-few:127", "instr-few:177", "instr-few:180", "instr-few:184",
    "instr-few:193", "instr-few:194"}
REPORT_ZERO_CASES = {  # starcoder2 six — judged real errors, report-only
    "starcoder2-cont:36", "starcoder2-cont:37", "starcoder2-cont:63",
    "starcoder2-cont:79", "starcoder2-cont:117", "starcoder2-cont:196"}


# ---------------------------------------------------------------------------
# datasets / arms
# ---------------------------------------------------------------------------

def load_jsonl(p):
    return [json.loads(l) for l in p.read_text().splitlines() if l.strip()]


def load_datasets():
    v1 = load_jsonl(V1)
    v2 = load_jsonl(V2)
    return v1, v2


def discover_arms():
    """Every raw preds file on disk. The kt3redux `-4card` rescore arms have
    no preds files of their own (they rescore the kt3b predictions) — the
    kt3b arms cover them."""
    arms = {}
    for sub in ("kt3", "kt3b", "kt3redux"):
        d = DATA / sub
        if not d.is_dir():
            continue
        for p in sorted(d.glob("preds-*.jsonl")):
            name = p.stem[len("preds-"):]
            arms[f"{sub}:{name}"] = {
                "preds": p,
                "dataset": "v2" if sub == "kt3redux" else "v1",
            }
    return arms


def card_names(row):
    return [m.group(1) for c in row["cards"]
            for m in [CARD_NAME_RE.match(c)] if m]


def v1_row_of(dataset, row_idx, v2rows):
    """Map a row id to its v1 row index (staging is keyed by v1 row)."""
    if dataset == "v1":
        return row_idx
    return v2rows[row_idx]["meta"]["v1_row"]


# ---------------------------------------------------------------------------
# plan-block state (the L3 guards) — same render grammar lam parses
# ---------------------------------------------------------------------------

STEP_GLYPH_RE = re.compile(r"^; ([▶☐]) (✉ )?([A-Za-z0-9]{3}-26\d{8})")


def plan_state(context):
    m = re.search(r";;; ┌─ plan ─\n(.*?)\n;;; └─ end plan ─", context, re.S)
    next_ready, active, msg_open = None, [], []
    if m:
        for line in m.group(1).split("\n"):
            hm = lam.HEADER_LINE.match(line)
            if hm:
                if hm.group(1) == "next ready":
                    next_ready = hm.group(2)
                else:  # NOW (active)
                    active.append(hm.group(2))
                continue
            sm = STEP_GLYPH_RE.match(line)
            if sm:
                if sm.group(1) == "▶":
                    active.append(sm.group(3))
                elif sm.group(2):
                    msg_open.append(sm.group(3))
    return {"next-ready": next_ready,
            "active-ids": sorted(set(active)),
            "msg-open-ids": sorted(set(msg_open))}


# ---------------------------------------------------------------------------
# staging — one hermetic world spec per v1 row, from the row's own context
# (assembly mirrors lam.main()'s kept-row path, driven by lam's parsers)
# ---------------------------------------------------------------------------

def build_staging(v1rows):
    """{v1_row: {agent, current_ns, extra_agents, plan_tx, echo_forms}}."""
    items = []
    echo_map = {}
    for i, r in enumerate(v1rows):
        items.append((f"t::{i}", r["target"]))
        for j, e in enumerate(lam.extract_echoes(r["context"])):
            items.append((f"e::{i}::{j}", e))
            echo_map.setdefault(i, []).append(e)
    analyses = lam.bb_analyze(items)

    repair_items = []
    for key, a in analyses.items():
        if key.startswith("e::") and not a["parsed"]:
            _, i, j = key.split("::")
            for n, t in lam.balance_repair(echo_map[int(i)][int(j)]):
                repair_items.append((f"r{n}::{key}", t))
    repaired = lam.bb_analyze(repair_items) if repair_items else {}

    staging = {}
    for i, r in enumerate(v1rows):
        context, target = r["context"], r["target"]
        agent_id = r["meta"]["agent"]
        sid = f"row{i}"
        ctx_ids = set(lam.ID_RE.findall(context))
        notes = []

        ta = analyses[f"t::{i}"]
        plan_ids_needed, agent_ids_needed = set(), set()
        if ta["parsed"]:
            for f in ta.get("forms") or []:
                if f.get("kind") == "plan":
                    plan_ids_needed.update(f.get("ids") or [])
                for mo in re.finditer(
                        r":seon\.agent/id\s+\"(?:my\.agent\.)?"
                        r"([A-Za-z0-9]{3}-26\d{8})\"", f["src"]):
                    agent_ids_needed.add(mo.group(1))

        echoes = []
        for j, e in enumerate(echo_map.get(i, [])):
            a = analyses[f"e::{i}::{j}"]
            if a["parsed"]:
                echoes.append({"src": e})
            else:
                fixed = None
                for n, cand in lam.balance_repair(e):
                    ra = repaired.get(f"r{n}::e::{i}::{j}")
                    if ra and ra["parsed"]:
                        fixed = cand
                        break
                if fixed:
                    echoes.append({"src": fixed})
                else:
                    notes.append(f"echo-dropped[{j}]: {(a.get('error') or '')[:60]}")
            for pid in lam.ID_RE.findall(e):
                if "plan/" in e:
                    plan_ids_needed.add(pid)

        plan = lam.parse_plan_block(context)
        tx_maps, staged, root_id = [], set(), None
        if plan and (plan["steps"] or plan["root_title"]):
            if plan["root_title"]:
                root_id = lam.mint_root_id(sid)
                tx_maps.append(
                    "{:my.plan/id %s :my.plan/title %s :my.plan/status :active"
                    " :my.plan/created-at %s :my.plan/agent [:seon.agent/id %s]%s}"
                    % (lam.edn_str(root_id),
                       lam.edn_str(plan["root_title"] or "plan"),
                       lam.ts_to_inst(None), lam.edn_str(agent_id),
                       (" :my.plan/goal " + lam.edn_str(plan["root_goal"]))
                       if plan["root_goal"] else ""))
            for s in plan["steps"]:
                staged.add(s["id"])
                parent = (" :my.plan/parent [:my.plan/id %s]"
                          % lam.edn_str(root_id) if root_id else "")
                tx_maps.append(
                    "{:my.plan/id %s :my.plan/title %s :my.plan/status :%s"
                    " :my.plan/created-at %s :my.plan/agent [:seon.agent/id %s]%s}"
                    % (lam.edn_str(s["id"]), lam.edn_str(s["title"]),
                       s["status"], lam.ts_to_inst(s["ts"]),
                       lam.edn_str(agent_id), parent))

        workers = set(re.findall(
            r'[Ww]orker agent id is "([A-Za-z0-9]{3}-26\d{8})"', context))
        workers |= set(re.findall(r'worker "([A-Za-z0-9]{3}-26\d{8})"', context))
        for mo in re.finditer(r"agent with id ([A-Za-z0-9]{3}-26\d{8})", context):
            workers.add(mo.group(1))
        agent_ids_needed |= workers
        agent_ids_needed.discard(agent_id)
        for mo in re.finditer(r"root ([A-Za-z0-9]{3}-26\d{8})", context):
            rid = mo.group(1)
            if rid not in staged:
                staged.add(rid)
                owner = next(iter(sorted(workers)), agent_id)
                tx_maps.append(
                    "{:my.plan/id %s :my.plan/title \"worker plan root\""
                    " :my.plan/status :active :my.plan/created-at %s"
                    " :my.plan/agent [:seon.agent/id %s]}"
                    % (lam.edn_str(rid), lam.ts_to_inst(None),
                       lam.edn_str(owner)))

        for pid in sorted(plan_ids_needed - staged - agent_ids_needed
                          - {agent_id}):
            if pid not in ctx_ids:
                notes.append(f"id-ungrounded: {pid}")
                continue
            parent = (" :my.plan/parent [:my.plan/id %s]"
                      % lam.edn_str(root_id) if root_id else "")
            tx_maps.append(
                "{:my.plan/id %s :my.plan/title \"prior step (restaged)\""
                " :my.plan/status :open :my.plan/created-at %s"
                " :my.plan/agent [:seon.agent/id %s]%s}"
                % (lam.edn_str(pid), lam.ts_to_inst(None),
                   lam.edn_str(agent_id), parent))

        staging[i] = {
            "agent": agent_id,
            "current_ns": f"my.agent.{agent_id}",
            "extra_agents": sorted(agent_ids_needed),
            "plan_tx": "[" + " ".join(tx_maps) + "]" if tx_maps else None,
            "echo_forms": echoes,
            "notes": notes,
        }
    return staging


# ---------------------------------------------------------------------------
# effectful-form stripping (the data-audit lane's skip policy: external
# effects must not execute in the audit process; static scoring still sees
# the full text — only the staged EVAL skips them)
# ---------------------------------------------------------------------------

EFFECTFUL_SUFFIXES = {"shell", "web", "subagents"}


def effectful_head(head_full):
    if not head_full:
        return False
    resolved, _kind = lam.resolve_head(head_full, prefix_live=True)
    if resolved in lam.EFFECTFUL_NSES:
        return True
    ns = head_full.rsplit("/", 1)[0] if "/" in head_full else ""
    return (ns.split(".")[-1] in EFFECTFUL_SUFFIXES
            or head_full.startswith("seon.test."))


def strip_effectful(texts):
    """{text: (eval_text, stripped_form_indices)} for every unique text."""
    uniq = sorted(set(texts))
    analyses = {}
    for chunk_start in range(0, len(uniq), 400):
        chunk = uniq[chunk_start:chunk_start + 400]
        analyses.update(lam.bb_analyze(
            [(f"s::{chunk_start + j}", t) for j, t in enumerate(chunk)]))
        for j, t in enumerate(chunk):
            analyses[t] = analyses.pop(f"s::{chunk_start + j}")
    out = {}
    for t in uniq:
        a = analyses[t]
        if not a["parsed"]:
            out[t] = (t, [])
            continue
        stripped = [i for i, f in enumerate(a.get("forms") or [])
                    if f.get("call") and effectful_head(
                        (f.get("head") or {}).get("full"))]
        if not stripped:
            out[t] = (t, [])
        else:
            kept = [f["src"] for i, f in enumerate(a["forms"])
                    if i not in set(stripped)]
            out[t] = ("\n".join(kept), stripped)
    return out


# ---------------------------------------------------------------------------
# manifest
# ---------------------------------------------------------------------------

def sid_for(v1_row, text):
    h = hashlib.sha1(text.encode()).hexdigest()[:10]
    return f"r{v1_row}-{h}"


def cmd_manifest(args):
    v1, v2 = load_datasets()
    arms = discover_arms()
    FAIR.mkdir(parents=True, exist_ok=True)

    print(f"staging {len(v1)} worlds from row contexts …", flush=True)
    staging = build_staging(v1)
    n_notes = sum(len(s["notes"]) for s in staging.values())
    print(f"staging notes (dropped echoes / ungrounded ids): {n_notes}")

    texts = []
    arm_rows = {}
    for arm, spec in arms.items():
        preds = load_jsonl(spec["preds"])
        arm_rows[arm] = preds
        for d in preds:
            texts.append(d["clean"])
    print(f"analyzing {len(set(texts))} unique prediction texts "
          f"({len(texts)} total) …", flush=True)
    stripped_of = strip_effectful(texts)
    n_str = sum(1 for _, s in stripped_of.values() if s)
    print(f"texts with effectful forms stripped: {n_str}")

    jobs = {}
    evalmap = {"targets": {}, "arms": {}, "stripped": {}}
    for i, r in enumerate(v1):
        sid = f"t{i}"
        evalmap["targets"][str(i)] = sid
        jobs[sid] = dict(staging[i], sid=sid, status="fair",
                         target=r["target"])
    for arm, spec in arms.items():
        v2rows = v2 if spec["dataset"] == "v2" else None
        amap = {}
        for d in arm_rows[arm]:
            v1r = v1_row_of(spec["dataset"], d["id"], v2rows)
            eval_text, stripped = stripped_of[d["clean"]]
            sid = sid_for(v1r, eval_text)
            amap[str(d["id"])] = sid
            if sid not in jobs:
                jobs[sid] = dict(staging[v1r], sid=sid, status="fair",
                                 target=eval_text)
                if stripped:
                    evalmap["stripped"][sid] = stripped
        evalmap["arms"][arm] = amap

    shards = args.shards
    files = [open(FAIR / f"manifest-{k}.jsonl", "w") for k in range(shards)]
    for n, sid in enumerate(sorted(jobs)):
        row = jobs[sid]
        row.pop("notes", None)
        files[n % shards].write(json.dumps(row, ensure_ascii=False) + "\n")
    for f in files:
        f.close()
    (FAIR / "evalmap.json").write_text(json.dumps(evalmap))
    print(f"wrote {len(jobs)} eval jobs -> {shards} manifest shards "
          f"({len(arms)} arms + {len(v1)} targets, deduped)")


# ---------------------------------------------------------------------------
# harness run (the PINNED worktree — owner stability ruling 2026-07-12)
# ---------------------------------------------------------------------------

def cmd_run(args):
    k = args.shard
    manifest = FAIR / f"manifest-{k}.jsonl"
    out = FAIR / f"harness-out-{k}.jsonl"
    env = {
        "SEON_LORA_AUDIT": "1",
        "SEON_CONFIG": "config/test.edn",
        "SEON_LORA_AUDIT_MANIFEST": str(manifest),
        "SEON_LORA_AUDIT_OUT": str(out),
    }
    if args.limit:
        env["SEON_LORA_AUDIT_LIMIT"] = str(args.limit)
    import os
    full_env = dict(os.environ, **env)
    print(f"shard {k}: {manifest} -> {out} (cwd {PIN})", flush=True)
    subprocess.run(["node", "out/test/test.js"], cwd=PIN, env=full_env,
                   check=False)


# ---------------------------------------------------------------------------
# scoring + finalization
# ---------------------------------------------------------------------------

def load_harness_results():
    res = {}
    for p in sorted(FAIR.glob("harness-out-*.jsonl")):
        for l in p.read_text().splitlines():
            if not l.strip():
                continue
            d = json.loads(l)
            res[d["sid"]] = d
    return res


def bb_score(rows_payload, index_syms, index_arglists):
    payload = {"index-syms": index_syms,
               "index-arglists": index_arglists,
               "rows": rows_payload}
    proc = subprocess.run(["bb", str(SCORER)], input=json.dumps(payload),
                          capture_output=True, text=True, check=True)
    return json.loads(proc.stdout)


def target_gated(tres):
    """True when the historical target evals hard-clean in the staged world
    (envelope refusals allowed) — the row's L2 gate is then trustworthy."""
    if not tres or tres.get("crash") or not tres.get("forms"):
        return False
    return all(f["ok"] for f in tres["forms"])


def finalize_row(s, hres, tres, stripped):
    fair = s.get("fair") or {}
    gated = target_gated(tres)
    pforms = None
    if hres and not hres.get("crash"):
        pforms = hres.get("forms") or []
    aligned = None
    if pforms is not None and not fair.get("parse-fail"):
        kept = [i for i in range(len(fair.get("forms", [])))
                if i not in set(stripped)]
        if len(kept) == len(pforms):
            aligned = dict(zip(kept, pforms))

    counts = Counter()
    calls = []
    for c in fair.get("calls", []):
        cl, why = c["class"], c.get("why")
        if cl in ("pending-register", "pending-transact",
                  "pending-plan-write"):
            fv = aligned.get(c["form-idx"]) if aligned else None
            if fv is None:
                cl, why = "neutral", "eval-unaligned"
            else:
                ok = bool(fv["ok"] and not fv.get("envFail"))
                # KNOWN HARNESS GAP (reported to the data-audit lane): the
                # eval-boundary :seon.agent/id injection does not reach
                # every plan write in the staged harness — that envelope is
                # the boundary's failure, not the model's. Inconclusive.
                boundary = "no :seon.agent/id resolved" in (
                    fv.get("envErr") or "")
                if cl == "pending-plan-write":
                    if ok:
                        cl, why = "neutral", "plan-write-clean"
                    elif boundary or not gated:
                        cl, why = "neutral", "eval-inconclusive"
                    else:
                        cl, why = "void", "plan-write-failed"
                elif ok:
                    cl, why = "accepted", "stores-valid"
                elif boundary or not gated:
                    # on UNGATED rows a failed write may be the staging's
                    # fault — inconclusive, not damning
                    cl, why = "neutral", "eval-inconclusive"
                else:
                    cl, why = "void", "write-failed"
        c = dict(c, **{"class": cl})
        if why:
            c["why"] = why
        calls.append(c)
        if cl != "ns-move":
            counts[cl] += 1

    acc, void, neut = counts["accepted"], counts["void"], counts["neutral"]
    subst = acc + void + neut
    l3_fired = acc > 0 and void == 0
    p = acc / subst if subst else 0.0
    l3_credit = (2 * p / (1 + p)) if l3_fired else 0.0

    if pforms:
        l2_frac = sum(1 for f in pforms if f["ok"]) / len(pforms)
        l2_pass = all(f["ok"] for f in pforms)
        l2_env = sum(1 for f in pforms if f.get("envFail"))
    else:
        l2_frac, l2_pass, l2_env = None, None, None

    gate = l2_frac if (gated and l2_frac is not None) else 1.0
    l4 = s["useful"]
    fair_useful = round(gate * max(l4, l3_credit), 4)

    l1 = fair.get("l1") or {}
    return {
        "id": s["id"],
        "l0_parsed": s["parsed"],
        "l1_all_valid": bool(l1.get("all-valid")) if l1.get("total") else None,
        "l1_frac": (round(l1["valid"] / l1["total"], 3)
                    if l1.get("total") else None),
        "l2_gated": gated,
        "l2_frac": l2_frac,
        "l2_pass": l2_pass,
        "l2_envfail_forms": l2_env,
        "l3_fired": l3_fired,
        "l3_credit": round(l3_credit, 4),
        "l3_counts": dict(counts),
        "l4_useful": l4,
        "fair_useful": fair_useful,
        "fair_calls": calls,
        "eval_crash": bool(hres and hres.get("crash")),
        "eval_missing": hres is None,
    }


def score_arm(arm, spec, v1, v2, harness, evalmap, index_syms,
              index_arglists):
    rows = v2 if spec["dataset"] == "v2" else v1
    target_key = "target_bundle" if spec["dataset"] == "v2" else "target"
    preds = {d["id"]: d for d in load_jsonl(spec["preds"])}
    ids = sorted(preds)
    payload = [{"id": i,
                "target": rows[i][target_key],
                "prediction": preds[i]["clean"],
                "context": rows[i]["context"],
                "card-names": card_names(rows[i]),
                "plan-state": plan_state(rows[i]["context"])}
               for i in ids]
    scored = bb_score(payload, index_syms, index_arglists)
    amap = evalmap["arms"][arm]
    out = []
    for s in scored:
        i = s["id"]
        v1r = v1_row_of(spec["dataset"], i, v2)
        sid = amap[str(i)]
        hres = harness.get(sid)
        tres = harness.get(evalmap["targets"][str(v1r)])
        stripped = evalmap["stripped"].get(sid, [])
        row = finalize_row(s, hres, tres, stripped)
        row["v1_row"] = v1r
        out.append(row)
    return out


def summarize_arm(arm, spec, rows_scored):
    clean = [r for r in rows_scored if r["v1_row"] not in JUNK_V1]
    n = len(clean)

    def mean(xs):
        xs = [x for x in xs if x is not None]
        return round(sum(xs) / len(xs), 3) if xs else None

    return {
        "arm": arm,
        "dataset": spec["dataset"],
        "n_scored": len(rows_scored),
        "n_clean": n,
        "parse_rate": mean([1.0 if r["l0_parsed"] else 0.0 for r in clean]),
        "l1_all_valid_rate": mean(
            [1.0 if r["l1_all_valid"] else 0.0 for r in clean
             if r["l1_all_valid"] is not None]),
        "l2_gated_rows": sum(1 for r in clean if r["l2_gated"]),
        "l2_pass_rate_gated": mean(
            [1.0 if r["l2_pass"] else 0.0 for r in clean
             if r["l2_gated"] and r["l2_pass"] is not None]),
        "l2_frac_mean": mean([r["l2_frac"] for r in clean]),
        "l3_fired_rate": mean([1.0 if r["l3_fired"] else 0.0 for r in clean]),
        "l4_useful_mean": mean([r["l4_useful"] for r in clean]),
        "fair_useful_mean": mean([r["fair_useful"] for r in clean]),
        "fair_ge_0.5": mean(
            [1.0 if r["fair_useful"] >= 0.5 else 0.0 for r in clean]),
        "delta_fair_minus_l4": (
            round(mean([r["fair_useful"] for r in clean])
                  - mean([r["l4_useful"] for r in clean]), 3)
            if n else None),
        "eval_crashes": sum(1 for r in clean if r["eval_crash"]),
        "eval_missing": sum(1 for r in clean if r["eval_missing"]),
    }


def cmd_score(args):
    v1, v2 = load_datasets()
    arms = discover_arms()
    if args.arms:
        keep = set(args.arms.split(","))
        arms = {k: v for k, v in arms.items() if k in keep}
    harness = load_harness_results()
    evalmap = json.loads((FAIR / "evalmap.json").read_text())
    idx = json.loads(FN_INDEX.read_text())["fns"]
    index_syms = [f["seon.fn/sym"] for f in idx]
    index_arglists = {f["seon.fn/sym"]: f.get("seon.fn/arglists") or "()"
                      for f in idx}
    print(f"harness results loaded: {len(harness)} sids")
    for arm, spec in arms.items():
        rows_scored = score_arm(arm, spec, v1, v2, harness, evalmap,
                                index_syms, index_arglists)
        safe = arm.replace(":", "-")
        (FAIR / f"scored-fair-{safe}.json").write_text(
            json.dumps(rows_scored, indent=1))
        summary = summarize_arm(arm, spec, rows_scored)
        (FAIR / f"summary-fair-{safe}.json").write_text(
            json.dumps(summary, indent=1))
        print(json.dumps(summary), flush=True)


# ---------------------------------------------------------------------------
# acceptance — the FN audit's judged cases + the identity self-test
# ---------------------------------------------------------------------------

def cmd_acceptance(_args):
    ok = True
    for audit_arm, fair_arm in AUDIT_ARM_TO_FAIR.items():
        safe = fair_arm.replace(":", "-")
        p = FAIR / f"scored-fair-{safe}.json"
        if not p.exists():
            print(f"MISSING scored file for {fair_arm} — run score first")
            ok = False
            continue
        by_id = {r["id"]: r for r in json.loads(p.read_text())}
        for key in sorted(RA_CASES | MUST_ZERO_CASES | REPORT_ZERO_CASES):
            arm, sid = key.rsplit(":", 1)
            if arm != audit_arm:
                continue
            r = by_id.get(int(sid))
            if r is None:
                print(f"{key:24s} NOT-SCORED (id absent from arm preds)")
                continue
            fu = r["fair_useful"]
            if key in RA_CASES:
                verdict = "PASS" if fu > 0 else "FAIL"
                want = ">0 (reasonable-alternative)"
            elif key in MUST_ZERO_CASES:
                verdict = "PASS" if fu == 0 else "FAIL"
                want = "=0 (real error)"
            else:
                verdict = "ok" if fu == 0 else "NOTE(credited)"
                want = "=0 (report-only)"
            if verdict == "FAIL":
                ok = False
            print(f"{key:24s} fair={fu:.3f}  want {want:34s} {verdict}")
    print("ACCEPTANCE:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def cmd_selftest(_args):
    """Every dataset target scored as its own prediction through the FULL
    fair path (static + staged-world gate) — non-junk rows must be 1.0."""
    v1, _ = load_datasets()
    harness = load_harness_results()
    evalmap = json.loads((FAIR / "evalmap.json").read_text())
    idx = json.loads(FN_INDEX.read_text())["fns"]
    index_syms = [f["seon.fn/sym"] for f in idx]
    index_arglists = {f["seon.fn/sym"]: f.get("seon.fn/arglists") or "()"
                      for f in idx}
    payload = [{"id": i, "target": r["target"], "prediction": r["target"],
                "context": r["context"], "card-names": card_names(r),
                "plan-state": plan_state(r["context"])}
               for i, r in enumerate(v1)]
    scored = bb_score(payload, index_syms, index_arglists)
    bad = []
    gated_n = 0
    for s in scored:
        i = s["id"]
        tres = harness.get(evalmap["targets"][str(i)])
        row = finalize_row(s, tres, tres, [])
        if row["l2_gated"]:
            gated_n += 1
        if i not in JUNK_V1 and row["fair_useful"] < 1.0:
            bad.append((i, row["fair_useful"], row["l2_frac"],
                        row["l4_useful"]))
    n_clean = len(scored) - len(JUNK_V1)
    print(f"self-test: {n_clean - len(bad)}/{n_clean} non-junk targets "
          f"fair=1.0 (gated rows: {gated_n}/{len(scored)})")
    for i, fu, l2, l4 in bad[:40]:
        print(f"  row {i}: fair={fu} l2_frac={l2} l4={l4}")
    print("SELF-TEST:", "PASS" if not bad else "FAIL")
    return 0 if not bad else 1


# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------

def cmd_report(_args):
    def fmt(x, nd=3):
        return "—" if x is None else f"{x:.{nd}f}".lstrip("0") or "0"

    summaries = []
    for p in sorted(FAIR.glob("summary-fair-*.json")):
        summaries.append(json.loads(p.read_text()))
    if not summaries:
        print("no fair summaries yet")
        return
    print("## Fair-scored day table (v2-display, fair-scored)\n")
    print("| arm | n | parse | L1 valid | L2 gated | L2 pass | L3 fired | "
          "L4 useful (old) | **FAIR useful** | fair ≥.5 | Δ fair−L4 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for s in summaries:
        print(f"| {s['arm']} | {s['n_clean']} | {fmt(s['parse_rate'])} | "
              f"{fmt(s['l1_all_valid_rate'])} | {s['l2_gated_rows']} | "
              f"{fmt(s['l2_pass_rate_gated'])} | {fmt(s['l3_fired_rate'])} | "
              f"{fmt(s['l4_useful_mean'])} | **{fmt(s['fair_useful_mean'])}** | "
              f"{fmt(s['fair_ge_0.5'])} | {fmt(s['delta_fair_minus_l4'])} |")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("manifest")
    m.add_argument("--shards", type=int, default=3)
    m.set_defaults(fn=cmd_manifest)
    r = sub.add_parser("run")
    r.add_argument("--shard", type=int, required=True)
    r.add_argument("--limit", type=int, default=0)
    r.set_defaults(fn=cmd_run)
    s = sub.add_parser("score")
    s.add_argument("--arms", default=None)
    s.set_defaults(fn=cmd_score)
    a = sub.add_parser("acceptance")
    a.set_defaults(fn=cmd_acceptance)
    t = sub.add_parser("selftest")
    t.set_defaults(fn=cmd_selftest)
    p = sub.add_parser("report")
    p.set_defaults(fn=cmd_report)
    args = ap.parse_args()
    rc = args.fn(args)
    sys.exit(rc or 0)


if __name__ == "__main__":
    main()
