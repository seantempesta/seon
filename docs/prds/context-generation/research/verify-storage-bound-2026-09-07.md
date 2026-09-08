---
type: research
status: complete
date: 2026-09-07
tags: [research, verification, storage, agent]
---

# Independent verification of the storage-bound landing

Written by the `verify-storage` lane against
[the storage-bound landing note](storage-bound-landing-2026-09-07.md),
[the agent record and turn loop PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§4a and §5, the lane's diff (`git diff 66cedc7fa..8f22125ad -- src resources
config`, read complete), and the three issue notes it filed. Every claim below
was re-derived on this lane's own evidence; nothing is repeated on the note's
authority.

Live surface: scratch cluster `verify-storage` under
`--root tmp/verify-storage-root`, published from HEAD (`ab11ce6e5`, two
commits past the note's `8f22125ad`), seeded with
`juniper_fixture_2026_09_06.clj`. Every source below went through
`seon.cluster.agent/submit-source!` — the ordinary durable turn path — and its
facts were read back out of the database. Cluster dials in effect:
`:seon.config.eval.result/max-bytes 8388608`,
`:seon.config.eval/time-limit-ms 30000`,
`:seon.config.eval.result/blob-threshold 4096`,
`:seon.config/on-core-error :panic`.
The probe is `tmp/verify-storage/probe.clj`.

## 1. The submission table, measured

Each row: one `submit-source!`, waited to `:seon.cluster.run/closed-at` under a
bounded loud await, then the evaluation entity read back.

| source | stored | `:seon.eval/missing` | `:seon.eval/size` | handle | turn | agent free after | wall |
|---|---|---|---|---|---|---|---|
| `(range 100000)` | **5,288,936 bytes inline**, no blob | — | — | `result/e34244` | closed | yes | 1209 ms |
| `(range)` | nothing | `:over-bound` | 8388608 | none | closed | yes | 1485 ms |
| `(atom 1)` | nothing | `:unserializable` | absent | none | closed | yes | 1258 ms |
| `(repeat "x")` | nothing | `:over-bound` | 8388608 | none | closed | yes | 1326 ms |
| `(apply str (repeat 9437184 "x"))` (9 MiB) | nothing | `:over-bound` | 8388608 | none | closed | yes | 2398 ms |
| `(apply str (repeat 5242880 "x"))` (5 MiB) | **5,242,929 bytes inline**, no blob | — | — | `result/e34469` | closed | yes | 2464 ms |
| `(reduce (fn [m i] {:a m}) {:leaf 1} (range 5000))` | **1,690 bytes of DIAGNOSTIC**, `:seon.cluster.eval/error` | — | — | `result/e34367` | closed | yes | 2096 ms |
| `(lazy-seq (Thread/sleep 60000))` | not executable: `Unable to resolve symbol: Thread/sleep` | — | — | — | closed | yes | 1695 ms |

`open-run-after` was `nil` on every row: no submission wedged the agent at
`agent-already-running`, and nothing ran past its bound. Every row settled in
under 2.5 s.

**The sleeping lazy sequence cannot be written in agent source.** Five
spellings were probed live through `submit-source!` and every one refused at
analysis: `(Thread/sleep 10)`, `(java.lang.Thread/sleep 10)`,
`(Thread. (fn []))`, `(clojure.core.async/timeout 10)`,
`(.take (java.util.concurrent.LinkedBlockingQueue.))`. The behaviour was
therefore probed at the admission seam instead — see §2.2.

## 2. Probe 2 — is the interrupt consulted DURING streaming?

### 2.1 It is, and it beats the bound (HOLDS)

```clojure
(admit/admit {:seon.sci.admit/value (range)
              :seon.sci.admit/interrupt-fn
              (fn [] (when (> (swap! calls inc) 5000) (sci.interrupt/interrupt! "probe time limit")))
              :seon.sci.admit/caps caps           ; max-bytes 8388608
              :seon.config/on-core-error :panic})
;; => THREW clojure.lang.ExceptionInfo "probe time limit"
;;    seon.sci.kernel/interrupted? => true
;;    interrupt calls => 5001
```

The interrupt fired at node 5001, far before the 8 MiB bound, and propagated
out of `admit` as sci's own uncatchable interrupt — not swallowed, and not
converted into `:over-bound`. The evaluation ends interrupted, exactly as
claimed. `project` rethrowing both `interrupted?` and `over-bound?` before the
`:on-core-error` branch is what makes this true, and it is the right order.

### 2.2 A BLOCKING realization is not bounded by it (HOLDS-WITH-AMENDMENT)

```clojure
(def blocking (lazy-seq (do (Thread/sleep 5000) [1])))   ; JVM-side value
;; interrupt-fn throws sci/interrupt! once 1000 ms have passed
;; measured: blocking-elapsed-ms => 5005, answer => "probe deadline"
```

Admission returned after **5005 ms against a 1000 ms deadline**. The
`:interrupt-fn` is a POLL: `items!` calls `(seq values)` / `(first remaining)`
to obtain the child *before* `project` calls the interrupt on it, so a source
that blocks inside its own realization is never asked. The deadline was
observed only once the block released.

This does not refute the design — streaming is still strictly better than
counting a finished string, and it is what makes `(range)` and `(repeat "x")`
terminate at all — but the astra B8 sentence as written ("a lazy sequence can
block before its first byte") describes a case the streaming bound does not
actually solve. What solves it today is that no blocking primitive is
reachable from agent source (§1). That mitigation is a property of the SCI
binding table, not of this seam, and nothing asserts it.

## 3. Probe 3 — the ablated handle (HOLDS)

```text
my.agents.juniper=> (inc result/e34257)      ; the (range) evaluation, missing
#:seon.repl{:error "… Unable to resolve symbol: result/e34257", :result result/e34351, :ms 3}

my.agents.juniper=> (count result/e34244)    ; the stored (range 100000)
100000
```

Exactly sci's ordinary unresolved-symbol error for the dead handle, and the
live handle beside it resolves to the WHOLE sequence. Reading the stored node
back independently confirms faithful storage: face `:seon.print/list`,
**100,000 items**, first `0`, last `99999`, **zero** nodes carrying
`:seon.print/elided`, `/pruned` or `/truncated-string`, and
`admit/semantic-value` reproduces a 100,000-element sequence `0 … 99999`.

## 4. Probe 4 — byte identity of the two projections (HOLDS)

`seon.repl/render-ai` and `seon.repl/render-html` were called on the same
pulled evaluation entities. In all four cases the AI response line and the
HTML response string are the SAME BYTES:

| evaluation | AI bytes | AI response line | equals HTML response string |
|---|---|---|---|
| `(range 100000)` | 179 | `#:seon.repl{:value (0 1 … 30 31 ...), :result result/e34244, :ms 88}` | yes |
| `(range)` | 104 | `#:seon.repl{:value #:seon.eval{:missing :over-bound, :size 8388608}, :ms 97}` | yes |
| 9 MiB string | 130 | `#:seon.repl{:value #:seon.eval{:missing :over-bound, :size 8388608}, :ms 780}` | yes |
| 5 MiB string | **5,242,987** | the whole string, quoted | yes |

`seon.repl/missing-text` is the one generator of the missing sentence, it is
data and not comment-shaped, and both projections read it. That part of the
claim holds without amendment.

The transcript-side half of this claim
(`one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`) is
covered by the gate in §9.

## 5. Probe 5 — "HTML renders the stored value with no elision" (REFUTED as worded)

No HTML surface renders the 100,000-element value.

- `seon.repl/render-html` renders the SAME `*print-length* 32` line as
  `/ai` — `(0 1 … 30 31 ...)` — by construction, because both go through
  `entity-emission` and the one REPL grammar. That is the ruled behaviour and
  the landing note says so (§5); it is the assignment's wording that is wrong.
- `/data` pages every collection at `:seon.render.value/max-collection`
  (8 entries, with a pager), so it does not render 100,000 items either.

The note's actual §3.7 claim is narrower and about STRINGS, and it holds at the
storage layer: a 5 MiB string is one inline datom of 5,242,929 bytes, served
whole. The route-level proof is
`seon.render.web-test/data-serves-a-five-megabyte-attribute-whole-with-its-handle`,
which is outside the selection I was asked to run and was NOT re-measured here.

## 6. Probe 6 — the fault committer (HOLDS, with a defect beside it)

`seon.error/normalize` was called with five sources. It never threw and never
produced "could not be normalized":

| source | recorded kind | recorded `data-edn` | `:seon.error/capped?` |
|---|---|---|---|
| `(atom 1)` | `:seon.error/unclassified` | `#:seon.eval{:missing :unserializable}` as a print node | **false** |
| `(range)` | `:seon.error/unclassified` | `#:seon.eval{:missing :over-bound, :size 8388608}` | **false** |
| flat value carrying an atom member | `:probe/render-fault` (preserved) | the whole map, atom rendered as an object node | false |
| `(ex-info "render fault" {:probe/evidence (atom 1)})` | `:seon.error/unclassified` | the complete `Throwable->map`, 11,655 bytes | false |
| `(ex-info "render fault" {:probe/evidence (range)})` | `:seon.error/unclassified` | **the marker only**, 297 bytes | **false** |

The claim holds: the fault is recorded with a marker rather than the committer
dying, and `admitted-or-marker` is the reason.

Two things beside it are not right, and both are the class this project keeps
meeting — see findings D and H in §10.

## 7. Probe 7 — every guard on `result-edn` / `missing` / `size` / print node

What each reports when its subject is ABSENT:

| site | guard | absent ⇒ |
|---|---|---|
| `src/seon/sci/admit.clj` `admit-walk` | `(if (unserializable-root? print-node) …)` | a node with a `:seon.print/value` is faithful; only a bare object or a `::failed` root is missing. Correct — but it asks only at the ROOT (§10 F) |
| `src/seon/sci/admit.clj` `admit*` | `(if (int? (:…/max-bytes caps)) … (missing-bound-refusal caps))` | a flat refusal NAMING the key. Correct; the strongest check in the diff |
| `src/seon/sci/admit.clj` `restorable-node` | `(when (string? serialized) …)` | nil ⇒ no handle. Correct — this IS the ablation |
| `src/seon/cluster/loop.clj:1621` | `(when (and entity-id (restorable-node result-edn)) …)` | no handle. Correct |
| `src/seon/repl.clj` `entity-emission` | `(and (int? (:db/id unit)) (restorable-node …))` | no `:seon.repl/result` key. Correct |
| `src/seon/repl.clj` `missing-text` | `(when (keyword? missing) …)` | nil. Correct in context |
| `src/seon/repl.clj` `value-text` | `(some? (missing-text emission))` first | falls through to the node, then nil. Correct |
| `src/seon/repl.clj` `response` | `(when (seq entries) …)` | nil for an evaluation that settled nothing. Correct as far as it goes — but an INTERRUPTED evaluation settles `:ms` only, and `interrupted-at` is in neither `entity-emission` nor `response-order`, so it reads identically to a still-running one (§10 G) |
| `src/seon/cluster/run.clj` `terminal?` | `(or result-edn missing error interrupted-at)` | false = "still running". Correct, and the addition of `missing` is exactly the fix it claims |
| `src/seon/cluster/work.clj` `next-ordinal` | the same four as `not-join` clauses | the form is re-attempted. Consistent with `terminal?`; the two now agree |
| `src/seon/cluster/run.clj` `settlement-projection` | `cond->` on `missing` and `(int? size)` | key omitted. `absent = no key`. Correct |
| `src/seon/render/transcript.clj` `emission` | `(and (::result entry) (nil? (::missing entry)))` | no `:seon.repl/value`; missing travels as its own key. Correct |
| `src/seon/render/value.clj` `admitted-projection` / `artifact` | `(if (:seon.eval/missing admitted) …)` | else-branch `select-keys` — an admission that carried NEITHER a node nor `missing` still yields `{}`, the shape the fix existed to end. Unreachable today, undefended tomorrow |
| `src/seon/error.clj` `admitted-or-marker` | `(if (:seon.eval/missing admitted) …)` | the marker. Correct |
| `src/seon/error.clj` `capped?` | `(not= full-edn (:seon.error/data-edn fact))` | **false when the whole evidence was replaced by the marker.** Reports "nothing omitted" precisely when everything was (§10 D) |
| `src/seon/render/walk.clj` `connection-width` | `(if (nat-int? declared) declared Integer/MAX_VALUE)` | **no elision is ever reported, including the pull's own truncation** (§10 C) |

## 8. Probe 8 — the four legacy cap keys (REFUTED as stated)

`rg` over `src/`, `script/` and `resources/` finds the four display-cap keys
read at **fourteen** call sites in **eleven** files, not the six or seven the
note's §3.1 table names.

In the note (verified present, all reached through `seon.config/result-caps`
or an explicit caps map, all safe on a cluster whose config carries the keys):

| reader | key(s) | nil-safe? |
|---|---|---|
| `src/seon/cluster/message.clj:279,303` | `max-string` | destructured, passed on |
| `src/seon/render/web.clj:3207-3208` | `max-string` | passed on |
| `src/seon/eval/drive.clj:89-90` | `max-string` | passed on |
| `src/seon/render/ns.clj:43-44` | `max-nodes`, `max-collection` | **yes** — `pos-int?` guards |
| `src/seon/error.clj:355,357` | `max-depth`, `max-collection` | passed into a profile map |
| `src/seon/instrument.clj:142-145` | all four | a literal map — a WRITER, not a reader |
| `script/seon/fresh_operator.clj:1614-1618` | all four | `select-keys` |

**Not in the note** — seven further sites, five of them a bare `(long …)`
that would be `RT.longCast` on nil, which is the exact class §3.1 argues the
re-homing chunk must avoid:

| reader | key | shape |
|---|---|---|
| `src/seon/render/web.clj:593` | `max-collection` | `(long (:… caps))` — **nil-unsafe** |
| `src/seon/render/transcript.clj:828` | `max-nodes` | `(long (get-in unit …))` — **nil-unsafe** |
| `src/seon/render/transcript.clj:881` | `max-nodes` | `(long (get-in unit …))` — **nil-unsafe** |
| `src/seon/render/transcript.clj:1072` | `max-string` | `(long (get-in unit …))` — **nil-unsafe** |
| `src/seon/render/walk.clj:106` | `max-nodes` | `(dec (long (:… caps)))` — **nil-unsafe** |
| `src/seon/render/walk.clj:148` | `max-collection` | the pull's query-work limit (documented in the diff) |
| `src/seon/render/walk.clj:571` | `max-nodes` | `(long (:… caps))` — **nil-unsafe** |
| `src/seon/print.cljc:974` | `max-string` | `seon.print/admit-string`'s request key |

§3.2 **holds and has already landed**: `script/seon/fresh_operator.clj:1613`
carries `:seon.config.eval.result/max-bytes` at HEAD (`ab11ce6e5`).

One consequence of the move that the note does not record:
`seon.print/admit-string` (`print.cljc:968-979`) has **no caller in `src/`** any
more — `seon.sci.admit` was its only one. It survives only in
`test/seon/print_test.clj` and in `seon.fn`'s `authorized-callers` set. Dead
production code, and it is the last owner of `:seon.print/truncated-string`
production.

## 9. Probe 9 — the gates

Run at HEAD `ab11ce6e5`, on the assigned selection:

```
bin/test seon.sci.admit-test seon.sci.eval-test seon.repl-test \
         seon.render.transcript-test seon.cluster.run-test seon.error-test
```

**15 red, 167 tests, 1016 assertions, 35 failures, exit 1.** Every red was
confirmed reproducible in the runner's own isolated confirmation pass.

```
seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets
seon.error-test/a-committed-fault-renders-its-evidence-without-renderer-failure-prose
seon.error-test/capping-is-honest
seon.error-test/fault-preparation-bounds-the-fact-and-omits-disposable-flow-state
seon.error-test/fitting-can-require-a-blob-below-the-content-size-threshold
seon.render.transcript-test/a-tight-budget-degrades-then-elides-loudly
seon.render.transcript-test/every-generated-history-is-ordered-total-and-token-bounded
seon.render.transcript-test/malformed-receipt-bytes-and-any-unique-about-stay-replayable
seon.render.transcript-test/populated-history-restores-the-repl-fidelity-checklist
seon.render.transcript-test/receipt-content-enters-the-shared-capped-floor
seon.render.transcript-test/same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order
seon.render.transcript-test/supersession-chains-vanish-before-token-accounting
seon.render.transcript-test/tight-budgets-pull-only-a-budget-derived-newest-candidate-set
seon.sci.eval-test/runtime-function-rows-carry-parsed-contract-facts
seon.sci.eval-test/static-and-runtime-contracted-definitions-publish-identical-facts
```

`one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` — the
byte-identity proof the lane had to keep — is **GREEN**. That half of §4.2
holds.

**§4.2's "no new red" does NOT hold for this selection.** Eleven of the fifteen
are in the note's inherited seventeen. **Four `seon.error-test` reds appear
nowhere in the note's inherited list and nowhere in its §4.2 accounting**, and
three of them fail on assertions this diff's own edits break:

| red | assertion | attribution |
|---|---|---|
| `capping-is-honest` | `(true? (:seon.error/capped? wide))` → `false` | **this wave** — `src/seon/error.clj:502-505` dropped `(:seon.sci.admit/capped? admitted)` from the disjunction, and admission no longer produces the key at all |
| `fault-preparation-bounds-the-fact-and-omits-disposable-flow-state` | `(<= fact-bytes 4096)` → **915,655**; `(<= data-edn-bytes 4096)` → **614,906**; the message no longer contains `"more characters"` | **this wave** — `:seon.config.eval.result/max-string` was the only bound on a fault's own message |
| `fitting-can-require-a-blob-below-the-content-size-threshold` | fitting no longer shortens the evidence; `capped?` `false` | **this wave** — same two causes |
| `a-committed-fault-renders-its-evidence-without-renderer-failure-prose` | fault cards render `"renderer unavailable"`, `ArityException: Wrong number of args (1)` on `seon.error/render-faults-html` and `seon.cluster.message/render-inbox-html` | **NOT this wave.** `render-faults-html` is `[faults database]` while the walk invokes producers with one argument; `git diff 66cedc7fa..8f22125ad -- src/seon/error.clj` does not touch it (0 hits). It arrived with `879967692` / `7ff102072`. Verified before naming a cause |

The `seon.render.transcript-test` and `seon.sci.eval-test` reds match the
note's inherited set exactly, and
`seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets` is the
documented disabled-rendering-limits member.

```
bin/test --platform
```

**GREEN: 73 platform tests, 395 assertions, 0 failures, 0 errors**, and the
runner removed its isolated operator root as successful. Identical to the
note's §4.1 figure. Verdict on the platform half of §4: **holds.**

## 10. Findings, ranked

### Blockers — fix before the next lane

**B1. Faults are no longer bounded, and the number is measured.**
`seon.error-test/fault-preparation-bounds-the-fact-and-omits-disposable-flow-state`
is red: one fault fact is **915,655 UTF-8 bytes** against its own declared
4,096 inline limit, its `:seon.error/data-edn` is **614,906 bytes**, and the
`"… more characters"` elision the message used to carry is gone.
`:seon.config.eval.result/max-string` was the only bound on a fault's own
message and its inline evidence, and §3.1 correctly identifies `seon.error` as
a reader that needs "render-profile constants of its own" — but the cap was
removed from the walk before those constants existed. Faults are DURABLE
database facts routed into a steward's context; this is a growth path on the
store and on every fault-carrying prompt. Verdict on §3.1's deferral:
**holds-with-amendment — the deferral is not safe for `seon.error`.**

**B2. The AI boundary has no string elision at all.**
`seon.repl/render-ai` for the stored 5 MiB string result is **5,242,987
bytes** for ONE evaluation (§4). `*print-length*` bounds collection width, not
string length; `seon.print/fit` preserves the complete admitted node by
standing ruling; and the transcript's token budget is among the disabled
rendering limits. So the wave's own premise — "elision happens only at AI
context generation" — is unmet for the exact shape whose cap it deleted: a
long string is elided NOWHERE, in storage or in context, up to 8 MiB.
[The filed `/data` issue](../../../seon/issues/the-data-route-has-no-presentation-bound-for-a-string.md)
names the route; this is the same missing bound on the prompt path, which is
the more expensive half. Verdict on §1.2/§5: **holds-with-amendment.**

**B3. Deleting `:seon.sci.admit/capped?` killed a live REFUSAL.**
§3.4 says every protected reader is nil-safe and "`nil` now means what `false`
meant". True for `src/seon/error.clj:489` and the three `src/seon/cluster.clj`
sites. **False for `src/seon/effect.clj:606`**, where `false` meant "the value
was whole" and the branch is a refusal:

```clojure
;; effect.clj:605-629, measured on this cluster
(admit/admit-value {… :seon.sci.admit/value {:probe/payload <9 MiB string>}})
;; => keys [:seon.eval/missing :seon.eval/size], capped? => nil
;; so (:seon.sci.admit/capped? projected-request) is nil, the
;; :seon.effect/request-too-large refusal never fires, and
;; :seon.effect/request-edn is recorded as (admit/canonical-edn nil) => "nil"
```

An oversized capability request is now **silently dispatched with a `nil`
request** instead of refused. Verdict on §3.4: **refuted.**

**B4. Admission is no longer total in depth; the JVM stack is the only bound.**
Measured on this cluster with `(reduce (fn [m _] {:a m}) {:leaf 1} (range n))`:

| n | `admit` under `:record` |
|---|---|
| 200, 400, 600, 800, 1000, 1500 | ok |
| **2000** | **throws `java.lang.StackOverflowError` OUT of `admit`** |
| 5000, under `:panic` | `:seon.sci.admit/projection-failed` naming `clojure.lang.PersistentArrayMap` — never the stack |

`:record` is the production dial, so in production a value ~2000 deep escapes
admission as a raw `StackOverflowError` at an evaluation boundary where law
2.4 requires a flat value. The comment this diff DELETED from
`config/default.edn` named exactly this reason — "the recursive walk overflows
near depth 5000" — and nothing replaced it; the wave's own regression
`a-value-under-the-bound-is-stored-whole-however-wide-or-deep` tests depth
200. Verdict on §1.1: **holds-with-amendment — the caps were display caps, but
`max-depth` was also load-bearing for totality.**

**B5. The walk's elision observation now reports "fine" when the pull
truncated.** `connection-width` answers `Integer/MAX_VALUE` for any request
carrying no profile, while the pull's own limit stays
`:seon.config.eval.result/max-collection + 1 = 8193`. The observation is
`elided? (> (count values) width)`, and `values` was already cut at 8193, so
the predicate can never fire for any declared width ≥ 8193 — including the
profile-less default. Measured on this cluster, distance 1 from `juniper`:

| request | elision observations |
|---|---|
| `:seon.render.profile/max-children 1` | 7 |
| no `:seon.render/profile` | **0** |
| `:seon.render.profile/max-children 100000` | **0** |

Before the change the two numbers were the same constant, and the `+1`
over-fetch is precisely what made the report exact. This is the project's
named failure class — a check that reads absence of signal as health — created
by the fix. Verdict on §1.6: **holds-with-amendment — the move to the
presentation authority is right; decoupling it from the pull's limit without
reporting the pull's own cut is the defect.**

### Frictions

**F1. `:seon.error/capped?` is `false` precisely when everything was dropped.**
Five `normalize` probes, all `capped? false`, including one where an 11,655-byte
`Throwable->map` was replaced wholesale by a 297-byte marker (§6). Two reds
(`capping-is-honest`, `fitting-can-require-a-blob-below-the-content-size-threshold`)
assert the wanted behaviour and are the regression already in place.

**F2. One unserializable member ablates the WHOLE fault evidence.**
`(ex-info "render fault" {:probe/evidence (range)})` records only the marker;
`(ex-info "render fault" {:probe/evidence (atom 1)})` records all 11,655 bytes
with the atom as an opaque node. The difference is `over-bound` throwing out of
the walk versus `unserializable-root?` asking only at the root — so the
evidence a fault CAN keep depends on which failure the walk met, not on what
was serializable.

**F3. `:seon.eval/size` is the BOUND, never bytes reached.**
The 9 MiB string reports `8388608`, not its own ~9,437,186 bytes; so does
`(range)`; so does `(repeat "x")`. The lane's regression asserts exactly this
(`(= 4096 (:seon.eval/size admitted))` for a 4096 bound), but
`resources/seon/schemas/seon.eval.edn` declares it as "UTF-8 bytes reached when
the value went over the storage bound". A diagnostic that reads as a
measurement and is a constant is the one thing a diagnostic may not be. Either
rename it (`:seon.eval/bound`) or measure the source. Verdict on §1.3:
**holds-with-amendment.**

**F4. §3.1's inventory is short by seven sites in four files**, five of them a
bare `(long …)` on a key the chunk plans to delete (§8). The deferral argument
is sound and its list is not the whole list.

**F5. `seon.print/admit-string` is dead production code.** `seon.sci.admit` was
its only `src/` caller; it survives in `test/seon/print_test.clj` and in
`seon.fn`'s `authorized-callers`, and it is the last producer of
`:seon.print/truncated-string`.

**F6. A blocking realization is not bounded by the interrupt** (§2.2): 5005 ms
against a 1000 ms deadline. Mitigated today only because `Thread`,
`clojure.core.async` and `java.util.concurrent` are all unresolvable from agent
source — a property of the SCI binding table that nothing asserts.

**F7. `:seon.sci.admit/capped?` rides as a stored `nil` on a live agent-facing
surface.** `src/seon/cluster.clj:440` writes it into every MCP `eval_clj`
response (`"seon.sci.admit/capped?": null` on every result in this lane's
session) though it is declared in no schema. Absent = no key.

**F8. Two size spellings for one meaning.**
`:seon.cluster.eval/result-size` is still written by
`src/seon/cluster/loop.clj:711,754` and pulled by
`src/seon/cluster/curate.clj:275`, beside the new `:seon.eval/size`.

**F9. `seon.repl` never renders `:seon.cluster.eval/interrupted-at`** — it is
in neither `entity-emission`'s selection nor `response-order`, so an
interrupted evaluation reads in the agent's own history exactly like a running
one. PRD §4 says it MUST render.

**F10. `:seon.eval/missing :lost` is declared and written by nothing**, which
is the honest consequence of §3.3 and matches
[the filed blob-reader issue](../../../seon/issues/a-stored-result-blob-has-no-reader-at-the-render-boundary.md).

**F11. The `:unserializable` class name is cheaper to restore than
[its issue](../../../seon/issues/a-missing-value-loses-the-class-it-could-not-serialize.md)
suggests.** A nested reference already keeps it:
`(admit-value {:probe/payload (atom 1)})` →
`#:probe{:payload #:seon.sci.admit{:opaque "clojure.lang.Atom"}}`. Only the
ROOT drops it, two lines from `object-node`.

### Agreement — claims re-proven on this lane's own evidence

| claim | verdict |
|---|---|
| §1.1 admission applies no display caps; a value is admitted whole | HOLDS (100,000 items, 0…99999, zero elision faces; a 5 MiB string stored whole) |
| §1.2 the bound is streaming, under the evaluation's own SCI interrupt | HOLDS — the interrupt fired at node 5001 and won over the 8 MiB bound (§2.1) |
| §1.3 faithful or missing, with the reason | HOLDS (shape); see F3 for `size` |
| §1.4 windowing is deleted | HOLDS — `settlement-result`, `result-blob-smaller?`, `result-window-page-size`, `result-window-edn`, `capped-result?` and the two-arity `restorable-node` are gone from `src/` |
| §1.5 a missing value ablates its handle | HOLDS — `(inc result/e34257)` → `Unable to resolve symbol: result/e34257`; `(count result/e34244)` → `100000` |
| §1.7 the filed NPE class is dead | HOLDS — a caps map without the key returns a flat refusal naming `:seon.config.eval.result/max-bytes` as its `:seon.error/diagnostic-member`, with a regression |
| §2 `(range 100000)` = 5,288,936 emitted bytes | REPRODUCED EXACTLY |
| §3.2 `fresh_operator` must gain `max-bytes` | HOLDS, and LANDED at `ab11ce6e5` |
| §3.3 every faithful value inline, no result blob staged | HOLDS — 5,288,936 and 5,242,929 bytes both inline, `result-blob` nil, above a 4,096 threshold |
| §3.3a a bare host reference is missing, not described | HOLDS — `(atom 1)` → `#:seon.eval{:missing :unserializable}` |
| §3.3a-1 missing is a terminal fact | HOLDS — every one of eight submissions closed its turn and left the agent free; no `agent-already-running` |
| §3.3a-2 `evaluate` no longer refuses its own output | HOLDS — `(range)` settles in 1485 ms |
| §3.3a-3 the fault committer records a marker | HOLDS — five sources, none threw, none produced "could not be normalized" (see F1/F2 for what is wrong beside it) |
| §3.3a-4 `seon.render.value/artifact` is total for a missing admission | HOLDS by reading; the else-branch remains `select-keys` (§7) |
| §5 the four live rows | REPRODUCED, all four, plus four more (§1) |
| byte identity of `/ai` and `/html` for one evaluation | HOLDS on all four probed evaluations, missing markers included |
| `one-reply-reads-identically-…` stays green | HOLDS (§9) |

Assignment item (5) as worded — "HTML renders the stored 100000-element value
with NO elision" — is **refuted on every surface**, and the landing note never
claims it (§5 of this note).

## 11. Housekeeping

- `tmp/verify-storage-root` was brought down and deleted; `tmp/verify-storage/`
  keeps the probe and the gate logs.
- `tmp/juniper-context-live` and the default root were not touched.
- Every background shell this lane started was ended before this note was
  written.
- `tmp/test-runs/run.mF5rSr` is this lane's own retained failed selection root
  and is left in place: its fifteen reds are the evidence behind §9 and §10 and
  have not yet been fixed. It is sweepable once B1-B5 are addressed.
