---
type: research
status: active
tags: [research, agent, database]
---

# seon.db Flagship Drive-and-Tune Loop

## TL;DR

The user's methodology is a per-namespace **drive-and-tune loop**: curate a
namespace's REAL source into the agent's context, drive a live DeepSeek agent
on real tasks, observe what it can and cannot do, refine that namespace's
actual source plus comments, and repeat until the agent does the work
first-try. `seon.db` is the flagship proof — make it so well-taught that an
agent can do arbitrary queries and transactions.

The one curation lever is plain data: add `:seon.db` to `full-source-whitelist`
in `src/seon/ctx/namespaces.cljs:115`, then `bin/seon cluster reset default` so
the boot indexer re-reads `db.cljs`'s real file into `:seon.ns/source`. Today
`seon.db` renders as a truncated signature manifest (`;; ── namespace seon.db
(signatures) ──`), so "show, don't tell" is NOT happening for db work — the
agent sees `(seon.db/query [& args]) :spec [...…]` and a one-line doc fragment,
never the worked examples that already live in the docstrings. The harness is a
synchronous drive of `seon.agent.fsm/run-loop!` against the live agent conn with
the DeepSeek adapter, scored by reading the agent's eval rows and outbound
messages back from the DB. No new APIs: we show and refine real code; the
schema-generator and health-checker stay deferred.

Verified live (2026-06-23, branch `feature/agent-fsm`, session `default`):
`seon.db` has NO stored source (`:seon.ns/source` is the 12-char stub
`"(ns seon.db)"`), `full-source-ns? :seon.db` is `false`, and the whitelist is
`#{:seon.agent.todo}`. The lever is exactly the one-line edit plus reset.

## The Drive-and-Tune Loop

The loop is per-namespace and identical for every namespace; `seon.db` is just
the first one driven to completion. One mechanism, repeated.

1. **Curate** — set the target namespace to render as REAL FULL SOURCE. For a
   `seon.*` framework namespace this is one edit: add its keyword to
   `full-source-whitelist` (`src/seon/ctx/namespaces.cljs`). `my.*` namespaces
   are already full by the `my.*` rule; third-party (`acme`) roots are full by
   structural fall-through. The whitelist is the ONLY editable knob; it feeds
   `full-source-ns?`, which is consumed by BOTH the boot indexer (decides which
   namespaces get the real file text stored) AND `render-full?` (decides
   `:full` vs `:signature` detail). One rule, one writer, no drift.

2. **Re-index** — `bin/seon cluster reset default`. The whitelist edit is
   source, so it only takes effect after a rebuild (cljs-watch) AND a reset:
   the boot indexer (`seon.client/ns-row`) reads the real file into
   `:seon.ns/source` only for namespaces that pass `full-source-ns?`. Without
   the reset the stored source stays the `(ns x)` stub and the full render
   shows nothing. The reset re-seeds the core program graph from the indexed
   codebase, so the namespace's docstrings/comments — the manual — flow
   straight into the agent's full render.

3. **Drive** — run a live DeepSeek agent synchronously on a battery of real
   tasks for that namespace (`seon.agent.fsm/run-loop!` against a fresh scratch
   agent id on the live agent conn). Deliver each task as a human message, run
   the loop to completion, capture the exact forms the agent wrote, their eval
   results, and the human-facing answer.

4. **Observe** — for each task, read the DB back. Did the agent write a real
   `seon.db/query`/`transact!` form (not a fabricated `;;=>` claim)? Was
   `:seon.eval/ok?` true? Did the outbound answer match the independently
   computed ground truth? Cluster the failures by KIND: wrong call shape
   (passed a db when it should be omitted; used `pull-by-name` which does not
   exist), missing the `:seon.db/ok?` envelope check, wrong `:find` spec,
   keyword-equality on a ref attr, etc.

5. **Refine the SOURCE** — fix the cause in `db.cljs`'s real docstrings and
   `;;` comments (show, don't tell): add the smallest pod-correct worked
   example for the pattern the agent missed, right in the relevant fn's
   docstring so it renders with the fn. Demote agent-irrelevant publics to
   `defn-` so the full render is signal, not noise. Then go to step 2.

The loop terminates for a namespace when the agent does the battery first-try
across a few runs. Then pick the next namespace and repeat. Do NOT add a
parallel "curated db doc" namespace or a v2 renderer — the single
`full-source-whitelist` + `render-namespace` path is the mechanism; everything
else is data.

## The Harness

The exact runnable recipe. Real fn names from the codebase; a working
precedent exists at `scratchpad/nodeaf_probe.cljs` (same skeleton, stub llm-fn).

### Wiring hazards (read first)

- **The MCP default `:client` runtime conn is NOT the agent conn.** Verified:
  `@seon.db/*conn*` in the default session is a different empty conn. The live
  store (82 `:seon.ns` rows, the `user` entity, the program graph) is reachable
  only via `@seon.client/!agent-conn`. Every harness eval MUST start with
  `(set! seon.db/*conn* @seon.client/!agent-conn)` or run inside
  `(seon.db/with-agent id ...)`.
- **Never open a second writer conn on the live pod.** `nodeaf_probe.cljs` opens
  its own conn — fine for an isolated probe, but on the live pod it contends on
  the single wire-server writer. Reuse `@seon.client/!agent-conn`.
- **`await` / `^:async` are ILLEGAL in MCP REPL evals.** Verified: a top-level
  `(await …)` or an `^:async fn` evaluated through `mcp__seon_cljs__eval` throws
  `ReferenceError: await$ is not defined` (the self-hosted REPL has no async
  transpile context). Drive the whole sequence via PROMISE `.then` chaining
  instead — Node's AsyncLocalStorage propagates across `.then`, so a
  `(seon.db/with-agent aid (fn [] (-> (js/Promise.resolve nil) (.then step1)
  (.then step2) …)))` keeps the agent scope intact. Capture the final result
  into an atom (`(reset! !out …)`) and read it back in a SECOND eval — the MCP
  eval returns the Promise object, not its resolution.
- **Use `(seon.db/new-id!)` for every id you store.** Verified: id attrs
  (`:seon.db/id`, the message/todo/etc. ids) are `[:string {:min 14 :max 14}]`,
  so a custom string like `"todo-001"` or `(str "MSG-" aid)` FAILS Malli value
  validation and the tx returns `:seon.db/ok? false`. Mint ids with `new-id!`.
- **Give scratch agents a constant DB-assistant `:seon.agent/purpose`.** Without
  a purpose a fresh agent onboards/greets the human instead of doing the db
  work. Set a fixed role (a DB assistant — NOT db-skill coaching, which would
  leak the answers): e.g. `:seon.agent/purpose "You are a database assistant.
  Do exactly what the human asks against the cluster store and report the
  result."` Same string for every battery task; it is role, not a hint.
- **Drive synchronously (one logical sequence, `.then`-chained).** Run the whole
  drive as one chained Promise; do not rely on the async wake trigger
  (setTimeout-based, racy, breaks the ALS scope, and overlapping loops wedge the
  shared async continuation).
- **`:seon.fn/ns` is a REF, not a keyword.** Verified: `[?e :seon.fn/ns
  :seon.db]` THROWS "Nothing found for entity id :seon.db". Join through the ns
  entity: `[?e :seon.fn/ns ?n] [?n :seon.ns/name :seon.db]`. This is itself a
  teaching trap (see refinement target).

### Recipe

1. **Set curation (or baseline-A).** Edit `src/seon/ctx/namespaces.cljs:115`
   from `#{:seon.agent.todo}` to `#{:seon.agent.todo :seon.db}`. Save
   (cljs-watch rebuilds). Skip this for a baseline-A run (signatures-only) to
   measure the lift vs full-source — the A/B the vision asks for.

2. **Reset.** `bin/seon cluster reset default`; wait for `agent roster` in
   `logs/pod.log` (`bin/seon tail pod`). The indexer now stores `db.cljs`'s
   real file as `:seon.ns/source` for `:seon.db`.

3. **Re-bind the conn** (the reset replaced `!agent-conn`). Via
   `mcp__seon_cljs__eval` session_id `default`:
   `(set! seon.db/*conn* @seon.client/!agent-conn)`. Confirm
   `(seon.db/query '[:find (count ?n) . :where [?n :seon.ns/name]])` > 0 and
   `(seon.ctx.namespaces/full-source-ns? :seon.db)` is now `true`.

4. **Deliver task + drive, via `.then` chaining** (timeout_ms 300000; NO
   `await`/`^:async` — see the wiring hazard). Define each phase as a fn taking
   the prior result and RETURNING a Promise, then chain them; stash the final
   record in an atom and read it back in step 5.

   - `(set! seon.db/*conn* @seon.client/!agent-conn)`
   - `(def aid (str "DRVdb-" (.getTime (js/Date.))))` — fresh scratch id, never
     collides with the live roster.
   - `(def llm (seon.ai.openai-compat/agent-adapter {:seon.ai/temperature 0}))`
     — DeepSeek, deterministic; provider already `:deepseek`, `DEEPSEEK_API_KEY`
     present.
   - `(def !out (atom :pending))`
   - Chain bootstrap → setup-ns → create → deliver → drive, all inside the
     agent scope, ids from `new-id!`, a constant DB-assistant purpose:

     ```clojure
     (seon.db/with-agent aid
       (fn []
         (-> (seon.repl/ensure-bootstrap!)
             (.then (fn [cs]
               (-> (seon.eval/setup-agent-ns! cs (seon.ctx/home-ns aid) aid)
                   (.then (fn [_]
                     (seon.agent/create! {:seon.agent/id aid
                                          :seon.agent/purpose "You are a database assistant. Do exactly what the human asks against the cluster store and report the result."
                                          :seon.agent/max-turns-per-loop 5})))
                   (.then (fn [_]
                     (seon.db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id (seon.db/new-id!)   ; NOT a custom string
                          :seon.agent.message/from {:seon.user/id "user"}
                          :seon.agent.message/to [{:seon.agent/id aid}]
                          :seon.agent.message/content "<TASK>"
                          :seon.agent.message/at (js/Date.)
                          :seon.agent.message/hops 0
                          :seon.agent.message/origin :human}]})))   ; origin :human, hops 0 — wake gate
                   (.then (fn [_] (seon.agent/fresh-wake! {:seon.agent/id aid})))
                   (.then (fn [w]
                     (-> (seon.agent/set-state! {:seon.agent/id aid :seon.agent/state :active})
                         (.then (fn [_]
                           (seon.agent.fsm/run-loop! {:seon.agent/id aid
                                                      :seon.agent/llm-fn llm
                                                      :seon.agent/compile-state cs} w))))))
                   (.then (fn [halt] (reset! !out {:halt halt})))))))))
     ```

5. **Capture.** With `*conn*` still bound:

   - `evals` = `(seon.ctx/session-evals aid @seon.db/*conn*)` — oldest-first.
   - `forms` = `(mapv (juxt :seon.eval/source :seon.eval/ok? :seon.eval/result-edn :seon.eval/error) evals)` — exactly what the agent wrote and what it returned.
   - `outbound` = the agent's human-facing messages:
     `(seon.db/query {:seon.db/db @seon.db/*conn* :seon.db/query '[:find [?c ...] :in $ ?me :where [?m :seon.agent.message/from ?me] [?m :seon.agent.message/content ?c]] :seon.db/args [(:db/id (seon.db/entity {:seon.db/db @seon.db/*conn* :seon.db/ref [:seon.agent/id aid]}))]})`
   - `halt` keyword + turn count + final state round out the record.

6. **Score** in Clojure in the same eval. Compute the ground truth
   independently in the harness eval, then predicate on the captured data: the
   agent must (a) have issued a REAL `seon.db/query`/`transact!` form (claims
   like `;;=> 42` are already neutralized by `ctx/neutralize-result-claims`),
   AND (b) the outbound answer must match the true value. Record
   `{:pass ok? :forms forms :outbound outbound :halt halt}`.

7. **Tune.** If fail, read `forms` to see the exact defect, refine `db.cljs`
   source per defect, then repeat steps 2–6. If pass first-try across a few
   tasks, `seon.db`'s curated context is good — move to the next namespace.

Scratch agents are wiped by the next reset; no manual teardown. To stop one
mid-session: `(await (seon.agent/set-state! {:seon.agent/id aid :seon.agent/state :terminated}))`.

## The seon.db Battery

GROUND-TRUTH NOTE: the live store at synthesis time is a FULLY-SEEDED core
program graph (224 `:seon.fn`, 432 `:seon.schema`, 188 `:seon.test`, 82
`:seon.ns`, plus one live agent session) — NOT the 3-agent fixture an earlier
recon recorded. Therefore the battery below is built on the SEEDED PROGRAM
GRAPH (counts that re-derive deterministically after any `cluster reset` because
the seed is the codebase) plus transaction tasks against registered, currently
near-empty kinds. All counts below were verified live 2026-06-23 against
`@seon.client/!agent-conn`. The exact integers shift only if the indexed
codebase changes; the QUERY SHAPES and the difficulty grading are stable. Verify
the integers with the harness eval at drive time and use those as the scoring
oracle — do not hardcode these numbers into the agent's task.

| id | kind | instruction | expected shape | ground-truth | difficulty |
| --- | --- | --- | --- | --- | --- |
| db-q-count-fns | query | How many functions are indexed in this cluster's program graph? | `(db/query '[:find (count ?e) . :where [?e :seon.fn/sym]])` | 224 (scalar) | basic |
| db-q-count-schemas | query | How many registered schemas are in the store? | `(db/query '[:find (count ?e) . :where [?e :seon.schema/key]])` | 435 (live 2026-06-23; ONE `:seon.schema/key` row per registered schema, equals `(count (seon.schema/registered-schemas))`; shifts with the codebase — verify at drive time) | basic |
| db-q-list-ns-names | query | List the names of every namespace in the store. | `(db/query '[:find [?n ...] :where [?e :seon.ns/name ?n]])` | 82 ns-name keywords (collection form) | basic |
| db-q-pull-fn | query | Pull the indexed entry for the function `seon.db/query` — show its arglists and docstring. | `(db/pull '[:seon.fn/sym :seon.fn/arglists :seon.fn/doc] [:seon.fn/sym "seon.db/query"])` (STRING lookup value — `:seon.fn/sym` is a :string; the quoted-symbol form `'seon.db/query` THROWS "Cannot compare String to Symbol") | map with `:seon.fn/sym "seon.db/query"`, arglists string `"([& args])"`, doc starting "Run a Datalog query." | basic |
| db-q-find-by-attr | query | Which namespaces have a recorded `:seon.ns/source` (real file text)? Return their names. | `(db/query '[:find ?n :where [?e :seon.ns/name ?n] [?e :seon.ns/source _]])` | 69 namespaces (presence-of-attr filter) | intermediate |
| db-q-predicate-doc | query | Find functions whose docstring is longer than 400 characters; return the symbol. | `(db/query '[:find ?s :where [?e :seon.fn/sym ?s] [?e :seon.fn/doc ?d] [(count ?d) ?l] [(> ?l 400)]])` | a small set; exercises a binding-expr + predicate | intermediate |
| db-q-ref-join | query | How many functions live in the `seon.db` namespace? (`:seon.fn/ns` is a ref — join through the ns entity.) | `(db/query '[:find (count ?e) . :where [?e :seon.fn/ns ?n] [?n :seon.ns/name :seon.db]])` | 15 | intermediate |
| db-q-distinct-via-set | query | What distinct namespaces own at least one test? | `(db/query '[:find ?nm :where [?e :seon.test/ns ?n] [?n :seon.ns/name ?nm]])` | set of ns-name keywords (datalog `:find` dedupes) | intermediate |
| db-q-lookup-ref-entity | query | Using a lookup ref on the identity attribute, get the arglists of `seon.db/transact!` without first finding its numeric eid. | `(db/pull '[:seon.fn/arglists] [:seon.fn/sym "seon.db/transact!"])` or `(db/entity {:seon.db/ref [:seon.fn/sym "seon.db/transact!"]})` (STRING lookup value, never `'seon.db/transact!`) | arglists string `"([& call-args])"` via lookup-ref, not a raw eid | intermediate |
| db-q-aggregate-group | query | Count how many tests each namespace owns; return the top namespace by test count. | `(db/query '[:find ?nm (count ?e) :where [?e :seon.test/ns ?n] [?n :seon.ns/name ?nm]])` then max | `[:seon.db-test 39]` is the top group | advanced |
| db-q-multi-where | query | Find functions that have BOTH a docstring AND a `:seon.fn/spec`; return the count. | `(db/query '[:find (count ?e) . :where [?e :seon.fn/sym _] [?e :seon.fn/doc _] [?e :seon.fn/spec _]])` | 222 (2 of 224 lack a spec) | advanced |
| db-q-provenance | query | The store records eval provenance: how many `:seon.eval` rows exist? | `(db/query '[:find (count ?e) . :where [?e :seon.eval/id]])` | 4 (from the live session — shifts with activity; verify at drive time) | advanced |
| db-tx-add-todo | transaction | Store a new todo titled "Audit db battery" with status `:open` and a fresh id. | `(let [tid (db/new-id!)] (db/transact! {:seon.db/tx-data [{:seon.agent.todo/id tid :seon.agent.todo/title "Audit db battery" :seon.agent.todo/status :open}]}))` (`:seon.agent.todo/id` is a 14-char `:seon.db/id` — a custom string like `"todo-001"` FAILS Malli; mint with `new-id!`) | `:seon.db/ok? true`; read-back pull returns title + `:open`. 0 todo rows now — clean target. `:seon.agent.todo/status` is `[:enum :open :done]`. | basic |
| db-tx-upsert-by-identity | transaction | Re-store the SAME todo (its `new-id!` id) with status `:done`; do NOT create a second entity. | `(db/transact! {:seon.db/tx-data [{:seon.agent.todo/id <tid> :seon.agent.todo/status :done}]})` (reuse the id minted above) | todo count stays 1 (identity upsert); read-back status `:done`; title untouched (omitted = unchanged) | intermediate |
| db-tx-retract-attr | transaction | Clear the title on that todo, leaving the rest intact. | `(db/transact! {:seon.db/tx-data [[:db/retract [:seon.agent.todo/id <tid>] :seon.agent.todo/title]]})` (reuse the id) | `:seon.db/ok? true`; SCORE BY READ-BACK: `(:seon.agent.todo/title (db/entity {:seon.db/ref [:seon.agent.todo/id <tid>]}))` is nil while status survives; entity still resolves. NOTE: the envelope's `:seon.db/retracted` reads 0 here (the wire success report's `:tx-data` summary doesn't carry the user retraction datom — known quirk; do NOT score on `:retracted`). Demonstrates `[:db/retract eid attr]`, not omission. | intermediate |
| db-tx-multi-entity-kb | transaction | Store two `:my.kb.system` facts in ONE transaction, each with id/text/at. | `(db/transact! {:seon.db/tx-data [{:my.kb.system/id (db/new-id!) :my.kb.system/text "..." :my.kb.system/at (js/Date.)} {:my.kb.system/id (db/new-id!) :my.kb.system/text "..." :my.kb.system/at (js/Date.)}]})` (`:my.kb.system/id` is an unconstrained `:string` so any value works, but `new-id!` is the uniform habit) | `:seon.db/ok? true`; read-back count of `:my.kb.system/id` grows by 2. `my.kb.system/{id,text,at}` registered. | advanced |

Coverage: count/aggregate scalar, collection + relation + scalar `:find`
shapes, pull (wildcard subset + lookup-ref eid), presence-of-attr filter,
predicate + binding-expr, REF join (the `:seon.fn/ns` trap), distinct-via-set,
grouped aggregate, multi-where on one entity; transactions span add /
upsert-by-identity / retract-attr / multi-entity insert against real schemas.
A standalone ADD-WITH-REF task is omitted because the registered ref attrs
(`:seon.agent.todo/owner`, `:seon.agent.message/from`/`to`) need a target
entity to point at; fold a ref into db-tx-add-todo
(`{:seon.agent.todo/owner [:seon.agent/id aid]}`) if a ref task is wanted.

## The seon.db Refinement Target

`db.cljs` (999 lines, 15 public fns) already has genuinely excellent docstrings
— `transact!` and `store-inventory` carry full worked examples, both call
shapes, and the error-as-value envelope contract. The problem is they DO NOT
REACH the agent (signature manifest, truncated at 80 chars). So the refinement
is two-part: (A) make the existing teaching render, (B) fill the gaps that even
the full docstrings miss. Keep everything show-don't-tell — examples in code,
not prose walls. NO API changes.

### A. Make the real source render cleanly

1. **Whitelist + reset** (the lever) — covered above. This flips `seon.db`
   from manifest to full source, carrying every docstring example verbatim.

2. **Suppress the double-render for full-source namespaces.** The `:full` path
   emits the WHOLE verbatim file source AND then repeats each fn/schema as a
   truncated `[fn ...] :spec [...…]` member-row label (proven in the
   `seon.agent.todo` block of the rendered prompt: every fn and schema appears
   twice). For `db.cljs` that doubles cost and pastes weak 80-char spec labels
   next to the real readable forms. In `render-one-ns-ai`'s `:full` branch
   (`src/seon/ctx.cljs:1216-1224`), when the ns is full-source (real file text
   present), OMIT the fn/schema member-row blocks and render only the verbatim
   source. This is the change that makes the teaching render clean — ONE body
   of real code, no label echo.

3. **Demote agent-irrelevant publics to `defn-`** so the full source is signal,
   not noise (do this BEFORE whitelisting):
   - `entity-lazy` (`db.cljs:697`) — its own docstring says "Not part of the
     agent-taught surface" yet it renders publicly today.
   - `bootstrap-row-ids` (`db.cljs:877`), `core-kinds` (`db.cljs:892`) —
     `store-inventory`'s internal provenance derivations.
   - Collapse or mark the `listen-sync!`/`listen-async!` aliases so three
     near-identical entries don't bloat the block; the agent uses
     `seon.trigger`, not raw `listen!`.
   - Consider moving the ALS-scope plumbing (`with-agent`, `with-tx-context`,
     `current-tx-context`) to `seon.db.internal` — the loop sets these; the
     agent should not call them, and there is no per-fn hide mechanism today.

4. **Spec the unspecced publics.** `new-id!` (`db.cljs:222`) and
   `current-agent-id` (`db.cljs:272`) render `:unspecced` today — second-class
   to an agent being taught "every public fn is specced." Add
   `[:=> [:cat] :seon.db/id]` and `[:=> [:cat] [:maybe :string]]`.

### B. Fill the teaching gaps (worked examples IN the docstrings)

The vision is source-as-manual: each example is ONE line of real code + a
one-line `;;=>` comment showing the return shape, embedded in the relevant fn's
docstring so it renders WITH the fn. Use POD call shapes everywhere — db
OMITTED (auto-injected from `*conn*`), bare or `:seon.db/tx-data` tx vector,
`pull`/`entity` (NEVER `pull-by-name`, which does not exist).

Add to `query`'s docstring (`db.cljs:366`), the four `:find` shapes plus the
missing operators:

- relation `(db/query '[:find ?t :where [?e :my.kb.doc/title ?t]])` `;=> #{["Intro"]}`
- scalar `(db/query '[:find ?t . :where [?e :my.kb.doc/title ?t]])` `;=> "Intro"`
- collection `(db/query '[:find [?t ...] :where [?e :my.kb.doc/title ?t]])` `;=> ["Intro" "v2"]`
- tuple `(db/query '[:find [?t ?s] :where [?e :my.kb.doc/title ?t] [?e :my.kb.doc/score ?s]])`
- `:in` param (db omitted, input AFTER query) `(db/query '[:find ?t :in $ ?id :where [?e :my.kb.doc/id ?id] [?e :my.kb.doc/title ?t]] "d1")`
- multi-clause REF join `(db/query '[:find ?addr :where [?m :seon.agent.message/from ?p] [?p :seon.user/id ?addr]])`
- predicate `(db/query '[:find ?t :in $ ?min :where [?e :my.kb.doc/score ?s] [(>= ?s ?min)] [?e :my.kb.doc/title ?t]] 30)`
- binding-expr `(db/query '[:find ?t ?d :where [?e :my.kb.doc/title ?t] [?e :my.kb.doc/score ?s] [(* ?s 2) ?d]])`
- aggregate `(db/query '[:find (count ?m) . :where [?m :seon.agent.message/from _]])` `;=> 7`
- not/or `(db/query '[:find ?t :where [?e :my.kb.doc/title ?t] (not [?e :my.kb.doc/status :archived])])`
- pull-in-find + reverse-ref `(db/query '[:find (pull ?e [:my.kb.doc/title {:my.kb.doc/author [:my.person/id]}]) :where [?e :my.kb.doc/title _]])`
- the **ref-attr trap** — name it explicitly: a ref-typed attr (e.g.
  `:seon.fn/ns`) does NOT match by keyword; join through the target's identity:
  `[?e :seon.fn/ns ?n] [?n :seon.ns/name :seon.db]` (verified: the keyword form
  throws "Nothing found for entity id :seon.db").
- the **falsification note**: `;; empty #{} usually means a misspelled attr —
  the guard throws on an UNregistered attr; a registered-but-dataless attr
  honestly returns #{}; copy the keyword EXACTLY from store-inventory`.

Add to `transact!`'s docstring (`db.cljs:303`):

- add `(db/transact! {:seon.db/tx-data [{:my.kb.doc/id "d1" :my.kb.doc/title "Intro"}]})` `;=> {:seon.db/ok? true ...}`
- upsert-by-identity `(db/transact! {:seon.db/tx-data [{:my.kb.doc/id "d1" :my.kb.doc/title "Intro v2"}]})` — omitted keys untouched
- retract one `[:db/retract [:my.kb.doc/id "d1"] :my.kb.doc/title]`; delete `[:db.fn/retractEntity [:my.kb.doc/id "d1"]]`
- intra-tx tempid link + reified-tx `(db/transact! {:seon.db/tx-data [{:db/id "p:alice" :my.person/id "alice"} {:my.kb.doc/id "d1" :my.kb.doc/author "p:alice"} {:db/id "datomic.tx" :my.ingest/source "import"}]})` — same-tx new entities link by TEMPID string; lookup-refs do NOT resolve against not-yet-committed entities
- the **falsification note**: `;; an eval can succeed yet :seon.db/ok? false —
  the write did NOT happen; read the envelope`.

Add to `pull`/`entity` docstrings: the lookup-ref value-type contract
(`schema.cljc:107-115`, currently invisible) — a lookup-ref is
`[identity-attr value]` where value is a string/uuid/keyword/int, and a ref
attr also accepts an eid (pos-int), a tempid (neg-int or string), or a
lookup-ref.

Add to `store-inventory`/`installed-schema`: signpost the REPL-introspection
pair. `store-inventory` omits registered-but-dataless attrs; pair it with
`(keys (seon.db/installed-schema db))` which lists every attr the conn knows.
`installed-schema` is the agent's "what attrs exist on this db, exactly?" tool
and is buried today.

### Consolidate the cheat sheet (avoid the two-code-paths smell)

A hand-maintained "COMMON DB OPS" cheat sheet in the `<system>` prologue
(`src/seon/ctx.cljs`) currently carries the teaching that should live in the
docstrings — it duplicates content already in the `seon.db` docstrings verbatim.
Once `seon.db` renders full, the cheat sheet is the banned two-code-paths
pattern. Move the `:find`-shape teaching INTO `query`'s docstring (single source
of truth, code-as-data), then keep ONLY the cross-cutting "two laws"
(register-before-transact, two-segment namespaces) in the prologue.

## Success Bar

The namespace passes when a fresh DeepSeek scratch agent, given ONLY the curated
`seon.db` full-source context (no extra coaching, no harness leakage), completes
**at least 13 of the 15 battery tasks first-try** — meaning, for each task, the
agent issues a REAL `seon.db` form (not a fabricated `;;=>` claim) that evals
`:seon.eval/ok? true`, AND the human-facing outbound answer matches the
harness-computed ground truth. Sub-bars that gate "first-try":

- ALL basic + intermediate query tasks (db-q-count-fns through
  db-q-lookup-ref-entity, and the three basic transactions): 100% first-try.
- The ref-join task (db-q-ref-join) MUST pass without the agent first trying the
  keyword-equality form and erroring — this directly validates that the
  ref-attr-trap example landed.
- No task may pass via a fabricated result claim; the score requires a real
  eval'd form.

Run an A/B: baseline-A (signatures-only) vs the full-source curation, same
battery, same temperature 0. The lift (full minus baseline pass-count) is the
quantitative proof the curation works. Target lift: at least +6 tasks.

## Risks and Serialization Notes

- **Re-index dependency.** The whitelist edit does nothing until cljs-watch
  rebuilds AND `bin/seon cluster reset default` re-runs the indexer. Forgetting
  the reset yields an empty full block (the `(no recorded source)` guard) — a
  silent "the lever doesn't work" trap. The reset also REPLACES `!agent-conn`,
  so re-bind `*conn*` after every reset.
- **Conn footgun.** The MCP default `:client` conn is not the agent conn; every
  harness eval must `(set! seon.db/*conn* @seon.client/!agent-conn)`. Single
  most likely cause of "queries return nothing" in the harness.
- **One live cluster — serialize, don't race.** A test agent is editing
  concurrently. Do NOT run `cluster reset` while another agent is mid-work, and
  NEVER fire overlapping `cljs.test/run-tests` in the live pod (wedges the
  shared async continuation). Coordinate boot/reset/drive units; use a fresh
  scratch agent id per drive so the driver never collides with the live roster.
- **Stale ground-truth integers.** The battery's exact counts (224/432/188/82,
  15 fns in seon.db, 39 tests in seon.db-test, 4 evals) reflect the live store
  at synthesis time. The seeded counts re-derive after a reset (the seed is the
  codebase), but they DO shift if the indexed codebase changes; the eval count
  shifts with any session activity. Compute ground truth in the harness eval at
  drive time and score against THAT — never hardcode the integers into the
  agent's task or the scorer.
- **Token budget.** `db.cljs` is 999 lines / ~43KB; rendered full it is a large
  block in a ~97k context. The noise-demotion (entity-lazy, provenance helpers,
  listen aliases, ALS plumbing, `malli->datahike-schema`, etc.) plus suppressing
  the member-row double-render must be aggressive enough that the rendered block
  is mostly the ~6 agent-facing fns. Measure the prompt char delta
  (`assemble-context` returns `:seon.render/token-estimate`) before/after to
  confirm the cache-prefix benefit holds.
- **Scope of the whitelist.** The lever is GLOBAL — whitelisting `seon.db`
  shows it full to ALL agents permanently. If the intent is a per-experiment
  curation, that would need a context-scoped whitelist, which is a NEW
  mechanism contrary to "no new APIs". Confirm `seon.db` is worth permanent
  full-source for every agent (it almost certainly is — it is the API every
  agent uses).
- **Recursive rules unverified under self-hosted CLJS.** The `some-of` macro
  used by `expand-rule` is JVM-only (`#?(:clj defmacro)`); recursive datalog
  rules may break in the bootstrapped cljs.js pod. Keep rules OUT of the first
  battery; probe a self-referential rule READ-ONLY before teaching recursion.
  Everything else (P1–P12 shapes) is cljc-confirmed.
- **`setup-agent-ns!` arity.** `nodeaf_probe.cljs` calls it with 3 args
  `(cs (ctx/home-ns aid) aid)`. Verify the signature against `src/seon/eval.cljs`
  before the first drive; a drift throws at create.
