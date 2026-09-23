---
type: research
status: read-only analysis; no source edited; two read-only JVM probes on default
created: 2026-09-23
lane: cut-analysis (Opus 5.5)
tags: [agent-platform, shrink, cuts, long-functions, braids, lane-assignments]
---

# Cut analysis — long functions, their braids, and the next lanes

**What the system already does here.** Default's indexed facts already answer "who calls
this": one query over `:seon.fn/calls`/`:seon.fn/references` returns the no-caller and
test-only populations in 163 ms (`(d/q '[:find [?c ...] :where (or [?f :seon.fn/calls ?c]
[?f :seon.fn/references ?c])] db)`, §6). Three research documents from today already
price most deletions: [deletion hunt](deletion-hunt-2026-09-23.md) (rows S*/N*),
[shrink-db](shrink-db-2026-09-23.md) and [shrink-schema](shrink-schema-2026-09-23.md)
(each with REPL-proved first slices).
**The smallest composition that improves it.** This document does not re-price the
codebase. It splits the 25 longest functions into the jobs each one braids. It checks each
proposed replacement against upstream, not only against our fork. It marks every cut READY
or BLOCKED against the current ledger holds, and groups the READY cuts into three
deletion-only lanes that can launch now (§8).

Tree: working tree at HEAD `380647a56` (other lanes' uncommitted edits present, all in
held files). `src/` = **85,061 lines, 105 files, 4,167 var definitions**.

## 0. Summary

1. **Long functions are few and concentrated.** 70 definitions over 100 lines hold 10,793
   lines. 302 over 50 lines hold 26,468, and 730 over 30 lines hold 43,076, which is half of
   src. Seven files hold 29,373 lines (35 %): `turn`, `db`, `schema`, `render/web`,
   `sci/eval`, `fn`, `cluster`.
2. **The same five braids recur in the top 25** (§2). They are refusal values that every
   caller inspects by hand, inline error literals, "supplied-or-recomputed" dual paths, work
   proportional to the whole program on each call, and dead branches for a retired
   mechanism. Together these explain most of the length. Library-shaped gaps explain little.
3. **Most of the top 25 are BLOCKED today.** 17 of the 25 sit in files other lanes hold
   (`turn.clj`, `sci/eval.clj`, `test.clj`, `cluster.clj`, `fn.clj`, `cluster/agent.clj`,
   `cluster/source.clj`, `issue.clj`). Their cuts go to the holder as follow-ups (§8.2).
4. **Three deletion-heavy lanes are READY now** on free, clean files (§8.1):
   L1 dead and test-only sweep plus `bootstrap_drive.clj` (≈ −1,500),
   L2 `db.clj` shrink-db slices (≈ −720),
   L3 `schema.clj` one lazy-registry constructor (≈ −780).
   Each adds ≤ ~100 lines.
5. **Two prior deletion rows rest on fork inventions** (§9). The fork audit (`410b5daba`)
   rules N1 (read currency through the fork's `q-with-evidence` dependency plan) and N19
   (projection memo keyed by `:cache-context`) invalid as written. Upstream's
   `datahike.dependency-tracking` is the seam to price instead. `check-schema-update` (N3),
   `gc-storage!` (N6) and `mr/lazy-registry` (N4/S4) are upstream and remain valid.
6. **Removable estimate.** The rows analysed here come to ≈ 11,000 lines. They overlap the
   deletion hunt's ≈ 29,000 and do not add to it. The honest total stays at src ≈ 55,000. The
   owner's 10,000 needs the §7 dissolution pass of the deletion hunt, which no row here
   replaces.

## 1. Measurement

Scripts (committed with this note, force-added under the ignored `tmp/`):

- `bb tmp/cut-analysis/measure.bb > tmp/cut-analysis/measure.out` runs
  `clj-kondo --lint src --config '{:output {:format :edn} :analysis {:var-definitions
  {:shallow false}} :linters ^:replace {}}'`. From `:row`/`:end-row` it computes lines,
  branch count (`if|when|cond|condp|case|cond->|some->|try|catch` openers inside the span),
  maximum bracket depth (strings and comments skipped), per-file lines and a histogram.
- `bb tmp/cut-analysis/orphans.bb` lists namespaces with no requirer in src, script,
  `resources/seon/operator` or dev (kondo `:namespace-usages` + `:var-usages`).
- MCP `eval_clj` (JVM, `default`, session `cut-analysis`, read-only) writes
  `tmp/cut-analysis/indexed-callers.edn`: core function rows with no caller, and those
  with only test callers. The form is in §6.
- `bb tmp/cut-analysis/dead.bb` joins that set with kondo spans. It removes every symbol
  named in `resources/ config/ bin/ script/ deps.edn build.clj` (data callers: render
  pairs, `:db.fn/call`, config function symbols) or quoted in src (`'sym`, string-built
  resolution).

### 1a. The 40 longest definitions

| lines | branches | depth | var | file:row |
|---:|---:|---:|---|---|
| 407 | 45 | 22 | `seon.sci.eval/evaluate` | `src/seon/sci/eval.clj:3178` |
| 348 | 20 | 12 | `seon.turn/call-turn` | `src/seon/turn.clj:4293` |
| 336 | 46 | 14 | `seon.test/select` | `src/seon/test.clj:576` |
| 311 | 36 | 22 | `seon.test/run` | `src/seon/test.clj:1621` |
| 306 | 18 | 17 | `seon.sci.eval/acquire-program!` | `src/seon/sci/eval.clj:1867` |
| 240 | 10 | 13 | `seon.schema/projection-with-declarations` | `src/seon/schema.clj:2760` |
| 221 | 17 | 15 | `seon.schema/projection-from-rows` | `src/seon/schema.clj:3067` |
| 221 | 18 | 19 | `seon.effect/request*` | `src/seon/effect.clj:719` |
| 218 | 33 | 17 | `seon.db/write-owned-values-error` | `src/seon/db.clj:3333` |
| 213 | 10 | 12 | `seon.schema/build-projection` | `src/seon/schema.clj:2146` |
| 195 | 25 | 13 | `seon.test/admit-run` | `src/seon/test.clj:1014` |
| 185 | 25 | 15 | `seon.render/render-call` | `src/seon/render.clj:1412` |
| 178 | 20 | 19 | `seon.turn/system-turn` | `src/seon/turn.clj:2064` |
| 173 | 18 | 16 | `seon.schema/assert-complete-contract!` | `src/seon/schema.clj:1511` |
| 172 | 17 | 16 | `seon.issue/index-tx` | `src/seon/issue.clj:322` |
| 170 | 19 | 17 | `seon.fn/index!` | `src/seon/fn.clj:3550` |
| 169 | 26 | 15 | `seon.sci.eval/install-row!` | `src/seon/sci/eval.clj:941` |
| 166 | 15 | 10 | `seon.turn/record-attempt!` | `src/seon/turn.clj:3976` |
| 166 | 14 | 17 | `seon.turn/resume-turn` | `src/seon/turn.clj:4833` |
| 162 | 15 | 16 | `seon.test.runner/record-latest-tx` | `src/seon/test/runner.clj:1345` |
| 158 | 24 | 24 | `seon.cluster.source/publish!` | `src/seon/cluster/source.clj:544` |
| 158 | 0 | 14 | `seon.cluster/arm-agents!` | `src/seon/cluster.clj:3418` |
| 157 | 16 | 10 | `seon.error/prepare` | `src/seon/error.clj:515` |
| 155 | 12 | 12 | `seon.ai/send-request` | `src/seon/ai.clj:1330` |
| 153 | 29 | 16 | `seon.cluster.agent/acquire-context!` | `src/seon/cluster/agent.clj:782` |
| 149 | 12 | 13 | `seon.plan/compile-tree` | `src/seon/plan.clj:1147` |
| 146 | 12 | 18 | `seon.render.web/acquire-debug-data` | `src/seon/render/web.clj:1629` |
| 144 | 13 | 17 | `seon.cluster/populate-source!` | `src/seon/cluster.clj:1637` |
| 143 | 14 | 16 | `seon.turn/step` | `src/seon/turn.clj:5465` |
| 143 | 11 | 13 | `seon.call-preparation/plan-for` | `src/seon/call_preparation.clj:992` |
| 142 | 12 | 15 | `seon.fn/contract-findings` | `src/seon/fn.clj:1722` |
| 139 | 5 | 11 | `seon.fn/var-row` | `src/seon/fn.clj:598` |
| 139 | 9 | 11 | `seon.render.web/render-step` | `src/seon/render/web.clj:2618` |
| 138 | 13 | 12 | `seon.render.web/page-result` | `src/seon/render/web.clj:434` |
| 136 | 17 | 16 | `seon.test.runner/complete-members` | `src/seon/test/runner.clj:1208` |
| 131 | 19 | 14 | `seon.cluster.boot/request!` | `src/seon/cluster/boot.clj:553` |
| 129 | 24 | 12 | `seon.cluster.process/run-process!` | `src/seon/cluster/process.clj:169` |
| 129 | 8 | 14 | `seon.cluster.agent/armer-step` | `src/seon/cluster/agent.clj:1221` |
| 127 | 15 | 15 | `seon.program/digest-map` | `src/seon/program.cljc:449` |
| 124 | 14 | 14 | `seon.turn/row-tx` | `src/seon/turn.clj:1199` |

**Most branching (lines > 15), beyond the table above:** `cluster/full-source-refresh!`
`cluster.clj:1978` (119 lines, 29 branches), `instrument/violation` `instrument.clj:272`
(121, 25), `render.value/value-node*` `render/value.clj:430` (114, 23),
`error/log-line` `error.clj:1219` (63, 19), `render.transcript/render-session`
`render/transcript.clj:1361` (114, 19), `render.transcript/ledger-turn-body` `:1855`
(97, 19), `schema/pulled-attribute-entry` `schema.clj:3455` (116, 18),
`fn/published-index-rows` `fn.clj:3326` (96, 17).

**Deepest nesting (depth ≥ 20):** `cluster.source/publish!` 24, `test/run` 22,
`instrument/arm-var!` `instrument.clj:744` 22, `sci.eval/evaluate` 22,
`cluster.agent/render-identity-html` `cluster/agent.clj:290` 21,
`db/query-index-patterns` `db.clj:600` 21, `render.web/feed` `render/web.clj:2857` 21,
`issue/adopt-tx` `issue.clj:894` 21, `fn/published-index-rows` 20,
`render.walk/acquired-tree` `render/walk.clj:483` 20.

### 1b. The 15 largest files (one namespace per file; full list in `measure.out`)

| lines | file | | lines | file |
|---:|---|---|---:|---|
| 5,607 | `src/seon/turn.clj` | | 1,590 | `src/seon/plan.clj` |
| 4,546 | `src/seon/db.clj` | | 1,540 | `src/seon/ai.clj` |
| 4,202 | `src/seon/schema.clj` | | 1,537 | `src/seon/call_preparation.clj` |
| 3,807 | `src/seon/render/web.clj` | | 1,401 | `src/seon/program.cljc` |
| 3,798 | `src/seon/sci/eval.clj` | | 1,401 | `src/seon/issue.clj` |
| 3,719 | `src/seon/fn.clj` | | 1,375 | `src/seon/print.cljc`, `src/seon/flow.clj` |
| 3,694 | `src/seon/cluster.clj` | | 1,349 | `src/seon/cluster/agent.clj` |
| 2,445 | `src/seon/render/transcript.clj` | | 1,120 | `src/seon/instrument.clj` |
| 2,334 | `src/seon/error.clj` | | 1,018 | `src/seon/render/walk.clj` |
| 1,969 | `src/seon/test.clj` | | 978 | `src/seon/maintenance.clj` |
| 1,897 | `src/seon/render.clj` | | 950 | `src/seon/effect.clj` |
| 1,761 | `src/seon/test/runner.clj` | | | |

## 2. The braids that make the long functions long

Each braid is counted over all of `src/` with `rg`. The top 25 contain every one of them.

| # | braid | count | where it lengthens the top 25 | the un-braiding | owner |
|---|---|---:|---|---|---|
| B-a | **A refusal is a return value that every caller inspects by hand.** Callers test `(or (:seon.db.write.attempt/request-id x) (:seon.db/invalid-read x) (:seon.schema/expected-value x))` after every write | 41 sites (turn 14, plan 6, web 3, db 3) | `call-turn` has seven such checks and five `fail!` exits | Under the error policy, a failed write on a core path is a fault, so `transact!` would throw it and only agent-facing boundaries would turn it into a value. That is a **decision**, not a script (B3/owner): today's return shape is ruled | B3 + owner |
| B-b | **A copied general error predicate** `(and (map? x) (contains? x :seon.error/at) (contains? x :seon.error/layer) (contains? x :seon.error/operation))` | 52 sites (test 18, turn 13, fn 9, runner 7) | `admit-run`, `select`, `runner/provenance` `runner.clj:994` | AGENTS forbids a general predicate. The producer's declared union is checked at its one seam, or the producer throws (B-a). shrink-db slice 3 does the db.clj share | B3, A2 |
| B-c | **Inline error literals.** A map carrying `:seon.error/at (Date.)`, `:layer`, `:operation`, `:message`, `:offending`, `:member`, `:expected` | 342 literals; **2,128 lines** of base keys (plan 24, issue 21, config 18, sci/eval 17, ai 17, effect 15) | `effect/request*` has six literals (≈ 60 of its 221 lines); `ai/send-request` five (≈ 50 of 155) | A positional form of the 1.1 constructor (`error/refusal.clj:91` `diagnostic` needs `at`, `layer` and `operation` supplied), and one script per file. Roughly 7 lines become 2, so **≈ −1,200** | B3 1.1 callers; needs the helper in `error/refusal.clj` (held by error-floor) |
| B-d | **Supplied-or-recomputed dual paths** (habit 1): `(or supplied (recompute))` for a projection, compiled forms or registry | `assert-complete-contract!` has 15 `or` fallbacks and a `prepared?` switch (`schema.clj:1511`). `evaluate`, `acquire-program!` and `install-row!` each open with `(or supplied (db/carried-projection db) (env/of …))` | same functions | The value carries the projection (A1-3/A1-12). The function takes it as an argument and has one path | A1 |
| B-e | **Work proportional to the whole program on every call** | `acquire-program!` queries every function row, pulls every namespace with a wildcard, and reads every agent test (`sci/eval.clj:1897-1990`) on each acquisition, then pulls `:seon.fn/arglists` once per row inside the install loop (N+1, `:2150`) | `acquire-program!` | Read only the interpreted set (`overridden` ∪ `affected`, which it already computes at `:1922-1932`) and pull its arglists with one `pull-many` | B2 §2a (1.3d c1) |
| B-f | **Two hand topological sorts** | `cluster/reload-order` `cluster.clj:2098` (22 lines) and the Kahn loop inside `acquire-program!` `sci/eval.clj:2027-2061` (35 lines) | `acquire-program!` | One function. clj-reload's `parse/topo-sort` (`reference-code/clj-reload/src/clj_reload/parse.clj:163`) is the shape, but clj-reload is not a dependency. Keep Seon's `reload-order` and call it from both | B2 + B1 (both files held) |
| B-g | **Dead branches of a retired mechanism** | `snapshot?` in `test/admit-run` (`test.clj:1032`, `:1138-1146`, 9 sites in test.clj). `:seon.test.run/published-base-digest` is read at `test.clj:82,998,1050,1140`, `runner.clj:1555,1736` and `cluster/source.clj:447`, but **no src function writes it**: `runner/provenance` `runner.clj:986` never sets it | `admit-run`, `record-latest-tx` | Delete the branch family and the two optional schema attributes (`seon.test.run.edn:6-11,85-86,101-102`). Proof of absence: `rg published-base-digest src` shows only readers | B1 1.2b (snapshot dissolved) + B4 c5 |

## 3. The 25 longest functions, one row each

"Removed" is the estimated net deletion at the function. "Status" is checked against the
ledger's CURRENT HOLDS (`tmp/orchestrator/file-ownership.md`, block dated ~08:55Z).
Callers are src/test counts from the deletion hunt §6 (kondo), spot-checked with `rg`.

| # | function | what it does | why it is long (braids, hand machinery, dead or defensive code) | what it becomes | removed | owner step | callers | regression | status |
|---|---|---|---|---|---:|---|---|---|---|
| 1 | `sci.eval/evaluate` `:3178` (407) | evaluates one form under arm, custody and admission; returns a value, never throws | 7 jobs in one body: projection-state threading (`:3296-3301`, ambient transport A1-12), turn-environment scoping (`:3236-3252`), custody and dynamic bindings, a 30-line `*request-context*` map rebuilt per form (`:3318-3350`), acquisition **inside** evaluation (`:3405-3420`; acquisition belongs at turn start per AGENTS "Stability comes from the branch"), reader, schema-declaration rows, row derivation, admission; two nested `catch Throwable` with the same `failure-result` | `(-> request with-custody read-one declare! eval-form admit)`: six functions of data; acquisition moves to turn start; the request context is `env/scope` of the carried environment | −150 | B2 c3/c4 + A1-12 | 4 / 100 | `seon.sci.eval-test` evaluation-contract cases (interrupt, reader error, schema def) unchanged | BLOCKED: `sci/eval.clj` held by leak-fix-2; cut 4 |
| 2 | `turn/call-turn` `:4293` (348) | resolves providers, renders the prompt, calls the model with failover and backoff, freezes the plan | provider resolution + stream sink + prompt render + context-capture tx + blob-staged plan freeze + retry loop with `Thread/sleep` (`:4604`) + settlement; B-a seven times | `provider-attempt` (one function of target, schedule and prompt returning outcome data) + `freeze!` + settlement; backoff on the flow timer, not `Thread/sleep` | −200 | B2 c4 (one turn function) | 1 / 0 | a failover then success records two attempts; a backoff attempt is timed by its schedule | BLOCKED: `turn.clj` held by m4-n1; cut 4 |
| 3 | `test/select` `:576` (336) | chooses test members from recorded evidence and the changed reach | defensive: re-checks that three edge attributes are indexed symbol-many (`:610-616`), which the declared schema already guarantees because 1.3e refuses to retract them; re-checks admitted membership refs through `history` (`:664-671`), but required refs already refuse the retraction (AGENTS "Required refs refuse a deletion"); then baseline + changed + reach + reuse + long-exclusion command strings in one `let` | four functions: `baseline`, `changed-since`, `reached`, `reusable`; delete the two defensive re-checks | −180 | B4 c4 | 1 / 5 | an unchanged green request selects nothing (the cut-2 exit) | BLOCKED: `test.clj` held by test-overhead |
| 4 | `test/run` `:1621` (311) | runs a selection in batches on an isolated branch and records results | batch admission + reservation ("held elsewhere", `:1716-1726`, the claims B4 c5 deletes) + grouped recording by `terminated?` + `release-not-started!` rows + a 30-line result `cond->` | one admission, `run-members` loop, one `commit-results!` per member, one result assembly | −150 | B4 c5 (claims, staged results) | 3 / 10 | a bound firing mid-request records pending members and fails `passed?` | BLOCKED: test-overhead |
| 5 | `sci.eval/acquire-program!` `:1867` (306) | installs overridden rows and their callers into an SCI context | B-e (whole-program reads, N+1 pull); B-f (second topological sort); `cache-program!` of **all** function rows (`:1995`) although only interpreted ones are installed | interpreted set only; `pull-many`; shared `reload-order`; a cache keyed by the interpreted set | −170 | B2 §2a / 1.3d c1 (S7 of the hunt) | 1 / 0 | the acquisition count equals `(count interpreted)`; an unchanged branch acquires 0 rows | BLOCKED: leak-fix-2 |
| 6 | `schema/projection-with-declarations` `:2760` (240) | third projection constructor: incremental replacement of changed declarations | hand-built incremental Malli registry: reverse closure, recompile, reindex (added `1625fb9bc`) | shrink-schema S4: one constructor over the forms map with `mr/lazy-registry` (`reference-code/malli/src/malli/registry.cljc:81`, unchanged from upstream: `git log -- src/malli/registry.cljc` shows upstream commit `80138076` only) | −240 (inside S4) | A1-3 / 1.4 | 0 external | shrink-schema S4 proof: carried forms and contracts `=`; cold `load-projection` ≤ 150 ms | **READY** (L3) |
| 7 | `schema/projection-from-rows` `:3067` (221) | builds a projection from stored rows | row loading + fingerprint + base selection + validation; a second constructor | S4 `load-projection` (≈ 40 lines) | −180 | A1-3 | 0 external | same | **READY** (L3) |
| 8 | `effect/request*` `:719` (221) | resolves a capability handler and dispatches a request sync or in the background | B-c (six literals ≈ 60 lines); handler resolution, request contract, admission bound and dispatch in one `cond` | the positional constructor; `resolve-handler` as its own function | −90 | B3 §8 (effect 950 → 500) | 2 / 0 | the six refusal kinds keep their keys (`(set (keys r))` before = after) | BLOCKED: constructor helper in `error/refusal.clj` (error-floor) |
| 9 | `db/write-owned-values-error` `:3333` (218) | validates complete owning values reached by a transaction report | hand graph walk (EAVT children, AVET owners) + node budget + cycle detection + validation per root. The walk is Seon's job; the per-value validation is Malli's `m/explain` | shrink-db slice 8: the pre-flight submission check goes and the final report is the one check; `m/explain` per changed owner (hunt N2) later | −170 | A2 c6 | internal / 15 | shrink-db P7: the invalid-write cases of `transact_feedback_test.clj` refuse with operation, attribute, expected form, value and entity | **READY** (L2) |
| 10 | `schema/build-projection` `:2146` (213) | whole projection build | three paths in one function (materialize, incremental, full) plus a reuse test | S4: the full path over a lazy registry; the incremental path is the same call with fewer changed keys | −150 (inside S4) | A1-3 | 15 / 101 | S4; `build-projection`'s public arities kept so its 7 src callers (`program.cljc:285`, `schema/edn.clj:602`, `db.clj:292`, `fn.clj:2238,2494,2762,2946`) do not change | **READY** (L3) |
| 11 | `test/admit-run` `:1014` (195) | reserves a selection at the writer before tests execute | B-g dead `snapshot?` family; B-b; replay-immutability check | delete the snapshot branches; keep reservation until B4 c5 | −60 | B1 1.2b + B4 c5 | 1 / 11 | an identical replay emits no datoms; a changed replay refuses | BLOCKED: test-overhead |
| 12 | `render/render-call` `:1412` (185) | reuses a retained render while its inputs and reads are current | an invocation cache that re-decides currency by read evidence | B2 c9 memo keyed by what it reads | −120 | B2 c9 | 9 / 21 | two renders of an unchanged value do the work once | BLOCKED: plan order, cut 4 |
| 13 | `turn/system-turn` `:2064` (178) | runs a system-only turn | read currency + settlement + evaluation in one body | B2 c5 | −100 | B2 c5 | 3 / 45 | — | BLOCKED: m4-n1; cut 4 |
| 14 | `schema/assert-complete-contract!` `:1511` (173) | asserts that a schema or contract is complete | B-d: 15 `or` fallbacks recompute predicates, dependencies, compiled forms and the registry when the caller did not pass them | takes the projection; one path; `m/walk` for the traversal (`malli/core.cljc` `walk`, upstream) | −80 | A1 R14 | 3 / 1 (`schema/edn.clj:534,594`) | the complete-contract refusals of `seon.schema-test` unchanged | **READY** (L3) |
| 15 | `issue/index-tx` `:322` (172) | derives issue facts from notes | B3 task family | `seon.task` | (inside −3,222) | B3 S5 | 3 / 10 | B3 §4 | BLOCKED: `issue.clj` held by b1-adoption; `my.plan` ruling pending |
| 16 | `fn/index!` `:3550` (170) | populates a scratch branch from static analysis | two modes in one function (a fresh index and an in-place reconcile, `:3605-3640`) | two functions | −40 | B1 | 3 / 18 | the dependency-selection samples | BLOCKED: b1-adoption |
| 17 | `sci.eval/install-row!` `:941` (169) | installs one committed declaration into SCI | `case` over four declaration kinds, each with a B-d projection fallback | one install function per kind; the projection is an argument | −60 | 1.3d c1 | 2 / 13 | — | BLOCKED: leak-fix-2 |
| 18 | `turn/record-attempt!` `:3976` (166) | records one provider attempt | attempt evidence + error fact + settlement | B2 keeps; B-a residue | −30 | B2 | 1 / 5 | — | BLOCKED |
| 19 | `turn/resume-turn` `:4833` (166) | resumes a frozen plan | B2 c4 | one turn function | −100 | B2 c4 | 3 / 1 | — | BLOCKED |
| 20 | `test.runner/record-latest-tx` `:1345` (162) | writes the latest-result rows | B-g residue (`published-base-digest` carried at `:1555`); B4 process machinery | B4 c3 | −60 | B4 c3 | 1 / 0 | — | BLOCKED (coordination): `runner.clj` is free but its semantics belong to test-overhead's run |
| 21 | `cluster.source/publish!` `:544` (158) | reconciles and publishes on the source branch | moved?/unchanged?/populate/record in one body; depth 24 | 1.2b: capture → compare → rows | −80 | B1 1.2b | 2 / 5 | a docstring edit adopts in ≤ 700 ms (publication script row) | BLOCKED: test-overhead holds `source.clj` |
| 22 | `cluster/arm-agents!` `:3418` (158) | wires the cluster graph, fan-out, listener and prime | zero branches: it is wiring. Proc specs are written as code | the proc map as data | −30 | B1b | 1 / 0 | boot drill | BLOCKED: `cluster.clj` (m4-n1, b1-adoption) |
| 23 | `error/prepare` `:515` (157) | prepares one bounded error fact | cause chain (a copy of `error/refusal.clj` `chain`), normalization of two transient refusals by `if (= declared-schema …)` ×2 plus `case`, validation run twice | the constructor carries the chain; one normalization map | −70 | B3 c6 | 3 / 6 | a wrapped `ex-info` stores both `:via` links (census F0) | BLOCKED: semantic dependency on the one error route (error-floor, m4-n1) |
| 24 | `ai/send-request` `:1330` (155) | sends one HTTP request through the JDK client | B-c five literals; the transport itself is small | positional constructor (−40); transport library = owner decision (hunt probe row) | −40 | B3 / ai | 1 / 7 | provider timeout, non-2xx and unreadable JSON keep their keys | BLOCKED: constructor helper (error-floor) |
| 25 | `cluster.agent/acquire-context!` `:782` (153) | returns an execution handle, isolated or live | `locking` + a context cache keyed `[branch agent]` + isolation-branch allocation + three validation throws | B2 c2 fork once: the handle is a value derived from the branch head | −60 | B2 c2 | 1 / 25 | private defs survive a turn; an isolated handle never borrows the live branch | BLOCKED: m4-n1 holds `cluster/agent.clj` |

**Also over 140 lines:** `plan/compile-tree` `plan.clj:1147` (149, B3 task family,
BLOCKED on the `my.plan` ruling), `render.web/acquire-debug-data` `:1629` (146, the debug
surface, owner product decision per shrink-web §0.2), `cluster/populate-source!` `:1637`
(144, B1 1.2b, held), `turn/step` `:5465` (143, cut 4), `call-preparation/plan-for` `:992`
(143, A1 win 5, BLOCKED because its replacement is the wrapper in the held
`instrument.clj`), `fn/contract-findings` `:1722` (142, **test-only**, §6).

## 4. The 10 largest files

| file | lines | plan target | cuts found here | status |
|---|---:|---|---|---|
| `turn.clj` | 5,607 | 3,700 (B2) | B-a 14 sites; `call-turn`, `resume-turn`, `system-turn`; test-only `committed-attributes` `:3140` (51), `submit-evaluation!!` `:3324` (19) | BLOCKED (m4-n1; cut 4) |
| `db.clj` | 4,546 | ≈ 2,500 (shrink-db §3) | shrink-db slices 1–3, 5, 6, 8; dead `call-without-custody` `:395` (24), `deletion-error` `:3914` (17), `fresh-connection` `:117` (6) | **READY** (L2) |
| `schema.clj` | 4,202 | 3,050 (A1) | S4 lazy registry, `assert-complete-contract!` single path; dead `restore!` `:3814` (11), `commit-registration-delta!` `:3807` (6); test-only `register-core-predicate!` `:1270` (34, 4 db.clj callers: verify), `function-returns-in?` `:3989`, `pulled-form-in` `:3643`, `enum-members` `:1703` | **READY** (L3) |
| `render/web.clj` | 3,807 | 2,500 (B2) | a router compiled per request (`:3765`, shrink-web §0.4, 1.07 ms per request); a hand resource handler with a production regex (`:2963-2981`, §0.5); dead `join-package` `:1857` (9); debug surface ≈ 1,280 lines (product decision) | router + resource: READY small (≈ −40; plan cut 4 is rendering; this is delivery plumbing: orchestrator rules); rest BLOCKED (cut 4) |
| `sci/eval.clj` | 3,798 | 2,500 (B2) | `evaluate`, `acquire-program!`, `install-row!`; dead `install-host-namespace!` `:1476`, `program-cache-policy` `:2322` | BLOCKED (leak-fix-2) |
| `fn.clj` | 3,719 | 2,300 (B1) | test-only mechanisms: `contract-findings` 142, `plan-file-change` 108, `backfill-contract-facts!` 97, `output-path-report` 37, `functions-without-tests` 25, `currently-failing-functions` 16 = **425** | BLOCKED (b1-adoption): follow-up |
| `cluster.clj` | 3,694 | ≈ 2,000 | old boot span dead (`boot-phase` `:242`, `reserve-cluster!` `:804`, `release-reservation!` `:816`, `create-directories!` `:824`, `require-cluster-target!` `:830`, `read-advertisement` `:3668`) ≈ 73; test-only `publication-base!` `:2881` 69; N3 schema accretion (Datahike `check-schema-update`, upstream `db/transaction.cljc:155`) ≈ −450; B-f | BLOCKED (m4-n1, b1-adoption) |
| `render/transcript.clj` | 2,445 | 0 (B2 c8) | dead `render-session-ai`/`-html` `:848,858` (18); test-only `format-history-ai` `:963` (27) | dead + test-only: **READY** (L1); rest cut 4 |
| `error.clj` | 2,334 | 550 + 300 moved (B3 c6) | test-only prose builders `refusal-prose` `:1102`, `time-limit-prose` `:1902`, `edit-prose` `:1924`, `unclassified-prose` `:1968` (62) | BLOCKED (coordination with error-floor, which owns the error shown text) |
| `test.clj` | 1,969 | 800 (B4) | `select`, `run`, `admit-run`; B-b 18 sites; B-g | BLOCKED (test-overhead): follow-up |

## 5. Namespaces with no production caller

From `orphans.bb`: requirers in src, script, `resources/seon/operator` and dev. Checked
afterwards for data callers (schema EDN, `config/`, `build.clj`, `bin/`).

| namespace | lines | callers found | verdict |
|---|---:|---|---|
| `seon.bootstrap-drive` | 491 | only `test/seon/bootstrap_drive_test.clj`; no bin, script, deps or config reference | **delete with its test.** Its docstring says it "boots one repository-local scratch root" per drive, which the ONE-JVM law forbids for anything but the platform tier. Unowned; new cut (L1) |
| `seon.test.arm` | 254 | `test/seon/instrument_test.clj`, `test/seon/cluster/agent_test.clj` | test-launcher arming; dies with B4 c5's launcher retirement. BLOCKED (`instrument_test.clj` held by a1-arming) |
| `seon.web.search` | 32 | `config/default.edn:371` (function symbol as data) | live; keep |
| `seon.artifact` | 109 | `build.clj` (uberjar main), schema `seon.artifact.edn` | live; keep |
| `seon.issue.detect` | 333 | detectors named by symbol in issue rows (`test/seon/turn_test.clj:107`) | live by data; a B3 S2 patch is pending on it (`tmp/b3-task-s1/detect-subject.patch`): leave it |
| `seon.cluster.prompt`, `seon.render.ns`, `seon.schema.admission` | 453 / 936 / 487 | render pairs and admission keys in schema EDN; `bin/seon-hook` | live; keep |
| `seon.edit.jvm`, `seon.shell.jvm`, `seon.web.jvm` | 164 / 462 / 484 | `my.edit`, `my.shell`, `my.web` (the SCI surface) | live; keep |
| `my.*` (10 namespaces) | 1,291 | installed into agent contexts, not `require`d | the agent surface; keep |

## 6. Dead and test-only definitions from the indexed facts

Form (JVM mode, `default`, 57–163 ms, read-only):

```clojure
(let [db (datahike.api/db (seon.cluster.boot/connection "default"))
      syms (set (d/q '[:find [?s ...] :where [?f :seon.fn/sym ?s]
                       [?f :seon.schema.admission/source :core]] db))
      src-called (set (d/q '[:find [?c ...] :where [?f :seon.fn/sym ?caller]
                             (or [?f :seon.fn/calls ?c] [?f :seon.fn/references ?c])
                             [(not= ?caller ?c)]] db))
      all-called (set (d/q '[:find [?c ...] :where
                             (or [?f :seon.fn/calls ?c] [?f :seon.fn/references ?c])] db))]
  …)   ; → 133 dead, 840 test-only before the data-caller filter
```

After `dead.bb` removes data callers and quoted symbols, **48 dead definitions (650
lines)** and **66 test-only definitions (1,570 lines)** remain. The full list is in
`tmp/cut-analysis/dead.out`. Split by holder:

- **Free files (L1):** dead `fn.analyzer/manifest-roots` `:178` (39),
  `sci.kernel/new-guard` `:57` (38), `render.ns/budgeted-html` `:797` (36),
  `budgeted-ai` `:614` (19), `token-budget` `:416` (6), `call-preparation/watch!` `:706` (23),
  `config/settings-projection` `:447` (20), `render.walk/owning-namespace` `:619` (20),
  `sci.admit/restorable-node` `:622` (17), `cluster.export/reidentify-branches!` `:316` (16),
  `print/distinct-generated-nodes` `:111` (15), `enrich-elisions` `:1131` (7),
  `web.jvm/read-blob` `:210` (15), `render.transcript/render-session-ai`/`-html` (18),
  `render.web/join-package` `:1857` (9), `web.extract/html` `:6` (9),
  `eval.drive/run-sample-json!` `:449` (5), `repl/render-emission-ai` `:327` (5),
  `schema.internal/assert-multi-segment-namespace!` `:498` (24). Test-only with their tests:
  `context/comparison` `:302` (82), `context/remove-tx` `:207` (19), `problems/log-report`
  `:580` (54), `fn.analyzer/analyze-forms` `:798` (63, a second copy of `fn/analyze-forms`),
  `ai.tokens/report-sentence` `:218` (29), `reconcile/reconcile!` `:418` (28),
  `render.transcript/format-history-ai` `:963` (27), `render.walk/membership-diff` `:575`
  (19), `sci.admit/print-node-edn` `:651` (12), `blob/put-binary!` `:334` (11),
  `cluster.export/reidentify!` `:297` (18). Sum ≈ 900 lines, plus `bootstrap_drive.clj`
  491 and its test.
- **Held files (follow-ups, §8.2):** `fn.clj` 425 (b1-adoption); `cluster.clj` ≈ 142
  (m4-n1/b1-adoption); `turn.clj` 70 and `cluster/agent.clj` `fenced?`/`render-id-html`/
  `whoami` 72 (m4-n1); `flow.clj` `acquire-admission!`/`merge-dropped-fault` 21 (m4-n1);
  `cluster/boot.clj` `install-uncaught-handler!`/`refork!` 23 (error-floor);
  `schedule.clj` `valid-cron?`/`valid-timezone?` 28, `maintenance/report` 15,
  `cluster/store.clj` `fresh-file-lock` 21 (store-damage, leak-fix-2);
  `sci/eval.clj` 17 (leak-fix-2); `cluster/source.clj` `deleted-identities`/
  `dependency-digests` 31 (test-overhead); `issue/start!` 18 (b1-adoption);
  `plan/plan!` 35 (B3 ruling).
- **Limit.** The facts are default's program rows at its 14:52Z boot, so a function added
  since is absent. Each deletion is confirmed with `rg -w` over `src test script resources
  bin config docs/seon/issues` and one REPL `(ns-publics …)` check before it lands. A
  test-only public function is callable by agents ("every function in the cluster's
  program is callable"). L1 deletes one only when no skill, `my.*` docstring or issue note
  names it.

## 7. Ranked cuts (lines removed per unit of risk)

Risk: **L** = deletion with no caller; **M** = a behaviour-preserving rewrite on a hot
path, with parent/child timing required; **H** = semantics or an owner decision.

| rank | cut | files | removed | risk | owner step | status |
|---:|---|---|---:|---|---|---|
| 1 | dead definitions in free files | 17 files (§6) | ≈ 380 | L | "caller-less vars" (B1/B3 remainder); hunt N7 | **READY** L1 |
| 2 | `seon.bootstrap-drive` namespace + test | `bootstrap_drive.clj`, its test | 491 + test | L | unowned — new cut | **READY** L1 |
| 3 | test-only definitions in free files, with their tests | §6 list | ≈ 520 | L | hunt N9 | **READY** L1 |
| 4 | `fn.clj` test-only mechanisms | `fn.clj` + tests | 425 | L | B1 caller-less vars | BLOCKED: b1-adoption (follow-up) |
| 5 | `db.clj` shrink-db slices 1, 2, 3, 5, 6, 8 | `db.clj` + db tests | ≈ 720 | M | A2 c6/c13; shrink-db §2 | **READY** L2 |
| 6 | one projection constructor (shrink-schema S4) + `assert-complete-contract!` single path + dead residue | `schema.clj`, `schema/edn.clj` + schema tests | ≈ 780 | M | A1-3 / 1.4 (A1 R14) | **READY** L3 (plan-order note, §8.1) |
| 7 | old boot span + `publication-base!` | `cluster.clj` | ≈ 142 | L | B1b remnant | BLOCKED: m4-n1, b1-adoption |
| 8 | dead snapshot family (B-g) | `test.clj`, `runner.clj`, `source.clj`, `seon.test.run.edn` | ≈ 80 | L | B1 1.2b + B4 c5 | BLOCKED: test-overhead (follow-up) |
| 9 | inline error literals → positional constructor (B-c), scripted per file | ≈ 40 files | ≈ 1,200 | L–M (shown text keeps its keys) | B3 1.1 callers | BLOCKED: the helper belongs in `error/refusal.clj` (error-floor); then one script per free file |
| 10 | `call_preparation.clj` → wrapper-applied defaults (ruled option (a)) | `call_preparation.clj`, `instrument.clj` | 1,237 | M | A1 win 5 / S12 | BLOCKED: a1-arming holds `instrument.clj` |
| 11 | `acquire-program!` reads only the interpreted set; one topological sort (B-e, B-f) | `sci/eval.clj`, `cluster.clj` | ≈ 170 | M | B2 §2a | BLOCKED: leak-fix-2 |
| 12 | schema accretion → Datahike `check-schema-update` (hunt N3) | `cluster.clj:1002-1635` | ≈ 450 | M | B1 / 1.3e | BLOCKED: `cluster.clj` |
| 13 | HTML serializer → chassis (hunt N15) | `render/hiccup.clj`, `render/lint.clj`, `deps.edn` | ≈ 450 | M (new dependency: owner OK) | shrink-web | BLOCKED: `deps.edn` (store-damage) + owner |
| 14 | provider classifier (hunt S12) | `ai.clj`, `turn.clj` consumers | ≈ 740 | H | deep-review win / D3 | BLOCKED: `turn.clj` |
| 15 | B3 task family | `issue*`, `plan.clj`, `note.clj`, `my/{issue,plan,note}` | ≈ 3,222 | H | B3 S3–S7 | BLOCKED: `my.plan` ruling; `issue.clj` held |

Sum of the 15: ≈ 11,000 lines (≈ 3,000 of them READY now). They overlap the deletion
hunt's ≈ 29,000 and do not add to it.

## 8. Lane assignments

### 8.1 READY now (file-disjoint from every current hold and from each other; files clean in the working tree)

**L1 — dead and test-only sweep (Opus 5.5 or gpt-6-sol from this section).**
Paths: `src/seon/bootstrap_drive.clj`, `test/seon/bootstrap_drive_test.clj`, and in
`src/seon/`: `fn/analyzer.clj`, `sci/kernel.clj`, `render/ns.clj`, `call_preparation.clj`,
`config.clj`, `render/walk.clj`, `sci/admit.clj`, `cluster/export.clj`, `print.cljc`,
`web/jvm.clj`, `web/extract.clj`, `render/transcript.clj`, `eval/drive.clj`, `repl.clj`,
`context.clj`, `problems.clj`, `ai/tokens.cljc`, `reconcile.cljc`, `blob.clj`,
`schema/internal.cljc`, plus the test files whose only subject is a deleted definition
(`rg -l` per name). Leave `render/web.clj` `join-package` to the router lane below (one
file, one lane). Excluded because of pending work: `plan.clj`, `issue/detect.clj`,
`test/cache.clj`, `test/arm.clj`.
Method: one script over `tmp/cut-analysis/dead.out` produces the candidate list; for each
name run `rg -w` and one REPL `ns-publics` check; delete; `clj-kondo --lint` the paths;
adopt with `bin/seon init --dev default --changed <paths>`; one
`bin/test-check default --gate --changed-path …` request over the changed paths.
Added lines ≈ 0. Removed ≈ 1,400 src plus tests.
Regression: none new. HEAD loads, the adopted namespaces load, and the gate request is
green or its reds are tests of deleted definitions (question 1 of the three).

**L2 — `db.clj` shrink-db slices 1, 2, 3, 5, 6, 8 (Opus 5.5).**
Paths: `src/seon/db.clj`, `test/seon/db_test.clj`, `test/seon/transact_feedback_test.clj`
(if free at launch), its landing note. The spec is
[shrink-db §2](shrink-db-2026-09-23.md#2-the-first-slices). Each slice has its probe
P2/P3/P5/P7. Slice 4 (the projection stamp, 9 external readers in held files) and slice 7
(read currency; it edits `turn.clj`) stay out. Delete the three dead definitions of §6 in
the same lane. This is a **hot path**: time the shrink-db probes on the parent and on each
commit; a slowdown over 20 % or 50 ms is a defect. The measured target is `seon.db/q`
3.55 ms → within 2× of raw, and pull-by-lookup-ref 1.29 ms → ≤ 0.2 ms.
Default's profile shows 135,131 calls through `db/with-declarations` since the 14:52Z
boot (328 s inclusive): the read wrapper runs on every read, so these slices pay back on
every call.
Added ≈ 100; removed ≈ 720.

**L3 — one projection constructor (Opus 5.5; Astra review).**
Paths: `src/seon/schema.clj`, `src/seon/schema/edn.clj`, the schema test namespaces that
test the deleted constructors, its landing note. Spec:
[shrink-schema §0 and S4](shrink-schema-2026-09-23.md#2-first-slices-each--100-added-lines-net-far-negative),
plus `assert-complete-contract!` over the carried projection (§3 row 14) and the dead
`restore!`/`commit-registration-delta!`. **Keep the public signatures** of `load-projection`,
`build-projection`, `declaration-projection`, `projection-delta` and
`materialize-projection`, so the callers in `db.clj`, `fn.clj`, `program.cljc` and
`cluster.clj` stay untouched. Their removal is S5, after L2 releases `db.clj`.
Proof: carried forms and contracts `=`; cold `load-projection` ≤ 150 ms against the
1,012 ms measured by shrink-schema; `projection-validator :seon.error/base` ≤ 1 ms. A schema
declaration adopted incrementally on a branch of the live store still refuses a retirement
with a surviving writer (1.3e). Parent/child timing: this is acquisition.
**Plan-order note:** README §4 places the projection sweep after the first namespace
agents. The owner's 2026-09-23 cut directive ("most of the deletions should happen" in
analysed cuts) and the fact that S4 touches only free files are the case for launching
now. The orchestrator rules; a lane does not wait for that ruling.
Added ≈ 60; removed ≈ 780.

**Small fourth lane if a slot is wanted — `render/web.clj` delivery plumbing.** Replace the
router compiled per request with `reitit.ring/ring-handler` built once, and the hand
resource handler (a production regex, `:2963-2981`) with
`reitit.ring/create-resource-handler` (reitit-ring 0.10.1 `ring.cljc:289`, already on the
classpath per shrink-web §0.5), and delete `join-package`. ≈ −40 lines and 1 ms per
request. This is cut 4's file, but no rendering semantics change.

### 8.2 Follow-ups for the current holders (hand over when each releases, or as a follow-up message)

| holder | cut | lines |
|---|---|---:|
| b1-adoption (`fn.clj`, `cluster.clj`, `issue.clj`) | the six `fn.clj` test-only mechanisms (§4); the old boot span; `publication-base!`; `issue/start!` | ≈ 600 |
| test-overhead (`test.clj`, `cluster/source.clj`) | dead snapshot family (B-g) incl. `runner.clj` and `seon.test.run.edn`; `select`'s two defensive re-checks (§3 row 3); `source.clj` test-only definitions | ≈ 250 |
| error-floor (`error/refusal.clj`, `cluster/boot.clj`) | a positional constructor over `diagnostic` (≤ 15 lines), which unblocks rank 9; the dead `refork!` and `install-uncaught-handler!` | 23, then ≈ 1,200 by script |
| leak-fix-2 (`sci/eval.clj`) | B-e and B-f in `acquire-program!`; two dead definitions | ≈ 190 |
| m4-n1 (`turn.clj`, `cluster/agent.clj`, `flow.clj`) | test-only `committed-attributes`, `submit-evaluation!!`, `fenced?`, `render-id-html`, `whoami`; dead `acquire-admission!`, `merge-dropped-fault` | ≈ 160 |
| a1-arming (`instrument.clj`) | call preparation, ruled option (a) | ≈ 1,237 |
| store-damage (`deps.edn`, `schedule.clj`, `maintenance.clj`) | dead `valid-cron?`, `valid-timezone?`, `maintenance/report`; after release, N6 GC through upstream `gc-storage!` | 43, then ≈ 250 |

## 9. Upstream checks on earlier deletion rows

Checked against `reference-code/datahike` remote `upstream/main` with `git grep`, and
against the [fork audit](fork-audit-datahike-2026-09-23.md) (`410b5daba`):

| row | seam | upstream? | consequence |
|---|---|---|---|
| hunt N1, shrink-db slice 7 | fork `q-with-evidence` dependency plan (`query.cljc`, fork commit `6a0386d2`) | **no**; fork audit verdict **RIP** | read currency must be priced on upstream's `datahike.dependency-tracking` (`upstream/main:src/datahike/dependency_tracking.cljc`, from `1ea8972a`) instead; do not launch N1 as written |
| hunt N19 | `:cache-context` / attribute revisions as a memo key | **no** (fork invention, fork audit finding 1; AGENTS corrected `759307d57`) | the projection memo key must come from commit id or dependency tokens |
| hunt N3 | `check-schema-update` / `find-invalid-schema-updates` | **yes** (`db/transaction.cljc:155`, `schema.cljc:359` upstream) | valid |
| hunt N6 | `gc-storage!` | **yes** (`gc.cljc:205` upstream; upstream also ships `gc_guard.cljc` and `gc_roots.cljc`) | valid; the fork's reachability permit (`56f1c621`) is RIP, so use upstream's GC roots |
| hunt N4, shrink-schema S4 | `mr/lazy-registry`, `mr/composite-registry` | **yes** (`malli/registry.cljc:54,81`; the file's last change is upstream `80138076`) | valid; L3 |
| — | `release-materialized-db` (19 Seon calls) | not in upstream `versioning.cljc` | fork audit finding 3: all 19 are no-ops (datahike R4, already scheduled) |

## 10. Timings (this lane)

| operation | ms | proportional to | over 1 s: reason |
|---|---:|---|---|
| `measure.bb` (kondo analysis of src + span metrics) | 7,923 | all 105 src files (85,061 lines) | kondo analysis output is not cached and the question covers the whole program. For callers, the cheaper route is the indexed facts (below, 163 ms); kondo was needed only for spans and depth. One run, not repeated |
| `orphans.bb` (kondo over src, script, operator, dev + test), run twice (filter fix) | 7,977 and ≈ 8,000 | ≈ 430 files | same reason. The second run repeated the analysis after a path-filter bug; caching the kondo EDN once would have made it sub-second. This is my defect, noted and not repeated |
| `dead.bb` (kondo spans + data-caller text scan) | 3,568 | src spans + text of `resources config bin script` | one kondo pass for spans; the indexed facts have no spans |
| MCP `runtime_status` | < 1,000 | — | — |
| MCP `eval_clj` no-caller census | 163 | three `d/q` over program rows | — |
| MCP `eval_clj` dead/test-only split + spit | 57 | same | — |
| every `rg`, `git grep`, `sed` read | < 1,000 each | — | — |

No operation exceeded ten seconds, so no issue note is required.

## 11. Limits and out-of-scope findings

- **Analysis only.** No cut was implemented or probed here. Removal figures are definition
  spans minus an estimated replacement, or the shrink-db and shrink-schema figures where
  those were REPL-measured. Branch and depth counts are textual (`rg`-style), not a
  semantic cyclomatic measure.
- **Staleness.** The CURRENT HOLDS block is dated ~08:55Z. The orchestrator re-checks it at
  launch. The dead/test-only facts are default's rows from its 14:52Z boot.
- **Out of scope, observed (not filed; not this lane's operation):** default's profile
  (`runtime_status`, 14:52Z boot) shows `seon.turn/call-turn` with a maximum of **92,679 ms**
  inclusive and `seon.schema/call-with-projection-state` with 92,692 ms. 45 contracted
  functions are over one second. The owning issue class is likely
  `docs/seon/issues/a-one-form-resume-turn-takes-seconds.md` or a provider wait. The
  orchestrator should attribute it: "provider time" is not a justification without its
  number and reason.
