#!/usr/bin/env python3
"""LoRA data audit step 1 — build the REPL-eval manifest from the drafts file.

Reads data/tune/drafts-deepseek-2026-07-12.jsonl (all 685 rows, kept AND
rejected) and, for every KEPT pair (the rows that became train/valid), emits
one manifest row telling the CLJS harness (test/seon/needle_lora_audit_test.cljs,
run in the PINNED worktree) exactly how to stage a hermetic world and eval the
target:

  - extra agents to create (self + any worker/agent ids the situation implies)
  - plan tx-data (EDN) parsed from the situation's plan block render grammar
    (root header + frontier glyph lines + plan-ids referenced only by
    transcript echoes / target plan calls)
  - transcript echo forms to replay (the situation's own claimed history),
    with parse defects recorded (the unbalanced-echo generator bug)
  - target forms (byte-exact slices via bb lora_forms.clj), each with the
    head resolved under the REAL home-ns rules and a skip flag for
    effectful namespaces (shell/web/test-runner) that must not execute

Usage:  python3 src-needle/scripts/lora_audit_manifest.py
Output: src-needle/data/lora/audit-manifest.jsonl  (gitignored, derived)

Static head-resolution logic mirrors seon.agent.home/home-ns-require-specs
(pin 93c8d8ad): aliases message/agent/schema/db/plan; refers wait complete
pause resume terminate. data/fs/blob aliases exist ONLY when the pair's
transcript replays the fresh-stage require echo (or the target's own
gold-prefix require runs first).
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
DRAFTS = REPO / "data/tune/drafts-deepseek-2026-07-12.jsonl"
SITS = REPO / "src-needle/data/lora/situations.jsonl"
OUT = REPO / "src-needle/data/lora/audit-manifest.jsonl"

ID_RE = re.compile(r"[A-Za-z0-9]{3}-26\d{8}")

# seon.agent.home/home-ns-require-specs (pin 93c8d8ad) — the aliases every
# agent home ns is wired with, plus the refers.
HOME_ALIASES = {
    "message": "seon.agent.message",
    "agent": "seon.agent",
    "schema": "seon.schema",
    "db": "seon.db",
    "plan": "my.plan",
}
HOME_REFERS = {"wait", "complete", "pause", "resume", "terminate"}
# the fresh-stage gold-prefix require adds these (only live once that
# require has evaluated in the agent ns):
PREFIX_ALIASES = {
    "data": "my.data",
    "fs": "seon.agent.fs",
    "blob": "my.blob",
}
# namespaces whose fns must NOT execute in the audit process (external
# effects: subprocesses, network, the shared cljs.test continuation).
EFFECTFUL_NSES = {
    "seon.agent.shell", "my.shell",
    "seon.agent.web", "my.web",
    "seon.test.runner",
    "seon.agent.subagents", "my.subagents",
}
NS_MOVE = {"in-ns", "ns", "require", "use", "refer", "load-file"}


def bb_analyze(items):
    """[(sid, text)] -> {sid: analysis} via the repo's own edamame analyzer."""
    payload = json.dumps([{"sid": k, "text": v} for k, v in items])
    proc = subprocess.run(["bb", str(SCRIPTS / "lora_forms.clj")],
                          input=payload, capture_output=True, text=True,
                          check=True)
    return {r["sid"]: r for r in json.loads(proc.stdout)}


def valid_split(sid):
    key = hashlib.sha1(json.dumps(sid).encode()).hexdigest()
    return int(key[:8], 16) % 20 == 0


def edn_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def mint_root_id(sid):
    """Deterministic 14-char id (XXX-26########) that can't collide with a
    context id (context ids never start 'AU')."""
    h = hashlib.sha1(sid.encode()).hexdigest()
    digits = "".join(c for c in h if c.isdigit()) + "0000000000"
    return f"AUd-26{digits[:8]}"


def ts_to_inst(ts):
    """'2026-07-13 02:08' -> '#inst \"2026-07-13T02:08:00.000-00:00\"'."""
    m = re.match(r"(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2})", ts or "")
    if not m:
        return '#inst "2026-07-12T00:00:00.000-00:00"'
    return f'#inst "{m.group(1)}T{m.group(2)}:00.000-00:00"'


# ---------------------------------------------------------------------------
# plan block parsing — the render grammar is regular
# ---------------------------------------------------------------------------

STEP_LINE = re.compile(
    r"^; ([▶☐]) (?:✉ )?([A-Za-z0-9]{3}-26\d{8}) \[([^\]⟨]*)\]?\s*(.*)$")
HEADER_LINE = re.compile(
    r"^; → (NOW \(active\)|next ready): ([A-Za-z0-9]{3}-26\d{8}) «(.*?)»")
ROOT_LINE = re.compile(r"^; PLAN «(.*?)»(?: — goal: (.*))?$")


def parse_plan_block(context):
    """-> {root_title, root_goal, steps: [{id,status,ts,title}]} or None."""
    m = re.search(r";;; ┌─ plan ─\n(.*?)\n;;; └─ end plan ─", context, re.S)
    if not m:
        return None
    root_title = root_goal = None
    steps, seen = [], set()
    for line in m.group(1).split("\n"):
        rm = ROOT_LINE.match(line)
        if rm:
            root_title, root_goal = rm.group(1), rm.group(2)
            continue
        hm = HEADER_LINE.match(line)
        if hm:
            status = "active" if hm.group(1).startswith("NOW") else "open"
            sid_, title = hm.group(2), hm.group(3)
            if sid_ not in seen:
                seen.add(sid_)
                steps.append({"id": sid_, "status": status, "ts": None,
                              "title": title.rstrip(" …") or "step"})
            continue
        sm = STEP_LINE.match(line)
        if sm:
            glyph, sid_, ts, title = sm.groups()
            status = "active" if glyph == "▶" else "open"
            if sid_ in seen:
                # glyph line refines a header-derived stub
                for s in steps:
                    if s["id"] == sid_:
                        s.update({"status": status, "ts": ts,
                                  "title": (title.strip() or s["title"])})
                continue
            seen.add(sid_)
            steps.append({"id": sid_, "status": status, "ts": ts,
                          "title": title.strip() or "step"})
    if root_title is None and not steps:
        return None
    return {"root_title": root_title, "root_goal": root_goal, "steps": steps}


# ---------------------------------------------------------------------------
# transcript echo extraction
# ---------------------------------------------------------------------------

def transcript_lines(context):
    m = re.search(r";;; ┌─ transcript ─\n(.*?)\n;;; └─ end transcript ─",
                  context, re.S)
    return m.group(1).split("\n") if m else []


def extract_echoes(context):
    """Echoed eval segments (text before their ⟹ result marker), in order.

    Grammar observed in the generated transcripts: an echoed eval starts at
    a column-0 '(' line and its result marker '⟹' appears either inline
    (') ⟹ …') or at the start of the following line. User messages are
    quoted blocks opened by ';;; ◀ from user … — "' and closed by a line
    ending with '\"'."""
    lines = transcript_lines(context)
    echoes, buf, i, in_msg = [], [], 0, False
    while i < len(lines):
        line = lines[i]
        if in_msg:
            if line.rstrip().endswith('"'):
                in_msg = False
            i += 1
            continue
        if line.startswith(";;; ◀ from"):
            body = line.split(" — ", 1)
            rest = body[1] if len(body) > 1 else ""
            if not (rest.startswith('"') and len(rest) > 1
                    and rest.rstrip().endswith('"')):
                in_msg = True
            i += 1
            continue
        if buf:
            if "⟹" in line:
                pre = line.split("⟹", 1)[0].rstrip()
                if pre:
                    buf.append(pre)
                echoes.append("\n".join(buf))
                buf = []
            elif line.startswith(";"):
                # comment interrupted an unterminated echo — flush as-is
                echoes.append("\n".join(buf))
                buf = []
            else:
                buf.append(line)
            i += 1
            continue
        if line.startswith("("):
            if "⟹" in line:
                echoes.append(line.split("⟹", 1)[0].rstrip())
            else:
                buf = [line]
        i += 1
    if buf:
        echoes.append("\n".join(buf))
    return echoes


def balance_repair(text):
    """Candidate repairs for the unbalanced-echo generator bug (an EXTRA
    close-delimiter inside the trailing closer run, e.g. `}]}})`): try
    deleting each single closer in the trailing run, then pairs."""
    t = text.rstrip()
    run = 0
    while run < len(t) and t[-1 - run] in ")}]":
        run += 1
    cands, n = [], 0
    for i in range(1, run + 1):  # delete ONE closer, i-th from the end
        n += 1
        cands.append((n, t[:len(t) - i] + t[len(t) - i + 1:]))
    for i in range(1, run + 1):  # delete TWO adjacent closers
        for j in range(i + 1, min(run, i + 2) + 1):
            n += 1
            s = t[:len(t) - j] + t[len(t) - j + 1:]
            s = s[:len(s) - i] + s[len(s) - i + 1:]
            cands.append((n, s))
    return cands


# ---------------------------------------------------------------------------
# head resolution under the real home-ns rules (static mirror of eval)
# ---------------------------------------------------------------------------

def resolve_head(head_full, prefix_live):
    """-> (resolved_ns, kind) where kind ∈ resolvable|bare-unresolvable|
    core-shadow|ns-move; resolved_ns None for unresolvable/bare."""
    if "/" in head_full:
        ns, _name = head_full.rsplit("/", 1)
        if ns in HOME_ALIASES:
            return HOME_ALIASES[ns], "resolvable"
        if prefix_live and ns in PREFIX_ALIASES:
            return PREFIX_ALIASES[ns], "resolvable"
        if ns in PREFIX_ALIASES:
            return PREFIX_ALIASES[ns], "alias-not-wired"
        if "." in ns:  # fully-qualified real namespace
            return ns, "resolvable"
        return None, "alias-unknown"
    name = head_full
    if name in NS_MOVE:
        return None, "ns-move"
    if name in HOME_REFERS:
        return "seon.agent.lifecycle", "resolvable"
    return None, "bare"


def main():
    rows = [json.loads(l) for l in DRAFTS.read_text().splitlines()]
    sits = {json.loads(l)["sid"]: json.loads(l)
            for l in SITS.read_text().splitlines()}

    kept = [r for r in rows
            if r["meta"]["status"] in ("kept", "kept-repaired", "kept-mech")]
    abstain = [r for r in rows if r["meta"]["status"] == "abstain"]

    # bb-parse every target + every echo candidate in ONE batch
    items, echo_map = [], {}
    for r in kept:
        sid = r["meta"]["sid"]
        items.append((f"t::{sid}", r["target"]))
        for j, e in enumerate(extract_echoes(r["context"])):
            items.append((f"e::{sid}::{j}", e))
            echo_map.setdefault(sid, []).append(e)
    analyses = bb_analyze(items)

    # repair failed echoes with the trailing-closer trim, re-analyze
    repair_items = []
    for key, a in analyses.items():
        if key.startswith("e::") and not a["parsed"]:
            _, sid, j = key.split("::")
            for n, t in balance_repair(echo_map[sid][int(j)]):
                repair_items.append((f"r{n}::{key}", t))
    repaired = bb_analyze(repair_items) if repair_items else {}

    out, stats = [], Counter()
    for r in kept:
        meta = r["meta"]
        sid = meta["sid"]
        sit = sits.get(sid, {})
        context = r["context"]
        target = r["target"]
        ctx_ids = set(ID_RE.findall(context))
        notes = []

        ta = analyses[f"t::{sid}"]
        if not ta["parsed"]:
            notes.append(f"target-parse-error: {ta.get('error')}")
            stats["target-parse-error"] += 1
            # still exercise it: the harness evals the whole target string,
            # which fails at the reader exactly as the live REPL would.
            ta = {"parsed": False,
                  "forms": [{"src": target, "call": False, "head": None,
                             "kind": None, "ids": ID_RE.findall(target)}]}

        # does the pair's history wire the data/fs/blob aliases?
        prefix_live = bool(re.search(r"\(require '\[my\.plan", target)
                           or re.search(r"\(require '\[my\.plan", context))

        # --- target forms
        tforms = []
        plan_ids_needed = set()
        agent_ids_needed = set()
        for f in (ta.get("forms") or []):
            head = f.get("head") or {}
            full = head.get("full", "")
            resolved_ns, res_kind = (resolve_head(full, prefix_live)
                                     if f.get("call") else (None, "non-call"))
            skip = None
            if resolved_ns in EFFECTFUL_NSES:
                skip = f"effectful:{resolved_ns}"
            tforms.append({"src": f["src"], "head": full,
                           "kind": f.get("kind"),
                           "resolved_ns": resolved_ns,
                           "resolution": res_kind, "skip": skip,
                           "ids": f.get("ids") or []})
            if f.get("kind") == "plan" or resolved_ns == "my.plan":
                plan_ids_needed.update(f.get("ids") or [])
            for mobj in re.finditer(
                    r":seon\.agent/id\s+\"(?:my\.agent\.)?"
                    r"([A-Za-z0-9]{3}-26\d{8})\"", f["src"]):
                agent_ids_needed.add(mobj.group(1))

        # --- echoes (with repairs)
        echoes = []
        for j, e in enumerate(echo_map.get(sid, [])):
            key = f"e::{sid}::{j}"
            a = analyses[key]
            if a["parsed"]:
                echoes.append({"src": e, "repaired": False,
                               "parse_error": None})
            else:
                fixed = None
                for n, cand in balance_repair(e):
                    ra = repaired.get(f"r{n}::{key}")
                    if ra and ra["parsed"]:
                        fixed = cand
                        break
                echoes.append({"src": fixed if fixed else e,
                               "repaired": fixed is not None,
                               "parse_error": a.get("error")})
                stats["echo-unbalanced"] += 1
                notes.append(f"context-echo-unbalanced[{j}]: "
                             f"{(a.get('error') or '')[:80]}")
            # plan ids referenced by echoes must be staged too
            for pid in ID_RE.findall(e):
                if re.search(r"my\.plan/\w+!?", e) or "plan/" in e:
                    plan_ids_needed.add(pid)

        # --- plan staging
        plan = parse_plan_block(context)
        agent_id = meta["agent"]
        tx_maps = []
        root_id = None
        staged = set()
        if plan and (plan["steps"] or plan["root_title"]):
            if plan["root_title"]:
                root_id = mint_root_id(sid)
                tx_maps.append(
                    "{:my.plan/id %s :my.plan/title %s :my.plan/status :active"
                    " :my.plan/created-at %s :my.plan/agent [:seon.agent/id %s]%s}"
                    % (edn_str(root_id), edn_str(plan["root_title"] or "plan"),
                       ts_to_inst(None), edn_str(agent_id),
                       (" :my.plan/goal " + edn_str(plan["root_goal"]))
                       if plan["root_goal"] else ""))
            for s in plan["steps"]:
                staged.add(s["id"])
                parent = (" :my.plan/parent [:my.plan/id %s]" % edn_str(root_id)
                          if root_id else "")
                tx_maps.append(
                    "{:my.plan/id %s :my.plan/title %s :my.plan/status :%s"
                    " :my.plan/created-at %s :my.plan/agent [:seon.agent/id %s]%s}"
                    % (edn_str(s["id"]), edn_str(s["title"]), s["status"],
                       ts_to_inst(s["ts"]), edn_str(agent_id), parent))

        # worker/peer staging implied by the situation text
        workers = set(re.findall(r'worker "([A-Za-z0-9]{3}-26\d{8})"', context))
        for mobj in re.finditer(r"agent with id ([A-Za-z0-9]{3}-26\d{8})",
                                context):
            workers.add(mobj.group(1))
        agent_ids_needed |= workers
        agent_ids_needed.discard(agent_id)
        # a worker's plan root named in the message text
        for mobj in re.finditer(r"root ([A-Za-z0-9]{3}-26\d{8})", context):
            rid = mobj.group(1)
            if rid not in staged:
                staged.add(rid)
                owner = next(iter(workers), agent_id)
                tx_maps.append(
                    "{:my.plan/id %s :my.plan/title \"worker plan root\""
                    " :my.plan/status :active :my.plan/created-at %s"
                    " :my.plan/agent [:seon.agent/id %s]}"
                    % (edn_str(rid), ts_to_inst(None), edn_str(owner)))

        # plan ids referenced by echoes/target but absent from the block:
        # prior (already-done) steps — stage them :open so the history can
        # replay onto them. (Agent-id values are NOT plan steps.)
        for pid in sorted(plan_ids_needed - staged - agent_ids_needed
                          - {agent_id}):
            if pid not in ctx_ids:
                notes.append(f"id-ungrounded: {pid}")
                stats["id-ungrounded"] += 1
                continue
            parent = (" :my.plan/parent [:my.plan/id %s]" % edn_str(root_id)
                      if root_id else "")
            tx_maps.append(
                "{:my.plan/id %s :my.plan/title \"prior step (restaged)\""
                " :my.plan/status :open :my.plan/created-at %s"
                " :my.plan/agent [:seon.agent/id %s]%s}"
                % (edn_str(pid), ts_to_inst(None), edn_str(agent_id), parent))

        out.append({
            "sid": sid, "split": "valid" if valid_split(sid) else "train",
            "status": meta["status"], "family": meta["family"],
            "stage": meta["stage"], "domain": meta.get("domain"),
            "agent": agent_id, "target": target,
            "current_ns": sit.get("current_ns") or f"my.agent.{agent_id}",
            "extra_agents": sorted(agent_ids_needed),
            "plan_tx": "[" + " ".join(tx_maps) + "]" if tx_maps else None,
            "echo_forms": echoes,
            "target_forms": tforms,
            "notes": notes,
        })
        stats[meta["status"]] += 1

    for r in abstain:
        meta = r["meta"]
        out.append({"sid": meta["sid"],
                    "split": "valid" if valid_split(meta["sid"]) else "train",
                    "status": "abstain", "family": meta["family"],
                    "stage": meta["stage"], "domain": meta.get("domain"),
                    "agent": meta["agent"],
                    "current_ns": f"my.agent.{meta['agent']}",
                    "extra_agents": [], "plan_tx": None, "echo_forms": [],
                    "target_forms": [], "notes": []})
        stats["abstain"] += 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w") as fh:
        for row in out:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"wrote {len(out)} rows -> {OUT}")
    print("stats:", dict(stats))
    n_skip = sum(1 for row in out for f in row["target_forms"] if f["skip"])
    n_forms = sum(len(row["target_forms"]) for row in out)
    print(f"target forms: {n_forms} ({n_skip} skipped as effectful)")
    res = Counter(f["resolution"] for row in out for f in row["target_forms"])
    print("head resolution:", dict(res))


if __name__ == "__main__":
    main()
