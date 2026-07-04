---
type: research
status: draft
tags: [research, agent]
---

# Error blame + strict gate — a design proposal

**Status: draft, decision pending.** The owner asked for a way to "fail fast
and loud in development to catch our own fuckups, and also let agents fail
while working without crashing things." This doc is the decision surface —
not yet ruled on.

## TL;DR

Errors in the pod come from two populations that today share one shape and
one fate (swallow, maybe log, keep rendering): **agent-caused** (bad verb
input, a hallucinated symbol, a malformed form) — expected, the agent's
learning signal, already surfaces as `:seon/error` envelopes and warnings
sections. **Core-caused** (our wrapper throws, our render fn dies, a
fallback silently fires) — a bug, indistinguishable today from the first
population because both land in the same `catch :default` and both get the
same "degrade gracefully, keep the pod alive" treatment.

The proposal: tag every caught error with `:seon.error/blame` (`:agent` |
`:core`) at the catch site. Nothing else about the never-crash doctrine
changes — the pod still never crashes, agents still get their envelopes.
What changes is that a NEW derived surface (reactive-context section) reacts
to `:blame :core` datoms, and a strictness dial (`:seon.config/strict?`,
dev-default `true`) makes the dev-facing gates (hook, `bin/test-cljs`, gym
scorecard, eval suite) fail loud on any run that accumulated one — even when
every assertion in that run was green. Agent-blamed errors never gate
anything; they are data the agent reads, same as today.

## The tension it resolves

The never-crash doctrine (`src/seon/render/sci.cljs:1-30`, the tile-isolation
PRD) is correct and non-negotiable: the pod is a single Node thread: one
uncaught throw blanks every agent + the UI. So every risky boundary —
SCI-bounded tile invocation, cljs.js self-host eval, malli instrumentation —
converts failures into `:seon/error` values in place and keeps running. This
is right for an agent hallucinating a symbol. It is ALSO currently the fate
of a genuine bug in our own wrapper code — the audit's M24 finding names
this exactly:

> `catch :default` counts: 22 in `eval.cljs`, 10 in `client.cljs`, 9 each in
> `render.cljs` and `render/sci.cljs`. The never-crash doctrine is CORRECT
> for a single-threaded pod … But it is the substrate that makes #3-class
> silent degradation POSSIBLE: when every layer degrades gracefully, a
> defect's only trace is a warn line in a log nobody tails.
>
> — `docs/prds/agent-ctx/research/magic-systems-audit-2026-07-02.md`, §24
> "The fail-soft substrate — a doctrine-level observation" (line 550)

The audit's own recommendation is a review lens, not a mechanism: "watch for
`catch :default _ nil` vs catch-to-error-value." A lens only works if
someone is looking. The gap is structural: there is currently NO way for a
core bug to fail a green test run, a green hook pass, or a green gym
scorecard, because the only shape an error takes (`:seon/error`, verified
below) doesn't distinguish "the agent typo'd" from "our fallback fired."

Confirmed empirically: `grep -c 'catch :default'` across the four files
totals **53** sites (`render/sci.cljs` 11, `eval.cljs` 22, `client.cljs` 10,
`render.cljs` 9 — close to the audit's counts, small drift from two days of
churn). None currently carry a blame tag.

## The mechanism

### 1. Two error populations, one shape

`seon.error/->map` (`src/seon/error.cljs:60-75`) is the sole error→map
converter, already producing a stable envelope:

```clojure
{:seon.error/message   string
 :seon.error/ex-data   map     ; this level's ex-data
 :seon.error/data      map     ; ex-data merged across the whole cause chain
 :seon.error/stack     string  ; truncated ~4kb
 :seon.error/cause     map     ; recursive
 :seon.error/raw       any     ; opaque, the original error instance
 :seon.error/truncated true}   ; optional, set at cause-chain depth 5
```

The malli-instrumentation envelope (`src/seon/error/instrument.cljc:63-93`,
`:seon.error/kind` + the `:seon.error.malli/*` family) already rides inside
`:seon.error/data` via the SAME flattening path (per its docstring, line
14: "the throw propagates through cljs.js, lands in `eval`'s catch, gets
flattened into `(:seon.error/data error)` by `seon.error/->map`"). Blame
is one more scalar attribute on this existing shape:

```clojure
(schema/register! :seon.error/blame [:enum :agent :core])
```

Set at the catch site, not derived later — the catch site is the only place
that knows what was being called. Default `:core` when uncertain (a bug we
didn't classify is still a bug; an unclassified `:agent` blame would hide
one).

**Coarse initial rule**, using a discriminator that already exists:
`seon.render.sci/agent-authored-sym?` (`src/seon/render/sci.cljs:98-113`)
already answers "is this symbol agent-authored" for exactly this reason —
routing `my.*` fns through the SCI-bounded path vs the compiled core path:

```clojure
(defn agent-authored-sym? [sym]
  (boolean
    (and (qualified-symbol? sym)
         (let [ns (namespace sym)]
           (not (or (= ns "seon")
                    (re-find #"^(seon|clojure|cljs|sci|goog)\." ns)))))))
```

Blame reuses this: catching an error while invoking an agent-authored
symbol → `:agent`; everything else (a `seon.*`/`clojure.*`/`cljs.*` call
throwing) → `:core`. Misclassifications are expected at the boundary
(agent code calling into our wrapper, our wrapper calling agent code) — they
surface as data (a `:core`-blamed error whose stack trace is entirely
`my.*` frames, or vice versa) and get re-blamed as a follow-up fix, not
argued about up front.

### 2. The iron rule: nothing is caught without becoming data

Every `catch :default` must produce a `:seon/error` value that lands in the
DB or a derived surface — never console-only, never silent-continue. Most of
the 53 sites already do this (`bounding-error` in `render/sci.cljs:560-568`
is the canonical example — SCI failure → `:seon/error` block "in place,"
never the unbounded compiled path). The violation class the audit flags is
`catch :default _ nil` — swallow-to-nil with no trace at all (e.g.
`render/sci.cljs:159,230,264,287,305,318,346,414`; `eval.cljs:487,570,1206,
1252,1257,1770,1820`). These aren't all bugs — some are legitimate "try this,
fall back silently" parse probes (e.g. `eval.cljs:1206`, a best-effort
symbol-resolution attempt) — but each one is currently UNAUDITABLE: there is
no way to tell probe-and-ignore from swallowed-defect by reading the catch
site.

Mechanical check: the dev hook gets a lint rule — a `catch :default` block
whose body is a bare literal (`nil`, `false`, `{}`, `#{}`) with no call to
an error-constructing fn (`error/->map`, `bounding-error`, an explicit
`{:seon.error/blame …}` literal) is a finding, not a silent pass. It doesn't
have to become a DB write in every case (a swallow-to-nil is fine when the
caller's contract is "nil means try the next thing") — it has to become an
EXPLICIT, reviewed decision at that site: either construct an error value,
or annotate why not (e.g. `;; probe: absence is expected, not an error`).
The hook enforces "decided," not "always writes."

### 3. Fail-fast is a derived reaction, not a second code path

Per `docs/seon/concepts/reactive-context.md` (the load-bearing principle:
"Sections are functions of the DB at render time... If the state goes away,
the surface goes away"), a core-blamed error is just another attribute
presence query, same shape as the doc's own `warnings-section` example
(lines ~44-66):

```clojure
(defn core-errors-section
  "Render unfixed core-blamed errors since the latest user message."
  [{:seon.db/keys [db]}]
  (let [core-errs (db/query
                     {:seon.db/db db
                      :seon.db/query
                      '[:find ?eid
                        :where
                        [?e :seon.error/blame :core]
                        [?e :seon/error ?eid]]})]  ; join shape TBD at impl time
    (when (seq core-errs)
      (str "<CORE-BUG " (count core-errs) " unresolved>"))))
```

No acknowledgement state, no "mark as seen" flag — the section is blank the
moment the last core-blamed error datom predates the fix (same self-healing
property the reactive-context doc names as the reason notification queues
are banned). This is the same mechanism as the existing warnings section
(`reactive-context.md` lines 44-66), not a new one — it's a new query over
an existing attribute.

`:seon.config/strict?` (dev-default `true`) lives in the manifest — per the
owner's config triage, config is the one hand-maintained home, so this is
NOT a new hand-maintained list, it's the sanctioned place for exactly this
kind of dial. Under strict, the gates check "did this run accumulate ANY
new `:blame :core` error datom" as an ADDITIONAL pass/fail axis, independent
of whatever assertions the run itself made:

- **dev hook** — after reload/test, query for core-blamed datoms since the
  hook's own start-tx; fail the hook run if any exist.
- **`bin/test-cljs` wrapper** — same query bracketing the whole suite run.
- **gym scorecard / eval suite** — "zero core errors during the run"
  becomes a free bench axis (point 5 below) — a sample can score 1.0 on its
  assertions and still fail strict-gate if the harness itself threw.

The pod itself never gates on this — it keeps rendering, keeps serving
agents, exactly as today. Strict-gate is a property of the CI-shaped
surfaces that wrap a run, not the runtime.

### 4. Instrumentation is already the boundary — blame decided by caller context

The instrumentation wrapper (`src/seon/instrument.cljc`, `report-fn` in
`src/seon/error/instrument.cljc:242-249`) is a single mechanism that already
fires on EVERY `:malli/schema`-annotated fn call, agent-invoked or
core-internal — it doesn't currently distinguish them. It already has
access to the caller's identity: `db/current-agent-id`
(`src/seon/instrument.cljc:220`, wired as the `:seon.agent/id` accessor for
the envelope). Blame reuses that scope: an instrumentation violation while
inside an agent-verb call (the ALS-scoped `:seon.agent/id` is bound to the
calling agent AND the failing fn is `my.*`/agent-authored) → `:agent`,
folds into the agent's normal envelope, no gate. An instrumentation
violation on a `seon.*` fn — OUR contract broken by OUR code, or a `seon.*`
fn called with a shape only our own code could have produced — → `:core`,
strict-gated. One wrapper, `report-fn` (`error/instrument.cljc:242`), one
new field on the envelope it already throws (`ex-info (str type)
(explain-payload type data)` → add `:seon.error/blame` into
`explain-payload`'s cond-> at line 214).

### 5. What it retires

- **Warn-only guards as a class.** If every catch either produces an
  `:agent`-blamed envelope or a `:core`-blamed strict-gated datom, there is
  nothing left for a bare `console.warn`/log-line to communicate that isn't
  already structured data. (Logging itself doesn't go away — it's still
  useful for humans tailing `logs/pod.log` — but it stops being the ONLY
  trace, which is the M24 complaint verbatim.)
- **Silent fallbacks as a class**, for the `:core` population specifically.
  A fallback firing because an agent gave bad input is fine and stays
  silent-to-the-gate (it's `:agent`-blamed, it's the point of the fallback).
  A fallback firing because OUR code hit an unexpected shape is now loud.
- **A free bench axis.** The eval/gym lane (my lane, per
  `feedback_scorers_gate_correctness_not_style`) gets "zero core errors
  during the run" without writing a new scorer — it's the same strict-gate
  query, run against the sample's tx window.

### 6. Composition with turn-capture (commit `2ef14d12`)

Turn-capture already persists `:seon.agent.turn/rendered-as-of` (the
pre-turn basis-t) and the verbatim prompt/reply as `my.blob` refs. A
`:core`-blamed error at turn N, joined to that turn's `rendered-as-of` +
prompt blob, is a reproducible bug report with zero extra plumbing: replay
the exact rendered context that was live when our own code broke. This
composition is free — both mechanisms are attribute-presence queries over
the same eval/turn log; blame is just one more attribute to join on.

## What changes at the catch sites — migration sketch

53 `catch :default` sites across 4 files. One file per commit, in the order
the audit already established as the failure gradient (SCI env
reconstruction → general eval → client-boundary → render):

1. **`src/seon/render/sci.cljs`** (11 sites) — first because
   `agent-authored-sym?` and `bounding-error` already live here; the blame
   rule is nearly free to apply (SCI-bounded invocation of an agent symbol
   → `:agent`; env-reconstruction/init failure, lines 264-346, is core
   machinery reading OUR OWN stored ns/fn source → `:core`).
2. **`src/seon/eval.cljs`** (22 sites) — the largest file; the general
   eval/self-host catch boundary. Most sites wrap cljs.js compilation of
   agent-submitted forms (→ `:agent`); a minority wrap our own bootstrap
   compile-state or bulk-load machinery (→ `:core`, e.g. anything touching
   `:seon.fn/source`/`:seon.ns/source` reconstruction rather than the
   agent's live eval string).
3. **`src/seon/client.cljs`** (10 sites) — the wire/socket boundary; largely
   `:core` (talking to `wire-server`, reading `fs`), a few wrap agent-
   triggered actions.
4. **`src/seon/render.cljs`** (9 sites) — the block/slot renderer; mixed,
   same `agent-authored-sym?` discriminator applies (it's already imported
   here at lines 738/792/981 for the identical routing decision).

Each commit: add `:seon.error/blame` at every catch in that file, parity-
gate against `bin/test-cljs` (no behavior change — same error VALUES, one
new key), then move to the next file. Mechanical after the first file sets
the pattern.

## Open questions for the owner

1. **Blame-ambiguity: agent-authored fn inside our bounded runner.** When
   an agent's `my.*` tile fn throws while EXECUTING INSIDE
   `render.sci/bounding-error`'s try block, the throw is agent-caused
   (their code, their bug) but the catch site is core machinery. The
   proposal's rule (agent-authored-sym? → `:agent`) handles this case
   correctly as written. The harder case is the reverse: our SCI env-
   reconstruction code (`sci/eval-string*`, alias-parsing) throwing WHILE
   preparing to run agent code — is that `:core` (our reconstruction bug)
   or does the agent's presence make it ambiguous? Proposal's answer: it's
   `:core` (the throw happens before the agent's symbol is ever invoked,
   the discriminator is "what were we calling," not "whose turn is it").
   Confirm or override.
2. **Does `strict?` gate `bin/test-cljs` from day one, or warn-first?** The
   dial is dev-default `true` per this doc, meaning the FIRST run after
   shipping could fail the suite on pre-existing swallowed `:core` errors
   nobody has looked at yet (the 53-site sweep surfaces backlog, not just
   new bugs). Options: (a) ship strict-gate live immediately, accept a red
   suite until the sweep lands (forces the sweep); (b) ship the attribute +
   section but `strict?` defaults `false` until the sweep completes, then
   flip. (a) matches "fail fast and loud" more literally; (b) avoids a
   surprise red suite blocking unrelated work mid-sweep.
3. **Does the red surface belong on the root world only, or every agent's
   warnings block?** A `:core` bug is system-wide by construction (it's OUR
   code, not scoped to one agent's session) — the reactive-context doc's
   own pattern (cross-agent visibility is the default) argues for the root
   world AND every agent's warnings block seeing it, same as the existing
   `warnings-section` example already queries across all agents with no
   `:seon.agent/id` filter. Confirm this reuses the same "no filter = cross-
   agent" default, or whether core-bug visibility should be root-only to
   avoid drowning agents in a bug that isn't theirs to fix.

## Revision 2026-07-04 — owner discussion + time-travel grounding

Owner reviewed the draft in chat; the design below supersedes the
corresponding sections above. Grounding research (all claims cited to
source): [[error-time-travel-reproduction-2026-07-04]].

### Renames

- **`:seon.error/blame` → `:seon.error/fault`** (`[:enum :agent :core]`).
  "Blame" implies authorship attribution (git-blame answers who WROTE the
  line); we classify which population the failure belongs to. `origin` was
  rejected — collides with `:seon.db/origin` tx provenance.
- **The catch-site verb is `seon.error/record!`** — a NEW fn, not a change
  to `->map` (which stays the pure converter). `record!` classifies,
  structures, persists (fire-and-forget, never throws, never awaits;
  buffers in memory when the conn is down and flushes on the next
  successful write), and escalates per the dial. It is the iron rule as a
  fn: nothing is caught without becoming data.

### The dial: `:seon.config/on-core-error` (replaces boolean `strict?`)

`:crash | :gate | :log` — per-cluster config (manifest, the sanctioned
hand-maintained home):

- **`:crash`** (dev default, after the sweep lands) — a `:core`-fault
  error persists its datom FIRST, then exits the pod loudly. Fail fast on
  our own fuckups.
- **`:gate`** (CI/test default) — pod stays alive; hook / `bin/test-cljs` /
  scorecard fail any run that accumulated a new `:core`-fault datom.
- **`:log`** (prod/demo) — datom + derived section only.

Invariant in every mode: **`:agent`-fault errors never crash, never gate.**
Agents cannot reach the crash path no matter how badly they flail.

### Automatic capture — two layers

1. **The funnel**: catch sites call `record!`; the malli instrumentation
   `report-fn` (`error/instrument.cljc:242`) gets the same one-line change,
   covering every schema'd fn call.
2. **The net**: `process.on "uncaughtException"` / `"unhandledRejection"`
   → `record!` with fault `:core`. Anything escaping every catch is
   persisted with zero per-site work — the 53-site sweep improves
   classification, but nothing can silently vanish even before it lands.

### EDN stack traces

`cljs.stacktrace/parse-stacktrace :nodejs` (.cljc, already in the pod's
dep, no source maps needed) parses V8 stack strings into
`{:file :function :line :column}` frames. `record!` stores
`:seon.error/frames` (vector of `:seon.error.frame/*` maps) alongside the
existing raw string — traces become Datalog-queryable ("every core-fault
error whose top frame is in render/sci.cljs").

### Working backwards — point-in-time restore + reproduction

Verified in the research doc: history is ON everywhere active (store
config + pod peer config + a boot precondition that throws if off); GC
never erases history (only explicit `:db/purge`, which seon never issues);
`seon.db` ALREADY exposes `as-of`/`since`/`history`/`basis-t`, pod-local
with no wire round-trip; and the int that `basis-t` returns is the tx eid
that `as-of` takes — the same int `:seon.agent.turn/rendered-as-of`
already stores.

So the plan is a stamp + a join, not new machinery:

- **`record!` stamps `:seon.error/at`** — `(db/basis-t)` at the catch site
  (free; covers errors outside any turn: listeners, boot, SSE).
- **Reproduction recipe**: `(db/as-of db (:seon.error/at err))` → the
  exact db value the failing code saw → re-render the turn via
  `seon.agent.inspect/turn` (turn-capture's `rendered-as-of` + prompt blob
  give byte-exact context replay), or re-invoke the failing fn.
- **Gap to close (sweep rider): full args.** The instrumentation envelope
  stores only the FAILING arg (`got-edn` + `arg-index`); push-button
  re-invocation wants `:seon.error/args-edn` (bounded via
  `tokens/bounded-pr-str`). Until then, turn-scoped errors reproduce via
  the turn's eval form.
- **Known limit**: as-of gives read/render fidelity; reproducing a WRITE
  path replays against a scratch cluster, not the live store.

### Revised rollout order (answers open question 2)

1. Land `record!` + global handlers + frames + `:seon.error/at` + the dial
   at `:gate`.
2. The 4-file catch-site sweep (one commit per file, parity-gated).
3. Flip dev to `:crash`. Fail-fast arrives after the backlog is drained
   instead of blocking the tree mid-sweep.

Open questions 1 (ambiguity rule) and 3 (visibility scope) still stand.

## Cost estimate

- **Schema**: 1 `register!` call (`:seon.error/blame`), 1 file
  (`src/seon/error.cljs` or `error/instrument.cljc`).
- **Sweep**: ~53 catch sites across 4 files, mechanical after the pattern is
  set in file 1 — each site is "read the call it wraps, apply the
  `agent-authored-sym?` discriminator or its `:core`-default, add one key
  to the constructed error map." Estimate 1 file per commit, 4 commits,
  each parity-gated against `bin/test-cljs` (no assertion changes expected
  — same error shapes, one new attribute).
- **Section fn**: 1 new reactive-context section (`core-errors-section`),
  following the existing `warnings-section` pattern verbatim.
- **Gate wiring**: 3 call sites (dev hook, `bin/test-cljs` wrapper, gym
  scorecard) each add one query-and-fail check bracketing the run — no new
  mechanism, reuses the strict-gate query from point 3.
- **Lint rule**: 1 dev-hook check (bare-literal catch body with no error
  constructor call = finding) — mechanical AST-shape check, not a schema
  validator.

Total: small, additive, no retrofits to existing error consumers (the
`:seon/error` shape is unchanged; `:seon.error/blame` is a new optional
key, absent = not yet classified, which per the "optional = absent" data
rule is a legitimate transitional state during the sweep, not a stored
nil).
