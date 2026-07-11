"""Typeahead replay corpus — real acme turns captured for the P4 bench.

typeahead-design.md §Evaluation: the swap-in bench replays a corpus of REAL
agent turns (byte-exact turn-capture blobs, capability-rung shaped) against
the diffusion worker in three arms (tasks/typeahead_replay.py). This module
GENERATES that corpus by driving short live sessions on a long-lived cluster
(acme; DeepSeek drives are pre-authorized there) and capturing, per sample:

  - the replay turn's byte-exact prompt blob (hash + provenance — the text
    stays in the cluster blob store, three-tier rule),
  - the VERBATIM prompt sections the bench re-renders at replay time
    (`recent-verbs`, `plan-ledger`, the contract `namespace <ns>` cards),
  - the driver offers parsed from the rendered menu (glyph N in the offer
    wire = glyph N in the prompt, same invariant as menu/verb-offers),
  - the task intent + a host-side outcome predicate (correctness gate:
    parses AND evals AND right outcome — scorers-gate-correctness rule).

Every sample is a warmup message + a FINAL message on the same agent, so the
replay turn (the final run's first turn) always renders a real
`:recent-verbs` menu (a fresh agent's very first turn has no eval history →
no menu — measured 2026-07-10). One planning sample restarts the pod between
messages (the interruption-resume rung shape).

Artifact: evals/typeahead_replay.corpus.json (the single dataset home).

Run:  .venv/bin/python -m seon_inspect.typeahead_corpus \
          --cluster-url http://127.0.0.1:7980/agents/run --cluster acme
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import time
import urllib.request
from pathlib import Path
from typing import Any

from seon_inspect import config
from seon_inspect.solver import pod_run

REPO_ROOT = Path(__file__).resolve().parents[3]
CORPUS_PATH = REPO_ROOT / "evals" / "typeahead_replay.corpus.json"

DRIVE_TIMEOUT_MS = 180_000
WARMUP = "Reply with the single word ready."

# ---------------------------------------------------------------------------
# The sample table — capability-rung shapes (goal-stated; verbs are never
# coached in the intent — the no-coaching rule; predicates live HERE).
# predicate kinds:
#   eval-answer  node-oracle-eval the reply's pure forms; the last value must
#                match one of `expect` (printed-value strings).
#   verb-call    some call-position symbol matches `heads` exactly or has a
#                namespace part in `head_nses`.
# Both kinds additionally REQUIRE the reply to parse (>=1 form, 0 errors).
# ---------------------------------------------------------------------------
DB_READ_HEADS = ["db/query", "seon.db/query", "db/pull", "seon.db/pull",
                 "db/entity", "seon.db/entity",
                 "search/grep-graph", "seon.agent.search/grep-graph"]

SAMPLES: list[dict[str, Any]] = [
    {"id": "r1", "rung": "repl", "warmups": [WARMUP],
     "intent": ("Compute the sum of squares of the integers 1 through 12 "
                "and reply with just the number."),
     "contract_nses": [],
     "predicate": {"kind": "eval-answer", "expect": ["650"]}},
    {"id": "r2", "rung": "repl", "warmups": [WARMUP],
     "intent": ("Compute 21*2 plus the remainder of 100 divided by 7; "
                "reply with just the number."),
     "contract_nses": [],
     "predicate": {"kind": "eval-answer", "expect": ["44"]}},
    {"id": "r3", "rung": "repl", "warmups": [WARMUP],
     "intent": ("Define a function mean that averages a collection of "
                "numbers, verify it on [1 2 3 4], and reply with the "
                "verified result."),
     "contract_nses": [],
     "predicate": {"kind": "eval-answer", "expect": ["5/2", "2.5"]}},
    {"id": "m1", "rung": "movement", "warmups": [WARMUP],
     "intent": ("Find which namespace defines a function named tagline and "
                "reply with the namespace name."),
     "contract_nses": ["seon.agent.search", "seon.db"],
     "predicate": {"kind": "verb-call", "heads": DB_READ_HEADS,
                   "head_nses": ["seon.agent.search", "search"]}},
    {"id": "m2", "rung": "movement", "warmups": [WARMUP],
     "intent": ("Count how many :seon.fn rows the database holds for "
                "namespace my.plan; reply with the number."),
     "contract_nses": ["seon.db"],
     "predicate": {"kind": "verb-call", "heads": DB_READ_HEADS,
                   "head_nses": []}},
    {"id": "p1", "rung": "planning", "warmups": [WARMUP],
     "intent": ("Create a durable 3-step plan for auditing your toolkit "
                "namespaces, then mark the first step as the one you are "
                "working on."),
     "contract_nses": ["my.plan"],
     "predicate": {"kind": "verb-call", "heads": [],
                   "head_nses": ["my.plan", "plan", "todo"]}},
    {"id": "p2", "rung": "planning", "restart_between": True,
     "warmups": [("Create a durable plan with three steps — inventory your "
                  "verbs, summarize them, report to me — and complete only "
                  "the first step.")],
     "intent": ("You were interrupted by a restart. Resume your plan: "
                "complete the next open step and say which steps remain."),
     "contract_nses": ["my.plan"],
     "predicate": {"kind": "verb-call", "heads": [],
                   "head_nses": ["my.plan", "plan", "todo"]}},
    {"id": "p3", "rung": "planning", "warmups": [WARMUP],
     "intent": ("Break the task 'inventory your my.kb manual' into two "
                "durable plan steps and start the first."),
     "contract_nses": ["my.plan"],
     "predicate": {"kind": "verb-call", "heads": [],
                   "head_nses": ["my.plan", "plan", "todo"]}},
    {"id": "k1", "rung": "kb",
     "warmups": [("Store durably, with provenance, these facts: project "
                  "Zephyr launches 2026-09-14; its owner is Rivera.")],
     "intent": ("From your stored knowledge: when does Zephyr launch and "
                "who owns it? Answer from the database."),
     "contract_nses": ["my.kb", "seon.db"],
     "predicate": {"kind": "verb-call", "heads": DB_READ_HEADS,
                   "head_nses": []}},
    {"id": "k2", "rung": "kb",
     "warmups": [("Record durably: the acme pod listens on port 7980 and "
                  "the wire REPL on 7981.")],
     "intent": ("What port does the wire REPL use, per your stored facts? "
                "Answer from the database."),
     "contract_nses": ["my.kb", "seon.db"],
     "predicate": {"kind": "verb-call", "heads": DB_READ_HEADS,
                   "head_nses": []}},
]


# ---------------------------------------------------------------------------
# Prompt-blob section extraction (pure; tested offline).
# ---------------------------------------------------------------------------
def extract_section(prompt: str, name: str) -> str | None:
    """The VERBATIM text of one `;;; ┌─ <name> ─ … ;;; └─ end <name> ─`
    section of a rendered prompt (brackets included), or None."""
    pat = re.compile(
        r"^;;; ┌─ " + re.escape(name) + r" ─\n.*?^;;; └─ end "
        + re.escape(name) + r" ─$", re.S | re.M)
    m = pat.search(prompt)
    return m.group(0) if m else None


MENU_LINE = re.compile(r"^; ([①②③④⑤⑥⑦⑧⑨⑩]) \((\S+) (.*?)\)(?: — .*)?$")


def offers_from_menu(section: str) -> list[dict[str, Any]]:
    """Driver offers parsed from a rendered `:recent-verbs` section — the
    same glyph/label/template shape as seon.agent.ctx.menu/verb-offers, so
    glyph N on the wire is glyph N in the prompt BY CONSTRUCTION (both read
    the same rendered lines)."""
    offers = []
    for line in (section or "").splitlines():
        m = MENU_LINE.match(line)
        if m:
            glyph, sym, arities = m.groups()
            offers.append({
                "glyph": glyph,
                "label": f"{sym} {arities}",
                "template": [["clamp", f"({sym} "], ["free", 24],
                             ["clamp", ")"]],
            })
    return offers


# ---------------------------------------------------------------------------
# Cluster read-back (wire-server socket REPL — same idiom as planning.py).
# ---------------------------------------------------------------------------
_TURNS_FORM = (
    "(do (require (quote [cheshire.core :as json]) (quote [datahike.api :as d]))"
    " (let [conn (:seon.server.registry/conn (seon.server.registry/get-conn"
    " {:seon.server.registry/db-name :%s}))"
    " db (deref conn)"
    " a (d/q (quote [:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]]) db %s)"
    " rs (if a (d/q (quote [:find [?r ...] :in $ ?a :where"
    " [?r :seon.agent.run/agent ?a]]) db a) [])"
    " ts (if (seq rs) (d/q (quote [:find [?t ...] :in $ [?r ...] :where"
    " [?t :seon.agent.turn/run ?r]]) db rs) [])"
    " rows (mapv (fn [t] (let [p (d/pull db (quote [:seon.agent.turn/at"
    " :seon.agent.turn/prompt-chars"
    " {:seon.agent.turn/prompt-blob [:my.blob/hash]}]) t)]"
    " {\"at_ms\" (some-> (:seon.agent.turn/at p) (.getTime))"
    "  \"prompt_chars\" (:seon.agent.turn/prompt-chars p)"
    "  \"prompt_blob\" (get-in p [:seon.agent.turn/prompt-blob :my.blob/hash])}))"
    " ts)]"
    " (println (str \"WIRE-JSON<\" (json/generate-string rows) \">WIRE-JSON\"))))")


def agent_turns(cluster: str, agent_id: str, *, port: int) -> list[dict]:
    """All turn rows (at_ms / prompt_chars / prompt_blob) for `agent_id`."""
    from seon_inspect.cluster import wire_repl_json
    rows = wire_repl_json(_TURNS_FORM % (cluster, json.dumps(agent_id)),
                          port=port)
    if not isinstance(rows, list):
        raise RuntimeError(f"turn read-back returned non-list: {rows!r}")
    return sorted((r for r in rows if r.get("at_ms")),
                  key=lambda r: r["at_ms"])


def read_blob(cluster: str, blob_hash: str) -> str:
    """The blob's full text from the cluster's content-addressed store."""
    p = (REPO_ROOT / "data" / "clusters" / cluster / "blobs"
         / blob_hash[:2] / blob_hash)
    return p.read_text()


def ensure_pod(cluster: str) -> None:
    """Start the acme pod if its door is down, then ready-poll."""
    try:
        urllib.request.urlopen("http://127.0.0.1:7980/", timeout=3)
        return
    except Exception:
        pass
    if cluster != "acme":
        raise RuntimeError("pod down and auto-start is only wired for acme")
    subprocess.run(["bin/acme", "start", "pod"], cwd=REPO_ROOT, check=True,
                   capture_output=True, timeout=180)
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            urllib.request.urlopen("http://127.0.0.1:7980/", timeout=3)
            return
        except Exception:
            time.sleep(2)
    raise RuntimeError("acme pod did not come ready")


def restart_pod(cluster: str) -> None:
    """`bin/acme restart pod` + ready-poll (the interruption-resume rung)."""
    if cluster != "acme":
        raise RuntimeError("restart_between is only wired for the acme "
                           "harness (never the default cluster)")
    subprocess.run(["bin/acme", "restart", "pod"], cwd=REPO_ROOT, check=True,
                   capture_output=True, timeout=180)
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            urllib.request.urlopen("http://127.0.0.1:7980/", timeout=3)
            return
        except Exception:
            time.sleep(2)
    raise RuntimeError("acme pod did not come ready after restart")


# ---------------------------------------------------------------------------
# The drive.
# ---------------------------------------------------------------------------
def drive_sample(spec: dict, *, cluster: str, cluster_url: str,
                 wire_port: int) -> dict:
    """Drive one sample's session; return the corpus row (loud on defects).

    Warmup message(s) mint + season the agent (eval history → a real menu);
    the FINAL message's first turn is the replay target: its byte-exact
    prompt blob supplies the sections the bench re-renders."""
    agent_id = None
    for msg in spec["warmups"]:
        r = pod_run(msg, DRIVE_TIMEOUT_MS, url=cluster_url, agent_id=agent_id)
        agent_id = r["agent_id"]
        if r.get("timed_out"):
            raise RuntimeError(f"{spec['id']}: warmup timed out ({agent_id})")
    if spec.get("restart_between"):
        restart_pod(cluster)
    t0_ms = int(time.time() * 1000)
    final = pod_run(spec["intent"], DRIVE_TIMEOUT_MS, url=cluster_url,
                    agent_id=agent_id)
    turns = [t for t in agent_turns(cluster, agent_id, port=wire_port)
             if t["at_ms"] >= t0_ms - 2000 and t.get("prompt_blob")]
    if not turns:
        raise RuntimeError(f"{spec['id']}: no captured turn for the final run")
    replay = turns[0]
    prompt = read_blob(cluster, replay["prompt_blob"])
    sections: dict[str, str] = {}
    for name in (["recent-verbs", "plan-ledger"]
                 + [f"namespace {ns}" for ns in spec["contract_nses"]]):
        sec = extract_section(prompt, name)
        if sec is not None:
            sections[name] = sec
    if "recent-verbs" not in sections:
        # load-bearing for the typeahead arm — fail loudly, never silently
        # produce a menu-less sample (zero-scores rule: harness first).
        raise RuntimeError(f"{spec['id']}: replay prompt carries no "
                           f"recent-verbs menu (blob {replay['prompt_blob']})")
    offers = offers_from_menu(sections["recent-verbs"])
    if not offers:
        raise RuntimeError(f"{spec['id']}: menu section parsed to 0 offers")
    return {
        "id": spec["id"], "rung": spec["rung"], "intent": spec["intent"],
        "warmups": spec["warmups"],
        "restart_between": bool(spec.get("restart_between")),
        "agent_id": agent_id,
        "replay": {"prompt_blob": replay["prompt_blob"],
                   "prompt_chars": replay["prompt_chars"],
                   "turn_at_ms": replay["at_ms"]},
        "drive_reply": final.get("reply", ""),
        "drive_model_config": final.get("model_config"),
        "sections": sections,
        "offers": offers,
        "contract_nses": spec["contract_nses"],
        "predicate": spec["predicate"],
    }


def generate(cluster: str = "acme",
             cluster_url: str | None = None,
             wire_port: int = 7981,
             out_path: Path = CORPUS_PATH,
             only: list[str] | None = None) -> dict:
    """Drive every sample and write the corpus manifest. Returns it.

    `only` re-drives just those sample ids and MERGES them into the
    existing manifest (flake recovery — e.g. a provider timeout killed a
    drive mid-corpus); sample order follows SAMPLES either way."""
    url = cluster_url or "http://127.0.0.1:7980/agents/run"
    prior: dict[str, dict] = {}
    if only and out_path.is_file():
        prior = {s["id"]: s
                 for s in json.loads(out_path.read_text())["samples"]}
    rows, failures = [], []
    for spec in SAMPLES:
        if only and spec["id"] not in only:
            if spec["id"] in prior:
                rows.append(prior[spec["id"]])
            else:
                failures.append(f"{spec['id']}: absent from prior manifest "
                                "and not in --only")
            continue
        # One retry per sample AFTER re-ensuring the pod is up: the acme
        # pod's :crash dial exits on a :core fault (observed twice
        # 2026-07-10: DeepSeek transport failures escaping as
        # unhandledRejection → :core — fault eids 3993/4561), taking the
        # in-flight drive with it. Restart-if-down is harness resilience,
        # never a score.
        for attempt in (1, 2):
            try:
                ensure_pod(cluster)
                rows.append(drive_sample(spec, cluster=cluster,
                                         cluster_url=url,
                                         wire_port=wire_port))
                print(f"[corpus] {spec['id']} ok "
                      f"(agent {rows[-1]['agent_id']}, "
                      f"{len(rows[-1]['offers'])} offers)", flush=True)
                break
            except Exception as e:
                print(f"[corpus] {spec['id']} attempt {attempt} FAILED: {e}",
                      flush=True)
                if attempt == 2:
                    failures.append(f"{spec['id']}: {e}")
    manifest = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "generator": "seon_inspect.typeahead_corpus",
        "cluster": cluster,
        "n": len(rows),
        "failures": failures,
        "samples": rows,
    }
    out_path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False)
                        + "\n")
    print(f"[corpus] wrote {out_path} (n={len(rows)}, "
          f"failures={len(failures)})", flush=True)
    return manifest


_REPLY_FORM = (
    "(do (require (quote [cheshire.core :as json]) (quote [datahike.api :as d]))"
    " (let [conn (:seon.server.registry/conn (seon.server.registry/get-conn"
    " {:seon.server.registry/db-name :%s}))"
    " db (deref conn)"
    " t (d/q (quote [:find ?t . :in $ ?h :where [?b :my.blob/hash ?h]"
    " [?t :seon.agent.turn/prompt-blob ?b]]) db %s)"
    " p (when t (d/pull db (quote [{:seon.agent.turn/reply-blob"
    " [:my.blob/hash]}]) t))"
    " out {\"reply_blob\" (get-in p [:seon.agent.turn/reply-blob :my.blob/hash])}]"
    " (println (str \"WIRE-JSON<\" (json/generate-string out) \">WIRE-JSON\"))))")


def enrich_replay_replies(cluster: str = "acme", wire_port: int = 7981,
                          path: Path = CORPUS_PATH) -> dict:
    """Add each sample's replay-turn RAW LLM reply (`replay_reply`) to the
    manifest — the DeepSeek reference arm (arm0): the same turn whose
    prompt the bench replays, scored by the same predicates. Read from
    the turn's reply blob (byte ground truth), post-hoc via the wire
    REPL; idempotent."""
    from seon_inspect.cluster import wire_repl_json
    manifest = json.loads(path.read_text())
    for s in manifest["samples"]:
        h = s["replay"]["prompt_blob"]
        out = wire_repl_json(_REPLY_FORM % (cluster, json.dumps(h)),
                             port=wire_port)
        rb = (out or {}).get("reply_blob")
        if not rb:
            raise RuntimeError(f"{s['id']}: no reply blob for replay turn "
                               f"(prompt blob {h})")
        s["replay"]["reply_blob"] = rb
        s["replay_reply"] = read_blob(cluster, rb)
    path.write_text(json.dumps(manifest, indent=1, ensure_ascii=False) + "\n")
    print(f"[corpus] enriched {len(manifest['samples'])} samples with "
          "replay replies", flush=True)
    return manifest


def corpus_sha(path: Path = CORPUS_PATH) -> str:
    """sha256 of the corpus artifact (run provenance)."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_corpus(path: Path = CORPUS_PATH) -> dict:
    return json.loads(path.read_text())


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--cluster", default="acme")
    ap.add_argument("--cluster-url",
                    default="http://127.0.0.1:7980/agents/run")
    ap.add_argument("--wire-port", type=int, default=7981)
    ap.add_argument("--only", help="comma-separated sample ids to re-drive "
                                   "and merge into the existing manifest")
    args = ap.parse_args()
    generate(cluster=args.cluster, cluster_url=args.cluster_url,
             wire_port=args.wire_port,
             only=args.only.split(",") if args.only else None)
