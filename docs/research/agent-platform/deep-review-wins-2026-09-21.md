---
type: research
status: complete (deep review; justified wins integrated into plan/ at the same commit)
created: 2026-09-21
tags: [agent-platform, review, deletion, algorithm, wins]
---

# Deep review — the wins the earlier passes missed

Read end to end: `AGENTS.md`, the integrated README and all eight specs at
`4a6470532`, the second-perspective note, the synthesis, the goals note, the six
deletion audits, the seven data packs, the astra reviews and the fresh-start
landing note. Evidence gathered this session: `grep` censuses over `src/` and
`test/`, reads of the vendored seams named per row, and three read-only
`eval_clj` JVM evaluations against `default` (forms and values in §5). No
source, test, schema, operator, branch or JVM was touched; no suite ran.

The premise held: the plan is a 40 % cut because it deletes mirrors and keeps
the mechanisms that carried them. Every win below is a mechanism, not a file.

## 1. Wins, ranked by lines deleted per unit of proof

"Proof" is what must be shown before the deletion is safe, counted in probes
or regressions. Line counts are from `wc -l`/`grep` this session; "beyond plan"
is the delta against the owning spec's current target.

| # | Win | What data, when, where carried, what triggers work | Deletes (file:line, count) | Guarantee retained, where | Proof | Risk | Owner |
|---|---|---|---|---|---|---|---|
| 1 | **The projection has one transport: the database value.** Delete the ambient projection (`*projection*`, `*projection-state*`, `*candidate-forms-overlay*`, `*packaged-forms*` `schema.clj:891-895`; `call-with-projection`, `call-with-projection-state`, `call-with-forms` `:1126-1150`; `handed-projection`/`current-projection`/`activate!`/`snapshot`/`entity-catalog`/`current-keys` `:3309-3365`; the registration delta and `restore!`/`register-all!`/`clear-all!` `:3366-3480`, `:3901`) | Computed once at publication/boot, carried on the database value (`db.clj:249-292`, read at `:1219`); every reader takes the value as an argument. Nothing recomputed; nothing bound per thread | ≈ 350 lines in `schema.clj`; 75 call sites in 25 `src` files (census §5.2); the `(or (db/carried-projection db) (schema/handed-projection))` arms at 12 sites; 99 test files convert mechanically | §2.1 by construction: no call can validate against a projection other than the value's own. An absent projection refuses by name (`refuse-projection-source` `:2737`) | HEAD loads; `grep -c 'call-with-projection\|handed-projection\|current-projection' src` = 0; one regression: a raw `datahike.api/db` value refuses ordinary use by name | `call-with-projection-state` sites bind an atom so in-writer transaction functions decode EDN against the caller's basis (`cluster.clj:3240-3275`); that need dies with A2 c2 (no codec). Order: after c2 | A1 (A1-3/A1-4 slice) |
| 2 | **Delete the schema-shape family.** After A1-1b (the wrapper reads `mr/schema`) and A1-6 (plans off the compiled contract) no reader of `:seon.schema.shape/*` rows survives: `call_preparation.clj` 36 refs (A1-6), `instrument.clj:714,725` (A1-1b), `fn.clj:2686-2694` (`backfill-contract-facts!`, B1 commit 3), `fn.clj:2593,2631` (index-time consistency of the rows themselves), `db.clj:4102` (the validator's affected-root list). `:seon.schema.shape/error` mentions are union keys, not reads | Shape rows are a stored structural mirror of compiled Malli schemas the registry already holds; written per arity at `program.cljc:718-727` and per schema at `:840`. With no reader, nothing is computed | `fn/schema_shape.clj` 468 (keep `typed-key-facts`, `program.cljc:443`, ≈ 40 lines); `schema.clj` `shape-projections`/`replace-shape-rows`/`shape-row-in`/`:2201-2235`, `:2495-2505`, `:1860-1890` ≈ 150; `resources/seon/schemas/seon.schema.shape.edn`; `:seon.fn.arity/{input,output,guard}-schema` and `:seon.schema/shape` attributes; **5,154 entities ≈ 30 K datoms (≈ 7 % of the 445,030 on `default`)**; **A1-7 (fingerprint normaliser, RESET) is withdrawn whole** | Structural questions are answered by the registry (`m/children`, `m/entries`, `m/-function-info`); key questions by `:seon.fn.arity/input-refs`/`output-refs` (1,247 rows, kept) | Reader census after A1-1b/A1-6 (§5.2) = 0; one regression: `my.program/reads-key` answers from `input-refs`; the reset republishes without the rows | RESET (attributes removed; batch 1) | A1, with B1 (`program.cljc` rows) |
| 3 | **One flock, the store's.** The root lifecycle lock (`operator/state.clj:436-800`: `try-file-lock`, lock slots, `await-lock-held-transition!` 90 lines, `with-lifecycle-lock!` 130 lines, holder files, `lifecycle-lock-timeout-ms`; `operator.clj:65`; the `:seon.operator.lock/*` schema) serialises operator commands across processes. After B1 every healthy command is one prepl request the JVM serialises itself; only cold `start` and `reset` cross processes, and the store's own `flock` (`store.clj:306-356`, 50 lines) already refuses a second JVM on the store | Exclusion is a fact of the OS lock the store holds for its lifetime; no holder file, no silence bound, no phase log | ≈ 365 lines `state.clj` + schema + the operator bound; B1 §2c keeps them today | A second `bin/seon start` on a locked store refuses with the holder's pid from the store lock; `reset --force` is down (exact pid) + delete + start | One drill: two concurrent starts on a scratch root → one JVM, one typed refusal | A publication's long hold was what the silence bound observed; that is now a bounded prepl phase (B1 §2d) | B1 |
| 4 | **The edit hook is one request.** `bin/seon-hook` (1,987 lines) runs clj-kondo in Babashka with `--cache false`, reconstructs `apply_patch` hunks (`:538-760`, 220 lines), diagnoses the cache (`:318-456`), walks the tree for shell writes (`:1688-1819`), and queues publication (`:1486-1665`). The JVM already holds clj-kondo with the project cache (`analyzer/analyze` over `::sources`), the program graph, the docstring facts and A1-9's pure admission | One prospective-edit request `{path bytes}` → findings; the hook parses the event, sends, prints | ≈ 1,300 lines beyond B1's 490: hook ≈ 150 for event/request/print (+ 220 patch reconstruction only if the payload lacks the file, + ≈ 300 for the asynchronous Gemini review batch, counted separately) | Blocking findings block; syntax refuses; shell writes are caught by the pathless request (B1 §2b); no JVM ⇒ "admission unavailable, start default" (B1 §2d already rules this for schema admission) | Two hook events: a syntax error refused by JVM findings; a `cat >` write caught by the pathless request | No live JVM ⇒ no lint. Owner option (§4 D-c) | B1 |
| 5 | **`call_preparation.clj` is one function over the compiled contract.** 5 supplier rows, 471 prepared symbols, 1,417 lines. Its parts: a state atom + carrier (`:53-113`), supplier coherence through shape rows (`:199-290`), a transaction-keyed snapshot with `watch!` (`:291-600`), eight Datalog structure queries (`:604-748`, A1-6), arity dispatch by count (`:749-1054`, `m/-function-info` gives min/max), and the hook (`:1099-1417`) | Plans: (symbol → arity → suppliable slots) derived once from `(mr/schema registry sym)` for the 5 supplier keys, carried on the projection; recomputed when a supplier row or a contract changes | ≈ 800 beyond A1's 1,100 → ≈ 300 (by reading the definition map, not every body) | Caller wins, required-entry match, fixed-arity precedence, ambiguous placement refused, variadic — the behaviour tests over `prepare`/`hook`/`supply` A1 §7 keeps | The retained behaviour tests | Estimate from structure; the lane recounts per commit | A1 |
| 6 | **`ai.clj` carries a kind stamp.** `:seon.ai/error-class` (`seon.ai.edn:71-82`) is a twelve-member enum computed by `disposition`/`cause-chain`/`caused-by?`/`truncation`/`streamed-completion` (`ai.clj:1155-1412`, ≈ 260 lines) from facts the error already carries (HTTP status, Throwable class, finish reason, whether the request was sent). Ruling D3: an entity is its attributes; the enum is the banned discriminator. Plus `retry-strategy`/`delays` (`:503-567`) beside B3's six retry dials, and `send-request` 178 lines | The provider error is one map: status, exception class, body excerpt, sent?, finish reason. Failover reads `sent?`; nothing classifies | ≈ 900 of 1,682 (B2 lists the file as untouched): the classifier, the enum, the retry ladder, the byte-identical 12-row tables in `cluster/turn_test.clj:2315` and `ai_test.clj:1333` | The turn's failover decision (one predicate: request sent?) and the recorded attempt facts | Three recorded provider outcomes (refused before send, HTTP error, truncated stream) settle through `record-attempt!` | Provider parsing is genuinely fiddly; the stream fold (`:762-931`) stays | B2, B3 (dials) |
| 7 | **`seon.db` re-decides Datahike's parser.** `query-call-valid?`/`query-guard-message` (`db.clj:1960-1993`), `query-input-*`/`aligned-query-arguments`/`missing-query-error`/`malformed-query-pattern-error`/`query-attribute-error`/`query-variable-attributes`/`query-find-attributes`/`lookup-ref-error`/`unknown-attribute-error` (`:1386-1610`, `:1865-1993`), `pull-call-valid?` (`:2400-2412`), `datoms-call-valid?` (`:2615-2627`) pre-check inputs that Datahike's `normalize-q-input`, parser and attribute check refuse themselves with typed `ex-info`; `q` already ends in `(catch Throwable cause (dependency-error ::q cause))` | The one `catch` translates Datahike's refusal into the flat error once; the Malli input contract on `q`/`pull`/`datoms` refuses shape | ≈ 450 lines (A2 target 5,600 → ≈ 5,150) | Every refusal still names operation, layer and offending input; Datahike's own `:type`/`:error` rides in `:seon.error/cause` | A table of the eight pre-checked inputs: Datahike throws on each, or the pre-check stays for that one | The nicer sentence (`query-argument-message`) is lost; owner law prefers the dependency's own diagnostic | A2 |
| 8 | **A candidate is a handle, not a cluster.** `graph-definition` (`cluster/agent.clj:469`) is a pure function of `(agent-id, handle)`; the handle already carries `:seon.db/connection`, `:seon.sci.eval/ctx`, `:seon.env/environment`, `:seon.flow/executor`, `:seon.agent/context-state` (§5.1). Cluster start additionally stands store, source base, config, recovery, prepl, web, search, error fanout and graphs (`cluster.clj:3187-3276`): measured `default` fork 9,924 ms + start 15,725 ms (fresh-start note) | A candidate = `registry/branch!` off the immutable commit (0.34 s, landed) + `open-branch!` + `(sci/fork base-ctx)` (0.007 ms) + `arm!` with a handle whose connection is the branch; faults commit on the fault's own scoped connection (`env/carry` already stamps it on every proc) | The per-candidate boot D1 §2a prices ("complete measured startup"): ≈ 25 s and a lifecycle class per task | Datoms/schema/writes isolated by the branch; private layer by the fork; agent facts on the candidate branch; one fault committer per handle or the committer writes on the fault's connection (`flow.clj:1051`) | One probe on a scratch cluster: branch, handle, arm one agent, one evaluation; facts only on the candidate branch; the shared page unchanged | The 2026-09-19 D5 ruling says "separate candidate clusters"; owner decides whether branch + handle in the hosting JVM satisfies it (§4 D-a) | D1 |
| 9 | **Malli fork: a refusing `:report`.** `-instrument-f` (`core.cljc:2201-2220`) calls `report` and then invokes `f` regardless. Add `:refuse` (a function of key and data returning a value): when it returns non-nil the wrapper returns it and never invokes `f` | The refusal is the wrapper's return value | ≈ 70 lines in `instrument.clj`: the marker object, `try`/`catch ExceptionInfo` rethrow-as-value (`:810-819`), the second frame; A1 C2's `:gen` trick becomes unnecessary (arity is dispatched at `:2203`) | Non-returning rejection; `:report` semantics upstream untouched | One fork test: refused input returns the value, `f` not called; refused output likewise | Fork divergence, eight lines | A1 |

Beyond the specs' ≈ 55,000-line sum these remove ≈ 5,000 more source/shell lines
(≈ 50,000) plus ≈ 30 K datoms and one RESET item. That is still not tenfold.
The remaining mass is `turn.clj`'s transaction-data builders, `render/web.clj`
and the indexer, as README §5 already says.

## 2. Wins looked for and not found

| Looked for | Evidence | Verdict |
|---|---|---|
| Dead legacy code in the corpus | test-system audit §3a: 4 lines; every "legacy spelling" is live in `src/` | none |
| `turn.clj` below B2's 3,700 | sampled `receipt-start-tx`/`receipt-row` (`:824-870`) and `record-attempt!` (`:3912-3960`): transaction-data builders with complete contracts, no mirror | not claimed |
| `print.cljc` 1,377 | elision values, requery forms, Hiccup tee — the one clipping grammar; no library equivalent | keep |
| The datom mass of `:seon.fn/{call-arities,calls,keywords,references}` (207 K of 445 K, 47 %) | each has a reader: the arity validator, reverse reach, `reads-key`'s mention group, `functions-using` | facts, keep; the reset transaction cost is the unknown (§3) |
| `reload-order` vs clj-reload | 25 lines, 0 cycles live; a dependency edge for 25 lines | keep (B1 already) |
| clj-kondo `:parallel` | B1 §2b names the per-file-group probe and the fidelity condition | already in plan |
| Boot 12.8–16.2 s | Clojure loading 373 namespaces; the only lever is AOT, which changes the reload model | out of scope, unverified |
| Datahike `merge!` expected basis, pull limit, `:db.type/any`, GC dry run, `txInstant` revision | all in A2/D1 | already in plan |
| The since-diff after acquisition reads are excluded | B2 commit 5 + A2 c1 reduce it to `read-evidence-current?` per distinct read | already in plan |

## 3. The three largest remaining unknowns

1. **The reset's 36,068 ms transaction of 107,049 operations.** Unattributed
   (B1 §2b). Four cardinality-many attributes hold 47 % of the datoms, and the
   final-report validator pulls every touched root with components inside the
   writer: on a reset every one of ≈ 35,000 rows is touched. The probe B1 names
   (construction, upsert/explode, index, validator, commit timed separately on
   one reset; `load-entities` as the non-equivalent lower bound) decides whether
   the target ≤ 60 s is reachable without changing the validator's shape.
2. **How much of the program an override interprets.** Measured this session
   (§5.3): the reverse call closure of `seon.error.refusal/diagnostic` is 1,902
   of 4,603 functions (41 %), `seon.id/id` 1,168, `seon.db/q` 1,116,
   `seon.db/transact!` 414; a leaf like `seon.cluster.message/send!` is 3. Under
   the owner's confirmed model (an unchanged compiled caller must not bypass an
   overridden callee) overriding a core leaf interprets a quarter to two fifths
   of the program in SCI. Two consequences are settled in the concerns note:
   contract-only changes (the first task class) must not count as body changes,
   and the closure size is reported on every override.
3. **There is no green baseline.** The last combined checkpoint recorded 186
   executed, 51 failures, 13 errors, and 38 reason-only long markers silently
   keep the 5 s default. The plan's commit gate (HEAD loads + one probe) makes
   the cuts survivable, but every "tests reaching my change" claim in the specs
   is unmeasured until the platform tier runs once.

## 4. Owner decisions — all ruled 2026-09-21

| # | Decision | Ruling | Rejected |
|---|---|---|---|
| D-a | Candidate shape (win 8) | branch + handle hosted by the cluster's JVM; a cluster stays the unit of a shared program and agent population, candidates merge back to its branch (primitives measured live: branch 74.65 ms, open 25.62 ms, fork 0.021 ms) | cluster per task (≈ 25 s each); two shapes |
| D-b | An affected caller that cannot be interpreted in SCI | refuse the override in that context, naming the host-bound caller; any first-party namespace may be overridden when its closure interprets cleanly; no roster. 15 of 109 source namespaces carry host-defining forms, mostly platform plumbing; agents' new functions over data have no compiled callers | typed `bypassing-callers` set; best-effort JVM fallback |
| D-c | The hook when no JVM is live (win 4) | refuse with the exact `bin/seon start` command; one lint path | Babashka fallback; silent skip |
| D-d | `:seon.ai/error-class` (win 6) | one declared error schema per failure extending `:seon.error/base`, each with its render pair; `seon.ai/complete`'s union names them; the enum and the retry ladder leave | keep the enum; read-time projection |
| D-e | Bounded completion (concerns note §2) | two declared bounds, the second waiting on the body's own exit signal (Codex: a cancelled Future reports done while the body runs), then abandon-and-disarm with the live thread recorded | the 252-line observer; an unbounded second wait |

Codex's REPL verification (`repl-verification-deep-review-2026-09-21.md`) also
corrected two of my claims and they stand corrected in the plan: a
contract-only change still needs its affected callers interpreted, because a
compiled caller bypasses the context's wrapper (the cost is install-time and
interpreted execution in that context, not a per-turn scan); and Datahike
returns an empty relation for an uninstalled query attribute, so Seon's
unknown-attribute check stays and win 7's 450 lines are conditional.
The design-ideas ledger is held by a concurrent Codex edit; these five rulings
are recorded here and in README §7 until that file is free.

## 5. Forms and values (read-only, `eval_clj` jvm, cluster `default`)

### 5.1 Handle and instance keys (191 ms)

`(let [inst (get @seon.operator.runtime/running-instances "default") cl (:seon.turn.loop/cluster inst) db (seon.db/db (seon.operator/connection "default"))] {:instance-keys (sort (keys inst)) :cluster-keys (sort (keys cl)) :shape-rows (seon.db/q '[:find (count ?e) . :where [?e :seon.schema.shape/fingerprint]] db) :arity-input-refs (seon.db/q '[:find (count ?e) . :where [?e :seon.fn.arity/input-refs]] db) :supplied-default-rows (seon.db/q '[:find (count ?e) . :where [?e :seon.call-preparation/key]] db) :datom-total (count (datahike.api/datoms db :eavt)) :top-attrs (take 12 (sort-by val > (frequencies (map :a (datahike.api/datoms db :eavt)))))})`

`:cluster-keys` `[:seon.agent/context-state :seon.cluster/name :seon.cluster.wake/channel :seon.config/on-core-error … :seon.db/connection :seon.db.process/id :seon.env/environment :seon.flow/executor :seon.flow/work-launcher :seon.sci.admit/caps :seon.sci.eval/ctx :seon.sci.eval/projection-state :seon.turn.loop/completion :seon.turn.loop/stream-channel]`;
`:instance-keys` add `:seon.boot/{advertisement,cluster-connection,config,config-result,executors,prepl-server,ready-ms,recovered-runs,recovery-operations} :seon.flow/{error-fanout,graph,work-launcher} :seon.render.web/{served,view} :seon.search/{completion,handle} :seon.store/store`;
`:shape-rows 5154 :arity-input-refs 1247 :supplied-default-rows 5 :datom-total 445030`;
`:top-attrs [[:seon.fn/call-arities 65918] [:seon.fn/calls 64465] [:seon.fn/keywords 44415] [:seon.fn/references 32706] [:seon.schema.admission/source 11853] [:seon.fn/file 7168] [:seon.fn/form-span 6761] [:seon.program/analyzed-source-digest 6761] [:seon.schema/references 5940] [:seon.schema.shape/fingerprint 5154] [:seon.schema.shape/form 5154] [:seon.schema.shape/comparison 5154]]`.

### 5.2 Censuses (grep over `src/`, this session)

- Ambient projection callers outside `schema.clj`: 75 lines in 25 files
  (`cluster.clj` 20, `render/web.clj` 7, `db.clj` 7, `test/runner.clj` 5,
  `sci/eval.clj` 5, `render/walk.clj` 5, `render.clj` 5, `test.clj` 3, …);
  99 test files name one of the same functions.
- `seon.schema.shape` mentions by file: `fn/schema_shape.clj` 76,
  `call_preparation.clj` 36, `error.clj` 8 (union keys), `instrument.clj` 4
  (two union keys, two fingerprint reads at `:714,725`), `render/value.clj` 2
  (union keys), `program.cljc` 2 (writers), `fn.clj` 1, `db.clj` 1,
  `sci/kernel.clj` 1, `sci/admit.clj` 1 (union keys).
- `:seon.fn/keywords` readers outside `fn.clj`: `turn.clj:1207`,
  `test/runner.clj:1222,2169,2187,2243` (the reach index B4 deletes).

### 5.3 Reverse function closures over `:seon.fn/calls` (313 ms)

`(let [db (seon.db/db (seon.operator/connection "default")) rows (seon.db/q '[:find ?caller ?callee :where [?e :seon.fn/sym ?caller] [?e :seon.fn/calls ?callee]] db) fns (set (seon.db/q '[:find [?s ...] :where [_ :seon.fn/sym ?s]] db)) incoming (reduce (fn [m [caller callee]] (update m callee (fnil conj #{}) caller)) {} rows) closure (fn [seed] (loop [pending [seed] seen #{}] (if-let [s (peek pending)] (if (seen s) (recur (pop pending) seen) (recur (into (pop pending) (get incoming s)) (conj seen s))) (count (filter fns seen)))))] {:functions (count fns) :edges (count rows) :reverse-function-closure (into (sorted-map) (map (fn [s] [s (closure s)]) '[…]))})`

`{:functions 4603 :edges 34363 :reverse-function-closure {seon.error.refusal/diagnostic 1902, seon.id/id 1168, seon.db/q 1116, seon.db/pull 889, seon.db/transact! 414, seon.schema/projection-from-database 392, seon.print/text-sink 286, seon.render.value/render-ai 171, seon.fn/gate-sets 166, seon.blob/put! 156, seon.render.ns/render-html 113, seon.turn/close-call 59, seon.ai/complete 45, seon.cluster.message/send! 3, seon.id/valid? 1}}`

Calls-only, lexical edges; declared dispatch (`:seon.fn/invokes`) would add to
each count. The seed itself is counted.
