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

## Run 3 — guidance validation 2026-06-09 (persistent store)

Goal: validate the guidance committed 2026-06-09 (bias-to-act, register! primer,
empty-result-=-typo reflex, datalog-vars-in-quoted-vector, legible errors) against
a freshly-booted pod on the new on-disk store. Orchestrator/observer drove DeepSeek
via the pod; no substrate edits, no commit, no restart.

- **Store path:** `data/seon-pod/2026-06-09T15-02-00-351Z` (file backend, conn id
  `dedd556c-cc5f-2e74-779c-024bee378764`). Reviewable after the run.
- **Phase 1 agent:** `NUQ-2606091108` (40 evals, all on the single task message).
- **Phase 2 agent:** `ccK-2606091110` (8 evals). Also present from failed boots:
  `QIg-2606091110` (entity created, boot promise rejected). Pre-existing: `kgQ-2606091102`.

### Step 0 — guidance is LIVE (confirmed)

All five live in the seeded prompt/capabilities:
- Bias-to-act: system prompt carries "A turn that re-reads what is already in front
  of you is a turn that didn't move the task; one well-aimed read plus the real write
  beats ten more reads."
- register! primer: capabilities `## What you can do` has a "Storing a NEW KIND of
  data: register the schema FIRST" block with the `:kb.doc/*` worked example.
- Empty-result reflex: "When a query comes back EMPTY (#{}), suspect a misspelled
  attribute before you conclude there's no data ... if the catalog lists
  :seon.kb.doc/path, query that — not :kb.doc/path."
- Datalog-vars-in-quoted-vector: system prompt "datalog logic variables ... only stay
  symbols when they live INSIDE the quoted query vector."

### VALIDATION FOCUS — did the guidance move the needle? (honest)

- **Bias to act: NO — regressed vs Run 2.** Phase 1 (`NUQ`) ran 40 evals and NEVER
  executed the task. It never called `seon.fs/list-dir`/`read-file` (0 fs calls),
  never registered a kb schema, never wrote a fn or test, never transacted kb data.
  It spun in a bootstrap-introspection + status-message loop: querying its own
  messages/evals, pulling its own ns, re-reading the system prompt and conventions,
  and posting "Agent NUQ ... bootstrapped and ready. Awaiting instructions." It even
  emitted, verbatim, ";; I'm stuck in a loop of posting status messages" (eval 221) —
  recognized the loop and STILL did not pivot to the task. The bias-to-act paragraph
  did not overcome the pull toward self-orientation. Worse than Run 2, where Phase 1
  at least completed the task after one corrective message.
- **register!: NOT EXERCISED.** Because neither agent reached the storage step, the
  register! primer was never put to the test this run. (Run 2 verified register!
  works; this run neither confirms nor refutes.)
- **Full-namespaced query attrs / empty-result recovery: PARTIAL (Phase 2).** `ccK`
  did the *precursor* correctly: it queried the schema-catalog (`:seon.schema/key`)
  to look for the kb attribute before assuming data shape — exactly what the reflex
  asks for. But it found no kb attrs (correct — Phase 1 stored none), then did NOT
  run a kb-doc query, did NOT explicitly report "no kb data was stored," and instead
  transacted its own ns source and went idle. So the catalog-first instinct landed,
  but the agent did not close the loop with a clear empty-result answer to the user.
- **Legible errors → self-correct: YES (clear win, consistent with Run 2/T12).**
  - `NUQ` passed `:seon.db/args {:limit 10}` (a map); got
    `:malli.core/invalid-input ... :schema [:vector :any], :value {:limit 10}` and on
    the next turn re-ran with a vector arg (eval 174/175 succeeded). Legible error,
    correct self-fix.
  - `[:seon.ns/name :seon.agent.NUQ...]` lookup → "Nothing found for entity id
    [:seon.ns/name :seon.agent.NUQ-2606091108] :error :entity-id/missing" — clear.
  - reader fragments (prose read as forms): `undeclared-var: .../new`,
    "Instance literal expects a string for its timestamp" (a bad `#inst`) — all legible.
- **Phase 1 stored data + fn + test? NO.** Final DB has zero `kb`/`doc`/`fact`/`learn`
  schema attrs; zero fns defined in `NUQ`'s ns; zero kb entities.
- **Phase 2 answered from the DB? NO.** Never queried kb attrs; gave no
  empty-result answer.

### NEW substrate rough edge (blocker) — second agent boot fails on shared persistent conn

After Phase 1, EVERY subsequent `start-agent-with-deepseek!` boot promise REJECTS with:

    Malli validation failed for :seon.fn/ns child: expected map or :seon.db/ref,
    got :seon.ns/name
    {:seon.db/error :seon.db/invalid-ref-child, :seon.db/attr :seon.fn/ns,
     :seon.db/actual-value :seon.ns/name}

Deterministic (reproduced twice). The agent ENTITY is still created (hence `QIg`,
`ccK` exist with 0 evals), but the boot promise rejects during substrate (re)indexing.
Diagnosis (no fix applied — substrate is out of scope): `:seon.fn/ns` is registered as
`[:vector :seon.db/ref]` and all PERSISTED values are valid int-ref vectors
(`#{[108 105] [113 106] ...}`) — so the bad keyword `:seon.ns/name` is NOT in the
store; it is produced at boot-time indexing, where something assigns the bare keyword
`:seon.ns/name` to `:seon.fn/ns` instead of a ref. Because the entity is created before
the reject, `chat` still works against the half-booted agent (that is how Phase 2 ran
against `ccK`). The first boot of the session succeeds; the failure is state-dependent
on the shared persistent conn already holding indexed fn/ns entities. This blocks the
intended "fresh agent, same conn" Phase 2 protocol and should be fixed before the next
e2e run.

### Honest assessment (Run 3)

- **The guidance did NOT move the needle on bias-to-act for this run — it regressed.**
  Both agents spent their turns on self-orientation; Phase 1 never touched the task
  despite a single, explicit, example-laden instruction and an in-prompt paragraph
  telling it not to re-read. This is the opposite of the intended effect. One run is
  not conclusive (DeepSeek variance is high), but it is a clear miss, not a win.
- **Legible-errors keeps paying off** — the one capability that visibly works turn
  over turn. Agents read the malli/reader messages and fix the next form.
- **The empty-result reflex half-fired:** catalog-first lookup happened (good), but
  the agent didn't produce the user-facing "no data" conclusion the reflex implies.
- **The bottleneck is unchanged from Run 2's diagnosis and now sharper:** the runtime
  is fine; the agent's task-execution discipline is the lever. The new prompt text did
  not produce act-sooner behavior here. Candidate next levers: (a) make the FIRST
  turn's context foreground the pending user task above the bootstrap/introspection
  affordances; (b) cap or de-emphasize self-status messaging; (c) a turn-0 nudge that
  the namespace is ALREADY bootstrapped so the agent stops "getting its bearings."
- **NEW: fix the `:seon.fn/ns` boot-indexing ref bug** before the next run — it
  silently breaks the "second agent on the same conn" pattern.

## Run 3 — 2026-06-09 ~18:33Z, scenario 1 of the two-scenario test (agent rEp-2606091203)

Question sent via POST /chat: "Track my workouts for me. Today I ran 5k in 24:30.
Yesterday a 60-minute strength session. Store these…" — a work-directed
schema-design + store task.

**Outcome: FAILED the core objective.** Zero workout schemas registered, zero
entities stored. The agent never saw the question.

### Root cause (verified in the live DB)

**User messages never reach the agent's context.** `seon.agent/messages` (feeding
`transcript-section`) walks `:seon.session/turns → :seon.turn/messages` — but the
`/chat` handler transacts the user `:seon.message` STANDALONE (agent ref only,
never attached to any turn). Verified: 0 of 2 user messages are in any turn's
`:seon.turn/messages`; turn 147's persisted `:seon.turn/prompt-text` (9486 chars)
does NOT contain "Track my workouts". 6 of 20 assistant messages are orphaned the
same way.

Downstream behavior (all 43s–3min after the /chat):

1. Agent (correctly, per its prompt) looked for "the most recent user> line in the
   transcript" — found none.
2. Fell back to QUERYING for the latest user message; failed TWICE with the same
   compile error (`undeclared var ?at` — `:seon.db/order-by [[?at :desc]]` outside
   the quoted query) and once with "Cannot parse clause".
3. Concluded no message existed, transacted "waiting for next task"-type replies,
   one EMPTY assistant message (content ""), went idle.

### Defect list (ordered, MVP-critical first)

1. **transcript-misses-user-messages** — fix `seon.agent/messages` to query by
   `:seon.message/agent` directly (derived-by-default; turn-walking is an
   unnecessary indirection for the transcript), or attach incoming user msgs to
   the opening turn. THE blocker; re-run scenario 1 after.
2. **`?at`-in-order-by compile error is repeat-trap** — agent hit the identical
   error twice; the error text didn't teach the fix (A3/A4). The order-by gotcha
   deserves a targeted error translation (it's the documented loose-`?var` trap).
3. **`:seon.eval` rows carry NO agent ref** — only `:seon.eval/ns` (a keyword like
   `:seon.agent.rEp-…`). Anything querying evals by agent silently returns ∅.
4. **Empty assistant message** (content "") transacted + a message whose content
   was raw code text — message-content hygiene at transact boundary.
5. **Turn-count stayed 0** on the agent entity through 5 turns (sessions/turns
   exist — entity counter not updated; inspector header reads it).
6. (Fixed during run) **unscoped user-trigger** woke every agent per user message
   (commit a0bdde9); **hot-reload didn't re-arm triggers** (1.2-infra agent landed
   auto re-arm in client.cljs).

### Status

Scenario 2 NOT run (premise requires scenario 1's stored schemas). Re-run both
after defect 1 lands.

## Run 4 — 2026-06-09 ~19:00Z, two-scenario test COMPLETE (post transcript-fix)

### Scenario 1 (fresh agent rLC-2606091459): PASS

With the `messages` direct-query fix live, rLC saw the question in 23s,
registered work-directed schemas (`:workout/date :workout/type
:workout/distance-meters :workout/duration-seconds`), hit a schema-type error
(`:number` invalid), READ THE ERROR AS A VALUE and recovered (switched to
seconds), stored both workouts CORRECTLY (5000m/1470s run; 3600s strength),
and replied "All set…" — ~2.5 min, 3 turns. The whole loop the demo needs.

### Scenario 2 (fresh agent ham-2606091509): FAIL on reuse

Question: add a 1500m/35min swim + total training time across all workouts.

- The existing `:workout/*` schemas WERE in ham's first-turn context
  (prompt-text 16,405 chars, contains "workout") — the catalog plumbing works.
- ham did NOT consult it: registered a PARALLEL `:workout/duration-minutes`
  (existing data uses `duration-seconds`), stored the swim as
  `{:workout/distance-meters 1500 :workout/duration-minutes 35}` — no
  `:workout/type`, no date → data model forked, aggregate now unanswerable
  by one query.
- Total reported: "35 minutes" — it summed ONLY its own attr
  (`(sum ?d)` over `duration-minutes`), ignoring the 2 existing workouts
  (real total ≈ 2h). Confidently wrong reply, sent BEFORE the verify step.
- Turn 2: `Maximum call stack size exceeded` (`:cljs/analysis-error`) on a
  large `let` form — cljs.js analyzer limit; agent recovered by stopping.

### Defects → next quality iteration (Track 1, 1.2 family)

1. **Reuse contract + catalog salience** — the system prompt says "check
   <functions> before writing a helper" but has NO equivalent for SCHEMAS.
   Add: "BEFORE seon.schema/register!, check the schema-catalog for an
   existing shape — extend it, never fork a parallel attr." Consider catalog
   render: instance counts + units make `duration-seconds (2 entities)`
   harder to miss.
2. **parallel-attr warning check** (A2 registry candidate): flag a register!
   whose name-stem collides with an existing attr in the same ns
   (`duration-minutes` vs `duration-seconds`) — reactive net for defect 1.
3. **Answer-after-verify nudge**: ham replied the total before querying
   existing data; prompt should bind "totals/aggregates ⇒ query the data you
   just cataloged" — possibly a worked example in capabilities.
4. **analyzer stack-overflow** on big let forms — guidance (smaller forms) or
   eval-side mitigation; recovered gracefully this time.

### Scoreboard

- transcript fix: PROVEN (S1 turnaround 23s vs run-3's never-saw-it)
- error-as-value recovery: PROVEN (rLC schema-type error)
- fresh-agent-same-conn: PROVEN (3rd + 4th boots clean)
- work-directed schema design: PROVEN (rLC)
- cross-agent REUSE: NOT YET — salience, not plumbing (ham)
