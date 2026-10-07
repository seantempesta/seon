---
type: design-review
lane: s5a-review
status: changes-requested
created: 2026-10-07
design: docs/research/agent-platform/2026-10-07/s5a-reply/design-notes.md
---

# s5a-reply design review

**Changes requested before implementation.** Deleting the reply reread and automatic problem routing is the right direction. The proposed receipt-to-reply equivalence is false as written: its converter rejects the proposed input, settlement requests omit source-dependent reply data, and a successful refusal transaction need not have written its proposed receipts. Fix these at the existing owners, without adding another evaluator, recorder or formatter.

Review lane: `s5a-review`. Source initially inspected at `1517e2f3546c64b8e1d2110e24da16b67482b2cc`; static-check archive at `3c487913c60f1f8e17e0f338ecfb9bea448fa8e3`. Default runs committed archive `d59363530c2b60e411231371dbc13c11ae76882b2cc`, PID 59503, start `2026-10-07T11:24:02.729Z`. The relevant source files (`mcp`, `repl`, `turn`, `turn.batch`, `render.transcript`, `cluster.agent`) and contract checker have no diff from that installed revision. Source citations below refer to the reviewed checkout. No implementation, test run, provider call, reload or default restart was performed.

## Ranked findings

### P1 — The proposed converter input fails its installed contract

Design §2 calls `entity-emission` “already total without a database,” then passes a settlement request plus evaluation id and expanded namespace, omitting the database. Its body can operate that way, but its admitted interface cannot:

- `src/seon/repl.clj:497`: `entity-emission` accepts `:seon.repl/entity-request`.
- `resources/seon/schemas/seon.repl.edn:81`: that is a render unit, an evaluation entity, or a render-value containing an evaluation entity. A render unit requires a database; an entity requires its run ref. The proposed request supplies neither. A settlement request is not an evaluation entity.
- Live probe P3 below used a valid settlement request reconstructed from an actual receipt. It answered `{:settle-request-valid? true :proposed-entity-request-valid? false}` in 1 ms, 113 calls. An earlier direct call was refused at the contract boundary, including missing `:seon.db/db` and `:seon.cluster.eval/run`.

**Smallest correction:** declare the actual held-input shape at the existing conversion owner, retaining validated named members; do not bypass instrumentation, add a fake database/run, or pass a database that restores the per-form query. Add `seon.repl.edn` to the schema/ownership/adoption closure. Prove the ordinary armed entrance, not just the unwrapped body. This is a missing design change, not a request for another adapter subsystem.

### P1 — Settlement requests are terminal patches, not complete reply inputs

The statement “the writer call stores the map it is given” is inaccurate. `evaluation-facts` (`src/seon/turn.clj:98-186`) produces terminal facts keyed by run and ordinal. `receipt-settle-call` (`:1424-1486`) adds selected terminal assertions to the already frozen receipt. Source, comment and identity belong to that earlier row. The terminal-attribute list (`:1170-1200`) deliberately excludes source.

This changes an observable reply even on a successful SCI pass:

- `repl/response-entries` (`src/seon/repl.clj:243`) calls `def-note` with source, value class and namespace.
- `def-note` (`:148-169`) needs the original `(def …)` source.
- Live `(def held-value 7)` returned a `:note` explaining the temporary SCI value. Feeding the same valid emission to `response` with only source removed dropped `:note`; value, result, ms and calls remained. See P1/P2 below.
- The design only adds id and namespace to the terminal request. That cannot reproduce this reply.

The single-ordinal refusal receipt is even thinner: `refusal-terminal-data` (`turn.clj:3655-3703`) builds ordinal, shown/error/diagnostics, but no namespace. The stored transcript recovers the frozen namespace, then the agent namespace, then `user` (`render/transcript.clj:258-284`). Do not turn absent namespace into a nested map containing nil.

**Smallest correction:** carry the already-held frozen source and namespace alongside the terminal facts through the same settlement result. Compose them once using presence-preserving operations. Do not reread each entity to repair omissions. The test table needs temporary defs and namespace changes as well as numbers and errors. A data equivalence assertion must include `:note`, `:ns`, `:out`, diagnostics, costs/findings and handle identity; shown-text equality alone is insufficient.

### P1 — Refusal receipts can describe writes the writer skipped or refused

Design §4 says the refused-refusal arm returns the refusal arm's receipts; §2 describes all returned receipts as what the writer wrote. There are two different counterexamples:

1. `open-run-tx-call` (`turn.clj:459-488`) decides **at the writer** whether a run is still present and open. Otherwise it returns `[]`. Both `refusal-terminal-data` (`:3707-3717`) and `settle-batch-refusal!` (`:3844-3855`) guard receipt writes with it. The error recording can commit successfully while none of the proposed terminal receipts is applied. A `:db-after` is not proof those requests became receipts.
2. If the refusal transaction itself refuses, `settle-batch-refusal!` (`:3863-3883`) recovers/closes the run and records a fault. Its attempted receipts did not commit. `resume-turn` (`:5200-5207`) explicitly supplies no basis in that case. `evaluate-source!` (`cluster/agent.clj:821-827`) consequently returns the refusal; today's MCP path prints that terminal refusal, not the attempted receipt vector.

A concurrent closure can also leave older terminal facts on the immutable returned basis. Returning the newly proposed patches is not equivalent to reading those facts.

**Smallest correction:** specify the reply and durability semantics of these arms before coding. Keep held evaluated values available, but do not label rejected/skipped requests as saved observations. Reuse the existing writer decision/report; no extra transaction, mutable receipt side channel, trial write or retry loop. If returning held values on these arms is the desired total-path improvement, label it **BEHAVIOR CHANGE**, with a regression, rather than promising today's reply data. Preserve the existing no-basis terminal-refusal behavior unless root explicitly chooses otherwise. An optional `receipts` member alone does not resolve this distinction.

## P2 findings and concrete simplifications

1. **Do not reconstruct JVM records twice.** `turn.batch/record-facts` (`src/seon/turn/batch.clj:631-649`) already maps `evaluation-record`; `evaluation-record` (`:611-629`) creates `:seon.cluster.eval/at` with `Date`. Mapping it again from `commit/:settled/:entries` is a second derivation and produces a different timestamp. The timestamp is not currently shown by `response`, so this is not a demonstrated reply-text regression; it does falsify “the receipt the writer wrote.” Return the accepted attempt's already-built record vector from the existing settle owner. Keep rebased entries/facts errors and the chosen attempt together. Do not make a private helper public merely for duplicate reconstruction.
2. **The test plan does not prove every terminal path.** Ordinary/error/faulted SCI cases omit refused settlement, skipped receipt writes, double refusal, pre-evaluation failure, renderer diagnostics, late installation failure, empty input and namespace transitions. JVM's no-commit branch (`mcp.clj:280-291`) already has a different answer: shown values plus the recording diagnostic through `turn.batch/reply`. Preserve it explicitly. `evaluation-record` also omits much of `shown-map`, including renderer diagnostics: today's JVM behavior is not the target all-entrant envelope. Distinguish preservation from a separate producer fix. The `:ms` change is correctly labeled, and live JVM calls confirmed the omission.
3. **An untriggered MCP call cannot prove routing was deleted.** `form-problem` always calls `planner-scoped-attempt?`; the latter is `(some? (message/trigger ...))` (`turn.clj:2379-2390`). The fixture must create the real triggered run whose red form is owned by B and authored by A, and demonstrate a routed message on the parent. A plain A SCI submission can already produce no assignment. Convert the existing triggered turn regression; keep its fold-continuation assertions. Retain explicit assignment/declination tests. Do not silently retain a touched `with-redefs` test without the testing skill's conversion or a concrete deferred task.
4. **The deletion sweep misses live references outside the listed calls.** `resources/seon/tasks/seon.mcp.edn:9` names `seon.cluster.agent/run-observation` in `:must-read`; `src/seon/mcp.clj:9`, `src/seon/turn.clj:537`, and `src/seon/sci/eval.clj:3722` also name deleted APIs. Update these through their owners in the deletion commit. The generated parity fixture contains declaration material too; verify whether regeneration changes it rather than hand-editing generated data. No extra production caller of `run-observation`, `form-problem`, `assignment-value`, `planner-scoped-attempt?` or `problem-id` was found by the repository sweep.
5. **Existing assignments remain consumers.** `assignment-facts` (`turn.clj:2465`) reads assignment/declination messages; `form-settlement` (`:2504-2545`) derives routed/owner-fixed/owner-declared-cant, and `plan-settlement` (`:2547`) consumes it. These also consume assignments already stored before the cut, not only future explicit messages. The design is right to keep them. “Nothing else consumes problem assignments” would be false; “the automatic producer can be deleted while explicit/historical consumers stay” is the accurate boundary.
6. **Reuse does not mean FINISH is already read-free.** `repl/response` is the existing composer, which is good. It still reaches `read-shown`/`one-form?` (`repl.clj:82-111`) and `def-note`'s EDN read (`:156`). Thus this slice adds no parser but does not establish spec §4.1's “no reading EDN back.” Keep that as a named remaining cut; do not add a new formatter, parser or cache to solve it here. The fault projection should be one extracted value function reused by stored and held history, including read refusals, occurrence count, origin branch and resolution, using the settlement's basis. `branch-errors` (`transcript.clj:336-374`) does more than pull a message.

## Existing flow and costs checked

The main SCI/JVM call chains in the design are accurate (`mcp.clj:212-291`, `cluster/agent.clj:784-874`). `history-entries` (`transcript.clj:724-757`) invokes `candidate-history`, then `entity-emission` for each evaluation; `run-observation` separately invokes `run-receipts` and joins by ordinal. Those are real repeated reads. `history-entries`, `entity-emission`, and `drive/run-receipts` have remaining consumers, so their wholesale deletion would be wrong.

The per-form `changed?` query is real (`repl.clj:527-545`), and `evaluation-terminal-data` has the two stated per-form queries (`turn.clj:3355-3374`). `form-problem` has the unconditional form pull and trigger lookup, with owner/author work conditional on a scoped non-artifact red (`problems.clj:224-258`). Removing the error fallback is equivalent for ordinary returned problem values: interrupted evaluations are artifacts and the remaining problem error is the evaluation's own error. The completed-disposition delivery in `asked-value` (`turn.clj:3181-3194`) must survive removal of its automatic-assignment alternative.

Independent installed read probe: on default's immutable basis, root run `9b94aec1f4ab`, two evaluations, `run-observation` alone took **10.590083 ms / 12,883,464 per-thread allocated bytes**. Existing task `seon.mcp/observation-reads-the-run-twice` owns this defect class. An initial latest-run query selected empty run `8edd38cc990a`: **4.155417 ms / 6,438,520 bytes**, two `db/q` calls; this is explicitly not a nonempty-pass baseline. Profiler output included its own thousands of `profile/entries` calls on the installed revision, so it is not a clean application call count (existing `seon.profile/begin-validates-every-cell-with-armed-predicates`).

The design's 1/10/100 rows are not independently reproduced here; raw forms/results and profile evidence are not present beside its note. Their source-level query explanation is supported, but the numerical savings remain hypotheses. The JVM-wide brackets include other work, as the design acknowledges. Do not count inclusive nested timings additively or present the predicted savings as achieved. Root's S0 needs the same parent/change workload with wall time, attributed allocation/calls, attempted/durable commits and store growth. This notes-only review changes no source/test lines and claims no performance improvement.

## Library seams and one path

Pins checked: Clojure `3bc2b3e91fdff462620d84bbdfd40379d9c562e5`, Malli `43c6dca8d2d8df3162b13725939954807be4a4ed`, Datahike `dbca76d536e426009cee8a780a350f74bd9dc858` in the reviewed HEAD. Datahike was read with `git show` at the pin because its worktree was dirty.

- Clojure `select-keys` (`reference-code/clojure/src/clj/clojure/core.clj:1559-1572`) uses `find`; it preserves present false/nil values. Ordinary `assoc`/`merge`/`mapv` suffice for the held-data composition. No object, atom, cache or protocol is needed.
- Malli `validator`/`explainer` (`reference-code/malli/src/malli/core.cljc:2672-2711`) compile/check the existing declared inputs. Use Seon's projection owner; do not invent hand validation or relax the contract to `:map` to make the probe pass.
- Datahike `apply-db-op` (`reference-code/datahike/src/datahike/db/transaction.cljc:1172-1173`, pinned version) invokes a transaction function against the in-transaction database and consumes its returned transaction data. It does not return arbitrary per-call application receipts. Do not reinvent that return channel with an atom/side effect inside the writer. The existing transaction report and the existing settlement owner are the appropriate seams.

The smallest viable direction remains: existing settlement owner answers its accepted values and outcome; existing reply owner composes them; delete the automatic routing branch and reread. `held-responses` is acceptable only as a thin data adaptation using the same projection and composer, not a new history/evaluation/rendering pipeline.

## Cross-design reconciliation

- **loop-pass:** no current `loop-pass/design-notes.md` exists. There are preserved runs and `stopped-wip/loop-pass.patch`; the approved agent-loop spec is the current authority. Its S4/S5 will replace report/history transport and S6 retires run machinery. Receipts added now must flow into that same pass FINISH, not become an MCP-only permanent representation. `turn.clj` and report/evaluated-source shapes are overlapping ownership. The old patch's continued context derivation is not an installed seam.
- **reader-prose:** no current design note exists; its stopped patch still contains repair/fenced-mode behavior superseded by approved §4.3. Preserve the distinction between MCP's all-code entrance and model prose/no-form cases. Source carried for P1 must be the held source already read, never reconstructed/resegmented at reply time. No S2/S6 parser change belongs in this slice.
- **repl-lookup:** its design touches `repl.clj`, `seon.repl.edn`, `sci/eval.clj` and rendered documentation values. The P1 contract correction adds a real overlap omitted from s5a's ownership list. It also has a stale proposed deletion of `my.program/declaration`; approved spec §4.7 explicitly keeps that other-caller API. Root should reconcile the named authority rather than treating old design prose as installed behavior. Add a declared-renderer/lookup result to the reply matrix.
- **walk-delete:** it changes `render/transcript.clj` in B1 and its old history/capture schemas in B3, then `turn.clj` and read evidence in B2/B4. Put shared fault/receipt conversion in surviving ownership; do not root the new response API in a soon-deleted capture/history schema. Its capture-preservation question is already decided by approved spec §7.4. Its old claims about an uncommitted loop-pass seam are not current installation evidence.
- **s0-probe:** the ledger still holds `mcp_entrance_test.clj` and `cluster/turn_test.clj`; root must coordinate their conversion. This reviewer neither edited nor contacted that lane.

## Live probes (default, own SCI agent `s5a-review`, namespace `scratch.s5a-review`)

P0: `[(+ 1 1) nil false ""]` returned `[2 nil false ""]`, `result/ef7f802c162e4`, `:ms 0`, no diagnostics.

P1, one submission:

```clojure
(def held-value 7)
(/ 1 0)
false
```

The def returned the rendered var map, `result/e7262013ff0a8`, `:note`, `:ms 0`, `:calls 6`. Division returned the expected ArithmeticException data with its SCI location, `result/e6c27f5baddc1`, `:ms 1`; the following false still returned, `result/e7a0ed0f7563b`, `:ms 0`. This verifies continuation and false preservation, not the proposed implementation.

P2:

```clojure
(let [d (seon.db/db)
      r (seon.db/pull d '[* {:seon.cluster.eval/ns [:seon.ns/name]}]
                     [:seon.cluster.eval/id "7262013ff0a8"])
      e (seon.repl/entity-emission (assoc r :seon.db/db d))]
  {:stored (seon.repl/response e)
   :without-source (seon.repl/response (dissoc e :seon.cluster.eval/source))})
```

`result/e80311415df0e`, 3 ms / 717 calls. Only the latter response loses the temporary-def note. These response strings are evidence from the production composer, not a second printer used to construct the review.

P3:

```clojure
(let [d (seon.db/db) p (seon.db/carried-projection d)
      r (seon.db/pull d '[* {:seon.cluster.eval/run [:seon.turn/id]}
                         {:seon.cluster.eval/ns [:seon.ns/name]}]
                     [:seon.cluster.eval/id "7262013ff0a8"])
      held (assoc (select-keys r [:seon.cluster.eval/ordinal :seon.eval/shown
                                 :seon.eval/renderer :seon.eval/value-class
                                 :seon.eval/duration-ms :seon.cluster.eval/call-count])
                  :seon.turn/id (get-in r [:seon.cluster.eval/run :seon.turn/id])
                  :seon.cluster.eval/ns
                  [:seon.ns/name (get-in r [:seon.cluster.eval/ns :seon.ns/name])])
      proposed (assoc held :seon.cluster.eval/id (:seon.cluster.eval/id r)
                           :seon.cluster.eval/ns (:seon.cluster.eval/ns r))]
  {:settle-request-valid?
   (seon.schema/valid-candidate-value? p :seon.cluster.eval/settle-request held)
   :proposed-entity-request-valid?
   (seon.schema/valid-candidate-value? p :seon.repl/entity-request proposed)})
```

`result/e2f36d02db402`: true/false, 1 ms / 113 calls. The earlier deliberately incompatible converter call returned its contract refusal in 8 ms / 86 calls and delivered it in `:errors` (`result/e62f4930cc0de`). That expected refusal is not filed as a runtime defect.

P4:

```clojure
{:seon.render/ai (fn [_] (throw (ex-info "s5a review renderer probe" {}))) :probe 1}
```

`result/e56960e271b68`, 1 ms: retained `:value`, `:diagnostics`, handle and timing; MCP `isError false`. The diagnostics included 32 stack frames plus a cut for 53 more, roughly 1,500–2,000 estimated tokens of generated diagnostic output. Existing tasks: `seon.error.refusal/errors-capture-whole-stack-traces`, `seon.sci.kernel/a-failure-keeps-and-classifies-every-frame-of-every-link`, `seon.sci.eval/diagnostics-dig-forms-point-into-the-value`. Preserve value-versus-diagnostic classification; do not silently turn this into an evaluation error.

JVM read probes passed `(seon.cluster.boot/connection "default")` explicitly. Reading the installed converter/evaluation-facts contracts returned their named signatures and a result handle, with no `:ms`. A second trivial JVM read likewise omitted `:ms`; link existing `seon.mcp/jvm-mode-replies-omit-time-calls-and-location`. No host definition was changed.

## Exact tests for root's central run

No tests were run by this lane. Existing focused identities to convert/retain:

- `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent` (includes real refused multi-form settlement, renderer failure and stopped submission cases).
- `seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`.
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`.
- `seon.cluster.turn-test/refused-terminal-program-transactions-settle-and-do-not-refire`.
- `seon.cluster.turn-test/a-red-form-routes-to-its-namespace-owner-and-the-fold-continues` (convert to the no-automatic-assignment behavior while retaining triggered-parent and fold evidence).
- `seon.cluster.problem-routing-test/stored-evaluations-derive-routing-and-settlement`.
- `seon.cluster.resume-artifact-routing-test/resume-artifacts-stay-red-and-are-excluded-from-owner-routing` (retain settlement assertion).
- `seon.repl-test/a-def-note-follows-the-evaluations-surface-and-outcome`.
- `seon.repl-test/a-response-carries-errors-as-data-and-late-diagnostics-beside-the-value`.
- `seon.repl-test/the-namespace-appears-only-when-the-form-changed-it`.
- `seon.repl-test/every-reply-path-reads-the-recorded-cost-its-findings-need`.

Proposed new entrance-level identities (not installed): `seon.mcp-entrance-test/held-replies-preserve-each-entrant-observation`, `seon.mcp-entrance-test/a-jvm-reply-keeps-its-duration-after-an-earlier-run`, and `seon.cluster.evaluate-source-test/skipped-and-refused-receipt-writes-are-not-reported-as-saved`. Put ordinary nil/false/empty, output, temporary def, namespace switch, declared renderer, renderer failure, error-then-success and fault rows in the first table; keep concurrent writer/refusal cases in the last test. Assertions are parsed reply DATA and actual stored facts; exact text only in the established printer/golden tests. Do not test only the new helper against another helper.

## Health, checks, limits and owner questions

Both `bin/seon status` and MCP `runtime_status` reported default `:faulted`: existing branch-custody fault in `seon.turn.world/record-fault`, task `3d2fb1b41dc8`, 3 occurrences. Health also named `seon.mcp/jvm-run` max 7,367 ms (task `bc5e022a68e6`), `seon.cluster.source/changed-identities` 1,454 ms (tasks `4a627750dc7e`, `483c64e1796e`), cache discipline and errored-receipt warnings. Heap-used snapshots were 577,416,544 and 610,970,976 bytes; live heap 459,976,032. No restart/reset is required by this review; existing faults remain root's recovery decision.

Required static checks ran against the committed archive named above, whose src/script/test/resources equal this notes-only candidate:

- `clj-kondo --lint src script test --cache false --parallel`: **0 errors, 1,482 warnings, 5,369 ms**, exit 2 for warnings. This is not a zero-warning pass. Warnings include the existing second-print, unowned-concurrency and unused-binding families; this review did not edit them.
- Installed `seon.contracts-compile-test/check`, explicitly pointed at the archive root and its 475 src/test files, with default's carried projection as the optional candidate: **findings `[]`, 1,948.173917 ms, 2,301,964,904 per-thread allocated bytes**. `result/e548b7d5f88a3` delivered a slow finding (`:seon.eval/duration-ms 1954`). This exceeds the budget, not a performance pass. Existing task `seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing` and `seon.schema/contract-check-on-three-files-exceeds-one-second`; no duplicate task created. No test runner was invoked and no uncommitted source was loaded.

Foreign boundaries: dirty `reference-code/datahike`, untracked content in `reference-code/http-kit`, `tmp/test-data-assertions/proofs.edn`, and unrelated untracked docs/src-clm were untouched. Dependency inspection used the pinned commit. The REPL skill uses obsolete line numbers (for example its `fork-for-turn` citation is 3320; the reviewed source defines it at 2813). This review used current symbol definitions and installed contracts. Skill edits are outside this notes-only assignment.

Design check: the deletion direction holds; the complete-data and writer-outcome assumptions do not. The observed seams support a smaller implementation than exposing `evaluation-record` for a second pass. There is no evidence requiring new state, cache, parser, evaluator or printer.

Questions for root:

1. On skipped/doubly refused settlement, preserve today's terminal-refusal-only answer, or explicitly improve it to retain evaluated values with durability diagnostics? Which behavior is approved for this pulled-forward slice?
2. Will s5a carry frozen source/namespace in the existing settlement result and adapt the existing converter contract, with `repl-lookup`/`walk-delete` coordinated around that surviving owner?
3. Can root narrow `s5a-reply`'s directory claim to free this review note? `bin/lane claim s5a-review docs/research/agent-platform/2026-10-07/s5a-reply/review-s5a-review-notes.md` refused because `s5a-reply` owns the whole directory. The reviewer did not release or edit another lane's claim. Until it is freed, the completed note stays in this session's `scratchpad/s5a-review/`, with no commit and net src/test lines **0**.


Cleanup evidence: the own-branch SCI call `(my.agent/retire-branch! {:seon.store/branch :agent-e3dbda7da14c})` returned `{:seon.error/message "run transition refused: admission-closed"}`, MCP `isError true`, with no timing or handle. This matches existing `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` and `my.agent/a-refused-retire-leaves-the-agent-admission-closed`. Retirement was then completed from the parent through the same public owner in JVM mode:

```clojure
(let [c (seon.cluster.boot/connection "default")
      instance (get @seon.operator.runtime/running-instances "default")]
  (my.agent/retire-branch!
   {:seon.store/branch :agent-e3dbda7da14c}
   {:seon.store/store (:seon.store/store instance)
    :seon.db/connection c
    :seon.agent/routing
    (get-in instance [:seon.turn.loop/cluster :seon.agent/routing])}))
```

Result `eac87355b76db`: `{:seon.store/branch :agent-e3dbda7da14c :my.agent/retired? true}`. No other branch or agent was retired. The owned static-check archive was removed after its result was recorded; note and revision evidence remain in the lane's scratchpad. No background shell or test JVM remains.

Reproducible static-check form (archive first with `git archive 3c487913c60f1f8e17e0f338ecfb9bea448fa8e3`; `root` is that extracted directory):

```clojure
(let [c (seon.cluster.boot/connection "default")
      root "/tmp/claude-501/-Users-sean-src-seon/2f0ff965-686f-4905-bf5c-7fe2ed6b5919/scratchpad/s5a-review/candidate"
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      paths (seon.contracts-compile-test/source-files
             [(str root "/src") (str root "/test")])
      a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
      t (System/nanoTime)
      findings (seon.contracts-compile-test/check
                {:seon.contracts-compile-test/root root
                 :seon.contracts-compile-test/paths paths
                 :seon.contracts-compile-test/candidate
                 (seon.db/carried-projection (seon.db/db c))})]
  {:paths (count paths) :findings findings
   :wall-ms (/ (- (System/nanoTime) t) 1e6)
   :allocated-bytes (- (.getThreadAllocatedBytes
                       ^com.sun.management.ThreadMXBean bean tid) a)})
```

Reread measurement form (the two-evaluation specimen above):

```clojure
(let [d (seon.db/db (seon.cluster.boot/connection "default"))
      rows (seon.db/q '[:find ?id ?t :where
                       [?a :seon.agent/id "root"] [?r :seon.turn/agent ?a]
                       [?r :seon.turn/id ?id ?t] [?r :seon.turn/closed-tx _]
                       [?e :seon.cluster.eval/run ?r]] d)
      run (first (last (sort-by second rows)))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
      t (System/nanoTime)
      result (when run (seon.cluster.agent/run-observation
                       {:seon.turn/id run :seon.db/db d} "root" "default"))
      elapsed (/ (- (System/nanoTime) t) 1e6)
      allocated (- (.getThreadAllocatedBytes
                    ^com.sun.management.ThreadMXBean bean tid) a)]
  {:run run :evaluations (count result)
   :wall-ms elapsed :allocated-bytes allocated})
```
