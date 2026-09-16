---
type: research
status: complete
created: 2026-09-16
tags: [research, steward, instrumentation, boot-recovery, gate-reds]
---

# Two batch-65 B reds: call preparation's refusal shape, and boot recovery's expectations

Date: 2026-09-16 (gate batch 65 B, HEAD `ccccea806`; run root
`tmp/test-runs/run.mVNxT1`, failure blocks
`tmp/orchestrator/gate-results/batch-65/named-failure-blocks.txt`).

Both reds were judged "possibly REAL, not fixture-honesty" by the gate session.
Neither is a regression from today's lanes, and neither is a fixture refusal.
One is a MECHANISM defect (`seon.instrument`), one is EXPECTATION drift
(`seon.cluster.boot-test`). Attribution and evidence below.

## 1. `seon.call-preparation-test/a-compiled-first-party-call-is-prepared`

### What the gate saw

```
FAIL (call_preparation_test.clj:493)  "the caller's 1 reached the callee unreplaced"
expected: (= [#:seon.db{:connection 1}] (:seon.error/diagnostic-offending ...))
actual:   (not (= [#:seon.db{:connection 1}] [1]))

FAIL (call_preparation_test.clj:502)  "the caller's 1 reached the callee unreplaced"
expected: (= ["a" 1] ...)
actual:   (not (= ["a" 1] [1]))
```

The kind (`:seon.instrument/contract-violated`) and the operation
(`seon.call-preparation-test/probe-received-connection?`) assertions PASSED.
So call preparation itself is correct: CALLER WINS holds, the caller's `1`
reached the callee unreplaced, and the callee's own contract refused it before
the body — exactly what the test set out to prove. Only the refusal's
`:seon.error/diagnostic-offending` disagreed.

### Attribution

Not today's lanes. `src/seon/instrument.clj` was last touched 2026-09-15 23:12
(`98b5f2afe`); the change that produced this shape is `6acd8818e` (2026-09-15
10:53, "WIP checkpoint at the program switch: in-flight lane work committed
as-is", explicitly "Not a gated state"), continued in `a65985098` "Render
structured refusals through the shared error grammar". That work replaced the
old bounded `offending-value`/`offending-leaf` reconstruction with
`"Retain the actual offending values; the error render pair owns projection."`
— a correct move under the one-clipping-spot law (AGENTS §2.4). What it did
NOT carry over was WHICH value the seam names.

`git log -S` over `src/seon/render.clj`, `src/seon/turn.clj`,
`src/seon/sci/eval.clj` and `src/seon/call_preparation.clj` shows no commit on
2026-09-16 touching call preparation or instrumentation: the render-selection,
settlement, and loader lanes are not implicated.

### Root cause (mechanism), verified live

`src/seon/instrument.clj` bound

```clojure
offending (if arity? (:arity data)
              (if (= :input arm)
                [(:value (first (:errors explanation)))]
                (:value (first (:errors explanation)))))
```

i.e. ONE problem's leaf value, while `:seon.error/diagnostic-member` says
`:arguments` and `:seon.instrument/problem-count` may be greater than one.
Reproduced in the default JVM (pid 38993) against `#'seon.instrument/violation`
before the change:

```clojure
{:fn-name 'probe.ns/f :args [{:seon.db/connection 1}]
 :input [:cat [:map [:seon.db/connection :string]]]}
;; => offending [1]
;;    message "probe.ns/f refused argument 0 (0-based) at [:seon.db/connection]: …"
{:fn-name 'probe.ns/g :args ["a" 1] :input [:cat :string :string]}
;; => offending [1]
;;    message "probe.ns/g refused argument 1 (0-based) at []: …"
```

The second case is the tell: path `[]`, offending `[1]`, and nothing in the
value says which argument or how many arguments there were. A refusal that
names a bare `1` is not evidence-complete about the call.

### The fix

The offending value is the value the contract CHECKED — the same value the
`case` above already binds for every arm:

```clojure
offending (if arity? (:arity data) value)
```

input → the caller's arguments; output → the returned value; guard → the
`[arguments return]` pair; arity → the count. Nothing is lost: every problem
already carries its own leaf value and path in `:seon.error/problems`
(`seon.error/explain-problem`, `src/seon/error.clj:773`), and the message
still names the failing argument and path. No bounding is reintroduced at the
seam — the render pair keeps owning projection.

Live after adoption (`bin/seon init --dev default --changed src/seon/instrument.clj`,
commit `6aaa879b-57fe-55ef-aadd-1ebee6fdfddd`), same two probes plus an output arm:

```
[[{:seon.db/connection 1}] ["a" 1]]   ; input arm — the two gate expectations
7                                      ; output arm — the returned value
```

`test/seon/call_preparation_test.clj` is UNCHANGED: its expectations were right.

### In-process regressions (default, pid 38993)

| run | result |
|---|---|
| `seon.instrument-test/a-sci-only-arity-miss-names-its-program-graph-arglists` | 0 fail / 0 error |
| `seon.instrument-test/a-violation-carries-bounded-arguments-only-when-it-can` | 4 pass / 0 fail / 0 error |
| `seon.instrument-test/registry-sized-contract-evidence-retains-the-offending-object` | 0 fail / 0 error |
| `seon.instrument-test/registration-failure-names-the-var-and-authored-contract` | 0 fail / 0 error |
| `seon.instrument-test/jvm-and-interpreted-functions-arm-the-declared-guard` | 6 pass / 0 fail / 0 error |

VERIFICATION BOUNDARY: `seon.call-preparation-test` cannot be reproduced
in-process. Its probes are not armed in default's JVM, so
`test-support/refusal-data` returns `nil` and every assertion in that block
fails for an environment reason, not a behavioural one. The mechanism change is
proven directly against `#'seon.instrument/violation` with the exact two
argument shapes the test uses; the cold gate is the proof.

## 2. `seon.cluster.boot-test/a-dead-holders-run-is-unclaimed-by-the-time-start-returns`

Three sub-failures, all EXPECTATION drift. Boot recovery is behaving correctly.

### 2a. `(inst? (:seon.turn/closed-tx (db/pull … '[*] …)))` — impossible since 2026-09-09

`:seon.turn/closed-tx` is declared `[:and {:seon.wake/context-inert true}
:seon.db/ref]` (`resources/seon/schemas/seon.turn.edn:157`), made a ref by
`ae0e54841` (2026-09-09, "Move agent data to transaction refs, inbox edges, and
runtime components"). A pull of a ref attribute answers `{:db/id n}` and can
never answer an inst. Confirmed live on default:

```clojure
(db/pull d '[{:seon.turn/closed-tx [:db/id :db/txInstant]}] [:seon.turn/id "12a2b18544e6"])
;; => #:seon.turn{:closed-tx {:db/id 536870948, :db/txInstant #inst "2026-09-16T10:58:01Z"}}
```

Fixed by asserting the instant one hop away, through the ref.

### 2b. `[_ :seon.turn/reply-size ?d]` — an unbound query answered from another turn

The `testing` label claims "the run is CLOSED with its plan intact", but the
fixture seeded `run-crashed` with NO reply facts at all, and the query bound
`_`, so it could be satisfied by any turn anywhere in the store. It passed
historically off a bootstrap opening that carried `:seon.turn/reply-size`; the
generated system-turn opening does not. Observed live: default's opening turn
`12a2b18544e6` (`:seon.turn.work/situation :generate`, ordinals 0-9 = `(help)`,
`(dir …)`, the situation reads) carries no `:seon.turn/reply-size`, while the
six ordinary turns do.

This is the project's recurring class in mirror image: a check whose subject
was absent reported health from an unrelated entity.

Fixed by seeding the crashed turn's reply (`"(+ 1 1)"` and its count) and
asserting both facts on `[:seon.turn/id "run-crashed"]`. The seeded write shape
was proven admissible before the edit (LIVE-PROOF VALIDATION RULE):
`(#'seon.db/write-error database projection [that-map])` → `nil`.

### 2c. `(= 1 (:seon.boot/recovered-runs instance))` — a literal mirror of an incidental count

`recover-runs!` (`src/seon/cluster.clj:2341`) counts EVERY open turn at boot and
closes them all in one transaction. The test seeds one open turn but the first
boot's own bootstrap opening is also still open when `cluster/stop!` runs
immediately after `await-bootstrap!`, so the second boot honestly recovers 2.
The number is a property of what the previous boot happened to leave open, not
of whether the dead holder's run was unclaimed.

Fixed by DERIVING it: the turns whose `:seon.turn/closed-tx` is the transaction
that closed `run-crashed` ARE what recovery closed, so the instance's report is
compared against that count. That kills a real class — a boot report disagreeing
with the facts it committed — where the literal `1` only asserted an accident.
The query form was verified live (`:in $ ?tx` count against default).

VERIFICATION BOUNDARY: this test builds a published root and creates stores, so
`seon.test/run` REFUSES it in the development JVM (destructive-owner reach) and
it is cold-only. Every query form and the seeded write shape were proven
individually against default; the test itself has NOT been executed. It needs
the cold gate.

## Files touched

- `src/seon/instrument.clj` — the offending-value fix (mechanism).
- `test/seon/cluster/boot_test.clj` — the three recovery expectations.

## Shared-tree note: who committed the boot_test hunks

`test/seon/cluster/boot_test.clj` was clean when this lane started and went
dirty under the fixture-sweep lane while these edits were in the working tree.
That lane's `0da13c8ae` ("fixture writes: clusters, messages and turns carry
their required refs") committed the whole file, so all four hunks described
above landed under ITS message rather than this lane's. Nothing was clobbered
and nothing is missing — HEAD carries `crashed-reply` (line 1719), the seeded
reply facts (1742-1743), the `:db/txInstant` hop (1774, 1784-1787) and the
derived recovery count (1811). Recorded because the commit message does not
describe them. The foreign hunks in that file, which this lane did not touch,
are at `@@ -222` (`:seon.schema.admission/source` on a `legacy.core/f` row) and
`@@ -1467` (a seeded message recipient).

## One defect found in passing, filed not fixed

`seon.test/check` has no `:seon.test/long` filter, so checking the tests
reaching `seon.instrument/violation` selected
`seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
into default's JVM and spent the whole 120,000 ms
`:seon.test/check-time-limit-ms` bound, returning a bare `:seon.test/unknown`
with `:seon.test/next-tier :none` and none of the results it had already
recorded. The test booted and cleaned up its own isolated root
(`tmp/boot-test/caff60ab-…`); the developer store was never a target and
`default` stayed healthy (`bin/seon status`: 1/1 alive, pid 38993). Filed as
[in-process-check-selects-declared-long-tests](../../../seon/issues/in-process-check-selects-declared-long-tests.md).

## One more ugly-output defect at the same seam, fixed (`3e41a5d22`)

Met while probing: every arity refusal rendered

```
seon.cluster.source/current refused argument count at []: expected the declared
arglists, got an argument count of. Fix: Call one of the declared arglists.
```

`:seon.error/actual-description` for the arity problem was the bare prefix
`"an argument count of"` while the message template reads `"… got <description>."`,
so the one number the refusal exists to report never reached the sentence.
Not new: `docs/prds/context-generation/research/turn-test-reds-batch28-evidence-2026-09-16.edn`
records the identical truncation for `seon.test/stale`. Fixed by putting the
count in the description. After adoption: `"… got an argument count of 0."`.
No test asserted the old string.

In-process after that change: `seon.instrument-test/a-sci-only-arity-miss-names-its-program-graph-arglists`
6/0/0, `seon.error-test/diagnostic-construction-is-evidence-complete` 4/0/0,
`seon.error-test/exact-dispatch-producers-carry-their-class-markers` 11/0/0.

## The fix observed in the wild

A genuine refusal raised during this lane's own probing, after adoption:

```clojure
:seon.error/diagnostic-offending
[nil #datahike/Connection[… :cluster-default] {:seon.test.run/provenance …}]
:seon.error/problems [#:seon.error{:path [] :argument "test-var" :offending nil …}]
```

`seon.test/run` called with a nil Var: the diagnostic now names the whole call —
the nil Var, the connection and the options — while the per-problem leaf still
carries the exact `nil` and its path. The old shape would have said `[nil]`.

## Batch 68: the two remaining call-preparation errors (`ed2eb3423`)

Batch 68 (`d090c9934`) confirmed the first red closed —
`a-compiled-first-party-call-is-prepared` no longer appears — and
`seon.instrument-test` and `seon.error-test` are green. Two errors remained in
`seon.call-preparation-test`.

### Attribution: both predate this lane

Batch 65 B, at `ccccea806` and therefore BEFORE `6cbe2017c` and `3e41a5d22`,
already recorded the identical pair with the identical messages:

```
ERROR in (a-two-slot-arity-prepares-only-unique-partial-placements)
  seon.schema/compilable-form refused predicate-functions at []: expected a map,
  got nil.   caller "seon.call-preparation (call_preparation.clj:580)"
ERROR in (an-unavailable-supplier-refuses-before-the-body)
  seon.call-preparation/prepare refused plan-value at
  [:seon.call-preparation/contract-t] …   caller "call_preparation_test.clj:602"
```

They were invisible as "the" reds in 65 B only because the rest of that
namespace was drowning in fixture-admission errors that the fixture-sweep lane
has since fixed. `violation` is the REPORTER — it runs only after a contract has
already failed — so it cannot change what the arm hands `compilable-form`.

### 1. The cold-arming class, at three more call sites

A projection carrying no bound predicates has NO KEY (`schema.clj:2150` already
says so in prose); `compilable-form` declares a map. Reading the key bare hands
it nil. `eeafb9dba` fixed exactly this in `direct-references` yesterday, and
`instrument.clj:568`, `schema.clj:608` and `schema.clj:2156` already default.
Three feeders still read it bare and refuse in a cold worker, where nothing
binds a projection before the arm:

- `call_preparation.clj:583` — `argument-validators`, reached by every arity
  with more than one slot (the batch-68 error);
- `schema.clj:1535` — the render-input arity match;
- `schema.clj:3045` — the function-output arity compile.

All three now use the same `get`-with-`{}`. PRECISE CLAIM: every site that
reads the key INLINE into a `compilable-form` call is now total — verified by
walking each `compilable-form` call in `src/`. Sites that bind a
`predicate-functions` local earlier and pass it on are NOT covered by this
commit and still read the key bare: `fn.clj:1907`, `fn.clj:1966`,
`schema.clj:2171`, `schema.clj:2611`, `sci/eval.clj:444`, `sci/eval.clj:1937`.
Each builds or receives a projection on a path that has so far always carried
the key; none is the observed red. The durable cure is one named derivation for
"the predicate bindings this projection carries", which would replace all nine
spellings — worth doing once, in a slice that can be adopted and gated.
Verified live on default against a projection with the key `dissoc`'d:

```clojure
{:key-present-in-cold? false
 :bare-read "seon.schema/compilable-form refused predicate-functions at []:
             expected a map, got nil. Fix: Supply a map at []."
 :defaulted-read :compiled}
```

### 2. A hand-rostered plan value

`an-unavailable-supplier-refuses-before-the-body` built a plan of three keys,
and `:seon.call-preparation/plan` requires `contract-t`, `basis-t` and
`arities`, so `prepare`'s own contract refused before the subject was reached —
the same class as `d934f4355`. The plan is now compiled by `plan-for`, with
only the thing under test (a slot whose supplier cannot produce) substituted.
Live on default:

```clojure
{:hand-rostered-valid? false
 :compiled-substituted-valid? true
 :missing-from-hand (:seon.call-preparation/contract-t
                     :seon.call-preparation/basis-t
                     :seon.call-preparation/arities)}
;; and through the armed prepare, with the substituted plan:
{:kind :seon.call-preparation/unavailable :key :seon.db/db :index 1}
```

### Verification boundary

NOT adopted into default. `bin/seon init --dev default` refused during source
build with "Predicate `seon.cluster.store/file-lock-object?` has no admitted
callable in the corpus projection" — that predicate and its
`register-core-predicate!` are UNCOMMITTED working-tree edits belonging to
another lane (`src/seon/cluster/store.clj`, `resources/seon/schemas/seon.store.edn`),
and publication takes the whole tree. The adoption threw before adopting, so
default kept its prior commit and stayed healthy (pid 63433, 2 agents). Every
proof above is therefore a direct evaluation of the new forms against the live
cluster, never an adopted definition; the cold gate is the proof.
