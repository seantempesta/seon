---
type: research
status: active
tags: [research, runtime]
---

# Flow prototype verdict: what the running code proved and what it broke (2026-07-25)

Subject under review: `docs/prds/sci-execution-runtime/research/flow-design-2026-07-25.md`.
Supporting evidence already established: `redesign-ledger-2026-07-25.md` (R-1..R-18)
and `wtf-review-2026-07-24.md`.

Prototype: 767 lines in
`/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-ef9f-4cc7-954e-ea6dbabccdff/scratchpad/flow`,
real Datahike file stores, real SCI, real `sci/fork`, real platform and virtual
threads, real second JVMs killed with `destroyForcibly`. Not wired into the tree;
nothing under `src/` or `test/` was modified.

## The verdict in one paragraph

The design's **physics are right and its core thesis survives contact**: a form-granular
pure transform over a database value, receipts as durable position, `:interrupt-fn`
containment on platform threads, a semaphore-bounded `:compute` pool, and messages as
facts all worked and are measured below. The design **as written in
`flow-design-2026-07-25.md` is not implementable** — three of its own sentences are
false against the running code: the turn-level transform signature (unimplementable,
already replaced), "No ticker, no polling" (stranded runs were never recovered until an
unrelated commit arrived), and the implied claim that the limits bound an eval (a
program ran 19.6x past its `time-limit` and 1480x past its allocation cap and was
reported `:ok`). Sixteen defects were found by adversarial attack; **fourteen are
fixable inside the design and two need an owner ruling**. None of the fourteen requires
a new mechanism. The honest cost is that "roughly 250 lines" becomes roughly 450-550,
and "replaces ~10,000+" is really ~7,000 measured.

Do not implement from the design document as it stands. Implement from the defect
ledger in section 2.

## 1. Does the design hold up, and what did the prototype prove

Yes, with the corrections below. Every number here was produced by the prototype on
JDK 26.0.1, Clojure 1.12.0, this machine.

### Proven: form granularity, and why the turn-level signature is dead

The reviewed signature `(database value, agent, message) -> [tx-data, messages, effects]`
was **not built**, because it cannot be. The prototype ran the identical form twice: once
against the turn's opening basis, once against the step's basis (the previous step's
transaction report `:db-after`). Answers: **0 and 9**. A turn-level transform can only
ever return the first answer, so read-your-own-writes inside a turn is impossible under
that shape. The built shape is `(db-after of previous step, agent id, step result) -> tx-data`,
committed immediately with an ordered receipt; the turn is a fold of steps.

Read-your-own-writes then costs **zero extra round trips** — Datahike's own transaction
report carries `:db-after`. Receipts record `:seon.eval/basis-t` per step and the chain
is strictly increasing across a turn.

### Proven: containment on the interpreted path

- Interpreted runaway `(loop [] (recur))`, `time-limit` 500ms, cap 4GB: killed `:time`
  at **503ms**, 107,533,312 fn entries, 2,593,128,128 bytes allocated.
- Allocating interpreted loop, cap 64MB: killed `:memory` at **67,324,048 bytes in 21ms**
  — 0.32% overshoot from the 1024-entry sample interval.
- SCI interpreted allocation rate measured for the first time: **~5.2 GB/s**
  (~48.1 bytes per fn entry). A bare spin loop trips a 64MB cap in **29ms**, 17x sooner
  than a 500ms `time-limit`.
- The uncatchable marker holds. `(try <runaway> (catch Exception e ...))` with a class
  that actually resolves was killed at 1,385,472 fn entries; the marker never reached the
  user catch clause. Inner-catch/outer-retry was killed too. `sci/interrupt!` does what
  `reference-code/sci/src/sci/interrupt.cljc:32-42` claims.
- Host interop is shut with `:classes` unset: `(.pow (biginteger 10) 50000000)` and
  `(java.lang.Thread/sleep 2000)` both fail to analyse. sci's own canonical unbounded
  example is closed. (It does not matter — see D7 and D8.)

### Proven: platform threads are load-bearing, and the arming site is too

`getCurrentThreadAllocatedBytes` independently reconfirmed: platform thread
`[0 -> 4,796,600]`, virtual thread `[-1 -1]`. Separately: **the `:interrupt-fn` must be
armed on the `:compute` thread, not by the caller.** The first prototype version armed it
on the `:io` thread, reported 183KB for a run that had allocated ~67MB, and misattributed
a `:memory` kill as `:time`. This is a real implementation trap, not a detail.

No carrier pinning: with `jdk.virtualThreadScheduler.parallelism=1` and 8 claimants
wedged inside evals, an unrelated virtual thread completed 5/5 steps in 902ms. Both
`Semaphore.acquire` and `Future.get` unmount. Agent code never runs on a virtual thread
at all, so it has no pinning surface — a second reason the platform-thread choice is
load-bearing.

### Proven: fork isolation, exactly as far as the design claims and no further

200 concurrent forks each defining and reading the **same** var name through the full
eval path: zero cross-fork bleed. Same for `defmulti`/`defmethod`,
`defprotocol`/`extend-protocol`, and records. sci built-ins are read-only from agent code
(`alter-var-root` on `clojure.core/inc` → "Built-in var is read-only"; `intern` → same;
`in-ns 'clojure.core` → refused). Host-function vars in the base cannot be hijacked
either (they are plain fns, not sci vars).

The leak class is nonetheless **real and demonstrated** on a deliberately-unsafe base:
fork A's `swap!` on a base atom is visible to fork B; fork A's `defmethod` on a base
multimethod is dispatched by fork B. The invariant "base vars hold only functions and
immutable values" is therefore load-bearing and **nothing enforces it** — it is prose
plus one demo line.

### Proven: crash resume from ordered receipts, single-writer

- SIGKILL between form 3 and 4 of 7: child exit 137; a survivor's pure query returned
  `total 7`, in-flight `{:index 3, :source ...}`, remaining indices 3-6 with exact
  sources, receipts `[[0 :ok][1 :ok][2 :ok][3 :running]]`; the survivor claimed by CAS
  and executed 3-6 to completion.
- Six kill positions (first form, mid-turn, inside the step commit, last form, final
  commit, after close) plus a double kill, each resumed by a real second JVM: right
  position every time, no index skipped, all receipts terminal, run closed. Double kill
  converged at epoch 3.
- At-least-once is honest and tight: **exactly one re-execution per crash** (8 evals for
  7 steps; 9 after a double kill).
- The committed ordered step plan beats re-parsing the reply, as designed: the prototype
  deliberately models `preflight/repair-read-entry` splicing (6 emitted entries → 7
  executed forms) and resume answered `total 7` throughout. A reply re-parse answers 6.
- Storage durability is solid. Commits that returned survived SIGKILL every time (kill
  points after commits 7, 63, 211). SIGKILL **inside** `d/transact` at 8 kill points,
  200-datom transactions: every reopen succeeded, every tag held exactly 0 or 200
  entities. No torn transaction, no corruption.

### Proven: write coalescing, and where the real cost is

- 200 single-datom transactions: serial **45.09 ms/tx**, 200 concurrent virtual threads
  **0.53 ms/tx** — 84.6x. The concurrent figure matches the prior session (0.49); the
  serial figure is 2x worse on this store path.
- Curve at n = 1/10/50/200 concurrent agents: **30.25 / 6.73 / 2.04 / 0.73 ms/tx**.
- One turn of 7 steps end to end: ~104 ms/step, of which eval time is 0-5ms. **The commit
  path, not SCI, is the cost centre** — 12 transactions per turn.
- 100 disjoint agents x 3 steps driven by 100 simultaneous claimants: all 300 `:ok`
  receipts, every counter exactly 3. Every corruption found needs shared state or a
  shared run.

### Proven: the semaphore queues and never bounces a claim

22 concurrent evals against 18 permits (config fact, default `availableProcessors`): 4
queued, max wait 71ms, total 88ms, every caller proceeded, no claim refused. At 8 against
2, same. Containment under concurrency: 18 healthy evals `[min median max]` 20/23/25ms
alone → 19/25/48ms with a `:time` runaway and a `:memory` hog added, both killed
correctly.

### Proven: OOM does not take the writer down, twice

A `(byte-array 2000000000)` under `-Xmx512m` with a co-located Datahike writer surfaced as
a flat error, `:seon.error/raw "Java heap space"`, and the **very next `d/transact`
committed successfully**. A retained-1GB attack reproduced it independently: the next eval
returned 2. `StackOverflowError` is likewise contained (17-26ms, permit released, host
healthy). Permits were never leaked across ~50 killed, thrown and OOM'd evals.

## 2. What broke, and whether it is fixable

Sixteen defects. Severity is the effect on a running cluster, not on the prototype.
"Design-level" means the sentence in `flow-design-2026-07-25.md` is wrong or missing;
"implementation" means the prototype coded it badly and the design is silent or correct.

### D1 — Multiple JVMs each holding their own write connection silently destroy each other's history (fatal, design-level)

Two live JVMs on one file store: the child's held connection returned **VISIBLE 0** after
the parent committed 99 datoms. Both processes **won the same epoch CAS**, both reported
`my-view-epoch 1`, and both drove the identical full run. Quantified: parent **0 of 40**
committed entities survived, child 40 of 40 — **40 successfully-returned commits vanished
with zero transact errors**, and the final store looked pristine (epoch 1, receipts
`[0..5 :ok]`, counter 6) with no trace that a second claimant ever existed.

The design's loop sentence — `claim (CAS) -> acquire database value -> transform -> commit`
— never says where the write connection comes from. CLAUDE.md already rules that the
writer JVM owns transactions and that other processes forward writes. The prototype
violated that rule and the file store is last-writer-wins at the branch head.

Fix: one writer connection per store; claimants forward writes. The unsafe configuration
must **refuse to open** rather than silently corrupt. Every clean crash-resume result in
section 1 was single-writer or strictly sequential JVMs; none of them is evidence for the
multi-writer configuration.

### D2 — Recovery of a stranded run is not event-driven and did not happen (fatal, design-level)

SIGKILL the only claimant 600ms into a chain, start a survivor running the real `wake!`
path, then commit nothing. The run went `claimable? true` at t=4s (lease 3000ms) and
**stayed stranded through t=12s — four lease periods — with 1 of 7 receipts and
`:run/open? true`. Nothing ever scanned.** Committing one unrelated entity
(`{:agent/id "totally-unrelated"}`) completed the entire chain instantly.

A lease going stale is not a committed transaction, so `d/listen` on the
committed-transaction feed structurally cannot deliver it — and the moment it matters is
exactly when the feed goes silent. `flow-design-2026-07-25.md` line 103, "No ticker, no
polling", is the defect.

Fix without reintroducing a poll: the commit that **sets** `:run/lease-until` is itself an
event, so every listener schedules a one-shot wake at that instant, cancelled by the run's
close commit. That is event-driven off a real commit, and a firing timer is a genuine bug
report in the ruling's sense.

### D3 — Receipts are a mutable cursor, not append-only evidence (fatal, design-level)

Real wake path, 10 agents, one 1-step run each, correct end state all counters 1. Observed
counters `{z0 1, z1 1, z2 1, z3 35, z4 704, z6 721, ...}` with receipts `([:ok 10])` —
exactly one clean `:ok` per run. **One step ran 704 times and the entire observability
surface shows no trace of it.** `:seon.eval/id` is a unique identity keyed by
(run, index), so a second claimant's `:running` receipt **overwrites** the first's
terminal `:ok`; resume then reads that `:running` as in-flight and resets the cursor, so
two claimants mutually re-run the same step.

This also silently destroyed the only evidence of the double execution in D4 (receipts
read a pristine `[0..5 all :ok]`).

Fix: key the receipt by (run, index, epoch) so a fenced claimant's write is
distinguishable rather than destructive; resume counts **terminal** receipts only, and a
`:running` receipt from a foreign epoch is abandoned, not in flight. A step with a terminal
receipt at its index must never be re-executable.

### D4 — No epoch fence after the claim (serious, design-level)

Single writer, real CAS: claimant A holds a 500ms lease, blocks 4s inside a host call, a
survivor legitimately steals the run (epoch 1→2). Both executed index 2. **A, never told
it was fired, went on to commit indices 3, 4, 5 and closed the run.** Final counter 3
where 6 was correct. `drive-run!` validates the claim once at the top and folds every step
to the end without re-checking the epoch it won.

In the real system every step is a model or capability call longer than any plausible
lease, so an unfenced overrun is the normal path, not an edge case.

Fix: include `[:db/cas run-eid :run/epoch e e]` in every step transaction. The stale
claimant's next commit fails and it stops. Renew the lease on a schedule owned by the
claim — while a step is in flight and while queued — not only at step boundaries.

### D5 — Wake is a positive feedback loop that kills the process (fatal, design-level)

Commits per useful run: **n=2 → 7.0, n=5 → 14.4, n=10 → 124.8**. Lost CAS claims: 5, then
157, then **10,343**. Ten agents produced 423,621 lines of Datahike CAS stack traces. At
n=20 the process **died with `OutOfMemoryError: Java heap space`** after 2,555 scan
attempts. `scan!` commits; every commit fires `listen!`; every `listen!` submits a whole
new `scan!` onto an unbounded virtual-thread executor. This is the direct cause of the
704x re-execution in D3.

The design points at the existing `listen!` → `scan!` wiring
(`driver/host.clj:807-815`) as already done. It is the defect, not the solution.

Fix is the repo's own stated live-update chain, applied here: attribute-indexed interest
(wake only on `:message/to` and `:run/open?` datoms this claimant did not author) →
equality suppression → a single-slot latest-wins pending scan. No scan-per-commit.

### D6 — The pure transform's read-modify-write loses updates with no failure at all (fatal, design-level)

40 concurrent 1-step runs for one agent: 40 runs, 40 `:ok` receipts, `:agent/counter = 1`.
**Thirty-nine increments lost, nothing anywhere reporting a problem.** Repeated at 10:
counter 1, nine lost. No crash, no stolen claim, one writer.

This is a property of the reviewed signature itself: a transform that reads a value from
its basis and writes a computed value is only correct if nothing else writes that
attribute between basis and commit, and `wake!`/`scan!` makes concurrent runs for one
agent the normal case.

Fix: emit `[:db/cas eid attr seen (inc seen)]` and re-run the transform against a fresh
basis on failure, or stop storing it — the repo rule is derive projections instead of
storing them, and this counter is exactly `(count receipts)`.

Made invisible by set semantics: `:agent/log` is cardinality-many string, so 10 runs
writing the same string produced 1 datom and the 704 re-executions produced 1 log line.
No duplicate-execution detector can be built on the visible facts; anything meant to prove
a step ran once must carry (run, index, epoch).

### D7 — `read-string` executes host code at read time, walking around sci entirely (fatal, implementation)

`flow/eval.clj:54` calls `clojure.core/read-string`, which honours `*read-eval*` (observed
`true` on the compute thread). Source
`[:x #=(clojure.core/spit "…/PWNED.txt" "read-eval escaped sci")]` returned `[:x nil]`,
outcome `:ok`, **0 fn entries, 0ms — and the file exists on disk**. Also
`#=(java.lang.System/getProperty "os.name")` → `"Mac OS X"`, and
`#=(java.lang.Runtime/getRuntime)` evaluated. No `:interrupt-fn`, no allocation
accounting, no `:classes` allowlist, no ctx.

Fix: use sci's own reader, `(sci/parse-string ctx source)` — edamame, which has no `#=` at
all — and the parse cost then sits inside the armed window. Stopgap
`(binding [*read-eval* false] ...)`. That this survived in a 68-line file written by an
author who was actively thinking about containment is the evidence that matters.

### D8 — The 1024-entry sample means the limits do not bound an eval (fatal, mixed)

The `:interrupt-fn` performs **zero** checks for any program with fewer than 1024 fn
entries, and each entry may drive an arbitrarily expensive host call. No interop, no
tricks:

- `(let [b (loop [v 2N i 0] (if (< i 18) (recur (* v v) (inc i)) v))] (mod (apply * (repeat 300 b)) 7))`
  → **319 fn entries, 9,825ms against a 500ms limit (19.6x), 99,340,558,744 bytes against
  a 64MB cap (1480x), outcome `:ok`**, agent received its answer.
- 29-entry BigInteger ladder: 2,338ms / 15.7GB / `:ok`. Bare `(byte-array 200000000)`:
  200MB / 0 entries / `:ok`. `host/block 900` under a 500ms limit: completed at 905ms,
  `:ok`.

Fix, two cadences: the time flag is a volatile boolean — read it on **every** entry (a
volatile read is nanoseconds against the ~48 bytes/entry the interpreter already
allocates); keep the 1024 sample only for the expensive `getCurrentThreadAllocatedBytes`
call. That turns the 9.8s case into a kill at the next fn entry. It does **not** fix a
single long host call — that is D9 and the owner ruling in section 2b.

### D9 — One poisoned form drains every compute permit in the cluster, permanently (fatal, design-level)

Permits 4, lease 1500ms, **one** message to **one** agent whose reply is
`(host/block 600000)`: rounds 0-3 show epoch climbing 1,2,3,4 with permits free 3,2,1,0;
rounds 4-5 still claim (epoch 5,6) and park on the semaphore. A healthy agent's message
then produced "STILL BLOCKED, never started after 6005ms, permits free 0". **Lost CAS
claims: 0** — every re-claim succeeded because the wedged claimant never contests.

The mechanism is the design's own recovery: `drive-run!` blocks until the eval returns, an
un-interruptible host call never returns, the lease goes stale, a survivor CASes the epoch
and re-runs the **same in-flight step**, feeding the next victim. `flow/eval.clj:45` has no
`.get` deadline, no cancel, no interrupt, so the permit and the platform thread are never
reclaimed.

Fix, three parts, all from data the design already commits: (1) bound the caller with
`.get(deadline + slack)` and `Thread.interrupt` the compute thread so host calls that
honour interruption unwind; (2) never re-execute blindly — the receipt already carries
index, `:running` and the epoch, so a pure query answers "this step has wedged N
claimants" and fails the run; (3) a wedged thread must leave the permit pool so capacity
degrades by one rather than to zero.

Related, same fix: `try`/`finally` outlives the kill. `(try <runaway> (finally (host/block 3000)))`
recorded the kill but ran 3,056ms against a 500ms limit; with `(host/block 600000)` the
eval was still running after 4s and the permit count dropped permanently. "The interrupt
fired" and "the eval finished" are different events, and `:seon.eval/ms` is the only place
that shows it.

### D10 — Agent-returned tx-data reaches the database unfiltered (fatal, design-level)

A step returned `{:facts [[:db/add "not-an-eid" :nope 1]]}`. The eval **succeeded**; the
poison detonated in the driver's own `d/transact` and the exception **escaped
`drive-run!` entirely**. The run was left open, epoch 1, receipt stuck at `:running`, and
**no fault recorded anywhere** — outcomes present were only `#{:running :ok}`. A survivor
retried 3 times and threw 3 times: a permanent poison pill with no attempt counter and no
dead-letter path.

Hostile-but-valid facts are worse because nothing complains: one step wrote **424242 into
another agent's counter**, another retracted its own run's `:run/open?` and `drive-run!`
carried on because it never re-reads that flag.

This violates "Nothing throws into the agent loop. Every failure is a `:seon/error`
value." The design's three shapes name "durable FACTS the driver commits" — the driver has
to decide what is committable, not pass through.

Fix: commit the step transaction inside a try; on failure commit a **terminal** receipt
carrying the fault, alone, which both records it and kills the pill because resume
advances past a terminal receipt. Constrain facts to attributes the agent may write on
entities it owns. Add an attempt count for dead-lettering.

### D11 — `start-run!` is check-then-act and spliced two model replies into one plan (serious, design-level)

12 trials of two concurrent `start-run!` calls for one run id with two different replies (7
steps vs 4). **3 of 12 committed the plan `BBBBAAA`** — indices 0-3 from reply B, indices
4-6 from reply A: one run entity, 7 steps, a coherent-looking plan spliced out of two
different model replies. `:run/id` and `:step/id` are unique identities so both
transactions "succeed", and `:step/source` is cardinality one so the later write wins per
index. In the real system the race window is the model call — seconds, not microseconds.

Fix: write the ordered plan as one cardinality-one value on the run, or gate the insert on
a `:db/cas`, so a second reply replaces the plan wholesale or not at all.

### D12 — `resume` uses a count as a position (minor, implementation, latent)

With an injected receipt set holding a hole (0,1,2,5,6 `:ok`; 3 and 4 absent), `resume`
answered `{:total 7, :next-index 5}` — steps 3 and 4 skipped forever, 5 and 6 re-executed.
`next-index = (if in-flight (key in-flight) (count receipts))` at `driver.clj:73` is only
correct while receipts are dense from 0, an invariant nothing enforces. Reached by
injection, not by a natural failure.

Fix, one line that also removes the in-flight special case: next-index is the first index
in `(range total)` with no terminal receipt.

### D13 — Message identity upserted an earlier message and silently killed a cycle (serious, design-level)

Two agents messaging each other in a loop stopped after **3 hops with no error**.
`:message/id` is `(str from "->" to "#" index)`, a unique identity, so the second message
upserted the first entity — which already had a run — and `waking-inbound`'s not-join
excluded it. Final state: 3 messages, 3 runs, zero open runs, nothing reported, and a torn
read where a completed run's `:run/message` points at an entity rewritten under it.

Fix: derive message identity from the sending receipt (run, index, epoch). Note the
tension the fix must resolve deliberately: a deterministic id is exactly what keeps
delivery idempotent under D3/D4 re-execution, so D3 and D13 must be designed together.

### D14 — The allocation limit measures the wrong quantity, and its default kills ordinary work (serious, naming and config)

It measures cumulative allocation (throughput), not live footprint, so it is
anti-correlated with the heap risk it appears to bound:

- **Kills a harmless program**: 20,000 x `(byte-array 1000000)`, all immediately garbage,
  live footprint ~0 — killed `:memory` at 1,023,124,376 bytes.
- **Misses a dangerous one**: retaining 1,000 x 1MB under `-Xmx512m` — 473 fn entries,
  only 473,333,160 bytes allocated, cap never fired, **the JVM OOM'd instead**.
- **Kills ordinary agent work**: `(reduce + (range 500000))` — killed at **20ms**,
  67,188,432 bytes, agent told "Allocated 67,188,432 bytes, past the limit."
  `(reduce + (range 100000))` survives at 24.8MB. Meanwhile the 99GB program in D8 passes.
- Consequence: at the default cap every interpreted runaway is reported as `:memory` in
  ~12ms, so the `:time` branch — including "blocked inside one host call", the single most
  diagnostic string in the design — is effectively unreachable.

Fix: name it what the JDK names it. `getCurrentThreadAllocatedBytes` is allocation, and
64MB is a **work budget** of ~1.4M interpreted steps (measured 48.1 bytes/entry). Say
that in the config key and in the agent-facing message. If the intent is "one agent must
not exhaust the heap", no in-process metric can express it — that is the process boundary,
and the design must say so instead of implying a heap bound. The three-way distinction the
agent needs is looping / blocked in a host call / retaining too much, and only the middle
one is currently expressible.

### D15 — Agent code cannot catch `Throwable` or `Error`, and `StackOverflowError` says nothing (minor, implementation)

`(catch Throwable e ...)` → "Unable to resolve classname: Throwable"; same for `Error`,
`RuntimeException`, `StackOverflowError`, `:default`. Only `Exception` and specific
subclasses resolve. Unbounded recursion yields message "Threw after 26ms.", raw `nil`,
because `.getMessage` on a `StackOverflowError` is nil and the fallback branch drops the
class name.

Fix: add `Throwable`/`Error` to `:classes` (idiomatic agent Clojure writes
`catch Throwable`), and include `(.getName (class t))` when `getMessage` is nil.

### D16 — `clojure.string` runs uninstrumented (minor, implementation, no exploit observed)

`flow/ctx.clj` merges `interrupt/clojure-core` but not `interrupt/clojure-string`, so
`clojure.string/replace`, `split` and `replace-first` run with **0 fn entries** against
6,244-16,356 entries on the guarded `re-find` path for the same input. The asymmetry is
real and measured. I could not make it bite: on JDK 26, four catastrophic-backtracking
patterns including the canonical `(x+x+)+y` at 22/26/30 characters all returned in 0-2ms.
Reporting a failed attack honestly — merge the namespace on principle, no exploit claimed.

### 2b — The two items that need an owner ruling, not a code fix

- **Heap blast radius with a co-located writer.** No in-process metric bounds live memory
  (D14). A single un-overridden host allocation is uncatchable by the `:interrupt-fn`
  (D8). Measured mitigation: an agent OOM did not take the writer down, twice. Measured
  risk: the writer's heap is the agent's heap. Either accept that blast radius explicitly
  or separate the processes. The design's Monitoring section currently implies a memory
  limit exists.
- **The un-interruptible host call ceiling.** `Thread.stop` is gone, so a host call that
  ignores interruption can only be escaped by killing the process. D9's fix bounds the
  *claimant* and the *permit*, not the call. That ceiling should be stated as the system's
  limit, in the agent-facing docs, rather than left implied.

## 3. What is still unproven

Be suspicious of anything not on the section 1 list. Specifically:

- **No LLM, no real capability calls.** No fs, web, shell, or agent-authored `db` write
  passed through the door. No `:mixed` workload. No streaming. The agent's "reply" is a
  pure function. Every eval was CPU-only or a deliberate host block. Every timing number
  for a turn is therefore a lower bound by the cost of a model call.
- **Lease renewal while parked on the semaphore is not implemented.** Renewal happens only
  at step boundaries. Max observed wait was 71ms against a 60s lease so it never fired
  here; a convoy of long evals makes a healthy queued run stealable. This is the reviewer's
  own required item, left undone.
- **R-8a was not falsified.** `seon.host.instrument`'s global fair `ReentrantReadWriteLock`
  is not in the prototype's path at all, so "other agents' latency is unaffected" says
  nothing about the tree. The risk lives untouched in `src/seon/host/instrument.clj`.
- **The multi-writer configuration has no clean result.** Every successful crash-resume
  was single-writer or strictly sequential JVMs. D1 invalidates concurrent-JVM claiming
  entirely; nothing has been proven about resume once writes are forwarded through one
  writer.
- **`resume` is re-queried at every step** — two Datalog queries per step over all
  receipts and all steps, O(steps^2) per turn. Correct, unoptimised, unmeasured at length.
- **Allocation overshoot is bounded only by what 1024 fn entries can allocate** — 0.32%
  measured, unbounded in principle (D8).
- **No Malli schemas anywhere.** The repo requires complete `:malli/schema` on durable
  defns. No `:seon/error` discipline beyond a flat map. Prototype keys (`:agent/*`,
  `:run/*`, `:step/*`, `:config/*`) are prototype-local and **are not proposed
  vocabulary**.
- **The fork base invariant is unenforced.** Prose plus one demo line; the leak class is
  demonstrated real on an unsafe base. Nothing prevents a future base from holding an atom
  or a multimethod.
- **Only the `:file` backend was exercised.** No cloud store, no remote writer, no replica
  session, no web-render reader concurrent with claimants.
- **No reset-boundary live proof.** Schema, acquisition, and process behaviour at a
  cluster reset is a different failure class than any fixture or prototype can see, per
  the repo's own testing rule.
- **Production footprint at scale is unmeasured.** Thread counts, RSS, and store growth
  were observed only at prototype scale (≤200 concurrent transactions, ≤22 concurrent
  evals, ≤100 agents).
- **The corrected line count is an estimate, not a measurement.** Section 6 projects
  450-550 lines after D1-D16; only the 284-line broken version was actually built.
- **ReDoS on other JDKs.** Not reproduced on JDK 26 (D16); the instrumentation asymmetry is
  real and the negative result is JDK-specific.

## 4. The four open questions

### Q1 — Read-your-own-writes: re-acquire, or read as-of the turn's basis

**Decided: neither.** Each step's basis is the transaction report's `:db-after` from the
previous step's commit. Datahike already returns it, so read-your-own-writes is free — no
re-acquisition round trip, no as-of semantics to teach agents.

**Validated.** Section 1's 0-vs-9 contrast is the direct measurement, and receipts record
a strictly increasing `:seon.eval/basis-t` chain per turn. Cost as predicted: the turn has
N bases, not one, so forensics gets N per turn, and other agents' commits are visible
between steps. That is snapshot-isolation physics, already accepted, now stated per step.

### Q2 — Transform granularity: turn or form

**Decided: form.** The turn is a fold of forms; the resume unit is the form.

**Validated, and the turn-level alternative was actively falsified** — see section 1.
Everything downstream of the turn framing (Q1, crash semantics, message atomicity)
dissolved. This is the design's single strongest simplification and it survived every
attack that was aimed at it; the fatal defects found are all in the *loop* around the
transform, not in the granularity choice.

### Q3 — Message delivery: effect or fact

**Decided: a fact, committed in the same transaction as the sending step's tx-data.**
Delivery is the existing `listen!` wake; consumption is the recipient's own claim/commit.
The `effects` slot was deleted outright: a capability call happens inside the step and is
covered by that step's receipt.

**Partially validated.** Atomicity is proven — a step's message and its facts are one
transaction, and message-chain crash recovery was clean at two kill points (b1/b2/b3 each
ended with exactly one run, 7/7 receipts, no duplicate or lost message) **whenever a
survivor actually scanned**. Two defects landed on this answer: the wake path itself is
the stampede in D5, and message identity upserts and silently kills a cycle in D13. The
decision stands; its implementation does not.

Stated cost, unchanged and now measured: atomicity is per **step**, not per turn. A sender
that crashes after step 3 has already delivered step 3's messages and there is no unsend.
This must be in the agent-facing docs as the semantics. The missing correlation attribute
(R-16) remains explicitly deferred.

### Q4 — Semaphore bound and exhaustion

**Decided: a config fact defaulting to `availableProcessors`; at exhaustion the caller
blocks — queue, never refuse the claim.**

**Validated as far as it goes, and then broken by what it queues behind.** Queueing works
(22 against 18: 4 queued, max wait 71ms, no bounce; 8 against 2: same). But D9 shows one
un-interruptible host call removes a permit permanently and the lease-steal cascade drains
the pool to zero from a **single** poisoned form, and 2 permits against two 6s host calls
pushed innocent-agent latency from 484ms to 6,516ms. The permit is held by the `:io` caller
across `.get`, so the bound is on *waiting* rather than on *computing*. The decision is
right; it requires D9's three-part fix and a rule that an agent-initiated blocking call
releases the `:compute` permit while it waits.

## 5. What this deletes, and the falsifier for each deletion

Line counts measured in the tree today. Each falsifier is the observation that would prove
the deletion premature; run it before the cut, not after.

- **`src/seon/host/eval.clj` (566), `host/invoke.clj` (284), `host/preflight.clj` (344),
  `host/record.clj` (483), `host/sample.clj` (116)** — the eval entry, invocation, reply
  repair, receipt writing and sampling. Replaced by `flow.eval` + the receipt fields in the
  driver's step transaction.
  *Falsifier:* a reply whose repair (`preflight/repair-read-entry` splicing, `record.clj:384`
  source rewriting) produces an executed form list the committed ordered step plan cannot
  reproduce. The prototype models the splice and answers correctly; run it against real
  recorded replies before cutting.
- **`src/seon/host/guard.cljc` (242)** — the containment door, whose filename is itself on
  the killed-vocabulary list. Replaced by `flow.interrupt` (65 lines).
  *Falsifier:* any containment behaviour in that file with no counterpart in the D7/D8/D14
  corrected `:interrupt-fn` — in particular anything bounding output size, which the
  prototype does not implement at all.
- **`src/seon/host/graduate.clj` (276)** — "graduate/graduation" is killed vocabulary and
  the mechanism is a phase gate the form-granular fold does not have.
  *Falsifier:* a live caller that changes durable state rather than deriving it. Check
  `src/seon/host.clj` and `src/seon/agent.cljs` first — both reference the `seon.host.*`
  set.
- **`src/seon/agent/driver/host.clj` (823), `agent/driver.cljc` (647),
  `agent/driver/pod.cljs` (51)** — the six-phase cursor driver. Replaced by `flow.driver`.
  *Falsifier:* a phase whose work is not expressible as an ordered step with a receipt.
  The recovery arm at `driver/host.clj:700` is already proven dead (5-arg call to a 7-arg
  fn); the rest needs the same treatment before the cut.
- **`src/seon/agent/loop.cljs` (702), `agent/turn.cljs` (787), `agent/run.cljs` (1167)** —
  the CLJS loop, turn and run surfaces. Replaced by the fold plus `run.core`'s claim
  algebra (kept verbatim per the ledger).
  *Falsifier:* `loop.cljs:39` `transitions` is already zero-caller; for the other two, any
  behaviour reachable from a live route or agent-facing toolkit fn that has no JVM owner.
- **`src/seon/agent/lifecycle.cljc` (506)** — leaf-bound lifecycle calls, the exact
  old-engine residue the standing goal names. Replaced by lifecycle as returned values the
  driver interprets.
  *Falsifier:* a lifecycle transition that must be observed by the agent *within* the same
  eval; that is the one shape the value-returning form cannot express.

Sum of the above: **6,994 lines across 14 files.**

Explicitly **not** in that number, and not deleted by this design:

- `src/seon/host/context.clj` (2,181) and `src/seon/agent/ctx.cljc` (1,959) — the
  context/render surface. R-8c shows this is a **port** to the JVM, not a deletion.
- `src/seon/agent/message.cljc` (584) — kept; the design depends on it.
- `src/seon/agent/run/core.cljc` (189) — kept verbatim (claim/epoch/lease algebra, on the
  ledger's keep list).
- `src/seon/host/instrument.clj` (242) — untouched by the prototype and carrying the
  unfalsified R-8a lock risk.
- The ~3,650 wire lines web-render retains (R-8b).

The design's "~10,000+" is an overclaim. **~7,000 is the defensible number**, and every one
of the 14 files must pass its falsifier first. Note that the test tree references
`seon.host.*` from at least 16 files; those tests are deleted in the same commit as the
path they pin, per the standing goal, and replaced by tests that assert the surviving
mechanism.

## 6. The honest line count

Built, and measured with `wc -l`:

- Core mechanism: `interrupt.clj` 65 + `ctx.clj` 30 + `eval.clj` 68 + `driver.clj` 195 =
  **358 raw / 322 non-blank / 284 excluding docstrings and comments.**
- Support and demonstrations: `store.clj` 36, `program.clj` 33, `demo.clj` 261,
  `crash.clj` 61, `crashee.clj` 18 = **409**.
- Total: **767**.

The design claimed "roughly 250 lines". The built core is 284 code lines — close, but that
284 is the version with D1-D16 in it. `driver.clj` alone carries a 24-row schema table.

Projected cost of the corrections, itemised so the owner can check the arithmetic: epoch
fence ~3; receipt identity ~2; resume totality ~2; `sci/parse-string` ~1; volatile read per
entry ~2; message identity ~3; plan as one value ~5; lease timer wake ~25; bounded
`.get` with interrupt and wedged-thread accounting ~30; scan coalescing plus interest
~40-60; admission gate for agent-returned facts ~25-40; Malli schemas on the public
surface ~30-50. **Honest projection: 450-550 lines**, against ~7,000 replaced. That is
still a 13-15x reduction and the design should claim that number, not 250 against 10,000.

## Reproducing this

From `/Users/sean/src/seon` (deps must resolve there), with
`S=/private/tmp/claude-501/-Users-sean-src-seon/ad6e7227-ef9f-4cc7-954e-ea6dbabccdff/scratchpad/flow`:

```bash
clojure -Sdeps "{:aliases {:proto {:extra-paths [\"$S/src\"]}}}" -M:writer:host:proto -m flow.demo
rm -rf $S/store-crash
clojure -Sdeps "{:aliases {:proto {:extra-paths [\"$S/src\"]}}}" -M:writer:host:proto -m flow.crash $S/store-crash

```

Saved output: `$S/demo-out.txt`, `$S/crash-out.txt`. Adversarial attack sources and stores
are under `$S/../attack-*` and `$S/../store-a*`. The scratchpad is session-scoped and will
not survive; anything load-bearing must be lifted into the implementation branch before it
is cleaned.
