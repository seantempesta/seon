---
type: research
status: complete
tags: [research, runtime, sci, concurrency]
---

# Phase 1 W2 — the interrupt arm rides with the work

Read end to end before implementing, and confirmed read:
[the Phase 1 lane specs](../plan/seon-env-phase1-specs-2026-08-07.md) (W2 is
the contract), [the seon.env PRD](../plan/seon-env-prd-2026-08-07.md),
[the interrupt-arm issue](../../../seon/issues/interrupt-arm-does-not-cross-a-thread-hop.md),
[the Phase 0 fork-carriage report](env-phase0-fork-carriage-2026-08-07.md),
and `reference-code/sci/doc/interrupt.md`.

## What was wrong, restated from source

`sci` lifts `:interrupt-fn` off the ctx captured at fn creation
(`reference-code/sci/src/sci/impl/fns.cljc:40,64,152`), so the interrupt
FUNCTION crosses a thread with the code. Seon's guard then threw that away:
every branch of it sat inside `(when-let [armed (.get thread-arm)] …)` over a
plain `ThreadLocal`, and `own-arm` set that slot on the calling thread only.
The arm — not the function — was the thing that could not travel.

Two consequences, both measured before the change (baseline re-run of Phase
0 probe B on this tree, `clojure -M:dev`, no cluster):

| Arm | Baseline |
|---|---|
| 20k interpreted loop, armed, same thread | `:seon.eval/fn-entries 20002`, `:ok` |
| identical workload awaited on a virtual thread | `:seon.eval/fn-entries 0`, correct value |
| unbounded loop detached under a 300 ms limit | 64,949 ticks at 1500 ms, never interrupted |
| control: same loop on the arming thread | interrupted at 303 ms, `:time`, 3,293,635 entries |

## The change

`src/seon/sci/kernel.clj`. The thread-local slot survives as the fast path
for finding the arm governing the running thread; it is no longer the arm's
identity.

- **`new-armed`** extracts the arm VALUE — every counter, latch and identity
  one evaluation needs, shared by whichever threads serve it. Nothing in it
  is thread-local.
- **`current-arm`** hands that value out. It is the ONE way an arm leaves the
  thread that created it, and capturing marks the arm `::travelled`.
- **`adopt-arm`** installs a carried arm for the dynamic extent of one piece
  of work and restores the displaced arm on the way out.
- **`arm?` + `:seon.sci.kernel/arm`** declare the carriage so the contract is
  a registered schema rather than a bare map (registered core predicate plus
  an honest generator that constructs a real arm, per the existing
  `seon.flow`/`seon.sci.eval` pattern).
- Counters became `AtomicLong` (`::entries`, `::host-interop-observations`)
  and `::built-in-calls` an atom, so entrances made on any thread accumulate
  into the one arm.
- `stop!` no longer cancels the deadline of an arm that has travelled, and no
  longer resets the `reached` latch. The deadline belongs to the arm value,
  not to the arming thread, so detached work is still cut at ~the limit that
  admitted it after its parent evaluation has disarmed. An arm that never
  travelled has no other observer and its timer task is still cancelled.

### The re-entrancy rule the issue asked for

Stated in `adopt-arm`'s docstring and enforced by construction: **adoption is
strictly nested.** A thread already armed saves that arm, serves the carried
one for the extent of the work, and restores it on exit. Arriving work
therefore never has to be refused and two limits are never merged: at every
instant the thread serves exactly one arm, and the displaced arm's deadline
is a latch on its own value rather than a clock on this thread, so nothing
about it is lost while it waits. A nil carried arm runs the work unarmed,
unchanged — system-side work that never came from an evaluation is not an
error.

### Diagnostics that stopped lying

- `:seon.eval/fn-entries` now counts entrances wherever they happened. The
  vocabulary table's "12 reads as blocked in a host call" reading is true
  again; the "or the work ran on another thread" caveat the PRD added can be
  retired.
- `:seon.eval/allocated-bytes` is a per-thread JVM counter, so sampling now
  happens only on the owning thread, and `adopt-arm` adds each adopting
  thread's own delta at release. Previously an adopting thread would have
  written its own allocation minus the arming thread's start value into the
  sample — a number with no meaning.

## The handoff contract for W1

W1 owns `src/seon/flow.clj`, `src/seon/sci/eval.clj`, `src/seon/cluster.clj`;
this lane owns `src/seon/sci/kernel.clj`. The arm is a VALUE W1's carriage
carries — exactly two calls, no new mechanism:

1. **At the submission, on the submitting thread.** Put the arm in the
   submission's environment under the key `:seon.sci.kernel/arm`:

   ```clojure
   (assoc environment :seon.sci.kernel/arm (kernel/current-arm))
   ```

   `current-arm` returns `nil` when the submitter is not inside an armed
   evaluation. That is not a refusal case: nil simply means the work carries
   no arm. The environment's own presence stays W1's refusal (`var-process`,
   `submit!`/`submit!!`); the arm member is optional, like the evidence sink.

2. **On the thread that runs the work, and around `complete!`.** Wrap the
   work in `adopt-arm`:

   ```clojure
   (kernel/adopt-arm (:seon.sci.kernel/arm environment) #(work-fn …))
   ```

   `adopt-arm` is the direct replacement for what `bound-fn*` was doing for
   the arm, and it is a value read from the submission rather than a thread
   binding captured at wrap time — so it survives the Phase 3 `bound-fn*`
   deletions unchanged.

Nothing else is required of W1, and nothing in `seon.flow` needs to know what
an arm contains: `:seon.sci.kernel/arm` is opaque, declared, and validated by
the registered schema.

**Integration state.** This lane proved its half with a probe-level carrier
(a host function in the regression namespace that does exactly the two calls
above), because W1's submission seam is not landed in the tree yet. The two
calls above are the remaining integration step; the issue stays OPEN until
they land, and it names that as its only remaining state.

## Acceptance evidence

### Probe B's exact scenario, inverted

`bin/test seon.sci.kernel-arm-carriage-test`, five consecutive runs:

```text
run 1: Ran 4 tests containing 16 assertions.  0 failures, 0 errors.
run 2: Ran 4 tests containing 16 assertions.  0 failures, 0 errors.
run 3: Ran 4 tests containing 16 assertions.  0 failures, 0 errors.
run 4: Ran 4 tests containing 16 assertions.  0 failures, 0 errors.
run 5: Ran 4 tests containing 16 assertions.  0 failures, 0 errors.
```

The detached test asserts the inverted scenario directly: an unbounded
interpreted loop handed to a virtual thread under a 300 ms limit, with the
parent evaluation already disarmed, settles with `::interrupted? true` and
the whole sequence completes inside 2000 ms (observed ~510 ms wall for the
test, i.e. the 300 ms limit plus settle). Entrance attribution is asserted
separately: the 20k-iteration workload awaited on a virtual thread records
`>= 20000` `:seon.eval/fn-entries` on the governing arm, where the baseline
recorded `0`.

### The regression is not green vacuously

`tmp/w2/liveness.clj`, one run, `adopt-arm` neutered with `with-redefs` so
the crossing carries nothing:

```clojure
:with-carriage      {:parent :detached, :settled {:interrupted? true}, :ticks 3600}
:adopt-arm-neutered {:parent :detached, :settled :still-running, :ticks 183830}
```

With carriage the loop is interrupted after 3,600 ticks; neutered it is still
running after 3 s at 183,830 ticks. The regression fails when the mechanism
is removed, which is the only thing that makes five green runs mean anything.

### Re-verified against the new sci pin

W3 advanced the fork to `seon-env-hook` head `f934044` (superproject
`288fab5c6`) mid-lane: `:built-in-call-observer` is now read from the RUNTIME
ctx, and `sci/init`/`merge-opts` refuse unknown option keys. Both were
checked against this change rather than assumed:

- The option refusal does not bite. `build-base-ctx`
  (`src/seon/sci/eval.clj:180-191`) destructures `::kernel/guard` out of
  `context-options` and passes only sci's own keys to `sci/init`.
- Built-in call accounting travels with the arm too. An interpreted fn that
  calls `clojure.core/mapv` inside adopted work on a virtual thread:
  `{:value [2 3 4] :fn-entries 1 :built-in-calls-observed 1 :sample
  (clojure.core/mapv)}`. Under the thread-local arm the observer would have
  no-op'd off-thread and reported zero.
- The regression is green three more consecutive runs on the new pin
  (4 tests / 16 assertions each), and the liveness check still separates
  cleanly there: `{:settled {:interrupted? true} :ticks 3066}` with carriage
  versus `{:settled :still-running :ticks 155930}` neutered.

## Reported friction and defects found on the way

1. **BLOCKER, fixed here: every existing schema resource was unmodifiable.**
   Adding one key to `resources/seon/schemas/seon.sci.kernel.edn` was refused
   with a `schema-key-collision` for all fifteen keys already in the file.
   Root cause in `src/seon/schema/admission.clj`: `default-registry-excluding`
   excluded the candidate file from its disk walk but seeded the registry
   from the LIVE `(schema/registered-schemas)`, which already holds that
   file's published declarations — so the exclusion removed nothing. Measured
   from a fresh JVM, admitting an UNCHANGED `seon.sci.kernel.edn`,
   `seon.flow.edn`, or `seon.env.edn` reported a collision for every key in
   the file, so this blocked all four Phase 1 lanes and anyone else touching
   a schema. Fixed by subtracting the candidate file's own on-disk keys from
   the live-registry seed; committed separately, path-limited.

2. **UGLY OUTPUT: `schema-exact-reuse` warnings are unreadable at volume.**
   The same one-key edit produced hundreds of warnings of the form "Schema
   `:seon.sci.kernel/already-armed` has the same shape as existing
   `:my.background/invalid-call`…". Every `[:= true]` marker schema in the
   system matches every other one, so the check fires N² times on shapes that
   are marker keywords by design and cannot be shared. The signal (a genuinely
   duplicated composite shape) is buried under it, and the reader's only
   option is to skip the whole block — which is how a real finding gets
   missed. The check wants to ignore shapes with no structure (`[:= x]`,
   bare keywords), or to report one grouped finding per shape rather than one
   per existing key.

3. **`bin/seon init` was red for a foreign reason while this lane worked.**
   The failure at the time of writing is `:malli.core/invalid-schema {:schema
   :seon.env/layer}` from W1's uncommitted `src/seon/env.clj` +
   `resources/seon/schemas/seon.env.edn`. This lane's own half was verified
   coherent independently: `:seon.sci.kernel/arm` and both new
   `:malli/schema` declarations resolve against the loaded registry. Noted
   per the foreign-breakage rule, not fixed here.
