---
type: prd
status: proposed
tags: [prd, runtime, sci, architecture]
---

# Splitting `seon.cluster.loop/turn` and `seon.sci.eval/evaluate`

Structure-only refactor proposal for the two functions the 2026-08-02
code-quality sweep ranked #5
(`research/code-quality-sweep-2026-08-02.md`, issue
`docs/seon/issues/runtime-turn-and-evaluate-kernels-conflate-boundaries.md`).

All line numbers are exact at `5c84e6e7e`, with both files clean in the
working tree.

## 0. Verdict up front

`turn` (`src/seon/cluster/loop.cljc:899-1521`, 623 lines) and `evaluate`
(`src/seon/sci/eval.clj:1229-1504`, 276 lines) do not need new namespaces,
new mechanisms, or a second orchestration entry. They need the same
treatment `terminal-tx`, `error-tx`, `settlement-result`,
`store-session-values!`, `record-attempt!` and `form-data` already got in
`loop.cljc`, and `arm`, `one-event`, `program-row`, `failure-value` and
`changed-session-defs` already got in `eval.clj`: named `defn-`s in the
same namespace, taking a map and returning a map or a vector of tx-data.

The proposal is 9 slices. 7 extract pure functions. 2 move existing code
without changing it (the `case` arms of `turn`, and the final assembly of
`evaluate`). No public contract, receipt attribute, schema, or observable
behavior changes anywhere in the wave.

## 1. Motivation — these are the contended files

The argument is not "623 lines is a lot". It is that on 2026-08-01 alone,
between 09:41 and 21:03, **22 commits** touched one or both of these two
files, and **five of them touched both in the same commit**:

| Commit | Subject | `loop.cljc` | `eval.clj` |
|---|---|---|---|
| `c6db32f56` | Make the eval door REPL-native: arity errors and bare dir/doc | — | +74 −14 |
| `5599d72b2` | Port seon.db q and pull into the fresh JVM | +3 −2 | — |
| `be37aac87` | Store oversized eval results as reachable blobs | +46 −4 | — |
| `88ebbde51` | Make SCI evaluation arms reload-safe | — | +18 −19 |
| `ac9de46b9` | Make the SCI program graph live per cluster | +9 −18 | +109 −60 |
| `91decd350` | Pass the database render page size at settlement | +12 −2 | — |
| `1376a601d` | Capture SCI print options on evaluations | — | +19 −5 |
| `d061a1cda` | Record SCI host interop analysis | — | +14 −2 |
| `92d2e39be` | Restore pure SCI session forms from facts | +83 −2 | +137 −8 |
| `78b1e6eca` | Restore faithful SCI session values first | +53 −9 | +69 −23 |
| `319fc6ccb` | Fail closed on unproven session form calls | +42 −23 | +37 −14 |
| `8e1ea52c2` | Fail closed on nondeterministic SCI replay | +19 −2 | +73 −16 |
| `d57f5977c` | Persist failed evaluation session deltas | +8 −2 | +22 −1 |
| `284c50338` | Restore session forms through the sealed reader | — | +2 −2 |
| `c1af16c89` | Persist model finish reasons on attempt receipts | +14 −3 | — |
| `8d251b76d` | Enforce contracts on interpreted functions | — | +32 −3 |
| `de7a01483` | Resolve AI settings once per turn | +22 −4 | — |
| `231bf5798` | Read effective config for interpreted contracts | — | +6 −4 |
| `406347c86` | Derive parsed contracts for runtime definitions | — | +11 −0 |
| `983121aa1` | Persist and stream model reasoning | +19 −0 | — |
| `45e7df901` | Seed agent bootstrap plans | — | +17 −5 |
| `2713fa643` | Add fact-space bootstrap drives | — | +12 −13 |

Read the columns. The session-image work (`92d2e39be` … `d57f5977c`) had
to edit both files five times because the *producer* of
`:seon.sci.eval/session-defs` is inside `evaluate`'s `try` body and the
*consumer* is inside `turn`'s `:resume` branch, ~200 lines into each. The
provider-evidence work (`c1af16c89`, `983121aa1`) and the settings work
(`de7a01483`) all landed inside `turn`'s `:call` branch. The contract work
(`8d251b76d`, `231bf5798`, `406347c86`) all landed inside `evaluate`'s
schema-declaration region. Three different concerns, one editable region
each, and the regions are nested inside a single `let` with no name.

The cost is concrete: a lane touching provider evidence and a lane
touching session-image replay serialize on `loop.cljc` even though they
share no data. That is what this refactor buys — not aesthetics, parallel
edit surface.

## 2. Inventory — what `turn` does today

`turn` is one `case` over `(:seon.cluster.work/situation work)` with four
arms and a `report` closure (`:899-922`).

| # | Responsibility (the code's own name) | Lines |
|---|---|---|
| 0 | bind `connection`, `process`, `agent-id`, `run-id`; build the `report` closure | 912-922 |
| **`:open`** — 24 lines | | 927-950 |
| 1 | `run/open-tx` + `run/claim-tx` in one transaction, trigger as `:tx-meta` | 928-943 |
| 2 | `refused!` → report | 946-950 |
| **`:call`** — 234 lines | | 975-1208 |
| 3 | read `db` once; `config/effective` → `ai/settings` → `ai/targets` → `ai/retry-strategy`; bind `primary`, `backup` | 976-989 |
| 4 | build the streaming `sink` over `:seon.cluster.loop/stream-channel` | 990-1004 |
| 5 | `fail!` — close the run with `:seon.cluster.run/error` and report | 1005-1022 |
| 6 | `freeze!` — `reply/sources`, then `run/plan-tx` + `refused!` | 1023-1050 |
| 7 | `prompt/prompt` inside a `try`, exception translated to a flat `::prompt-failed` value | 1060-1073 |
| 8 | `context/capture-tx` before the provider call | 1084-1093 |
| 9 | extract `:seon.cluster.prompt/text` | 1097 |
| 10 | derive the backoff `schedule` — empty when a `backup` exists | 1098-1103 |
| 11 | gate on `refused!` of the capture | 1104-1109 |
| 12 | the attempt `loop`: `ai/complete` | 1110-1123 |
| 13 | evidence extraction — `usage`, `reasoning-content`, `finish-reason`, each `(or completion (get-in [:seon.error/data …]))` | 1124-1135 |
| 14 | `ai/disposition` over the failure + backup availability | 1136-1143 |
| 15 | assemble the `record-attempt!` request `cond->` | 1144-1164 |
| 16 | the four-arm `cond`: `freeze!` / `fail!` / failover `recur` (with the `error/notice` → `render/render` system segment) / backoff `recur` (`Thread/sleep`) / `fail!` | 1165-1208 |
| **`:resume`** — 261 lines | | 1218-1478 |
| 17 | `requiring-resolve` the configured `evaluate`; wrap in `render/call-with-walk-context`; read `message/trigger` once; take `ctx` | 1219-1239 |
| 18 | the form `loop`: running-receipt transaction (`run/receipt-start-tx` + `:seon.problems/id`) and its `refused!` gate | 1243-1258 |
| 19 | `form-data`; resolve `evaluation-namespace`; `d/pull` the `:seon.ns` row | 1259-1271 |
| 20 | `lint-form` admission → `admitted-form` | 1272-1286 |
| 21 | assemble the `submit-evaluation!!` request map | 1287-1302 |
| 22 | `problems/form-problem` | 1303-1308 |
| 23 | `disposition` of the admitted value | 1309 |
| 24 | `asked` — `messages`, else `message/reply` when completed, else `problems/assignment-value` | 1310-1338 |
| 25 | `message/delivery` → `rows`; `:seon.error/values` → `refusals` via `error-tx` | 1339-1364 |
| 26 | `settlement-result`, then the 44-line `cond->` projecting `evaluation` + `problem` into the receipt request | 1365-1410 |
| 27 | `store-session-values!` | 1411-1412 |
| 28 | the ONE terminal transaction: `terminal-tx` + `rows` + `refusals` + `session-image-tx`, with the trigger `:tx-meta` | 1413-1438 |
| 29 | `sci.eval/install-program-row!` from `(:db-after outcome)` | 1439-1447 |
| 30 | `work/next-agent-work` → `next-ordinal` | 1448-1461 |
| 31 | the outcome `cond`: `terminal-refused!` / settled / `recur` / released | 1462-1478 |
| **`:close`** — 26 lines | | 1496-1521 |
| 32 | `d/pull` the holder; claim if not held; `run/close-tx`; `refused!` → report | 1497-1521 |

## 3. Inventory — what `evaluate` does today

`evaluate` is one `let` of six bindings, one `try` body of 25 bindings, one
`catch Throwable`, and a `finally`.

| # | Responsibility | Lines |
|---|---|---|
| 1 | select the ctx (`or ctx (build-base-ctx)`) — a supplied ctx is used as given | 1264 |
| 2 | `arm` it, destructuring `interrupt-fn`, `::stop!`, `::record`, `::built-in-calls` | 1265-1269 |
| 3 | allocate `printed`, resolve `namespace-name`/`namespace-object`, allocate the `ending-namespace`, `print-options`, `session-observation` volatiles | 1270-1277 |
| 4 | `reader-context` before, `one-event` (the ONE reader event), `sci/fork` when `:seon.sci.reader/ns-unmap?` | 1279-1286 |
| 5 | `intern-values` snapshot + record the `session-observation` for the catch | 1287-1293 |
| 6 | `sci/namespace-state` / `sci/namespace-interns` before, for the unmap path | 1294-1298 |
| 7 | the `eval-form!` closure: `sci/binding` of ns/out/err/print-length/print-level, `sci/eval-form`, `ending-namespace` update, and print-option capture in its own `finally` | 1299-1317 |
| 8 | select the schema `projection` (context, then current, then rebuild) | 1318-1321 |
| 9 | `program-row` → `raw-row`; `deleted-schema-key`; `schema/begin-registration-delta` | 1322-1326 |
| 10 | run `eval-form!` under the registration delta when there is one | 1327-1331 |
| 11 | verify the declaration/deletion registered its own reader identity, or throw `::schema-refused`; pure-validate the candidate projection | 1332-1367 |
| 12 | `program/with-contract-facts` when `:seon.fn/spec` is present | 1368-1378 |
| 13 | `evaluated-value`: declaration identity for a declared row (running `eval-form!` for a `live-declaration?`), otherwise `eval-form!` | 1379-1394 |
| 14 | unmap aftermath: `removed-program-identities`, `sci/namespace-state` after, `namespace-changed?`, `program/deletion-row` | 1395-1410 |
| 15 | `namespace-context-row` when no declaration row; assemble `row` with `::evaluated?` / `::namespace-state` | 1411-1425 |
| 16 | choose `value` (context-row name vs `evaluated-value`) | 1427 |
| 17 | `record :ok` — inside the boundary, before disarm, so a lazy sequence dies here | 1431 |
| 18 | `changed-session-defs` | 1432-1435 |
| 19 | `admit/admit` | 1436-1444 |
| 20 | the success return `cond->` | 1445-1458 |
| 21 | catch: `record` (`:time` vs `:error`), failed-path `changed-session-defs` from the `session-observation`, `failure-value`, `admit/admit` | 1459-1480 |
| 22 | the failure return `cond->`, including `:seon.cluster.eval/interrupted-at` when the record says `:time` | 1481-1502 |
| 23 | `finally (stop!)` | 1503-1504 |

## 4. Separability — responsibility by responsibility

"Pure" below means: a function of ordinary values (including an immutable
database value) that performs no transaction, no provider call, and no SCI
mutation.

### `turn`

| # | Separable | Data crossing the boundary | Owner |
|---|---|---|---|
| 3 | **pure** over a database value | in `db`, `:seon.cluster/name`, `agent-id`; out `:seon.ai/primary`, `:seon.ai/backup`, `:seon.ai/settings`, and the derived `schedule` | new `defn- provider-targets` in `loop.cljc` |
| 4 | separable but trivial | `stream-channel`, `agent-id`, `run-id` | leave inline |
| 5, 6 | no — they transact and `report` | — | leave as closures in the `:call` arm |
| 7 | separable | in a database value + `:seon.cluster.run/id`/`:seon.cluster.agent/id`/`:seon.sci.admit/caps`; out `:seon.cluster.prompt/rendered-context` or a flat error | leave inline; the `try` is the point |
| 10 | **pure** | in `strategy`, `backup`; out a vector of delays | folds into `provider-targets` |
| 13 | **pure** | in `completion`; out `:seon.ai/usage`, `:seon.ai/reasoning-content`, `:seon.ai/finish-reason` | new `defn- attempt-evidence` |
| 15 | **pure** | in `target`, `settings`, ids, `ordinal`, evidence, `failure`, `failover-from`, `delay-ms`; out the `record-attempt!` request | new `defn- attempt-request` |
| 16 | no — `recur` arity is the loop's | — | stays, but its arms shrink to calls |
| 19, 20 | **pure** over a database value | in `db`, `run-id`, `ordinal`, `ctx`, fallback namespace; out `:seon.cluster.run.form/source` + `:seon.cluster.run.form/ns` | new `defn- admitted-form` (absorbs `form-data`, the `d/pull`, and `lint-form`) |
| 21 | **pure** | in `admitted-form`, `evaluation-namespace`, `cluster`, `ctx`; out `:seon.sci.eval/request` | new `defn- evaluation-request` |
| 24 | **pure** over a database value | in `db`, `evaluation`, `settled`, `problem`, `agent-id`, `trigger`; out a `:my.message/value` or nil | new `defn- asked-value` |
| 25 | mixed — `message/delivery` is pure over a database value, `error-tx` is pure over one too | out `:seon.cluster.message/rows`, `:seon.error/values` → refusal tx-data | new `defn- delivery-rows` returning `{:seon.cluster.message/rows … :seon.error/values-tx …}` |
| 26 | **pure** — the single biggest win | in `evaluation`, `problem`, `settlement-evaluation`, `run-id`, `process`, `ordinal`, `settled`; out `:seon.cluster.loop/terminal-request` (a registered closed schema, `resources/seon/schema/loop.edn:31`) | new `defn- receipt-request` |
| 28 | no — it is THE transaction | — | stays |
| 29 | no — it installs from `:db-after` | — | stays |
| 30, 31 | no — loop control | — | stays |
| `:open`, `:call`, `:resume`, `:close` bodies | yes, mechanically | `cluster`, `work`, `now`, `report` | four `defn-`s: `open-turn`, `call-turn`, `resume-turn`, `close-turn` |

### `evaluate`

| # | Separable | Data crossing the boundary | Owner |
|---|---|---|---|
| 8 | already a function (`context-projection`); the three-way `or` is separable | out a projection | fold into a `defn- evaluation-projection` |
| 9, 11, 12 | yes, **but must stay inside the `try`** — it throws `::schema-refused` deliberately | in `event`, `projection`; out `raw-row`/`base-declared-row`, plus the `eval-form!` call under the delta | new `defn- declared-row` taking `eval-form!` as an argument |
| 14, 15 | yes | in `execution-ctx`, `before-interns`, `before-namespace-state`, `event`, `namespace-name`, `source`, `before-reader-context`, `namespace-unmap?`; out `row` + `namespace-changed?` | new `defn- unmap-row` and reuse of `namespace-context-row` |
| 20 | **pure** | in the admitted map, `record`, `printed`, `caps`, namespaces, `print-options`, `session-defs`, `row`; out `:seon.sci.eval/evaluation` | new `defn- success-evaluation` |
| 22 | **pure** | same shape plus `:seon.cluster.eval/error` and `:seon.cluster.eval/interrupted-at` | new `defn- failed-evaluation` |
| 1, 2, 3, 7, 17, 23 | no — thread-scoped arming, volatiles, and the `finally` | — | stay in `evaluate` |

**No new namespace is proposed.** The one candidate — moving the provider
attempt machinery (`attempt-id`, `attempts`, `evidence-attributes`,
`record-attempt!`, `attempt-evidence`, `attempt-request`) into a
`seon.cluster.attempt` — is *rejected* for now: those functions commit
through `store/transact!` with the same `cluster` map and the same
`error-tx` assembly the rest of `loop.cljc` uses, so ownership does not
differ; splitting them would put one transaction's construction in two
namespaces. Revisit only if the `:call` arm later grows its own error
recorder.

## 5. The proposed shape, in code names

After the wave, `loop.cljc` reads:

```
turn                 ; the case, ~30 lines
  open-turn
  call-turn          ; provider-targets, attempt-evidence, attempt-request
  resume-turn        ; admitted-form, evaluation-request, asked-value,
                     ; delivery-rows, receipt-request, terminal-tx,
                     ; session-image-tx, install-program-row!
  close-turn
```

and `eval.clj` reads:

```
evaluate             ; let + try/catch/finally, ~60 lines
  arm / stop!        ; unchanged
  one-event          ; unchanged
  evaluation-projection
  declared-row       ; schema verification + contract facts
  unmap-row          ; deletion identities + namespace state
  changed-session-defs   ; unchanged
  admit/admit        ; unchanged
  success-evaluation | failed-evaluation
```

Every new name is a `defn-` in the file that already owns the behavior,
taking one map and returning one map or one vector, matching
`terminal-tx`/`error-tx`/`settlement-result` next door.

## 6. Risks, by class

### 6.1 The two error classes

`docs/seon/architecture/laws.md`'s two-class law is woven through both
functions and a careless move violates it silently.

- In `turn`, an agent mistake and a refused transaction are both **values**
  (`store/transact!` never throws; `refused!` records and returns `true`).
  The one deliberate **throw** is `terminal-settlement-fault!`
  (`loop.cljc:597-612`), which closes the agent's wake channel and raises
  into Flow's error channel. **No extraction may call
  `terminal-settlement-fault!` from a new site**, and `terminal-refused!`
  must remain the only caller of it inside a turn.
- The `prompt/prompt` `try` (`loop.cljc:1060-1073`) is a *translation*
  site: the prompt owner refuses by throwing and this call site is what
  makes it a value. Moving the `prompt/prompt` call into `call-turn` is
  fine; moving it *out of* the `try` is a behavior change.
- In `evaluate`, the `catch Throwable` at `1459` is what makes the whole
  body total. **Nothing may be lifted out of the `try` into the outer
  `let`.** The `::schema-refused` `ex-info` at `1341` and `1355` is thrown
  *on purpose* so the catch turns it into a flat value; `declared-row` must
  therefore be *called from inside the try*, never evaluated eagerly.
- `failure-value` (`eval.clj:406-422`) special-cases
  `:seon.instrument/contract-violated` so instrumentation faults keep their
  own kind. `failed-evaluation` must take the already-computed value, never
  recompute a kind.

### 6.2 Transaction boundaries

- **The terminal transaction is ONE commit** (`loop.cljc:1413-1438`)
  carrying `terminal-tx` + delivery `rows` + `refusals` + `session-image-tx`.
  Every extraction in `:resume` must produce **tx-data**, and the number of
  `store/transact!` calls in the arm must be unchanged before and after each
  slice (running receipt, terminal, and — on refusal — `terminal-refused!`'s
  minimal settlement).
- **Install after commit, from `:db-after`.** `install-program-row!`
  (`1439-1447`) is gated on the outcome having no `:seon.error/kind` and
  reads `(:db-after outcome)`. It must not move above the transaction and
  must not be handed `@connection`.
- **Capture before the provider.** `context/capture-tx` (`1084-1093`)
  commits before `ai/complete` and its refusal gate (`1104-1109`) is what
  guarantees no unpaid-for provider call. `provider-targets` may be computed
  before or after; the *capture* may not move.
- **One transaction per attempt.** `record-attempt!` commits the error fact
  and the attempt row together and returns the committed fact; the `:call`
  arm's `(nil? fact) (fail! failure)` branch depends on that. `attempt-request`
  must remain a pure builder handed to it, not a second transactor.
- **One database read per turn for settings.** `de7a01483` deliberately put
  `db @connection` outside the attempt loop so failover cannot change
  settings mid-turn. `provider-targets` must take that one database value as
  an argument, never deref the connection itself.

### 6.3 SCI and thread scope

- `arm` (`eval.clj:319-383`) stores the armed state in a `ThreadLocal` and
  `stop!` removes it. **No extraction may introduce a `future`, executor
  submission, or lazy sequence escaping the `try`.** The `record :ok` call at
  `1431` is deliberately inside the boundary so an infinite lazy sequence
  dies at the time limit there.
- `arm` throws `::already-armed` when the thread is already armed
  (open issue `sci-evaluate-throws-when-a-guarded-context-is-re-armed.md`).
  This wave neither fixes nor worsens it; do not "helpfully" wrap it.
- **The volatiles are read by the catch.** `print-options`,
  `ending-namespace`, and `session-observation` are written in the `try` and
  read at `1484`, `1486`, and `1465`. An extraction that returns their values
  instead of leaving the volatiles in scope breaks the failure path silently
  — the failure map would carry the host default print face, which is exactly
  what `1310-1317` exists to prevent.
- **`eval-form!` runs at most once.** It is invoked from three places
  (`1330` under the registration delta, `1386` for a `live-declaration?`,
  `1394` for the plain path) and exactly one of them fires. This is the
  single most dangerous invariant in the wave; see §8.

### 6.4 Sequencing against other open work

Three open issues land inside these bodies:
`failed-eval-definitions-have-no-session-image-delta.md`,
`sci-evaluate-throws-when-a-guarded-context-is-re-armed.md`, and
`terminal-refusal-error-fact-fails-on-oversized-data.md`. Land refactor
slices **between** those fixes, never concurrently with one, and never in
the same commit. The `seon.cluster.loop` → `seon.cluster.turn` rename
(F2 R5, named in the namespace docstring at `loop.cljc:12-14`) stays a
separate atomic wave; mixing a rename into this refactor destroys every
diff the review depends on.

## 7. Slices

Each slice is independently landable, gate-green on its own, and
path-limited to one file.

| # | Slice | File | Focused gate |
|---|---|---|---|
| S1 | `receipt-request` — extract responsibility 26's `cond->`; output validated against `:seon.cluster.loop/terminal-request` | `loop.cljc` | `bin/test seon.cluster.loop-test seon.cluster.turn-test` |
| S2 | `attempt-evidence` + `attempt-request` — responsibilities 13 and 15 | `loop.cljc` | `bin/test seon.cluster.loop-test seon.cluster.turn-test seon.ai-test` |
| S3 | `provider-targets` — responsibilities 3 and 10 | `loop.cljc` | `bin/test seon.cluster.turn-test seon.config-application-test` |
| S4 | `admitted-form` + `evaluation-request` — responsibilities 19-21 | `loop.cljc` | `bin/test seon.cluster.turn-test seon.repl-parity-test` |
| S5 | `asked-value` + `delivery-rows` — responsibilities 24-25 | `loop.cljc` | `bin/test seon.cluster.message-test seon.cluster.message-assignment-test seon.cluster.problem-routing-test seon.gen.loop-test` |
| S6 | `open-turn` / `call-turn` / `resume-turn` / `close-turn`; `turn` becomes the `case` | `loop.cljc` | full `bin/test` |
| S7 | `success-evaluation` + `failed-evaluation` — responsibilities 20 and 22 | `eval.clj` | `bin/test seon.sci.eval-test seon.sci.session-image-test seon.sci.eval-instrumentation-test` |
| S8 | `unmap-row` + `evaluation-projection` — responsibilities 8, 14, 15 | `eval.clj` | `bin/test seon.sci.eval-test seon.cluster.turn-test` |
| S9 | `declared-row` — responsibilities 9, 11, 12; `evaluate` reduces to lifecycle | `eval.clj` | full `bin/test` + one live turn on a scratch cluster |

Ordering rationale: S1-S5 are pure extractions inside one arm each, so they
can interleave with other lanes' work in the same file with minimal conflict.
S6 is the mechanical move that actually delivers the parallel-edit win, and
it is placed after the pure extractions so the moved bodies are already
small. S7-S8 are the low-risk half of `eval.clj`. S9 is last because it is
the riskiest.

**The riskiest extraction is S9 (`declared-row`).** It is the only one that
must both (a) stay inside the `try` so its `::schema-refused` throw remains
an agent-visible value, and (b) preserve the exactly-once invocation of
`eval-form!` across three mutually exclusive call sites, one of which fires
*inside* `schema/call-with-registration-delta` so the agent's form runs
against an isolated overlay. Get it wrong in the obvious way — pass
`(eval-form!)` instead of `eval-form!` — and the agent's form evaluates
twice, or evaluates outside the registration delta, and both failures are
invisible to any test that only checks the returned value.

## 8. Tests

### Already sufficient to protect the refactor

- `test/seon/cluster/turn_test.clj` (2,946 lines) covers the schema
  registration/unregister family (`:1137`, `:1194`, `:1233`), the whole
  `ns-unmap` family (`:472`, `:535`, `:560`, `:592`, `:645`), refused
  terminal transactions and their settlement (`:733`, `:890`), contracted
  redefinition (`:1081`), install-only-from-a-successful-`db-after`
  (`:1265`), and provider evidence on success, absence, and failure
  (`:2069`, `successful-call-persists-the-providers-open-usage-document`,
  `reasoning-starvation-persists-usage-finish-and-the-named-error`). This
  last one already pins responsibility 13's `(or completion (get-in
  [:seon.error/data …]))` on both branches, so **S2 needs no new
  characterization test**.
- `test/seon/gen/loop_test.clj` drives the situations as kill positions in a
  state-transition property — the right protection for S6.
- `test/seon/cluster/loop_test.clj:366-380` already tests `terminal-tx`
  directly with `:seon.cluster.eval/result-blob`, and `:535`/`:593-603`
  pin the presence-is-the-state receipt projection.
- `test/seon/sci/session_image_test.clj` pins the failed-evaluation delta
  (`:182`), the time-limited delta (`:219`), and cross-JVM restore (`:74`) —
  the protection for S7's `failed-evaluation`.
- `test/seon/sci/eval_test.clj:144`
  (`agent-print-vars-are-captured-before-sci-bindings-unwind`) pins the
  `print-options` volatile, and `:521`/`:549` pin the per-thread arm — the
  protection for the §6.3 hazards.

### Characterization tests needed BEFORE moving code

1. **Before S1** — one turn whose evaluation carries
   `:seon.cluster.eval/result-blob`, `:seon.cluster.eval/interrupted-at`,
   `:seon.cluster.eval/error`, `:seon.cluster.eval/output`,
   `:seon.program/row`, and a `:my.run/value` *simultaneously*,
   asserting the committed receipt datoms. Today each attribute is asserted
   in a different test; nothing pins the combined projection that
   `receipt-request` will own. Add it to `seon.cluster.turn-test`.
2. **Before S9** — an exactly-once counter on `eval-form!`. Drive three
   forms through `evaluate` on one live ctx — a plain expression, a
   contracted `defn`, and a `:seon.schema/key` registration — with a side
   effect the test can count (a `def` incrementing a value the test reads
   back through `intern-values`), and assert the count is 1 in each case and
   that the schema form's effect is visible only after the registration
   delta. Add it to `seon.sci.eval-test`. This is the falsifier for the
   riskiest extraction and it does not exist today.
3. **Before S5** — one turn where `message/delivery` returns *both*
   `:seon.cluster.message/rows` and `:seon.error/values`, asserting both the
   delivered rows and the refusal error facts land in the same terminal
   transaction. `seon.cluster.message-test` covers each separately.

Each extracted pure function additionally gets one direct example test in
the same slice, per the issue's acceptance criterion "each extracted
boundary has one direct falsifier".

## 9. What does NOT change

This is a structure-only refactor. Explicitly unchanged:

- **Public contracts.** `turn`'s `:seon.cluster.loop/turn-request` →
  `:seon.cluster.loop/turn-report` and `evaluate`'s
  `:seon.sci.eval/request` → `:seon.sci.eval/evaluation` are byte-identical
  before and after. `settle-interruption!`, `lint-form`, `disposition`,
  `messages`, `terminal-tx`, and `committed-attributes` keep their
  signatures.
- **Receipts and schema.** No attribute is added, removed, renamed, or made
  optional. `resources/seon/schema/loop.edn` and
  `resources/seon/schema/eval.edn` are untouched.
- **Transaction count and content** per turn, per situation.
- **The `:seon.cluster.loop/evaluate` indirection** — `turn` keeps resolving
  the configured symbol through `requiring-resolve`, which is what lets tests
  substitute `fake-evaluate`.
- **Behavior on every failure path**, including which failures are values and
  which are Flow faults.
- **The namespace names.** No rename, no new namespace, no file moves.

### Behavior questions found while reading, deliberately NOT smuggled in

Listed, not fixed, per the cut-first rule. Each needs its own issue if it is
not already covered:

1. `loop.cljc:1197` calls `Thread/sleep` inside the `:call` arm. It is
   admitted retry strategy (the sweep's clock adjudication explicitly
   allows it), but it blocks an `:io` proc for the schedule's duration.
   Worth a measured look; not part of this wave.
2. `loop.cljc:1259-1271` issues a `d/pull` of the full `:seon.ns` row on
   *every form* of *every run*, including forms that cannot change the
   namespace. Cost, not correctness.
3. `eval.clj:1318-1321`'s three-way projection `or` silently falls back to
   `schema/build-projection` — a rebuild in the hot path whose occurrence is
   unobservable. Related to sweep finding #13
   (`one-program-graph-is-shared-across-clusters.md`).
4. `loop.cljc:1383-1399` computes `(or (:seon.cluster.eval/error evaluation)
   (:seon.cluster.eval/error problem))` twice and the same for
   `:seon.error/kind`. Harmless duplication that `receipt-request` will
   remove as a side effect of S1 — the only incidental cleanup in the wave.

## 10. Acceptance

Closes `docs/seon/issues/runtime-turn-and-evaluate-kernels-conflate-boundaries.md`
when all nine slices have landed and:

- `turn` is the `case` plus four named arms; `evaluate` is the arm/try/catch/
  finally lifecycle plus named transformations;
- no second loop, compatibility namespace, mutable state machine, or stored
  phase enum exists;
- `bin/test` is green, `test/seon/gen/loop_test.clj`'s state-transition
  property passes unchanged, and one live turn on a scratch cluster produces
  the same receipt datoms as before the wave;
- each extracted function has one direct falsifier, and the three
  characterization tests in §8 exist.
