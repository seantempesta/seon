---
type: review
status: changes-requested
created: 2026-10-07
lane: review-s3-codex
---

# S3 design review — Codex

Review of `s3-prep/design-notes.md`, with root's four decisions applied. **Revise before implementation:** the
indexed tuple is viable on the stored index, but the batch allocation seam, history projection and cost rationale
need corrections. This is a design review, not evidence that S3 is installed.

## Authority and baseline

- Read `lane-rules.md` first, including One happy path; AGENTS.md, the approved agent-loop spec, and the
  data-oriented-clojure, REPL, Datahike, data-modeling and testing skills. Applied the current spec where older
  skills describe the retired refresh design. No delegation, provider calls, tests, source edits or reloads.
- Review HEAD: `30bff7ae74690fe4a760aa3006ffde0acd6546cb`. Source citations below are **that HEAD**, unless
  explicitly identified as dependency source. Default reports source archive `ec5f13d2faf8eeb332c4e4adea43ba845e26d027`,
  pid 75294, start `2026-10-07T11:57:29.639Z`. Between those commits the only src/test changes are task-file reading
  and `contracts_compile_test.clj` (`e843f1d52`), not the reviewed settlement owners.
- Pins checked: Datahike checkout and gitlink both `e5453d967b749794944dbdfe64b751519983ba59`; Malli
  `43c6dca8d2d8df3162b13725939954807be4a4ed`; SCI `ce0aa53b9bf35029e0bc27a962d799c196a37874`.
- `bin/seon status` (203 ms) and MCP `runtime_status` both report **faulted**, 3 open faults, 10 errored receipts,
  7 unowned namespaces, 5 over-bound warnings, no missing readiness layers and 0 open turns. Heap used 1.17 GB,
  last reported live heap 630–691 MB, 188 platform threads. Top means/maxima in ms:
  `call-with-projection-state` 17406/419366 (tasks `d27179bab996`, `c4ece4f4eec9`),
  `analyzed-files` 1654/2365, `publish!` 2541/3971 (`9665c2ed25f0`), `adopt-entities!` 1329/1849
  (`5dbb7de36bdb`), `index!` 2411/3725 (`12037fb1e077`, `84fdc131a599`, `8b62adf64442`). These are the status
  observations, not measurements of this review's probes. The design already links the record-fault custody and
  shown-failure faults (`3d2fb1b41dc8`, `7f5dfb6ad7a2`). No inference of health from the operator's “OK” heading.
- Root's decisions are settled: replace author writes and convert all sites now; derive ordinals at the writer
  from the tuple, without a counter; opening/delivery declarations and checks move together to S4; the estimated
  +8 KB per commit is accepted. Delete A2/B1 and the renewed approval request from the reconciled design. Change
  spec §4.9 to “agent ordinals assigned at the writer” and amend the S3/S4 rows. No compatibility adapter proposed.

## Findings, ranked

### P1 — Allocate once for a batch; the current inner calls do not see each other's results

Design §4.3 attaches the allocator to both `receipt-settle-batch-call` and `receipt-settle-call`. At
`src/seon/turn.clj:963–973`, the batch uses `(mapcat #(receipt-settle-call database %) requests)`: every ordinary
Clojure call receives the **same** database. The returned assertions have not yet been transacted. Assigning
`inc(max)` independently in each inner call therefore duplicates ordinals; appending another allocation at the
outer call also performs redundant work and potentially replaces the first assertions.

Datahike's guarantee is narrower: transaction operations spliced by `:db.fn/call` see preceding applied operations
(`reference-code/datahike/src/datahike/db/transaction.cljc:1055–1062,1172`), not preceding ordinary function calls
inside a transaction function. The existing writer serialization is sufficient; no lock, counter or retry is needed.

**Smallest correction:** one terminal-facts implementation and one batch allocation `max + 1..k`; the single-item
entry delegates with a singleton vector. Preserve per-receipt terminal checks. At the other existing writer,
`record-evaluated-call`, allocate only in its new-record branch. Its identical-record arm currently returns `[]`
(`turn.clj:1348–1352`); a duplicate must neither consume ordinals nor compare writer-generated ordinals against
the caller's unnumbered request. Convert `recorded-evaluation`'s author selection too (`turn.clj:1249–1258`), so
duplicate comparison continues to include origin. This reader is missing from the “three src readers” inventory.

Required behavior proof: multi-form pass, two passes for the same agent in one transaction, concurrent SCI/JVM
settlements, identical record replay, conflicting replay and refused settlement. Assert committed numbering and
unchanged existing facts; do not test elapsed time. The helper's vector contract alone does not enforce these laws.

### P1 — “Neither ref is indexed, therefore the join scans every evaluation” is false

Design §1.3 confuses the literal attribute declaration with Datahike's derived reverse schema. The pinned
`db/utils.cljc:308` maps `:db.type/ref` to `[:db.type/ref :db/index]`. Its `indexing?` at line 40 uses that reverse
schema. Both refs already have AVET datoms on **default and the stored review branch**:

```clojure
(let [d @(seon.cluster.boot/connection "default")]
  (mapv (fn [a]
          [a (contains? (get (:rschema d) :db/index) a)
           (count (take 1 (datahike.api/datoms d :avet a)))])
        [:seon.cluster.eval/run :seon.turn/agent]))
;; [[:seon.cluster.eval/run true 1] [:seon.turn/agent true 1]]
```

Evidence: `result/edbebbef14a6b` (branch) and `result/ec734e6941f7a` (default, 3 ms, backend `:file`). Also `seon.eval/of-agent` orders by the assertion transaction of
`:seon.turn/id`, then turn id and form ordinal (`src/seon/eval.clj:51–71`), not an explicit `opened-tx` read.
The proposed tuple still eliminates the run join and sort and supplies the required writer order. Keep it for
those reasons. Retract the inferred whole-branch scan and its allocation attribution unless an actual query plan
or profile proves a planner scan despite the existing indexes. A raw/Datahike-versus-Seon comparison must use the
same selector and returned values; §2's numbers are historical measurements, not reproduced causal proof here.

### P1 — The new history reader does not yet preserve the exchange that the agent saw

§4.4 lists `id ordinal origin source shown comment error diagnostics output ns ending-ns` by hand. It omits
`:seon.cluster.eval/faults`, call counts, repeated calls, durations, and the measures/findings selected by
`seon.repl/cost-attributes` (`src/seon/repl.clj:317–329`). It also omits `:seon.eval/value-class`, used by the def
note and gone-value reporting. `s5a-reply/design-notes.md` explicitly retains faults on the last receipt and
expands them through the same reply owner. Reading just the proposed list cannot establish equality between
the proc's appended response and restart acquisition. The design itself correctly refuses deletion of the token
measures in §0.4; its new reader must not silently drop them instead.

There is already a schema-driven selection seam (`cost-attributes`) and a stored-to-emission owner
(`seon.repl/entity-emission`). Reuse their declared data requirements, including fault resolution, or consume
the exact finished exchange representation S4 chooses. Raw datoms also need cardinality/ref/codec semantics;
do not grow a second general pull decoder in `seon.db` beside `decode-pull-entity`/`decode-attribute-value`.

**Recommendation:** defer `agent-exchanges` and its new exchange schema to S4 with their first production
consumer. S3 needs the max-ordinal read and tuple facts. B2's “no schema without a writer” simplification applies
equally to this consumerless projection. If root keeps the reader in S3, require an explicit S4 selector and a
restart/append data-equivalence proof over ordinary values, errors, diagnostics, faults, output and findings.
No second parser, renderer, printer or cache is necessary.

### P2 — Spell out both tuple boundaries and the missing-agent maximum

`rseek-datoms` returns the preceding datom even if it belongs to another agent (or another attribute).
The stored probe for absent `"bb"` returned `["b" 10]`. Using its second component would start the absent agent
at 11. Validate **both** `(:a row)` and `(first (:v row))`; otherwise the maximum is zero. The reverse seek is
lazy and bounded by consumption, not automatically by the requested prefix (`db.cljc:265–279`).

The range needs an explicit upper bound `[agent-id Long/MAX_VALUE]`, or a prefix stop before fetching rows;
“from `[agent-id 1]`” alone reads later agents too. The installed API is
`(d/index-range db {:attrid attr :start [agent-id 1] :end [agent-id Long/MAX_VALUE]})`, not a four-argument call
(`api/impl.cljc:295`). The stored range/max measurements below establish that these library seams are sufficient.
No composite tupleAttrs bridge or custom comparator is needed. Preserve integer overflow refusal at the producer;
do not add a stored counter to avoid these simple boundary checks.

### P2 — The origin conversion inventory and claims need to include the surviving preparation paths

There are 24 matching lines in eight `.clj` test files plus the parity EDN, matching the design's 25-site/nine-file
inventory. Convert them now as ruled, including `recorded-evaluation`, request contracts and source producers.
But §8.1 (“every new evaluation carries exactly one origin”) contradicts §1.2/§4.2 (refresh/generated rows carry
none until S4). `append-generated-call` and the refresh still execute during S3. Identify those temporary rows
explicitly in the proof obligation; do not invent an inaccurate origin or a sixth enum member to satisfy prose.
The existing generated-run test (`turn_test.clj:1019`) exercises these writes, so changing assertions is more
than replacing `:system` with `:outside` everywhere.

The claim that all entrants already mint a pass id also needs its test caveat: the cited
`seon.test/attempt-request-context` is the **effect-reaching attempt** owner, not every test member;
`seon.turn.batch/test-request` exists but has no production caller (`rg test-request src`). `:test` is a target
for the later runner integration, not proven S3 recording. Keep S3's producer contract precise.

Carry the existing author attribute's `:seon.wake/context-inert true` policy onto origin/ordinal while the old
refresh survives, or explicitly prove why it is unnecessary. `wake/inert-attributes` (`cluster/wake.clj:144`)
feeds both `render.walk` and `generated-read-fault` (`turn.clj:1968`): dropping the metadata changes how generated
reads of these bookkeeping facts are classified. No new waking policy belongs in this preparation slice.

## Stored-index experiment (not d/with)

SCI agent `review-s3-codex`, namespace `scratch.review-s3-codex`, branch `:agent-59d6dd3083ef`. Registered these
three declarations through `seon.schema/register!`, then wrote **213** identity-bearing scratch entities through
`seon.db/transact!`: agents `a/b/c/d` have respectively 1/10/200/2 tuples. Identities came from `seon.id/id`.

```clojure
[:scratch.review-s3-codex/id [:string {:seon.db/identity true}]]
[:scratch.review-s3-codex/agent-ordinal
 [:tuple {:seon.db/index true} :string [:int {:min 1}]]]
[:scratch.review-s3-codex/entity
 [:map {:seon.db/attributes true :seon.program/partition :seon.data}
  :scratch.review-s3-codex/id :scratch.review-s3-codex/agent-ordinal]]
;; SCI write, after registering the above:
(seon.db/transact!
 {:tx-data (vec (for [[agent n] [["a" 1] ["b" 10] ["c" 200] ["d" 2]]
                      ordinal (range 1 (inc n))]
                  {:scratch.review-s3-codex/id (seon.id/id)
                   :scratch.review-s3-codex/agent-ordinal [agent ordinal]}))})
```

Reopened with `datahike.api/branch-as-db` using default's explicit connection. Readback reported commit
`6ac6365a-ebe8-5a07-8d2a-f2b4df3ec1d9`, 213 tuples, and native declaration
`{:db/valueType :db.type/tuple :db/tupleTypes [:db.type/string :db.type/long] :db/index true
:db/cardinality :db.cardinality/one}`. Returned tuple endpoints verify member types, counts and agent separation.
Default's production origin/agent-ordinal attributes and proposed helper Vars remain absent.

Each cold sample materializes the committed database with a **new private dependency node cache**, using existing
`create-storage` and `stored->db`. No shared cache was cleared. Konserve nodes were fetched from the live file
store; `CachedStorage`'s `:reads` counts actual cache-miss `k/get` calls (`index/persistent_set.cljc:456–468`). This
is node-cache cold with a warm OS cache, **not a whole-JVM restart**. Commit/schema acquisition is outside the
timing window. Fresh storage was used separately for each row and each kind of seek.

| Operation | Cold ms | Cold allocated bytes | Node reads | Warm ms | Warm allocated bytes |
|---|---:|---:|---:|---:|---:|
| range, 1 tuple | 0.266125 | 742656 | 2 | 0.037125 | 3240 |
| range, 10 tuples | 0.212459 | 742464 | 2 | 0.007250 | 3272 |
| range, 200 tuples | 0.347042 | 1462472 | 4 | 0.011333 | 6336 |
| max, 1 tuple | 0.378084 | 741776 | 2 | 0.010667 | 2256 |
| max, 10 tuples | 0.247417 | 741560 | 2 | 0.005584 | 2256 |
| max, 200 tuples | 0.237667 | 793336 | 2 | 0.004625 | 2256 |
| max, absent agent bb | 0.223875 | 741480 | 2 | 0.004792 | 2176 |

Warm ranges caused zero further node reads. These are single samples, same evaluating thread measured with
`ThreadMXBean/getThreadAllocatedBytes`, including small result-map/measurement overhead. The measured window
makes no Seon armed calls and writes no transactions or store bytes; the enclosing MCP invocation records its
own run separately. This is a before/after **cache-state** comparison, not an S3 implementation speedup. Full
history acquisition, concurrent writer behavior and per-commit store delta remain implementation proofs. The
accepted ~8 KB estimate is not re-certified as a measured number. Cold seek needs 2–4 nodes here, not the guessed
1–2 leaves; its cost follows tree depth and consumed range. Total fetch allocation still depends on the selected
receipt contents and the shape of stored leaves.

Reproducible host measurement form (range results `result/e94c211a073ea`; max results `result/e6f66b46a3a03`):

```clojure
(let [c (seon.cluster.boot/connection "default")
      live (datahike.api/branch-as-db c :agent-59d6dd3083ef)
      cid (datahike.api/commit-id live) store (:store live)
      attr :scratch.review-s3-codex/agent-ordinal
      allocation-bean (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))
      measure (fn [f]
                (let [b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean allocation-bean tid)
                      t (System/nanoTime) v (f)]
                  {:value v :us (/ (- (System/nanoTime) t) 1000.0)
                   :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean allocation-bean tid) b)}))]
  (mapv
   (fn [agent]
     (let [storage (datahike.index.persistent-set/create-storage
                    (dissoc store datahike.index.interface/node-cache-key) (:config live))
           raw (konserve.core/get store cid nil {:sync? true})
           d (datahike.writing/stored->db raw (assoc store :storage storage) {:secondary-indices? false})
           operation #(let [xs (mapv :v (datahike.api/index-range
                                         d {:attrid attr :start [agent 1] :end [agent Long/MAX_VALUE]}))]
                        [(count xs) (first xs) (peek xs)])
           cold (measure operation) reads (:reads @(:stats storage))
           warm (measure operation)]
       {:agent agent :cold cold :physical-node-reads reads :warm warm
        :warm-extra-reads (- (:reads @(:stats storage)) reads)}))
   ["a" "b" "c"]))
;; For the max row, substitute this operation and include "bb":
;; #(let [row (first (datahike.api/rseek-datoms d :avet attr [agent Long/MAX_VALUE]))]
;;    (if (and (= attr (:a row)) (= agent (first (:v row)))) (second (:v row)) 0))
```

## Cross-design boundaries and smallest composition

- **s5a-reply** owned the settlement, batch, MCP, SCI and agent files when review began and landed as `7b5535cf6`
  during review. Its held receipt is built before
  the writer assigns the new ordinal; do not silently assume it is the final stored map. If S4's append requires
  that ordinal, return the writer's decision through the existing settlement result. No reread or second allocator.
  `evaluation-record` was refactored there; implement against the landed version. No foreign edit made.
- **loop-pass:** the stopped patch uses author filtering and `(opening-id agent-id handle)`, reads all history
  each time, and inserts opening through `system-run-tx`. Those are not current authorities. S4 needs the ruled
  origins, assignment+revision completion and proc-held transcript. S3 must not preserve those old mechanisms.
- **reader-prose / S2b-1:** pass/form identity remains `seon.id/evaluation pass-id position`. Do not derive the
  new ordinal from a parser segment count. S6's prose-only/read-error exchange must still be orderable without
  inventing an evaluation of prose. That is a later consumer constraint, not a new S3 parser path.
- **repl-lookup / S1:** help/doc/source supplies opening forms, not separate recording or origin inference from
  syntax. No S3 lookup changes. The old proposed deletion of `my.program/declaration` is superseded by spec §4.7.
- **walk-delete / S2a:** `eval/of-agent` remains the debug ledger's owner; removing it globally would break S2a.
  S4 can remove it from the model path. `agent-exchanges` also needs S4's assignment-history boundary; an agent-id
  plus origin filter alone spans previous assignments. B2 moves the fact that identifies that boundary with S4.

Design check: the existing id derivation, row producer, native tuple bridge and serialized writer are real seams.
The proposal adds no necessary parser/evaluator/printer/cache/loop. Its smallest S3 is origin conversion, tuple
declaration, a bounded maximum read and allocation once per writer batch. It lets S4 remove the run-order join,
refresh and author conflation. Adding a new pulled shape without its consumer is the avoidable part.

Questions for root to reconcile (the four decisions above are not reopened):

1. Move `agent-exchanges` to S4 with the transcript selector and assignment boundary? Recommended: fewer unused
   declarations now, one data-equivalence proof when its consumer exists.
2. Make the singleton settlement delegate to the batch allocator, preserving terminal checks and replay no-op?
3. State the temporary origin exception for refresh/generated rows and the later test-member integration, rather
   than claiming every current entrant already writes the final enum?

## Focused tests for root's central run

No test was run by this lane. Notes-only changes require none. S3 implementation needs these existing tests
(convert author assertions, preserve their surviving behavior):

- `seon.turn-test/receipt-transitions-preserve-one-terminal-outcome`
- `seon.turn-test/recovery-preserves-terminal-receipts-exactly`
- `seon.turn-test/generated-system-runs-grow-only-after-their-settled-prefix`
- `seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`
- `seon.turn-test/a-refused-refusal-settlement-is-answered-as-a-value`
- `seon.mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`
- `seon.mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads` (convert outside-history expectation)
- `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection`

Proposed new proof names, **not installed**: `seon.turn-test/settlement-assigns-agent-ordinals-once`,
`seon.turn-test/record-replay-preserves-agent-ordinals`,
`seon.mcp-entrance-test/entrant-origin-is-recorded-on-each-evaluation`, and, if the reader remains in S3,
`seon.db-test/agent-exchanges-preserves-the-recorded-reply-data`. The numbering table must include absent and
adjacent agents, a multi-form batch, refusal and replay; the concurrency case must exercise actual writer order.
Schema publication/adoption on a live-store branch remains mandatory; scratch schema registration here is not
proof of adopting the final production schema or deleting author.

## Verification, encountered errors, resources and landing

- Explicit archive lint: `clj-kondo --lint src script test --cache false --parallel`, **0 errors, 1482 warnings,
  5369 ms** on review HEAD. After s5a landed, repeated on an archive of `cb69cd55f`: **0 errors, 1481 warnings,
  5129 ms**. That archive plus this Markdown diff has the same lint inputs. Warnings are not claimed as zero.
  An initial command accidentally linted the working tree (0 errors, 1481 warnings, 5234 ms); that is not the proof.
- Installed MCP contract checker on the archive's src/script/test escaped with `edamame/error`, row 35 col 67,
  “Use :auto-resolve + :current to resolve current namespace”, `result/eb7873fe9184a`. Existing task:
  `seon.contracts-compile-test/script-with-auto-resolved-keyword-escapes-contract-check`. The reply omitted time,
  the known JVM reply timing defect in s5a. No reload was attempted.
- Ran committed archive's checker in an isolated process, **no test runner**: 488 src/script/test files yield
  10 contract findings in `script/seon/dev/{docstring,markdown}.clj`, 3105.808125 ms / 3765695928 thread bytes.
  Canonical src/test selection: **475 files, zero findings**, 3389.328458 ms / 3735478792 thread bytes, process exit 0.
  Repeated after the landing on archive `cb69cd55f`: **475 files, zero findings**, 3222.930750 ms /
  3737249080 thread bytes, process exit 0.
  These are cold checks including packaged projection construction, not warm per-edit costs. The allocation and
  duration exceed budget; linked `seon.contracts-compile-test/corpus-check-allocation-is-floored-by-edamame-per-char-boxing`
  as the existing corpus-cost work, without claiming the newly measured allocation has the same cause.
- New task at default: `seon.contracts-compile-test/script-contract-vars-do-not-compile-against-packaged-projection`
  (`8ec5d0c145f6`, creation 37 ms / 628 calls). Example findings: `docstring/check-source` line 285 has
  `check-source-request-schema`; `markdown/validate-repository-pins` line 1124 uses
  `(var validate-repository-pins-request-schema)`. All ten are `:malli.core/invalid-schema`. Task proposes the proof;
  this review changes none of those files.
- Probe mistakes, not product bugs: first scratch map used unsupported `:seon.db/entity true` (settlement
  diagnostic, 286 ms), then omitted required partition (error, 261 ms); corrected to attributes+partition above
  (369 ms). First range probe used four arguments (ArityException, `result/e9b37eccc3061`); corrected from the
  installed API. The write's summarized `:committed false :datoms 0` was my wrong use of raw report keys:
  `seon.db/transact!` returns `:seon.db/tx` and `:seon.db/datoms` (`db.clj:5915–5942`). Stored readback proves the
  write succeeded (101 ms / 9895 calls), not a lost-write finding.
- Cleanup: own-branch retirement through SCI returned `run transition refused: admission-closed` without a timing
  or full envelope. Existing task `my.agent/retire-branch-refuses-a-finished-agent-as-admission-closed` covers it.
  Read the live roster, then invoked the same public retirement owner from root's JVM entrance with explicit
  store/routing/default connection: `{:seon.store/branch :agent-59d6dd3083ef :my.agent/retired? true}`, 33 ms,
  `result/e969996a4e0bc`. No other agent was retired. Probe forms and results are preserved above; no scratch
  schema was installed on default. The task created at default remains durable after branch retirement.
- The initial claim command accidentally used the archived `bin/lane`, whose ledger is archive-local. Corrected
  before commit by moving only my untracked draft to my scratch directory, claiming the vacant real path, and
  restoring my draft. No foreign file or claim was adopted. The final claim is in the real checkout's ledger.
- Foreign boundary: s5a-reply's uncommitted src/test/schema changes were excluded from the initial archive; after
  their commit they were included in the repeated checks. Default was never reloaded by this review. Source/test
  net delta of this review: **0/0**. Only this note is
  committed. Cold stored seek verified; full S3 behavior, whole-JVM cold history, provider/model origin and store
  increment are not claimed proven. Default needs **no restart or reset for this review**.
