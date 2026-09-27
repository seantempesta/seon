---
type: spec
status: revision 2, REPL-proven prototype; no production source changed
created: 2026-09-27
revised: 2026-09-27 (owner answers Q1–Q5 on e773f68cf; Astra review bf1471b38 R1–R9, all accepted by root)
lane: one-path-spec-opus
head: dd5dcaff7 (every cited file:line is HEAD's blob; the working tree holds other lanes' edits to turn.clj, db.clj, cluster.clj, schema/datahike.clj, which this lane did not read as evidence)
jvm: default (restarted by root three times during the lane: pids 89589 → 99936 → 20628); probes JVM-mode through `seon.db/with`; SCI-patch proofs in a scratch 512 MB JVM
prototype: tmp/one-path/pipeline.clj (appendix B), forgive.clj (C), entrants.clj (D), sci-observer.diff (E), manifest.edn + manifest-table.md (F)
evidence: tmp/one-path/{proofs-results,observe-results,forgive-results,results-v2,origins,h40-expansions}.edn, corpus-table-v2.md, deletion-ranges.tsv
---

# The one REPL path: spec and instructions

## 0. Answer

**What exists.** Today, text goes through `cluster.reply/sources`, then `turn/evaluate-sources`, then `sci.eval/evaluate`.
Each stage reads again, admits per form, gates per declaration and analyzes again in the writer. MCP then prints the
result a second time.

**What replaces it.** One orchestrator, `seon.sci.eval/evaluate-batch`. Its request carries the stages as values:
reader, evaluator, observer, derive, settle. Every entrant is that function with different stage values.

**Speed.** Measured warm on default, as prototype microbenchmarks with candidate writes through `seon.db/with`:

| Batch | One path | Today |
|---|---|---|
| One contracted `defn` | 38–39 ms (derive 7–8, speculative 1, settle 24–25) | 512–948 ms |
| 40 chained `defn`s, each reading the previous one's facts | 623 ms (15.6 ms per defn) | — |
| h36, 22 `register!` | 540–600 ms | 50.8–54.7 s |
| h16, register then contracted use | 395 ms (one schema flush 54 ms) | — |
| Acquisition through the same function | 42 µs per row | 27 µs |

**Corpus.**

- 94 items: 0 candidate writes refused, 0 diagnostics.
- p50 54 ms, p90 101 ms, max 571 ms. The 58 items that write rows have p50 60 ms.
- 74 items are value-identical to plain SCI; the rest differ by design (appendix A).
- The producer observer (patched SCI, scratch JVM) joins **101 of 101** binds exactly: 46 by full span, 50 generated
  (joined at their call) and 5 members.

**Owner answers and review findings.** All are proven at the REPL (§1). What is not proven is listed there.

**Instructions.** 17 steps, each under an hour and each loadable on its own. Additive steps come first, then switch
steps, then one scripted deletion whose lint delta is the 102-reference manifest.

- **Net production (src and script): −1,609.** About 78% of the removed lines are measured: a scripted deletion over
  HEAD removes 1,976 lines, and the prototype adds 690.
- **Resources:** −14.
- **Tests:** about −373.
- **SCI fork:** +38/−12.
- **Q5 extension entities**, if taken: +60.

§7.5 splits the totals into measured, range-counted and estimated rows.

**Owner questions.** One is new (§10). A refused commit can either keep the batch's live Vars as private, or revert
them.

## 1. Every answer, and its REPL evidence

### Owner answers

**Q1 (A): kondo reads SCI's recorded expansion.**

- The SCI fork's `:macro-expansion-observer` records `[call expansion]` in `analyze-call`'s macro branch
  (appendix E).
- Only a call that is the origin of a generated bind is supplied to kondo. It goes in as a string `analyze-call`
  hook that returns SCI's own expansion, located by the call's position (`probe_kondo.clj` `expansion-hook-code`),
  under `:__dangerously-allow-string-hooks__`.
- Proven on h40 (`h40-expansions.edn`): `h40-x`/`h40-y` and `h40-p`/`h40-q` are defined at their call positions with
  `:defined-by clojure.core/defn`, exactly SCI's origin.
- The macro never runs a second time.

**Q2: forgiving input stage in front of the one reader.** This is `seon.sci.forgive`, one reader cursor per text
version (§3.2).

- Run on 52 real inputs: 7 run4 replies, 18 stored evaluations, 18 from `reply_test` and 9 by hand.
  - 39 are identical to today's `planned-sources`. The other 13 are improvements or designed outcomes.
  - 0 hangs. 69 ms in total; the slowest was 35 ms, on 12 KB.
- Live in the pipeline (`proofs-results.edn :q2-live`), one reply produced 4 repairs:
  - `Let me require it first.` became a comment;
  - the `user=>` marker was stripped;
  - an unbalanced `defn` was repaired at offset 89;
  - `That should do it.` became a comment.

  After those repairs, `::str/k` resolved through the alias the reply had just required, as
  `:clojure.string/k`. Values: `nil`, `:clojure.string/k`, `3`, `#'user/bad`.
- Ambiguous input is returned unread, as data: `{:unread {:from 69 :reason "…Unmatched delimiter: )"}}`, with the
  earlier values kept.

**Q3: threaded db-after and one transaction.**

- Each form evaluates with `seon.db/*read-database*` bound to the working value.
- After a defining form, its rows go through `speculative-row-tx`, the pure part of `row-tx`, and are applied by
  `speculative-with`: the writer's `prepared-write` without the final-report validator, `d/with`, and the projection
  state carried.
- The commit is one validated `db/with` over the basis.
- Proven:
  - `(doc f)` after `defn f` in the same batch finds it;
  - after a redefinition it shows the new doc;
  - a function whose contract names a key registered earlier in the batch arms and settles;
  - a `pull` of one's own registration answers it.
- Cost per defining form: speculative apply 1 ms, derive 7–8 ms.
- Schema rows are the exception: they are flushed once, before the first form whose operator is not
  `register!`/`unregister!`. Threading them per form re-derived the content projection at 80–140 ms per form.

**Q4: a storable registered key is an attribute.**

- An agent-registered key the bridge can store is installed with no entity-map listing.
- An unstorable one, such as a function schema, is not installed. The reply carries
  `{:seon.schema/key k :seon.schema/installed? false :seon.error/message <bridge's reason>}`.
- Proven (`:q4-register`): `:one.path.q4/n` installed; `:one.path.q4/f` noted with "A stored attribute needs a
  storable Datahike value type…".
- A bare new entity holding only the new key is still refused as `unowned-entity`, by the writer's identity law and
  not the attribute rule. The same key on an owned entity applies. The reply shows both outcomes.
- HEAD has no "stored? false" report to delete. That was revision 1's prototype note, and it is gone.

**Q5: `def`/`defonce` are first-class entities with their edges; extensions are priced separately.**

- Proven (`:q5-defs`): rows for `base-v`, `dep-v` and `once-v` carry `:seon.fn/calls` edges (`dep-v` calls
  `user/base-v`). They are acquired in edge order into a fresh context: `[42 {:k 41}]`.
- `defmulti`/`defprotocol` Vars are the same kind of row.
- The smallest correct extension entity for `defmethod`/`extend-*` is keyed by (target, dispatch value or type):
  - kondo finds it (`:defmethod` usages with `:dispatch-val-str`; `:protocol-impls`);
  - the runtime confirms it (`get-method`), so a method in a false branch is excluded. Proven: `:sq` true, `:never`
    false;
  - its source is the extension form's span, and it is replayed after its target at acquisition.
- Specified and priced in S16. Acquisition replay and `remove-method` are not proven.

**Contract coverage** (contract-coverage spec, 2014f98b6).

- The REPL never refuses a missing contract.
- One new uncontracted function with none before it gives
  `{:seon.eval/contract-note "user/nc1 has no :malli/schema; specify it before proposing a merge."}`.
- More than one, or any existing agent function without a spec on the branch, gives
  `{:seon.eval/contract-warning [user/nc2 user/nc3]}`.
- The warning is derived from branch facts by one query. It is never a stored counter.
- The edit hook and merge refuse; that is not this stage.

### Review findings

**R1: an observer that sees every write.** It is a fork seam at the producer, not an env watch (appendix E).

- `sci.lang/*write-observer*` is dynamic. It is called in `Var.bindRoot` after the root is written, and in
  `sci-intern`'s new-Var path. `ns-unmap` and `remove-ns` emit their own events.
- `eval-def` binds `*def-origin*` around its `bindRoot`:
  - an authored origin is the def form's full reader span;
  - a generated one is the innermost macro call's location, marked `:generated`.
- `eval-def` also writes metadata before the root, so the observer sees the final metadata.

Proven in a scratch JVM (`observe-results.edn`):

| Case | Events |
|---|---|
| `(def f 2)` then `(intern 'user 'f 3)` then `(alter-var-root #'f inc)`, on a **retained** Var | 3 binds, roots 2/3/4. The authored span is on the def; the others have `:origin nil`. |
| `intern` of a new Var | 1 bind |
| `defonce` twice, then `declare` | 1 bind, from the first `defonce` only |
| Nested `(eval '(def h 1))` | 1 bind |
| `alter-var-root` in a fork, on a Var inherited from the base | 1 bind, in the fork; the base keeps 1 |
| Nested scopes: outer defines a, inner defines b, then outer defines c | outer [a c], inner [b]. Dynamic binding makes the innermost scope the owner. |
| Unmap and `remove-ns` | `:unmap` and `:remove-ns` events |

- Arming's own root replacement runs with `*write-observer*` bound to `nil`, so it is not a second definition.
- Not proven: `future` (plain `sci/init` does not provide it) and a same-context two-thread overlap.
- Stated rule: one batch owns a context at a time, which is the agent's branch custody. Overlap is detected by the
  loaded-identity comparison, never locked.

**R2: an exact join.**

- An authored bind joins the kondo definition whose full span equals `*def-origin*`'s span.
- A generated bind joins at its call:
  - kondo's usage at the call position with `:defined-by` equal to the call operator; or
  - a definition inside that call whose `:defined-by` is the operator.
- A member (the record constructor helper) carries `{:sci.impl/member-of '->R}`, set by its producer, `records.cljc`.
- `defprotocol`'s methods are now one final `def` of P, not an `alter-var-root`.
- **Rebound** is a final write with no origin, not made inside a declaration: `intern` or `alter-var-root` after a
  def. The value is kept and the Var is private, with reason `:rebound`.
- The reviewer's `review-rebound` case now answers:
  - the bind is observed (`:rebound-defn`: authored bind, then `:origin nil` bind);
  - no row is persisted;
  - the arm stage runs on every bind whose Var carries `:malli/schema`, so the interned root is armed too. That is the
    same call as at the def. Its regression is in S7; it was not run live with arming.
- Corpus: 101/101 exact (§0).
- The join enum now includes `:ambiguous`, which fixes the §2 mismatch the review found.

**R3: totality at every boundary.**

- Each entry is published into the retained vector before any bookkeeping.
- Every later step is `guarded`, which appends a diagnostic and keeps the prefix.
- Printing falls back per entry.
- Proven:

  | Case | Result |
  |---|---|
  | `ns-name` stage throws on every form | values `["42" "43"]` plus 5 diagnostics |
  | a lazy value whose print throws | `#<unprintable clojure.lang.LazySeq>` then `42`, reply present |
  | the reviewer's `(def ns-name …)` shadow | `["42" "#'user/ns-name" "43"]`, committed |
  | malformed contract | the def answers its Var; calls refuse by name; the next form runs |

**R4: effects fold by identity.**

- New-then-removed makes no row (`:r4-new-then-removed`: tx-count 0, program `{user/rr :removed}`).
- Removing a stored identity retracts it (`:r4-remove-existing`: stored before, absent after).
- `my.program/ns-unmap!` now converts in the same cut (S12).
- h13 is replaced by a successful unmap in the driver (S17).

**R5: namespace facts settle with their rows.**

- `namespace-row` is `eval/namespace-context-row` (HEAD :1266, kept) over SCI's resolver bindings. It is written in
  the same transaction as the rows.
- The reviewer's exact `review.alias` batch commits: `:r5-alias`, values `nil`, `#'review.alias/upper`, `"OK"`,
  tx-count 3.
- Closure capture: a def span whose re-analysis reports a local of its enclosing form as unresolved is not admitted.
  The Var stays private with reason `:captures-locals` (S6). This is specified, not yet run.

**R6: a loadable cut, with every caller listed.**

- The deletion is a script (`delete_forms.bb` over `deletions.edn`) run on HEAD dd5dcaff7: 88 defns, 1,940 lines.
- kondo's usages of the deleted Vars at HEAD form the manifest: 102 references in 20 files (appendix F). Each is
  assigned to a step.
- Keyword and contract references kondo cannot see are listed by hand in §7.4:
  - `:seon.sci.eval/bindings`;
  - `:seon.sci.eval/restore`;
  - the task `:must-read` entries;
  - the source rereads.
- `program_change_test` is kept and revised, not deleted.
- The complete candidate diff was not produced. The deletion half is real and measured; the addition half is the
  prototype's measured size. §7.5 labels each row.

**R7: one function for every entrant.**

- `pipeline/run` is the only fold. `jvm-batch`, `thunk-batch` and `render-batch` are stage values over it
  (appendix D).
- The test harness, acquisition, refresh and file proof are converted in S12–S14.
- `proven-file` is an analysis consumer of `fn/batch-rows`, not an evaluation entrant.
- `render/value.clj:1401` reads a stored print artifact. It is not a REPL reader.

**R8: read-only render.**

- A fork plus database-value custody: the environment's connection is removed at entry.
- `transact!` refuses. `:r8-render` value 2 is the floor's print of `seon.db/missing-connection`.
- A `def` is observed and discarded with a diagnostic; `(resolve 'user/leaked)` in the viewer is `nil`.
- A shared atom inherited from the viewer is mutated (`:viewer-shared 1`). This is stated, not sandboxed: renders are
  cooperative code (CLAUDE.md, no security isolation).

**R9: labels.** Every number in this spec is a prototype microbenchmark or a candidate-write check. The committed
driver with semantic assertions is S17.

## 2. The one value

This is an in-memory open map, not an entity. Its schemas go in `resources/seon/schemas/seon.eval.edn`. None collides
with a registry key. Live objects (the SCI Var, the value) exist only during the call.

```clojure
#:seon.eval
{:request
 [:map {:description "One batch: text, where it starts, and the stages as values. An entrant is a choice of stage values."}
  [:seon.eval/text :string]
  [:seon.eval/start-ns :symbol]
  [:seon.eval/mode [:enum :reply :code]]
  [:seon.db/database-value :seon.db/database-value]
  [:seon.sci.eval/ctx {:optional true} :seon.sci.eval/ctx]
  [:seon.eval/read-next [:=> [:cat :map :seon.eval/read-state] :seon.eval/read-step]]
  [:seon.eval/evaluate [:=> [:cat :map :any] :any]]
  [:seon.eval/ns-name [:=> [:cat :map] :symbol]]
  [:seon.eval/observe? :boolean]
  [:seon.eval/derive? :boolean]
  [:seon.eval/settle? :boolean]]

 :read-step
 [:or
  [:map [:seon.eval/form :any] [:seon.eval/start nat-int?] [:seon.eval/end nat-int?]
        [:seon.eval/refused {:optional true} [:enum :fabricated-response]]]
  [:map [:seon.eval/eof [:= true]]]
  [:map [:seon.eval/unread-from nat-int?] [:seon.eval/reason :any]]]

 :repair
 [:map [:seon.eval/repair-kind [:enum :prose :prompt-marker :delimiters]]
       [:seon.eval/prose {:optional true} :string]
       [:seon.eval/at {:optional true} nat-int?]]

 :origin
 [:or
  [:map [:line pos-int?] [:column pos-int?] [:end-line pos-int?] [:end-column pos-int?] [:authored [:= true]]]
  [:map [:line pos-int?] [:column pos-int?] [:generated [:= true]]]]

 :effect
 [:multi {:dispatch :op}
  [:bind [:map [:op [:= :bind]] [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.eval/sym :qualified-symbol]
          [:seon.eval/origin {:optional true} [:maybe :seon.eval/origin]]
          [:seon.eval/member-of {:optional true} :symbol]
          [:seon.eval/arm-error {:optional true} :seon.error/base]]]
  [:unmap [:map [:op [:= :unmap]] [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
           [:seon.eval/sym :qualified-symbol]]]
  [:remove-ns [:map [:op [:= :remove-ns]] [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
               [:seon.ns/name :seon.ns/name]]]]

 :join
 [:map [:seon.eval/sym :qualified-symbol]
  [:seon.eval/join-kind [:enum :matched :member :rebound :unanalyzed]]
  [:seon.eval/reason {:optional true}
   [:enum :runtime-origin :no-definition-at-origin :ambiguous :captures-locals]]]

 :entry
 [:map {:description "One top-level form: its exact (repaired) source, span, namespaces, and its value or error. Published before any bookkeeping."}
  [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
  [:seon.cluster.eval/source :seon.cluster.eval/source]
  [:seon.eval/line pos-int?] [:seon.eval/column pos-int?]
  [:seon.eval/ns-before :symbol]
  [:seon.eval/ns-after {:optional true} :symbol]
  [:seon.sci.admit/value {:optional true} :seon.schema/value]
  [:seon.cluster.eval/error {:optional true} :seon.cluster.eval/error]
  [:seon.eval/shown {:optional true} :seon.eval/shown]
  [:seon.eval/duration-ms :seon.eval/duration-ms]]

 :schema-note
 [:map [:seon.schema/key :qualified-keyword] [:seon.schema/installed? [:= false]]
       [:seon.error/message :string]]

 :batch
 [:map {:description "What the one function answers. Absent keys are stages not run."}
  [:seon.eval/entries [:vector :seon.eval/entry]]
  [:seon.eval/effects [:vector :seon.eval/effect]]
  [:seon.eval/repairs [:vector :seon.eval/repair]]
  [:seon.eval/unread {:optional true} [:map [:seon.eval/unread-from nat-int?] [:seon.eval/reason :any]]]
  [:seon.eval/text :string]                       ; the repaired text: what is stored and rendered
  [:seon.program/rows {:optional true} [:vector :seon.program/row]]
  [:seon.eval/private {:optional true} [:vector :seon.eval/join]]
  [:seon.eval/schema-notes {:optional true} [:vector :seon.eval/schema-note]]
  [:seon.eval/contract-note {:optional true} :string]
  [:seon.eval/contract-warning {:optional true} [:vector :qualified-symbol]]
  [:seon.eval/diagnostics [:vector :seon.error/base]]
  [:seon.eval/commit {:optional true}
   [:or [:map [:seon.db/basis-t :seon.db/basis-t] [:seon.eval/durable? :boolean]]
        [:map [:seon.eval/refusal :seon.error/base]]]]]}
```

**One new stored fact:** `:seon.cluster.eval/binds`, a cardinality-many symbol. It holds the Vars one evaluation
bound. Its consumers are:

- `turn/read-only-evaluation?`, which replaces its reread through `declaration-row` (HEAD turn.clj:2098);
- the reply's private list.

## 3. The stages

### 3.1 The fold (`evaluate-batch`, prototype `pipeline/run`)

For each top-level form:

1. **Read.** One form at the live cursor through `:read-next`. For SCI that is `forgive/next-form` over
   `sci/parse-next` with `seon.sci.reader/parse-options`, under SCI's live resolver.
2. **Flush.** If schema keys are pending and this form's operator Var is not `register!`/`unregister!`, the schema rows
   are flushed into the working value in one apply.
3. **Evaluate once**, with `db/*read-database*` bound to the working value. The observer is bound around evaluation.
   Each `:bind` is armed at the def under the current candidate projection (arming runs with the observer bound to
   `nil`). An arming failure installs a root that refuses by name, plus `:arm-error`.
4. **Publish** the entry.
5. **Bookkeeping, guarded.** `ns-after`; the form's effects; for a defining form, derive (kondo over the form with
   every observed Var declared, the join, the rows, re-analysis if kondo's namespace differs); fold into the program
   by identity; speculative apply.

After the loop:

- the contract note or warning (one query);
- `program-tx` against the basis: the schema part, namespace rows, final rows and removals;
- one validated `db/with` (production: `settle-batch!`, durable);
- the reply, printed per entry with a fallback.

### 3.2 The forgiving input stage (`seon.sci.forgive`, prototype `forgive.clj`)

It reads one form at the cursor. Only when that read fails does it repair the text at that point and read again from
the same offset. A bare token in a model reply that does not stand alone on its line is treated the same way.

| Failure | Repair |
|---|---|
| Prose line | `; prose`. A trailing all-collection code suffix stays code, on its own line. |
| `user=>`-style prompt marker at the start of a line | Removed |
| Delimiter error | Parinfer indent mode over the rest of the text, accepted only when the rest then reads clean |
| Anything else | Unread from here, as data |

Other rules:

- A map naming `:seon.repl/*` keys is fabricated output. It is returned refused, not evaluated (today's rule).
- A repair makes a new text version: a new line index, and a new cursor advanced to the offset.
- Every read must advance, and there are at most 64 repairs per form. Both loops terminate.
- The repaired text is what is evaluated, stored and rendered.

### 3.3 Stage functions and owners

| Function | Owner | Contract |
|---|---|---|
| `evaluate-batch` | `seon.sci.eval` | `[:=> [:cat :seon.eval/request] :seon.eval/batch]` |
| `next-form`, `version`, `skip-to`, `structure?` | `seon.sci.forgive` | Appendix C docstrings; `next-form` → `:seon.eval/read-step` plus `:v` and `:repairs` |
| `arm!` | `seon.sci.eval` (wraps `install-function-contract!` :1346) | `[:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map :seon.sci.eval/sci-var] [:maybe [:map [:root :any] [:arm-error {:optional true} :string]]]]` |
| `batch-rows`, `join-bind`, `derive-form` | `seon.fn` (generalizes `source-rows` :1533) | `derive-form: [:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :seon.eval/entry [:vector :seon.eval/effect] [:vector :qualified-symbol]] [:vector :seon.eval/join]]` |
| `entity-at`, `speculative-with` | `seon.db` (the only direct `datahike.api` owner) | `[:=> [:cat :seon.db/database-value :seon.db/tx-data :any] :seon.db/database-value]` |
| `speculative-row-tx`, `schema-part`, `program-tx`, `candidate-projection` | `seon.turn` (beside `row-tx` :1286 and `schema-attribute-change-tx` :1103) | tx-data producers |
| `settle-batch!` (kept) | `seon.turn` | settle request carries `:seon.program/rows` |

## 4. Entrants: stage values over the one function

| Entrant | Call site (HEAD) | `:read-next` | `:evaluate` | observe / derive / settle |
|---|---|---|---|---|
| Product turn | `turn/evaluate-sources` :5058 from `resume-turn` :5299 | forgive, `:reply` | SCI per-form (kernel arm, bounds, bindings from `evaluate` :4379) | yes / yes / yes |
| MCP SCI (root, branch) | `mcp.clj` `run-form` :391 → `agent/evaluate-source!` :785 | forgive, `:code` | SCI | yes / yes / yes |
| MCP JVM | `mcp.clj` `jvm-evaluation-form` :376 | `jvm-read-next`: `read+string` after `forgive/skip-to` at the live `*ns*` | host `eval` | no / no / no |
| Test body | `test.clj` `bounded-result` :218 (body call inside) | one synthetic entry | thunk: `runner/run-var!` or `sci.eval/run-test` | no / no / no (evidence is the runner's `record-tx`) |
| Render, preview, context | `eval.clj` `evaluated-source` :4058; `turn.clj` `preview-sources` :5222 callers `system-turn` :2282, `web.clj` `render-source-call` :1425 | forgive, `:code` | SCI on `(sci/fork viewer)` with database-value custody | yes (reported) / no / no |
| Acquisition | `eval.clj` `install-function-from-database!` :1414 (:1448), `install-row!` :1764 (:1921, :1946) | code reader over the row's source | SCI | no / no / no (42 µs per row) |
| `register!` | inside every batch: the always-bound registration delta | — | — | schema part plus flush rule |
| Merge / write-back | `my/program.clj` `prepare-merge!` :526, `accept-merge!` :548 (entities, no evaluation); `cluster/source.clj` `proven-file` :1090 | — | — | `fn/batch-rows` over file bytes only: an analysis consumer, not an evaluation |

### The MCP reply projection

Each entry becomes one event, in both modes:

```clojure
{"tag" "ret"
 "val" <the floor's (or declared renderer's) shown text, ONE JSON string>
 "seon.cluster.eval/ordinal" n
 "seon.turn/id" id                  ; SCI mode with custody
 "ms" <entry wall ms>
 "seon.blob/digest" d               ; only when the floor cut the value; seon.cluster/mcp-value reads the rest
 "exception" true}                  ; only on an error entry; val is the floor's print of the error map
```

- The batch's `repairs`, `unread`, `private`, `schema-notes`, `contract-note`/`contract-warning` and `diagnostics`
  are one final `{"tag" "batch" …}` event of data.
- It has no `;;` lines.
- Both double prints are deleted in S10:
  - `run-events` (:440) re-reads terminal EDN and emits `:seon.repl/response`;
  - `cluster/mcp-project` (:352) returns `{:seon.dev.mcp/value <prepared>}`, which the prepl prints again.

## 5. Measurements

Warm default JVM. These are prototype microbenchmarks; the final commit is `seon.db/with`.

| Batch | Total | Notes |
|---|---|---|
| 1 contracted `defn` | 38–39 ms | derive 7–8, speculative 1, settle (candidate write) 24–25 |
| 40 chained `defn`s | 623 ms | 15.6 ms per defn, each reading the previous one's facts |
| h36, 22 `register!` | 540–600 ms | one delta; candidate projection memoized from the previous one |
| h37 (22 in one `do`) | 440–560 ms | |
| h16 register, then use | 395 ms | one schema flush 54 ms |
| Contract note / warning | 53 / 63 ms | one query |
| Acquisition through `run` | 42 µs per row | today 27 µs |
| Forgiving stage | 69 ms for 52 inputs | max 35 ms, on 12 KB |

**Today's path**, measured on a hosted branch agent through `evaluate-source!`:

| Input | Run 1 / run 2 |
|---|---|
| One `defn` | 948 / 512 ms |
| h39 | 342 / 309 ms |
| h36 | **54,657 / 50,825 ms** |

Costs removed and replaced along the way (measured):

| Cost | Measured | Replacement |
|---|---|---|
| Arming against the working value | slow | arms against the basis db and projection |
| Analysis against the working value (`available-functions` query) | 38 ms | derive runs on the basis db with the observed Vars declared |
| `row-tx` on the working value | 40 ms | `speculative-row-tx` |
| `seon.db/pull` on a speculative value | grows with the overlay | `dbu/entid` + `d/pull` |

## 6. The corpus

Appendix A holds the full table. In summary:

- 94 items, 0 refused, 0 diagnostics, 6.8 s in total including the plain-SCI reference runs.
- 74 are value-identical.
- The designed differences:
  - Seon supplies `seon.schema`, `clojure.test` and UUID;
  - h27 is a contract refusal;
  - strings print readably;
  - object hashes differ;
  - h20's delimiters are repaired;
  - h35 prints inside the arm.
- In default, 29 binds are private only because the stand-in env watch cannot see what the patched SCI sees. In the
  scratch JVM the producer observer joins 101/101.
- The corpus normalizes `ERR` text, and h13's old green was false (R4, R9). S17 replaces the driver with semantic
  assertions.

## 7. The instructions

**Rules for every step:**

- Cite HEAD dd5dcaff7.
- Lint `src script test` with `--cache false`.
- Compile touched schemas and contracts with their consumers from a `git archive` of HEAD plus the diff.
- Each step leaves HEAD loadable: new functions are added beside the old ones first, callers switch, and one scripted
  deletion comes last.
- Root runs the named focused tests centrally.
- `seon/schema/datahike.clj`, `turn.clj`, `db.clj` and `cluster.clj` have uncommitted edits by other lanes. Root checks
  `tmp/orchestrator/file-ownership.md` before each step.

### 7.1 Before the cut (each lands alone)

**S1. SCI fork: the producer observer.** 1 h.

- Apply `tmp/one-path/sci-observer.diff` (+38/−12, 6 files, appendix E) in `reference-code/sci`:
  - `lang.cljc`: `*write-observer*`, `*def-origin*`, and the observer call in `bindRoot`;
  - `evaluator.cljc`: the `eval-def` origin parameter, metadata written before the root;
  - `analyzer.cljc`: the origin in `analyze-def`, `with-top-level-loc` at :2106, the expansion observer;
  - `namespaces.cljc`: intern, unmap and remove-ns events;
  - `protocols.cljc`: the final `def` of P;
  - `records.cljc`: the ctor `:sci.impl/member-of`.
- Add fork tests `sci.core-test/write-observer-sees-every-root-write` (the R1 table) and
  `…/generated-definitions-carry-their-macro-call-location`.
- Proven on the patched copy: 182 tests, 814 passes, and the same 4 pre-existing failures as pinned (F9).
- Commit and push the fork (standing authorization), then bump the gitlink.

**S2. Analyzer forms are never shadowed by host copies (corpus F1).** 15 min.

- `eval.clj` `install-caller-host-bindings!` (:2370), filter `own-bindings`: add
  `(not (contains? sci.impl.utils/ana-macros local-name))` and the same for `special-syms`.
- Regression `seon.sci.eval-test/records-types-and-import-work-in-a-program-namespace`.
- +2.

**S3. Q4: storable agent registrations are attributes.** 30 min.

- `schema/datahike.clj` `compiled-attribute-selection` (:224): add to `core` every root key whose
  `(:seon.schema.projection/schema-admissions projection)` source is `:agent` and that is `declared-storable?`.
  About +4.
- Registration's note producer (in S8's `schema-part`) answers unstorable keys with the bridge's
  `malli->datahike-attr-in` message.
- Regression `seon.schema-in-place-test/an-agent-registered-storable-key-is-an-attribute`:
  - `:x/n [:int]` installed, and written on an owned entity;
  - `:x/f [:=> …]` noted, not installed;
  - a bare entity holding only `:x/n` refused `unowned-entity`.
- Focused: `seon.schema.datahike-test`, `seon.schema-in-place-test`.

### 7.2 Additive steps (new code beside old; HEAD loads after each)

**S4. `seon.sci.forgive`.** 45 min.

- Move `forgive.clj` (appendix C, 189 lines of body) to `src/seon/sci/forgive.clj`, contracted.
- `parinferish` is already a dependency; `turn.clj` uses it for `repair-source`.
- Regression `seon.sci.forgive-test`:
  - the 52 inputs in `forgive-inputs.edn` become a test data resource;
  - assert 39 identical to recorded `planned-sources`, the 13 designed outcomes, and 0 non-advancing reads;
  - bound 5 s.

**S5. `seon.db/entity-at` and `seon.db/speculative-with`.** 20 min.

- Prototype `speculative-with` (8 lines) and the `dbu/entid` + `d/pull` read (4 lines) move into `seon.db`, the
  `datahike.api` owner.
- Test `seon.db-test/a-speculative-value-reads-its-rows-and-carries-the-projection`.

**S6. `seon.fn`: `batch-rows`, `join-bind`, `derive-form`.** 1 h.

- Generalize `source-rows` (:1533) into prototype `batch-rows` (32 lines), keeping `source-rows` as its one-request
  arity.
- Add `join-bind` with full-span equality for `:authored` origins, call-position rules for `:generated`, and
  `:member-of`; add `derive-form`, with re-analysis in SCI's namespace and every observed Var declared.
- Add the `:captures-locals` rule: a def span whose re-analysis reports a symbol unresolved that the enclosing form
  binds locally is not admitted.
- Add the Q1 hook: `probe_kondo.clj` `expansion-hook-code`, supplied only for generated-bind origin calls.
- Regression `seon.fn-test/binds-join-kondo-definitions-exactly`:
  - nested `let`/`when`;
  - `defprotocol` methods;
  - record ctor member;
  - false branch;
  - redefinition;
  - nested `in-ns` then `defn`;
  - runtime `intern` (`:rebound`);
  - h40 through Q1;
  - `(let [k 1] (defn f [] k))` → `:captures-locals`.
- This also fixes finding F1: `source-rows` throwing on a nested `in-ns`.

**S7. `seon.sci.eval/evaluate-batch`.** 1 h.

- The prototype `run` (the fold), `arm!` with the observer bound `nil`, and the SCI stage values.
- The per-form evaluator is `evaluate`'s kernel arm, bounds and bindings, extracted as `evaluate-form` from
  `evaluate` (:4379). `evaluate` keeps calling it until S14.
- Remove the env-watch stand-in: the observer is `sci.lang/*write-observer*` bound around `:evaluate`.
- Regressions:
  - `seon.sci.eval-test/every-definition-is-observed-at-the-def-and-armed-before-the-next-expression`: the R1 table
    plus a rebound armed root;
  - `…/a-batch-keeps-every-value-when-a-later-stage-fails`: the R3 table.

**S8. `seon.turn`: the program transaction and plural settlement.** 1 h.

- Add prototype `candidate-projection` (memo from the previous candidate), `schema-part` (with the S3 notes),
  `program-tx`, `speculative-row-tx` and the contract note/warning query.
- `receipt-settle-call` (:1702) accepts `:seon.program/rows`, `mapcat row-tx`.
- The namespace closure comes from entries plus rows:
  - replaces `namespace-rows`/`evaluated-namespaces` at callers `source-rows` :653, `record-evaluated-call` :1605
    and `receipt-settle-batch-tx` :1089;
  - `receipt-settle-tx` :1062 takes final rows without `analyze-settlement`.
- Schema resources `seon.cluster.eval.edn` and `seon.turn.loop.edn`: add `[:seon.program/rows [:vector :seon.program/row]]`.
- Regression `seon.cluster.evaluate-source-test/one-batch-settles-in-one-transaction-with-every-row`:
  - h39;
  - nested `in-ns`;
  - `register!` then use;
  - `review.alias` (R5);
  - one applied transaction.

### 7.3 Switch steps (callers move; old code is then unreferenced)

**S9. The product turn.** 1 h.

- `evaluate-sources` (:5058–5221, 164 lines) becomes about 60: `evaluate-batch` over the reply text, with rows
  attached to the evaluation whose ordinal made the final bind.
- It keeps the render profile, agent overlay, read-evidence sinks, handle binding and disposition.
- `with-settled` (:5116–5117) and `gate-function-install` (:5157) are no longer called.
- Forgiving stage callers: `declared-sources` :2025, `call-turn` :4781, `agent.clj` `segmenter` :667 and
  `disposition-rule-error` :5043 read entry forms, not rereads.
- The rereads go:
  - `source-events` :1975 is deleted;
  - `source-key` :1981 becomes `[ns source]` (stored sources are single repaired forms);
  - `evaluable-source?` :2491 is deleted (a comment-only text makes no evaluation);
  - `read-only-evaluation?` :2076 tests `:seon.cluster.eval/binds` absence.
- `restore-evaluations!` :5260 and its call go (owner question §10, answer A).
- The evaluation schema gains `:seon.cluster.eval/binds`.
- Focused tests:
  - `seon.cluster.evaluate-source-test`;
  - `seon.turn-test`;
  - `seon.cluster.turn-test`;
  - `seon.dev.mcp-bridge-test/a-run-reply-is-the-response-the-agents-context-reads`.

**S10. MCP prints once.** 45 min.

- `run-events` :440 maps entries to the §4 projection from `:seon.eval/shown`, deleting the EDN decode and re-print.
- `jvm-evaluation-form` :376 becomes `jvm-batch` (appendix D).
- `cluster/mcp-project` :352 answers the floor's text with the digest beside it.
- Regression `seon.dev.mcp-bridge-test/jvm-and-sci-events-carry-the-shown-text-once`. Update
  `…/jvm-mode-reads-through-the-one-reader` so several forms are allowed.

**S11. Render and preview.** 45 min.

- `evaluated-source` :4058, `preview-sources` (callers `system-turn` :2282, `web.clj` `render-source-call` :1425) and
  context assembly become `render-batch`: a fork, with custody set from the execution environment (:3825–3853) with
  the connection removed.
- Regression `seon.sci.eval-test/a-render-batch-cannot-define-or-write`:
  - a def is discarded with a diagnostic;
  - `transact!` refuses;
  - a shared atom mutation is reported as the stated limit.

**S12. Acquisition and removal.** 1 h.

- `install-function-from-database!` :1448 and `install-row!` :1921, :1946 call `evaluate-batch` with the code reader
  and observe/derive/settle off, replacing `one-event`.
- `transfer-evaluated-roots!` :1983 reads roots from the batch's `:bind` effects (the Var's root and metadata at the
  final bind), replacing `:seon.sci.eval/bindings` (schema refs `seon.sci.eval.edn:62,64`,
  `seon.turn.loop.edn:135,137`).
- `my/program.clj` `ns-unmap!` :718 and `remove-ns!` :734 (33 lines) call SCI's unmap and remove-ns, about 6 lines;
  the batch folds the effect (R4).
- `program.cljc` `deletion-row` :1394 loses its reader fallback (26 lines).
- Regression `seon.cluster.evaluate-source-test/a-definition-and-its-removal-in-one-batch-settle-once`, asserting
  both SCI resolution and stored facts.
- Keep `program_change_test` (:35 covers loaded-program adoption), with its value assertion revised for totality.

**S13. Test bodies.** 30 min.

- `test.clj` `bounded-result` :218: the `:else` branch's `runner/run-var!` / `sci.eval/run-test` call becomes the
  `:evaluate` thunk of a one-entry batch (appendix D `thunk-batch`).
- Bound, thread and release stay `bounded-result`'s. Recording stays the runner's `record-tx`: test evidence is not
  program.
- Focused: `seon.test/one-request-test`.

**S14. `evaluate` becomes the per-form stage; the gate becomes the warning.** 45 min.

- `evaluate` :4379 keeps only its single-source convenience (it answers `evaluate-batch`'s one entry for its ~40 test
  callers).
- Its admission references :4613–4891 (15 sites; appendix F) go, and `evaluate-form` holds the arm.
- Focused:
  - `seon.sci.eval-test`;
  - `seon.repl-test`;
  - `seon.test/accretion-test`;
  - `seon.sci.entry-gate-test`, rewritten: the REPL warns, and merge refuses per the contract-coverage spec.

### 7.4 The deletion (one scripted step)

**S15. Delete.** 45 min.

1. `bb tmp/one-path/delete_forms.bb <root> tmp/one-path/deletions.edn` removes 88 defns, 1,940 lines
   (`deletion-ranges.tsv`):

   | File | Defns | Lines |
   |---|---|---|
   | `eval.clj` | 37 | 933 |
   | `reader.cljc` | 19 | 316 |
   | `reply.clj` | 17 | 390 |
   | `turn.clj` | 15 | 301 |

   Delete the rest of `reply.clj` (36).
2. `reader.cljc` `read-events` :563/:580 and `reader-error` :603 drop their deleted callees.
3. Lint must report **zero unresolved symbols**. Appendix F lists every reference that lint found at HEAD, each
   converted in S8–S14 or deleted in this step.
4. Hand-listed references kondo cannot see:
   - `seon.sci.eval.edn`: delete the program-change, restoration, capture, admission, unindexed/multiple/unavailable
     and `bindings` schema families (about −80). `:seon.sci.eval/restore` is gone with `restore-evaluations!`.
   - Task `:must-read` retargets (appendix F, second table):
     - `seon.sci.eval.edn:5,12,20,27,44,53,60,140,156,161,166,181`;
     - `seon.sci.reader.edn:11,12`;
     - `seon.turn.edn:5,19,61,62,81,82,127`;
     - `seon.schema.edn:60,61`;
     - `seon.agent.edn:144`;
     - `seon.turn.edn:154,215`;
     - `seon.turn.loop.edn:181,182,303`.
5. Tests:
   - delete `test/seon/cluster/reply_test.clj` (481) and `test/seon/sci/restoration_test.clj` (132);
   - convert the other 30 test references (appendix F) to `evaluate-batch`, `forgive/forms` or the batch's
     `:seon.eval/effects`;
   - delete `reader_test` `declaration-facts-equal-the-current-var-lift` and
     `namespace-tracking-has-repl-semantics-and-fails-closed` (about 110).
6. Compile every touched schema and contract with its consumers from a `git archive` of HEAD plus the diff.

### 7.5 Net lines

| Row | Removed | Added | Kind |
|---|---|---|---|
| Scripted deletion, 88 defns + rest of `reply.clj` | 1,976 | 0 | **measured** (diff at dd5dcaff7) |
| Prototype fold, stages, schema part, speculative, derive, join (`pipeline.clj` less header 48 and stand-in 27) | 0 | 501 | **measured** |
| Forgiving stage (`forgive.clj` less header) | 0 | 189 | **measured** |
| `evaluate` admission body :4696–4890 → per-form stage | 195 | 30 | range + estimate |
| `evaluate-sources` :5058–5221 → `evaluate-batch` call | 164 | 60 | range + estimate |
| MCP `run-events` + `jvm-evaluation-form` + `mcp-project` wrapper | 64 | 10 | range + estimate |
| `my.program` removal :718–750 | 33 | 6 | range + estimate |
| `deletion-row` fallback | 26 | 0 | range |
| `restore-evaluations!` + call | 25 | 0 | range |
| `source-events` / `evaluable-source?` / `source-key` | 24 | 4 | range + estimate |
| JVM, render and test stage values (appendix D) | 0 | 30 | measured prototype |
| Q4 selection + `binds` consumer | 0 | 8 | estimate |
| Caller conversions (40 production references) | 0 | 60 | estimate |
| **src + script** | **2,507** | **898** | **net −1,609** |
| Resources: retired schema families / one-value schemas / `binds` | 80 | 66 | estimate, **net −14** |
| Tests: `reply_test` 481, `restoration_test` 132, `reader_test` 110, `eval_test` ~70, `accretion_test` ~60 / regressions ~420, conversions ~60 | 853 | 480 | **net ≈ −373** |
| SCI fork | 12 | 38 | measured |
| S16 (Q5 extensions), if taken | 0 | ~45 src + ~15 resources | estimate |

**Verification limit.** The combined candidate diff was not built, archived, loaded or compiled; that is S15's
compile gate. The deletion half and the prototype half are measured separately.

### 7.6 After the cut

**S16. Q5 extension entities (recommended; separate commit).** 1.5 h.

- Entity `:seon.method/*`, keyed by `[:seon.method/multi sym, :seon.method/dispatch edn-string]`, and
  `:seon.impl/*`, keyed by `[protocol sym, type sym]`. Source is the extension form's span.
- Derive them from kondo `:defmethod` usages (`:dispatch-val-str`) and `:protocol-impls`, confirmed by `get-method`
  or `satisfies?` on the live value.
- Acquisition replays extensions after all rows, by edge.
- `remove-method` is an observed effect (a fork event, +3).
- Regression: `defmethod` in a false branch makes no entity, and acquisition in a fresh context dispatches.

**S17. The committed measurement driver.** 1 h.

- `script/seon/dev/one_path_corpus.clj` runs the complete installed entrance.
- It asserts semantically per item:
  - error class and data, not normalized `ERR`;
  - effects;
  - final rows;
  - acquisition in a fresh context.
- It counts reads, evaluations, analyses, writer transactions and prints, and records allocation and latency on
  parent and change.

## 8. Regressions (one per class)

| Class | Regression | Step |
|---|---|---|
| Observation | `write-observer-sees-every-root-write` (fork), `every-definition-is-observed-at-the-def-and-armed-before-the-next-expression` | S1, S7 |
| Join | `binds-join-kondo-definitions-exactly` | S6 |
| Totality | `a-batch-keeps-every-value-when-a-later-stage-fails` | S7 |
| Settlement | `one-batch-settles-in-one-transaction-with-every-row` | S8 |
| Removal | `a-definition-and-its-removal-in-one-batch-settle-once` | S12 |
| Render | `a-render-batch-cannot-define-or-write` | S11 |
| MCP | `jvm-and-sci-events-carry-the-shown-text-once` | S10 |
| Input | `seon.sci.forgive-test` | S4 |
| Schema | `an-agent-registered-storable-key-is-an-attribute` | S3 |
| Host bindings | `records-types-and-import-work-in-a-program-namespace` | S2 |

## 9. Findings

Root files these; this lane edits no task file.

- **F1.** `seon.fn/source-rows` throws on a nested `in-ns` followed by a definition: "task-links refused context at
  []" (fn.clj:831). Fixed by S6.
- **F2.** Today, 22 top-level registrations take 50.8–54.7 s. The one path takes 540–600 ms.
- **F3.** The first `db/with` on a newly read value costs ~400 ms, then 18–24 ms, and adds ~680 ms per write during
  root's test rounds. Unattributed; see the JVM-wide profile task c7bc0c506aa4.
- **F4.** `report-arity-comparison` costs 13–20 ms per write and is proportional to the whole program.
- **F5.** `register!` admission costs 8.5 ms per call against the whole candidate population (task b2e2909e662d).
- **F6.** MCP JVM error replies run to ~12 KB. This is the double print; fixed by S10.
- **F7.** Every `defn` row lists `clojure.core/defn` (and `def`/`defonce`) in `:seon.fn/calls`. It is noise unless
  some consumer needs it.
- **F8.** SCI's `__->R__ctor__` is a user Var. S1 marks it a member at its producer.
- **F9.** Four pre-existing `sci.core-test` failures on the pinned fork: `function-results-dont-have-metadata` ×2 and
  `error-location-test` ×2.
- **F10.** An MCP non-default session fails with "No such var: seon.operator.prepl/evaluate".
- **F11.** `eval/reader-context` with a nil ctx throws a bare NPE.
- **F12.** A contract refusal stored a 6.4 MB blob: the whole projection as the offending value. Errors hold
  references, not copies.
- **F13.** `seon.db/pull` gets slower per call as the speculative overlay grows. Datalog over a `with` value takes
  38 ms against 4.8 ms on a committed value.
- **F14, caused by this lane.** Twice, a runaway forgiving-stage probe (a quadratic regex, then a repair cycle) spun
  inside default's JVM. It was stopped through the raw prepl by interning a throwing `forgive/next-form`. After that,
  forgive ran only in a scratch 512 MB JVM with a watchdog, and got a progress guard. No data was written.
- **F15.** Root restarted default three times during the lane, with transient prepl-closed and
  advertisement-missing states. Not caused by this lane.

## 10. Owner question

**OQ1. A batch whose one commit is refused.** Values are always returned. The question is what happens to the batch's
live Vars.

- **(A, recommended) Keep them live.** They are listed as `:seon.eval/private` with the refusal, like any unadmitted
  def. They never merge, and the agent redefines them.
  - Cost: 0 lines. Deletes `restore-evaluations!` and the restoration machinery (counted in §7.5).
  - Sacrifice: a refused function stays callable in the agent's own world until it is redefined.
- **(B) Revert them.** Add `:previous` to the fork's bind event, then fold the effects in reverse: rebind the previous
  root, or unmap a new Var.
  - Cost: +14 lines (fork +2). Unproven, about 1 h.
  - Sacrifice: a private atom or def made in the same batch is reverted too.

## Appendix A. Corpus table (`tmp/one-path/corpus-table-v2.md`)

| item | same values as plain SCI | producer binds (scratch) | rows (default) | ms | verdict |
|---|---|---|---|---|---|
| m-defn-top | yes | 1 exact | 1 | 411 | green |
| m-defn-do | yes | 1 exact | 1 | 71 | green |
| m-defn-let | yes | 1 exact | 1 | 75 | green |
| m-defn-when | yes | 1 exact | 1 | 76 | green |
| m-defn-macro | yes | 2 exact | 2 | 75 | green |
| m-defn-noschema-top | yes | 1 exact | 1 | 59 | green |
| m-defn-noschema-do | yes | 1 exact | 1 | 53 | green |
| m-defn-noschema-let | yes | 1 exact | 1 | 52 | green |
| m-defn-noschema-when | yes | 1 exact | 1 | 56 | green |
| m-defn-noschema-macro | yes | 2 exact | 2 | 56 | green |
| m-def-top | yes | 1 exact | 1 | 51 | green |
| m-def-do | yes | 1 exact | 1 | 49 | green |
| m-def-let | yes | 1 exact | 1 | 56 | green |
| m-def-when | yes | 1 exact | 1 | 56 | green |
| m-def-macro | yes | 2 exact | 2 | 59 | green |
| m-defmacro-top | yes | 1 exact | 1 | 52 | green |
| m-defmacro-do | yes | 1 exact | 1 | 55 | green |
| m-defmacro-let | yes | 1 exact | 0 | 9 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-defmacro-when | yes | 1 exact | 0 | 8 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-defmacro-macro | yes | 2 exact | 2 | 58 | green |
| m-defmulti-top | no: object hash in print | 1 exact | 1 | 52 | green |
| m-defmulti-do | no: object hash in print | 1 exact | 1 | 49 | green |
| m-defmulti-let | no: object hash in print | 1 exact | 0 | 9 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-defmulti-when | no: object hash in print | 1 exact | 0 | 8 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-defmulti-macro | no: object hash in print | 2 exact | 2 | 52 | green |
| m-defprotocol-top | yes | 2 exact | 0 | 9 | green; 2 bind(s) private in default only (pinned-SCI stand-in) |
| m-defprotocol-do | yes | 2 exact | 0 | 9 | green; 2 bind(s) private in default only (pinned-SCI stand-in) |
| m-defprotocol-let | yes | 2 exact | 0 | 9 | green; 2 bind(s) private in default only (pinned-SCI stand-in) |
| m-defprotocol-when | yes | 2 exact | 0 | 9 | green; 2 bind(s) private in default only (pinned-SCI stand-in) |
| m-defprotocol-macro | yes | 3 exact | 1 | 57 | green; 2 bind(s) private in default only (pinned-SCI stand-in) |
| m-defrecord-top | yes | 3 exact | 2 | 53 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-defrecord-do | yes | 3 exact | 2 | 55 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-defrecord-let | yes | 3 exact | 0 | 11 | green; 3 bind(s) private in default only (pinned-SCI stand-in) |
| m-defrecord-when | yes | 3 exact | 0 | 10 | green; 3 bind(s) private in default only (pinned-SCI stand-in) |
| m-defrecord-macro | yes | 4 exact | 3 | 56 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-deftype-top | yes | 1 exact | 1 | 59 | green |
| m-deftype-do | yes | 1 exact | 1 | 60 | green |
| m-deftype-let | yes | 1 exact | 0 | 10 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-deftype-when | yes | 1 exact | 0 | 9 | green; 1 bind(s) private in default only (pinned-SCI stand-in) |
| m-deftype-macro | yes | 2 exact | 2 | 54 | green |
| m-definterface-top | yes | - | 0 | 2 | matches plain SCI (SCI has no definterface) |
| m-definterface-do | yes | - | 0 | 2 | matches plain SCI (SCI has no definterface) |
| m-definterface-let | yes | - | 0 | 2 | matches plain SCI (SCI has no definterface) |
| m-definterface-when | yes | - | 0 | 2 | matches plain SCI (SCI has no definterface) |
| m-definterface-macro | yes | 1 exact | 1 | 54 | matches plain SCI (SCI has no definterface) |
| h01-ns-require | no: print: strings shown readably | - | 0 | 3 | green |
| h02-in-ns-top | yes | 1 exact | 1 | 61 | green |
| h03-in-ns-nested | yes | - | 0 | 2 | green |
| h04-several-ns | yes | 1 exact | 1 | 82 | green |
| h05-declare | yes | 2 exact | 2 | 103 | green |
| h06-letfn | yes | - | 0 | 1 | green |
| h07-reader-conditional | yes | - | 0 | 2 | green |
| h08-tagged-literals | yes | - | 0 | 1 | green |
| h09-metadata | yes | 1 exact | 1 | 95 | green |
| h10-syntax-quote | yes | - | 0 | 2 | green |
| h11-redefinition | yes | 1 exact | 1 | 121 | green |
| h12-ns-unmap | yes | 1 exact | 0 | 23 | green |
| h13-program-ns-unmap | yes | 1 exact | 1 | 101 | green |
| h14-register-top | no: Seon namespace seon.schema | - | 0 | 235 | green |
| h15-register-nested | no: Seon namespace seon.schema | - | 0 | 213 | green |
| h16-register-then-use | no: Seon namespace seon.schema | 1 exact | 1 | 297 | green |
| h17-binding-set | no: print: strings shown readably | 1 exact | 1 | 58 | green |
| h18-destructuring | yes | 1 exact | 1 | 73 | green |
| h19-throw-mid-run | yes | 2 exact | 2 | 54 | green |
| h20-unbalanced | no: delimiter repaired by the forgiving stage | 1 exact | 1 | 53 | green |
| h21-large-range | yes | - | 0 | 7 | green |
| h22-large-string | no: print: strings shown readably | - | 0 | 38 | green |
| h23-large-def | yes | 1 exact | 1 | 51 | green |
| h24-defonce | yes | 1 exact | 1 | 48 | green |
| h25-deftest | no: Seon supplies clojure.test | 1 exact | 2 | 77 | green |
| h26-qualified-defn | yes | - | 0 | 3 | green |
| h27-contract-refusal | no: contract refusal (by design) | 1 exact | 1 | 84 | green |
| h28-bare-tokens | no: print: strings shown readably | - | 0 | 4 | green |
| h29-comments-discard | yes | - | 0 | 1 | green |
| h30-require-import | no: Seon allows java.util.UUID | - | 0 | 3 | green |
| h31-def-no-value | yes | - | 0 | 2 | green |
| h32-multi-arity | yes | 1 exact | 1 | 74 | green |
| h33-loop-anon | yes | - | 0 | 2 | green |
| h34-extend-protocol | no: object hash in print | 2 exact | 0 | 11 | green; 2 bind(s) private in default only (pinned-SCI stand-in) |
| h35-lazy-large | no: probe prints after the batch; production prints in the arm | 1 exact | 1 | 49 | green |
| h36-register-22 | no: Seon namespace seon.schema | - | 0 | 571 | green |
| h37-register-22-in-do | no: Seon namespace seon.schema | - | 0 | 451 | green |
| h39-two-defns-in-let | yes | 2 exact | 2 | 80 | green |
| h40-macro-two-defns | yes | 3 exact | 1 | 116 | green; 2 bind(s) private in default only (pinned-SCI stand-in) |
| h41-defn-when-timing-1 | yes | 1 exact | 1 | 67 | green |
| h41-defn-when-timing-2 | yes | 1 exact | 1 | 68 | green |
| h41-defn-when-timing-3 | yes | 1 exact | 1 | 75 | green |
| h41-defn-when-timing-4 | yes | 1 exact | 1 | 80 | green |
| h42-t1-a-let | yes | 1 exact | 1 | 82 | green |
| h42-t1-b-when | yes | 1 exact | 1 | 82 | green |
| h42-t2-a-let | yes | 1 exact | 1 | 81 | green |
| h42-t2-b-when | yes | 1 exact | 1 | 81 | green |
| h42-t3-a-let | yes | 1 exact | 1 | 82 | green |
| h42-t3-b-when | yes | 1 exact | 1 | 81 | green |

## Appendix B. The prototype orchestrator (`tmp/one-path/pipeline.clj`, as run)

```clojure
(ns pipeline
  "REPL-proven prototype of the one REPL path, revision 2 (spec:
  docs/research/agent-platform/one-repl-path-spec-2026-09-27.md).

  ONE orchestrator, `run`. Its request carries the stages as values:
  :read-next (the forgiving input stage + the one reader), :evaluate (SCI, the
  host JVM, or a supplied thunk), :observe (definition observation),
  :derive? and :settle?. Entrants differ only in these values.

  The fold, per top-level form:
    read       one form at the live cursor (forgive/next-form: prose becomes
               comments, prompt markers go, delimiters are repaired only when
               the rest then reads clean; anything else is returned unread)
    evaluate   once, with seon.db/*read-database* bound to the working value,
               so the form reads every earlier form's facts
    publish    the entry is retained BEFORE any bookkeeping
    derive     for a form that defined, unmapped or registered: kondo over that
               form, the exact join, its rows, its namespace rows, its schema
               rows; folded by program identity; applied speculatively to the
               working value (the writer's own preparation, no validator)
  Then: the contract warning (derived from facts, never a refusal), ONE
  validated transaction computed against the basis, and a total reply
  printed per entry.

  Observation here is an env-atom watch: a stand-in, because default's JVM runs
  the pinned SCI. Production uses the producer observer proven in a scratch JVM
  (tmp/one-path/sci-observer.diff, observe_scratch.clj).

  Load order (MCP eval_clj jvm, default):
    (load-file \"/Users/sean/src/seon/tmp/one-path/forgive.clj\")
    (load-file \"/Users/sean/src/seon/tmp/one-path/pipeline.clj\")
  Writes nothing: speculative values and the final commit use `with`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.db.utils :as dbu]
            [sci.core :as sci]
            [sci.impl.vars :as sci.vars]
            [seon.db :as db]
            [seon.fn :as sfn]
            [seon.fn.analyzer :as analyzer]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as eval]
            [seon.sci.reader :as reader]
            [seon.test.accretion :as accretion]
            [seon.turn :as turn])
  (:import (java.nio.charset StandardCharsets)))

(defn- ms-since [t0] (/ (- (System/nanoTime) t0) 1e6))

(defn- diagnostic [stage t]
  {:seon.error/message (str (name stage) ": " (ex-message t))
   :one-path/stage stage
   :seon.error/throwable-class (.getName (class t))})

(defmacro ^:private guarded
  "Evaluate body; on a throw answer `fallback` and conj a diagnostic naming
  `stage` onto the volatile `diags`. The value already retained is never
  touched."
  [diags stage fallback & body]
  `(try ~@body
        (catch Throwable t# (vswap! ~diags conj (diagnostic ~stage t#)) ~fallback)))

(defn- line-starts [^String text]
  (into [0] (keep-indexed (fn [i c] (when (= \newline c) (inc i)))) text))

(defn- utf8-length [^String s] (alength (.getBytes s StandardCharsets/UTF_8)))

;;; ---------------------------------------------------------------------------
;;; Observation (stand-in: env-atom watch; production: sci.lang/*write-observer*)
;;; ---------------------------------------------------------------------------

(defn- sci-var? [v] (instance? sci.lang.Var v))
(defn- var-symbol [v] (let [m (meta v)] (symbol (str (:ns m)) (str (:name m)))))

(defn env-watch
  "Stand-in observer: calls `on-event` with {:op :bind :var v :origin} when a
  held Var's root changes, and {:op :unmap}/{:op :remove-ns}. Answers stop."
  [ctx on-event]
  (let [held (atom #{}) roots (atom {})
        check! (fn [v]
                 (when (sci.vars/hasRoot v)
                   (let [root (.getRawRoot ^sci.lang.Var v)]
                     (when-not (identical? root (get @roots v ::none))
                       (swap! roots assoc v root)
                       (let [armed (on-event {:op :bind :var v
                                              :origin (select-keys (meta v) [:line :column])})]
                         (when armed (swap! roots assoc v armed)))))))
        k (keyword "pipeline" (str (gensym "observe")))]
    (add-watch (:env ctx) k
               (fn [_ _ old-env new-env]
                 (let [old-nss (:namespaces old-env) new-nss (:namespaces new-env)]
                   (when-not (identical? old-nss new-nss)
                     (doseq [[n m] new-nss :let [om (get old-nss n)] :when (not (identical? m om))]
                       (doseq [[s v] m :when (and (symbol? s) (sci-var? v) (not (identical? v (get om s))))]
                         (swap! held conj v))
                       (doseq [[s v] om :when (and (symbol? s) (sci-var? v) (not (contains? m s)))]
                         (on-event {:op :unmap :ns n :sym s})))
                     (doseq [[n _] old-nss :when (not (contains? new-nss n))]
                       (on-event {:op :remove-ns :ns n})))
                   (run! check! @held))))
    (fn stop [] (remove-watch (:env ctx) k))))

;;; ---------------------------------------------------------------------------
;;; Arming: at the definition, total, never a refusal of the definition
;;; ---------------------------------------------------------------------------

(defn- refusing-root [sym t]
  (let [refusal (ex-info (str sym " is unavailable: its contract did not arm: " (ex-message t))
                         {:seon.error/member :seon.fn/spec :seon.fn/sym sym} t)]
    (fn [& _] (throw refusal))))

(defn- arm!
  "Arm `v`'s contract under `projection`; answer the armed root, or install a
  refusing root and answer it with the failure."
  [ctx db projection v]
  (when-let [contract (:malli/schema (meta v))]
    (try
      (#'eval/install-function-contract!
       ctx {:seon.fn/sym (var-symbol v) :seon.fn/spec (pr-str contract)} projection db)
      {:root (.getRawRoot ^sci.lang.Var v)}
      (catch Throwable t
        (let [r (refusing-root (var-symbol v) t)]
          (sci.vars/bindRoot v r)
          {:root r :arm-error (ex-message t)})))))

;;; ---------------------------------------------------------------------------
;;; Schema delta: one candidate projection per changed forms value
;;; ---------------------------------------------------------------------------

(defn- candidate-projection
  [{:keys [db delta memo]}]
  (let [base (db/carried-projection db)
        changed (schema/changed-keys delta)
        forms @(:seon.schema.delta/candidate-forms delta)
        ;; Derived from the PREVIOUS candidate: projection-with-declarations
        ;; recompiles only what differs from the projection it is given.
        [_ prev] @memo
        from (or prev base)
        build (fn []
                (if (empty? changed)
                  base
                  (#'schema/projection-with-declarations
                   'pipeline/candidate-projection from forms
                   (get from :seon.schema.projection/function-contracts {})
                   (assoc (#'schema/population-options from)
                          :seon.schema/schema-admissions
                          (into (:seon.schema.projection/schema-admissions from)
                                (map (fn [k] [k {:seon.schema.admission/source :agent}]))
                                (filter (fn [k] (contains? forms k)) changed))))))
        [k v] @memo]
    (if (identical? k forms) v (let [v (build)] (vreset! memo [forms v]) v))))

;;; ---------------------------------------------------------------------------
;;; Per-form derivation: kondo over the form, exact join, rows
;;; ---------------------------------------------------------------------------

(defn- namespace-entity [ctx ns-name]
  (merge {:seon.ns/name ns-name}
         (#'eval/binding-rows (#'eval/reader-context ctx ns-name))))

(defn- batch-rows
  "seon.fn/source-rows over one analysis of `requests`: rows keyed by
  [request-index utf8-offset name]."
  [db requests analysis]
  (let [{a :seon.fn/analysis text :seon.fn/source spans :seon.fn/source-spans
         function-entities :seon.fn/function-rows} analysis
        context (#'sfn/text-context text)
        byte-starts (:seon.fn/byte-line-starts context)
        functions (into (#'sfn/first-party-function-symbols a) (map :seon.fn/sym) function-entities)
        submitted (#'sfn/source-analysis a (ffirst spans) (second (peek spans)))
        projection (db/carried-projection db)
        entities (#'sfn/analysis-entities-by-file
                  submitted functions {"<stdin>" context}
                  (#'sfn/program-targets db (#'sfn/population-targets projection (#'sfn/invoked-attributes submitted)) functions)
                  projection)
        form-starts (mapv (fn [[first-row _]] (nth byte-starts (dec first-row))) spans)
        form-of (fn [offset] (last (keep-indexed (fn [i s] (when (<= s offset) i)) form-starts)))
        shapes (program/shapes-in projection)]
    (into {}
          (keep (fn [entity]
                  (when (and (or (:seon.fn/sym entity) (:seon.test/sym entity)) (:seon.fn/form-span entity))
                    (let [[s _] (:seon.fn/form-span entity)
                          i (form-of s)
                          ns-row (:seon.fn/namespace-row (nth requests i))
                          row (program/declaration-row
                               projection
                               (program/canonical-row shapes (dissoc entity :seon.fn/file :seon.fn/form-span))
                               :all :agent)]
                      [[i (- s (form-starts i)) (name (or (:seon.fn/sym entity) (:seon.test/sym entity)))]
                       (assoc row :seon.program/definition-digest
                              (db/definition-digest-of projection row ns-row))]))))
          (get entities "<stdin>"))))

(defn- to-synthetic [{:keys [first-row line column]} [l c]]
  [(+ first-row (- l line)) (if (= l line) (+ 1 (- c column)) c)])

(defn- contains-pos? [{row ::analyzer/row col ::analyzer/col end-row ::analyzer/end-row end-col ::analyzer/end-col} [r c]]
  (and row end-row
       (or (< row r) (and (= row r) (<= col c)))
       (or (< r end-row) (and (= r end-row) (< c end-col)))))

(defn- join-bind
  "The one kondo definition a bind is: starting at its origin, or inside the
  call at its origin and defined by that call's operator."
  [analysis coords {:keys [sym origin]}]
  (let [a (:seon.fn/analysis analysis)
        {:keys [line column]} origin]
    (if-not line
      {:join :unanalyzed :reason :runtime-origin}
      (let [pos (to-synthetic coords [line column])
            same (filter #(= (name sym) (str (::analyzer/name %))) (::analyzer/var-definitions a))
            at (filter #(= pos [(::analyzer/row %) (::analyzer/col %)]) same)
            call (first (filter #(= pos [(::analyzer/row %) (::analyzer/col %)]) (::analyzer/var-usages a)))
            op (when call (symbol (str (::analyzer/to call)) (str (::analyzer/name call))))
            inside (when call (filter #(and (contains-pos? call [(::analyzer/row %) (::analyzer/col %)])
                                            (= op (::analyzer/defined-by %))) same))
            cs (distinct (concat at inside))]
        (case (count cs)
          0 {:join :unanalyzed :reason :no-definition-at-origin}
          1 (cond-> {:join :matched :kondo (first cs)}
              (not= (namespace sym) (str (::analyzer/ns (first cs))))
              (assoc :kondo-ns-differs true))
          {:join :unanalyzed :reason :ambiguous})))))

(defn- origin-byte-offset [{:keys [source line column]} [l c]]
  (let [ls (line-starts source) k (- l line)
        off (+ (get ls k) (- c (if (zero? k) column 1)))]
    (utf8-length (subs source 0 off))))

(defn- span-source [analysis {r ::analyzer/row c ::analyzer/col er ::analyzer/end-row ec ::analyzer/end-col}]
  (let [t (:seon.fn/source analysis) ls (line-starts t)
        off (fn [l col] (+ (get ls (dec l)) (dec col)))]
    (subs t (off r c) (off er ec))))

(defn- derive-form
  "Rows for one form's binds: kondo over the form (every observed Var
  declared), the exact join, a definition kondo placed in another namespace
  re-analyzed in SCI's, and the Var's evaluated contract."
  [{:keys [ctx]} db entry binds declared]
  (let [req {:namespace-name (:ns-before entry) :form-source (:source entry)
             :seon.fn/namespace-row (namespace-entity ctx (:ns-before entry))
             :seon.fn/declared declared}
        analysis (#'sfn/runtime-analysis-batch db [req])
        rows (batch-rows db [req] analysis)
        coords {:first-row (ffirst (:seon.fn/source-spans analysis)) :line (:line entry) :column (:column entry)}]
    (vec
     (for [{:keys [sym var origin] :as b} binds
           :let [j (join-bind analysis coords b)
                 row (when (= :matched (:join j))
                       (if (:kondo-ns-differs j)
                         (let [n (symbol (namespace sym))
                               r2 {:namespace-name n :form-source (span-source analysis (:kondo j))
                                   :seon.fn/namespace-row (namespace-entity ctx n) :seon.fn/declared declared}]
                           (first (vals (batch-rows db [r2] (#'sfn/runtime-analysis-batch db [r2])))))
                         (get rows [0 (origin-byte-offset entry [(:line origin) (:column origin)]) (name sym)])))
                 contract (:malli/schema (meta var))]]
       (cond-> (assoc j :sym sym :fn? (fn? @var))
         row (assoc :row (cond-> row
                           (and contract (fn? @var)) (assoc :seon.fn/spec (pr-str contract)))))))))

(defn- namespace-row
  "The namespace fact the rows and receipts need: SCI's resolver bindings
  (aliases, refers, requires, imports) with the namespace's source."
  [ctx db projection n]
  (#'eval/namespace-context-row projection db n nil {} (#'eval/reader-context ctx n) true))

;;; ---------------------------------------------------------------------------
;;; Schema part: every changed key, agent-registered storable keys installed
;;; ---------------------------------------------------------------------------

(defn- schema-part
  "Rows and attribute declarations for the delta's changed keys. A storable
  key an agent registered is an attribute (owner Q4); an unstorable one is
  reported with the bridge's reason."
  [{:keys [delta] :as batch} db & [only-keys rows-only?]]
  (let [changed (sort (cond->> (schema/changed-keys delta) only-keys (filter only-keys)))]
    (if (empty? changed)
      {:tx [] :notes []}
      (let [candidate (candidate-projection (assoc batch :db db))
            current (db/carried-projection db)
            forms (:seon.schema.projection/forms candidate)
            selected (set (schema.datahike/database-attributes-in candidate))
            storable (fn [k] (try (schema.datahike/malli->datahike-attr-in candidate k) nil
                                  (catch clojure.lang.ExceptionInfo t (ex-message t))))
            registered (filter #(schema/registration-delta-form delta %) changed)
            extra (for [k registered :when (and (not (selected k)) (nil? (storable k)))]
                    (schema.datahike/malli->datahike-attr-in candidate k))
            notes (for [k registered :let [why (storable k)] :when why]
                    {:seon.schema/key k :seon.schema/installed? false :seon.error/message why})
            rows (for [k changed]
                   (if-let [form (schema/registration-delta-form delta k)]
                     (let [row (program/declaration-row
                                candidate
                                (program/with-contract-facts
                                 {:seon.program/row (accretion/schema-row
                                                     forms {:seon.schema/key k :seon.schema/form (pr-str form)
                                                            :seon.schema.admission/source :agent})
                                  :seon.program/compile-options (:seon.schema.projection/compile-options candidate)
                                  :seon.program/predicate-functions (schema/predicate-functions-in candidate)
                                  :seon.program/schema-keys (set (keys forms))
                                  :seon.program/schema-forms forms})
                                :all :agent)
                           existing (db/pull db '[*] [:seon.schema/key k])]
                       (if (:db/id existing)
                         (program/exact-replacement-tx existing row)
                         [(assoc row :db/id (str "schema:" k))]))
                     (when-let [id (:db/id (db/pull db [:db/id] [:seon.schema/key k]))]
                       [[:db/retractEntity id]])))]
        {:tx (-> (if rows-only? [] (vec (#'turn/schema-attribute-change-tx db current candidate (set changed))))
                 (into (if rows-only? [] extra))
                 (into (mapcat identity) rows))
         :notes (vec notes)}))))

;;; ---------------------------------------------------------------------------
;;; Transactions: speculative (per form) and final (once)
;;; ---------------------------------------------------------------------------

(defn- program-tx
  "Writer data for the folded program change against `db`: namespace rows,
  the schema part, each final row, each removal."
  [batch ctx db program namespaces]
  (let [projection (db/carried-projection db)
        {stx :tx notes :notes} (schema-part batch db)
        basis (if (seq stx) (db/with db stx) db)
        basis (if (:seon.error/at basis) db basis)
        ns-tx (into [] (comp (keep #(namespace-row ctx db projection %))
                             (mapcat #(#'turn/row-tx db {:seon.program/row %} %)))
                    namespaces)
        rows-tx (into [] (mapcat (fn [[_ v]] (when (map? v) (#'turn/row-tx basis {:seon.program/row v} v))))
                      program)
        removals (into [] (keep (fn [[sym v]]
                                  (when (= ::removed v)
                                    (when-let [id (:db/id (db/pull db [:db/id] [:seon.fn/sym sym]))]
                                      [:db/retractEntity id]))))
                       program)]
    {:tx (-> stx (into ns-tx) (into rows-tx) (into removals)) :notes notes :schema? (seq stx)}))

(defn- speculative-row-tx
  "A row's facts on the working value, for later forms' reads: row-tx's pure
  part (exact replacement of an existing identity, else the new entity and its
  namespace). Admission, concurrency and contract checks run once, in the
  final transaction's row-tx."
  [w row]
  (let [[ident v] (program/row-identity row)
        ;; The database owner's own reads (production: a seon.db function):
        ;; seon.db/pull's per-value work grows with the speculative overlay.
        existing (when-let [e (dbu/entid w [ident v])] (d/pull w '[*] e))
        ns-attr (if (:seon.fn/ns row) :seon.fn/ns :seon.test/ns)
        ns-ref (get row ns-attr)
        missing-ns (when (and (vector? ns-ref) (not (dbu/entid w ns-ref))) (second ns-ref))
        row (cond-> row missing-ns (assoc ns-attr (str "namespace:" missing-ns)))]
    (cond-> (if (:db/id existing)
              (program/exact-replacement-tx existing row)
              [(assoc row :db/id (str (name ident) ":" v))])
      missing-ns (conj {:db/id (str "namespace:" missing-ns) :seon.ns/name missing-ns}))))

(defn- speculative-with
  "The value `tx` leaves on `db`: the writer's own preparation (encoding),
  Datahike's `with`, the projection state carried; no final-report validator.
  Validation runs once, at the batch's commit."
  [db tx state]
  (let [prepared (update (#'db/prepared-write (:seon.schema/projection @state) tx)
                         :tx-meta dissoc :datahike/validate-report)]
    (db/carry-projection-state (:db-after (d/with db prepared)) state)))

;;; ---------------------------------------------------------------------------
;;; Stage values
;;; ---------------------------------------------------------------------------

(defn sci-read-next
  "The one reader behind the forgiving input stage, at the live cursor."
  [{:keys [ctx mode]} state]
  ((requiring-resolve 'forgive/next-form)
   ctx (dissoc (#'reader/parse-options {::reader/ctx ctx ::reader/features #{:clj} ::reader/tags {}}) :eof)
   mode (:structured? state) (:version state) (:repairs state) (:last-code-end state)))

(defn sci-evaluate [{:keys [ctx]} form] (sci/eval-form ctx form))

(defn sci-ns-name [{:keys [ctx]}] (sci/eval-form ctx '(clojure.core/ns-name clojure.core/*ns*)))

;;; ---------------------------------------------------------------------------
;;; The one function
;;; ---------------------------------------------------------------------------

(defn- entry-line-column [^String text start]
  (let [ls (line-starts text) l (count (take-while #(<= % start) ls))]
    [l (inc (- start (get ls (dec l))))]))

(defn run
  "The one orchestrator. Request: {:db :text :mode :ctx :start-ns} plus stage
  values :read-next :evaluate :ns-name :observe :derive? :settle?."
  [{:keys [db text start-ns mode] :or {start-ns 'user mode :code} :as request}]
  (let [t0 (System/nanoTime)
        ctx (or (:ctx request) (eval/base-ctx db))
        batch (merge {:read-next sci-read-next :evaluate sci-evaluate :ns-name sci-ns-name
                      :observe env-watch :derive? true :settle? true}
                     request
                     {:ctx ctx :mode mode
                      :delta (schema/begin-registration-delta (db/carried-projection db))
                      :memo (volatile! [nil nil])})
        diags (volatile! [])
        entries (volatile! [])
        effects (atom [])
        ordinal (volatile! 0)
        working (volatile! db)
        program (volatile! {})
        private (volatile! [])
        declared (volatile! [])
        touched-ns (volatile! #{})
        flushed-keys (volatile! #{})
        timings (volatile! {})
        clock (fn [k t] (vswap! timings update k (fnil + 0) (ms-since t)))
        projection-now (fn [] (if (seq (schema/changed-keys (:delta batch)))
                                (candidate-projection batch)
                                (db/carried-projection db)))
        stop (when-let [observe (:observe batch)]
               (observe ctx (fn [{:keys [op var] :as e}]
                              (let [e (assoc e :ordinal @ordinal)]
                                (if (= :bind op)
                                  (let [armed (guarded diags :arm nil (arm! ctx db (projection-now) var))]
                                    (swap! effects conj (cond-> (assoc e :sym (var-symbol var))
                                                          (:arm-error armed) (assoc :arm-error (:arm-error armed))))
                                    (:root armed))
                                  (do (swap! effects conj e) nil))))))
        final-state
        (try
          (schema/call-with-registration-delta
           (:delta batch)
           (fn []
             (sci/binding [sci/ns (sci/create-ns start-ns)]
               (loop [state {:version ((requiring-resolve 'forgive/version) text 0)
                             :structured? ((requiring-resolve 'forgive/structure?) text)
                             :repairs [] :last-code-end nil}]
                 (let [ns-before (guarded diags :ns-name start-ns ((:ns-name batch) batch))
                       tr (System/nanoTime)
                       r (guarded diags :read {:unread-from (:pos (:version state)) :v (:version state)
                                               :repairs (:repairs state) :reason :read-stage-failed}
                                  ((:read-next batch) batch state))
                       _ (clock :read tr)
                       cur-text (:text (:v r))]
                   (if (or (:eof r) (:unread-from r))
                     (assoc state :end r :text cur-text)
                     (let [;; Pending registrations reach the working value, in one apply,
                           ;; before the first form whose operator (SCI's resolved Var) is not
                           ;; a registration: registering reads no database facts.
                           op-var (when (and (seq? (:form r)) (symbol? (first (:form r))))
                                    (try (sci/resolve ctx (first (:form r))) (catch Throwable _ nil)))
                           registration? (and op-var (#{'seon.schema/register! 'seon.schema/unregister!}
                                                      (symbol (str (:ns (meta op-var))) (str (:name (meta op-var))))))
                           pending (remove @flushed-keys (schema/changed-keys (:delta batch)))
                           _ (when (and (:derive? batch) (seq pending) (not registration?))
                               (guarded diags :schema-flush nil
                                 (let [tf (System/nanoTime)
                                       cand (candidate-projection batch)
                                       tx (:tx (schema-part batch @working (set pending) true))
                                       nxt (speculative-with @working tx (#'eval/projection-state db cand "default"))]
                                   (vreset! working nxt)
                                   (vswap! flushed-keys into pending)
                                   (clock :schema-flush tf))))
                           effect-mark (count @effects)
                           forms-before @(:seon.schema.delta/candidate-forms (:delta batch))
                           te (System/nanoTime)
                           outcome (if (:refused r)
                                     {:error (ex-info "Only the REPL writes responses: this map was not evaluated." {:reason (:refused r)})}
                                     (try {:value (binding [db/*read-database* @working]
                                                    ((:evaluate batch) batch (:form r)))}
                                          (catch Throwable t {:error t})))
                           _ (clock :evaluate te)
                           [line column] (entry-line-column cur-text (:start r))
                           entry (merge {:ordinal @ordinal :source (str/trimr (subs cur-text (:start r) (:end r)))
                                         :line line :column column :ns-before ns-before}
                                        outcome)]
                       ;; Publish first: nothing after this line can lose the value.
                       (vswap! entries conj entry)
                       (let [ns-after (guarded diags :ns-name ns-before ((:ns-name batch) batch))
                             _ (vswap! entries update (dec (count @entries)) assoc :ns-after ns-after)
                             _ (vswap! touched-ns into [ns-before ns-after])
                             form-effects (subvec @effects effect-mark)
                             binds (vals (reduce (fn [m e] (if (= :bind (:op e)) (assoc m (:var e) e) m)) {} form-effects))
                             _ (vswap! declared into (map :sym binds))
                             forms-after @(:seon.schema.delta/candidate-forms (:delta batch))
                             form-keys (when-not (identical? forms-before forms-after)
                                         (set (filter #(not= (get forms-before % ::absent) (get forms-after % ::absent))
                                                      (into (set (keys forms-before)) (keys forms-after)))))
                             schema-moved? (boolean (seq form-keys))]
                         (when (and (:derive? batch) (or (seq form-effects) (and schema-moved? (seq (schema/changed-keys (:delta batch))))))
                           (guarded diags :derive nil
                             (let [td (System/nanoTime)
                                   joined (derive-form batch db (peek @entries) binds (vec (distinct (concat @declared (map :sym binds)))))]
                               (doseq [{:keys [sym row join]} joined]
                                 (if row
                                   (vswap! program assoc sym row)
                                   (do (vswap! program assoc sym ::private)
                                       (vswap! private conj {:sym sym :join join}))))
                               (doseq [{:keys [op ns sym]} form-effects :when (= :unmap op)]
                                 (vswap! program assoc (symbol (str ns) (str sym)) ::removed))
                               (vswap! touched-ns into (keep #(some-> % :row :seon.fn/ns second) joined))
                               (clock :derive td)
                               (let [ts (System/nanoTime)
                                     form-program (select-keys @program (concat (map :sym joined)
                                                                                 (for [e form-effects :when (= :unmap (:op e))]
                                                                                   (symbol (str (:ns e)) (str (:sym e))))))
                                     ;; Program rows are threaded into the working value. A
                                     ;; registration is visible to later forms through the batch's
                                     ;; registration delta (its candidate projection: arming,
                                     ;; contracts, seon.schema reads); its schema entity row lands in
                                     ;; the one commit. Threading schema rows re-derives the
                                     ;; database's projection per value (~80-140 ms per form, measured).
                                     tx (into (vec (for [[sym v] form-program :when (= ::removed v)
                                                         :let [id (dbu/entid @working [:seon.fn/sym sym])]
                                                         :when id]
                                                     [:db/retractEntity id]))
                                              (mapcat (fn [[_ v]] (when (map? v) (speculative-row-tx @working v))))
                                              form-program)
                                     nxt (if (empty? tx)
                                           @working
                                           (speculative-with @working tx
                                                             (#'eval/projection-state db (projection-now) "default")))]
                                 (if (:seon.error/at nxt)
                                   (vswap! diags conj {:seon.error/message (str "speculative apply refused: " (:seon.error/message nxt))
                                                       :one-path/stage :speculative})
                                   (vreset! working nxt))
                                 (clock :speculative ts)))))
                         (vswap! ordinal inc)
                         (recur (assoc state :version (:v r) :repairs (:repairs r) :last-code-end (:end r)))))))))))
          (catch Throwable t (vswap! diags conj (diagnostic :batch t)) nil))
        _ (when stop (stop))
        rows (into [] (keep (fn [[_ v]] (when (map? v) v))) @program)
        uncontracted (->> rows
                          (filter #(and (:seon.fn/sym %) (not (:seon.fn/spec %))
                                        (not (:seon.fn/macro? %)) (not (:seon.fn/host-bound? %))
                                        (fn? (some-> (sci/resolve ctx (:seon.fn/sym %)) deref))))
                          (mapv :seon.fn/sym))
        prior (if (and (:derive? batch) (seq uncontracted))
                (guarded diags :contract-facts []
                  (db/q '[:find [?s ...] :where [?f :seon.fn/sym ?s] [?f :seon.schema.admission/source :agent]
                          (not [?f :seon.fn/spec _])] db))
                [])
        contract (cond (empty? uncontracted) nil
                       (and (= 1 (count uncontracted)) (empty? prior))
                       {:seon.eval/contract-note (str (first uncontracted) " has no :malli/schema; specify it before proposing a merge.")}
                       :else {:seon.eval/contract-warning (vec (distinct (concat uncontracted prior)))})
        ts (System/nanoTime)
        final (when (and (:settle? batch) (or (seq @program) (seq (schema/changed-keys (:delta batch)))))
                (guarded diags :settle nil
                  (let [{:keys [tx notes]} (program-tx batch ctx db @program
                                                       (distinct (concat (filter some? @touched-ns)
                                                                         (keep #(some-> % :seon.fn/ns second) rows))))
                        after (if (seq tx) (db/with db tx) db)]
                    {:tx-count (count tx) :after after :notes notes})))
        _ (clock :settle ts)
        tp (System/nanoTime)
        reply {:values (mapv (fn [e]
                               (cond (contains? e :value)
                                     (try (binding [*print-length* 8 *print-level* 3] (pr-str (:value e)))
                                          (catch Throwable t
                                            (vswap! diags conj (diagnostic :print t))
                                            (str "#<unprintable " (.getName (class (:value e))) ">")))
                                     (:error e) (str "ERR " (ex-message (:error e)))))
                             @entries)
               :repairs (:repairs (:end final-state))
               :unread (when-let [u (:unread-from (:end final-state))]
                         {:from u :reason (:reason (:end final-state))})
               :private (mapv :sym @private)
               :schema-notes (:notes final)
               :contract contract
               :diagnostics (into (mapv :seon.error/message @diags) (keep :arm-error @effects))
               :commit (cond (nil? final) nil
                             (:seon.error/at (:after final)) {:refused (:seon.error/message (:after final))}
                             :else {:applied true :tx-count (:tx-count final)})}]
    (clock :print tp)
    {:reply reply :entries @entries :effects @effects :program @program :working @working
     :after (:after final) :text (:text final-state)
     :timings @timings :total-ms (ms-since t0)}))
```

## Appendix C. The forgiving input stage (`tmp/one-path/forgive.clj`, as run)

```clojure
(ns forgive
  "The forgiving input stage in front of the one reader (probe).

  One reader cursor per text version. `next-form` reads ONE form at the
  cursor. Only when that read fails, or, in a model reply, when a bare token
  does not stand alone on its line, does it repair the text at that point and
  read again from the same offset:

    prose line          -> `; prose` comment line (a readable code suffix on
                           the line stays code, on its own line)
    prompt marker       -> removed (`user=> (f 1)`)
    unbalanced closers  -> Parinfer indent mode, accepted only when the rest
                           of the text then reads without error
    anything else       -> unread from here, as data

  A repair makes a new text version (new line index, new cursor advanced to
  the offset): cost is proportional to the text once per repair, never per
  form. The repaired text is what is evaluated, stored and rendered."
  (:require [clojure.string :as str]
            [clojure.tools.reader.reader-types :as rt]
            [parinferish.core :as parinferish]
            [sci.core :as sci]))

(defn- line-starts [^String text]
  (into [0] (keep-indexed (fn [i ch] (when (= \newline ch) (inc i)))) text))

(defn version
  "A text version: the text, its line index and a reader cursor at `pos`."
  [^String text pos]
  (let [rdr (sci/source-reader text)]
    (dotimes [_ pos] (rt/read-char rdr))
    {:text text :starts (line-starts text) :rdr rdr :pos pos}))

(defn- line-bounds [^String text pos]
  (let [s (inc (.lastIndexOf text "\n" (int (max 0 (dec pos)))))
        e (let [i (.indexOf text "\n" (int pos))] (if (neg? i) (count text) i))]
    [s e]))

(defn- fence-line? [line]
  (let [l (str/triml line)] (or (str/starts-with? l "```") (str/starts-with? l "~~~"))))

(defn skip-to
  "Offset of the next form start from `pos`: past whitespace, commas, `;`
  comments and Markdown fence lines at a line's start."
  [^String text pos]
  (loop [p pos]
    (if (>= p (count text))
      p
      (let [c (.charAt text p)
            [ls le] (line-bounds text p)]
        (cond
          (or (Character/isWhitespace c) (= \, c)) (recur (inc p))
          (= \; c) (recur le)
          (and (str/blank? (subs text ls p)) (fence-line? (subs text ls le))) (recur le)
          :else p)))))

(defn- advance
  "The version with its cursor moved forward to `pos`."
  [v pos]
  (dotimes [_ (- pos (:pos v))] (rt/read-char (:rdr v)))
  (assoc v :pos pos))

(defn- read-one
  "Read one form at the cursor. {:form :end :v} or {:eof} or {:error}. A read
  that does not advance is an error, so every loop over reads terminates."
  [ctx opts v]
  (let [eof (Object.)
        {:keys [text starts rdr pos]} v]
    (try
      (let [form (sci/parse-next ctx rdr (assoc opts :eof eof))]
        (if (identical? eof form)
          {:eof true}
          (let [l (sci/get-line-number rdr) c (sci/get-column-number rdr)
                end (min (count text) (+ (get starts (dec l) (count text)) (dec c)))]
            (if (> end pos)
              {:form form :end end :v (assoc v :pos end)}
              {:error (ex-info "The reader did not advance." {:pos pos})}))))
      (catch Throwable t {:error t}))))

(defn- structured? [form ^String source]
  (or (coll? form) (str/starts-with? source "#")))

(defn- code-line? [line]
  (boolean (re-find #"^[\(\[\{\#'`~@^]" (str/triml line))))

(defn- reads-clean?
  "Whether all of `text` reads to forms without an error (a repair's check)."
  [ctx opts text]
  (loop [v (version text 0)]
    (let [v (advance v (skip-to (:text v) (:pos v)))
          r (read-one ctx opts v)]
      (cond (:eof r) true
            (:error r) false
            :else (recur (:v r))))))

(defn- code-suffix
  "The offset, within `tail`, of a suffix whose every form is a collection
  (today's readable-code-suffix rule), or nil."
  [ctx opts ^String tail]
  (some (fn [i]
          (let [suffix (subs tail i)]
            (when (loop [v (version suffix 0) n 0]
                    (let [v (advance v (skip-to (:text v) (:pos v)))
                          r (read-one ctx opts v)]
                      (cond (:eof r) (pos? n)
                            (:error r) false
                            (not (coll? (:form r))) false
                            :else (recur (:v r) (inc n)))))
              i)))
        (keep-indexed (fn [i ch] (when (and (pos? i) (#{\( \[ \{} ch)) i)) tail)))

(defn- comment-rest-of-line
  "The text with everything from `start` to the end of its line made a `;`
  comment, keeping (when `keep-suffix?`) a structured code suffix as code on
  its own line. The commented text begins with `;`, so the next read advances."
  [ctx opts ^String text start keep-suffix?]
  (let [[_ le] (line-bounds text start)
        tail (subs text start le)
        suffix (when keep-suffix? (code-suffix ctx opts tail))
        prose (str/trim (if suffix (subs tail 0 suffix) tail))]
    {:text (str (subs text 0 start) "; " prose (when suffix (str "\n" (subs tail suffix))) (subs text le))
     :repair {:kind :prose :text prose}}))

(defn- delimiter-repair
  "Parinfer indent mode over the rest of the text, accepted only if it changed
  the text and the result reads clean."
  [ctx opts ^String text pos]
  (let [rest-text (subs text pos)
        parsed (parinferish/parse rest-text {:mode :indent})
        candidate (parinferish/flatten parsed)]
    (when (and (not (:error? (meta parsed))) (not= candidate rest-text)
               (reads-clean? ctx opts candidate))
      {:text (str (subs text 0 pos) candidate)
       :repair {:kind :delimiters :at pos}})))

(def ^:private prompt #"^[A-Za-z][\w.\-]*=>[ \t]*")

(defn structure?
  "Whether a reply holds any line that begins with a collection: then a bare
  token alone on its line is code. One linear pass per reply."
  [^String text]
  (boolean (some #(re-find #"^[\(\[\{]" (str/triml %)) (str/split-lines text))))

(def ^:private prose-read-failure #"^Invalid (?:number|symbol|keyword|token)")

(defn next-form
  "The next form: {:form :start :end :v :repairs :line-code?}, {:eof :v
  :repairs} or {:unread-from :v :repairs :reason}. `mode` is :reply or :code.
  `last-code-end` is the end offset of the previous code form, or nil."
  [ctx opts mode structured-reply? v repairs last-code-end]
  (loop [v v repairs repairs guard 0]
    (let [text (:text v)
          start (skip-to text (:pos v))
          v (advance v start)
          [ls le] (line-bounds text start)
          begins-line? (str/blank? (subs text ls start))
          follows-code? (and last-code-end (<= last-code-end start)
                             (str/blank? (subs text last-code-end start))
                             (not (str/includes? (subs text last-code-end start) "\n")))
          marker (when begins-line? (re-find prompt (subs text start le)))
          step
          (cond
            (> guard 64) {:unread-from start :v v :repairs repairs :reason :too-many-repairs}
            (>= start (count text)) {:eof true :v v :repairs repairs}
            marker [:redo (str (subs text 0 start) (subs text (+ start (count marker)))) start {:kind :prompt-marker}]
            :else
            (let [r (read-one ctx opts v)
                  source (when (contains? r :form) (subs text start (:end r)))]
              (cond
                (:eof r) {:eof true :v v :repairs repairs}
                ;; Only the REPL writes responses: a map naming :seon.repl/*
                ;; keys is fabricated output, returned unread (today's rule).
                (and (map? (:form r)) (some #(and (keyword? %) (= "seon.repl" (namespace %))) (keys (:form r))))
                {:form (:form r) :start start :end (:end r) :v (:v r) :repairs repairs
                 :refused :fabricated-response}
                (contains? r :form)
                (if (or (= :code mode)
                        (and (structured? (:form r) source) (or begins-line? follows-code?))
                        (and structured-reply? begins-line?
                             (= (str/trim (subs text ls le)) (str/trim source))))
                  {:form (:form r) :start start :end (:end r) :v (:v r) :repairs repairs}
                  (let [{t :text rep :repair} (comment-rest-of-line ctx opts text start true)]
                    [:redo t start rep]))
                :else
                (let [msg (str (ex-message (:error r)))
                      delimiter? (re-find #"EOF while reading|Unmatched delimiter|expected .* to match|Unexpected EOF" msg)
                      fixed (when delimiter? (delimiter-repair ctx opts text start))]
                  (cond
                    fixed [:redo (:text fixed) start (:repair fixed)]
                    (and (= :reply mode) (re-find prose-read-failure msg)
                         (not (code-line? (subs text ls le))))
                    (let [{t :text rep :repair} (comment-rest-of-line ctx opts text start true)]
                      [:redo t start rep])
                    :else {:unread-from start :v v :repairs repairs :reason msg})))))]
      (if (vector? step)
        (let [[_ t at rep] step] (recur (version t at) (conj repairs rep) (inc guard)))
        step))))

(defn forms
  "Every form of `text` through `next-form` (read only, no evaluation)."
  [ctx opts mode text]
  (let [s? (structure? text)]
    (loop [v (version text 0) out [] repairs [] last-end nil]
      (let [r (next-form ctx opts mode s? v repairs last-end)
            t (:text (:v r))]
        (cond
          (:eof r) {:forms out :text t :repairs (:repairs r)}
          (:unread-from r) {:forms out :text t :repairs (:repairs r)
                            :unread-from (:unread-from r) :reason (:reason r)}
          :else (recur (:v r) (conj out (str/trimr (subs t (:start r) (:end r)))) (:repairs r) (:end r)))))))
```

## Appendix D. Entrant stage values (`tmp/one-path/entrants.clj`, as run)

```clojure
(ns entrants
  "Every entrant is `pipeline/run` with different stage values (probe)."
  (:require [clojure.string :as str]
            [sci.core :as sci]))

(defn- run [request] ((requiring-resolve 'pipeline/run) request))

;;; JVM: Clojure's reader at the live *ns*, host eval; no SCI observation,
;;; nothing derived or settled (host Vars are not program entities).

(defn- jvm-read-next [_ {:keys [version]}]
  (let [{:keys [text]} version
        pos ((requiring-resolve 'forgive/skip-to) text (:pos version))
        rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (subs text pos)))
        eof (Object.)
        [form s] (try (read+string {:eof eof :read-cond :allow} rdr)
                      (catch Throwable t [::error (ex-message t)]))]
    (cond
      (identical? eof form) {:eof true :v version}
      (= ::error form) {:unread-from pos :v version :reason s}
      ;; read+string answers the form's text trimmed; reading starts at the
      ;; skipped offset, so the form spans [pos, pos + |text|).
      :else (let [end (+ pos (count s))]
              {:form form :start pos :end end :v (assoc version :pos end)}))))

(defn jvm-batch
  [db text start-ns]
  (let [n (create-ns start-ns)]
    (binding [*ns* n]
      (refer-clojure)
      (run {:db db :text text :start-ns start-ns :ctx ::none
            :read-next jvm-read-next
            :evaluate (fn [_ form] (eval form))
            :ns-name (fn [_] (ns-name *ns*))
            :observe nil :derive? false :settle? false}))))

;;; Test body: the evaluator stage is the resolved body; one entry.

(defn thunk-batch
  [db body]
  (run {:db db :text "(body)" :ctx ::none
        :read-next (fn [_ {:keys [version]}]
                     (if (zero? (:pos version))
                       {:form ::body :start 0 :end 6 :v (assoc version :pos 6)}
                       {:eof true :v version}))
        :evaluate (fn [_ _] (body))
        :ns-name (fn [_] 'user)
        :observe nil :derive? false :settle? false}))

;;; Render: the viewer's context forked, custody a database VALUE (no
;;; connection, so no writer), definitions observed and reported, nothing
;;; derived or settled.

(defn render-batch
  [db viewer-ctx text]
  (let [out (run {:db db :text text :ctx (sci/fork viewer-ctx) :mode :code
                  :derive? false :settle? false})]
    (update-in out [:reply :diagnostics] into
               (for [e (:effects out) :when (= :bind (:op e))]
                 (str "a render form defined " (:sym e) "; renders are read-only: it was discarded with the fork")))))
```

## Appendix E. The SCI fork patch (`tmp/one-path/sci-observer.diff`, +38/-12)

```diff
diff -ru reference-code/sci/src/sci/impl/analyzer.cljc tmp/one-path/sci-patched/src/sci/impl/analyzer.cljc
--- reference-code/sci/src/sci/impl/analyzer.cljc	2026-09-26 11:52:26.343481224 -0600
+++ tmp/one-path/sci-patched/src/sci/impl/analyzer.cljc	2026-09-27 13:44:32.740878480 -0600
@@ -872,9 +872,15 @@
               m (if m-needs-eval?
                   (analyze ctx m)
                   (->constant m))
-              file @utils/current-file]
+              file @utils/current-file
+              origin (if expr-loc?
+                   {:line (:line expr-loc) :column (:column expr-loc)
+                    :end-line (:end-row expr-loc) :end-column (:end-col expr-loc)
+                    :authored true}
+                       (assoc (select-keys utils/*top-level-location* [:line :column])
+                              :generated true))]
           (sci.impl.types/->Node
-           (eval/eval-def ctx bindings var-name init m file)
+           (eval/eval-def ctx bindings var-name init m file origin)
            nil))))))
 
 #_(defn analyze-defn [ctx [op fn-name & body :as expr]]
@@ -2098,12 +2104,14 @@
                                 v (if (seq? v)
                                     (with-meta v (merge m (meta v)))
                                     v)
+                                _ (when-let [observe (:macro-expansion-observer ctx)]
+                                    (observe expr v))
                                 expanded (cond (:sci.impl/macroexpanding ctx) v
                                                (and top-level? (seq? v) (= 'do (first v)))
                                                ;; hand back control to eval-form for
                                                ;; interleaved analysis and eval
                                                (t/->EvalForm v)
-                                               :else (analyze ctx v top-level?))]
+                                               :else (with-top-level-loc true (meta v) (analyze ctx v top-level?)))]
                             expanded)
                           (let [rest-forms #?(:cljd (desugar-named-args (rest expr))
                                               :default (rest expr))]
diff -ru reference-code/sci/src/sci/impl/evaluator.cljc tmp/one-path/sci-patched/src/sci/impl/evaluator.cljc
--- reference-code/sci/src/sci/impl/evaluator.cljc	2026-08-02 16:20:38.076485977 -0600
+++ tmp/one-path/sci-patched/src/sci/impl/evaluator.cljc	2026-09-27 13:44:04.526622564 -0600
@@ -23,7 +23,7 @@
      syntax-quote})
 
 (defn eval-def
-  [ctx bindings var-name init m file]
+  [ctx bindings var-name init m file origin]
   (let [init (types/eval init ctx bindings)
         m (types/eval m ctx bindings)
         m (assoc m :name var-name :file file)
@@ -44,9 +44,11 @@
                        (lang/->Var @prev var-name m false false nil (:ns m))
 
                        :else prev)
-                v (do (when-not (identical? utils/var-unbound init)
-                        (vars/bindRoot prev init))
-                      (utils/reset-meta!* prev m)
+                v (do (utils/reset-meta!* prev m)
+                      (when-not (identical? utils/var-unbound init)
+                        #?(:clj (binding [lang/*def-origin* origin]
+                                  (vars/bindRoot prev init))
+                           :default (vars/bindRoot prev init)))
                       prev)
                 the-current-ns (assoc the-current-ns var-name v)]
             (assoc-in env [:namespaces cnn] the-current-ns)))
diff -ru reference-code/sci/src/sci/impl/namespaces.cljc tmp/one-path/sci-patched/src/sci/impl/namespaces.cljc
--- reference-code/sci/src/sci/impl/namespaces.cljc	2026-09-26 08:25:15.586811066 -0600
+++ tmp/one-path/sci-patched/src/sci/impl/namespaces.cljc	2026-09-27 13:44:04.527454904 -0600
@@ -585,6 +585,8 @@
                                   ;; nil marks the imported class as unmapped
                                   (update the-ns-map :imports assoc sym nil)
                                   :else the-ns-map)))))))
+  #?(:clj (when-some [observe sci.lang/*write-observer*]
+            (observe {:op :unmap :ns (sci-ns-name* (store/get-ctx) sci-ns) :sym sym})))
   nil)
 
 (defn sci-ns-unalias [sci-ns sym]
@@ -605,6 +607,8 @@
   (let [ctx (store/get-ctx)
         env (:env ctx)]
     (swap! env update :namespaces dissoc sym)
+    #?(:clj (when-some [observe sci.lang/*write-observer*]
+              (observe {:op :remove-ns :ns sym})))
     nil))
 
 (defn sci-intern
@@ -634,6 +638,8 @@
          (let [var-name (symbol (str ns-name) (str var-sym))
                new-var (sci.impl.utils/new-var var-name val var-meta)]
            (swap! env assoc-in [:namespaces ns-name var-sym] new-var)
+           #?(:clj (when-some [observe sci.lang/*write-observer*]
+                     (observe {:op :bind :var new-var :root val})))
            new-var)))))
 
 (defn sci-bound?
diff -ru reference-code/sci/src/sci/impl/protocols.cljc tmp/one-path/sci-patched/src/sci/impl/protocols.cljc
--- reference-code/sci/src/sci/impl/protocols.cljc	2026-07-18 05:58:20.100077249 -0600
+++ tmp/one-path/sci-patched/src/sci/impl/protocols.cljc	2026-09-27 13:58:24.472287900 -0600
@@ -107,11 +107,13 @@
                                                                 ~(list 'var fq-protocol-name) " found for: "
                                                                 (clojure.core/protocol-type-impl x#)))))))))]
                       `(do
-                         ~@impls
-                         (clojure.core/alter-var-root (var ~protocol-name)
-                                                    update :methods conj ~method-name))))
+                         ~@impls)))
                   signatures
                   )
+           ;; The protocol's methods are part of its one definition: a final
+           ;; def (not a later root write) so its origin is the declaration's.
+           (def ~(with-meta protocol-name {:doc docstring})
+             (update ~protocol-name :methods into [~@(map first signatures)]))
            ~(list 'quote protocol-name))]
     expansion))
 
diff -ru reference-code/sci/src/sci/impl/records.cljc tmp/one-path/sci-patched/src/sci/impl/records.cljc
--- reference-code/sci/src/sci/impl/records.cljc	2026-07-18 05:58:20.100290872 -0600
+++ tmp/one-path/sci-patched/src/sci/impl/records.cljc	2026-09-27 13:57:55.428831954 -0600
@@ -448,7 +448,7 @@
          :implements ~(with-meta interfaces {:record true
                                              :method-counts method-counts})
          ~@methods)
-       (defn ~constructor-fn-sym
+       (defn ~(with-meta constructor-fn-sym {:sci.impl/member-of (list 'quote factory-fn-sym)})
          (~fields
           (~constructor-fn-sym ~@fields nil nil))
          ([~@fields meta# ext#]
diff -ru reference-code/sci/src/sci/lang.cljc tmp/one-path/sci-patched/src/sci/lang.cljc
--- reference-code/sci/src/sci/lang.cljc	2026-07-18 05:58:20.101066320 -0600
+++ tmp/one-path/sci-patched/src/sci/lang.cljc	2026-09-27 13:44:04.526190144 -0600
@@ -68,6 +68,12 @@
                  watches)))
   ref)
 
+#?(:clj (def ^:dynamic *write-observer*
+          "Execution-scoped observer of successful root writes and removals:
+          a fn of one event map, or nil." nil))
+#?(:clj (def ^:dynamic *def-origin*
+          "The executing def's authored origin while it binds its root." nil))
+
 (deftype ^{:doc "Representation of a SCI var, created e.g. with `(defn foo [])`
     The fields of this type are implementation detail and should not be accessed
     directly."}
@@ -98,7 +104,9 @@
     (let [old-root (.-root this)]
       (vars/with-writeable-var this meta
         (vars/bumping-set! root v))
-      (notify-watches this watches old-root v))
+      (notify-watches this watches old-root v)
+      #?(:clj (when-some [observe *write-observer*]
+                (observe {:op :bind :var this :root v :origin *def-origin*}))))
     ;; this is the return value for alter-var-root which should be the only place calling bindRoot directly
     v)
   (getRawRoot [_this]
```

## Appendix F. Conversion manifest at HEAD dd5dcaff7

kondo usages of every deleted Var (`tmp/one-path/manifest.edn`, 102 references, 20 files; 40 production, 62 test).

| file:line | retired name | inside | step |
|---|---|---|---|
| src/seon/cluster/agent.clj:667 | `planned-sources` | `segmenter` | S9 |
| src/seon/render/web.clj:1425 | `preview-sources` | `render-source-call` | S11 |
| src/seon/sci/eval.clj:1448 | `one-event` | `install-function-from-database!` | S12 |
| src/seon/sci/eval.clj:1921 | `one-event` | `install-row!` | S12 |
| src/seon/sci/eval.clj:1946 | `one-event` | `install-row!` | S12 |
| src/seon/sci/eval.clj:4613 | `row-backed?` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4625 | `bindings` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4639 | `unadmitted-definitions` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4696 | `one-event` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4705 | `context-capture` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4713 | `context-restoration` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4776 | `admit-program-change!` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4800 | `declared-row` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4823 | `top-level-do?` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4824 | `top-level-evaluation` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4826 | `context-capture` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4835 | `top-level-do?` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4840 | `unmap-row` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4856 | `context-capture` | `evaluate` | S14 |
| src/seon/sci/eval.clj:4891 | `bindings` | `evaluate` | S14 |
| src/seon/sci/reader.cljc:563 | `declaration-facts` | `read-events` | S15 (same-file edit) |
| src/seon/sci/reader.cljc:563 | `declaration-facts` | `read-events` | S15 (same-file edit) |
| src/seon/sci/reader.cljc:580 | `next-reading-context` | `read-events` | S15 (same-file edit) |
| src/seon/sci/reader.cljc:580 | `next-reading-context` | `read-events` | S15 (same-file edit) |
| src/seon/sci/reader.cljc:603 | `resolved-operation` | `reader-error` | S15 (same-file edit) |
| src/seon/sci/reader.cljc:603 | `resolved-operation` | `reader-error` | S15 (same-file edit) |
| src/seon/turn.clj:653 | `namespace-rows` | `source-rows` | S8 |
| src/seon/turn.clj:1062 | `analyze-settlement` | `receipt-settle-tx` | S8 |
| src/seon/turn.clj:1089 | `evaluated-namespaces` | `receipt-settle-batch-tx` | S8 |
| src/seon/turn.clj:1605 | `evaluated-namespaces` | `record-evaluated-call` | S8 |
| src/seon/turn.clj:1605 | `namespace-rows` | `record-evaluated-call` | S8 |
| src/seon/turn.clj:2025 | `planned-sources` | `declared-sources` | S9 |
| src/seon/turn.clj:2282 | `preview-sources` | `system-turn` | S11 |
| src/seon/turn.clj:4781 | `planned-sources` | `call-turn` | S9 |
| src/seon/turn.clj:5043 | `read-source` | `disposition-rule-error` | S9 |
| src/seon/turn.clj:5116 | `with-settled` | `evaluate-sources` | S9 |
| src/seon/turn.clj:5116 | `with-settled` | `evaluate-sources` | S9 |
| src/seon/turn.clj:5117 | `with-settled` | `evaluate-sources` | S9 |
| src/seon/turn.clj:5117 | `with-settled` | `evaluate-sources` | S9 |
| src/seon/turn.clj:5157 | `gate-function-install` | `evaluate-sources` | S9 |
| test/seon/ai_test.clj:676 | `sources` | `captured-run4-replies-stop-before-fabricated-responses` | S15 convert (table 7.4) |
| test/seon/ai_test.clj:774 | `sources` | `an-empty-normal-stop-is-a-reply-but-malformed-json-is-a-provider-refusal` | S15 convert (table 7.4) |
| test/seon/cluster/evaluate_sources_test.clj:76 | `planned-sources` | `ordered-evaluation-retains-one-explicit-basis-without-publication` | S15 convert (table 7.4) |
| test/seon/cluster/host_test.clj:544 | `planned-sources` | `a-refused-deletion-is-associated-only-with-the-one-evaluation-proposing-it` | S15 convert (table 7.4) |
| test/seon/cluster/prompt_test.clj:40 | `preview-sources` | `record-evaluation!` | S15 convert (table 7.4) |
| test/seon/cluster/reply_test.clj:42 | `sources` | `a-bare-token-on-its-own-line-is-a-form-as-clojure-main-reads-it` | S15 delete file |
| test/seon/cluster/reply_test.clj:44 | `sources` | `a-bare-token-on-its-own-line-is-a-form-as-clojure-main-reads-it` | S15 delete file |
| test/seon/cluster/reply_test.clj:46 | `sources` | `a-bare-token-on-its-own-line-is-a-form-as-clojure-main-reads-it` | S15 delete file |
| test/seon/cluster/reply_test.clj:50 | `sources` | `prompt-markers-are-removed-only-at-top-level-line-starts` | S15 delete file |
| test/seon/cluster/reply_test.clj:56 | `sources` | `prompt-markers-are-removed-only-at-top-level-line-starts` | S15 delete file |
| test/seon/cluster/reply_test.clj:67 | `sources` | `no-form-replies-tell-the-agent-what-to-send` | S15 delete file |
| test/seon/cluster/reply_test.clj:78 | `sources` | `sources` | S15 delete file |
| test/seon/cluster/reply_test.clj:151 | `sources` | `the-source-is-exactly-what-the-agent-wrote` | S15 delete file |
| test/seon/cluster/reply_test.clj:161 | `sources` | `the-source-is-exactly-what-the-agent-wrote` | S15 delete file |
| test/seon/cluster/reply_test.clj:165 | `parsed-events` | `crlf-events-stay-within-the-original-reply` | S15 delete file |
| test/seon/cluster/reply_test.clj:173 | `sources` | `crlf-events-stay-within-the-original-reply` | S15 delete file |
| test/seon/cluster/reply_test.clj:206 | `sources` | `forms-run-and-prose-becomes-source-comments` | S15 delete file |
| test/seon/cluster/reply_test.clj:217 | `sources` | `forms-run-and-prose-becomes-source-comments` | S15 delete file |
| test/seon/cluster/reply_test.clj:227 | `sources` | `forms-run-and-prose-becomes-source-comments` | S15 delete file |
| test/seon/cluster/reply_test.clj:244 | `sources` | `attribution-follows-the-one-reader` | S15 delete file |
| test/seon/cluster/reply_test.clj:249 | `sources` | `attribution-follows-the-one-reader` | S15 delete file |
| test/seon/cluster/reply_test.clj:262 | `sources` | `attribution-follows-the-one-reader` | S15 delete file |
| test/seon/cluster/reply_test.clj:268 | `sources` | `attribution-follows-the-one-reader` | S15 delete file |
| test/seon/cluster/reply_test.clj:285 | `sources` | `every-declared-arity-satisfies-its-own-contract` | S15 delete file |
| test/seon/cluster/reply_test.clj:287 | `sources` | `every-declared-arity-satisfies-its-own-contract` | S15 delete file |
| test/seon/cluster/reply_test.clj:293 | `sources` | `every-declared-arity-satisfies-its-own-contract` | S15 delete file |
| test/seon/cluster/reply_test.clj:304 | `sources` | `refusals-satisfy-the-armed-reply-contract` | S15 delete file |
| test/seon/cluster/reply_test.clj:306 | `sources` | `refusals-satisfy-the-armed-reply-contract` | S15 delete file |
| test/seon/cluster/reply_test.clj:353 | `sources` | `a-forms-comment-is-only-the-prose-written-above-it` | S15 delete file |
| test/seon/cluster/reply_test.clj:359 | `sources` | `a-forms-comment-is-only-the-prose-written-above-it` | S15 delete file |
| test/seon/cluster/reply_test.clj:367 | `sources` | `read-errors` | S15 delete file |
| test/seon/cluster/reply_test.clj:427 | `sources` | `a-fenced-reply-retains-surrounding-prose-as-comments` | S15 delete file |
| test/seon/cluster/reply_test.clj:434 | `sources` | `tilde-fences-have-the-same-presentation-semantics` | S15 delete file |
| test/seon/cluster/reply_test.clj:458 | `sources` | `a-reply-of-provider-control-markup-refuses-instead-of-recording` | S15 delete file |
| test/seon/cluster/reply_test.clj:473 | `sources` | `every-plan-source-carries-a-reader-event` | S15 delete file |
| test/seon/cluster/turn_test.clj:2572 | `planned-sources` | `turn-intent-is-the-complete-crash-falsifier` | S15 convert (table 7.4) |
| test/seon/cluster/turn_test.clj:2642 | `sources` | `delimiter-repair-is-span-local-and-precedes-intent` | S15 convert (table 7.4) |
| test/seon/cluster/turn_test.clj:2663 | `sources` | `delimiter-repair-is-span-local-and-precedes-intent` | S15 convert (table 7.4) |
| test/seon/core_functions_test.clj:64 | `evaluate-candidate` | `auto-check-checks-the-function-instead-of-refusing-its-own-arguments` | S15 convert (table 7.4) |
| test/seon/loop_proof_test.clj:774 | `preview-sources` | `virtual-loop-end-to-end` | S15 convert (table 7.4) |
| test/seon/loop_proof_test.clj:790 | `planned-sources` | `virtual-loop-end-to-end` | S15 convert (table 7.4) |
| test/seon/loop_proof_test.clj:826 | `planned-sources` | `virtual-loop-end-to-end` | S15 convert (table 7.4) |
| test/seon/render/transcript_test.clj:1153 | `planned-sources` | `one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` | S15 convert (table 7.4) |
| test/seon/render_simplification_test.clj:764 | `preview-sources` | `authored-source-invocation-reuses-one-stored-run-across-presentations` | S15 convert (table 7.4) |
| test/seon/render_simplification_test.clj:787 | `preview-sources` | `authored-source-invocation-reuses-one-stored-run-across-presentations` | S15 convert (table 7.4) |
| test/seon/repl_parity_test.clj:520 | `sources` | `-` | S15 convert (table 7.4) |
| test/seon/sci/eval_test.clj:1184 | `one-event` | `unmap-entity-carries-the-exact-forked-namespace-state` | S15 convert (table 7.4) |
| test/seon/sci/eval_test.clj:1198 | `unmap-row` | `unmap-entity-carries-the-exact-forked-namespace-state` | S15 convert (table 7.4) |
| test/seon/sci/eval_test.clj:1238 | `one-event` | `declared-entity-evaluates-a-schema-once-inside-its-delta` | S15 convert (table 7.4) |
| test/seon/sci/eval_test.clj:1244 | `declared-row` | `declared-entity-evaluates-a-schema-once-inside-its-delta` | S15 convert (table 7.4) |
| test/seon/sci/restoration_test.clj:41 | `accept-candidate!` | `a-refused-batch-restores-the-context-that-accepted-a-candidate` | S15 delete file |
| test/seon/sci/restoration_test.clj:72 | `accept-candidate!` | `acceptance-that-throws-restores-the-evaluating-context` | S15 delete file |
| test/seon/sci/restoration_test.clj:92 | `apply-restoration!` | `a-var-redefined-then-removed-gets-its-captured-root-back` | S15 delete file |
| test/seon/sci/restoration_test.clj:93 | `program-restoration` | `a-var-redefined-then-removed-gets-its-captured-root-back` | S15 delete file |
| test/seon/sci/restoration_test.clj:109 | `restore-or-unavailable!` | `a-restoration-that-cannot-run-leaves-the-execution-unavailable` | S15 delete file |
| test/seon/sci/restoration_test.clj:116 | `restore-or-unavailable!` | `a-restoration-that-cannot-run-leaves-the-execution-unavailable` | S15 delete file |
| test/seon/test/accretion_test.clj:25 | `evaluate-candidate` | `candidate!` | S15 convert (table 7.4) |
| test/seon/test/accretion_test.clj:196 | `evaluate-candidate` | `a-definition-evaluates-once-and-its-gate-proves-that-root` | S15 convert (table 7.4) |
| test/seon/test/accretion_test.clj:211 | `refuse-install` | `a-definition-evaluates-once-and-its-gate-proves-that-root` | S15 convert (table 7.4) |
| test/seon/turn_test.clj:260 | `restore-or-unavailable!` | `a-refused-batch-whose-restoration-fails-answers-the-stored-restoration-error` | S15 convert (table 7.4) |
| test/seon/turn_test.clj:2215 | `preview-sources` | `a-replaced-result-is-shown-by-the-one-print-owner` | S15 convert (table 7.4) |
| test/seon/turn_test.clj:2251 | `planned-sources` | `a-reply-keeps-its-dispatch-macro-forms-as-code` | S15 convert (table 7.4) |

References kondo does not see (keywords, contracts, task text), converted by hand:

| Reference | Where (HEAD) | Step |
|---|---|---|
| `:seon.sci.eval/bindings` | `eval.clj:1985` (`transfer-evaluated-roots!`); `seon.sci.eval.edn:62,64`; `seon.turn.loop.edn:135,137` | S12 |
| `:seon.sci.eval/restore`, execution-unavailable error | `turn.clj:5260-5276` (`restore-evaluations!`), its call in `resume-turn` | S9 |
| Reader rereads of stored sources | `turn.clj` `source-events` :1975, `source-key` :1981, `read-only-evaluation?` :2076, `evaluable-source?` :2491 | S9 |
| `reply/sources`, `code-sources`, `planned-sources` in schema/task text | `seon.agent.edn:144`; `seon.turn.edn:154,215`; `seon.turn.loop.edn:181,182,303` | S15 |
| Task `:must-read` of deleted defns | `seon.sci.eval.edn:5,12,20,27,44,53,60,140,156,161,166,181`; `seon.sci.reader.edn:11,12`; `seon.turn.edn:5,19,61,62,81,82,127`; `seon.schema.edn:60,61` | S15 |
| `deleted-schema-key` (kept; callers survive) | `eval.clj:475,1931,2126,2128,3882,4869` | none: the defn is not deleted; :3882 and :4869 lie in deleted/rewritten bodies |

## Appendix G. Reproduce

```clojure
;; default, MCP eval_clj jvm (writes nothing: with-values only)
(load-file "/Users/sean/src/seon/tmp/one-path/forgive.clj")
(load-file "/Users/sean/src/seon/tmp/one-path/pipeline.clj")
(load-file "/Users/sean/src/seon/tmp/one-path/entrants.clj")
(load-file "/Users/sean/src/seon/tmp/one-path/proofs.clj")   ; writes tmp/one-path/proofs-results.edn
(load-file "/Users/sean/src/seon/tmp/one-path/corpus.clj")   ; writes tmp/one-path/results-v2.edn
```

```sh
# Scratch JVM (-Xmx512m), classpath = tmp/one-path/sci-patched/src ahead of the pinned SCI, plus clj-kondo:
#   (load-file "tmp/one-path/observe_scratch.clj")  -> observe-results.edn  (R1 table)
#   (load-file "tmp/one-path/origins_scratch.clj")  -> origins.edn          (every corpus bind's origin + expansions)
#   (load-file "tmp/one-path/join_proof.clj")       -> 101/101 exact join
#   (load-file "tmp/one-path/expand_scratch.clj")   -> h40-expansions.edn   (Q1)
# Deletion measurement: copy HEAD's four files to a root, then
bb tmp/one-path/delete_forms.bb <root> tmp/one-path/deletions.edn   # prints deletion-ranges.tsv
```
