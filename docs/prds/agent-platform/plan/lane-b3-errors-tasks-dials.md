---
type: plan
status: first pass (Fable, 2026-09-21) for astra review; clean write follows
created: 2026-09-21
tags: [agent-platform, lane-b3, errors, tasks, config, effects, env]
---

# Lane B3 — one error model, one task family, dials that mean something

Owned: `src/seon/error.clj`, `src/seon/error/refusal.clj`, `src/seon/issue.clj`,
`src/seon/issue/*`, `src/my/issue.clj`, `src/seon/plan.clj` (lifecycle),
`src/seon/config.clj`, `src/seon/effect.clj`, `src/seon/search.clj`,
`src/seon/env.clj`, `src/seon/bootstrap.clj`, `config/default.edn`, the
`seon.error*`, `seon.issue*`, `seon.plan`, `my.plan*`, `seon.effect`,
`seon.config*`, `seon.env`, `seon.search` schema resources, the
`:seon.error/kind` and `:seon.error/class` sites in every file not held by
another lane. Sources read end to end: the writer brief, data pack B3, the
errors/issues/config deletion audit, the synthesis, the goals note (§2d, §2e,
config rows, D1/D2/D3/D6/D8/D12/D13, §1k/§1o/§1q/§1r), the note sweep,
packs A1 §4d, A2 §9, B2 §7, C1 §3.

## 0. For the owner: what was dumb, and the simpler way

**Errors.** Today an error is built by copying itself. `error/diagnostic`
demands seven `diagnostic-*` keys and 85 % of the time `diagnostic-operation`
is the `operation` two lines up (`error/refusal.clj:37-74`; 275 sites; a live
specimen this session had `diagnostic-cause` = `message` verbatim and
`diagnostic-evidence` = `frame` verbatim). The set of error schemas a boundary
may return is copied by hand into 14 places (695 lines) and has already
drifted into six different sets — while `error/facet-keys` (`error.clj:1921`)
derives the true set from the registry in 15 lines. 799 sites still write a
`:seon.error/kind` that the live schema does not even store. When a fault is
recorded, the whole in-memory map is printed to a 55 KB EDN string and stored
beside the datoms that already say the same thing (live occurrence on
`default`: `:seon.error/data-size 55201`, `:seon.error/capped? true`); a Malli
problem path is exploded into three component entities with ordinal datoms
and 33 predicates/generators (`error.clj:2314-2658`) exist only to check that
the explosion can be reassembled. The renderer lives in the recorder, so two
`requiring-resolve` delays break a load cycle the split would not have.

The simpler way: an error is one flat map — when, where, who, an optional
message, the offending value — plus the members its own schema declares. It
is constructed by one additive function, validated once by the armed wrapper
against the union the function declared (already landed at
`instrument.clj:775-808`), recorded as one root per D13 signature with one
occurrence per (agent, turn), and the offending value is the value renderer's
shown text plus a `result/e<id>` reference — the same mechanism every
evaluation result already uses. Nothing is stored that the schema does not
declare. Work per error is proportional to the declared union of the one
function that returned it, never to the whole schema population.

**Tasks.** Two lifecycles exist for one noun: `seon.issue` (1,510 lines,
notes ingested from markdown by a hand-written character scanner that cost
484 ms per publication) and `seon.plan` (1,887 lines, a per-agent component
tree with its own reconcile). The database on `default` holds 1,882 issue
entities, every one from a note path, none with a detector, none assigned —
the directory and the database are two registries of the same thing. Done is
decided by re-running a whole-program detector to check one subject.

The simpler way: `seon.task` is one entity — linked facts plus an optional
agent. One transaction function turns any trigger (a detector finding, a
recurring fault, a merge conflict, an authored step) into a task identity and
either wakes the task's agent or creates task and agent together. Done is one
query. A plan step is a task with a parent. Notes never enter `src/`.

**Dials.** 92 config keys; 33 are tuned constants nobody derives from an
event and 9 stand in for an event the system already publishes (a turn's
`closed-tx`, a child's exit). Bounds belong to the seam that admits the work;
a timer beside an event is a guess.

## 1. Goal and the numbers that prove it

| Measure | Before (measured) | After (target) | Form |
|---|---|---|---|
| Owned src lines | 10,972 (`error` 2,790 · `issue`+`plan`+`my` 4,169 · `config` 926 · `effect` 1,053 · `search` 571 · `env` 531 · `bootstrap` 932) | ≈ 2,500 (§8 table) | `wc -l` |
| Owned schema lines | ≈ 2,400 | ≈ 900 | `wc -l resources/seon/schemas/{seon.error*,seon.issue*,seon.plan,my.plan*,seon.effect,seon.config*,seon.env,seon.search}.edn` |
| `:seon.error/kind` sites | 799 (306 src) | 0 | `rg -c ':seon.error/kind'` |
| `:seon.error/class true` | 172 in resources | 0 | `rg -c` |
| `diagnostic-*` key lines | 2,310 src | 0 | `rg -c ':seon.error/diagnostic-'` |
| union copies | 14 (695 lines) | 0 | `rg -c ':my.background/error :my.edit/error'` |
| bytes stored per recorded fault (live) | 55,201 EDN + blob 13,279 + root | datoms of the declared schema + one `result/e<id>` shown text ≤ profile | probe §4.1 |
| issue/task entities on `default` | 1,882 (all from notes, 0 detector, 0 agent) | only tasks with a live subject (owner audit of 369 notes) | probe §4.2 |
| done check for one task | whole-program detector run (`detector-rows`, `issue.clj:629`) | one scoped query, O(1 subject) | probe §4.3 |
| config keys | 92 (86 dials) | 50 | `rg -c ':seon.config' config/default.edn` |

Live probe recorded this session (form and value): `seon.error/facet-keys`
on a raw `datahike.api/db` value refused — `(seon.db/carried-projection
(datahike.api/db conn))` is `nil`; the projection rides only `seon.db/db`
values. The refusal itself carried `diagnostic-cause` = `message` and
`diagnostic-evidence` = `frame`, the duplication §0 names.

## 2. The data flow

### 2a. One error value

| Step | Data | Computed when | Carried where | Proportional to |
|---|---|---|---|---|
| construct | `{:seon.error/at :seon.error/layer :seon.error/operation ?message ?offending ?member ?cause + schema members}` | at the refusing function | the return value (or `ex-data` of the one throw at a `:panic` seam) | the one map |
| validate | declared union of the returning arity | at the armed wrapper after the call (`instrument.clj:775-808`) | the wrapper's `::declared` permission | **declared members only**: `(some #(valid? % v) declared)`, not `facets` over all ~116 keys then intersect (today's `:787`); the all-facets set is computed only on the refusal path for the message |
| identity | D13 signature (`error.clj:200-225`, unchanged) | at recording | `:seon.error/signature` (identity attribute) | one `id/id` over a 7-tuple |
| offending | value-renderer shown text + `result/e<id>` | at recording (`prepare`, open decision 21 kept: the constructor stays pure) | `:seon.error/shown`, `:seon.error/result-id` on the occurrence | the render profile, no second bound (`seon.config.error/max-evidence-bytes` deleted) |
| store | root `{signature layer operation frame exception-class}` + occurrence component `{id count first-at last-at process ?agent ?turn shown result-id}` | one `:db.fn/call commit-call` (`error.clj:1567`) | Datahike; provenance in tx-meta (`transaction.cljc:175`), never on the entity | the datoms the schema declares — no `data-edn`, `data-size`, `capped?`, `dropped-fault-*`, `proc`, `op`, `cid`, `throwable-class`, `steward`, `regressions`, `seon.instrument/*` mirrors |
| explanation | Malli problem → `:seon.error/path` (a value), `:seon.error/expected-key`, `:seon.error/expected-shape`; the full explain rides the `result/e<id>` | at the wrapper's refusal | the flat value | one problem; `location`/`segment`/`omission`/`key` components and 33 predicate defs deleted |
| recurrence | occurrence count sum | inside `commit-call` on `:db-before` | `:seon.error.occurrence/count` | `recurrence` (`error.clj:1538`, unchanged) |
| task | recurring fault → `seon.task/trigger-call` with detector `'seon.error/recurring` and subject `[:seon.error/signature s]` | inside the same transaction as the second occurrence | the task's `:seon.task/errors` ref | one lookup by identity (§2b) |
| render | `seon.render.error/ai` / `html` (B2) declared ONCE on `:seon.error/base` | at read | schema property | 275 property copies deleted; the pair is found by `extends-schema? :seon.error/base` (probe §6.2) |

The constructor: `(seon.error/error m)` — additive, pure, no id minting, no
rendering. Input `[:map [:seon.error/layer :qualified-keyword]
[:seon.error/operation :qualified-symbol] [:seon.error/message {:optional
true} :string] [:seon.error/offending {:optional true} :seon.schema/value]
[:seon.error/member {:optional true} :seon.error/member] [:seon.error/cause
{:optional true} [:or :seon.error/throwable :seon.error/base]]]`, open (the
schema's own members ride through); output `:seon.error/base`. It `assoc`s
`:seon.error/at`, and when `:seon.error/cause` is a Throwable, `:seon.error/frame`
and `:seon.error/exception-class` from it. `diagnostic-member` → `member`;
`diagnostic-cause` was in practice a keyword kind (`:seon.issue/not-found`)
— under D3 the schema is the meaning, so it is deleted at the site, and
`cause` survives only as the wrapped Throwable or upstream error value.
`diagnostic-layer/operation/offending/expected/evidence` are the base members
already on the map.

Kinds and classes: pure code. `kind` is absent from the live schema (pack
§3a); 306 src sites drop the key or convert `(:seon.error/kind x)` guards to
the callee's distinguishing required member (`;; debt:` sites, 195 today,
each converted or deleted — never a predicate, D12). 172 `:seon.error/class
true` markers deleted; `error_class_schema_test` deleted.

Union copies: the 14 sites are all pass-throughs (cause-chain walk,
stored-observation restore, kernel `failure-value`, admit decode, effect
`request-result`, `seon.db/error-result`). PRD-PF §1.2 admits `:seon.error/base`
"at a genuinely polymorphic inspection boundary"; the wrapper already exempts
`::base?` arities (`instrument.clj:800`). Every domain function still names
its union. The `refusal_test` drift check dies with the mirror. This answers
pack open question 1: the copies cannot be reconciled because they disagree;
the boundary they guard never dispatched on the set.

Load cycle: `error.clj:40` and `db.clj:52-60` delays exist because
`seon.error` renders. Everything from `schema-expectation` (`:977`) through
`index-refusal-prose` (`:2298`) plus `render-*`, `faults-*`, `notice`,
`log-line` moves to `seon.render.error` (B2); `seon.error` then requires no
render namespace and `seon.db` requires `seon.error` directly. The
`schema-expectation` case table (`:977-1000`) is deleted when A1's
`default-errors` entries land (pack A1 §4d: ten of seventeen already present).

`:panic`/`:record` (§1k): one dial `:seon.config/on-core-error`, read at the
fault committer (`cluster.clj:2709`, B1) and `db.clj:3228` (A2). B3 changes
nothing there; `:seon.config.error/escalate-to` and `/recurrence-limit`
(escalation through the retired steward) are deleted — recurrence opens the
task (row above) instead of a message to `"root"`.

### 2b. One task family

Entity `:seon.task/task` (`resources/seon/schemas/seon.task.edn`, new file,
replaces `seon.issue.edn`, `seon.issue.citation.edn`, `seon.plan.edn`,
`my.plan.edn`, `my.plan.item.edn`):

| Attribute | Type | Deletion dial | Note |
|---|---|---|---|
| `:seon.task/id` | string, identity | — | `seon.task/subject-id` = `seon.issue/subject-id` verbatim (`issue.clj:554-564`, C1 §3) for detected tasks; `id/id` of `[title subject]` for authored |
| `:seon.task/title`, `/problem` | string | — | problem is the instruction text |
| `:seon.task/severity` | enum blocker/friction/cleanup | — | the one closed-set exception (AGENTS §3); ranks the index |
| `:seon.task/detector` | ref → `:seon.fn` | optional, sweep | present on detected tasks |
| `:seon.task/subject` | `[:tuple :qualified-keyword :seon.schema/value]` as a VALUE | — | the observed identity (G2); the ref lives in the typed set below |
| `:seon.task/functions` `/tests` `/errors` `/namespaces` | `[:set :seon.db/ref]` | optional, sweep | the linked facts; `:seon.render/units` |
| `:seon.task/agent` | ref, `:seon.wake/listen true`, `:seon.wake/opens-turn? true` | optional | as `:seon.issue/agent` today (`seon.issue.edn:agent`) |
| `:seon.task/updated-tx` | ref, listened | optional | a repeat trigger asserts `"datomic.tx"` here: the existing agent wakes, no new task, agent or message (D2) |
| `:seon.task/budget`, `/budget-exhausted-tx`, `/resolved-tx`, `/created-by` | as issue today | — | T1/T4 unchanged |
| `:seon.task/parent` | ref → task | optional, sweep | a plan step; `:seon.task/needs [:set ref]`, `:seon.task/position :int` |

Deleted with the note pipeline: `status` (open = no `resolved-tx`, derive or
die), `opened`, `path`, `keys`, `files` + citation component, `runs`,
`issues`, `members`, `unresolved`, `commits`. Live evidence: 1,882 issue
entities ≈ 489 open + 1,398 archived notes — the indexer walks `archive/`
too (inferred from the count, not verified by path).

| Mechanism | Data | When | Where | Proportional to |
|---|---|---|---|---|
| **D2 writer** `seon.task/trigger-call [db request]` | `{detector subject severity title problem ?budget ?created-by}` | inside the caller's transaction (fault committer, detector schedule, merge writer, `my.task/add!`) | `:db.fn/call` | one identity lookup: absent → task + agent (+ first turn, the agent row shape `issue.clj:1093-1230` today, composed from B2's agent creation tx) ; present with agent → `updated-tx` only; present without agent → agent |
| done `seon.task/done?` | tests verified on current reach (`tests-done-query`, `issue.clj:1028`, kept) OR detector scoped to the ONE subject returns nothing | at settlement | query | detectors take `{:seon.task/subject [a v]}` and add `:in ?subject` — today `done?` re-runs the whole-program detector (`issue.clj:1042-1060`) |
| settle `seon.task/settle-call [db agent-id]` | tasks of this agent with `done?` → `resolved-tx "datomic.tx"`, budget exhaustion → `budget-exhausted-tx` + root task | turn close | replaces `plan/settle-call` (`plan.clj:952`) + `issue/exhaust-tx` (`:1310`) | the agent's open tasks |
| test run | the task's stale tests (`stale-issue-tests`, `plan.clj:791`) | turn close, before settle | `seon.test/run-owned` (B4's seam) — `run-issue-tests!` (`plan.clj:823`) is deleted; the 7 `turn.clj` sites (B2 §7) call `seon.task/run-tests!` + `seon.task/settle-call` | stale tests only (reach digest) |
| detectors | `public-without-doc`, `-contract`, `-reaching-test` (`issue/detect.clj:181,228,285`) | when a root `seon.schedule.task` row fires (`schedule.clj:331 fire-call`) and calls `seon.task/trigger-detector!` | `seon.task` (queries, ~25 lines each) | `generate`/`generate!` (`issue.clj:690,749`) and publication-time minting deleted; findings are rows only through `trigger-call` |
| template | render pair `seon.task/render-ai` / `render-html` + `:seon.render/units` | at read | the schema property | `issue/opening.clj` (232 lines, dial `:seon.config.render/issue-opening`, three candidates) deleted: the 160-byte namespace picture won the 7-opening trial (goals §2d) |
| conflict task (D6/D8) | detector `'seon.program/conflict`, subject `[:seon.fn/sym x]`, problem = both sources + basis commit id rendered by `seon.program/history` | the merge writer (B1) | `trigger-call` | fingerprint identity ⇒ one instance |
| plan step | a task with `parent`/`needs`/`position`, agent = author | `my.task/add!` | same entity | `plan!`/`compile-tree`/`refuse-*` (`plan.clj:1202-1576`, 375 lines) deleted; current step DERIVES as the lowest-position open task with no open `needs` (`derived-frontier`, `plan.clj:265`, kept as one query) |

Consequence of merging the plan: `:seon.agent/plan` (`seon.agent.edn:156`,
a component) is deleted; steps no longer cascade with the agent — agents are
never retracted (goals §3), so nothing is lost; readers at
`cluster/agent.clj:174,312`, `cluster/status.clj:116`,
`render/transcript.clj:1244-2136` (B2) become `(seon.task/of-agent db id)`.
`bootstrap.clj:378-688` reads `plan/ready-subjects` for the generated opening
— that opening is a retired direction (goals §5 "the old generated-opening
machinery does not return"); see §6.4.

`my.task` (replaces `my.issue` + `my.plan`, B2 owns `my/plan.clj`): `tasks`,
`task`, `add!`, `update!`, `tests!`, `complete!` (authored step with no tests,
detector or done-query only — T1 governs `start!`, not an agent's own
sub-steps), `start!`. Each is one request map in, one entity map or declared
error out, contract naming its union.

Notes: the 369 live notes are audited by the owner against this spec's
deletion list (§5, §7); survivors become tasks through a one-off
`script/seon/dev/notes_to_tasks.clj` calling `my.task/add!` once per note,
deleted after. `bin/issues-index` keeps working over `seon.task/report`.

### 2c. Dials

Class A (41): kept, minus `seon.config.error/max-evidence-bytes` (§2a) → 40.
Class C (43): 33 deleted with declaration, manifest row and readers; 10 with a
DERIVED reader kept (pack §10 row 6): the seven `:seon.config.ai/*` wire
passthroughs read through the `:seon.ai/wire` property (`ai.clj:592-639`) and
`shell/{home,path,lang}` through `:seon.shell/environment` (`shell/jvm.clj:96`)
— absent-by-default, zero cost; moving them onto the provider descriptor row is
B2's item. Deleted: `ai.retry/*` (6; one provider, one policy: a failed
request tries the backup namespace once, then a typed error — B2 converts
`ai.clj`), `ai/chars-per-token-prior`, `shell/inline-output-bytes`,
`shell/preview-bytes` (one inline cut: `fs/max-inline-bytes`),
`run/max-episode-runs`, `test/auto-check-cases` (B4 agrees),
`bootstrap/beyond-closure-token-budget`, `agent/write-refusal-bound`,
`agent/show-all-settings`, `error/recurrence-limit`, `error/escalate-to`,
`maintenance/min-usable-ratio`, `web/max-search-results`,
`render.agent/composition`, `render/issue-opening`, `ai.backup/*` 4 →
`ai.backup/{endpoint,model,api-key-variable}` stay as the ONE failover row
(3 kept, `timeout-ms` deleted: the primary's `ai/timeout-ms` bounds both).

Class B — the event each stands for, named (AGENTS §2.3 keeps both halves:
the bound becomes the composition of the bounds the seam already declares):

| Dial | Event | Bound after |
|---|---|---|
| `agent/turn-completion-backstop-ms` (600 s) | `:seon.turn/closed-tx` datom | sum of the turn's admitted evaluation `eval/time-limit-ms` + `ai/timeout-ms` |
| `operator/event-silence-backstop-ms` (30 s) | worker exchange | dies with the worker pool (B4) |
| `shell/termination-grace-ms` (1 s) | `Process.onExit` completion | remaining `shell/time-limit-ms`, then `destroyForcibly` |
| `flow/ping-timeout-ms` (20 ms) | proc reply | `flow/ping` own default 1000 ms (`flow.clj:136-142`); dial deleted |
| `render/coalesce-ms` (16) | the SSE consumer's readiness | `(sliding-buffer 1)` newest-only, no timer (B2 converts `render/web`; B3 deletes the row) |
| `seon.effect/time-limit-ms` (per-request override) | handler completion | the capability's own declared bound (`fs`/`web`/`shell`/`background` dials) |
| `seon.test-support/event-backstop-seconds`, `seon.test/time-limit-ms` | terminal test facts | B4 |
| `seon.test/long-ms` | — | NOT an event dial: the brief rules it the only way up; audit misclassified |

`seon.env`: `defrecord`, `defonce` class pin, `environment?`,
`environment-state?`, `environment-state` (atom), `replace-environment!`,
two generators, `print-method` (`env.clj:36-115`) deleted; the environment is
a namespaced map with `:seon.env/environment [:map …]` (drop the `:fn`
wrapper) and one render pair. The atom's nine readers (`db.clj:1851,4388`,
`cluster.clj:3178`, `sci/eval.clj:152-2854`) are the "projection-state"
seam: A1 lands the projection carried on the database value, B2 lands the
environment bound into the SCI ctx at `fork-for-turn`; B3 lands the value
type and stops at those files if held. `effect.clj:42` `*request-context*`
(16 reads, all internal) becomes the first argument of `request*`; the
`my.*` capability functions declare `:seon.env/environment` and receive it
through call preparation (vocabulary: "supplied defaults"), so the far side
of a Flow hop carries the frame as data — which `with-request-context`
(`effect.clj:488-518`) already rebuilds from data.

### 2d. Effects, search, blob

| Item | Decision | Evidence |
|---|---|---|
| synchronous effect rows | not written; a row is opened only for `:seon.effect/background? true` and for capabilities declaring write-back provenance (`my.edit`: `:seon.effect/file`, `/form-span`, `/program`) | readers outside `effect.clj`: `background.clj:36-125`, `edit/jvm.clj:73-74`, `turn.clj:1732` (recovery stamps: only background rows can be open across a restart), `turn.clj:2002` (per-turn ordinals — B2 converts to the evaluation's shown text). Live `default`: 0 effect rows, so no RESET consequence |
| `receipt-state`, `payload-face`, `receipt-identities` (`effect.clj:53-72`) | deleted with the retired spelling | vocabulary |
| `reach-rules`/`capabilities` (`:142-169`) | kept; bound declared as the recursive rule's work over `:seon.fn/calls` reported as an elision | one caller `test/accretion.clj:98` |
| `seon.search` | deleted whole: `derived/lucene`, `IndexHandle`, atoms, lock, `index-step` proc, cluster wiring (`cluster.clj:789,796,2859-2867,2911,2923-2924,2990,3022,3094-3099,3133-3137`, B1's held file), `:seon.search/handle` env member, `seon.search.edn`, `search_test` | `search/search` has zero src callers (pack §8) |
| `tokens` + `similar-identities` (`search.clj:123-167`, pure) | moved to `seon.schema.admission` (A1's file; paired commit, stop if held) | sole caller `admission.clj:332` |
| `blob/verify-stored!` (`blob.clj:149-176`) | handed to A2 with this note: it re-reads the whole blob to verify konserve against the digest computed on the way in (`:121-148`) | A2 owns `blob.clj` |

## 3. Reading list

| Read | Guarantees |
|---|---|
| `reference-code/malli/src/malli/error.cljc:44-172` `default-errors` | keyed by `::m/missing-key`, `::m/limits`, predicate symbols and (pack A1) `:int :string :keyword …`; only `:vector :sequential :map :set :tuple :and :or :fn` absent — A1's fork entries |
| `error.cljc:288-306` `error-message` | ten-step fallback; `{:unknown false}` returns nil |
| `error.cljc:374-390` `humanize` | shaped like the VALUE, not a sentence; `:wrap`/`:resolve` options |
| `reference-code/malli/src/malli/core.cljc:2659-2665` `explain`; `:1015-1022` `:or` explainer | a failing `:or` carries every branch's problems — why the wrapper validates the declared union, not all facets |
| `reference-code/datahike/src/datahike/db/transaction.cljc:175`, `:321-359` | user `:db/txInstant` in tx-meta wins; the report carries `:tx-meta` — provenance never on the entity |
| `transaction.cljc:868-880` | `:db/retractEntity` / `:db.fn/retractEntity` are the deletion grammar; sweep semantics per the datahike skill |
| `reference-code/datahike/src/datahike/schema.cljc:167-168` `:db/tupleType`; `pull_api.cljc:16` `+default-limit+ 1000` | homogeneous ordered tuple exists; pull cuts silently at 1,000 (fork default changes it) — `recurrence` pulls `:limit nil` |
| `reference-code/clojure/src/clj/clojure/core.clj:4924,4933`; `core_print.clj:473` | `ex-info`/`ex-data`; `Throwable->map` `:trace` = the `:seon.error/frame` tuple |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-142` | `ping` default `timeout-ms 1000` |
| `reference-code/konserve/src/konserve/core.cljc:634,658` | `bget`/`bget-range` stream by key — content address is the verification |
| `src/seon/error.clj:200-225` signature, `:1538` recurrence, `:1567` commit-call, `:1683` recording, `:1753` commit-tx, `:1921` facet-keys | the landed D13 pieces; keep byte-for-byte except the stored member list |
| `src/seon/instrument.clj:775-808` | declared-union validation at the wrapper (A1's file; the declared-only probe §6.1 is a request to A1) |
| `src/seon/issue.clj:554-564`, `:1028-1040` | `subject-id` verbatim; `tests-done-query` |
| `src/seon/plan.clj:265-305` `derived-frontier` | the one plan query worth keeping |
| `src/seon/schedule.clj:331` `fire-call` | the existing mechanism that runs detectors on root's schedule |
| `src/seon/call_preparation.clj:14-70` | supplied defaults: how `my.*` receives the environment |
| `src/my/program.clj:238-262` | the read idiom a detector follows; already calls `subject-id` |

## 4. REPL protocol

`eval_clj` mode `jvm`, cluster `default`, `read_only true`. `conn` =
`(seon.operator/connection "default")`; `db` = `(seon.db/db conn)` (a raw
`datahike.api/db` value carries no projection — measured this session).

| # | Before | After |
|---|---|---|
| 4.1 fault shape | `(datahike.api/pull db '[* {(:seon.error/occurrences :limit 2) [*]}] [:seon.error/signature s])` — today: `:seon.error/data-size 55201`, `:seon.error/capped? true`, `seon.instrument/actual` a 7.7 KB print-node string | the same pull returns only declared attributes; `:seon.error/shown` ≤ the agent profile; `(count (keys occurrence))` ≤ 10 |
| 4.2 tasks | `(count (d/q '[:find [?e ...] :where [?e :seon.issue/id _]] db))` = 1,882; `:seon.issue/agent` 0; `:seon.issue/detector` 0 | `(seon.task/report db)` lists tasks with a live subject only; `(count …:seon.task/path…)` refused (attribute gone) |
| 4.3 done cost | `(time (seon.issue/done? db [:seon.issue/id x]))` — runs `detector-rows` over the program | `(time (seon.task/done? db [:seon.task/id x]))` — one scoped query, ≤ 10 ms |
| 4.4 trigger | — | `(seon.db/transact! conn [[:db.fn/call #'seon.task/trigger-call {…}]])` twice: second returns only the `updated-tx` datom |
| 4.5 constructor | `(error/diagnostic {…12 keys…})` | `(seon.error/error {:seon.error/layer :x/y :seon.error/operation 'a/b :seon.error/message "m" :x/member 1})` → 5-key map; returned through an armed function whose contract names `:x/y-error` passes; through one that does not → the wrapper's refusal |
| 4.6 unions | `(count (seon.error/facet-keys (seon.db/carried-projection db)))` | unchanged count; `(rg -c ':my.background/error :my.edit/error' src resources)` = 0 |
| 4.7 dials | `(count (seon.config/dial-attributes projection))` = 86 | 50 |
| 4.8 env | `(instance? clojure.lang.IAtom (:seon.sci.eval/projection-state cluster))` true | `(map? (seon.env/of ctx))` true, no atom in the env schema |

Every commit's debug probe: `runtime_status` on `default` answers healthy; a
fault query (4.1) returns; `/agent/root` renders.

## 5. The work, ordered as commits

Each leaves HEAD loadable (`clojure -M -e "(require 'seon.error 'seon.task
'seon.config 'seon.effect 'seon.env)"`) and `default` hot-reloadable. Held
files (`cluster.clj`, `fn.clj` dirty today) stop the slice at the file.

| # | Commit | Net | RESET |
|---|---|---|---|
| 1 | `seon.error/error` constructor added; `diagnostic` facade (`error.clj:304`) and leaf (`refusal.clj:37-74`) converted to call it; 99 owned construction sites converted; `diagnostic-*` keys dropped at those sites | −900 | no |
| 2 | 14 union copies → `:seon.error/base`; `refusal_test` drift check deleted; `seon.effect.edn:195-458` → `[:or :seon.schema/value :seon.error/base]` | −650 | no |
| 3 | `:seon.error/class true` markers (172) and `error_class_schema_test` deleted; `:seon.error/kind` sites in owned files (5) and every unheld file converted; `;; debt:` guards in owned files converted to the callee's required member | −300 | no |
| 4 | Renderer moved: `error.clj:977-1100, 1112-1500, 1863-1920, 1966-2310` → `seon.render.error` (B2 lands the namespace; B3 deletes from `error.clj` in the same publication); `error.clj:40`, `db.clj:52-60` delays deleted; dead prose builders and their tests deleted | −1,200 (moved 300) | no |
| 5 | Stored shape: `seon.error.edn` fact/occurrence reduced to the §2a member list; `location`/`segment`/`omission`/`key`/`projection`/`evidence`/`basis` resources and `error.clj:2314-2658` deleted; `prepare` stores shown text + `result-id`; `max-evidence-bytes` dial deleted | −700 | **RESET NEEDED** |
| 6 | `seon.task`: schema, `trigger-call`, `done?`, `settle-call`, `run-tests!`, detectors as scoped queries, render pair, `of-agent`, `report`; `my.task`; `turn.clj`'s 7 sites repointed (B2 agrees or stop); `seon.issue`, `issue/*`, `my.issue`, `seon.plan`, `my.plan`, `seon.issue*.edn`, `seon.plan.edn`, `my.plan*.edn`, `:seon.agent/plan` deleted; `notes_to_tasks.clj` script added | −3,600 | **RESET NEEDED** |
| 7 | Detector schedule rows for root (`seon.schedule.task`); recurring fault → `trigger-call` in `commit-call`; `recurrence-limit`/`escalate-to`/`steward` (`error.clj:1526`, `:2112`, `:2178`) deleted | −120 | no (rows seeded at reset) |
| 8 | Dials: 33 class-C declarations, rows, readers deleted; 5 class-B rows deleted with their event conversions (B2/B4 seams named per row); `seon.env` value type; `effect.clj` context argument | −450 src, −300 schema | **RESET NEEDED** (config attributes retired) |
| 9 | Effects: synchronous rows dropped, `receipt`/`face` spellings deleted, `seon.effect.edn` receipt entity narrowed to background + provenance | −350 | no (0 live rows) |
| 10 | `seon.search` deleted; `tokens`/`similar-identities` into `seon.schema.admission` (A1 paired); `:seon.search/handle` env member and cluster wiring (B1 paired) | −620 | no |
| 11 | `seon.bootstrap` cut to `seed-tx` + `supervision-tx` (§6.4 decides) | −800 | no |

## 6. Better than the floor — probes first

| # | Candidate | Probe that decides |
|---|---|---|
| 6.1 | Wrapper validates the DECLARED union only (`some` over ≤ 5 validators) instead of `facets` over all ~116 then `intersection` (`instrument.clj:787`) | A1 seam: count validator calls per returned error before/after with `mi/-f->original` counters; expect ≈ 116 → ≤ 5 |
| 6.2 | One render pair on `:seon.error/base` instead of 275 property copies across 53 resources | `seon.render` candidate selection: does it follow `:and` extension (`internal/extends-schema?`)? If not, B2 adds the one clause; else nothing to do. `rg -c 'seon.error/render-ai' resources` → 1 |
| 6.3 | `:seon.error/path` as one `:db.type/any` value (A2's fork admission) instead of a tuple family | if A2's admission lands first, store the vector; else store `:seon.error/shown` only and keep the path on the `result/e<id>` — no component either way |
| 6.4 | `seon.bootstrap` (932): the generated opening (`situation`, `next-entry`, intent acquisition, `beyond-closure-budget`) is the retired direction; `help`/`dir`/`doc` are `seon.sci.eval`'s (`:1194`, `:1222`, `:1467`) | `cluster/agent.clj:359` (B2) — is `bootstrap/situation` reached by a live turn on `default`? If the system turn 0 opening (`turn.clj:2191`) is the path, delete to `seed-tx` + `supervision-tx` (≈ 120). Three options if not: keep as is / move the opening into `seon.task/render-ai` / delete and let the namespace picture open |

## 7. Tests

| File | Lines | Disposition |
|---|---|---|
| `error_class_schema_test` 182, `search_test` 218, `error_write_timing_test` 182 (60 s bound on one write), `error/refusal_test` 90 (mirror check), `issue_test` 455, `issue_generate_test` 195, `issue_settlement_test` 349, `issue_deletion_test` 92, `issue/detect_test` 197, `my/plan_test` 607, `plan_test` 45, `plan_completion_test` 72, `bootstrap_test` 470, `bootstrap_drive_test` 84 | 3,238 | **delete**; replaced by `seon.task_test` (trigger idempotence, done as a query, scoped detector, settle) ≈ 150 and `error_test` reduced |
| `error_test` 1,437 (47 deftests) | | keep constructor, signature, recurrence, recording classes; delete prose blocks (`:763-841`), union, `diagnostic-*` and predicate tests → ≈ 350 |
| `returned_error_test` 62, `blob_error_test` 18, `blob_threshold_test` 16, `background_test` 51, `my/{fs,web,edit,background}_test` | | keep |
| `error_result_test` 160 | | keep; `long-ms 60000` (`:99-101`) removed — one bounded admission |
| `config_functions_test` 32 `long-ms 20000` ("measured 13.05 s; the symbol query is 4.70 ms") | | algorithm defect in `require-functions!` (`config.clj:737`): one query over `:seon.fn/sym` with `:in $ [?sym ...]`, bound removed |
| `config_application_test` 254 `:seon.test/long` (real cluster) | | genuinely long: keep with a number and reason |
| `config_test` 702, `effect_test` 1,032, `env_test` 384 | | collapse to the surviving mechanisms: manifest difference, request with a carried context, env as a map; ≈ 600 total |
| `my/test_test` `long-ms 300000` | | B4's (base-context acquisition O(program)) |

Test lines: ≈ 8,900 → ≈ 1,900. The lane runs only reaching tests in-process
(`seon.test/check`), never a suite.

## 8. Done, size target, landing note, stop rules

| File | Before | Floor (audit) | Target | Why the gap |
|---|---:|---:|---:|---|
| `error.clj` + `refusal.clj` | 2,790 | ≈ 900 | **550** (+300 in `seon.render.error`, B2) | rendering leaves; evidence-cap machinery (`:372-465`, `:594-668`) dissolves into the value renderer; predicates gone |
| `issue`+`issue/*`+`my.issue`+`plan`+`my.plan` | 4,169 | ≈ 3,660 | **530** (`seon.task` 450 + `my.task` 80) | one entity, one writer, done as a query, no note pipeline, no tree reconcile |
| `config.clj` | 926 | — | **650** | 146 diagnostic lines → 40; `require-functions!` one query |
| `effect.clj` | 1,053 | ≈ 800 | **500** | sync rows, dynamic var, ceremony, retired spellings |
| `search.clj` | 571 | 0 | **0** | 45 lines to admission |
| `env.clj` | 531 | ≈ 465 | **150** | record/atom/predicates/print-method gone |
| `bootstrap.clj` | 932 | — | **120** (§6.4) | retired opening generator |
| **owned src** | **10,972** | ≈ 6,900 | **≈ 2,500** | |

Done: every §1 row measured on `default` after a fresh reset with the §4
forms; `rg -c ':seon.error/kind\|:seon.error/diagnostic-\|:seon.error/class true' src test script bin resources` = 0 in unheld files; HEAD loads.

Landing note: `docs/prds/agent-platform/landing/lane-b3.md` — the §4 forms
with values, `wc -l` per file, the held-file list, RESET commits, and the
deletion list the owner audits the 369 notes against (`seon.issue` note
attributes, `issue/opening`, `seon.search`, `seon.plan` component, the 33
dials, the 9 event dials, `diagnostic-*`, `kind`/`class`).

Stop at: a held file (`cluster.clj`, `fn.clj` today); the B2 seams
(`seon.render.error`, `turn.clj` 7 sites, `my/plan.clj`, `:seon.agent/plan`
readers, `render/web` coalesce, `ai.clj` retry); the A1 seams
(`instrument.clj` declared-only validation, `admission.clj` move, `default-errors`
entries); the A2 seams (`:db.type/any`, `blob.clj`); the B4 seam
(`run-owned` for `run-tests!`); an unsettled design (§6.4; the `-cause`
deletion if the reviewer wants it kept; the retry-policy deletion).

Not settled here: whether `:seon.error/cause` as a ref to a recorded
upstream error earns its datom or the message suffices (three options in the
landing note); whether the plan step's manual `complete!` survives T1 (kept
for agent-owned steps only); whether the conflict task's two sources are
rendered from branches by commit id or copied as text (rendered, by B1's
`seon.program/history`).
