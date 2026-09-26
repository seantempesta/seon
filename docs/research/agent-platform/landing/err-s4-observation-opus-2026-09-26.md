---
type: landing
created: 2026-09-26
lane: err-s4-observation-opus
commit: d404c8ff3
---

# Landing: err-s4-observation-opus (audit slice 4, MCP/JVM and context observation), 2026-09-26

Commit `d404c8ff3`. RESET NEEDED: default (pid 21381) runs archive 67b02c99f. The bridge hot-reloads `script/seon/dev/mcp.clj`, so its half is live now. `cluster.clj`, `render/transcript.clj` and `operator/prepl.clj` take effect only after root adopts them.

## Broken or unmet (read first)

1. **The shared observation is still prose, not complete error data.** MCP and the Seon context now read the same bytes: `seon.repl/response` over the saved record, through `seon.render.transcript/history-entries`. The saved record itself holds only the following:
   - `:seon.eval/shown`, the error renderer's prose
   - `:seon.cluster.eval/error`, a string
   - `:seon.cluster.eval/triage-edn`, a string

   The complete data does exist, but only in the live result value, e.g. `result/e86a8d13361b6` for P1. Its `:seon.error/chain` holds both ex-data maps, `{:audit/offending 7}` and `{:audit/detail :sentinel}`. It never reaches the record, and none of that is in my files. The producer fix belongs to slice 2 (sci/eval `failed-evaluation`/`shown-result`, kernel `failure-value`) and slice 1 (`repl/error-text`/`response-entries`).
2. **MCP loses detail once this is adopted.** Today MCP prints `shown`. After adoption it prints what the context prints, which is `repl/error-text` built from triage:
   - The location becomes a wrapper frame: `sci.lang.Var/null (lang.cljc:219)` for P1, `seon.instrument/compiled-wrapper$fn (instrument.clj:682)` for P2, `seon.sci.eval/admit-program-change!$refuse! (eval.clj:990)` for P3.
   - P2 drops its expected schema `[:int {:min 1, :max 64}]`.

   This is convergence onto the context's defect, not a new one. For slice 1: prefer the saved shown observation over triage. Also, `response-entries` shows `:error <shown>` when there is no triage, which hides a refused settlement's refusal. rc-m5's MCP `:err` event, now deleted, used to show that refusal.
3. **P4's context response is unreadable EDN.** Its `:value` holds `{…}\n;; renderer failed …` inside the `#:seon.repl{…}` reply map. That is repl.clj/render (slice 1).
4. **Fault windows are still reconstructed as-of from occurrence attributes that keep no history.** `transcript/branch-errors` is not deleted, because nothing saves the observation yet: a deletion would stop delivery. It remains the single owner, and MCP now reads it too. Task `3638f3f3bd57` needs the settlement writer (turn.clj plus a receipt attribute in the schema) to save the observation once. See the options below.
5. **JVM-mode evaluations are not recorded, and since the bridge cursor is deleted they carry no fault window.** Before, a JVM reply carried a cursor summary (message, signature and count only). Now it carries none until task `8aba59c87b88` lands. `runtime_status` still lists open faults. See the options below.
6. **Live defects in the value that sit outside my files** (slice 2):
   - The live error value inlines a live SCI object, `:sci.impl/callstack #object[clojure.lang.Volatile …]`, twice: in the chain's first link's `:data` and in `:seon.error/data :seon.sci.eval/data`. That is a copy of context state, not a reference.
   - The chain's first link duplicates the author's exception as an SCI wrapper.
   - `:seon.error/frame` is `[sci.lang.Var invoke "lang.cljc" 219]`, not the author's location.
   - `seon.config/result-caps` puts the whole effective config under `:seon.error/offending`.
7. **Platform (root already knew):** the start at 18:39 hung with the cluster layer missing for about 17 minutes. During that time, every JVM-mode value was replaced by the `mcp-effective` refusal. The commit here fixes that replacement.

## What changed (evidence at the REPL, pid 21381)

- **Bridge (`mcp.clj`, live now).**
  - `run-form` attaches each evaluation's `:seon.repl/response` from `transcript/history-entries` for the run's own agent and run. It takes 3.3 ms for one run and 9.0 ms for four, measured in a scratch copy.
  - `run-events` emits one `:ret` per evaluation with that response.
  - Every bridge failure carries `(pr-str (Throwable->map t))`, so the whole chain stays native EDN.
- **JVM (`cluster.clj`, after adoption).**
  - P0 projects as the one shape. Proven at the REPL with `seon.error.refusal/diagnostic`: `#:seon.error{:layer :seon.dev.mcp/evaluation, :operation clojure.core/eval, :exception-class clojure.lang.ExceptionInfo, :chain [{… "audit outer" :data #:audit{:operation :read-only :value 7}} {… "audit inner" :data #:audit{:cause :sentinel}}], :frame [user$eval… invoke "NO_SOURCE_FILE" 1], :message "audit inner"}`.
  - A refused config read, or a projection that throws, keeps the value. The value is printed plainly under the bootstrap caps, with `:seon.render.value/diagnostics` beside it. `prepl.clj` hands the projection the Throwable (`*e`), not prepl's `Throwable->map`.
- **Transcript.**
  - The cluster-branch copy of another branch's core fault (`fault/record!` writes it for the cluster's agents) now appears in the cluster agents' window with its origin branch. Before, a filter dropped it for root.
  - `history-entries` entries name their `:seon.cluster.eval/ordinal`.
- **Probes through MCP on branch `err-s4-observation-opus`** (created at basis 536871008, now retired). Runs `a7ef30f23987` (P1), `b3371e0a90ab` (P2), `62eb904a8e6c` (P3), `4d3ac679c74d` (P4/P7). The context bytes MCP will return after adoption:
  - P1: `#:seon.repl{:error "Execution error (ExceptionInfo) at sci.lang.Var/null (lang.cljc:219).\naudit own cause", :result result/e86a8d13361b6, :ms 1}`
  - P2: `#:seon.repl{:error "Execution error (ExceptionInfo) at seon.instrument/compiled-wrapper$fn (instrument.clj:682).\nseon.id/digest refused length at []: expected an integer, got an integer 0. …", :result …, :ms 58}`
  - P3: `#:seon.repl{:error "… declares no :malli/schema …", :note "audit-missing-contract was not installed …", :ms 62}`
  - P7 receipt: `shown` keeps `{:audit/value 42 …}` plus one diagnostic line (rc-m5), with no `:seon.cluster.eval/error`.
- **Static checks.** `seon.contracts-compile-test/check` over `cluster.clj` and `transcript.clj` returns `[]` (164 ms). `seon.issue/tasks-tx` adds 3 tasks and retracts `e7be95eac7cd`. clj-kondo reports no new errors; the 4 `render.value` unresolved-var errors are stale cache and predate this change.

## Deleted (lines)

- `mcp.clj`, -62 net (75 added, 137 removed): the `window-bases` atom and its comment (6), `max-window-errors`/top-20 (1), `window-form` (23), `branch-errors!` (28), the cursor writes on create/retire (6), the `:out`/`:err` composition in `run-events` (10), and the prose `ex-message` error builders at 6 sites.
- `cluster.clj`: `mcp-io-prepl`, the second prepl printer, served only tests (19 lines; task e7be95eac7cd closed). The value-replacing arms of `mcp-project` (effective refusal and caps refusal as the value) and its floor-replaces-value catch are replaced.
- `transcript.clj`: the branch filter and dissoc that hid cluster copies (-4).
- `src/`+`script/` net **-54** (132 added, 186 removed). Tests: +98/-103.

## Tasks

- `18051e0ee3e2` `seon.dev.mcp/consume-recorded-diagnostic-observation`: stays open until the observation is complete data.
- `8aba59c87b88` `seon.cluster/record-jvm-evaluations-on-shared-path`.
- `3638f3f3bd57` `seon.render.transcript/keep-fault-observations-historical`.
- Regressions name them:
  - `mcp-bridge-test/a-run-reply-is-the-response-the-agents-context-reads`
  - `mcp-bridge-test/the-bridge-keeps-no-error-cursor`
  - `mcp-bridge-test/a-bridge-failure-carries-its-whole-cause`
  - `transcript-test/a-cluster-copy-of-another-branchs-fault-reads-in-the-cluster-agents-window`
  - `seon.mcp-test/outward-values-use-total-projection` (updated to the one shape)
- Deleted: `a-new-branch-window-opens-at-its-fork-basis` (it tested the cursor).

## Root runs (after adoption; not run by this lane)

```
bin/test-check default --ns seon.dev.mcp-bridge-test
bin/test-check default --ns seon.mcp-test
bin/test-check default --test seon.render.transcript-test/a-cluster-copy-of-another-branchs-fault-reads-in-the-cluster-agents-window
bin/test-check default --test seon.render.transcript-test/an-evaluations-branch-errors-read-in-its-own-response-once
bin/test-check default --ns seon.cluster.mcp-test
bin/test-check default --ns seon.dev.prepl-retention-test
```

## Options for the two cross-owner remainders (need turn.clj / sci eval / schema owners)

A. **Save the observation at settlement (recommended).** At the settlement writer, `turn.clj`'s receipt-settle tx computes the batch's window once, on the current db. That window is `(previous settlement tx, this tx]` from `error/occurred-since` (slice 3's evidence-bearing version). The writer stores it on the batch's last evaluation as a declared `:seon.cluster.eval/faults` component or ref-many to the occurrences, so the record holds references, not copies. The transcript renders the stored refs.
   - Deletes: `branch-errors`' history scan and its as-of reads (about 55 lines).
   - Cost: +20 in turn plus 3 in the schema. O(window occurrences) per settlement, none per render.
   - Guarantee: an old window never changes.
   - Sacrifice: counts shown are the occurrence's current count unless the ref is to a per-event occurrence (slice 3).

B. **JVM evaluator as a stage value.** `evaluate-source!` accepts `:seon.eval/evaluator` (host `eval` in the target namespace, no custody). JVM mode runs as root's ordinary run, so its record, window and response are the same path, and `jvm-evaluation-form` is deleted (about 30 lines in mcp.clj).
   - Cost: hours across turn/sci-eval.
   - Sacrifice: a JVM probe now writes a transaction (read_only probes would need to skip recording, or not).

C. **Simplest.** Keep JVM mode unrecorded as the read-only diagnostic entrance it is under ONE PERSPECTIVE. It shows faults only through `runtime_status`, and all delivery goes through SCI runs. Cost: 0. Sacrifice: JVM-mode users are never told of new faults.
