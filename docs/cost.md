# Cost proofs

**Cost is work done (ticks, bytes, calls), never wall or CPU time** (owner, 2026-10-23: "I like ticks vs wall clock or anything that is load dependent. Work done, not scheduled time slices").

A cost proof (`seon.cost/proof`) states what a function's work should cost as its input grows, measures it at a few sizes, and is red when a size costs past its estimate or grows steeper than the work needs, in a dimension counted exactly (```seon.cost/exact```: ticks, bytes, calls). An estimate that states no exact dimension also measured is red (`NO COUNTED DIMENSION`). Time is measured and shown, never red: a green is reused forever at its reach digest ([merging](merging.md#a-merge-runs-what-the-change-reaches)), so a verdict must be a function of the code and its input alone.

## What things cost here

The constants a lower bound is worked from (AGENTS.md §3a), measured on 2026-11-12 on the build machine. Each is a fact to estimate with, or a target where today's number is a bug.
- A fork of a running agent costs about 65 ms; one in-process test in a warm fork is about 21 ms, and the median test is about 10 ms of work.
- A transaction of about 10 datoms should cost about 50k ticks and 60 KB; an AVET seek costs microseconds; a tick is about 3 to 10 ns.
- Reading source should allocate at most 20 bytes a character, against 56 today.
- A system's first agent should be ready in under 2 s, against 16 s today; a system boot is 10.7 s today, which is a cost to remove, not a constant to plan with.
- The whole suite should run in under 60 s of wall time, and a typical change's minimal set in seconds.

## What is measured

A proof measures the function under test on the thread that calls it, and nothing else in the process:
- **ticks**, the counted dimension for time: the engine-timer ticks the call ran (`jolt.host/ticks-of`, Jolt `jolt/thread-ticks`; ```seon.cost/once```). Load does not move them. Jolt arms the timer in one place, `jolt-set-timer!`, which adds what the last arm used to the thread's count and reads it whole at 0 bytes. Not counted: work with interrupts disabled, and work while nothing is armed (`ticks-of` arms one for its extent). A collection or signal raised from another thread runs its handler on this thread, so ```seon.cost/tick-allowance``` (64) is taken off per collection during the call. **Blind spot:** work in C (a foreign call, a bulk copy) costs next to no ticks and waiting costs none, so bytes stay beside ticks;
- **bytes**, what that thread allocated (`seon.profile/alloc-bytes`, Chez's per-thread count on our branch ``jolt/thread-bytes``). Not counted: other threads' allocation, a collection's copying, the collector's and runtime's own conses, what a collection or collect request's handler allocates (``jolt/collect-uncounted``, ``jolt/a-collection-request-is-the-collectors``), a new Scheme stack and the continuation a stack cut makes (``jolt/a-stack-cut-is-not-the-threads-code``), since those depend on what ran before (`seon.profile-test/a-collection-in-a-measured-call-is-not-its-bytes`, `seon.profile-test/a-new-stack-is-not-the-calls-bytes`). Chez's `(statistics)` and `bytes-allocated` count the whole process;
- **calls**, the contracted functions it called, counted exactly ([counting calls](#counting-calls));
- **time**, the thread's CPU time (`seon.profile/cpu-ns`): another thread's work and a wait are not the call's (`seon.profile-test/a-measure-is-the-same-beside-another-thread`), but a collection on the thread and the core's speed under load are, so time over its estimate is shown (`TIME OVER (measured, not red)`), never red.

Each size is one call (```seon.cost/measure```), less what measuring an empty call costs. Only a size whose one call is over its estimate is measured again (```seon.cost/run```): calls until `:seon.config.proofs/cost-settled` calls after the one that allocated the fewest bytes allocate no fewer, at most `:seon.config.proofs/cost-max-samples`, ticks and calls their least; the row keeps `:calls`, and one that does not settle is marked `:unsettled`. When every size passes but the curve does not, the sizes measured by one call are measured again this way and the curve judged on those (`seon.cost-test/a-curve-is-judged-on-warm-sizes`). Proofs: `seon.profile-test/a-threads-allocation-is-its-own`, `seon.cost-test/a-measure-takes-a-count-of-samples-never-a-time`.

## Counting calls

```seon.profile/counted``` wraps each armed function once more, counting every call exactly and timing one in 64. Counts are kept per epoch: ```seon.profile/begin!``` opens one and zeroes every count, ```seon.profile/calls``` is its total, ```seon.profile/touched``` the functions called since with exact `:calls` and estimated `:ms`, ```seon.profile/top``` the k costliest. An agent is one thread, so its counts are exact; root's procs run on several, so root's are approximate and never proof. `seon.profile/reads` records what ran since `reads-begin!`, which no nested epoch resets; a test's verdict holds both (`seon.profile/with-reads`).

**Every process arms the same functions:** root arms `seon.contract/boot-nss` at boot and each test shard before its tests (`test_runner/shard!`), so a proof counts the same calls in `bin/test`, an agent's test child and the binary (`seon.contract-test/a-test-process-arms-what-root-arms-at-boot`). **A private function is counted where its contract is checked**, in a test process ([checks](checks.md#contracts-armed-at-definition)). **Work set aside is no call's:** a signal handler's work (```seon.profile/aside```) is not counted as calls, and its ticks and bytes go to the call it interrupted as a callee's.

## What an estimate allows and how a verdict reads

An estimate states its work's growth and its price per unit: ```seon.cost/work``` is W(n) (constant, log, linear, n log n, quadratic, cubic), ```seon.cost/per-unit``` prices one unit from the unit table's `:ops` (overridden by `:per-n`), and ```seon.cost/expected``` is fixed + per-unit x W(n) in each stated dimension (```seon.cost/dimensions```). A dimension is over above ```seon.cost/slack``` times its estimate (3x ticks and time, 1.25x bytes, 1x calls) and loose at or under ```seon.cost/loose``` (a third).

A proof runs the sizes ```seon.cost/sizes``` keeps: its own sizes up to `:seon.config.proofs/cost-max-n`, ascending, those past it named on the verdict. A proof the cap leaves under ```seon.cost/min-points``` (3) sizes is refused (`:seon.cost/too-few-sizes`) and measures nothing. ```seon.cost/proof``` asserts inside a deftest: one report whose message is the explanation and whose `:actual` is the measurements and verdict as data. ```seon.cost/verdict-lines``` writes the one wording, red first; ```seon.cost/explain``` joins estimate, measurements and lines into what an agent reads.

## How growth is judged

A measure of ticks may read one 1,000-tick timer chunk more than another of the same call (`seon.cost-test/one-timer-chunk-is-not-a-red-and-a-real-regression-is`), so ```seon.cost/judge``` takes `:seon.config.proofs/tick-quantum` (1,050) off each size before judging it over, and against the growth before fitting.

A dimension is steeper when its fitted exponent (```seon.cost/exponent```, the least-squares slope of log cost over log size) passes the work's by more than ```seon.cost/steeper-by``` (0.3). The fit is chosen so a change making every size cheaper or dearer by one amount never moves the verdict (`seon.cost-test/a-speedup-never-reads-as-steeper`, `seon.cost-test/growth-takes-the-basis-the-rows-allow`):
- on each size's increment over the smallest, against W(n) - W(n0), when the largest increment is at least ```seon.cost/growth-share``` of the smallest size's cost, using only increments over ```seon.cost/increment-floor```. **Not for log work:** a stored index's log cost is a staircase with no power-law shape in its increments, so log work takes the cost less its fixed part;
- when fewer than two increments are positive, on the cost less the estimate's `:fixed`, never more than the smallest size leaves beyond its per-unit part;
- for constant work that did not grow (the largest within `increment-floor` of the smallest, `seon.cost-test/a-flat-costs-variance-is-not-growth`, or within the fixed part, `seon.cost-test/a-largest-size-within-the-fixed-part-did-not-grow`), on the cost itself; only the largest size's estimate judges a small creep.

```seon.cost/growth``` answers each dimension's exponent, the one allowed and the basis taken.

## An accepted overrun and its ratchet

A proof past its estimate stays green while the task its `:overrun` names is open and every dimension at the largest size is within ```seon.cost/ratchet``` (1.5x) of the `:measured` that task recorded, so a regression is red even inside an open overrun. The waiver `{:task "ns/name" :n SIZE :measured {...}}` is judged whole (```seon.cost/waiver-fault```): `BROKEN WAIVER` when the task is in no task file (`:no-task`, `seon.task/file-declarations`), `:n` is missing (`:no-size`), or `:n` is not the largest size measured (`:wrong-size`). A record is taken by running the proof with a dummy `:overrun` and reading its largest row, and is retaken whenever the measure changes. A new `:overrun` needs the owner's yes. Waivers are listed by health ([`:waived-proof`](health.md#a-proof-that-passes-by-waiver)) and counted by `bin/prove`.

## How we measure

Every measurement, in a cost proof or outside one, starts from a written estimate and is read across sizes (owner: "think about things algorithmically first and always put estimates on things"):
- **Estimate first.** Before the run, write what the work is proportional to (pages mapped, live bytes, datoms touched, forms compiled, bytes copied), the expected cost from that and the unit costs (`seon.cost/units`, never a previous run), its O(·), and what would show the model wrong.
- **Across sizes.** Three or more sizes spanning 100x or more; a single size is never a result. Report the least of repeats, and the median and p95 when the spread matters; time anything under a microsecond in batches.
- **Measured over estimated** past a dimension's slack, or loose under it, is a finding to explain before the number is used: an over in an exact dimension is red, an under a finding.
- **A process's memory is what the platform charges it:** `seon.lib/real-mb` over `jolt.host/process-footprint-bytes` (macOS `phys_footprint`; on Linux the proportional set size). A forked child is charged every inherited page it touches, read or written, though only a write copies (`seon.lib-test/the-memory-gauge-reads-what-macos-charges-across-a-fork`), so a sum of footprints overstates RAM. `bin/check scale` therefore charges each agent the machine's drop in free plus inactive pages (`seon.lib/sysmem`, `host_statistics64` in `native/probe.c`) times `:seon.config.agent/max-running`, plus root's and the fork server's footprints, against `:seon.config.machine/memory-mb` (`:footprint-need-mb` shows the footprint sum beside it). The machine-wide drop is read over a whole run of many agents and judged in GB, never to the MB ([machine](machine.md#the-target-a-16-gb-macbook-m1)).
- **Record the load** beside a timed run; final timings on a quiet machine.
- **Whoever reads first pays the cold read:** the first reader of an on-disk index decodes its nodes, so measure warm or in both orders before blaming a function.
- **A unit's cost is the difference of two sizes, by function:** measure at two sizes with `seon.profile/measure!` and subtract `seon.profile/touched` by var.
- **The contracts' share is a run with them off:** `SEON_INSTRUMENT=off` (`:seon.config.contract/instrument`).

**A proof's input is the shape production holds** ([testing](testing.md#testing-in-the-world)). The in-memory test store keeps no index node at an address, so `datahike.api/datoms-diff` cannot skip shared subtrees there; a function whose cost rests on that skip is measured on a store of its own on disk, saying why (```seon.reconcile-test/observed-input```, ```seon.program-cost-test/on-disk-diverged-of```, ```seon.merge-cost-test/three-of```).

A cost proof that names its function only as a quoted symbol keeps a stale result, since a quoted name is no reference ([source](source.md#tests-run-by-reach)).

**A measure is taken in the multi-threaded regime.** Jolt's lazy seq cells take a claim path from the first second thread or fiber on, for the rest of the process ([forks](forks.md#jolt)); every root, agent and test child runs fibers, so ``seon.cost/measure`` starts one thread before its first call.

**A proof leaves the process as it found it:** a test that arms a namespace (`contract/instrument!`) reloads it unarmed after, or every later proof in the shard counts its checks.

## A bound in the contract

A contract may carry its cost bound beside its schema: `:seon.cost/bound`, a function of the same arguments answering what one call may cost (`{:ticks :bytes :calls}`, any of them) and `:out`, the measure of its answer a caller's bound passes on (```seon.cost/bound```). The bound is the algorithm sketched: each step at its own cost, measured once on a case that isolates it; priced from the unit table instead, ```seon.ctx/changes```' bound was up to 1,000x under.

A generated estimate (`:seon.cost/generated`: `:of`, `:n`, `:work`, `:cases`, `:gen`) is a proof with no sizes: ```seon.cost/cases``` draws argument vectors from `:gen` or the contract's input at the test's seed (```seon.proofs/test-seed```), ```seon.cost/run``` measures each against its bound, and ```seon.cost/judge``` judges each as a size; a case over is shown with its arguments. Proofs: `seon.cost-test/a-bound-holds-on-its-contracts-generated-inputs`, `seon.cost-test/a-scan-inside-the-loop-is-red-under-the-bound`, `seon.ctx-test/changes-holds-its-bound-on-generated-records`.

A bound composes: a caller's is the sum of its callees' over the measures they pass in `:out`. A cache is an input (bound the cold call, or take the cache's state), and a bound sees only the measures its generator varies. A function taking a db has no generated input yet (task `seon.cost/a-db-function-draws-its-cases-from-a-domain-generator`).

## What a contract costs

A public function's contract is checked on every call, a private one's in a test process ([checks](checks.md#contracts-armed-at-definition)), so a schema over a large value costs its size on every call. Contracts stay whole ([the ruling](decisions.md#a-contract-checks-a-collection-whole)): a large value passed among helpers is checked shallowly, and each entry once where it enters (`:seon.reconcile/index`, `:seon.ctx/ix`; `seon.reconcile-test/an-index-is-listed-at-the-cost-of-its-tasks`). A collection of `:any` still walks its elements. A value checked against a registered name asks the schema the registry holds (```seon.contract/held```), never `(m/schema k)` anew.

## What a test run measures

Every test run measures each contracted function from the counters every call passes through: ```seon.profile/counted``` counts each call and its engine ticks and thread bytes, in all and its own, with no clock read and nothing allocated. Each test opens its own epoch (```seon.proofs/run-tests!```, through ```seon.profile/measure!```), and its ```seon.profile/touched``` goes on its result as `:fns` and into its cached verdict ([merging](merging.md#verdicts-are-a-cache)). Each test's ended value carries `:heap`, the live heap its child holds after a collection (```seon.agent/take-value```), recorded, never judged. Counting adds about 4 ticks a call (`seon.profile-test/counting-a-call-allocates-nothing-and-costs-a-few-ticks`, `seon.profile-test/a-functions-own-work-is-its-work-less-its-callees`). Self is approximate where a call throws or parks on a fiber. These measures are the data for a function's cost bound.

## A diff per attribute costs a descent each

`d/datoms-diff` of one attribute's range skips shared subtrees but still descends from both roots and opens the unshared leaf, a fixed cost per call whatever the attribute holds. So a caller that already read the `:eavt` diff hands it over: `seon.reconcile/index` takes its watched datoms from `seon.render.proc/advance-main`'s diff (`seon.render-cost-test/a-move-of-main-costs-what-moved`); `seon.merge/content-datoms` filters it to the content attributes in one pass (`seon.program-cost-test/content-datoms-given-a-diff-are-a-filter`); `seon.problems/caused-split` looks each touched section, name, form and task up by entity (`seon.problems-test/caused-follows-what-changed`), reading its own 12 attributes' diff (`seon.problems/caused-datoms`) when not given one. A name that appeared or vanished finds the sections citing it by the `:seon.doc/names` AVET index.

## Where it is used

An agent's task names its cost proofs as goals ([agent](agent.md)), and `bin/test` runs them with every other test. A proof's `:ops` are priced from the unit table `seon.cost/units`, which `seon.cost-test/the-unit-table-is-current` measures again and holds to 1.25x, each row in a fresh forked process (`seon.check/units`; `seon.cost-test/a-unit-measures-the-same-across-runs`).

**An evaluation records its cost once, when it ran.** `seon.repl/wrap-cost` puts the form's `:ticks`, `:ms`, `:alloc-bytes` and costliest functions beside the answer, from the one measurement ```seon.profile/measure!``` (the only reader of the tick counter in src: `seon.main-test/only-measure-reads-the-tick-counter`); `record-tx` stores the same values, so the model reads the same bytes in every later turn (`seon.repl-test/an-evals-response-carries-its-ticks`).
