---
type: landing
status: partial — two slices landed; F14/F03 wait for foreign files; A100-A105 wait for the actual-exit fix
lane: standing-deletion-opus-2
created: 2026-09-26
authority: docs/research/agent-platform/agent-centric-audit-2026-09-26.md (F01, F03, F12, F14); docs/research/agent-platform/atom-audit-2026-09-26.md (A014, A100-A105, H07/H08)
---

# Landing: standing deletion, lane 2

Two slices landed: the reload fault in the work launcher, fixed by deleting the custom buffer type (69229772b), and the
projection cache converted to core.cache (e590464c0). Net source change is −60 lines (flow −50, schema −10). Test lines
are unchanged: two lines edited.

## Broken or blocked first

- **ADOPTION NEEDS A LAUNCHER RESTART (read before adopting 69229772b).** The live work launcher's input channel still
  holds the old `RefusingBuffer` instance. Once the new `seon.flow` Vars load, `work-launcher-step` no longer decrements
  that buffer's `AtomicLong`. After concurrency + queue-depth further submissions, the old buffer would therefore refuse
  every background call. A topology change rebuilds the graph (law), so restart default's work launcher, or restart
  default, after adopting. Until then, default keeps the fault this slice removes. **RESET NEEDED** if no launcher restart
  exists short of one.
- **F14 needs files outside this lane.** The refusal at `src/seon/sci/reader.cljc:567-577` is still live policy.
  `test/seon/loop_proof_test.clj:821-843` asserts it on a saved trial reply, and `render/transcript.clj`'s
  fabricated-problem counts it. Moving it to reply planning touches `src/seon/cluster/reply.clj` and
  `test/seon/loop_proof_test.clj`, which this lane does not hold, as well as reader.cljc and reader_test.clj, which it
  does. Note for the design: the provider request already stops at `"#:seon.repl"` (`test/seon/ai_test.clj:91,241,665`).
  The reader check is therefore a second guard. The one case it covers that the stop sequence misses is the
  `{:seon.repl/value …}` spelling. Waiting on root for the files.
- **F03 is still held.** `src/seon/cluster.clj:475 mcp-io-prepl`'s only callers are `test/seon/mcp_test.clj:22` and
  `test/seon/dev/mcp_bridge_test.clj:588,609`. All three files are foreign. Waiting on root.
- **F12 attribution is not done (root decision, unchanged).** Per-execution attribution needs the timed wrapper in
  `instrument.clj` to know its execution: either a dynamic binding read on every armed call, or ownership carried in the
  environment. Either option adds per-call cost to every armed call. The cost part landed in 4c0993b5d.
- **A100-A105 (launcher lifecycle atoms) not started, deliberately.** The atom audit orders it after the actual-exit
  fix. As written, `stop-work-launcher!` cancels each Future and settles it at once, so `drained` can mean "callback
  emitted" while `work-fn` is still inside a host call
  (`docs/seon/issues/cancelled-future-is-not-body-exit-evidence.md`, severity blocker). Folding `::status`, `::active?`
  and `::task` into the submission map entry is a 15-35 line lifecycle change at risk 5. It should land together with
  that exit distinction, reviewed, not as a deletion slice.

## F01 residue (verified, nothing left in this lane's files)

`rg` finds no `valid-cron?`, `valid-timezone?` or `commit-registration-delta!`, and `src/seon/render/agent.clj` is gone
(2b38801b5). The remaining residue, `db.clj deletion-error` and `walk.clj join-membership`, is in foreign files.

## Slice 1: the work launcher reload fault (69229772b)

**Fault.** `release-admission!` was hinted `^RefusingBuffer`, and its compiled checkcast names the class loaded with the
current Var. Reloading `seon.flow` redefines the deftype, creating a new class in a new DynamicClassLoader, while the
live channel keeps the instance of the old class. A scratch-namespace probe on default reproduces the exact message:
`(deftype B [x]) (def old (B. 1)) (deftype B [x]) (defn rel [^B b] (.-x b)) (rel old)` fails with "class
tmp.sd2_probe.B cannot be cast to class tmp.sd2_probe.B (… loader @497941d3; … loader @6032397a)".

**Model error.** Admission was counted in a second place. `io-submissions` already holds every unsettled submission
from `submit!` to `io-terminal!`. The buffer kept a parallel `AtomicLong` of the same work, and releasing it needed a
cast back to the concrete type. The fix is not a guard on the cast. Admission is now decided where unsettled work is
held: `submit!` uses `swap-vals!` and admits only while `(count before) < concurrency + queue-depth`. Queued work is a
subset of the unsettled work, so core.async's own fixed buffer of that size never blocks the injection
(`flow/impl.clj:111` builds `(async/chan buf-or-n)`, and `inject` is `>!!` at `:190-196`). No deftype is left to reload.

**Deleted:** `acquire-admission!` (12), the `RefusingBuffer` deftype (29), `refusing-buffer` (7), `release-admission!`
(4), `refuse-io-submission!` (8), the buffer's `::queued` filter, the `::io-admission-buffer` state key, and the
`LinkedList`/`AtomicLong`/`Counted` imports. flow.clj: +32/−82.

**Behaviour change.** A submission refused for capacity now returns `false` from `submit!`, like its other refusals. Its
`complete!` still receives `:seon.flow/submission-capacity-error`. The only production caller,
`effect.clj:865`, ignores the return. `flow_test.clj` asserts `false` for the third submission at concurrency 1 and
depth 1.

**REPL proof** (scratch copy `tmp.sd2-flow` on default, predicate registration stripped, namespace removed afterwards).
Concurrency 1, depth 1, with a blocked first body: the returns were `[true true false true]`, the refusal carried
`:seon.flow/submission-capacity :io`, and the values `[0 1 3]` show that capacity came back after settlement. Stop
returned nil, in 4.3 ms total. The scratch copy is unarmed, but `submit!`'s declared output (`:boolean`) accepts
`false`, and `submission-capacity-error`'s contract is unchanged.

**Skill corrected:** `.agents/skills/seon-flow-architecture/references/workloads-and-scheduling.md`. The admission
section and executor rows had claimed `submit!!`, the compute observer and the fault-committer graph, all deleted
(d2604ede9, 5d8e803f7). The citation hook passes.

## Slice 2: the projection cache is core.cache (e590464c0, atom audit A014)

`:seon.schema.projection/compiled` is now `(clojure.core.cache.wrapped/basic-cache-factory {})`, which is still an atom
on the projection value. `projection-cache-value` reads a hit with `cache/lookup` and a sentinel, and hands the miss to
`lookup-or-miss`. `identity-only-descriptors` had its own non-atomic deref/swap copy. That copy is deleted, and it now
goes through `projection-cache-value`. schema.clj: +10/−20.

Probe on default's carried projection, 100k hits: the hand-written CAS took 67 ns, `lookup-or-miss` alone 112 ns (it
swaps on every call, a +67% regression, so it was rejected), and lookup-then-miss 38 ns. Descriptors were identical
(5), there was one derivation per key, and a nil answer was retained. One semantic difference: two concurrent first
callers of the same key may each derive, and one answer is kept. Under the old CAS, the second caller would have
waited on the first caller's delay.

## Root runs (serially)

- `bin/test-check default --ns seon.flow-test`
- `bin/test-check default --ns seon.flow-launcher-error-join-test`
- `bin/test-check default --ns seon.env-test`
- `bin/test-check default --ns seon.effect-test`
- `bin/test-check default --ns seon.schema-test`
- `bin/test-check default --ns seon.error-test` (a `projection-cache-value` caller)

Already run: `seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection` passed
(4.3 s). It ran on default's committed archive, before these commits. Neither commit changes a declared contract or a
schema resource.

## Verification limits

No test request was run by this lane (load rule). The new flow code ran only as an unarmed scratch copy. The new
schema code ran only as equivalent scratch functions, not as the edited file loaded.
