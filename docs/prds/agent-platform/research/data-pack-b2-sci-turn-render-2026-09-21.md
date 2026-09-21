---
type: research
status: draft
created: 2026-09-21
tags: [data-pack, lane-b2, sci, core-async-flow, hyperlith, seon.turn, seon.render, citation-verification]
---

# Data pack — lane B2 (SCI fork, turn loop, flow, rendering)

Facts for a spec author. Every row below was re-read in this session against
the working tree at branch `steward-platform` (`6d7192c22` plus the
uncommitted files in `git status`) and against the current submodule
gitlinks. No recommendation, no ordering, no done criteria.

**One measurement was taken** (`mcp__seon__eval_clj`, jvm, read-only, cluster
`default`, 2026-09-21) and is reported in §2.5. Every other number is a line
count, a grep count, or a line read out of a file.

Submodule gitlinks verified this session:

| submodule | `git log -1 --oneline` | branch |
|---|---|---|
| `reference-code/sci` | `fcbd8862 Add fact-safe SCI Var root installation` | `seon-env-hook` (our fork) |
| `reference-code/core.async` | `dc35f3e [maven-release-plugin] prepare release v1.10.874-alpha3` | detached |
| `reference-code/hyperlith` | `b08a8e8 Move back to original batching mechanism` | detached |
| `reference-code/datastar-clojure` | `1cef624 Chore: setting RC7` | detached |

Current sizes (`wc -l`), all matching the audit: `turn.clj` 5,417 ·
`render/web.clj` 3,694 · `sci/eval.clj` 3,382 · `render/transcript.clj` 2,443 ·
`render.clj` 1,891 · `print.cljc` 1,377 · `flow.clj` 1,364 ·
`cluster/agent.clj` 1,048 · `render/walk.clj` 1,016 · `render/ns.clj` 936 ·
`sci/kernel.clj` 750 · `render/value.clj` 680 · `cluster/wake.clj` 577 ·
`repl.clj` 517 · `cluster/prompt.clj` 411 · `oversight.clj` 300 ·
`run.clj` 149 · `render/block.clj` 96.

---

## 1. Audit citations verified, with drift

`deletion-audit-sci-turn-render-2026-09-21.md` citations re-read at HEAD.
Rows not listed verified exactly as written.

| audit citation | verified | note |
|---|---|---|
| `sci/impl/utils.cljc:356-380` generation + `bind-root!` | **corrected**: `next-generation` 356, `generation-meta` 359, `bind-root!` 362-379 | the span is 356-379; `:356-360` in the §1 table is off by one at both ends |
| `sci/core.cljc:276` `bind-root!` | **corrected**: 273 | |
| `core.async flow/impl.clj:245-260` | **corrected**: `proc` 243, `compute-timeout-ms` default 245, the futurized `.get` 258-260 | `futurize` itself is at `impl.clj:29-36` and is not cited anywhere in the audit |
| `core.async impl/dispatch.clj:82-116` | exact: `make-io-executor` 82, `create-default-executor` 91, `executor-for` 97, `exec` 114 | |
| `core.async flow.clj:96-107` / `:264-266` `:compute-exec` | **corrected**: the exec keys are documented at `flow.clj:78` and `:101`; `create-flow` is `flow.clj:76` | there is no `:264-266` exec block in `flow.clj`; `:compute-timeout-ms` is documented at `flow.clj:186-188` inside `process` |
| `hyperlith impl/datastar.clj:119-180` | **corrected**: `render-handler` 122-189; the dropping-buffer tap 143-145, `hk/as-channel` 148, `:on-close` 179-182 | 60-line figure holds (122-189 is 68 lines) |
| `datastar-clojure ->patch-elements-seq` | exact, one consumer: `src/seon/render/web.clj:1872` | |
| `sci/eval.clj:1399-1670` documentation block | **corrected**: `program-documentation` 1407, `documentation-unavailable` 1420, `documentation-schemas` 1479, `documentation-contract` 1491, `program-doc-var` 1621, `program-dir-var` 1636, `install-program-doc!` 1647 → span **1407-1658** | `install-declared-classes!` (1659) is class import, not documentation; the audit lists it in the *other* row |
| `sci/eval.clj:1173-1398` namespace loading, incl. `install-declared-classes!` | **corrected**: `classpath-locatable?` 1173, `host-namespace!` 1213, `load-core-namespaces!` 1311, `install-first-party-namespaces!` 1327, `install-host-namespace!` 1389; `install-declared-classes!` is at **1659**, outside the span | |
| `sci/eval.clj:3170-3381` candidate path | exact anchors: `fork-candidate-ctx` 3170, `install-candidate-function!` 3181, `evaluate-for-install` 3201, `accept-candidate!` 3227, `refuse-install` 3243, `evaluate-candidate` 3320 | **but see §9-C: it has three production callers** |
| `render.clj:692-889` invocation cache | **corrected**: 692-700 is `source-generation`, a public function with two live callers (`web.clj:1520`, `:2161`). The cache starts at `program-evidence-current?` **701** | deleting from 692 deletes `source-generation` |
| `render.clj:1719` `*walk-context*` | exact; 24 further reads at 1757-1874 | |
| `render/ns.clj:412-833` / `:416-833` ladder | **corrected**: `token-budget` 416 … `budgeted-html` 797, block ends 837 (`namespace-form` 838) | |
| `render/block.clj:61` | **corrected**: 61 is `surface-id`, the DOM-id derivation, not the block renderer | |
| `render/transcript.clj:1502-2442` extra views | anchors: `render-outline` 1629, `render-ledger-turn` 1949, `render-ledger-context` 1960, `render-ledger` 2329; `render-session-ai` 854, `render-session-html` 864, `render-session` 1371, `render-session-loading` 1343 | the session views start at **854**, not 1502 |
| `render/web.clj:1849-1916` packages | `join-package` **1849 and public**, `package-bytes` 1859, `frame-bytes` 1866, `next-package` 1877, `package-patches` 1905 | |
| `render/web.clj:558-1408` debug value | anchors: `generic-entity` 583, `declared-entity-units` 681, `graph-model` 922, `debug-graph-html` 970, `debug-applicable-candidates` 1074, `experiment-preview-html` 1094, `debug-selected-renderer-details` 1120, `applicable-renderers-html` 1211, `debug-found-value` 1278, `debug-found-values-html` 1357 | |
| `flow.clj:241-944` launcher | anchors: `with-current-arm` 241, `RefusingBuffer` 312, `execute-work!` 352, `io-terminal!` 427, `work-launcher-step` 517, `start-work-launcher!` 672, `submit!` 794, `submit!!` 871 | block ends at 944 (`dropped-fault-descriptor` 945) |
| `flow.clj:945-1130` fault committer | **corrected**: the committer runs 945-1132 (`fault-committer-proc` 1122) and the error fanout continues to **1364** (`monitor-graph` 1133 … `stop-error-fanout!` 1341) | |
| `cluster/agent.clj:95-123` buffer, `:442-464` mailbox, `:508` conn | exact: `CountedSlidingBuffer` 95, `toString` 113, `wake-channel` 122, `mailbox-step` 442, `graph-definition` 469, `:chan-opts` buffer 503 | the turn proc's `:workload :io` is at **453** (mailbox) and **938**; `agent.clj:494` in the audit is the docstring, not the declaration |
| `turn.clj:5053-5262` backstop | anchors exact: 5053, 5058, 5169, 5194, 5217; `turn-completion-error` 5011 | **`write-refusal-bound` 5103 and `write-refusal-error` 5135 also sit inside the span** and are public |
| `seon.eval.edn:8-9` declared pair | **corrected**: `:seon.render/ai seon.repl/render-ai` and `/html seon.repl/render-html` are at `resources/seon/schemas/seon.eval.edn:7-8` | |
| `instrument.clj:604-620` `supplied-projection` (malli audit) | not re-read this session — **unverified** | |

---

## 2. Reference-code seams: what each GUARANTEES

### 2.1 `sci/fork` and `:sci/generation`

`core.cljc:345-350` — `fork` is `(update ctx :env (fn [env] (atom (assoc @env :sci/generation (utils/next-generation)))))`.
Guarantee: a **new atom over the same namespace map** plus a fresh gensym
generation (`impl/utils.cljc:356`); no per-Var allocation.

`impl/utils.cljc:362-379` `bind-root!`: generation equal ⇒ mutate the Var
root in place; unequal ⇒ **copy** the Var (`new-var` + `generation-meta`,
`:359`), bind the copy, `swap!` it into `[:namespaces ns intern]` of this
env. Guarantee: the base Var object is never mutated through a fork, and the
copy carries the **fork's** generation.

Fact that follows: `(= (:sci/generation (meta v)) (:sci/generation @(:env ctx)))`
holds for exactly the Vars this ctx created or rebound, and for no inherited Var.

### 2.2 `namespace-state` / `install-namespace-bindings!`

`namespace-state` (751) returns `(:namespaces @(:env ctx))`: O(1) to obtain,
O(namespaces × interns) to walk. `install-namespace-state!` (762) replaces the
whole map. `namespace-bindings` (712) returns `{:aliases :imports :requires
:refers}` for one namespace, refers as **qualified symbols**;
`install-namespace-bindings!` (771) replaces those four and **throws** on a
non-nil import target that is not an installed class. Guarantee: resolver
structure is separable from interned Vars and installable as data.

### 2.3 `flow/futurize` + `:compute` workload

- `impl.clj:29-36` `futurize`: wraps `(apply f args)` in a `FutureTask`, executes it on `exec` (an `Executor` or a tag resolved by `disp/executor-for`), returns the `FutureTask`.
- `impl.clj:243-261` `proc`: `:workload :compute` ⇒ transform becomes `#(.get ^Future ((futurize step {:exec (spi/get-exec resolver :compute)}) %1 %2 %3) compute-timeout-ms TimeUnit/MILLISECONDS)`; default **5000 ms** (`:245`).
- Timeout reporting: `.get` throws `TimeoutException`; the `try` at `impl.clj:308-318` puts `#::flow{:pid :status :state :count :cid :msg :op :step :ex}` on the `::flow/error` out-port. **The deadline surfaces only as an `::flow/error` message**, never as a return value.
- `dispatch.clj:91-96`: `:compute` → named ctp, `:io` → virtual threads when available, `:mixed` → named ctp; `executor-for` (`:97`) memoized per tag, honours `clojure.core.async.executor-factory`.
- `flow.clj:78`/`:101` `create-flow` takes `:mixed-exec`/`:io-exec`/`:compute-exec`; `:186-188` `:compute-timeout-ms` is a `process` option valid only with `:workload :compute`; `:136-155` `ping`/`ping-proc` return per-proc status plus the `:ping-map-fn` state projection.

### 2.4 hyperlith batch send

`reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-189` `render-handler`:

`:143-145` one `a/tap` of `:hyperlith.core/refresh-mult` into
`(a/chan (a/dropping-buffer 1))` — the in-source comment gives the reason: the
mult distributes synchronously to every tap, so a slow handler must not block.
`:146` optional `:first-render` put. `:148-178` `hk/as-channel` `:on-open`
spawns a thread over a brotli `OutputStreamWriter` + `SSENewlineFilterWriter`
+ 16 KiB `BufferedWriter`; the loop takes one value, renders on the CPU pool,
appends `"event: datastar-patch-elements\ndata: elements "`, streams **the
whole new view**, flushes, sends the byte array. `:179-182` `:on-close` closes
the tap. Guarantee: no revisioning, no delta, no per-tab registry; loss is the
dropping buffer's and the client's idiomorph performs the diff.

### 2.5 Measured (one read-only eval, cluster `default`, 2026-09-21)

| quantity | value |
|---|---|
| namespaces in the live base SCI ctx (`sci/namespace-state`) | **419** |
| interned entries across them | **5,326** |
| `seon.sci.eval/base-bindings` over that ctx, 3 consecutive calls | **2.417 / 1.649 / 1.468 ms** |
| `sci/fork` of that ctx, 3 consecutive calls | **0.0202 / 0.0024 / 0.0015 ms** |
| agent contexts held by `default` | `("root")` |

Ratio, warm: the snapshot is ~600-1,000× the fork it precedes. Both are
sub-3 ms; the audit made no timing claim and none should be inferred beyond
these numbers. `regenerate-agent-context!` performs `base-bindings` on the
base **plus** a full second walk of the agent ctx's `namespace-state`
(`eval.clj:2131-2141`) plus a non-Var merge walk (`:2164-2172`); that total
was not measured.

---

## 3. `turn.clj` mechanism table, verified spans and callers

Spans are `(defn …)` line to the line before the next top-level form.

| mechanism | verified span | external callers (outside `turn.clj`) |
|---|---|---|
| `open?` 215 / `terminal?` 225 / `interrupted-warning` 233 / `opening-db` 268 | 215-304 | `opening-db` ← `render.clj:47` delay |
| transitions `open-call` 395, `close-tx` 432, `close-call` 442, `open-run-tx-call` 465, `require-open-run` 330 | 305-495 | writer `:db.fn/call` targets |
| `next-id` 525, `receipt-identity` 541 | 525-551 | `receipt-identity` ← `render/transcript.clj` + 6 test files |
| `source-rows` 566, `plan-call` 613, `open-tx` 677 | 566-693 | |
| `system-run-call` 694, `system-run-tx` 725, `append-generated-call` 737, `append-generated-tx` 796, `generated-run-tx` 803 | 694-823 | `system-run-tx` ← `bootstrap.clj:884`; `generated-run-tx` ← `bootstrap.clj:931` |
| receipt start/settle 824-1000 + record-evaluated 1369-1703 | as cited | `receipt-start-tx` ← 9 test files; `receipt-settle-tx` ← 9 test files; `receipt-start-call` / `receipt-settle-batch-call` ← **no caller outside `turn.clj`** |
| schema-change machinery 1001-1065 | `affected-schema-attributes` 1001, `schema-attribute-change-tx` 1034 | none outside |
| declaration shape helpers 1066-1124 | `cardinality-many?` 1066 … `declared-content` 1117 | none outside |
| `declaration-written-by-run?` 1125, `declaration-diverged-since-open?` 1162 | 1125-1202 | none outside |
| `relation-assertions` 1203, `declaration-projection` 1213, `row-tx` 1238 | 1203-1368 | none outside |
| render pair `render-ai` 1797 / `render-html` 1884 | 1797-1895 | schema-declared |
| since-diff `declared-sources` 1909, `latest-evaluations` 1956, `read-only-evaluation?` 1990, `system-plan` 2008, `issue-origin-read?` 2048, `generated-read-fault` 2053, `system-turn` 2103 | 1896-2285 | `system-turn` ← `render/web.clj` debug controls |
| `compact-call` 2286, `compact!` 2323, `virtual-turn!` 2330 | 2286-2345 | web debug controls |
| routed-problem settlement `unbound-value?` 2397, `problem-id` 2408, `planner-scoped-attempt?` 2416, `red-receipt?` 2429, `resume-artifact?` 2438, `form-owner` 2459, `form-settlement` 2539, `plan-settlement` 2582 | 2397-2613 | **each name also exists in `src/seon/problems.clj`** (see §9-A); `red-receipt?` is turn-local only; `form-settlement`/`plan-settlement` ← 3 test files + `test/seon/schema/datahike_parity.edn` |
| turn bound `outside-wake-t` 2639, `episode-runs` 2677, `max-episode-runs` 2714, `turns-left` 2742, `deferred-triggers` 2822 | 2614-2837 | `episode-runs` ← `problems.clj:255`, `repl.clj:26` |
| `next-agent-work` 2888, `more-agent-work?` 2967, `latest-answering-turn-t` 2980, `unanswered-wakes` 3007, `unanswered-triggers` 3060 | 2838-3083 | |
| delimiter repair `repairable-delimiter-error?` 3099, `repaired-span` 3107, `repair-source(s)` 3123/3145, `planned-sources` 3155 | 3084-3178 | none outside |
| `committed-attributes` 3179, `disposition` 3231, `gate-function-install` 3253 | 3179-3335 | `disposition` ← `turn_loop_test.clj:1046` |
| proc plumbing `submit-evaluation!!` 3358, `error-tx` 3378, `delivery-rows` 3421, `phase` 3448, `settle-batch!` 3585, `settle-batch-refusal!` 3691, `settle!` 3742 | 3336-3808 | `settle!` public |
| attempts 3809-4035 | `attempt-id` 3809 … `record-attempt!` 3912 | |
| `fold-*` 4036, `evaluation-request` 4084, `open-turn` 4110, `call-turn` **4175-4489 (315 lines)** | 4036-4489 | |
| `evaluate-sources` 4519, `preview-sources` 4636 | 4519-4675 | |
| `resume-turn` 4676, `close-turn` 4832, `generate-turn` 4846, `turn` 4936 | 4676-5010 | see below |
| `turn-completion-error` 5011, backstop 5053-5262 (incl. public `write-refusal-bound` 5103, `write-refusal-error` 5135) | 5011-5262 | `turn-completion-error` ← **`cluster/agent.clj:834`** (one caller) |
| `step` 5263 | 5263-5417 | the proc, ← `cluster/agent.clj:498` |

### Callers of the three turn siblings

| callee | call sites |
|---|---|
| `resume-turn` | `turn.clj:4303` (inside `call-turn`), `turn.clj:4930` (inside `generate-turn`), `turn.clj:4993` (the `turn` dispatch); test `fn_test.clj:544`, `fn_test.clj:1855` |
| `close-turn` | `turn.clj:4994` only |
| `generate-turn` | `turn.clj:4992`; `test/seon/cluster/bootstrap_resume_child.clj:27-29` names it as the boundary |

`turn` (4936) dispatches `:generate`/`:resume`/`:close` at `turn.clj:4992-4994`.

### What the routed-problem settlement calls into `plan.clj` (B3 boundary)

| `turn.clj` line | call |
|---|---|
| 2270 | `[:db.fn/call #'plan/settle-call agent-id]` (inside `system-turn`) |
| 3573 | `(plan/run-issue-tests! cluster agent-id)` |
| 3575 | `[:db.fn/call #'plan/settle-call agent-id]` |
| 3603 | `(plan/run-issue-tests! cluster agent-id)` |
| 3612 | `[:db.fn/call #'plan/settle-call agent-id]` |
| 4834 | `(plan/run-issue-tests! cluster (:seon.agent/id work))` (inside `close-turn`) |
| 4838 | `[:db.fn/call #'plan/settle-call (:seon.agent/id work)]` |

Exactly two `plan` functions are reached from `turn.clj`: **`plan/settle-call`** (4 sites) and **`plan/run-issue-tests!`** (3 sites). Three of the seven sites are outside the 2397-2613 block.

---

## 4. `src/seon/run.clj`, and `receipt` / `episode` still live

`src/seon/run.clj` is 149 lines: `render-namespace-ai` 13, `walkthrough` 37,
`usage-form` 81, `wait` 113, `complete` 133. `src/seon/turn.clj` already
occupies the name `seon.turn`.

### Requirers and textual references

| file:line | form |
|---|---|
| `src/seon/background.clj:4` | `(:require [seon.run :as run] …)` |
| `src/my/agent.clj:5` | `[seon.run :as run]` |
| `src/my/turn.clj:3` | `[seon.run :as run]` — the whole namespace delegates (`:15`, `:27`, `:34`, `:41`) |
| `src/seon/bootstrap.clj:863`, `:871` | agent-visible **source strings** `"(seon.run/complete \"Read …"` |
| `src/seon/run.clj:96` | Datalog literal `[?test :seon.fn/calls seon.run/walkthrough]` — a stored **symbol value**, not a var reference |
| `test/seon/turn_loop_test.clj:26` | `[seon.run :as my.turn]` (aliased to the agent-facing name) |

Test files carrying `seon.run/…` **inside source strings or quoted forms** (a
rename changes agent-visible bytes, not only code): `concurrency_independence_test.clj`
(138, 615) · `bootstrap_drive_test.clj:20` · `turn_continue_test.clj:217` ·
`turn_loop_test.clj` (268, 1046-1048) · `ai_stream_fold_test.clj` (132, 146,
165, 173, 244) · `cluster/reply_test.clj` (78, 168, 171, 186-188, 197-213,
377-379, 423) · `cluster/armed_test.clj` (309, 458) · `cluster/agent_test.clj`
(370, 636, 1169, 1218, 1513). 21 files contain the token in total.

### `receipt`, by owner

`turn.clj` 202 · `schedule.clj` 74 · `effect.clj` 67 (B3) · `maintenance.clj`
58 · `render/transcript.clj` 46 · `eval/drive.clj` 24 · `problems.clj` 13 ·
`bootstrap_drive.clj` 13 · `bootstrap.clj` 12 · `background.clj` 10 ·
`db.clj` 7 · `sci/eval.clj` 6 · `test/accretion.clj` 3 · `cluster/reply.clj` 3.

Declared `receipt` **attributes** live only in the maintenance family:
`resources/seon/schemas/seon.maintenance.receipt.edn` (`/fire` 25, `/task` 27,
`/handler` 29, `/request` 31, `/result` 38, `/error` 41, `/interrupted-at` 44)
plus `seon.maintenance.edn:30-32` (`/id`, `/completed-at`). **No
`:seon.turn.receipt/*` or `:seon.cluster.receipt/*` attribute exists** — the
turn-side `receipt` spelling is entirely in function names and locals.

Public `receipt`-named vars in `turn.clj`, external callers: `receipt-identity`
7 files · `receipt-start-tx` 9 · `receipt-settle-tx` 9 ·
`receipt-settle-batch-tx` 2 · `receipt-settle-call` 1 · `receipt-start-call`
and `receipt-settle-batch-call` **0**.

### `episode`

`:seon.agent/episode` — 15 sites in 5 files (`turn.clj`, `oversight.clj`,
`cluster/agent.clj`, `test/seon/turn_test.clj`,
`test/seon/cluster/agent_test.clj`). `episode-runs` — 48 sites; readers
outside `turn.clj`: `problems.clj:241,254,255,517,560,605`, `repl.clj:25-26,58-59,68,72`.
Config key `:seon.config.run/max-episode-runs` (`repl.clj:68`, `:72`).

---

## 5. Eleven render paths and six elision sites

| # | path | verified anchor | who calls it |
|---|---|---|---|
| 1 | contract-fitting selection + invocation | `render.clj:278` `namespace-candidates`, `:616` `selection-inspection` | the render entry points |
| 2 | the value renderer | `render/value.clj:269` `window`, `:536` `pager`, `:550` `prepare`, `:668/675` `render-ai|html` | evaluation, HTML |
| 3 | the sink/emit grammar | `print.cljc` (1,377 lines) | the value renderer |
| 4 | the REPL grammar | `repl.clj:50` `frame`, `:213` `response`, `:256` `text`, `:447/480` `render-ai|html` | declared pair on `seon.eval.edn:7-8` |
| 5 | walk acquisition + AI assembly | `render/walk.clj:123` `root-selector`, `:757` `neighborhood`, `:979` `history` | `render.clj:49` delay, web |
| 6 | HTML blocks | `render/block.clj:61` `surface-id` (96 lines total) | web morph target |
| 7 | namespace page AI+HTML with its own ladder | `render/ns.clj:895` `render-ai`, `:928` `render-html`, ladder 416-837 | route |
| 8 | transcript entries/history | `render/transcript.clj:676` `render-ai`, `:762` `history-entries`, `:802` `render-html` | `web.clj:1481`, `eval/drive.clj:285`, `bootstrap.clj:816` |
| 9 | transcript session/outline/ledger | `:854`, `:864`, `:1343`, `:1371`, `:1629`, `:1949`, `:1960`, `:2329` | `web.clj:3168-3171`, `:3188`, `:3235`, `:3275` |
| 10 | debug page value + experiment | `web.clj:583`-`1357` (ten fns, §1) | the debug route |
| 11 | prompt selection | `cluster/prompt.clj:266` `compose`, `:278` `select` | `turn.clj:55-56` delay |

Declared pairs naming `seon.render.transcript` in resources:
`seon.runtime.edn:4-5` (`render-history-ai|html`), `:11-12`
(`render-runtime-ai|html`), `seon.message.edn:29` and `:66`
(`inbox-form`, `message-form`), `seon.render.transcript.edn:4`, `:10`.
`seon.render.transcript/request-error` appears as a declared error member at
`seon.effect.edn:386`, `seon.render.edn:255`, and in 8 `error.clj` sites,
`sci/admit.clj:590`, `sci/kernel.clj:552`.

| # | elision site | verified span | what it stamps |
|---|---|---|---|
| 1 | value renderer window/pager | `render/value.clj:269-549` | the profile |
| 2 | print grammar elision values | `print.cljc:444`, `:457`, `:984`, `:1015` | `::requery-form` |
| 3 | prompt whole-unit selection | `prompt.clj:230` `dropped-elision` | `:seon.print/bound-by :seon.config.ai/prompt-token-budget` (`:242`) |
| 4 | namespace page ladder, AI **and HTML** | `render/ns.clj:416-837` | `omission-value` (`:428`) |
| 5 | walk connection elision | `render/walk.clj:211-254` | `:seon.error/layer :seon.render.walk/connections` (`:242`) |
| 6 | walk distance elision | `render/walk.clj:669-703` | **`:seon.print/bound-by :seon.render/distance`** (`:698`), `:seon.render.walk/limit`, `:seon.render.walk/continuation-subject` |

---

## 6. Mechanisms with no test, confirmed by grep over `test/`

| mechanism | src home | matches under `test/` |
|---|---|---|
| `same-invocation-evidence?` | `render.clj:821`, `web.clj` | **0** |
| `call-cache-evidence` (alias `render.clj:833`) | `render.clj`, `web.clj` | **0** |
| `retained-program-current?` | `render.clj:768`, `web.clj` | **0** |
| `RefusingBuffer` | `flow.clj:312` | **0** |
| `next-package` | `web.clj:1877` | **0** |
| `package-patches` | `web.clj:1905` | **0** |
| `budgeted-ai` | `ns.clj:614` | **0** — *not in the audit's list* |
| `budgeted-html` | `ns.clj:797` | **0** — *not in the audit's list* |
| `minimal-html-view` | `ns.clj` | **0** — *not in the audit's list* |
| `installation-covers-program-change?` | `sci/eval.clj:1052` | **0** — *not in the audit's list* |

For contrast: `CountedSlidingBuffer` → `test/seon/oversight_test.clj`;
`base-bindings` → `test/seon/sci/eval_test.clj`.

---

## 7. The seven turn-test namespaces

| file | lines | `deftest`s | ns docstring / evident class |
|---|---:|---:|---|
| `test/seon/cluster/turn_test.clj` | 3,729 | 59 | "Turn integration on canonical databases and real SCI contexts"; local provider replies, deliberate failure injection |
| `test/seon/turn_test.clj` | 2,284 | 34 | no docstring; requires `cluster`, `agent`, `db`, `eval`, `sci.core`, `datahike.api` — transaction/entity-level turn facts |
| `test/seon/turn_loop_test.clj` | 1,684 | 26 | "Sealed acceptance draft for the run loop (N3, C9)": pure parts (committed-attribute set, disposition reader, the one terminal transaction) + the crash walk as kill positions over facts |
| `test/seon/turn_work_test.clj` | 803 | 13 | "Acceptance tests for the agent's next-work derivation": table + generative property over isolated Datahike values |
| `test/seon/turn_continue_test.clj` | 225 | 9 | session continuation (`prove-session`, terminal dispositions) |
| `test/seon/turn_work_cost_test.clj` | 40 | 1 | `generated-state-agrees-with-the-writer`, times itself via `System/nanoTime`; reuses `#'work/with-database` from `turn_work_test` |
| `test/seon/turn_error_test.clj` | 21 | 1 | `system-turn-preserves-missing-agent-as-a-declared-refusal` |
| **total** | **8,786** | **143** | |

Two of the seven (`turn_work_cost_test`, `turn_error_test`) are single-test
files; `turn_work_cost_test` has a compile-time dependency on
`seon.turn-work-test`'s private fixture.

---

## 8. The arming / SCI-fork interface, exactly

**Where arming writes into a ctx.** `src/seon/sci/eval.clj:694-709`
`install-function-contract!`:

```clojure
(sci/bind-root! ctx sci-var
  (instrument/wrap-interpreted function-symbol spec-edn projection
                               on-core-error caps @sci-var …))
```

`ctx` is whichever context the install runs against — the base during
`acquire-program!`, the **agent's fork** during an agent's own install
(`turn.clj:3253` `gate-function-install` → `sci.eval/evaluate-candidate`
3320 / `accept-candidate!` 3227 → `install-candidate-function!` 3181).

**What the wrapper carries.** `src/seon/instrument.clj:521-522`: the wrapper
is returned `(with-meta wrapped (assoc (meta wrapped) interpreted-original original))`,
where `interpreted-original` is `::seon.instrument/interpreted-original`
(`instrument.clj:413`).

**What `:sci/generation` reads.** `sci/impl/utils.cljc:362-379`: `bind-root!`
compares `(:sci/generation (meta sci-var))` with `(:sci/generation @(:env ctx))`.
Unequal ⇒ copy the Var, stamp it with the **ctx's** generation
(`generation-meta`, `:359`), and intern the copy into the ctx's namespace map.

**The consequence today.** `sci/eval.clj:2121-2182`
`regenerate-agent-context!` selects private bindings by
`(= generation (:sci/generation (meta entry)))` (`:2138`) **and** by
`same-program-root?` (`:2115-2119`), which unwraps
`::instrument/interpreted-original` on both sides. The second test exists
because an armed program Var acquires the fork's generation the moment
`bind-root!` copies it — making generation alone report a program Var as
private. `base-bindings` (`:2100-2113`) is the snapshot the second test
compares against; it strips `:sci/generation` from each Var's meta (`:2109`).

`instrument.clj:844-858` `current-wrapper?` is the JVM-side re-arm
comparison (`:seon.instrument/var`, `/authored`, `/contract`, `/definitions`
vs the projection's forms); it does not read `:sci/generation`.

---

## 9. Corrections to the audit, with evidence

| # | correction | evidence |
|---|---|---|
| A | The routed-problem settlement names also exist in `src/seon/problems.clj`: `unbound-value?`, `problem-id`, `planner-scoped-attempt?`, `resume-artifact?`, `form-owner` each resolve in **both** files. The audit's row does not say which is definition and which is caller. `red-receipt?` is `turn.clj`-only; `problem-id` also appears in `render/transcript.clj` | grep over `src` |
| B | `render.clj:692` is not the cache boundary: `692-700` is `source-generation`, public, two live callers | `web.clj:1520`, `web.clj:2161` |
| C | The candidate install path is production-reachable, not unclaimed | `turn.clj:3270`, `:3320`, `:4593`; `contracts_fixture.clj:49`, `contracts_install_test.clj:26,42,50`, `core_functions_test.clj:57`, `custody_stability_test.clj:51` |
| D | `install-declared-classes!` is cited in the wrong row — it is at `eval.clj:1659`, outside `1173-1398` | `grep '^(defn' eval.clj` |
| E | The documentation block starts at 1407, not 1399 | `eval.clj:1407` |
| F | The transcript's "three extra views" start at **854**, not 1502 | `render-session-ai` 854, `render-session-html` 864 precede `render-outline` 1629 |
| G | `flow.clj`'s fault-committer row ends at **1364**, not 1130; the error fanout (`monitor-graph` 1133 … `stop-error-fanout!` 1341) is inside the block the row marks keep | `grep '^(def' flow.clj` |
| H | `turn-completion-error` has exactly one external caller | `cluster/agent.clj:834` |
| I | `write-refusal-bound` (5103) and `write-refusal-error` (5135) are public and inside the backstop span the audit lists as five private names | `turn_test.clj:2165,2212` reads `:seon.config.agent/write-refusal-bound` |
| J | The walk's distance elision is stamped as a **query-work** bound, which AGENTS.md §2.4 treats as a control separate from the AI profile ("a query-work cut is reported as its own elision naming the bound that made it"). The audit classifies the site "illegal — never the walk". Both texts read this session; they disagree | `walk.clj:698` writes `:seon.print/bound-by :seon.render/distance` with `:seon.render.walk/limit` and `/continuation-subject` |
| K | `seon.eval.edn`'s declared pair is at **7-8**, not 8-9 | file read |
| L | `render/block.clj:61` is `surface-id`, a DOM-id derivation with an injectivity requirement in its docstring — not the block renderer | file read |

---

## 10. Open questions for a spec author (evidence both ways, no recommendation)

| # | question | evidence for | evidence against / other side |
|---|---|---|---|
| Q1 | What does `seon.run` become? `seon.turn` is taken by the 5,417-line loop | the vocabulary table retires "run (for a turn)" in favour of "turn" (durable-goals §4) | `src/my/turn.clj` requires it and is agent-facing; `seon.run` requires 3 namespaces, `seon.turn` requires 27 and already holds six cycle breakers (`turn.clj:51-62`). A third name is grounded in the declared `:seon.turn/disposition` (`turn.clj:3231`); no evidence any specific third name is in use |
| Q2 | Does the agent-visible spelling change with the namespace? | `bootstrap.clj:863,871` emits `"(seon.run/complete …)"` into an agent's opening source, and stored evaluations on `default` hold those bytes; the prompt re-renders "the same bytes" (PRD §15) | database data is disposable by ruling (durable-goals §3). Not decided in any read document |
| Q3 | Walk distance/connection elision: delete both, delete the presentation half, or keep the query-work report | §9-J | AGENTS.md §2.4 and Datahike's silent pull cut at `pull_api.cljc:16` (cited by AGENTS.md, not re-read here) are what the report defends against |
| Q4 | Which half of the arming/fork interface moves (§8)? **A**: arm only at the base, so `bind-root!` never runs against a fork | `regenerate-agent-context!`'s second test disappears | `turn.clj:3253/3270/3320` install agent-authored contracted functions into the agent's own fork by design (§9-C) |
| Q4b | **B**: a fork-side `bind-root!` that preserves the source Var's generation | ~3 lines in `sci/impl/utils.cljc:362-379`; agent-authored Vars already carry the fork's generation before arming | unverified — no probe was run |
| Q5 | Can `acquire-program!`'s `:core` branch be `sci/copy-ns`? | the audit records it as "not verified" | `copy-ns` is a **macro** resolved at compile time (`core.cljc:492`, `-copy-ns` `:445`); `acquire-program!` (1771) runs at runtime over database rows. Whether arming survives such a bind was not probed here |
| Q6 | Turn-test collapse target | §7: 143 `deftest`s, 8,786 lines, 7 namespaces | `turn_work_cost_test.clj` depends on private vars in `turn_work_test.clj` (`#'work/with-database`, `#'work/configure-agent-bound!`) |
| Q7 | `:seon.agent/episode` as a name | retired in the vocabulary (durable-goals §4, the "never written again" list) | 15 live sites plus the config key `:seon.config.run/max-episode-runs` |

---

## 11. REPL access and the seed command

**A Codex lane reaches the REPL through the same MCP server this session
used.** `.codex/config.toml:3-4`:

```toml
[mcp_servers.seon]
command = "bin/mcp-server"
```

`bin/mcp-server:6` execs `bb --classpath script:src:resources -m seon.dev.mcp`.
Tools declared at `script/seon/dev/mcp.clj:848-875`: **`eval_clj`**
(`code`, `mode` `jvm|sci`, `namespace`, `root`, `cluster`, `read_only`,
`session_id`, `timeout_ms`), **`runtime_status`**, **`get_value`**; dispatch
at `:886-888`. `read_only` is read at `:674`. The `jvm` mode binds no cluster
custody; `(seon.operator/connection "default")` (`src/seon/operator.clj:168`)
supplies an explicit connection. A ready-made handle path, verified by the
§2.5 probe: `@seon.operator.runtime/running-instances` → `(get … "default")`
→ `:seon.turn.loop/cluster` → `:seon.sci.eval/ctx` and
`:seon.agent/context-state`.

**There is no Juniper seed command.** Case-insensitive search over `bin/`,
`script/`, `config/`, `resources/` and `src/` returns two hits, both prose:
`src/seon/turn.clj:476` (a measurement comment) and `src/seon/repl.clj:8` (a
docstring example prompt `my.agents.juniper=>`). "juniper" otherwise appears
only as an inline fixture agent id inside `test/` files (e.g.
`test/seon/repl_test.clj:33,41,54,58`). `bin/seon` (`script/seon/fresh_operator.clj:3642-3661`)
has commands `start`, `config`, `init`, `status`, `stop`, `down`, `open`,
`reset` — no `seed`, no fixture subcommand.

What a fresh cluster actually gets: `src/seon/cluster.clj:2456-2459` calls
`seon.bootstrap/seed-tx` (`src/seon/bootstrap.clj:885`) for an absent agent,
which writes the namespace row, one `:seon.message` and
`turn/generated-run-tx`. On the live `default` cluster the only agent context
is `"root"` (§2.5), namespace `my.agents.root` (`bootstrap.clj:880`).
`bin/seon init --dev default` is handled at `fresh_operator.clj:697` and is
invoked by `reset` at `:3552`.
