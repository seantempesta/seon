---
type: research
status: active
tags: [research, agent]
---

# Live-drive validation — P0 + my.todo on the acme cluster (2026-06-28)

A live-drive of the recently-landed Core work on the ISOLATED acme cluster
(HTTP 7980 / wire-REPL 7981), driving a real DeepSeek agent on a planning +
DB-memory task. The goal: prove these ideas are load-bearing, not fanciful.
**Validate-and-report — no fixes applied.** All evidence is observed in the
running acme pod (`logs/acme/pod.log`) and read back from the acme datahike
store via the wire-server REPL.

## TL;DR

- **P0 (agent-create wedge) — PASS, load-bearing.** Two back-to-back
  `POST /agents/new` both logged `instrumentation {:already-done? true}`;
  **zero** `:malli.core/invalid-output`, zero Promise/wedge errors across
  the whole session (9 turns + 2 creates); heartbeats still ticking.
- **my.todo — PASS, robust core.** A live DeepSeek agent DISCOVERED
  `plan!`/`next`/`done!`/`tree`/`list-open`/`complete` from context and used
  them with the exact docstring shapes. It authored a correct dependency
  DAG, the `next` queue correctly withheld blocked work, it worked every
  ready leaf, and halted cleanly via `complete`. The hierarchy + dependency
  behavior is **correct end-to-end**.
- **The first turn flailed, then SELF-RECOVERED.** The agent's first `plan!`
  failed (it prefixed prose into the code block), it then HALLUCINATED todo
  ids and called `done!` on non-existent items — but the `done!` fail
  envelopes ("…(seon.agent.todo/list-open {}) shows the open ids") steered it
  to `list-open`/`tree`, after which it issued a clean `plan!` and executed
  flawlessly. **Errors-as-values is the load-bearing recovery mechanism.**
- **Two smells for Core triage** (below): (1) `record-eval!` DATA LOSS on a
  nil `:seon.eval/source` (3 eval rows silently dropped in turn 1);
  (2) the eval reader reads *every* top-level form in the agent's block, so
  bare-paren prose / a stray `}]` becomes a failed eval — frequent friction,
  though the error feedback is good.

## Setup (what was validated against)

- Branch `feature/agent-fsm`. Rebuilt acme: `bin/acme build` (acme-client
  cache recompiled `seon.agent.todo` at 23:58, after the 22:56 Phase-6a
  commit; compiled JS contains `plan_BANG_`), then
  `SEON_AI_PROVIDER=deepseek bin/acme restart pod`.
- Clean boot confirmed: HTTP 200 on `/agents`; roster resumed 3 prior
  agents; `instrumentation: {:registered 332, :skipped 19, :bad-spec 0,
  :n-instrumented 332}`; `replay {…n-ok 9 …n-fail 0}`. No `invalid-output`.
  (The `acme.widget/broken-tile` SCI WARN is the deliberate override-demo
  tile — expected per the harness doc. The `:invalid-id-type` UUID wart was
  pre-existing and ignored per task.)
- Adapter: DeepSeek (`[seon.client] using deepseek LLM (API key set)`).

## P0 verdict — PASS

Agent-create no longer wedges the pod. Boot ran the single instrumentation
pass (`:registered 332`); both post-boot creates short-circuited via the
once-gate:

```
04:10:42  POST /agents/new — creating agent {}
04:10:48  [start-agent!] instrumentation: {:enabled? true, :already-done? true, :n-instrumented 332}
04:10:52  POST /agents/new OK {:agent "ABi-2606280010"}
04:11:13  [start-agent!] instrumentation: {:enabled? true, :already-done? true, :n-instrumented 332}
04:11:16  POST /agents/new OK {:agent "mvE-2606280011"}
```

Session-wide health sweep of `logs/acme/pod.log` AFTER the run:
`invalid-output` count = **0**; `unhandled|wedge|UnhandledPromise` = **0**;
heartbeats continuing (last 04:17:29, long after the agent halted at
04:13:26). The `defonce !instrumented?` once-gate
(`seon.instrument.cljc:376` / `instrument-from-db-once!` 379) is doing
exactly its job: the 2nd pass that pre-P0 re-wrapped already-wrapped vars
(mis-detecting `^:async` as sync → `invalid-output` wedge) never runs.

## my.todo verdict — PASS (hierarchy + dependency behavior correct)

**Discovery.** The agent (`ABi-2606280010`, DeepSeek) had never seen these
verbs except through context. Its successful `plan!` (eval #25) is a
near-verbatim adaptation of the `seon.agent.todo` docstring exemplar
(`:children` nesting, `:ref` labels, `:after` edges) — conclusive evidence
the self-teaching surface rendered into its prompt and was sufficient:

```clojure
(seon.agent.todo/plan!
  {:seon.agent.todo/title "Build a small project dashboard"
   :seon.agent.todo/children
   [{:seon.agent.todo/title "Research what data to show" :seon.agent.todo/ref "research"}
    {:seon.agent.todo/title "Design the tile layout" :seon.agent.todo/ref "design"
     :seon.agent.todo/after ["research"]}
    {:seon.agent.todo/title "Build the data query" :seon.agent.todo/ref "data-query"}
    {:seon.agent.todo/title "Wire the tile" :seon.agent.todo/ref "wire"
     :seon.agent.todo/after ["design" "data-query"]}]})
```

**The stored result is a correct DAG** (read back from the acme store via
the wire REPL — a diamond, not a chain):

```
Build a small project dashboard            (root, :done)
├─ Research what data to show              (:done, no deps)        ← ready first
├─ Design the tile layout                  (:done, depends-on Research)
├─ Build the data query                    (:done, no deps)        ← ready first (parallel)
└─ Wire the tile                           (:done, depends-on Design + Build-data-query)
```

**The `next` queue withheld blocked work — proven turn by turn** (raw evals,
verbatim, all real DeepSeek output against the live store):

```clojure
;; after done! Research + done! Build-data-query:
(seon.agent.todo/next {})
;; => [{:id "ZBU-2606280012" :title "Design the tile layout" …}]
;;    Wire is NOT offered — still blocked on Design.

;; after done! Design:
(seon.agent.todo/next {})
;; => [{:id "uAU-2606280012" :title "Wire the tile" …}]
;;    Wire now ready (both deps satisfied).

;; after done! Wire:
(seon.agent.todo/next {})
;; => []      ; all done — empty queue is the done-signal
```

Then the agent sent an accurate summary to its human and halted:

```clojure
(complete "done — dashboard project executed with dependency ordering, all closed")
;; => :idle      ; FSM run closed cleanly (log: "halt verb — complete")
```

So `plan!` / `next` / `done!` / `tree` / `list-open` / `complete` all work
end-to-end on a real agent, the parent/child tree and the `depends-on` DAG
behave correctly, and blocked leaves are never surfaced. **Robust core.**

### The turn-1 flail → self-recovery (the real agent experience)

"Server-side mechanics passing ≠ the context helps the agent." Here is what
the agent ACTUALLY experienced before it succeeded. The full eval arc
(read back from `:seon.eval` rows for the agent, time-ordered):

1. Greeted, `(wait …)`, then its FIRST `plan!` attempt FAILED — it wrote a
   sentence of prose *inside the code block, immediately before the form*:
   ```
   `:ref` labels and `:after` to express the DAG: research → design, and both
   design + data-query must finish before wire-tile.(seon.agent.todo/plan! {…})
   ```
   The reader choked on the prose → the plan! never ran → **no todos created.**
2. Bare-paren annotations (`(no deps)`, `(depends on research)`,
   `(depends on design + data query)`) leaked as their own failed evals.
3. **The agent then HALLUCINATED the child ids** (`1uC-…`, `3vE-…`, `2yD-…`,
   `4wF-…` — plausible auto-id shapes that were never minted) and called
   `done!` on them. Confirmed via bitemporal history: those ids have **no
   eid in `(d/history db)`** — they never existed. Each `done!` returned a
   fail envelope (the *eval* didn't throw, so `:seon.eval/ok? true`, but the
   *operation* failed):
   ```clojure
   (seon.agent.todo/done! {:seon.agent.todo/id "1uC-2606280011"})
   ;; => {:seon.agent.todo/ok? false
   ;;     :seon.agent.todo/error "done!: no todo \"1uC-2606280011\" —
   ;;        (seon.agent.todo/list-open {}) shows the open ids."}
   ```
4. **Recovery, unaided.** Guided by that error text, the agent called
   `(seon.agent.todo/list-open {})` then `(seon.agent.todo/tree {})`, saw the
   store was empty, and issued the clean `plan!` above — which succeeded.
   From there it executed the DAG perfectly (the verbatim section above).

Takeaways: the **errors-as-values envelopes with embedded next-step guidance
are load-bearing** — they turned a hallucination spiral into a clean recovery
with no human intervention. But the turn-1 friction is real and is a
context-steering opportunity (see smells #2/#3).

## Context verdict

- **Self-teaching surface present and sufficient.** The `:seon.ns` row for
  `seon.agent.todo` is in the acme corpus (eid 147, 16 835-char
  `:seon.ns/source`, includes the `:seon.agent.todo/after` exemplar). Core
  nses render via `:seon.ns/source` (there are no per-fn `:seon.fn` rows for
  core verbs — expected; the 332 fns live in the program graph for
  instrumentation). The agent reproduced the exact API shapes, so the source
  reached and helped it.
- **Context budget healthy.** Turn ctx grew 72 681 → 88 094 chars
  (~18k → ~22k tokens) over 9 turns as the transcript accumulated — no
  runaway.
- **Steering gap.** Nothing in the rendered context stopped the agent from
  (a) embedding prose in a code block, or (b) inventing ids instead of using
  the ids `plan!` *returns* (`{:ok? true :root … :ids {label→id}}`). The
  docstring shows the return shape but the agent ignored it on the first
  pass. A short "use the ids plan! returns; don't guess" nudge — or surfacing
  the just-created ids in the next context render — would likely have
  prevented the whole turn-1 spiral. Not a blocker; an ergonomics win.

## Bugs / smells for Core triage

### 1. `record-eval!` DATA LOSS on nil `:seon.eval/source` (Core — transcript integrity)

- **Where:** `src/seon/eval.cljs` — `record-eval!` destructures `source`
  (line ~2110) and builds `:seon.eval/source source`; the attr is registered
  `:string`. A nil `source` fails Malli, so BOTH the eval+tee tx and the
  bare-eval retry fail → the row is dropped. Emit site: `eval.cljs:2251`
  (`"DATA LOSS — bare eval row … failed with no tee rows to drop"`).
- **Observed (repro):** turn 1 of this drive, `logs/acme/pod.log` lines
  72–77 — 3×:
  ```
  [seon.eval/record-eval!] tx FAILED: Malli validation failed for
    :seon.eval/source: expected :string, got nil — source: null
  [seon.eval/record-eval!] DATA LOSS — bare eval row Smc-2606280012 failed
    with no tee rows to drop — source: null
  ```
  (eval ids `Smc-`/`fXE-`/`Ypb-2606280012` — absent from the persisted eval
  log; truly lost, not even stamped `:seon.eval/record-error`).
- **Why it matters:** the transcript is the agent's memory; these were 3
  attempts in the messy turn 1 that vanished silently — the exact moments you
  most want recorded. The root cause is UPSTREAM: a caller (the form-reader /
  `eval-batch!` path) passes `source = nil` for some malformed/empty input
  instead of `""` or the raw text. Fix the caller to always supply a string
  (and/or have `record-eval!` coerce nil→"" as a last-ditch so a row is never
  lost). Not fixed here per task scope.

### 2. Eval reader evaluates EVERY top-level form in the agent's block (Core/UX — high frequency)

- **Where:** the agent eval path reads & evals all top-level forms in the
  submitted source string.
- **Observed (repro):** many failed evals this drive were bare-paren prose or
  a stray closing delimiter that the model emitted alongside real code, e.g.
  source `}]\n\nDesign is the only ready item — wire is still blocked…`
  (→ "Unmatched delimiter"), and `(no deps)` / `(the root "…")`
  (→ "`no`/`the` is not defined"). At least 8 of 45 evals were this class.
- **Why it matters:** the model naturally interleaves a word of rationale or
  a trailing fence with its code; each becomes a failed eval. The error
  messages are excellent and the agent recovered every time, so this is
  friction, not breakage — but it inflates turn count and eval noise. Worth a
  Core decision: tolerate trailing prose, or strengthen the context rule
  ("one form, no prose in the code block; rationale goes in `;` comments").

### 3. Agent operated on hallucinated ids without checking fail envelopes (context-steering)

- Covered in the flail narrative. Not a code bug — a steering opportunity.
  The recovery worked because the `done!`/`next` envelopes are
  self-explaining. Surfacing `plan!`'s returned `:ids` into the next render,
  or a one-line "act on the ids verbs return, never invent ids," would likely
  remove the spiral.

## Load-bearing verdict — YES (robust core; turn-1 ergonomics are the rough edge)

- **P0:** fully load-bearing. The once-gate makes agent-creation safe; proven
  with zero wedge signatures over a full multi-turn run plus two creates.
- **my.todo:** the core mechanism is robust and real — a model that had never
  seen the API discovered it from context, authored a correct dependency DAG,
  and the derived `next` queue enforced the dependency semantics turn by turn
  (blocked work withheld, done work gone, empty queue = done-signal). This is
  not fanciful; it did real planning work and closed itself out.
- **The honest caveat:** the agent's FIRST attempt flailed (prose-in-code,
  hallucinated ids). It recovered *only because* errors are values with
  guidance — which is itself a strong validation of the architecture, but it
  also means the system currently relies on recovery rather than prevention.
  Two cheap context/steering nudges (smells #2/#3) and one transcript-integrity
  fix (smell #1) would turn a working-but-noisy turn 1 into a clean one.

## Provenance / how to re-read

- Acme pod `ABi-2606280010` (driven), `mvE-2606280011` (P0 2nd create).
- Wire REPL (acme store): `nc 127.0.0.1 7981`; conn via
  `(:conn (deref (deref (var seon.server.wire/state))))`; evals via
  `[?e :seon.eval/agent ?ag] [?ag :seon.agent/id "ABi-2606280010"]`, sort by
  `:seon.eval/at`.
- Log: `logs/acme/pod.log` (turns 04:11–04:13; DATA LOSS lines 72–77).
