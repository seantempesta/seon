---
type: research
status: draft
created: 2026-09-21
tags: [data-pack, errors, tasks, issues, config, dials, effects, search, lane-b3]
---

# Data pack B3 — errors, tasks, dials

Facts for a spec author. Every row was re-verified this session at HEAD
`e7721a963` (branch `steward-platform`). No recommendations, no ordering, no
done criteria. Where the source audit
([deletion-audit-errors-issues-config-2026-09-21.md](deletion-audit-errors-issues-config-2026-09-21.md))
drifted, the correction is in §10 with its evidence.

**Snapshot provenance.** The audit was verified at `b2f34ebaf`.
`git diff --stat b2f34ebaf..HEAD -- src test resources config bin script`
is **empty**: the two intervening commits (`344d289b7`, `e7721a963`) moved
and repointed docs only. Every `src`/`resources`/`config` citation in the
audit therefore still addresses the same bytes at HEAD.

**Working tree at the time of this pack.** `git status --porcelain` shows
uncommitted edits in `src/seon/cluster.clj`, `src/seon/fn.clj`,
`test/seon/cluster/publication_delta_test.clj`,
`test/seon/fn/publication_cache_test.clj` — a foreign lane's, not B3's.
`src/seon/cluster.clj` carries 25 `:seon.error/kind` sites (§3) and the
`seon.search` wiring (§8), so both are held paths today.

**Archived-path note.** The two inventories in §3b are cited by their
CURRENT paths under `docs/prds/steward-platform/research/`; that directory
is scheduled to move under `docs/archive/` and the citations will need
repointing when it does. `docs/prds/steward-platform/plan/` (ERR, PRD-PF,
NSA) is likewise still at its current path at HEAD.

---

## 1. Audit citations re-verified at HEAD

Gitlinks at HEAD: malli `606083c5` (`heads/seon-ref-scope`), datahike
`006e634a` (`v0.3.2-878`), konserve `07377c27`, sci `fcbd8862`,
clj-kondo `57252e07`, clojure `b18d3adc` (1.13.0-alpha5), core.async
`dc35f3e0`.

**Verified exact** (symbol found at the cited line, count reproduced):
`diagnostic` leaf's 7 required evidence keys `error/refusal.clj:37` (input
map `:43-50`, `dissoc` into `:seon.error/data` `:58-74`); the pass-through
facade `error.clj:304` (body `:323`); 275 construction sites; 2,310
`diagnostic-*` key lines; `kind` 306/443/46/4 and **0 in `resources`**;
`class` 183; `facet-keys` `:1921`; `seon.search/search` zero src callers;
predicates+generators `error.clj:2314`→2,658; dead prose builders `:1304`
etc., alive only through `test/seon/error_test.clj`; the steward 0-of-23
measurement (in `stack-failing-function`'s docstring `:496-502`; `steward`
itself at `:1526`); `blob.clj:149` `verify-stored!` (sole caller `:212`);
`render.clj:88` + `render/value.clj:25` defaults delays; `issue.clj:43-45`
484 ms docstring; `config_functions_test.clj:8` 13.05 s; the four landed
pieces (§9).

| Audit claim | Cited | Verified at HEAD | Verdict |
|---|---|---|---|
| 14 union copies | 8 in error.clj + kernel + admit + effect.edn | 14 copies at 6 files; six different member sets | **corrected — §4b** |
| `machinery-namespace-prefixes` | `error.clj:484-490` | `def` at `:484`, prefix vector at `:491` | off by one |
| `seon.env` atom | `env.clj:71`, `:105` | `environment-state` `:81`, `replace-environment!` `:107`, `environment-state?` `:74-80` (asserts `IAtom`), generator `:69` | off by ~10 |
| dynamic var read at 7 sites | `effect.clj:482 551 793 826 828 835 846` | **16** reads: those plus `:939 :940 :949 :953 :962 :992 :996` and the `binding` `:514` | **corrected** |
| `issue.clj` regex note parser | sci-turn-render `:929` | zero regexes in `issue.clj`; `:929` is a pull pattern | **falsified — §10 row 1** |
| 93 dials | — | 92 distinct `:seon.config*` keys (99 matching lines), 86 `:seon.config/dial` markers | **corrected** |

---

## 2. Reference-code seams — what each GUARANTEES

| Seam | file:line | Guarantee (fact) |
|---|---|---|
| `malli.error/default-errors` | `reference-code/malli/src/malli/error.cljc:44` | A map keyed by `::m/missing-key`, `::m/limits`, `::m/tuple-size`, `::m/invalid-type`, `::unknown`, predicate SYMBOLS (`'vector?`) and a few schema types; each value is `{:error/message {:en …}}` or `{:error/fn {:en (fn [error options] …)}}`. It has NO entries for keyword types such as `:int`, `:string`, `:keyword`. |
| `malli.error/error-message` | `:288-306` | Ten-step fallback in fixed order: schema properties → type-properties → `(errors type)` → `(errors (m/type schema))`, each first in `locale` then `default-locale`, then `::unknown` twice when `:unknown` is not false. Returns `nil` when every step misses and `{:unknown false}` is passed. |
| `malli.error/humanize` | `:374-390` | Consumes an `explain` result `{:value :errors}`; returns `nil` when `:errors` is nil; otherwise pushes `(wrap (assoc error :message message))` into the value's own SHAPE at each error's resolved path (`-push-in`), default `:wrap :message`, default `:resolve -resolve-direct-error` (`:307`). Output mirrors the input structure; it is not a flat list. |
| `malli.core/explain` | `:2659-2665` | "Creates the `explainer` for every call" — no caching; `(explainer ?schema options) value [] []`. Each problem carries `:schema :value :in :path` (+ `:type` when set). |
| `:or` explainer | `:996` (`-or-schema`), explainer body `:1015-1022` | On the FIRST branch that leaves the accumulator identical (i.e. validates), it returns `(reduced acc)` — no problems. When every branch fails, the accumulator carries the problems of ALL branches. A failure against an N-member `:or` therefore produces problems from up to N branches. |
| `:multi` | `:1861` (`-multi-schema`), registered `:3034` | Dispatch-value schema selection; the dispatch function is a schema property, not a stamp on the value. |
| Datahike `:db/tupleType` | `reference-code/datahike/src/datahike/schema.cljc:167-168` | Declared `{:db/valueType :db.type/value :db/cardinality :db.cardinality/one}` — a homogeneous tuple's element type. `db.cljc:811-826` validates it: `:db/tupleType` must be a KEYWORD, `:db/tupleTypes`/`:db/tupleAttrs` must be VECTORS; `db.cljc:811` dispatches on whichever of the three is present. Seon's bridge emits `:db/tupleTypes` only (`src/seon/schema/datahike.clj:174`); it never emits `:db/tupleType`. |
| Datahike `:db.type/any` | `schema.cljc:87`, absent from the `:db.type/value` set at `:35-55` | Exists as a value type but is not a member of the admissible-value set — the malli/datahike audit's fork item. |
| Datahike tx-meta | `reference-code/datahike/src/datahike/db/transaction.cljc:175`, `:321-359` | A user-supplied `:db/txInstant` inside `:tx-meta` wins over the transactor's; the transaction report carries `:tx-meta` back (`:359`); secondary/vt-pushdown reads provenance from the report's `:tx-meta`, not from domain datoms. |
| konserve `bget` / `bget-range` | `reference-code/konserve/src/konserve/core.cljc:634`, `:658` | Streaming binary read by key; `bget-range` reads a byte range without materializing the whole blob. `bassoc` is the write half (`:6`). |
| Clojure `ex-info` / `ex-data` | `reference-code/clojure/src/clj/clojure/core.clj:4924`, `:4933` | `ex-data` returns the map given to `ex-info` (nil for other throwables). |
| `Throwable->map` | `reference-code/clojure/src/clj/clojure/core_print.clj:473` | Datafies a throwable into `{:cause :via :trace :data}`; `:via` is the cause chain, `:trace` a vector of `[class method file line]` arrays — the same shape `:seon.error/frame` stores. |
| `clojure.core.server` prepl | `reference-code/clojure/src/clj/clojure/core/server.clj:228` | The io-prepl the MCP `jvm` mode evaluates through (§12). |

---

## 3. `:seon.error/kind` and `:seon.error/class` sites

### 3a. Counts

`kind` total = **306 src + 443 test + 46 script + 4 bin = 799**; `resources`
carries **0**. `class` markers are a SEPARATE 183 (`resources` 156, `test` 24,
`src` 3). The owner brief's "799 sites" is `kind` alone.

**Live fact (one read-only `eval_clj`, cluster `default`, jvm mode, this
session):** a `datoms` scan on `:seon.error/kind` returned
`Bad entity attribute :seon.error/kind … not defined in current schema`
from `datahike.db.utils`. The attribute is **absent from the live installed
schema**. Every one of the 799 sites writes or reads a key nothing stores.

Per-file `src` counts (37 files): `turn.clj` 60, `schema.clj` 29,
`fn.clj` 25, `cluster.clj` 25, `db.clj` 24, `cluster/message.clj` 17,
`cluster/agent.clj` 17, `schema/edn.clj` 12, `agent.clj` 11,
`cluster/prompt.clj` 10, `edit/jvm.clj` 6, `cluster/registry.clj` 6,
`test/runner.clj` 5, `schema/datahike.clj` 5, `cluster/wake.clj` 5,
`schema/internal.cljc` 4, `eval/drive.clj` 4, then 3 each in `run.clj`,
`print.cljc`, `note.clj`, `eval.clj`, `cluster/source.clj`,
`cluster/reply.clj`, `cluster/export.clj`; 2 each in `search.clj`,
`reconcile.cljc`, `issue/opening.clj`, `fs/jvm.clj`, `fn/schema_shape.clj`,
`context.clj`, `cluster/store.clj`; 1 each in `repl.clj`,
`issue/detect.clj`, `fs.clj`, `fn/signature.cljc`, `fn/analyzer.clj`,
`artifact.clj`.

Largest test files: `cluster/turn_test.clj` 35, `db_test.clj`
26, `fn_test.clj` 25, `turn_test.clj` 23, `turn_loop_test.clj` 17,
`loop_proof_test.clj` 12, `dev/fresh_operator_test.clj` 12. Largest script:
`script/seon/fresh_operator.clj` 40.

`class` markers by file (top): `seon.schema.edn` 25, `my.fs.edn` 16,
`seon.fn.edn` 13, `my.web.edn` 11, `seon.schema.datahike.edn` 10,
`seon.schema.edn.edn` 8, `seon.cluster.registry.edn` 8,
`seon.cluster.source.edn` 7, `seon.reconcile.edn` / `seon.cluster.store.edn`
/ `seon.agent.edn` / `my.message.edn` 6 each.

### 3b. The two archived inventories vs HEAD

| Inventory | Snapshot it names | Delta to HEAD |
|---|---|---|
| `docs/prds/steward-platform/research/error-result-retirement-hunks-2026-09-23.md` (883 lines) | `b866bb39d` — its own header records uncommitted edits in `cluster.clj`, `cluster/source.clj`, `publication_inputs_test.clj` | Its quoted `src/seon/cluster.clj:2649-2715` block no longer lands at those lines; `cluster.clj` is dirty at HEAD. Its "Complete source inventory" (`:275`) is per-file and each per-file section must be re-anchored. Its authority is the SHAPE of the change (drop `:seon.error/data-edn`, stage to a result reference), not the line numbers. |
| `docs/prds/steward-platform/research/error-family-1a-2026-09-19.md` (3,877 lines) | landed as part of `796a76314` | Records the D12 supersession (`:205`), the D13 gate (`:493`), five "RESET NEEDED" blocks (`:286 :385 :768 :1086 :1681`) and an explicitly UNLANDED draft (`:1170`). Sections after `:1729` are marked blocked at a foreign overlay boundary — not landed code. |

Both carry hunks against pre-`796a76314` bytes in places; a spec author
re-anchors by SYMBOL (`grep -n '(defn <name>'`), never by their line numbers.

---

## 4. Construction sites and union copies

### 4a. 275 `error/diagnostic` construction sites by file (src)

`plan.clj` 24, `issue.clj` 22, `config.clj` 18, `test/runner.clj` 16,
`operator/state.clj` 16, `effect.clj` 15, `sci/eval.clj` 12, `render.clj`
10, `env.clj` 10, `edit.clj` 10, `shell/jvm.clj` 9, `render/web.clj` 9,
`program.cljc` 9, `operator.clj` 8, `flow.clj` 8, `schedule.clj` 7,
`call_preparation.clj` 6, then 5 each in `turn.clj`, `test.clj`,
`bootstrap.clj`, `blob.clj`, `my/program.clj`; 4 each in `sci/admit.clj`,
`maintenance.clj`, `db.clj`; 3 each in `sci/kernel.clj`, `schema.clj`,
`render/walk.clj`, `render/transcript.clj`, `cluster/source.clj`; 2 each in
`render/data.clj`, `instrument.clj`, `cluster/agent.clj`.

Singles: `sci/reader.cljc`, `render/lint.clj`, `fn.clj`, `eval.clj`,
`error.clj`, `context.clj`, `cluster.clj`, `await.clj`. Test sites: **9**.

Owned-file share: `plan.clj` 24 + `issue.clj` 22 + `config.clj` 18 +
`effect.clj` 15 + `env.clj` 10 + `blob.clj` 5 + `bootstrap.clj` 5 = **99 of
275**. The other 176 are other lanes' files.

**A live specimen of the shape**, captured verbatim from the MCP evaluation
this session (an instrumentation refusal returned to the caller):
`:seon.error/data` carried `diagnostic-layer :development-mcp`,
`diagnostic-operation 'evaluate-jvm`, `diagnostic-member :exception`,
`diagnostic-expected :successful-prepl-evaluation`, `diagnostic-offending
"clojure.lang.ExceptionInfo"`, `diagnostic-cause` = a verbatim copy of
`:seon.error/message`, `diagnostic-evidence {:seon.error/frame […]}` = a
verbatim copy of the top-level `:seon.error/frame`, and
`diagnostic-evidence-availability :seon.error/known`. Two of the eight are
literal duplicates of sibling members on the same map.

The static twin is `src/seon/env.clj:89-102`, where 5 of 7 evidence keys
restate a member already on the map.

### 4b. The 14 union copies — verified, and already drifted

| file:line-end | lines | distinct error keys |
|---|---:|---:|
| `src/seon/error.clj:89-126` | 38 | 101 |
| `src/seon/error.clj:236-273` | 38 | 101 |
| `src/seon/error.clj:333-366` | 34 | 92 |
| `src/seon/error.clj:862-895` | 34 | 92 |
| `src/seon/error.clj:910-943` | 34 | 92 |
| `src/seon/error.clj:1802-1839` | 38 | 101 |
| `src/seon/error.clj:1869-1902` | 34 | 92 |
| `src/seon/error.clj:2059-2092` | 34 | 92 |
| `src/seon/error/refusal.clj:8-33` | 26 | 78 |
| `src/seon/error/refusal.clj:90-115` | 26 | 78 |
| `src/seon/sci/kernel.clj:540-574` | 35 | 116 |
| `src/seon/sci/admit.clj:576-613` | 38 | 116 |
| `resources/seon/schemas/seon.effect.edn:195-458` | 264 | 264 |
| `resources/seon/schemas/seon.db.edn:9-30` | 22 | 69 |
| **total** | **695** | |

**Six different member sets across 14 copies.** Measured differences against
`error.clj:89`: the 92-key copies omit 9 `:seon.test*` keys
(`:seon.test/admission-error`, `/execution-error`, `/expired`,
`/not-runnable-error`, `:seon.test.run/immutable-error`,
`/unavailable-error`, …); `refusal.clj` and `db.edn` omit a further set
including `:seon.dev.mcp/jvm-exception-error`,
`:seon.render.data/no-such-path-error`,
`:seon.render.hiccup/unparseable-tag-error`; `kernel.clj`/`admit.clj` ADD 6
(`:seon.program/binding-error`, `/signature-error`,
`:seon.sci.admit/projection-failed-error`, …); `effect.edn` adds the
`my.background/*` and `my.edit/*` members. The audit listed 8+1+1+1 sites
and missed `refusal.clj`'s second copy and `seon.db.edn` entirely.

`seon.effect.edn:193-194` comments the mirror as deliberate:
"Polymorphic handler pass-through: explicit canonical facets per PRD 1q.
effect-test checks this manifest against the canonical fixture population."

---

## 5. The dials

92 distinct `:seon.config*` keys in `config/default.edn`; 86 carry
`:seon.config/dial true`. Classification column is the AUDIT's (§3.2
A/B/C), carried unchanged. "Readers" counts `src` files containing the
literal key.

| Dial | manifest | declaration | rdrs | reader files | cls |
|---|---|---|---:|---|---|
| `seon.config.db/keep-history?` | default.edn:4 | seon.config.db.edn:3 | 4 | cluster, db, registry, store | A |
| `seon.config.db/write-time-limit-ms` | :11 | seon.config.db.edn:2 | 1 | db | A |
| `seon.config.db/validation-node-limit` | :12 | seon.config.db.edn:1 | 2 | schema, db | A |
| `seon.config.flow/ping-timeout-ms` (=20) | :50 | seon.config.flow.edn:1 | — | flow | **B** |
| `seon.config.flow.compute/{concurrency,queue-depth}` | :17-18 | seon.config.flow.compute.edn:1,7 | 1 | flow | A |
| `seon.config.flow.io/{concurrency,queue-depth}` | — | seon.config.flow.io.edn | 1 | flow | A |
| `seon.config.agent/turn-completion-backstop-ms` (=600000) | :158 | seon.flow.edn:125 | — | flow/turn | **B** |
| `seon.config.agent/write-refusal-bound` | — | seon.config.agent.edn | 1 | — | **C** |
| `seon.config.agent/show-all-settings` | — | seon.config.agent.edn | 1 | — | — |
| `seon.config.operator/event-silence-backstop-ms` (=30000) | :323 | seon.schedule.fire.edn:52 | — | operator | **B** |
| `seon.config.shell/time-limit-ms` | — | seon.config.shell.edn:27 | 1 | shell/jvm:419 | A |
| `seon.config.shell/stdin-max-bytes` | — | seon.config.shell.edn:23 | 1 | shell/jvm:416 | A |
| `seon.config.shell/termination-grace-ms` (=1000) | :207 | seon.config.shell.edn:25 | 1 | shell/jvm:440 | **B** |
| `seon.config.shell/inline-output-bytes` | — | seon.config.shell.edn:7 | 1 | shell/jvm:365 | **C** |
| `seon.config.shell/preview-bytes` | — | seon.config.shell.edn:21 | 1 | shell/jvm:366 | **C** |
| `seon.config.shell/{home,path,lang}` | — | seon.config.shell.edn:1,9,15 | **0 literal** | read generically via `:seon.shell/environment` at `shell/jvm.clj:96` | **C** |
| `seon.config.render/coalesce-ms` (=16) | :362 | seon.config.render.edn:1 | — | render/web | **B** |
| `seon.config.render/issue-opening` | — | seon.config.render.edn | 1 | issue/opening.clj:26-36 | **C** |
| `seon.config.render.agent/{token-budget,max-depth,max-children}` | — | seon.config.render.agent.edn | — | render | A |
| `seon.config.render.agent/composition` | — | seon.config.render.agent.edn | — | render | **C** |
| `seon.config.eval/time-limit-ms` | — | seon.config.eval.edn:1 | — | sci/eval | A |
| `seon.config.eval.result/{max-bytes,max-depth,max-collection,max-string,max-source,max-nodes,blob-threshold}` | — | seon.config.eval.result.edn | — | print, sci/admit, cluster | A |
| `seon.config.fs/{max-read-bytes,max-write-bytes,max-glob-results,max-traversal-entries,max-depth,max-inline-bytes}` | — | seon.config.fs.edn | 5 | fs/jvm | A |
| `seon.config.web/{max-response-bytes,max-redirects,timeout-ms,max-inline-bytes}` | — | seon.config.web.edn | — | web/jvm | A |
| `seon.config.web/max-search-results` | — | seon.config.web.edn | — | web/jvm | **C** |
| `seon.config.ai/{max-tokens,prompt-token-budget}` | — | seon.config.ai.edn | — | ai | A |
| `seon.config.ai/timeout-ms` | — | seon.config.ai.edn | **0 literal** | — | A |
| `seon.config.ai/chars-per-token-prior` (=3.2) | — | seon.config.ai.edn | 1 | ai/tokens | **C** |
| `seon.config.ai/{temperature,top-p,frequency-penalty,presence-penalty,response-format,extra-body-edn,stop}` | — | seon.config.ai.edn:121+ | **0 literal** | consumed generically through the `:seon.ai/wire` schema property at `ai.clj:592-601`, `:620-639` | **C** |
| `seon.config.ai/{no-auth,no-provider,retain-reasoning,api-key-variable,endpoint,model}` | — | seon.config.ai.edn | 1-5 | ai.clj:403-496, :1641 | C / — |
| `seon.config.ai.retry/{base-delay-ms,multiplier,jitter-fraction,maximum-delay-ms,maximum-retries,maximum-total-delay-ms}` | :463-478 | seon.config.ai.retry.edn | — | ai | **C** (6 dials, one decision) |
| `seon.config.ai.backup/{endpoint,model,api-key-variable,timeout-ms}` | — | seon.config.ai.backup.edn | 1 ea | ai | **C** |
| `seon.config.error/max-evidence-bytes` | — | seon.config.error.edn | — | error, cluster | A |
| `seon.config.error/{recurrence-limit,escalate-to}` | — | seon.config.error.edn | — | error.clj, cluster.clj | **C** (escalation routes through the retired steward) |
| `seon.config.effect.background/time-limit-ms` | — | seon.config.effect.background.edn:1 | — | effect.clj:731 | A |
| `seon.config.message/max-chain` | — | seon.config.message.edn:1 | — | cluster/message | A |
| `seon.config.maintenance/{min-usable-bytes,log-max-bytes,log-retained-files}` | — | seon.config.maintenance.edn | — | maintenance | A |
| `seon.config.maintenance/min-usable-ratio` | — | seon.config.maintenance.edn | — | maintenance | **C** |
| `seon.config.run/max-episode-runs` | — | seon.config.run.edn:1 | — | turn | **C** |
| `seon.config.test/auto-check-cases` | — | seon.config.test.edn:1 | — | test | **C** |
| `seon.config.bootstrap/beyond-closure-token-budget` | — | seon.config.bootstrap.edn:1 | — | bootstrap | **C** |
| `seon.test-support/event-backstop-seconds`, `seon.test/long-ms`, `seon.test/time-limit-ms`, `seon.effect/time-limit-ms` | (test/effect namespaces, not `seon.config*`) | — | — | — | **B** |

Audit class totals as stated: **A 41, B 9, C 43**. The complete
key→line→reader machine-readable dump used to build this table is
reproducible with:
`python3` over `config/default.edn` + `rg -l -F <key> src` (the script is
given in §12).

`config/effective` is called at **93** sites in `src`; `config/defaults`
has **8** readers; `config/result-caps` is used 203× across src+test (audit
§3.1, not re-counted here).

---

## 6. The two task lifecycles, side by side

### 6a. Public API

| Concern | `seon.issue` | `seon.plan` |
|---|---|---|
| create | `create-tx` `:1093` (private) | `add!` `:1063` / `add-step-call` `:675` |
| start | `start-tx` `:1232`, `start!` `:1342` | `start!` `:1143` / `start-step-call` `:1101` |
| complete / settle | `exhaust-tx` `:1310`, `guard-call` `:1426` | `complete!` `:1083` / `complete-step-call` `:975`; `settle-call` `:952` |
| update | `add-tx` `:1358`, `add!` `:1408` | `update!` `:1185` / `update-step-call` `:1158` |
| link tests | `tests-tx` `:1452`, `tests!` `:1462` | — (reads `:seon.issue/tests`) |
| done decision | `tests-done-query` `:1028` (private), `done?` `:1042`, `done-query` `:1066` | `stale-issue-tests` `:791`, `run-issue-tests!` `:823`, `done-query-result` `:901`, `query-satisfied?` `:937` |
| reads | `issues` `:775`, `status` `:802`, `report` `:1476` | `plan` `:378`, `item` `:447`, `items` `:505`, `current` `:536`, `blocked` `:552`, `steps` `:563`, `ready` `:574`, `ready-subjects` `:586` |
| whole-tree reconcile | — | `input-entries` `:1202`, `refuse-duplicate-identities!` `:1222`, `refuse-duplicate-positions!` `:1242`, `refuse-dependency-cycle!` `:1267`, `compile-tree` `:1402`, `plan!` `:1576` |
| note ingestion | `words` `:41`, `note-opened` `:68`, `parse-note` `:103`, `citation-*` `:199-300`, `index-tx` `:338`, `index!` `:511`, `notes` `:142`, `note-path?` `:131` | — |
| detection | `subject-id` `:554`, `subject-row` `:565`, `detector-rows` `:629`, `generate` `:690`, `generate!` `:749` | — |
| identity migration | `adopt-tx` `:950`, `adopt!` `:1006` | — |
| render | `render-ai` `:912`, `render-html` `:920`, `status-text` `:880`, `status-view` `:900` | 3 pairs: `render-item-*` `:1683/:1715`, `render-ready-items-*` `:1742/:1750`, `render-plan-*` `:1813/:1849`, plus `format-*` `:1670/:1725/:1785` |

`seon.issue` 1,510 lines; `seon.plan` 1,886 lines (public fns listed above);
`seon/issue/detect.clj` 332 (`entity-map-without-pair` `:61`,
`public-without-doc` `:181`, `public-without-contract` `:228`,
`public-without-reaching-test` `:285`); `seon/issue/opening.clj` 232
(`candidate` `:67`, `source` `:190`, `context` `:213`); `src/my/issue.clj`
35 (`status` `:5`, `add!` `:13`, `tests!` `:26`); `src/my/plan.clj` 11
public pass-throughs.

### 6b. External callers

| Callee | Callers outside its own ns |
|---|---|
| `seon.issue/report` | `script/seon/dev/issues.clj:15` |
| `seon.issue/{status,add!,tests!}` | `src/my/issue.clj:11,24,34` |
| `seon.issue/subject-id` | `src/my/program.clj:262` |
| `seon.issue/exhaust-tx` | `src/seon/plan.clj:973` (`[:db.fn/call #'issue/exhaust-tx agent-id]`) |
| `seon.issue/adopt!` | `src/seon/cluster.clj:2090` (`requiring-resolve`) |
| `seon.issue/{index!,notes}` | `src/seon/cluster/source.clj:345,347` (`requiring-resolve`) |
| `seon.issue/done-query` | `src/seon/issue.clj:17` docstring records `seon.plan` reading it at LOAD time; used at `issue.clj:1218` |
| `my.issue/status` | `src/seon/issue/opening.clj:81` emits the FORM `(my.issue/status {…})` |
| `seon.plan/settle-call` | `src/seon/turn.clj:2270`, `:3575`, `:3612`, `:4838` (all `[:db.fn/call #'plan/settle-call …]`) |
| `seon.plan/run-issue-tests!` | `src/seon/turn.clj:3573`, `:3603`, `:4834` |
| `seon.plan/*` reads | `src/my/plan.clj:25,41,53,65,77,89,101` |

**The exact turn.clj span B2 must coordinate on** is the two symbols
`seon.plan/settle-call` (`plan.clj:952`) and `seon.plan/run-issue-tests!`
(`plan.clj:823`), reached from `turn.clj:2270, 3573, 3575, 3603, 3612,
4834, 4838` — seven call sites, no others.

### 6c. `seon.issue/subject-id`, verbatim (`src/seon/issue.clj:554-564`)

```clojure
(defn subject-id
  "The detected issue identity shared by generation and a prospective plan.

  The detector and the subject's installed identity value determine it;
  entity ids, titles and the proposed retraction do not participate."
  {:malli/schema
   [:=> [:cat :qualified-symbol
         [:tuple :qualified-keyword :seon.schema/value]] :seon.issue/id]}
  [detector [attribute value]]
  (id/id (into (sorted-map) {:seon.issue/detector detector attribute value})))
```

`subject-row` (`:565-585`) refuses unless exactly ONE supplied attribute is
in the detector context's `identities` set, and its docstring states an
entity id "would change under a refork and is refused".

### 6d. Schema surface

`resources/seon/schemas/seon.issue.edn` 142, `seon.issue.citation.edn`,
`seon.plan.edn` **13** (only `current-line`, `step-lines`,
`update-example`, `non-test-entity`, `non-test-error`, `refusal`),
`my.plan.edn` **296**, `my.plan.item.edn` **102**. The plan entity itself is
declared as a component of the agent:
`resources/seon/schemas/seon.agent.edn:156`
(`:plan [:and {:seon.db/component true :seon.db/component-schema
:my.plan/entity …`). `:seon.plan/refusal` enumerates 18 error keys — a
19th union copy of the same kind as §4b but scoped to plan.

---

## 7. Effects, env, request context

### 7a. `:seon.effect/*` readers outside `effect.clj`

| file | lines | what it reads |
|---|---|---|
| `src/seon/background.clj` | `:36 :49 :68-87 :107 :124-125` | `/id`, `/request-edn`, `/result-edn`, `/result-blob`, `/result-size`, `/duration-ms`, `/interrupted-at` — a backgrounded request's durable row |
| `src/seon/turn.clj` | `:1732` (`effect/interruption-stamps`), `:2002-2003` (`[?effect :seon.effect/run ?turn]`, `/form-ordinal`) | boot-recovery interruption stamps; per-turn effect ordinals |
| `src/seon/edit/jvm.clj` | `:73-74` | writes `:seon.effect/provenance {:seon.effect/file …}` — write-back provenance |
| `src/seon/db.clj` | `:3951`, `:3961` | `:seon.effect/capability` in a validator branch |
| `src/my/{fs,web,edit,shell,background,program}.clj` | — | the declared `my.*` surface; each is one `(effect/request! #'f request)` |
| `src/seon/{error.clj,error/refusal.clj,print.cljc,render/value.clj,fn.clj,instrument.clj,sci/*.clj,cluster/wake.clj,test/accretion.clj,shell/jvm.clj}` | — | `:seon.effect/…-error` KEYS inside union copies only, not row reads |

`effect.clj` structure: `*request-context*` `:42`; `receipt-state` `:53`,
`payload-face` `:60`, `receipt-identities` `:64` (retired "receipt"/"face"
spellings); `render-ai` `:74`, `render-html` `:104`; `reach-rules` `:142` +
`capabilities` `:151` (sole production caller
`src/seon/test/accretion.clj:98`); `open-call` `:247`, `write-back-adds`
`:278`, `settle-call` `:308`, `interrupt-call` `:386`,
`interruption-stamps` `:445`; `with-request-context` `:488`, `dispatch`
`:520`; `request*` `:788`; `request!` `:1046`.

### 7b. `*request-context*` readers — 16, not 7

`src/seon/effect.clj` `:482 :514(binding) :551 :793 :826 :828 :835 :846
:939 :940 :949 :953 :962 :992 :996`. Zero readers outside `effect.clj`.

### 7c. `seon.env` atom

`defrecord Environment` `:36`; `defonce empty-environment` `:38` (with the
comment that reloading a defrecord emits a new JVM class);
`environment-state-generator` `:69`; `environment-state?` `:74` (asserts
`clojure.lang.IAtom`); `environment-state` `:81` (returns `(atom
environment)`); `replace-environment!` `:107`.

External readers: `src/seon/cluster.clj:3178` (`replace-environment!`),
`src/seon/db.clj:1851`, `:4388` (`environment-state`),
`src/seon/sci/eval.clj:152`, `:157`, `:174`, `:2206`, `:2849`, `:2854`.
Nine call sites in three files, all outside B3's ownership except `env.clj`
itself.

---

## 8. `seon.search` callers, confirmed by grep

`rg -n 'search/(similar-identities|tokens|search|open!|close!|index-step|apply-report!|supplied-handle|rebuild!)' src script bin --glob '!src/seon/search.clj'` returns exactly four lines:

| site | call |
|---|---|
| `src/seon/cluster.clj:2861` | `#'search/index-step` as a flow proc step-fn |
| `src/seon/cluster.clj:3099` | `(search/close! (:seon.search/handle instance))` |
| `src/seon/cluster.clj:3137` | `(search/open! connection search-path)` |
| `src/seon/schema/admission.clj:332` | `(search/similar-identities declaration-key (remove …) 3)` inside `name-overlap-findings` |

`search/search` is called ONLY from `test/seon/search_test.clj:30` (and
named in strings at `:73`, `:208`). Other cluster wiring:
`cluster.clj:72` (require), `:789`, `:796` (`derived/lucene` path),
`:2859-2867`, `:2911`, `:2923-2924`, `:2990`, `:3022`, `:3094`, `:3097`.
`src/seon/schema.clj:188-195` names `seon.search/handle?` in a docstring
recording the 2026-09-16 `default` wedge. Sizes: `src/seon/search.clj` 571,
`test/seon/search_test.clj` 218; `tokens` `:123`, `similar-identities`
`:143` (span `:123-167`, 45 lines, pure).

---

## 9. The landed pieces the audit calls correct — verified

| Piece | file:line | Verified |
|---|---|---|
| D13 signature | `src/seon/error.clj:200-225` | `(defn- signature …)` at `:200`; docstring states "Incidental time, process, message and offending bytes never enter this tuple"; body hashes `[layer operation (sorted-set facets) throwable-class frame (sorted-map expected-key/expected-shape) path]` through `(id/id … 64)`. Matches D13 exactly. |
| recording | `:1683` | `(defn recording …)` at `:1683`; docstring: "Counts and notification decisions belong exclusively to commit-call's mid-transaction database". |
| commit-tx | `:1753` | `(defn commit-tx …)` at `:1753`; returns `(:seon.db/tx-data result)` or the refusal. |
| recurrence | `:1538` | `(defn- recurrence …)` at `:1538`; pulls `(:seon.error/occurrences :limit nil)` with `:seon.error.occurrence/count` — the explicit `:limit nil` defeats Datahike's silent 1,000-member pull cut. |
| declared-union validation at the wrapper | `src/seon/instrument.clj:775-808`, commit `796a76314` ("Declare instrumentation outputs and enforce complete error facets", 2026-09-19, 6 files, +398/−66) | After the call, when the value is a map satisfying `base?`, the wrapper computes `actual` via `error/facets`, compares with the arity's `::declared` set, and rejects when the intersection is empty — emitting `:seon.instrument/{fn,arity,returned-error,declared-facet-digest,declared-facet-count,actual-facet-count,declared-facets,actual-facets}`. `::base?` permission arities are exempt. |
| wrapper staleness | `:844-858` `current-wrapper?` | Compares var identity, policy, authored form, contract and the `:seon.instrument/definitions` map against the projection's forms. (AGENTS.md and the redesign plan cite `instrument.clj:593` for this; the seam is `:844`.) |
| `facet-keys` derivation | `:1921-1936` | Reads `:seon.schema.projection/forms`, keeps keys whose compiled schema is `:and` and `extends-schema?` `:seon.error/base`, memoized on the projection via `schema/projection-cache-value`. |

`:seon.error/fact` (`resources/seon/schemas/seon.error.edn:103-140`) today
declares `:seon.error/data-edn` **required** (`:122`), with
`:seon.error/data-size` `:123` and `:seon.error/data-blob` `:126` optional,
and `:seon.error/result-id` `:108` + `:seon.error/shown` `:109` optional.
`:seon.error/result-id`'s own description (`:27`) reads: "The result
identity whose live object is bound as `result/e<id>`; a value token, not
another entity identity."

---

## 10. Corrections to the audit, with evidence

| # | Audit / brief says | Evidence at HEAD | Correction |
|---|---|---|---|
| 1 | `issue.clj` has a "regex note parser" (`issue.clj:929 citation-pattern`, sci-turn-render §2c) | `grep -c 're-find\|re-matches\|re-seq\|re-pattern\|#"' src/seon/issue.clj` = **0**. `citation-pattern` at `:929` is a Datahike PULL PATTERN, not a regex. `parse-note` `:103-120` is hand-written `str/split-lines` + `.indexOf` field splitting; `words` `:41-60` is a hand-rolled ASCII character scanner over a boolean lookup table. | There is NO regex in `issue.clj`. The finding is a hand-rolled text scanner substituting for a declared fact (AGENTS §2.2), which is a different rule. The ONLY regex in any B3-owned file is `src/seon/error.clj:1472` — `(str/replace message #"\s+" " ")`, a whitespace collapse in a log/render line. |
| 2 | 14 union copies at 11 named sites | 14 copies at 6 files; `src/seon/error/refusal.clj` holds **two** (`:8`, `:90`), and `resources/seon/schemas/seon.db.edn:9-30` holds one the audit never listed | The inventory is 8 + 2 + 1 + 1 + 1 + 1. A 19th copy of the same shape exists at `resources/seon/schemas/seon.plan.edn:16` (`:seon.plan/refusal`, 18 members) — same class, narrower scope. |
| 3 | The unions are "copies" of one set | Distinct-key counts across the 14: 101, 101, 92, 92, 92, 101, 92, 92, 78, 78, 116, 116, 264, 69 — **six different sets** | The mirror has already drifted apart; a mechanical find-and-replace across all 14 is not behaviour-preserving without deciding which set each boundary meant. |
| 4 | `:seon.error/kind` retirement is a database question ("PRD §6 rules it a RESET item either way") | Live read, cluster `default`, this session: `datahike.db.utils` logs `Bad entity attribute :seon.error/kind … not defined in current schema` | The attribute is absent from the live installed schema. The 799 sites are pure code; no `kind` datoms exist to retract on `default`. |
| 5 | `effect.clj` dynamic var read at 7 sites | `grep -n '\*request-context\*' src/seon/effect.clj` → 16 lines | 16 reads (15 plus the `binding` at `:514`), all inside `effect.clj`. |
| 6 | Class C includes "16 `:seon.config/absent` provider passthroughs — a dial nobody needed" | `:seon.config.ai/{temperature,top-p,frequency-penalty,presence-penalty,response-format,extra-body-edn,stop}` have zero literal readers but are consumed generically by `ai.clj:592-601` `wire-setting-triples` → `:620-639`, driven by the `:seon.ai/wire` property on each declaration (`seon.config.ai.edn:128`). `:seon.config.shell/{home,path,lang}` likewise via the `:seon.shell/environment` property at `shell/jvm.clj:96`. | These 10 have a DERIVED reader, not no reader. Deleting them changes what reaches the provider wire / child environment. |
| 7 | `seon.env` atom at `:71`/`:105`; `machinery-namespace-prefixes` at `:484-490` | `environment-state` `:81`, `replace-environment!` `:107`; the prefix vector is on `:491` | Off-by-a-few line citations; symbols are correct. |
| 8 | 92 vs "93 dials" | 92 distinct `:seon.config*` keys in `config/default.edn` (99 matching lines; `:seon.config.ai/api-key-variable`, `/endpoint`, `/model` each appear under several provider rows), 86 `:seon.config/dial` markers in `resources` | Use 92 distinct / 86 marked. |
| 9 | sci-turn-render §4 proposes "one `seon.error/value?` predicate … dissolves every one" of the 179 inline guards | D12 (NSA:392) forbids a general predicate | The audit's remedy is superseded by the ruling; the guard count itself stands (`;; debt:` comments: **167** in `src`; lines mentioning `:seon.error/at`: 809). |
| 10 | Test line counts §5 | `error_test.clj` is **1,437** lines (audit implies ~90 deletable prose blocks within it); `issue_*` + `issue/detect_test` = 455+195+349+92+197 = **1,288**, not "1,091+" | Restated. Others verified exact: `error_class_schema_test` 182, `search_test` 218, `error_write_timing_test` 182, `returned_error_test` 62, `error_result_test` 160, `config_functions_test` 32, `config_application_test` 254, `my/test_test` 78, `bootstrap_drive_test` 84, `blob_error_test` 18, `blob_threshold_test` 16. Also in scope: `test/my/plan_test.clj` 607, `test/seon/plan_test.clj` 45. |
| 11 | — (new) | `seon.db/datoms` returned a value its own declared output contract refused: *"seon.db/datoms refused return value at []: expected a collection member satisfying a map or a tuple with 4 entries, got a collection member that is a vector"* (live, this session, `default`) | A live contract defect in `db.clj`'s declared output for `datoms`. Not a B3 file; recorded for A2. |

Declared `:seon.test/long-ms` bounds in this area, verified verbatim:
`config_functions_test.clj:8-9` 20,000 ms with the reason "measured 13.05 s.
The symbol query itself is 4.70 ms; writer-wide work remains a measured
defect"; `error_write_timing_test.clj:80-82` 60,000 ms;
`error_result_test.clj:99-101` 60,000 ms; `my/test_test.clj:13-14` 300,000
ms; `config_application_test.clj:147` and `bootstrap_drive_test.clj:28`
carry `:seon.test/long` with no `-ms`.

---

## 11. Open questions a spec author must decide

Neutral; evidence on both sides, no recommendation.

| # | Question | For | Against |
|---|---|---|---|
| 1 | Does `:seon.error/base` replace all 14 union copies, or does each boundary keep an explicit set? | ERR/PRD-PF §1.2 admits the base alias "at a genuinely polymorphic inspection boundary (a renderer, a recorder)"; `facet-keys` already derives the set live | D12 (NSA:392) wants each function to NAME the errors it can return; the six drifted sets (§10 row 3) mean the copies do not currently agree on what the boundary accepts, so one replacement silently widens some and narrows none |
| 2 | Open decision **10** (ERR:265-269): an error consumer needing to distinguish more than the schema's members express | The archived doc rules this a schema decision, "never improvised" | It is unresolved; the 167 `;; debt:` sites are the population that will surface it |
| 3 | Open decision **11** (ERR:265-266): a producer whose failure has no natural owner resource | Same; the doc asks for the candidate owner to be listed | Unresolved |
| 4 | Does `malli.error/humanize` + the value printer replace `problem-sentence` (`error.clj:1072`) and `schema-expectation` (`:977-1000`)? | `me/error-message` already has a 10-step fallback (`error.cljc:288`) and the malli audit proposes adding keyword-type entries to the FORK's `default-errors` | `humanize` returns a structure SHAPED LIKE THE VALUE (`:374-390`), not a sentence; ERR:148-168 declares a fixed one-line grammar (`<operation> refused <member> at [<path>]: expected …`), and `explain-problem` (`:1020`) assembles Seon-specific path/argument/fix parts `humanize` does not produce. Requires A1's fork entries to land first. |
| 5 | Replacement for the count/ordinal/omission triple: `:db/tupleType`, a blob, or something else? | Datahike declares `:db/tupleType` at `schema.cljc:167` and validates it at `db.cljc:822`; `seon.blob` already stores bulky payloads | Seon's bridge emits only `:db/tupleTypes` (`schema/datahike.clj:174`); `:db/tupleType` is never produced today, so admitting it is a bridge change (A1's file). Either choice changes stored shape → RESET. |
| 6 | Is `seon.plan` merged INTO `seon.task`, or does `seon.task` replace both `seon.issue` and `seon.plan` from scratch? | One family is ruled (NSA:D1) | `:my.plan/entity` is declared as a **component of the agent** (`seon.agent.edn:156`), with 296+102 lines of `my.plan*` schema and 11 agent-facing `my.plan` functions; `seon.issue` has no component relation. The two have different owning roots. |
| 7 | Where do the three `issue/detect` detectors live once issue-minting is deleted? | `my.program/breaks` (`src/my/program.clj:238`) already returns comparable read data, and `my/program.clj:262` already calls `issue/subject-id` | The detectors are whole-program queries with no declared bound; D2 requires a trigger to resolve to a task identity at the WRITER, which is not where a read lives |
| 8 | Do the 9 class-B dials each have their event as a datom today? | `:seon.config.agent/turn-completion-backstop-ms` stands for the turn-closed datom, which `:seon.turn/closed-tx` already is | `:seon.config.flow/ping-timeout-ms` (20 ms) stands for core.async.flow proc readiness, for which no Seon datom exists; `:seon.config.render/coalesce-ms` (16) stands for a browser frame, which is outside the process. Unverified per-dial; requires a live check. |
| 9 | Does the `seon.effect` durable row survive for synchronous requests? | Audit §4: the only readers are `background.clj` (backgrounded) and `turn.clj:1732`/`:2002` (boot recovery, per-turn ordinals) | `turn.clj:2002-2003` queries `[?effect :seon.effect/run ?turn]` + `/form-ordinal` for EVERY effect on a turn, not only background ones; deleting synchronous rows changes what that query sees |
| 10 | `seon.error` renderer split: which namespace owns `render-ai` `:1975` / `render-html` `:1989`? | The two `defonce`+`delay`+`requiring-resolve` cycle-breakers (`error.clj:37-41`, `db.clj:56-57`) exist only because `seon.error` is recorder AND renderer | The split crosses into `render/*`, which is B2's; the schema properties naming `seon.error/render-ai` appear on ~156 `resources` declarations (the `class` marker population, §3a) |

---

## 12. REPL access for a Codex lane — verified

| Fact | Evidence |
|---|---|
| Codex lanes get the `seon` MCP server | `.codex/config.toml:3-4` — `[mcp_servers.seon] command = "bin/mcp-server"`; `bin/mcp-server:6` execs `bb … -m seon.dev.mcp` |
| Tools exposed | `script/seon/dev/mcp.clj:848` `eval_clj`, `:861` `runtime_status`, `:868` `get_value` |
| `eval_clj` arguments | `code` (required, exactly one form), `root` `:852`, `cluster` `:853`, `read_only` `:855`, `mode` `:856` (`"jvm"` \| `"sci"`, default `jvm`), plus `namespace`, `session_id`, `timeout_ms` (max 120000) |
| `jvm` mode binds NO cluster custody | tool description: "It binds no cluster custody; at a development REPL, `(seon.operator/connection "default")` supplies the explicit connection to pass to `seon.db`." Confirmed working this session. |
| `sci` mode MUTATES the shared per-cluster ctx | tool description; `read_only` at `:674` maps to `:seon.dev.mcp/read-only?` |
| The raw prepl advertisement | `data/clusters/<name>/prepl.edn`, written by `script/seon/fresh_operator.clj:127-129`. Live content at `default`: `{:seon.boot/cluster-name "default" :seon.boot/prepl-host "127.0.0.1" :seon.boot/prepl-port 51887 :seon.boot/pid 10562 :seon.boot/start-instant #inst "2026-09-21T02:54:04Z" :seon.render.web/url "http://127.0.0.1:7994"}` |
| Scratch cluster | `bin/seon [--root PATH] start [CLUSTER]`; `bin/seon:7-14` requires `--root` to be an EXISTING directory; commands dispatched at `script/seon/fresh_operator.clj:3656-3663` (`start config export init status open stop down reset logs`). There is no `eval` or `repl` subcommand — evaluation is the MCP tool or the prepl socket. |
| Prepl connection cap | at most four processes probe one JVM's prepl concurrently (AGENTS §7) |

Regenerate §5 by iterating `config/default.edn`'s `:seon.config*` lines and
running `rg -l -F <key> src` per key; regenerate §4b's key sets with
`re.findall(r':[a-z][a-zA-Z0-9.\-]*/[a-zA-Z0-9\-]+', span)` over each span
in that table.
