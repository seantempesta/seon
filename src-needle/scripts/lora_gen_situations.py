#!/usr/bin/env python3
"""LoRA data-gen step 1 — staged situations for frontier-draft training pairs.

Emits synthetic SITUATIONS (context + cards + intent stamps) whose SHAPE
mirrors the held-out eval contexts (data/tune/acme-2026-07-12.jsonl) but
whose CONTENT (domains, ids, titles, user phrasings) is fresh. The held-out
rows are EVAL ONLY — nothing from them is reused verbatim; a mechanical
leakage guard enforces it (see below).

Three families:
  arc      staged turns of a session arc per domain: fresh plan-lay-down,
           schema registration, seed transact, report query, plan
           bookkeeping, inspection, stuck-error recovery, planner-consult,
           finish. Mirrors the render grammar (plan block + transcript
           block) observed in the held-out contexts.
  kt2b     single-ask turns re-minted from the KT2b case machinery
           (src-needle/cases/kt2b_cases.json — BFCL/tau2/agentbench-derived
           phrasings re-domained onto the seon fn index): fresh ids, the
           case query as the NEW user message, expected head kept as a
           mechanical filter stamp.
  abstain  nothing-applies situations (answered user, empty frontier,
           outbound summary sent) -> empty target, minted directly (no
           frontier draft needed).

Leakage guard (mechanical, hard-fails the run):
  1. id disjointness — no id-shaped string (XXX-26########) generated here
     may appear anywhere in the held-out v1/v2 files (contexts, targets,
     meta), and vice versa: held-out ids may not appear in any situation.
  2. content n-grams — no 8-word-gram of any generated user-message body
     may appear in any held-out user-message body. Render-grammar scaffold
     (plan coaching lines, transcript headers, event brackets) is exempt:
     it is the projection's constant grammar, identical for every agent at
     serving time, not content.
  3. domain words — the held-out scenario nouns (expense, book/reading,
     team/standup/task-tracker) are banned as generated domain nouns.

Output: src-needle/data/lora/situations.jsonl (gitignored; the drafts +
curation pipeline downstream of this commits data/tune/drafts-*.jsonl).
"""

import argparse
import json
import random
import re
import string
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
V1 = REPO / "data/tune/acme-2026-07-12.jsonl"
V2 = REPO / "data/tune/acme-2026-07-12-v2.jsonl"
FN_INDEX = REPO / "src-needle/data/fn-index.json"
KT2B_CASES = REPO / "src-needle/cases/kt2b_cases.json"
OUT = REPO / "src-needle/data/lora/situations.jsonl"

ID_RE = re.compile(r"[A-Za-z0-9]{3}-26\d{8}")

# ---------------------------------------------------------------- grammar
# Constant render-grammar scaffold, reproduced from the projection (this is
# the renderer's fixed text — identical in every held-out row and at
# serving time; exempt from the content-leakage guard by construction).

PLAN_COACH = """; Open frontier (▶ = the step you are on, ☐ = open) — close each
; step with (my.plan/done! {:my.plan/id "<id>"})
; the MOMENT its work lands (never batch closes at the end);
; take one up with (my.plan/active! {:my.plan/id "<id>"}); add a
; DISCOVERED step UNDER this plan (never a new parentless root):
; (my.plan/step! {:my.plan/title "…" :my.plan/parent [:my.plan/id "<an id here>"]})"""


def transcript_header(ns):
    return (
        f"; seon · {ns} · live REPL\n"
        "; The flat, time-ordered log below is this REPL's history — your\n"
        "; messages and evals interleaved, oldest-first. Append below.\n"
        "; Write the forms you want run; never write out a result yourself — a result you\n"
        "; type is stripped, and the real value arrives interleaved on your next turn."
    )


REQUIRE_FORM = ("(require '[my.plan :as plan] '[seon.schema :as schema] "
                "'[seon.db :as db] '[my.data :as data] "
                "'[seon.agent.fs :as fs] '[my.blob :as blob])")

TX_OK_GLYPH = ("⟹ {:seon.db/ok? true, :seon.db/tempids {}, :seon.d "
               "…⟨⚠ TRUNCATED at 12 of 31 tokens — the DISPLAY is clipped, "
               "the live value is COMPLETE⟩")


# ---------------------------------------------------------------- domains
# Fresh content domains. None of the held-out scenario nouns (expenses,
# books/reading log, team facts, standup tasks). Attrs: (name, malli-type,
# identity?, optional?).

DOMAINS = [
    {"key": "recipe", "noun": "recipe", "plural": "recipes",
     "title": "Recipe box groundwork",
     "goal": "a recipe box my human can search by cuisine",
     "attrs": [("name", ":string", True, False), ("cuisine", ":keyword", False, False),
               ("minutes", ":int", False, False), ("rating", ":int", False, True)],
     "seeds": [("miso ramen", ":japanese", 45, 4), ("shakshuka", ":israeli", 30, 5),
               ("cacio e pepe", ":italian", 20, 4)],
     "report": "the count of recipes per cuisine",
     "report_expect": "japanese 1, israeli 1, italian 1"},
    {"key": "planting", "noun": "planting", "plural": "plantings",
     "title": "Garden bed log",
     "goal": "a planting log that survives the season",
     "attrs": [("species", ":string", True, False), ("bed", ":keyword", False, False),
               ("planted", ":string", False, False), ("days-to-harvest", ":int", False, False)],
     "seeds": [("bush bean", ":north", "2026-05-02", 55), ("cherry tomato", ":south", "2026-05-10", 70),
               ("butter lettuce", ":north", "2026-06-01", 45)],
     "report": "the earliest harvest date per bed",
     "report_expect": "north from the bush bean, south from the cherry tomato"},
    {"key": "invoice", "noun": "invoice", "plural": "invoices",
     "title": "Invoice ledger setup",
     "goal": "an invoice ledger my human can total per client",
     "attrs": [("number", ":string", True, False), ("client", ":string", False, False),
               ("amount-cents", ":int", False, False), ("status", ":keyword", False, False)],
     "seeds": [("INV-71", "Marberly & Co", 128000, ":sent"), ("INV-72", "Oxbow Labs", 45500, ":paid"),
               ("INV-73", "Marberly & Co", 99000, ":sent")],
     "report": "the total outstanding amount per client",
     "report_expect": "Marberly & Co 227000, Oxbow Labs 0"},
    {"key": "trip", "noun": "trip", "plural": "trips",
     "title": "Mileage journal",
     "goal": "a mileage journal queryable per vehicle",
     "attrs": [("id", ":string", True, False), ("vehicle", ":keyword", False, False),
               ("date", ":string", False, False), ("km", ":int", False, False)],
     "seeds": [("t-101", ":van", "2026-06-20", 82), ("t-102", ":bike", "2026-06-22", 14),
               ("t-103", ":van", "2026-06-29", 156)],
     "report": "total km per vehicle",
     "report_expect": "van 238, bike 14"},
    {"key": "episode", "noun": "episode", "plural": "episodes",
     "title": "Podcast queue",
     "goal": "a podcast queue with listened state that persists",
     "attrs": [("title", ":string", True, False), ("show", ":string", False, False),
               ("minutes", ":int", False, False), ("listened?", ":boolean", False, False)],
     "seeds": [("The Long Now of Soil", "Field Notes", 62, "false"),
               ("Chapter Eleven", "Ledger Lore", 41, "true"),
               ("Signals in the Static", "Field Notes", 55, "false")],
     "report": "unlistened minutes per show",
     "report_expect": "Field Notes 117, Ledger Lore 0"},
    {"key": "plant", "noun": "houseplant", "plural": "houseplants",
     "title": "Houseplant watering roster",
     "goal": "a watering roster that says what is due today",
     "attrs": [("name", ":string", True, False), ("species", ":string", False, False),
               ("last-watered", ":string", False, False), ("interval-days", ":int", False, False)],
     "seeds": [("Fernando", "boston fern", "2026-07-08", 3), ("Spike", "haworthia", "2026-06-28", 14),
               ("Matilda", "monstera", "2026-07-05", 7)],
     "report": "which houseplants are due for water on 2026-07-12",
     "report_expect": "Fernando and Matilda due, Spike not yet"},
    {"key": "gamenight", "noun": "game night", "plural": "game nights",
     "title": "Game night record",
     "goal": "a record of game nights my human can settle arguments with",
     "attrs": [("id", ":string", True, False), ("game", ":string", False, False),
               ("players", ":int", False, False), ("winner", ":string", False, False)],
     "seeds": [("gn-11", "Cascadia", 4, "Priya"), ("gn-12", "Azul", 3, "Marco"),
               ("gn-13", "Cascadia", 5, "Priya")],
     "report": "wins per winner",
     "report_expect": "Priya 2, Marco 1"},
    {"key": "sighting", "noun": "bird sighting", "plural": "bird sightings",
     "title": "Bird sighting log",
     "goal": "a sighting log totalable per species",
     "attrs": [("id", ":string", True, False), ("species", ":string", False, False),
               ("spot", ":keyword", False, False), ("count", ":int", False, False)],
     "seeds": [("s-301", "cedar waxwing", ":river-path", 12), ("s-302", "varied thrush", ":ridge", 2),
               ("s-303", "cedar waxwing", ":ridge", 5)],
     "report": "total individuals per species",
     "report_expect": "cedar waxwing 17, varied thrush 2"},
    {"key": "tool", "noun": "tool loan", "plural": "tool loans",
     "title": "Tool lending board",
     "goal": "a lending board that knows who has what",
     "attrs": [("name", ":string", True, False), ("holder", ":string", False, False),
               ("on-loan?", ":boolean", False, False), ("due", ":string", False, True)],
     "seeds": [("torque wrench", "Ana", "true", "2026-07-19"), ("laser level", "shop", "false", None),
               ("tile saw", "Kofi", "true", "2026-07-15")],
     "report": "which tool loans are out and to whom",
     "report_expect": "torque wrench with Ana, tile saw with Kofi"},
    {"key": "donation", "noun": "donation", "plural": "donations",
     "title": "Donation record",
     "goal": "a donation record totalable per organization",
     "attrs": [("id", ":string", True, False), ("org", ":string", False, False),
               ("amount-cents", ":int", False, False), ("receipt?", ":boolean", False, False)],
     "seeds": [("d-51", "River Cleanup Trust", 5000, "true"), ("d-52", "Open Atlas", 2500, "false"),
               ("d-53", "River Cleanup Trust", 7500, "true")],
     "report": "the total donated per organization",
     "report_expect": "River Cleanup Trust 12500, Open Atlas 2500"},
    {"key": "subscription", "noun": "subscription", "plural": "subscriptions",
     "title": "Subscription audit",
     "goal": "a subscription list my human can audit monthly",
     "attrs": [("service", ":string", True, False), ("monthly-cents", ":int", False, False),
               ("renews", ":string", False, False), ("active?", ":boolean", False, False)],
     "seeds": [("Cloudloft", 900, "2026-08-01", "true"), ("PaperTrail Pro", 1400, "2026-07-21", "false"),
               ("Kilnworks", 2200, "2026-08-11", "true")],
     "report": "the monthly total across active subscriptions",
     "report_expect": "3100 cents from Cloudloft and Kilnworks"},
    {"key": "brew", "noun": "tea brew", "plural": "tea brews",
     "title": "Tea brewing notebook",
     "goal": "a brewing notebook that remembers what worked",
     "attrs": [("id", ":string", True, False), ("leaf", ":string", False, False),
               ("steep-seconds", ":int", False, False), ("verdict", ":keyword", False, False)],
     "seeds": [("b-21", "gyokuro", 120, ":again"), ("b-22", "keemun", 240, ":weak"),
               ("b-23", "gyokuro", 90, ":perfect")],
     "report": "the best steep time per leaf",
     "report_expect": "gyokuro 90 from the :perfect verdict, keemun none yet"},
    {"key": "chore", "noun": "chore", "plural": "chores",
     "title": "Chore rotation board",
     "goal": "a rotation board that knows who owes what",
     "attrs": [("name", ":string", True, False), ("assignee", ":string", False, False),
               ("weekday", ":keyword", False, False), ("done?", ":boolean", False, False)],
     "seeds": [("compost run", "Jonas", ":tuesday", "false"), ("filter swap", "Ren", ":saturday", "true"),
               ("gutter check", "Jonas", ":saturday", "false")],
     "report": "open chores per assignee",
     "report_expect": "Jonas 2, Ren 0"},
    {"key": "film", "noun": "film", "plural": "films",
     "title": "Watchlist ledger",
     "goal": "a watchlist with scores that outlives the browser tab",
     "attrs": [("title", ":string", True, False), ("year", ":int", False, False),
               ("seen?", ":boolean", False, False), ("score", ":int", False, True)],
     "seeds": [("Stalker", 1979, "true", 5), ("The Fall", 2006, "false", None),
               ("Paprika", 2006, "true", 4)],
     "report": "the mean score across seen films",
     "report_expect": "4.5 from Stalker and Paprika"},
    {"key": "errand", "noun": "errand", "plural": "errands",
     "title": "Errand batching list",
     "goal": "an errand list batchable by zone",
     "attrs": [("title", ":string", True, False), ("zone", ":keyword", False, False),
               ("minutes", ":int", False, False), ("urgent?", ":boolean", False, False)],
     "seeds": [("passport photos", ":downtown", 20, "true"), ("compost bags", ":hardware", 10, "false"),
               ("key copy", ":hardware", 15, "false")],
     "report": "total minutes per zone",
     "report_expect": "downtown 20, hardware 25"},
]

# Preambles for the fresh plan-lay-down message — REWORDED relative to the
# held-out stimulus (the guard verifies no shared 8-gram).
PREAMBLES = [
    ("Below is the session plan. Record it as your durable plan before anything "
     "else, then take the steps strictly one at a time and close a step only "
     "once its outcome really holds."),
    ("Start by writing this plan down as your durable plan. After that, work "
     "the steps in order — never close a step before its check passes."),
    ("Treat the following as this session's plan: persist it first, then "
     "advance step by step, closing each one the moment its check is true."),
    ("Here's what this session should accomplish. Durably record the plan "
     "up front; only then begin, finishing and closing steps individually."),
]

STEP_LEADS = [
    "Design a structured shape for {plural} ({attr_words}) — expect: a probe {noun} stores and reads back whole.",
    "Model {plural} as structured data ({attr_words}) — expect: one probe {noun} round-trips intact.",
    "Register a schema for {plural} covering {attr_words} — expect: a probe {noun} written then read back matches.",
]
STEP_SEEDS = [
    "Store these {n} seed {plural}: {seed_words} — expect: a lookup returns exactly {n} {plural}.",
    "Record the {n} starter {plural}: {seed_words} — expect: querying finds all {n}.",
    "Load {n} initial {plural}: {seed_words} — expect: exactly {n} rows come back.",
]
STEP_REPORTS = [
    "Report {report} — expect: {report_expect}, computed by querying what was stored, never retyped by hand.",
    "Produce {report} — expect: {report_expect}, computed from what the database holds.",
    "Answer from the data: {report} — expect: {report_expect}.",
]


# ---------------------------------------------------------------- helpers

class Ids:
    """Fresh id minting with a reserved (held-out) blacklist."""

    def __init__(self, rng, banned):
        self.rng = rng
        self.banned = banned
        self.minted = set()

    def _mk(self, minutes):
        mmdd = "0712" if minutes < 24 * 60 else "0713"
        hh, mm = (minutes % (24 * 60)) // 60, minutes % 60
        while True:
            chars = "".join(self.rng.choice(string.ascii_letters + string.digits)
                            for _ in range(3))
            i = f"{chars}-26{mmdd}{hh:02d}{mm:02d}"
            if i not in self.banned and i not in self.minted:
                self.minted.add(i)
                return i

    def clock(self):
        """A per-arc monotone clock: successive mints advance 1-4 minutes."""
        minutes = self.rng.randrange(0, 2 * 24 * 60 - 240)
        rng = self.rng

        class Clock:
            def mint(self):
                nonlocal minutes
                minutes += rng.randrange(1, 5)
                return Ids._mk(self_outer, minutes)
        self_outer = self
        return Clock()

    def mint(self):
        return self._mk(self.rng.randrange(0, 2 * 24 * 60))


def ts_from_id(i):
    hh, mm = i[-4:-2], i[-2:]
    return f"{hh}:{mm}:{random.Random(i).randrange(0, 60):02d}"


def date_from_id(i):
    mm, dd = i[6:8], i[8:10]
    return f"2026-{mm}-{dd} {i[-4:-2]}:{i[-2:]}"


def attr_words(dom):
    return ", ".join(a[0] for a in dom["attrs"])


def seed_words(dom):
    parts = []
    for s in dom["seeds"]:
        parts.append(" ".join(str(x) for x in s if x is not None))
    return "; ".join(parts)


def fresh_message(dom, rng, n_steps=3):
    pre = rng.choice(PREAMBLES)
    steps = [
        rng.choice(STEP_LEADS).format(plural=dom["plural"], noun=dom["noun"],
                                      attr_words=attr_words(dom)),
        rng.choice(STEP_SEEDS).format(n=len(dom["seeds"]), plural=dom["plural"],
                                      seed_words=seed_words(dom)),
        rng.choice(STEP_REPORTS).format(report=dom["report"],
                                        report_expect=dom["report_expect"]),
    ][:n_steps]
    lines = [f"{i + 1}. {s}" for i, s in enumerate(steps)]
    return (f"{pre}\n\n# {dom['title']}\nGoal: {dom['goal']}\n"
            + "\n".join(lines) + "\n"), steps


def preview(text, n=80):
    flat = text.replace("\n", " ")
    return flat[:n].rstrip() + " …" if len(flat) > n else flat


def plan_block(lines):
    return ";;; ┌─ plan ─\n" + "\n".join(lines) + "\n;;; └─ end plan ─"


def transcript_block(ns, events):
    return (";;; ┌─ transcript ─\n" + transcript_header(ns) + "\n\n"
            + "\n".join(events) + "\n;;; └─ end transcript ─")


def user_event(msg_id, msg, new=True):
    tag = " (NEW — unanswered; respond to this)" if new else ""
    return f';;; ◀ from user @ {ts_from_id(msg_id)} [{msg_id}]{tag} — "{msg}"'


def outbound_event(to, msg_id, msg):
    return f'; ▶ to {to} @ {ts_from_id(msg_id)} [{msg_id}] — "{msg}"'


def context_of(plan_lines, ns, events):
    return plan_block(plan_lines) + "\n\n" + transcript_block(ns, events)


# ---------------------------------------------------------------- cards

def load_fn_index():
    idx = json.loads(FN_INDEX.read_text())
    return {f["seon.fn/sym"]: f for f in idx["fns"]}


def card_text(fn):
    name = fn["seon.fn/sym"].rsplit("/", 1)[1]
    doc1 = (fn.get("seon.fn/doc") or "").split("\n")[0].strip() or "…"
    arglists = fn.get("seon.fn/arglists") or "([])"
    inner = arglists.strip()[1:-1].strip()  # drop the outer ( ... )
    # split top-level [...] vectors
    vecs, depth, cur = [], 0, ""
    for ch in inner:
        cur += ch
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
            if depth == 0:
                vecs.append(cur.strip())
                cur = ""
    if len(vecs) <= 1:
        body = f"{vecs[0] if vecs else '[]'} …"
    else:
        body = " ".join(f"({v} …)" for v in vecs)
    doc1 = doc1.replace('"', "'")
    return f'(defn {name} "{doc1}" {body})'


CARD_FNS_BY_INTENT = {
    "reconcile": ["my.plan/reconcile!", "my.plan/plan!"],
    "plan!": ["my.plan/plan!", "my.plan/active!"],
    "register": ["seon.schema/register!", "seon.schema/registered-schemas"],
    "transact": ["seon.db/transact!", "seon.db/query"],
    "query": ["seon.db/query", "my.data/group-sum", "seon.db/pull"],
    "done": ["my.plan/done!", "my.plan/active!"],
    "step": ["my.plan/step!", "my.plan/needs!"],
    "inspect": ["my.plan/list-open", "my.plan/tree", "my.plan/document"],
    "finish": ["seon.agent.lifecycle/complete", "seon.agent.message/user"],
    "planner": ["my.plan/document", "my.plan/reconcile!", "seon.agent.message/agent"],
}


def cards_for(fn_index, rng, intents, n_total=None):
    n_total = n_total or rng.randrange(4, 7)
    want = []
    for it in intents:
        for sym in CARD_FNS_BY_INTENT.get(it, []):
            if sym in fn_index and sym not in want:
                want.append(sym)
    pool = [s for s in fn_index if s not in want]
    rng.shuffle(pool)
    syms = (want + pool)[:max(n_total, len(want))]
    rng.shuffle(syms)
    return [card_text(fn_index[s]) for s in syms]


# ---------------------------------------------------------------- stages

def register_forms(dom):
    forms = []
    for (name, typ, ident, opt) in dom["attrs"]:
        attr = f":my.{dom['key']}/{name}"
        if ident:
            forms.append(f"(schema/register! {attr} [{typ} {{:seon.db/identity true}}])")
        else:
            forms.append(f"(schema/register! {attr} {typ})")
    return forms


def seed_tx_map(dom, seed):
    kv = []
    for (name, typ, _, _), val in zip(dom["attrs"], seed):
        if val is None:
            continue
        if typ == ":int":
            v = str(val)
        elif typ == ":keyword":
            v = str(val)
        elif typ == ":boolean":
            v = str(val).lower()
        else:
            v = json.dumps(val)
        kv.append(f":my.{dom['key']}/{name} {v}")
    return "{" + " ".join(kv) + "}"


def build_arc_situations(dom, fn_index, ids, rng, variants):
    out = []

    for v in range(variants):
        vrng = random.Random(rng.random())
        clock = ids.clock()
        agent = clock.mint()
        ns_agent = f"my.agent.{agent}"
        msg_id = clock.mint()
        message, steps = fresh_message(dom, vrng)
        root_id = clock.mint()
        step_ids = [clock.mint() for _ in steps]
        mint = clock.mint

        # ---- fresh: lay the plan down
        pl = [f"; PLAN «{preview(message)}»",
              f"; → next ready: {root_id} «{preview(message)}» — 0 of 1 steps done",
              PLAN_COACH,
              f"; ☐ ✉ {root_id} [{date_from_id(root_id)}] {preview(message)}"]
        ev = [user_event(msg_id, message, new=True)]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "fresh",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["reconcile", "finish"]),
            "intended_heads": ["plan/reconcile!", "plan/plan!"],
            "gold_prefix": f"(in-ns '{ns_agent})\n{REQUIRE_FORM}",
            "mech_target": "(plan/reconcile! {:my.plan/markdown "
            + json.dumps(f"# {dom['title']}\nGoal: {dom['goal']}\n"
                         + "".join(f"- [ ] {i + 1}. {s}\n" for i, s in enumerate(steps)))
            + "})",
            "abstain": False,
        })

        # ---- schema: step 1
        pl = [f"; PLAN «{dom['title']}» — goal: {dom['goal']}",
              f"; → next ready: {step_ids[0]} «{steps[0][:70]}» — 0 of {len(steps)} steps done",
              PLAN_COACH]
        pl += [f"; ☐ {sid} [{date_from_id(sid)}] {st[:70]}"
               for sid, st in zip(step_ids, steps)]
        ev = [user_event(msg_id, message, new=False),
              f"{REQUIRE_FORM} ⟹ nil",
              f"; step 1: design the {dom['noun']} shape"]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "schema",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["register", "done"]),
            "intended_heads": ["schema/register!"],
            "gold_prefix": "",
            "mech_target": "\n".join(register_forms(dom)),
            "abstain": False,
        })

        # ---- seed: step 2 (schema landed; registers in transcript)
        reg = register_forms(dom)
        pl = [f"; PLAN «{dom['title']}» — goal: {dom['goal']}",
              f"; → NOW (active): {step_ids[1]} «{steps[1][:70]}» — 1 of {len(steps)} steps done",
              f";   verify before done!: a lookup returns exactly {len(dom['seeds'])} {dom['plural']}",
              PLAN_COACH,
              f"; ▶ {step_ids[1]} [{date_from_id(step_ids[1])}] {steps[1][:70]}"]
        pl += [f"; ☐ {sid} [{date_from_id(sid)}] {st[:70]}"
               for sid, st in zip(step_ids[2:], steps[2:])]
        ev = [user_event(msg_id, message, new=False)]
        ev += [f"{f} ⟹ :my.{dom['key']}/{a[0]}" for f, a in zip(reg, dom["attrs"])]
        ev += [f'(my.plan/done! {{:my.plan/id "{step_ids[0]}"}}) ⟹ {{:my.plan/ok? true, :my.plan/do '
               "…⟨⚠ TRUNCATED at 10 of 24 tokens — the DISPLAY is clipped, the live value is COMPLETE⟩",
               f"; step 2: store the seed {dom['plural']}"]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "seed",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["transact", "done"]),
            "intended_heads": ["db/transact!"],
            "gold_prefix": "",
            "mech_target": ("(db/transact! {:seon.db/tx-data ["
                            + " ".join(seed_tx_map(dom, s) for s in dom["seeds"]) + "]})"),
            "abstain": False,
        })

        # ---- report: step 3 (transact landed)
        tx = (f"(db/transact! {{:seon.db/tx-data ["
              + " ".join(seed_tx_map(dom, s) for s in dom["seeds"]) + "]}})")
        pl = [f"; PLAN «{dom['title']}» — goal: {dom['goal']}",
              f"; → NOW (active): {step_ids[2]} «{steps[2][:70]}» — 2 of {len(steps)} steps done" if len(steps) > 2
              else f"; → next ready: — — {len(steps)} of {len(steps)} steps done",
              PLAN_COACH]
        if len(steps) > 2:
            pl += [f"; ▶ {step_ids[2]} [{date_from_id(step_ids[2])}] {steps[2][:70]}"]
        ev = [user_event(msg_id, message, new=False),
              f"{tx}\n{TX_OK_GLYPH}",
              f'(my.plan/done! {{:my.plan/id "{step_ids[1]}"}}) ⟹ {{:my.plan/ok? true, :my.plan/do '
              "…⟨⚠ TRUNCATED at 10 of 24 tokens — the DISPLAY is clipped, the live value is COMPLETE⟩",
              f"; step 3: {dom['report']}"]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "report",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["query", "done"]),
            "intended_heads": ["db/query", "data/group-sum", "data/rows", "data/sum-by"],
            "gold_prefix": "",
            "abstain": False,
        })

        # ---- bookkeep-done: work landed, close + take next
        pl = [f"; PLAN «{dom['title']}» — goal: {dom['goal']}",
              f"; → NOW (active): {step_ids[0]} «{steps[0][:70]}» — 0 of {len(steps)} steps done",
              PLAN_COACH,
              f"; ▶ {step_ids[0]} [{date_from_id(step_ids[0])}] {steps[0][:70]}"]
        pl += [f"; ☐ {sid} [{date_from_id(sid)}] {st[:70]}"
               for sid, st in zip(step_ids[1:], steps[1:])]
        ev = [user_event(msg_id, message, new=False)]
        ev += [f"{f} ⟹ :my.{dom['key']}/{a[0]}" for f, a in zip(reg, dom["attrs"])]
        probe = seed_tx_map(dom, dom["seeds"][0])
        ev += [f"(db/transact! {{:seon.db/tx-data [{probe}]}})\n{TX_OK_GLYPH}",
               f"; probe {dom['noun']} stored and read back — step 1 outcome holds"]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "bookkeep-done",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["done"]),
            "intended_heads": ["plan/done!", "plan/active!"],
            "gold_prefix": "",
            "mech_target": (f'(plan/done! {{:my.plan/id "{step_ids[0]}"}})\n'
                            f'(plan/active! {{:my.plan/id "{step_ids[1]}"}})'),
            "abstain": False,
        })

        # ---- bookkeep-step: discovered work mid-arc
        discovered = {
            "recipe": "backfill missing rating on shakshuka",
            "planting": "record the second north-bed succession sowing",
            "invoice": "chase the overdue Marberly & Co balance",
            "trip": "reconcile the odometer gap on the van",
            "episode": "dedupe the double-added Field Notes episode",
            "plant": "verify Spike's interval after repotting",
            "gamenight": "record the tiebreak rule we agreed on",
            "sighting": "confirm the ridge varied thrush with a photo",
            "tool": "add the missing due date on the tile saw",
            "donation": "attach the missing Open Atlas receipt",
            "subscription": "double-check the PaperTrail Pro cancellation",
            "brew": "retry keemun at a shorter steep",
            "chore": "swap Jonas and Ren for next week",
            "film": "score The Fall after watching",
            "errand": "add the pharmacy pickup to the downtown batch",
        }[dom["key"]]
        ev = [user_event(msg_id, message, new=False),
              f"{tx}\n{TX_OK_GLYPH}",
              f"; found while storing: {discovered} — needs its own step under this plan"]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "bookkeep-step",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["step", "done"]),
            "intended_heads": ["plan/step!"],
            "gold_prefix": "",
            "mech_target": ("(plan/step! {:my.plan/title " + json.dumps(discovered)
                            + f' :my.plan/parent [:my.plan/id "{step_ids[0]}"]}})'),
            "abstain": False,
        })

        # ---- inspect: orientation turn (which steps are open?)
        pl = [f"; PLAN «{dom['title']}» — goal: {dom['goal']}",
              f"; → next ready: {step_ids[1]} «{steps[1][:70]}» — 1 of {len(steps)} steps done",
              PLAN_COACH,
              f"; ☐ {step_ids[1]} [{date_from_id(step_ids[1])}] {steps[1][:70]}",
              f"; ☐ {step_ids[-1]} […⟨block clipped to 200 of 231 tokens⟩"]
        ask_id = mint()
        ev = [user_event(msg_id, message, new=False),
              user_event(ask_id, "Where does the plan stand? List what is still open before you continue.", new=True)]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "inspect",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["inspect"]),
            "intended_heads": ["plan/list-open", "plan/tree", "plan/document"],
            "gold_prefix": "",
            "mech_target": f'(plan/list-open {{:seon.agent/id "{agent}"}})',
            "abstain": False,
        })

        # ---- stuck: failed attempts, fix needed
        bad = (f"(schema/register! :{dom['key']}\n  [:map [::{dom['attrs'][0][0]} :string]\n"
               f"   [::{dom['attrs'][1][0]} :string]])")
        pl = [f"; PLAN «{dom['title']}» — goal: {dom['goal']}",
              f"; → NOW (active): {step_ids[0]} «{steps[0][:70]}» — 0 of {len(steps)} steps done",
              f";   verify before done!: a probe {dom['noun']} stores and reads back whole",
              PLAN_COACH,
              f"; ▶ {step_ids[0]} [{date_from_id(step_ids[0])}] {steps[0][:70]}"]
        ev = [user_event(msg_id, message, new=False),
              f"{bad}\n⟹ ✗ :malli.core/invalid-schema",
              "; errors are values — read it and adapt; nothing threw at you (the failure is a value you can inspect and handle).",
              f"{bad}\n⟹ ✗ :malli.core/invalid-schema",
              "; per-attribute registration is the pattern — one register! per attr, namespaced keys"]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "stuck",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["register"]),
            "intended_heads": ["schema/register!"],
            "gold_prefix": "",
            "mech_target": "\n".join(register_forms(dom)),
            "abstain": False,
        })

        # ---- finish: all steps done -> summary + complete
        pl = ["; Recently completed — already done, do NOT redo:"]
        pl += [f"; ✓ [{date_from_id(sid)}] {st[:70]}" for sid, st in zip(step_ids, steps)]
        qres = f"; {dom['report']}: {dom['report_expect']}"
        ev = [user_event(msg_id, message, new=False),
              f"(seon.db/query {{:query '[:find ?x :where [?e :my.{dom['key']}/{dom['attrs'][0][0]} ?x]]}}) "
              f"⟹ #{{{' '.join(json.dumps(str(s[0])) for s in dom['seeds'])}}}",
              qres,
              f'(my.plan/done! {{:my.plan/id "{step_ids[-1]}"}}) ⟹ {{:my.plan/ok? true, :my.plan/do '
              "…⟨⚠ TRUNCATED at 10 of 24 tokens — the DISPLAY is clipped, the live value is COMPLETE⟩"]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "finish",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["finish"]),
            "intended_heads": ["message/user", "lifecycle/complete"],
            "gold_prefix": "",
            "mech_target": ("(seon.agent.message/user "
                            + json.dumps(f"{dom['title']} complete: {dom['report_expect']}.")
                            + ")\n(seon.agent.lifecycle/complete "
                            + json.dumps(f"{dom['title']} complete") + ")"),
            "abstain": False,
        })

        # ---- planner: consult on a worker's plan
        worker = mint()
        wroot = mint()
        pmsg_id = mint()
        pmsg = (f'Acting as planner: worker "{worker}" owns a {dom["noun"]} plan '
                f"(root {wroot}) that has gone stale. Pull that worker's whole open plan via "
                f'(my.plan/document {{:seon.agent/id "{worker}"}}) and revise only that subtree.')
        pl = ["; Recently completed — already done, do NOT redo:",
              f"; ✓ [{date_from_id(root_id)}] planner consult for worker {mint()}…"]
        ev = [user_event(pmsg_id, pmsg, new=True)]
        out.append({
            "family": "arc", "domain": dom["key"], "stage": "planner",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["planner"]),
            "intended_heads": ["plan/document"],
            "gold_prefix": "",
            "mech_target": f'(my.plan/document {{:seon.agent/id "{worker}"}})',
            "abstain": False,
        })

        # ---- abstain-a: outbound question sent, nothing pending
        outb_id = mint()
        pl = ["; Recently completed — already done, do NOT redo:"]
        pl += [f"; ✓ [{date_from_id(sid)}] {st[:70]}" for sid, st in zip(step_ids, steps)]
        ev = [user_event(msg_id, message, new=False),
              outbound_event("user", outb_id,
                             f"{dom['title']} finished: {dom['report_expect']}. Anything else before I close out?")]
        out.append({
            "family": "abstain", "domain": dom["key"], "stage": "abstain-awaiting",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["finish", "inspect"]),
            "intended_heads": [],
            "gold_prefix": "",
            "abstain": True,
        })

        # ---- abstain-b: completed, :idle already returned
        ev = [user_event(msg_id, message, new=False),
              outbound_event("user", mint(),
                             f"{dom['title']} done — {dom['report_expect']}."),
              f'(seon.agent.lifecycle/complete "{dom["title"]} done") ⟹ :idle']
        out.append({
            "family": "abstain", "domain": dom["key"], "stage": "abstain-idle",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": cards_for(fn_index, vrng, ["finish"]),
            "intended_heads": [],
            "gold_prefix": "",
            "abstain": True,
        })

    return out


# ---------------------------------------------------------------- kt2b family

def build_kt2b_situations(fn_index, ids, rng, domains):
    cases = json.loads(KT2B_CASES.read_text())["cases"]
    out = []
    for case in cases:
        exp = case.get("expected")
        if not exp or exp not in fn_index:
            continue
        query = case["query"]
        clock = ids.clock()
        agent = clock.mint()
        # Re-mint embedded ids; keep a map so the plan block can carry them.
        id_map = {}
        for old in sorted(set(ID_RE.findall(query))):
            id_map[old] = clock.mint()
        for old, new in id_map.items():
            query = query.replace(old, new)
        ns_agent = f"my.agent.{agent}"
        msg_id = clock.mint()
        dom = rng.choice(domains)
        pl = [f"; PLAN «{dom['title']}» — goal: {dom['goal']}",
              PLAN_COACH]
        if id_map:
            pl += [f"; ☐ {new} [{date_from_id(new)}] {dom['noun']} follow-up"
                   for new in id_map.values()]
        else:
            pl = ["; Recently completed — already done, do NOT redo:",
                  f"; ✓ [{date_from_id(msg_id)}] {dom['title']} groundwork…"]
        ev = [user_event(msg_id, query, new=True)]
        ns_suffix = exp.rsplit("/", 1)[0].rsplit(".", 1)[-1]
        name = exp.rsplit("/", 1)[1]
        want_cards = [exp] + CARD_FNS_BY_INTENT.get("inspect", [])
        vrng = random.Random(rng.random())
        pool = [s for s in fn_index if s != exp]
        vrng.shuffle(pool)
        syms = [exp] + pool[: vrng.randrange(3, 6)]
        vrng.shuffle(syms)
        out.append({
            "family": "kt2b", "domain": dom["key"], "stage": f"kt2b-{case['work_kind']}",
            "agent": agent, "current_ns": ns_agent,
            "context": context_of(pl, ns_agent, ev),
            "cards": [card_text(fn_index[s]) for s in syms],
            "intended_heads": [f"{ns_suffix}/{name}"],
            "gold_prefix": "",
            "abstain": False,
            "kt2b_case": case["id"],
        })
    return out


# ---------------------------------------------------------------- guard

def heldout_material():
    ids, msgs = set(), []
    for p in (V1, V2):
        for line in p.read_text().splitlines():
            row = json.loads(line)
            blob = json.dumps(row)
            ids.update(ID_RE.findall(blob))
            for m in re.finditer(r'◀ from user @ [^"]*— "(.*?)"\n?;;;', row["context"], re.DOTALL):
                msgs.append(m.group(1))
            # also single-line matches (no closing bracket capture)
            for m in re.finditer(r'◀ from user[^\n]*— "([^"]*)', row["context"]):
                msgs.append(m.group(1))
    return ids, msgs


def word_ngrams(text, n=8):
    words = re.findall(r"[a-z0-9']+", text.lower())
    return {" ".join(words[i:i + n]) for i in range(len(words) - n + 1)}


def run_guard(situations, heldout_ids, heldout_msgs):
    ho_grams = set()
    for m in heldout_msgs:
        ho_grams |= word_ngrams(m)
    problems = []
    gen_ids = set()
    for s in situations:
        blob = s["context"] + " " + " ".join(s["cards"])
        sids = set(ID_RE.findall(blob))
        gen_ids |= sids
        clash = sids & heldout_ids
        if clash:
            problems.append((s["sid"], "id-clash", sorted(clash)[:3]))
        for m in re.finditer(r'◀ from user[^\n]*— "(.*?)"(?:\n;;;|\Z)', s["context"], re.DOTALL):
            grams = word_ngrams(m.group(1))
            hit = grams & ho_grams
            if hit:
                problems.append((s["sid"], "ngram-clash", sorted(hit)[:2]))
    return problems, gen_ids


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variants", type=int, default=3, help="arc instantiations per domain")
    ap.add_argument("--seed", type=int, default=20260712)
    args = ap.parse_args()

    rng = random.Random(args.seed)
    fn_index = load_fn_index()
    heldout_ids, heldout_msgs = heldout_material()
    ids = Ids(rng, heldout_ids)

    situations = []
    for dom in DOMAINS:
        situations.extend(build_arc_situations(dom, fn_index, ids, rng, args.variants))
    situations.extend(build_kt2b_situations(fn_index, ids, rng, DOMAINS))

    for i, s in enumerate(situations):
        s["sid"] = f"{s['family']}-{s['domain']}-{s['stage']}-{i:04d}"

    problems, gen_ids = run_guard(situations, heldout_ids, heldout_msgs)
    if problems:
        for p in problems[:20]:
            print("GUARD:", p)
        raise SystemExit(f"leakage guard failed: {len(problems)} problems")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w") as fh:
        for s in situations:
            fh.write(json.dumps(s, ensure_ascii=False) + "\n")

    from collections import Counter
    fam = Counter(s["family"] for s in situations)
    stg = Counter(s["stage"].split("-")[0] if s["family"] == "kt2b" else s["stage"]
                  for s in situations)
    tok = sorted(len(s["context"]) // 4 for s in situations)
    print(f"situations: {len(situations)}  families: {dict(fam)}")
    print(f"stages: {dict(stg)}")
    print(f"context tokens p50={tok[len(tok)//2]} p90={tok[int(.9*len(tok))]} max={tok[-1]}")
    print(f"fresh ids minted: {len(gen_ids)}; guard clean vs {len(heldout_ids)} held-out ids, "
          f"{len(heldout_msgs)} held-out messages")


if __name__ == "__main__":
    main()
