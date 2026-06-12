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

## Run 4 CORRECTION (2026-06-09 ~20:15Z, found by 1.2-reuse agent)

**The run-4 root cause above is WRONG.** "Catalog WAS in context (prompt-text
contains 'workout')" matched the word "workout" in the USER'S QUESTION text —
not the catalog. Reading ham's stored `:seon.turn/prompt-text` properly: the
`:workout/*` schemas were NEVER in ham's context. S2's failure was (mostly)
plumbing, not salience.

**Actual root cause — silent data loss in `record-eval!` (eval.cljs):**
`build-tee-entities` gives `:seon.schema` tee rows
`:seon.schema/ns [:seon.ns/name <keyword-ns>]`. For DATA namespaces
(`:workout`) no `:seon.ns` entity exists → datahike throws "Nothing found for
entity id [:seon.ns/name :workout]" → the ENTIRE record-eval! tx fails with
only a console.warn — losing BOTH the `:seon.schema` row AND the `:seon.eval`
row. Reproduced live on a scratch conn. This is why the run-4 db has ZERO
:seon.schema entities for :workout/* despite 8 successful-looking register
evals, and why first-registration evals are missing from transcripts.

Fix unit (eval.cljs): (a) upsert the `:seon.ns` entity in the same tx (or make
`:seon.schema/ns` optional for data namespaces), AND (b) record-eval! must
never silently lose rows — on tx failure, fail loud + retry without the tee
rows so the EVAL row always survives (surface-errors-loudly).

The 1.2-reuse salience fixes remain valid (immune — catalog now derives from
`(:schema db)` directly) and `check-parallel-attr` fires on the real run-4
duplicate. But S2 re-run is BLOCKED on the tee fix.

## Run 5 — 2026-06-09 ~20:16Z, S1 re-run (agent Mmp-2606091543): FAIL — the A4 envelope bug, live

Mmp behaved BETTER (registered :user.workout/* with duration-SECONDS — the
reuse-contract prompt is visibly shaping schema design) but ZERO data landed
while every transact eval showed :ok true and the agent told the user
"Your workouts are stored." Three claimed-success replies, no data.

Root cause chain (pod.log:32619+):
1. `:user.workout/distance-km` registered as `:double` — register! accepts it
   but the datahike bridge does NOT install it → transact rejects with
   "Bad entity attribute … not defined in current schema".
2. The rejection is ASYNC — past the eval's result capture. Eval records
   :ok true; the error only lands in the unhandledRejection safety net.
   The agent CANNOT see the failure → it reports success. This is exactly
   Track A §A4: transact! must RESOLVE to the
   `{:seon.db/ok? false :seon.db/error …}` envelope, never reject past the
   sync try. Run 5 is the live proof of why A4 is MVP-critical: the agent
   isn't lying by character — it is blind by construction.
3. Secondary: register!-accepted-but-bridge-uninstallable types (`:double`)
   must either install or be rejected AT register! time with the valid-type
   list (rLC got that list for `:number` — `:double` slipped through).

Positive signals: duration-seconds chosen unprompted (run-4 prompt fix
working); agent self-diagnosed "save failed due to a schema registration
race" and retried — right instinct, wrong information.

## Run 6 — 2026-06-09 ~20:56Z, S1+S2 with tee+envelope+salience all live: BOTH PASS

- **S1 (DbV-2606091656): PASS in ONE turn (~23s).** Stored both workouts
  correctly AND REUSED the pre-existing `:user.workout/*` attrs (incl.
  established `duration-seconds`) — no fork, no drama. (Mmp's run-5 rows had
  also landed post-fix, so the store now held duplicates of the two workouts.)
- **S2 (dwr-2606091657): PASS on the thesis.** Fresh agent REUSED the schemas
  exactly (complete `:swim` entity: type/date/id/duration-seconds 2100/
  distance-str), queried stored data, computed the total IN the query,
  replied — ~1 min. Cross-agent reuse: PROVEN.
- **New finding — the Datalog `:with` gotcha:** dwr's total (119 min = 7170s)
  deduped VALUES because `(sum ?d)` lacked `:with ?e` (true row total
  12,240s given the duplicate rows). The ORCHESTRATOR'S verification query
  made the SAME mistake. Teach it: the capabilities `(sum …)` worked example
  must include `:with ?e`; candidate warn check later.
- Still open (minor, recurring): trailing EMPTY assistant message ("",
  20:59:09) — message-content hygiene at the transact boundary (run-3
  defect #4).

### MVP loop status

Work-directed design → store → cross-agent reuse → in-query aggregate ALL
DEMONSTRATED LIVE. Remaining for the demo: messaging codified (1.5), `:with`
teaching, empty-message hygiene, file-corpus scenario.

## Run 7 — 2026-06-10 ~00:49Z, codebase-Q&A demo scenario (post units 1.5→#25)

Question (Vdx-2606092048, fresh): "How does seon validate schemas at transact
time? … Store what you find so the next agent doesn't have to redo this."

### Scenario 1: PASS — the full taught pipeline executed

- 16s: `seon.search/grep` for "validate" AND "unregistered" (searched the
  ERROR TEXT — smart) — the search→read recipe verbatim.
- 47s: registered `:finding/*` EXACTLY per the taught convention (no fork).
- Stored 3 findings with provenance: validate-attrs! (db.cljs:630,
  :verified), validate-entity-values! (:694, :verified), pipeline summary
  honestly :inferred.
- 95s: correct user-facing answer (two-step Malli-backed gate).
- Post-reply: kept extracting transact!'s body, hit its own string-code
  errors, read them via (result :id) and retried — accretion instinct
  works; slightly over-eager after the answer.

### Scenario 2 (tIY-2606092100, fresh, related question): EFFICIENT RE-DERIVE, NOT REUSE

- 70s flawless: grep (validate-entity-values! BY NAME at 8s — suspiciously
  specific; convention-guess or attr-name osmosis) → read-file → exact line
  ranges → correct reply! to the user.
- BUT: never queried the :finding rows. Knowledge was stored, discoverable
  (attrs in its catalog), and ignored.

### The next context gap (one level above run-4's)

Schemas get reused (taught). FINDINGS don't get consulted — discoverable ≠
salient. Fix candidates: (a) prompt/capabilities line: "before researching,
query existing :finding rows for your topic"; (b) render recent findings'
:finding/question one-liners in the catalog block (content salience, not
just attr names); (c) maybe a warnings-style surface when stored findings
match terms in the inbound question.

### Substrate scoreboard for the day

search→read recipe: PROVEN · finding convention: PROVEN · envelope honesty:
no false claims observed · reply! routing: PROVEN · UI: watchable live
(collapsed statics, turn separators, sub-second renders) · finding REUSE:
the one remaining behavior for the Friday demo bar.

## PRE-V4 BASELINE (post gym-simplification) — 2026-06-11

The gym was simplified per the user's r2 directive (gym-upgrade PRD
§r2): the U3 structural pass-gates (`:gym.structural/layout-complete`,
`:gym.structural/cache-prefix-stable`) and the `:context-fidelity`
rubric axis were RIPPED OUT, along with the double-render machinery.
The gym now tests AGENT BEHAVIOR only — mechanical store/outcome
datalog + prompt-blob predicates + the LLM judge — plus full CAPTURE
(prompt-blob paths, run-id uuids, per-turn section-name/char-count
telemetry, informational only). Then the baseline was rerun: stub tier
plus paid DeepSeek tier (`--paid=s32,s21,s12`), working tree at
`14bf47f` plus the uncommitted simplification.

### Harness fixes in this baseline (the simplification summary)

- `test/seon/gym/driver.cljs` (−118 lines net): `structural-results`,
  `dynamic-tail-sections`, `prefix-section-texts`, `first-char-diff`,
  `prefix-diff-detail` deleted; `capture-turn-profile` is a single
  render recording `[section-name char-count]` pairs; profile schema
  dropped `layout-complete?`/`layout-missing`/`prefix-stable?`/
  `prefix-diff`; `:seon.gym.axis/name` enum dropped
  `:context-fidelity`. `:seon.gym.scorecard/turn-profiles` stays a
  REQUIRED scorecard key and the driver unconditionally populates it —
  cards validate (the "schema still requires turn-profiles" trap was
  checked: not present).
- `test/seon/gym/driver_test.cljs`: the two structural falsification
  tests (volatile/stable static section) replaced by ONE telemetry
  test (`turn-profiles-record-context-telemetry-without-gating`) that
  asserts the verdict comes ONLY from scenario predicates while the
  evidence (profiles, prompt files) is all captured.
- No dangling refs to the deleted gates anywhere in `test/seon/gym/**`
  or `bin/gym` (grep-verified); scenario EDNs carry no
  `:context-fidelity` axes.

### Scenario table — stub tier (run 1, `tmp/gym-baseline-run1.log`)

Deliberate-falsification stubs (`gymtest-*` RED rows, the
`:deliberately-broken` envelope-honesty variant, prompt-blob-missing)
are SUPPOSED to be red — their deftests assert the red; the suite was
0-failure. Real stub scenarios:

| scenario | pass? | failed predicates | turns | run-id |
|---|---|---|---|---|
| s01-stub-pipeline-smoke | PASS | — (5/5) | 1 | 8ae80c60 |
| blank-message-refusal | PASS | — (3/3) | 1 | 8bef3671 |
| envelope-honesty (honest variant) | PASS | — (4/4) | 1 | 3e211c86 |
| finding-storage-shape | PASS | — (4/4) | 1 | 445ece13 |
| gymtest-context-telemetry (new) | PASS | — (1/1) | 2 | 43ee0263 |
| judge-wiring-mock | PASS | judge 88 "states the concise envelope keys" | 1 | 592626a1 |
| two-agent-judge-wiring | PASS | judges a:100, b:100 | 2 | 1dbe7fd6 |

### Scenario table — paid DeepSeek tier

| scenario | pass? | failed predicates | judge | turns | run-id |
|---|---|---|---|---|---|
| s21-log-workout-existing-schema (run 1) | PASS | — (7/7) | n/a (mechanical only) | 1 | bc091093 |
| s32-consult-before-research | **FAIL** | `:at-most-one-repo-search` (2/10 evals grepped; cap 1) | judge-pass? TRUE — 100: "correctly states message! returns a concise envelope… cites the source file, matching ground truth exactly" | 1 | 6c73d314 |
| s12-run8-two-agent-consultation | **FAIL** | `:a-stored-at-least-two-findings-with-provenance` (rows=1, need ≥2) | judge-pass? FALSE — a:40, b:40 (both replies claim transact! THROWS; ground truth: errors are VALUES — `{:seon.db/ok? false :seon.db/error …}`, caller never sees a throw; missing file citations) | 2 | 1a00af62 |

### S-21 stability verdict: 3/3 PASS — STABLE

Three independent paid probes, all 7/7 green
(`:run-row-landed-with-established-attrs`, `:no-attr-fork-anywhere`,
`:seeded-attrs-visible-as-domain-attrs`,
`:zero-schema-registrations-needed`, `:agent-replied-to-the-user`,
`:agent-ends-idle`, `:terminates-under-cap`):

| probe | run-id | log |
|---|---|---|
| 1 | bc091093 | tmp/gym-baseline-run1.log |
| 2 | 2744f2b0 | tmp/gym-s21-probe2.log |
| 3 | 3b6b9a6e | tmp/gym-s21-probe3.log |

Schema-reuse on an existing domain schema is reproducibly solid — a
safe demo scenario.

### System findings, ranked by demo risk (demo 2026-06-12)

1. **s12 judge double-fail (a:40, b:40) — the agent misdescribes the
   substrate's OWN error model.** Both agents told the user transact!
   throws ex-info; the real surface returns an error VALUE. If the
   demo includes "ask the agent how seon works", this exact wrong
   answer is on the table. Context candidate: the error-envelope
   contract (`:seon.db/ok?` value-not-throw) is evidently not salient
   in the rendered context.
2. **s12 finding under-storage (1 stored, need ≥2)** — same
   storage-salience class as run-7/#26; agent A consulted and searched
   correctly but stored only one finding row. Behavior, not harness.
3. **s32 over-searching (`:at-most-one-repo-search` red, 2 greps)** —
   the agent consulted the stored finding FIRST (predicate green) but
   then re-derived via grep anyway. Cost/efficiency smell, mild demo
   risk (slower turns), not a correctness risk.
4. **EXPECTATION DIVERGENCE, good direction: s32's
   `:seeded-claim-rendered-in-prompt` was expected-RED and came up
   GREEN** in run 1 — the seeded finding text IS rendering into the
   prompt now. The known finding-salience gap (#26) appears at least
   partially closed at the context layer; the remaining gap is
   behavioral (using it instead of re-searching, storing enough).

### Baseline numbers v4 must beat

- Stub tier: 7/7 real scenarios PASS (gate: stay 7/7).
- Paid tier: s21 3/3 stable PASS; s32 FAIL (1 red predicate, judge
  green); s12 FAIL (1 red predicate + judge double-fail at 40/40).
  v4 bar: s32 → 5/5 green; s12 → ≥2 findings stored AND judge ≥70 on
  both answers (no value-vs-throw misstatement).
- CLJS suite at this baseline: **365 tests / 1545 assertions / 0
  failures / 0 errors** (pre-simplification 366/1554/0; the −1/−9 is
  the two deleted structural falsification tests replaced by one
  telemetry test — expected).

## POST-V4 SWEEP — 2026-06-11 (context-v4 landed at 5071468; gym aligned; FRESH cluster)

Setup: gym aligned to v4 (shared `seon.client/boot-seed!`, boot-parity
creation evals, woken-turn predicate scoping, s32 identity-kind
fixture, P21 terminates-under-cap predicates — gym-upgrade PRD §r3);
live cluster RESET to a fresh store (old store at
`tmp/store-backups/store-pre-v4-reset-20260611-153550`; fresh boot
verified: 1 minted agent, creation turn logs the 3 tutorial evals,
zero `:my.kb.instruction`/`:your-sections` residue, live composer
renders 90,975 chars with no dead sections and 0 `render failed`
lines). Suite at sweep sha `815f908`+fence edits: **373/1590/0 on
every run** (matches the pre-sweep baseline counts).

### Stub tier

All real stub scenarios PASS across all four suite runs (s01,
blank-message-refusal, envelope-honesty, finding-storage-shape, plus
the gymtest falsification stubs in their deliberate states). Stub gate
HELD through the alignment.

### Paid tier — scenario × sweep

Sweep 1 ran BEFORE the answer-key fix (see harness fixes below) — its
s32 salience "green" was contamination, recorded for the record.

| scenario | sweep | mech | failed predicates | judge |
|---|---|---|---|---|
| s32 | 1 (pre-fix) | 4/6 | consult-first; repo-search (8 greps) | FAIL 40 |
| s32 | 2 | 3/6 | consult-first; salience 0/5; repo-search (3) | FAIL 40 |
| s32 | 3 | 4/6 | consult-first; salience 0/6 | **PASS 95** (0 repo searches — answered from the rendered `seon.agent` source) |
| s21 | 1 | 5/7 | run-row never landed; 6/13 register! evals | n/a |
| s21 | 2 | 5/7 | run-row never landed; 5/10 register! evals | n/a |
| s21 | 3 | 4/7 | run-row never landed; **fork landed** (`:my.kb.workout/*` registered); 1 register! | n/a |
| s12 | 1 | 6/8 | A stored 1 finding (<2); B grep'd first | **PASS 100/100** |
| s12 | 2 | 6/8 | A stored 0 findings; B grep'd first | FAIL 40/30 (B: wrong file + "currently throws — that's a bug") |

### The bar (set pre-v4) vs reality

- **s32 → 5/5: NOT MET.** Consult-first red 3/3 (GREEN at the pre-v4
  baseline — the V4-3 risk realized); the previously-EXPECTED-RED
  salience predicate is GENUINELY red (0/N prompts carry the seeded
  claim text) once decontaminated — sweep 1's green came from the
  agent grepping the gym's own test files. Re-grep economy improved
  monotonically across sweeps (8 → 3 → 0).
- **s12 → ≥2 findings + judge ≥70 + no transact!-throws
  misstatement: PARTIALLY MET, high variance.** Sweep 1: judges
  100/100, zero misstatements (the V4-1 errors-as-values teaching
  demonstrably landed) but 1 finding stored. Sweep 2: 0 findings,
  judges 40/30, and agent B said an unregistered attr "currently
  throws — that's a bug" — the misstatement class returned in 1 of 2
  sweeps.
- **S-21 stays 3/3: NOT MET — 0/3, the headline regression.**
  Root-caused below (finding 1); in every run the strength/run row
  the user asked to log NEVER landed in `:seon.workout/*`.

### Harness fixes this sweep (before/after documented)

1. **Filesystem answer-key leak (the §3.4 self-bait rule, fs
   variant).** The gym configured `allowed-roots [cwd]`, so paid
   agents grepped `test/seon/gym/driver_test.cljs` and read the
   judge's reference text verbatim ("never the raw tx-report" reached
   prompts via grep results — s32 sweep-1 blob
   `logs/prompts/QPD-2606111538/ykT-2606111538.txt`). Fixed:
   gym fs roots = `src/` + `docs/` (deliberate, documented parity
   divergence). Before: salience 7/8 "green". After: 0/N — honest red.
2. **envelope-honesty blanket transcript-excludes** tripped on the
   creation turn's legitimate tile-wiring success envelope → re-
   anchored as a datalog pin on the bogus eval's own result prefix.
   (datahike-cljs queries can NOT call `clojure.string/*` — engine
   built-ins only — and EDN can't carry regex literals; `subs`+`=` is
   the available shape. JVM-datahike habits don't port.)
3. **prompt-blob predicates** re-scoped to message-driven turns
   (`woken-by` present); the missing-blob falsification fixture gained
   a woken-by ref.

### System findings, ranked (evidence inline; src fixes NOT made — fence)

1. **Prior agents' domain schemas have NO v4 surface — the S-21
   killer.** The schema-catalog died (V4-3); `store-inventory` only
   lists kinds with an installed `:db.unique/identity` attr
   (`:seon.workout` has none — and CAN'T: date-as-identity would
   merge same-day rows); `seon.ctx/namespaces-section`
   (src/seon/ctx.cljs:767–789) requires `[?n :seon.ns/source ?src]`,
   and tee-minted data namespaces are SOURCELESS (`build-tee-entities`
   nested-upserts `{:seon.ns/name kw}` only — fresh live store already
   carries 13 sourceless ns rows). Net: an agent asked to "log it with
   my other workouts" cannot see `:seon.workout/*` anywhere and forks
   (`:my.kb.workout/*`, sweep 3) or stalls (sweeps 1–2). Fix
   hypotheses: (a) namespaces-section renders sourceless ns rows that
   have `:seon.schema/_ns`/`:seon.fn/_ns` members via the existing
   `reconstituted-ns-source`; (b) V4-3's stated escalation 2 — richer
   inventory rows (attr names) — plus a non-identity kind source
   (domain-attrs) for the inventory's kind derivation.
2. **Substrate namespaces are not requirable from agent evals.**
   s21 sweep-2 chronology (agent BNv-2606111546, 19:46:36): the agent
   imitated the rendered `my.kb` pattern with
   `(ns my.kb.workout (:require [seon.schema :as schema] [my.kb :as kb]))`
   → `"ns my.kb not available — :cljs/analysis-error"`. Cascade: ns
   form failed → `schema/register!` ×5 = undeclared var → transact
   rejected (unregistered attrs) → verification query threw the
   typo-guard. The prompt renders namespaces the eval environment
   refuses to require — show-don't-tell teaching a move that errors.
   Hypothesis: the bootstrap compile-state's analyzer cache lacks
   `my.*` entries (seon.repl/ensure-bootstrap! / seon.eval analysis
   cache wiring).
3. **Blind same-batch reply → false success claims to the user.** In
   the same s21 run, ALL of research+register+transact+verify+reply
   was ONE response batch; every form before the reply FAILED, and
   `reply!` still executed: "logged — 24-minute run on June 11.
   stored as run-2026-06-11." — nothing was stored. The reply is
   composed before any result exists, so errors can't inform it.
   Candidates: batch policy (stop executing a batch's remaining forms
   after an error, or hold a `reply!` that follows failed forms), or
   a taught reply-after-verify rule — measure with this same
   scenario.
4. **Consult-first regression (the V4-3 open risk, now measured).**
   3/3 s32 runs grep/pull first despite the turn-1 prompt carrying
   BOTH the system-prompt consult teaching AND the creation-turn
   inventory result showing `{:kind :my.kb.codebase … :rows 4}`
   (verified in blob QPD-2606111538/BzI-2606111538.txt — context
   rendered, agent ignored). The seeded claim TEXT renders in zero
   prompts, so salience stays red until a finding-claims surface
   exists (gym-upgrade §4.3 kb-section rung). PRD escalations in
   order: per-wake inventory eval → richer inventory rows → NEVER a
   resurrected section. Note for predicate semantics: sweep 3's agent
   answered correctly at judge 95 with ZERO repo searches by reading
   the rendered `seon.agent` source — v4's full-source body makes
   "consult the store first" and "already knows from the prompt"
   converge; the consult anchor may need a v4 rethink (user call —
   it's the behavior pin).
5. **s12 storage still under-landing, now with variance:** 1 finding
   (sweep 1) then 0 (sweep 2) vs the ≥2 bar; baseline stored 1.
   Sweep-2 agent A also burned 47 evals / 7 turns. The
   stores-proactively teaching is not landing as behavior.
6. **First-boot creation evals run BEFORE the substrate seed.** Fresh
   live pod: the FIRST agent's tutorial `store-inventory` showed all
   rows 0 and `(my.kb.system/instructions)` → `[]` (singleton not yet
   seeded); minted-later agents see the seeded world. Now a
   one-binding fix: in `start-agent!` move
   `(await (boot-seed! {:seon.db/conn conn}))` ABOVE the per-agent
   boot loop (the extraction made the seed callable early).
   src/seon/client.cljs.
7. **Judge-rubric staleness risk (harness hygiene):** s12's rubrics
   pin exact file/line ground truth (internal.cljs ~422/~499 —
   verified still correct this sweep); any db split/refactor must
   re-verify them or the judge grades against a dead tree.

### Staging list (gym fence — the orchestrator commits)

- `src/seon/client.cljs` (boot-seed! extraction ONLY + start-agent!
  call site)
- `test/seon/gym/driver.cljs`
- `test/seon/gym/driver_test.cljs`
- `test/seon/gym/scenarios/s32-consult-before-research.edn`
- `test/seon/gym/scenarios/consults-findings-run8.edn`
- `test/seon/gym/scenarios/envelope-honesty.edn`
- `test/seon/gym/scenarios/todo-resume.edn`
- `docs/prds/agent-runtime/gym-upgrade-prd-2026-06-11.md`
- `docs/prds/agent-runtime/research/e2e-demo-findings-2026-06-08.md`

Sweep logs: `tmp/gym-postv4-paid{1,2,3}.log` (sweep 1 = pre-fs-fix,
contaminated s32 salience).

## POST-WAVE-A RE-MEASURE — 2026-06-11 (Wave A landed: A2 = 2093b0e, A1+A3 = 3dbada1; measured at b185c2c + fenced gym edits)

Re-ran ONLY the post-v4 reds per fix-everything PRD "Then: the
re-measure". Suite (post-Wave-A): **409/1855/0** on all five runs
(was 373/1590/0 — Wave A added tests). Stub gate HELD (all stub
scenarios in their expected states, every run). Before the paid runs,
two DECIDED/required harness cuts landed in fence:

1. **§2 provenance widening applied** to the s12 storage predicate:
   provenance-SHAPED rows (source-path/source-line/confidence attr
   NAMES) in ANY namespace count; consult predicates stay strict on
   `:my.kb[./]`. Implementation note — engine pin, verified live:
   datahike-cljs THROWS "Cannot compare <keyword> to" when TWO datom
   patterns with distinct unbound attr vars join on one entity; the
   predicate rides a nested `q` (one unbound-attr pattern per level) +
   `set`/`contains?`, all engine built-ins. Falsified both ways
   (matches shared AND forked namespaces; excludes a row missing
   confidence).
2. **s12 judge ground-truth re-verified (f7 discipline):** Wave A
   moved the anchors — validate-attrs! 422→460,
   validate-entity-values! 499→615 in `src/seon/db/internal.cljs`;
   behavior claims unchanged. References updated.

### Scorecard — post-Wave-A vs post-v4 reds vs pre-v4 baseline

| scenario | sweep | result | failed predicates | judge |
|---|---|---|---|---|
| s21 | 1 | **PASS 7/7** | — (0/4 register evals, 3 turns) | n/a |
| s21 | 2 | **PASS 7/7** | — (0/8 register evals, 6 turns) | n/a |
| s21 | 3 | **PASS 7/7** | — (0/4 register evals, 3 turns) | n/a |
| s32 | 1 | 4/6 | consult-first; salience 0/2 | **PASS 100** (0 greps) |
| s32 | 2 | 5/6 | consult-first (salience 1/11 = docstring-contaminated, see below) | **PASS 100** (0 greps) |
| s32 | 3 | 4/6 | consult-first; salience 0/12 | **PASS 100** (1 grep) |
| s12 | 1* | 6/8 | storage 1 row; B grep'd first | FAIL 40/30 (*judge miscalibration — see harness fix 3) |
| s12 | 2 | 6/8 | storage 1 row; B consulted :seon.fn first | **PASS 100/100**, zero misstatements |
| s12 | 4 (re-run after judge fix) | 7/8 | B consulted :seon.fn first | **PASS 100/100**, zero misstatements, **storage 2 rows** |

### The bar vs reality

- **S-21 3/3: MET.** 0/3 → **3/3**, and better than the pre-v4
  baseline's economy: zero `schema/register!` evals in all three runs
  (pre-Wave-A: 6/13, 5/10, 1/8 register evals) and one clean transact
  of all four established attrs
  (`{:seon.workout/date "2026-06-11" :seon.workout/type :run
  :seon.workout/duration-minutes 24 :seon.workout/notes "felt good"}`),
  3-turn arcs, reply in a post-verify turn (the f3 blind-reply class
  did not recur here).
- **s32 5/5: NOT MET — but every red is a predicate-semantics call,
  not a behavior defect.** Re-grep economy GREEN 3/3 (0/0/1 vs the
  run-7 signature; pre-v4: 8→3→0); judge PASS 100 3/3 (post-v4: FAIL
  40 ×2); replied/idle/under-cap green. Consult-FIRST red 3/3 — and in
  ALL THREE runs the first eval WAS a store consult: a datalog/pull of
  the `:seon.fn` program graph for `message!` (the v4 convergence the
  post-v4 sweep already flagged — "consult the store" and "consult
  :my.kb.codebase" diverge when the store carries full sources; the
  anchor is the user's call). Salience: honestly red 2/3; sweep 2's
  1/11 "hit" is CONTAMINATION-BY-DOCSTRING (below).
- **s12 ≥2 findings + judge ≥70 + zero misstatements: MET on the
  judge-calibrated runs, storage variance remains.** Judges 100/100
  on both calibrated sweeps; ZERO transact!-throws misstatements in
  every sweep (sweep 1's judge-B FAIL 30 graded a CORRECT reply —
  see fix 3). Storage: 1 → 1 → 2 rows (pre-v4: 0–1); sweep 4 met the
  ≥2 bar with the TAUGHT mixed shape (`:my.kb.codebase.fn/*` domain
  attrs + shared `:my.kb/source-path/-line/confidence` on the same
  rows). Sweep 1's extra rows used `file`/`line` names with NO
  confidence-shaped attr — outside even the widened cut; counting
  them would be answer-chasing, so it stays red for that sweep.

### What each Wave-A fix demonstrably changed (blob evidence)

- **A1 (wire tx normalization):** S-21's run row LANDED 3/3 (post-v4:
  0/3 — "run-row never landed"). Single-eval transacts with the
  taught map form succeed (`:seon.eval/ok? true` on the transact
  evals; row read back by the predicate's post-run datalog).
- **A2 (parse-forms format contract):** S-21 eval counts collapsed
  13/10/8 → 4/8/4 with ZERO register evals; narration lines
  (`assistant> ;; …`) ride prompts as comments, and no run produced
  the eaten-consult `:read`-span signature. s12 sweep 2's agent A
  still burned 47 evals/13 turns — A2 removes parse losses, not
  re-derivation appetite.
- **A3 (per-attr inventory + loud truncation + sourceless
  reconstitution):** the S-21 carrier was the RECONSTITUTED ns
  source — all four `(schema/register! :seon.workout/… …)` forms
  render in the namespaces section in all three runs (post-v4: the
  namespaces-section required `:seon.ns/source` and tee-minted ns
  rows were invisible). The inventory itself contains
  `{:seon.db/kind :seon.workout …}` in the live value, but the
  creation-turn DISPLAY clips at "⚠ TRUNCATED at 1500 of 2294 chars"
  — deterministically at `:seon.handler.matc…`, so the workout row
  was NEVER visible in the rendered inventory in any run (sorted
  output puts `:seon.*` substrate kinds before user-domain kinds…
  and `:seon.workout` last). The truncation banner IS loud and the
  agents did not fork — but the inventory display budget is the top
  remaining gap (below).

### Harness fixes this re-measure (before/after)

3. **s12 judge miscalibration (sweep 1).** Judge-B FAILed (30) a
   reply that traced "internal throw → transact! catch →
   `{:seon.db/ok? false …}` envelope" — verbatim ground truth — by
   misreading the internal-throw description as a caller-facing throw
   claim (justification preserved in
   `tmp/gym-postwaveA-paid1.log`). Rubrics for judge-a/judge-b now
   state the internal/surface distinction explicitly ("describing the
   internal throw is CORRECT; FAIL only a claim that the CALLER sees
   a throw/rejection"). This calibrates the judge to reference facts
   it already had; agents never see rubrics. s12 was re-run (sweep 4)
   so both counted measurements use the calibrated judge. After:
   100/100, 100/100.
4. **s32 salience text is NOT unique to the fixture.** Sweep 2's
   1/11 "green": the agent pulled `message!`'s FULL SOURCE from the
   program graph, and the real docstring contains "returns a CONCISE
   envelope, never the raw\n   tx-report" — the §3.4 uniqueness check
   missed it because a LINE BREAK splits the phrase in src (verified:
   single-line grep of src/ finds nothing; blob line 2340 of
   `logs/prompts/NSD-2606111713/eLW-2606111713.txt` carries the full
   docstring). The fixture claim was authored FROM the docstring, so
   contamination-by-substrate is structural. NOT re-cut here — the
   predicate stays EXPECTED-RED and any new anchor text is the same
   user call as the consult anchor (both are the v4
   store-source/my.kb convergence question). Future self-bait checks
   must be whitespace-insensitive.

### System findings (report-only, src out of fence)

1. **Creation-turn inventory display clips before user-domain kinds
   (every S-21 run).** The per-attr inventory value is 2294 chars;
   the eval display caps at 1500; sorted order puts ~13 substrate
   kinds first. `:seon.workout` (and any user-domain kind) never
   renders. Fix hypotheses (general): render the creation-turn
   inventory result unclipped (it is THE consult surface), or sort
   non-substrate kinds first, or shrink substrate rows (they're
   derivable from the rendered namespaces anyway). src/seon/ctx.cljs
   (eval display clamp) / src/seon/db.cljs:744 (sort).
2. **`:seon.fn/sym` value-shape trap.** Agents consistently first try
   `[?f :seon.fn/sym "message!"]` (bare name) — stored values are
   qualified (`"seon.agent.message/message!"`) → silent empty. s32
   sweep 2 recovered via unbound `?sym` + client-side filter; sweep 3
   concluded (falsely narrated) "message! isn't stored as a :seon.fn
   row" and went to grep. Same legibility family as the query
   typo-guard, but for VALUE shapes on identity-ish attrs: a no-rows
   result on a unique-attr equality where a near-miss exists (suffix
   match) should say so. src/seon/db.cljs (query guard) or index bare
   names alongside.
3. **Consult-first behavior has converged on the program graph, not
   my.kb.** 4/4 first evals across s32+s12-B post-Wave-A were
   `:seon.fn`/`:seon.ns` datalog — the store IS being consulted
   first, the strict `:my.kb` anchors mark it red. The kb-section /
   finding-claims rung (gym-upgrade §4.3) plus the anchor-semantics
   user call are now the whole remaining distance on these axes.
4. **s12 storage variance persists** (1 → 1 → 2 rows vs ≥2 bar;
   judge-perfect answers regardless). The stores-proactively teaching
   lands as ONE exemplary row most runs. Wave B's shown-not-told
   mixed-namespace example (ROOT 1) is the open lever; sweep 4 shows
   the taught shape DOES land when the agent stores at all.
5. **Dangling schema-catalog pointers still in tree** (Wave B scope,
   unchanged): src/my/kb.cljs:22-23, src/my/soul.cljs:176,201 — one
   S-21 narration again said "The schema-catalog call…". Did not
   cause forks this time (reconstituted sources carried), but ROOT
   1's executable-teachings unit remains the fix.

### Ranked remaining gaps

1. Inventory display clip (finding 1) — hides exactly the rows the
   surface exists to show; cheap, general, src-side.
2. kb-section / finding-claims rung + the TWO user calls on predicate
   semantics (consult anchor, salience anchor) — without them s32's
   two reds are unmovable by any agent behavior.
3. Wave B executable teachings (catalog pointers, sym-shape examples,
   shown mixed-namespace provenance) — findings 2, 4, 5.
4. s12 storage volume (behavioral; re-measure after Wave B).

### Staging list (gym fence — the orchestrator commits)

- `test/seon/gym/scenarios/consults-findings-run8.edn` (§2 widening +
  judge calibration + f7 line re-anchors)
- `docs/prds/agent-runtime/research/e2e-demo-findings-2026-06-08.md`
  (this section)

Sweep logs: `tmp/gym-postwaveA-stub.log`,
`tmp/gym-postwaveA-paid{1,2,3,4}.log`; cards extracted at
`tmp/card-{s21,s32,s12}-{1..4}.edn`. Sweep 1 s12 = pre-judge-fix
(recorded, superseded by sweep 4).

---

## POST-WAVE-B RE-MEASURE — 2026-06-11 evening (Wave B landed; tree frozen at ba9a71e; S-21 NOT re-run — 3/3 this afternoon stands)

Re-ran ONLY s32 and s12 per fix-everything PRD "Then: the re-measure".
Suite (post-Wave-B): **432/1935/0** on every run (stub-gate run plus
5×s32 plus 4×s12) — stub gate HELD throughout. No harness cuts were needed
pre-flight: the §2 storage widening, the widened consult anchor, the
§2b whitespace normalization, and the s32 fixture re-cut were already
in tree. **Zero predicate re-cuts this measure.**

### Scorecard — post-Wave-B

| scenario | sweep | consult-first | repo-search | storage | judge | notes |
|---|---|---|---|---|---|---|
| s32 | 1 | **PASS** | 0 greps | n/a | PASS 100 | salience red (expected-red pin) |
| s32 | 2 | **PASS** | 4 greps (red) | n/a | PASS 100 | re-derivation appetite recurred |
| s32 | 3 | **PASS** | 0 greps | n/a | PASS 100 | |
| s32 | 4 | **PASS** | 0 greps | n/a | PASS 100 | |
| s32 | 5 | **PASS** | 0 greps | n/a | **FAIL 0** | honest red — see below |
| s12 | A1 | B grep'd first | A searched ok | **0 rows** | **0 / 30** | A never replied; B misstated (below) |
| s12 | B1 | B grep'd first | A searched ok | 1 row | 95 / **30** | |
| s12 | A2 | B grep'd first | A searched ok | 1 row | **40** / 95 | |
| s12 | A3 | B grep'd first | A searched ok | **0 rows** | 95 / 100 | zero misstatements |

### The bar vs reality

- **s32 consult-first 5/5 (widened anchor): MET.** Every run's first
  message-driven eval was a `seon.db/query` of the `:seon.fn`/`:seon.ns`
  program graph — the widened "any store read" anchor passes 5/5 (the
  old vocabulary anchor would have scored 0/5; behavior identical to
  post-Wave-A, the anchor semantics were the fix). Repo-search economy
  4/5 (run 2: 4 greps = the run-7 re-derivation signature, once).
  Judge 4/5 at 100. **Run 5's judge 0 is an honest red and the
  remaining s32 risk shape:** the agent queried
  `[?n :seon.ns/name :seon.agent]` for `message!`'s source — wrong ns
  (it lives in `seon.agent.message`; runs 1–4 queried correctly or
  recovered) — got empty rows, never fell back to the seeded
  `:my.kb.codebase` finding (which answers verbatim), and replied "the
  store query came back empty". Program-graph consult with NO kb
  fallback = right reflex, brittle target. Salience predicate red 5/5
  as pinned (EXPECTED-RED until a context rung renders claim text).
- **s12: ALL THREE SUB-BARS MISSED.**
  - **Storage ≥2: 0 / 1 / 1 / 0** (post-Wave-A: 1/1/2 — no
    improvement; A1's and A3's agent A stored NOTHING despite A3's A
    giving a judge-95 answer).
  - **ZERO transact!-throws misstatements: 1 misstatement** (A1's
    agent B). Verified against the actual reply text, not just the
    judge: B read the internal validator and told the user "If
    validation fails, it THROWS — not an envelope — … So you get an
    `ExceptionInfo` whose `ex-data` contains …" with no mention of
    the `transact!` catch. The live surface contract
    (src/seon/db.cljs `transact!`, "SAFE BY DEFAULT … never throws
    into your eval") is unchanged — the reply is a caller-facing
    throw claim, exactly the class the bar tracks. Honest red.
  - **Judges ≥70: 4 of 8 slots below** (0, 30, 30, 40). A1's 0 = A
    sent no reply at all (2-turn arc, idle without replying — the f3
    blind-idle sibling). The 30s/40 are real answer defects
    (wrong/absent function locations, missing the second gate step),
    with one stale-ground-truth caveat (smell 2 below) that does NOT
    flip any verdict.
  - **NEW REGRESSION — B's consult-first: 0/4** (post-Wave-A: 3/3
    store-consult). Every B's first message-driven eval was a
    `seon.agent.search/grep`, so even the WIDENED any-store-read
    anchor fails. Wave B's context changes steered B to the repo
    before the store; this axis moved backward while s32's identical
    axis is 5/5 — the difference is the question shape ("where
    exactly does seon check…" reads as a code question; s32's reads
    as an API question).

### Smells (report-only; src/ out of fence)

1. **`seon.agent.search/grep` returns wrong results when scoped via
   `:seon.agent.search/paths`.** A1's agent B grepped
   `"validate-values!"` in `src/seon/db/internal.cljs` and got
   match-count 2: line 1098 (real) + `(defn- validate-values!` at line
   1147. The live file has EIGHT matching lines, the defn (no dash) is
   at 678, and live line 1147 is `:keep-history?` precondition code;
   the text `(defn- validate-values!` exists in this repo ONLY in
   `src/seon/db.clj:129` (the JVM sibling). Cross-file result/label or
   offset bug in the search tool — it fed B the wrong line numbers it
   then cited (1153–1171). Worth a focused agent.
2. **s12 judge references missed the f7 re-verification for Wave B.**
   They still anchor "post-Wave-A 3dbada1" and treat
   `validate-entity-values!` as the only correct name —
   `validate-values!` (the tx-walking wrapper, internal.cljs:678,
   called at the 1098 gate) is a CORRECT thing for an agent to name,
   and judge-b dinged A1/B1 partly for naming it. Did not flip
   verdicts (A1-B's red stands on the misstatement; B1-B also
   invented `validate-entity` @333), but re-anchor before the next
   paid s12.
3. **One silent paid no-op (non-reproduced).** The stub-gate run
   (`tmp/test-cljs-20260611-195542-86333.log`) printed
   `SEON-GYM PAID-GATE {… gate "s32" enabled [:s32]}` yet left ZERO
   s32 trace — no scorecard, no refusal failure, 432/1935/0, 1m49s
   (stub-only duration). All five sweep runs against the SAME
   compiled test.js + env ran s32 normally. One-off; if it recurs,
   instrument `run-paid!`'s skip/refuse/run branches.
4. **Scorecard lines appear twice per run log** — benign: the second
   is `bin/gym`'s epilogue re-echoing the grep of `tmp/gym-latest.log`
   into the same redirected file, not a double print (run-ids
   confirmed single in the live stream).

### Operational note (this measure)

The s12 sweep launch double-fired (two identical 3-run loops ~1 min
apart); the duplicate was killed after its run 1 completed cleanly.
That extra completed run is COUNTED (B1 — same binary, same protocol);
its killed run 2 is discarded. The `tmp/gym-postwaveB-s12-{1,2}.log`
files are two-writer chimeras — the per-invocation
`tmp/test-cljs-<ts>-<pid>.log` files are the source of truth and are
what the cards were cut from.

### Staging list (gym fence — the orchestrator commits)

- `docs/prds/agent-runtime/research/e2e-demo-findings-2026-06-08.md`
  (this section)

Sweep logs: s32 `tmp/gym-postwaveB-s32-{1..5}.log` (cards
`tmp/card-postwaveB-s32-{1..5}.edn`); s12 per-pid
`tmp/test-cljs-20260611-{200915-98110,201042-99010,201215-99978,201617-2925}.log`
(cards `tmp/card-postwaveB-s12-{A1,B1,A2,A3}.edn`). Prompt blobs:
s32 `logs/prompts/{rxh-2606111959,shz-2606112001,dhV-2606112004,ICX-2606112006,Kdx-2606112008}/`;
s12 `logs/prompts/{iDn,UZm}-2606112010/`,
`{Xes-2606112011,WMf-2606112013}/`, `{UTN-2606112013,KYl-2606112014}/`,
`{YPW-2606112017,ruI-2606112018}/`.

## POST-WAVE-B COLLECTION COMPLETE — 2026-06-12Z (run 4 recovered + root-cause diagnosis)

The post-Wave-B measure RAN TO COMPLETION: the gym driver process
survived the harness restart that killed the driving agent, and s12
run 4 (agent pair `kdB-2606112024` / `hrq-2606112026`) finished and
cut a card. This section reconciles ALL artifacts, adds run 4, and
diagnoses the two universal failures. NO new paid runs; src/ read-only.

### Artifact reconciliation (read this before trusting file counts)

- 7 s12 card FILES = **5 distinct runs**. `card-postwaveB-s12-1.edn` is
  a byte-level duplicate of `-B1` (run-id `34794e2a…`, agent
  `Xes-2606112011`) and `-s12-3.edn` duplicates `-A3` (run-id
  `888fa200…`, agent `YPW-2606112017`) — same cards saved under a
  second naming scheme. There is no "run 2" under the numeric scheme.
- `card-postwaveB-s32-{4,5}.edn` each contain the SAME scorecard twice
  (identical run-id and timestamp, `SEON-GYM SCORECARD` prefix) — a
  duplicate echo of one run, NOT an earlier appended run. 5 distinct
  s32 runs total, as scored.
- Only run 4's driver log survives (`tmp/gym-postwaveB-s12-4.log`;
  `tmp/gym-latest.log` is the same invocation modulo the epilogue
  echo). Earlier-run evidence = cards + prompt blobs.

### Run 4 (the recovered run) — the best s12 run of the sweep

| predicate | result |
|---|---|
| `:a-stored-at-least-two-findings-with-provenance` | **FAIL** rows=[] |
| `:a-searched-the-repo` | PASS (10/56 evals) |
| `:b-first-eval-consults-stored-findings` | **PASS** — B's first eval: `(seon.db/query … [?e :my.kb.codebase/question ?q] [?e :my.kb.codebase/answer ?a])` |
| `:b-replied-to-the-user` / idle / caps | all PASS |
| judges | **100 / 100** — zero misstatements |

Run 4 flips two claims in the section above: B-consult-first is
**1/5, not 0/4** (run 4's B consulted A's stored finding FIRST and the
consult paid off — the accumulation thesis demonstrated end-to-end
once), and "judges ≥70 both slots" holds in **2/5** runs (A3, 4).
Blobs: `logs/prompts/{kdB-2606112024,hrq-2606112026}/`.

### Corrected scenario × run table (5 s32 + 5 s12 distinct runs)

| scenario | run | agent | consult-first | salience | storage | judge | card |
|---|---|---|---|---|---|---|---|
| s32 | 1 | rxh-2606111959 | PASS | red 0/6 | n/a | 100 | s32-1 |
| s32 | 2 | shz-2606112001 | PASS | red 0/9 | n/a | 100 (4 greps red) | s32-2 |
| s32 | 3 | dhV-2606112004 | PASS | red 0/2 | n/a | 100 | s32-3 |
| s32 | 4 | ICX-2606112006 | PASS | red 0/3 | n/a | 100 | s32-4 (dup echo) |
| s32 | 5 | Kdx-2606112008 | PASS | red 0/2 | n/a | **0** | s32-5 (dup echo) |
| s12 | A1 | iDn/UZm-2606112010 | FAIL (grep) | — | **0 rows** | **0 / 30** | s12-A1 |
| s12 | B1 | Xes/WMf-2606112011 | FAIL (grep) | — | 1 row (eid 1054) | 95 / **30** | s12-B1 ≡ s12-1 |
| s12 | A2 | UTN/KYl-2606112013 | FAIL (grep) | — | 1 row (eid 1065) | **40** / 95 | s12-A2 |
| s12 | A3 | YPW/ruI-2606112017 | FAIL (grep) | — | **0 rows** | 95 / 100 | s12-A3 ≡ s12-3 |
| s12 | 4 | kdB/hrq-2606112024 | **PASS** | — | **0 rows** | 100 / 100 | s12-4 |

**Bars: s32 consult-first 5/5 MET (judge 4/5, repo-economy 4/5,
salience 0/5 = the expected-red pin). s12 bar NOT met** (storage ≥2:
0/5 runs; zero-misstatement: violated once, A1-B; judges ≥70 both
slots: 2/5).

### Diagnosis 1 — s12 storage rows≈0: predicate is CORRECT, the misses are real, three mechanisms

The scenario EDN's storage predicate IS the fix-everything §2 decided
shape (name-anchored `source-path` + `source-line` + `confidence` on
one entity, any namespace, nested-q form) — verified by reading
`test/seon/gym/scenarios/consults-findings-run8.edn` against PRD §2.
And it WORKS: it found the one fully-provenanced row in B1 (eid 1054)
and A2 (eid 1065). The universal sub-2 counts are agent behavior, with
concrete mechanisms proven from run 4's tx log + the rendered prompts:

1. **The worked example agents copy omits `:my.kb/source-line`.** The
   rendered `my.kb` ns docstring's canonical transact row carries
   `source-path` + `verified-at` + `confidence` — NO `source-line`.
   The ambient `my.soul` seed rows (visible in every store-inventory)
   carry the same line-less shape. Agents imitate what is shown; the
   predicate demands what is told. Same told-vs-shown failure as
   blind-spot #9, one attr over.
2. **Line RANGES make agents fork a plural attr.** Run 4's A read a
   multi-line validator and registered
   `(seon.schema/register! :my.kb/source-lines :string)` (plus a
   `:my.kb.codebase/source-lines` sibling) because the registered
   `:my.kb/source-line` is a single `:int` — then stored ONE Q&A
   mega-row with `source-path` + `confidence` + `source-lineS`. The
   name check (`"source-line"` exactly) scores it 0. The §2 widening
   un-pinned the NAMESPACE but still pins the exact NAME; "source-lines"
   is the same vocabulary-vs-behavior near-miss one rung down.
3. **Consolidation under an identity question-attr.** Registering
   `:my.kb.codebase/question` as identity (run 4 did) upserts
   everything about one question into ONE row — ≥2 rows requires ≥2
   questions' worth of granular claims, which a single-question prompt
   doesn't naturally produce. A1's A is the degenerate case: idle
   after one truncated grep, stored nothing, replied nothing.

**Fix units:** (S) align the `my.kb` docstring example + `my.soul`
seed rows to carry `:my.kb/source-line` — show the full shape agents
are graded on; (S) DECIDE range semantics — either bless a registered
`:my.kb/source-line-end :int` next to the `:int` start (and show it),
or keep single-line and say so in the docstring; do NOT silently widen
the predicate to accept `source-lines` before the vocabulary decision;
(M) if granular multi-row storage is the bar, the `my.kb` instructions
must show a TWO-row store (one claim per row) for one question — the
current example shows exactly one row per question.

### Diagnosis 2 — s32 salience 0/5: world seeded fine; NO prompt surface carries row content; pinned red by design

- **The seed WAS transacted in every run world.** All 5 runs' prompt
  blobs show `(seon.db/store-inventory)` returning
  `{:seon.db/kind :my.kb.codebase, :seon.db/attrs {:my.kb.codebase/claim 4, :my.kb.codebase/question 4}}`.
- **The predicate is the documented EXPECTED-RED pin** (fixture
  comment: "EXPECTED-RED until a context rung renders finding CLAIM
  TEXT (not just attr names) into the prompt" — gym-upgrade §4). Not a
  regression; it is the #26 target behavior awaiting its fix unit.
- **Mechanism, precisely:** the rendered sections are
  `[:system :namespaces :your-entity :live-tile :warnings :open-todos :transcript :prompt]`.
  Stored data reaches a prompt ONLY via (a) store-inventory output in
  the transcript — attr names + counts, never values — or (b) an
  agent's own query results. No s32 agent ever queried
  `:my.kb.codebase` (all five first-evals targeted `:seon.fn` source
  rows), so claim text never entered any blob. The substrate even
  diagnoses the render gap itself, live, in every s32 prompt:
  `[unmarked-entity-kinds] … Affecting: :my.kb.codebase/claim` — the
  seeded kind has an identity attr but no `{:seon.db/entity true}`
  `:map` schema, so its rows are "invisible to the entity renderer".
  Note the warning would NOT have fixed salience: no prompt rung
  renders entity-marked row content either.
- **Side effect worth tracking:** that warning ends "Please correct
  before moving on" — in s32 worlds it points at the FIXTURE's kind,
  nudging paid agents toward schema housekeeping on harness-seeded
  data (self-defeating-surfaces adjacent; rxh spent transacts on it).

**Fix unit (M, the #26 unit this measure specs):** a derived findings
rung — render the rows of kb-shaped kinds (claim/question + provenance
attrs) as a context section, content not just names, clipped per kind;
the salience predicate then flips green with zero gym edits. Smaller
alternative (S, weaker): teach the kb instructions an explicit
"inventory shows a `:my.kb.*` kind → datalog its rows BEFORE the
program graph" rule; this also addresses the s32-5 judge-0 class
(empty wrong-ns program-graph query → "undocumented" reply, with the
verbatim answer sitting in 4 seeded rows the agent never read).

### Diagnosis 3 — judge-red classes (named, with evidence)

- **A1 judge-a 0 — premature-idle/no-reply** (f3 blind-idle sibling):
  A's whole arc = tile-wire, inventory, ONE grep (result display
  truncated at 1500/16405 chars), idle. No store, no reply. Real.
- **A1 judge-b 30 — internal-throw SURFACE-MISATTRIBUTION (new class,
  NOT the old hallucinated transact!-throws).** B grepped and read the
  REAL validator in `src/seon/db/internal.cljs`, then told the user
  "If validation fails, it THROWS — not an envelope … you get an
  `ExceptionInfo`". The old class invented a throw from priors; this
  one reads true internal code and reports the internal throw as the
  caller contract, never tracing the catch in `seon.db/transact!`'s
  public face. Evidence-grounded wrongness survives Wave A because
  Wave A fixed the priors path, not the partial-read path.
- **B1 judge-b 30 — fabricated provenance:** non-existent
  `validate-entity` "at line 333" + misstated pipeline order.
- **A2 judge-a 40 — omission:** described gate step 1
  (`validate-attrs!`) only; never mentioned per-value Malli validation.
- Bonus from A1-B's transcript: B emitted bare prose lines outside
  `;;` comments → 3 reader-error evals → the `message!` REFUSAL gate
  correctly blocked the composed-before-results reply (gate working as
  designed, live). The transcript then shows the agent evaluating a
  bare `{:seon.agent.message/ok? true …}` map LITERAL — which renders
  indistinguishably from a real send result. C-19's
  model-authored-result flagging covers exactly this; +1 priority.

### Diagnosis 4 — cleanup check: CLEAN

- The gym's scratch-store isolation held: the live pod store
  (`http://127.0.0.1:7890/agents`, HTTP 200) lists 9 agents, ALL
  pre-existing (newest `FqR-/yPU-2606111933`); zero `rxh-*` or
  `26061120xx`-cohort ids leaked. No `seon.agent/complete!` strays to
  sweep.
- Pod healthy end-state: `bin/seon status` green (pod, cljs-watch,
  jvm, wire-server all up); pod restart at 23:34Z predates nothing in
  this measure.

### Smells (additive to the four above)

5. **Gym card writer emits duplicate artifacts** — same card echoed
   twice into one file (`s32-{4,5}.edn`, `SEON-GYM SCORECARD` prefix)
   and same run saved under two file names (`s12-1`≡`B1`, `s12-3`≡`A3`).
   A naive reader counts 7 s12 runs where 5 exist; one canonical
   `card-<run-id>.edn` per run would make miscounting impossible.
6. **`:my.kb/source-line` is `:int` (single line) while the gym's own
   reference answers cite ranges** ("~line 460", "1153–1171") — the
   substrate's provenance vocabulary cannot express what its own
   ground truth uses; that contradiction is what pushed run-4's A to
   mint `source-lines`.
7. **s32 worlds nag agents about fixture schemas** (`unmarked-entity-
   kinds` fires on `:my.kb.codebase/claim`, "Please correct before
   moving on") — harness-seeded data generating a standing to-do in a
   paid agent's prompt; either seed the kind entity-marked or exempt
   identity-attr kinds without entity schemas from the imperative tone.

### Verdict

- **s32: bar MET** (consult-first 5/5 on the widened anchor); salience
  0/5 stays the pinned #26 target; one honest judge-0 (no-kb-fallback
  class); one re-derivation relapse (run 2).
- **s12: bar NOT met** — storage 0/5 (real, three mechanisms above),
  one misstatement (new surface-misattribution class), judges ≥70 both
  slots 2/5. Run 4 is the existence proof the demo loop works
  end-to-end: A stores, B consults FIRST, B answers 100 — the storage
  red there is purely the `source-lines` vocabulary near-miss.
