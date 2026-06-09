---
type: research
status: active
tags: [research, agent]
---

# E2E Agent-Runtime Demo Findings (2026-06-08)

A bounded, live, end-to-end run of the seon agent runtime against the running
CLJS pod with the real DeepSeek adapter. Two phases: (1) an indexing agent that
reads docs and tries to make them queryable, (2) a fresh agent on the same
substrate conn that should answer questions by digging the DB. Observation only
— no substrate edits, no restarts, no commits.

## TL;DR

- **Phase 1 READ + DIGESTED very well, but STORED nothing usable.** The indexing
  agent (`rxH-2606082150`) genuinely read ~10 real docs and designed a coherent,
  deeply-nested `:seon.kb.*` fact model — but it never called
  `seon.schema/register!`, so every attempt to `transact!` real facts was
  rejected at the boundary. It then **falsely declared success** ("stored my
  learnings as structured knowledge base facts"). **Zero `:seon.kb.*` datoms
  exist in the DB.**
- **Phase 2 NEVER RAN.** The Q&A agent (`KUK-2606082153`) errored on every turn
  before any LLM call. Root cause is a substrate-wide instrumentation bug
  (below), not the agent.
- **Dominant finding — a live regression in the turn pipeline.** Partway through
  the demo, EVERY agent's turn started dying with
  `:malli.core/invalid-output` from `seon.analyzer-info/snapshot-defs`: the live
  CLJS compiler analyzer-state grew a `nil` namespace key, and the function's
  output schema is `[:map-of :symbol …]`. Instrumentation throws → the turn
  aborts → no agent can take a turn. This eventually killed `rxH` too (it only
  completed its first ~15 turns) and blocked Phase 2 entirely.

## Agents used

| Phase | Agent id | Conn | Outcome |
|-------|----------|------|---------|
| 1 (index) | `rxH-2606082150` | shared `@seon.client/!agent-conn` | Ran ~15 turns / 54 evals, then went idle. Later turns started failing on the analyzer bug. |
| 2 (Q&A) | `KUK-2606082153` | same shared conn | 2 turns dispatched, **both `:seon.turn/status :error`**, 0 evals, 0 assistant messages. |

Pre-existing idle agents on the conn: `iPj-2606082147`, `TWI-2606082147`,
`tOx-2606082135` (`tOx` also hit the analyzer bug at its turn 22).

## Phase 1 — INDEX

### What it actually did (good)

It did NOT read just one doc as instructed — it explored broadly (a mild
over-run), but every read was real content. Eval sources (clipped) show genuine
`seon.fs` reads of:

- `docs/seon/_dashboard.md`
- `docs/seon/architecture/overview.md`
- `docs/seon/components/{database,runtime,schema-system,context,agent-system}.md`
- `docs/seon/concepts/{step-functions,namespace-as-process}.md`

plus `list-dir` of `docs/seon`, `components/`, `architecture/`, `concepts/`.

It then designed a **sound, deeply-nested fact model** — exactly the namespacing
discipline the conventions want. Stored as `:seon.schema/key` schema-definition
DATA entities (15 of them, all `seon.kb.*`):

```
:seon.kb.doc/title        "[:string {:min 1}]"
:seon.kb.doc/path         "[:string {:min 1}]"
:seon.kb.doc/summary      "[:string]"
:seon.kb.doc/type         "[:enum :dashboard :component :concept :architecture :reference :spec :vision]"
:seon.kb.component/name   "[:string {:min 1}]"
:seon.kb.component/purpose "[:string]"
:seon.kb.component/namespaces "[:vector :string]"
:seon.kb.architecture/name "[:string {:min 1}]"
:seon.kb.architecture/description "[:string]"
:seon.kb.concept/name     "[:string {:min 1}]"
:seon.kb.concept/description "[:string]"
:seon.kb.fact/id          "[:string {:min 1}]"
:seon.kb.fact/content     "[:string]"
:seon.kb.fact/source      "[:string]"
:seon.kb.fact/tags        "[:vector :keyword]"
```

It defined a storage fn (note the **bare-keyword destructuring and missing
`:malli/schema`** — convention violations):

```clojure
(defn store-fact!
  "Store a learned fact about the seon project.
   Takes a map with :seon.kb.fact keys and transacts it.
   Returns the tx-report."
  [{:keys [id content source tags]}]                ; <- bare keys, no :malli/schema
  (seon.db/transact!
    {:seon.db/tx-data
     [{:seon.kb.fact/id      id
       :seon.kb.fact/content content
       :seon.kb.fact/source  source
       :seon.kb.fact/tags    tags}]}))
```

### What it failed to do (the gap)

- **It never called `seon.schema/register!`** on any `:seon.kb.*` attr. Storing a
  schema's source string as a `:seon.schema/key` entity is NOT registration —
  `transact!` validates against the *registered* schema set, and these were
  absent. (`:seon.kb.doc/title` and `:seon.kb.test` did end up registered, by
  some side path, but the rest did not.)
- Consequently **every kb data write failed** (`:ok? false`): `store-fact!`
  calls, `:seon.kb.component/*` transacts, `:seon.kb.architecture/*` transacts —
  14–15 failed evals.
- **No identity attr** was marked `:seon.db/identity true`, so nothing would have
  been upsertable even if registered.
- **No `deftest` was ever written** (0 occurrences in its eval sources), despite
  the instruction.
- It then **claimed success in its final assistant message** — a hallucinated
  completion. Final DB state: zero `:seon.kb.*` datoms.

Eval tally: 54 total, 39 ok, 15 failed.

### Rough edge: failed evals don't capture the error

`:seon.eval/result-edn` was **empty** for the failing kb transacts. The agent
got no error text back from its own failed evals, which is plausibly why it
didn't recover — it couldn't see *why* the writes failed. (The real errors were
only in the pod's stderr.) This is a strong candidate fix: persist the failure
reason into `:seon.eval/error` / `:seon.eval/result-edn` so the agent can self-
correct.

## Phase 2 — FRESH AGENT, SAME DB, Q&A

The fresh agent `KUK-2606082153` was booted on the same conn and asked a non-
trivial, DB-only question: *what `seon.kb` schemas were defined, and are there
any actual stored facts or only schema definitions?* — a question whose honest
answer ("only the model, no facts") requires inspecting the substrate.

**It never got to answer.** Both dispatched turns recorded
`:seon.turn/status :error` with **0 evals and 0 assistant messages**. The user
message was injected correctly; the turn's `:seon.turn/prompt-text` was well-
formed and its `<schema-catalog>` correctly listed the 28 `:seon.schema`
entities (so the kb model *was* visible to the new agent). But the turn aborted
before the LLM call.

So the payoff question is unanswered by the demo: we could not observe whether a
fresh agent retrieves from the DB, because the turn pipeline was broken by the
time Phase 2 started.

## BUG — substrate-wide turn-killer (highest priority)

Pod log, repeated across `tOx`, `rxH`, and `KUK`:

```
turn N ▸ run-turn! error #error {:message ":malli.core/invalid-output",
  :data {:seon.error.malli/fn-sym seon.analyzer-info/snapshot-defs,
         :seon.error.malli/schema [:=> [:cat :seon.analyzer-info/compile-state]
                                       :seon.analyzer-info/defs-snapshot],
         :seon.error.malli/humanized ["should be a symbol"],
         :seon.error.malli/got-edn "{}",
         :seon.error.malli/return-value-edn "{nil {}, cljs.compiler {...}}"}}
```

- Function: `seon.analyzer-info/snapshot-defs`
- Declared output schema (`:seon.analyzer-info/defs-snapshot`):
  `[:map-of :symbol [:map-of :symbol :int]]`
- Reality: the live CLJS compiler analyzer-state has a **`nil` namespace key**
  (`{nil {} cljs.compiler {...}}`), so the snapshot map has a `nil` key →
  instrumentation rejects it (`should be a symbol`) → the whole turn aborts.

This is an instrumentation-vs-reality mismatch — classic code smell. The schema
assumes every analyzer ns key is a symbol; the actual cljs analyzer carries a
`nil`-keyed entry. **Either the schema must tolerate the `nil` key (or
`snapshot-defs` must drop it) — do not loosen instrumentation globally.** Because
this is in the turn hot-path (`run-turn!`), it takes the entire agent runtime
down: no agent can complete a turn once the analyzer state acquires the `nil`
key. This is almost certainly why `rxH` stopped after ~15 turns and why Phase 2
never executed.

Not fixed here (observation-only run). Flagged for a focused follow-up.

## Other rough edges / follow-up candidates

1. **Failed evals are silent to the agent** — `:seon.eval/result-edn` empty on
   failure. Surfacing the error EDN back into the eval entity would let agents
   self-correct (Phase 1's core failure).
2. **Over-run tendency, confirmed again** — the indexing agent read ~10 docs and
   re-announced "I now have a comprehensive understanding…" across several
   assistant messages before doing anything. Burned ~54 evals on a task that
   needed maybe 10.
3. **Hallucinated completion** — the agent's final summary asserted facts were
   stored when none were. With (1) fixed it might catch this; worth a
   verify-before-claiming nudge in the system prompt.
4. **`register!` vs store-as-data confusion** — the agent conflated persisting a
   schema's *source string* as a `:seon.schema/key` entity with actually
   registering the schema. The `## What you can do` block lists db ops but does
   NOT mention `seon.schema/register!`; the agent had to guess and guessed wrong.
   Adding `register!` (and an identity-attr example) to the capability list would
   directly address the Phase 1 gap.
5. **`chat` Promise resolves before the turn finishes** — `(.then …)` fired
   `:done` while the agent was still `:running` (Phase 1) or had already errored
   (Phase 2). The Promise signals "turn dispatched," not "turn complete." Polling
   `:seon.agent/state` is the only reliable completion signal — worth documenting,
   or have `chat` resolve on turn completion.
6. **No `:seon.turn/error` field** — turns store `:seon.turn/status :error` but no
   message; the cause was only recoverable from pod stderr. Persisting the turn
   error onto the turn entity would make failures observable from the DB (and
   visible to a debugging agent via its own schema-catalog).

## Honest assessment

- **READ / DIGEST: strong.** DeepSeek pulled real, on-disk seon docs (a domain
  absent from its training data) and produced a genuinely well-structured,
  conventionally-namespaced fact model. The comprehension was real.
- **LEARN / PERSIST: failed.** It did not cross the last mile — register schemas,
  store facts, write a test. It mistook designing-and-describing for doing, and
  the runtime gave it no feedback (empty failure results) to notice.
- **RETRIEVE: unobserved.** The fresh agent never ran a turn because of the
  `snapshot-defs` instrumentation bug. The end-to-end "second agent answers from
  the first agent's data" loop could not be demonstrated this run.

The most valuable signal is the turn-killer bug: it is a single instrumentation
mismatch that silently takes down the entire agent runtime mid-session, and it
should be the next thing fixed before any further e2e runs.

## Re-run 2026-06-08 (post T11/T12/T13)

Re-run after tonight's three blocking fixes. Observer-only; no substrate edits,
no commits. Driven via `mcp__seon_cljs__eval` against the `:client` runtime.

Agent ids:

- Phase 1 (index): `nuX-2606082242` (DeepSeek).
- Phase 2 (Q&A): `SgL-2606082246` (DeepSeek).
- Confound: the pod auto-boots an agent on restart (`CDv-2606082241`). It
  self-drove ~79 evals on the shared `@seon.client/!agent-conn` with NO directive
  from me — registered 2 attrs, did 4 transacts, 0 defns. There is no clean
  "stop an agent loop" verb (`seon.client`/`seon.agent` expose only
  `stop-heartbeat!`). I filtered all observations by `:seon.eval/ns` to separate
  agents. **Rough edge: auto-booted agent contaminates the shared DB during an
  observed run; provide an agent-stop verb or a no-auto-boot flag for demos.**

### Phase 1 — REGISTER + STORE: SUCCESS (the key improvement)

Unlike the prior run (register! never called), `nuX` registered real
`:seon.kb.doc/*` schemas and transacted real entities. Took TWO messages: msg 1
it spent the entire turn reading docs + introspecting (32 evals, 0 register, 0
transact, 0 defn) then went idle without storing — the over-explore pattern.
Msg 2 ("STOP reading, do the four steps NOW") pushed it over the last mile.

Schemas it registered (T13 confirmed working — attrs land in `(:schema @conn)`):

```clojure
(seon.schema/register! :seon.test/sym [:string {:seon.db/identity true}])
(seon.schema/register! :seon.test/source :string)
(seon.schema/register! :seon.test/ns [:seon.db/ref])
;; and the kb.doc family (path = identity upsert key):
;; :seon.kb.doc/path :seon.kb.doc/title :seon.kb.doc/type
;; :seon.kb.doc/tags :seon.kb.doc/summary :seon.kb.doc/content
```

`:seon.kb.doc/*` attrs were ABSENT at baseline (0) and PRESENT after the run (6).
T13 register! + auto-derive-datahike-schema works end to end.

Real data it transacted (3 `:seon.kb.doc` entities, queried back from DB):

```clojure
{:seon.kb.doc/title "Seon Dashboard"
 :seon.kb.doc/path "docs/seon/_dashboard.md"
 :seon.kb.doc/type :concept
 :seon.kb.doc/tags [:concept :dashboard :index]
 :seon.kb.doc/summary "The Seon Dashboard is the main entry point ..."}
{:seon.kb.doc/title "Agent Concepts" :seon.kb.doc/path "docs/seon/concepts/agents.md"
 :seon.kb.doc/type :concept :seon.kb.doc/tags [:agent :concept :lifecycle] ...}
{:seon.kb.doc/title "Agent System Component"
 :seon.kb.doc/path "docs/seon/components/agent-system.md"
 :seon.kb.doc/type :component :seon.kb.doc/tags [:agent :component :provider :system]
 :seon.kb.doc/summary "Provider-agnostic AI agent lifecycle — launch, observe, persist, interrupt ..."}
```

Deeply-typed, fully-namespaced, natural-key identity (`path`), keyword enum
(`type`), vector-of-keyword tags. This is exactly the target shape.

### fn-writing / T14: NOT EXERCISED (cannot confirm fixed)

Neither agent ever evaluated a real `(defn ...)`. `nuX`: 0 defns. `CDv`: 0 defns.
Both stored functions/tests as STRINGS inside `:seon.test/source` (the corpus
"test as data" path) rather than def-ing them. So:

- The T14 phantom-ns symptom ("Nothing found for entity id [:seon.ns/name :]" /
  lost eval after a defn) did NOT appear — searched all failed evals across both
  agents for that signature: **0 hits**. But this is because no defn ran, not
  because the path was exercised and passed. **T14 remains unverified by this run.**
- The one place `nuX` tried to materialize a function-as-data, the transact FAILED
  with a legible parse error (it embedded an unbalanced `(deftest ...)` string):
  `"Unmatched delimiter: ) [at line 5, column 68]"`.

### T12 error-surfacing: CONFIRMED WORKING

Failed evals carry specific, actionable messages the agent can read. Real samples:

- `Nothing found for entity id [:seon.ns/name :seon.agent.nuX-2606082242] ... :error :entity-id/missing`
- `Query for unknown vars: [?error] ... :error :parser/query`
- `ENOENT: no such file or directory, open '.../architecture/README.md'`
- `undeclared-var: seon.agent.nuX-2606082242/?at ... :seon.error/kind :compile`
- Full malli instrument-input explain on a bad `:seon.db/args` shape (humanized +
  path + got-type/expected), e.g. passing `{:limit 5}` where `[:vector :any]` is
  expected.

Errors are no longer silent/empty — a clear improvement over the prior run.

### T11 turn-killer: CONFIRMED WORKING

Turns terminate cleanly (`:seon.turn/status :done`, agent returns to `:idle`)
across many turns; no mid-session runtime death. Both agents idled normally after
each turn. The prior run's "turn-killer takes down the runtime" did not recur.

### Phase 2 — ANSWER FROM DB: FAILED

Fresh agent `SgL-2606082246`, same conn. Across TWO messages (≤4 budget) it never
produced a DB-grounded answer:

- Msg 1: it got distracted investigating a "1 failed eval" warning, explored the
  schema catalog + eval logs (12 evals), **0 queries against `:seon.kb.doc/*`**,
  went idle without answering.
- Msg 2 (handed the EXACT attrs and three queries to run): it STILL did not run a
  correct query. The one data query it attempted dropped the `seon.` prefix:

  ```clojure
  ;; SgL's query — WRONG namespace (:kb.doc/path instead of :seon.kb.doc/path)
  (seon.db/query {:seon.db/query '[:find ?path ?title
                                   :where [?e :kb.doc/path ?path]
                                          [?e :kb.doc/title ?title]]})
  ;; => #{}   (empty; never noticed the typo, never recovered)
  ```

  Count of correct `:seon.kb.doc/`-namespaced queries by SgL: **0**. It produced no
  prose answer to the three questions; final turn ended still re-reading the user
  message and the schema catalog.

So the cross-agent "second agent answers from the first agent's stored data" loop
was demonstrated to be POSSIBLE (the data is there, well-typed, queryable — I
verified `[:find ?title ?path :where [?e :seon.kb.doc/title ?title]...]` returns
all 3 docs from the observer session) but the agent itself did NOT close it: it
over-explored and mis-typed the attribute namespace.

### Remaining rough edges (this run)

1. **Over-exploration / under-delivery is the dominant failure mode now.** With
   T11/T12/T13 fixed, the runtime is healthy but the AGENT burns a whole turn
   reading/introspecting and stops before doing the requested storage/answer. Both
   phases needed a second, blunt "STOP exploring, DO it now" message. A single
   intent is not enough; the agent treats "learn" as "read forever."
2. **Namespace-prefix drift in queries.** `SgL` queried `:kb.doc/*` instead of
   `:seon.kb.doc/*` even after being given the exact keys, got `#{}`, and never
   suspected the typo. An empty result should prompt a "did I name the attr right?"
   check — the agent has no reflex for that.
3. **`?at`/`?time`-as-datalog-var collides with ns-qualified symbol resolution.**
   Recurring across all three agents: using `?at` in a `:find`/`:where` yields
   `undeclared-var: seon.agent.<id>/?at`. The agents work around it inconsistently.
   Worth a note in the capability docs or a query preprocessor.
4. **Agents conflate "store a test as data" (`:seon.test/*` corpus) with the
   requested task.** `nuX` registered `:seon.test/*` AND `:seon.kb.doc/*`; the
   `:seon.test/*` transact (a `deftest` string) failed on unbalanced parens. The
   "test as data" corpus path competes for attention with plain knowledge storage.
5. **Auto-booted agent on pod restart** (see Agent ids) — contaminates a shared-conn
   observation run; no stop verb.
6. **Turn counter in logs is per-step, not per-message** (`turn 8 ▸ done`,
   `turn 9 ▸ done` within one chat) — fine, but don't read it as message count.

### Honest assessment (re-run)

- **The three fixes hold.** T11 (turns terminate, runtime survives) and T12
  (legible errors) are clearly working. T13 (register! → schema → datoms) is
  working: an agent registered nested namespaced schemas and stored real,
  well-typed knowledge entities — the single biggest improvement over the prior
  run, where register! was never called.
- **T14 is unverified** — no agent wrote a real `(defn ...)` this run, so the
  phantom-ns path was not exercised. To confirm T14, a future run must force a
  literal `(defn ...)` eval and check the eval record persists.
- **The bottleneck moved from the runtime to the agent's task-execution
  discipline.** Phase 1 succeeded only after a corrective second message; Phase 2
  failed outright (agent never queried the right attrs). The substrate now does
  its job; the agent over-explores and mis-types. Prompt/guardrail work (or a
  reflexive "empty result ⇒ re-check attr names" hint) is the next lever, not more
  runtime fixes.
