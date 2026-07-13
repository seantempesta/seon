#!/usr/bin/env python3
"""LoRA data audit step 3 — classify REPL results + coverage/complexity stats.

Inputs:
  src-needle/data/lora/audit-manifest.jsonl   (step 1: staging + static heads)
  src-needle/data/lora/audit-results.jsonl    (step 2: the pin harness run)
  data/tune/drafts-deepseek-2026-07-12.jsonl  (targets + meta)
  data/tune/acme-2026-07-12.jsonl             (the mined held-out, for the
                                               form-kind distribution baseline)
  src-needle/data/fn-index.json               (the 168-fn surface)

Output: markdown-ready tables + verbatim failure exemplars on stdout, and
src-needle/data/lora/audit-summary.json with the stable numbers.

Pair classification (owner's taxonomy):
  eval-clean                all target forms ok, no envelope refusals, and the
                            pair needed NO situation staging beyond the world
  eval-clean-after-staging  same, but the pair needed staged plan/peer/echo state
  envelope-error            eval ok but a capability verb REFUSED (ok? false)
  eval-error                >=1 target form failed eval (undeclared var,
                            invalid args, bad query, read error, ...)
  id-ungrounded             target id not present in its own context
  unevaluable               harness crash / skipped-effectful (reported apart)
"""

import json
import re
import subprocess
from collections import Counter, defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "src-needle/scripts"
MANIFEST = REPO / "src-needle/data/lora/audit-manifest.jsonl"
RESULTS = REPO / "src-needle/data/lora/audit-results.jsonl"
DRAFTS = REPO / "data/tune/drafts-deepseek-2026-07-12.jsonl"
MINED = REPO / "data/tune/acme-2026-07-12.jsonl"
FN_INDEX = REPO / "src-needle/data/fn-index.json"
SUMMARY = REPO / "src-needle/data/lora/audit-summary.json"

ATTR_RE = re.compile(r":([a-z][\w.-]*)/([\w?!*+<>=-]+)")
STR_RE = re.compile(r'"((?:[^"\\]|\\.){4,}?)"')

HOME_ALIASES = {"message": "seon.agent.message", "agent": "seon.agent",
                "schema": "seon.schema", "db": "seon.db", "plan": "my.plan",
                "data": "my.data", "fs": "seon.agent.fs", "blob": "my.blob"}
HOME_REFERS = {"wait": "seon.agent.lifecycle", "complete": "seon.agent.lifecycle",
               "pause": "seon.agent.lifecycle", "resume": "seon.agent.lifecycle",
               "terminate": "seon.agent.lifecycle"}


def jl(path):
    return [json.loads(l) for l in path.read_text().splitlines() if l.strip()]


def err_kind(err):
    e = err or ""
    if "is not defined" in e:
        return "undeclared-var"
    if "NAMESPACE is not loaded" in e:
        return "undeclared-ns"
    if "invalid-arity" in e or "invalid arity" in e:
        return "invalid-arity"
    if "invalid-input" in e or "invalid input" in e or "malli" in e.lower():
        return "invalid-args"
    if "timed out" in e:
        return "timeout"
    if "unbalanced" in e or "read error" in e.lower() or "EOF" in e:
        return "read-error"
    if "parse" in e.lower() and "query" in e.lower():
        return "bad-query"
    if "Unknown" in e or "unknown" in e:
        return "bad-query"
    return "other-error"


def env_kind(err):
    e = (err or "").lower()
    if any(w in e for w in ("not found", "no such", "unknown", "missing",
                            "does not exist", "no open")):
        return "env-missing-entity"
    return "env-refusal"


SILENT_OK = re.compile(r"prior session|did not survive")


def silent_ok(f):
    """The quoted-arg undeclared-head bug: eval recorded ok? true but the
    form ran nothing and stashed no value (repro: probe-quoted-arg,
    2026-07-12). The DATA verdict is eval-error; the runtime's ok? true
    is the bug."""
    return bool(f.get("envFail") and SILENT_OK.search(f.get("envErr") or ""))


def classify_pair(man, res):
    """-> (klass, detail) for one pair."""
    if res is None:
        return "unevaluable", "no-result-row"
    if res.get("crash"):
        return "unevaluable", "harness-crash: " + res["crash"][:80]
    forms = res.get("forms") or []
    if not forms:
        return "unevaluable", "zero-eval-rows"
    fails = [f for f in forms if not f.get("ok")]
    if fails:
        return "eval-error", err_kind(fails[0].get("err"))
    silent = [f for f in forms if silent_ok(f)]
    if silent:
        return "eval-error", "undeclared-var-silent-ok"
    envs = [f for f in forms if f.get("envFail")]
    if envs:
        return "envelope-error", env_kind(envs[0].get("envErr"))
    staged = bool(man.get("plan_tx") or man.get("echo_forms")
                  or man.get("extra_agents"))
    return ("eval-clean-after-staging" if staged else "eval-clean"), ""


def bb_kinds(items):
    payload = json.dumps([{"sid": k, "text": v} for k, v in items])
    proc = subprocess.run(["bb", str(SCRIPTS / "lora_forms.clj")],
                          input=payload, capture_output=True, text=True,
                          check=True)
    out = Counter()
    per_row = {}
    for r in json.loads(proc.stdout):
        kinds = [f.get("kind") or "junk" for f in (r.get("forms") or [])
                 if f.get("call")]
        per_row[r["sid"]] = kinds
        out.update(kinds)
    return out, per_row


def main():
    mans = {r["sid"]: r for r in jl(MANIFEST)}
    ress = {r["sid"]: r for r in jl(RESULTS)}
    drafts = {r["meta"]["sid"]: r for r in jl(DRAFTS)}
    idx = json.loads(FN_INDEX.read_text())
    index_syms = {f["seon.fn/sym"] for f in idx["fns"]}
    index_pairs = {(s.rsplit("/", 1)[0].rsplit(".", 1)[-1],
                    s.rsplit("/", 1)[1]): s for s in index_syms}

    audited = [m for m in mans.values() if m["status"] != "abstain"]

    # ---- Q1: classification ------------------------------------------------
    klass = Counter()
    detail = Counter()
    by_family = defaultdict(Counter)
    by_status = defaultdict(Counter)
    exemplars = defaultdict(list)
    per_pair = {}
    for m in audited:
        k, d = classify_pair(m, ress.get(m["sid"]))
        # HONESTY split: a failure downstream of a FAILED ECHO replay is a
        # STAGING limitation (the transcript window dropped the register!
        # lines the real history had), not a proven data defect — on the
        # real serving-time world those attrs exist. Reclassify.
        if k == "eval-error" and m["family"] == "arc":
            r = ress.get(m["sid"]) or {}
            first_err = next((f.get("err") or "" for f in (r.get("forms") or [])
                              if not f.get("ok")), "")
            if ("never seen" in first_err
                    or "Unregistered attributes" in first_err):
                k, d = "staging-gap", "context-window-dropped-register"
        per_pair[m["sid"]] = (k, d)
        klass[k] += 1
        if d:
            detail[f"{k}:{d}"] += 1
        by_family[m["family"]][k] += 1
        by_status[m["status"]][k] += 1
        if k in ("eval-error", "envelope-error", "unevaluable") \
                and len(exemplars[f"{k}:{d}"]) < 4:
            r = ress.get(m["sid"]) or {}
            bad = next((f for f in (r.get("forms") or [])
                        if (not f.get("ok")) or f.get("envFail")
                        or silent_ok(f)), None)
            if bad or r.get("crash"):
                exemplars[f"{k}:{d}"].append({
                    "sid": m["sid"], "stage": m["stage"],
                    "status": m["status"],
                    "src": (bad or {}).get("src") or r.get("crash"),
                    "err": (bad or {}).get("err") or (bad or {}).get("envErr")})

    # per-FORM failure heads
    form_fail_heads = Counter()
    n_forms = n_form_fail = n_form_env = 0
    for m in audited:
        r = ress.get(m["sid"])
        if not r:
            continue
        for f in (r.get("forms") or []):
            n_forms += 1
            head = (f.get("src") or "(").lstrip("(").split()[0] \
                if f.get("src") else "?"
            if not f.get("ok"):
                n_form_fail += 1
                form_fail_heads[f"{head} [{err_kind(f.get('err'))}]"] += 1
            elif silent_ok(f):
                n_form_fail += 1
                form_fail_heads[f"{head} [undeclared-silent-ok]"] += 1
            elif f.get("envFail"):
                n_form_env += 1
                form_fail_heads[f"{head} [envelope]"] += 1

    # echo replay: is the situation's CLAIMED history realizable? Each
    # context echo replayed through the live pipeline; a failing echo means
    # the transcript teaches a history that cannot have happened.
    echo_stats = Counter()
    echo_fail_stage = Counter()
    echo_fail_ex = []
    for m in audited:
        r = ress.get(m["sid"])
        if not r:
            continue
        for e in (r.get("echoes") or []):
            if e.get("ok") and not e.get("envFail"):
                echo_stats["ok"] += 1
            elif e.get("ok"):
                echo_stats["envelope-fail"] += 1
                echo_fail_stage[m["stage"] + ":envelope"] += 1
                if len(echo_fail_ex) < 6:
                    echo_fail_ex.append((m["sid"], e.get("src", "")[:80],
                                         (e.get("envErr") or "")[:100]))
            else:
                echo_stats["eval-error"] += 1
                echo_fail_stage[m["stage"] + ":error"] += 1
                if len(echo_fail_ex) < 6:
                    echo_fail_ex.append((m["sid"], e.get("src", "")[:80],
                                         (e.get("err") or "")[:100]))

    # id groundedness re-verify (the ingredients rule over final targets)
    id_re = re.compile(r"[A-Za-z0-9]{3}-26\d{8}")
    ungrounded = []
    for m in audited:
        d = drafts[m["sid"]]
        ctx_ids = set(id_re.findall(d["context"]))
        bad = [i for i in id_re.findall(d["target"] or "") if i not in ctx_ids]
        if bad:
            ungrounded.append((m["sid"], bad))

    # ---- Q2: coverage + complexity -----------------------------------------
    fpp = Counter()          # substantive forms per pair
    covered = set()          # index fns hit by RESOLVABLE heads
    covered_intent = set()   # index fns hit counting bare heads by name
    bare_heads = Counter()
    for m in audited:
        subs = [f for f in m["target_forms"]
                if f.get("kind") not in ("ns-move",) and f.get("head")]
        fpp[min(len(subs), 8)] += 1
        for f in m["target_forms"]:
            h = f.get("head") or ""
            if "/" in h:
                ns, name = h.rsplit("/", 1)
                full = HOME_ALIASES.get(ns, ns) + "/" + name
                if full in index_syms:
                    covered.add(full)
                    covered_intent.add(full)
            elif h in HOME_REFERS:
                full = HOME_REFERS[h] + "/" + h
                if full in index_syms:
                    covered.add(full)
                    covered_intent.add(full)
            elif f.get("resolution") == "bare":
                bare_heads[h] += 1
                for (ns_last, name), full in index_pairs.items():
                    if name == h:
                        covered_intent.add(full)

    uncovered = sorted(index_syms - covered_intent)
    unc_by_ns = Counter(s.rsplit("/", 1)[0] for s in uncovered)

    # form-kind distribution, synthetic vs mined, SAME analyzer
    syn_kinds, _ = bb_kinds([(m["sid"], drafts[m["sid"]]["target"])
                             for m in audited])
    mined_rows = jl(MINED)
    mined_kinds, _ = bb_kinds([(f"m{i}", r.get("target") or "")
                               for i, r in enumerate(mined_rows)])

    # state-coupling: does the target reuse an id or attr its context set up?
    coupled = Counter()
    for m in audited:
        d = drafts[m["sid"]]
        ctx = d["context"]
        tgt = d["target"] or ""
        ctx_ids = set(id_re.findall(ctx))
        ctx_attrs = {a for a in ATTR_RE.findall(ctx)}
        t_ids = set(id_re.findall(tgt))
        t_attrs = {a for a in ATTR_RE.findall(tgt)
                   if a[0].startswith("my.") or "." not in a[0]}
        uses_id = bool(t_ids & ctx_ids)
        uses_attr = bool(t_attrs & ctx_attrs)
        coupled["id" if uses_id else ("attr" if uses_attr else "context-free")] += 1

    # query-groundedness: query-kind forms over attrs the context never set up
    unestablished = []
    for m in audited:
        d = drafts[m["sid"]]
        ctx_attrs = set(ATTR_RE.findall(d["context"]))
        for f in m["target_forms"]:
            if f.get("kind") != "query":
                continue
            q_attrs = {a for a in ATTR_RE.findall(f["src"])
                       if not a[0].startswith(("seon", "db", "my.plan"))}
            missing = q_attrs - ctx_attrs
            if q_attrs and missing == q_attrs:
                unestablished.append((m["sid"], sorted(
                    f"{n}/{v}" for n, v in missing)[:3]))
                break

    # vacuous-clean queries: a query-kind target form that evals OK but
    # returns an EMPTY result — the staged world had nothing to verify
    # against, so "clean" carries no information about the query's content.
    vacuous = []
    for m in audited:
        r = ress.get(m["sid"])
        if not r:
            continue
        for f in (r.get("forms") or []):
            src = f.get("src") or ""
            if not f.get("ok"):
                continue
            if not re.match(r"\((?:db/|seon\.db/)?(?:query|q)\b", src):
                continue
            v = (f.get("val") or "").strip()
            if v in ("#{}", "[]", "nil", "()", '""'):
                vacuous.append((m["sid"], src[:60]))
                break

    # copy-fidelity: quoted strings in target present verbatim in context
    copy_hist = Counter()
    for m in audited:
        d = drafts[m["sid"]]
        strs = STR_RE.findall(d["target"] or "")
        strs = [s for s in strs if len(s) >= 4]
        if not strs:
            copy_hist["no-strings"] += 1
            continue
        frac = sum(1 for s in strs if s in d["context"]) / len(strs)
        copy_hist["all-copied" if frac == 1.0 else
                  ("mostly" if frac >= 0.5 else
                   ("some" if frac > 0 else "all-fresh"))] += 1

    # ---- Q3: the A2 fraction ------------------------------------------------
    n = len(audited)
    rejected = sum(v for k, v in klass.items()
                   if k in ("eval-error", "envelope-error"))
    staging_gap = klass.get("staging-gap", 0)
    by_split = defaultdict(Counter)
    for m in audited:
        by_split[m["split"]][per_pair[m["sid"]][0]] += 1

    # ---- print ---------------------------------------------------------------
    def table(counter, total=None):
        t = total or sum(counter.values())
        return "\n".join(f"| {k} | {v} | {100*v/t:.1f}% |"
                         for k, v in counter.most_common())

    print("## Q1 — classification (%d audited non-abstain pairs)\n" % n)
    print("| class | pairs | share |\n|---|---|---|")
    print(table(klass, n))
    print("\n### detail\n\n| class:detail | pairs |\n|---|---|")
    for k, v in detail.most_common():
        print(f"| {k} | {v} |")
    print("\n### by family\n")
    for fam, c in by_family.items():
        print(f"- **{fam}**: " + ", ".join(f"{k} {v}" for k, v in c.most_common()))
    print("\n### by curation status\n")
    for st, c in by_status.items():
        print(f"- **{st}**: " + ", ".join(f"{k} {v}" for k, v in c.most_common()))
    print("\n### by split\n")
    for sp, c in by_split.items():
        print(f"- **{sp}**: " + ", ".join(f"{k} {v}" for k, v in c.most_common()))
    print(f"\nper-form: {n_forms} eval rows, {n_form_fail} failed, "
          f"{n_form_env} envelope-refused")
    print("\n### top failing heads\n\n| head [kind] | forms |\n|---|---|")
    for k, v in form_fail_heads.most_common(20):
        print(f"| `{k}` | {v} |")
    print("\n### exemplars (verbatim)\n")
    for k, exs in sorted(exemplars.items()):
        print(f"**{k}**\n")
        for e in exs:
            print(f"- `{e['sid']}` ({e['stage']}, {e['status']}): "
                  f"`{(e['src'] or '')[:120]}` → {(e['err'] or '')[:160]}")
        print()
    print(f"\nid-ungrounded (target id ∉ its own context): {len(ungrounded)}")
    for sid, bad in ungrounded[:5]:
        print(f"- {sid}: {bad}")
    print("\n### echo replay (is the claimed transcript history realizable?)\n")
    print("echo verdicts:", dict(echo_stats))
    print("failures by stage:", dict(echo_fail_stage.most_common(10)))
    for sid, src, err in echo_fail_ex:
        print(f"- {sid}: `{src}` → {err}")

    print("\n## Q2 — coverage + complexity\n")
    print("substantive forms per pair:",
          dict(sorted(fpp.items())))
    print(f"\nfn coverage of the {len(index_syms)}-fn index: "
          f"{len(covered)} via RESOLVABLE heads, "
          f"{len(covered_intent)} counting bare-name intent")
    print("\nuncovered areas (by ns): ",
          dict(unc_by_ns.most_common()))
    print("\nbare (unresolvable-at-serving) heads:",
          dict(bare_heads.most_common(20)))
    st, mt = sum(syn_kinds.values()), sum(mined_kinds.values())
    print("\n| kind | synthetic | % | mined-214 | % |\n|---|---|---|---|---|")
    for k in sorted(set(syn_kinds) | set(mined_kinds)):
        print(f"| {k} | {syn_kinds.get(k,0)} | {100*syn_kinds.get(k,0)/st:.1f}%"
              f" | {mined_kinds.get(k,0)} | {100*mined_kinds.get(k,0)/mt:.1f}% |")
    print("\nstate-coupling:", dict(coupled))
    print("copy-fidelity (quoted strings vs context):", dict(copy_hist))
    print(f"\nquery forms over attrs the context NEVER established: "
          f"{len(unestablished)} pairs")
    for sid, a in unestablished[:8]:
        print(f"- {sid}: {a}")
    print(f"\nvacuous-clean queries (eval ok, EMPTY result — nothing to "
          f"verify against): {len(vacuous)} pairs")
    for sid, s in vacuous[:8]:
        print(f"- {sid}: `{s}`")

    print("\n## Q3 — A2 fraction\n")
    print(f"REPL gate (eval-error + envelope-error): {rejected}/{n} "
          f"= {100*rejected/n:.1f}% of kept pairs would have been rejected "
          f"by the full REPL-proven pipeline")
    print(f"(+ {staging_gap} more pairs unverifiable under text-staging — "
          f"the transcript window dropped the register! history the target "
          f"depends on; db-staged worlds would decide them)")

    SUMMARY.write_text(json.dumps({
        "n_audited": n, "class": dict(klass), "detail": dict(detail),
        "by_family": {k: dict(v) for k, v in by_family.items()},
        "by_status": {k: dict(v) for k, v in by_status.items()},
        "by_split": {k: dict(v) for k, v in by_split.items()},
        "form_stats": {"n": n_forms, "fail": n_form_fail, "env": n_form_env},
        "fail_heads": dict(form_fail_heads.most_common(30)),
        "coverage": {"resolvable": len(covered),
                     "intent": len(covered_intent),
                     "index": len(index_syms),
                     "uncovered_by_ns": dict(unc_by_ns)},
        "bare_heads": dict(bare_heads),
        "kinds_synthetic": dict(syn_kinds), "kinds_mined": dict(mined_kinds),
        "state_coupling": dict(coupled), "copy_fidelity": dict(copy_hist),
        "unestablished_queries": len(unestablished),
        "vacuous_queries": len(vacuous),
        "id_ungrounded": len(ungrounded),
        "echo_stats": dict(echo_stats),
        "echo_fail_stage": dict(echo_fail_stage),
        "rejected_fraction": rejected / n,
    }, indent=1))
    print(f"\nsummary -> {SUMMARY}")


if __name__ == "__main__":
    main()
