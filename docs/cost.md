# Cost proofs

A cost proof (`seon.cost/proof`) states what a function's work should cost as its input grows, measures it at a few sizes, and is red when a size costs past its estimate or grows steeper than the work needs.

## What is measured

A proof measures the function under test, on the thread that calls it, and nothing else in the process (task `seon.cost/a-proof-measures-only-its-own-thread`):
- **time** is that thread's CPU time, `seon.profile/cpu-ns` (CLOCK_THREAD_CPUTIME_ID). Another thread's work and a pause the scheduler gave to someone else are not the call's. A call that waits (on a channel, a socket, a lock, a sleep) is measured by what it does, not by how long it waits: the waiting is its dependency's cost, proved on that dependency (`seon.profile-test/a-measures-time-is-what-its-thread-does`: a call sleeping 20 ms reads under 2 ms). What the thread's time still holds: a collection that runs on it (the collector runs on the thread that triggers it), and how fast its core runs. Under load the scheduler moves it between the M1's performance and efficiency cores and the clock speed changes, so the same work costs more CPU time: 3.9 ms alone and 5.2 ms (1.34x) beside 16 busy threads, its wall time hardly more. The estimate's allowance for time (3x) absorbs that; bytes and calls are exact;
- **bytes** are what that thread allocated, `seon.profile/alloc-bytes`: Chez's per-thread count on our branch ``jolt/thread-bytes`` (its allocation areas less the tails it dropped, and its large objects). Another thread's allocation, a collection's copying of live data, and the conses the collector and the runtime's own books make on this thread (`during_alloc` raised: locked objects, guardians) are not counted. Chez's own `(statistics)` and `bytes-allocated` count the whole process, so a proof under them reads whatever else ran beside it;
- **calls** are the contracted functions it called, counted exactly (`seon.profile`).

Each size is the least of several repeats (`seon.cost/measure`), less what measuring an empty call costs. The proofs: `seon.profile-test/a-threads-allocation-is-its-own`, `seon.profile-test/a-measure-is-the-same-beside-another-thread`.

## How we measure

Every measurement, in a cost proof or outside one, starts from a written estimate and is read across sizes, never from one quick run (owner: "think about things algorithmically first and always put estimates on things"):
- **Estimate first.** Before the run, write what the work is proportional to (pages mapped, live bytes, datoms touched, forms compiled, bytes copied), the expected cost derived from that and the unit costs (`seon.cost/units`, never a previous run), its O(·), and what would show the model wrong.
- **Across sizes.** Measure at three or more sizes spanning 100x or more (a proof's default is 1,000, 10,000 and 100,000) and compare the fitted growth with the estimate's; a single size is never a result. Over repeats, report the least (a proof's choice), and the median and p95 when the spread matters; time anything under a microsecond in batches.
- **Measured over estimated.** Above 3x, or at most a third, is a finding to explain (a wrong model, or the wrong work done) before the number is used: `seon.cost/judge`'s over and under.
- **A forked process's memory is the pages it owns**, `seon.lib/real-mb`: its shared-now-private and private resident pages, summed from `seon.lib/mp-probe`'s Mach VM regions. macOS `footprint` counts pages a forked child only reads, so it overstates a child's memory; `bin/check scale` and an agent's view read `real-mb` ([machine](machine.md#the-target-a-16-gb-macbook-m1)).
- **Record the load** beside a timed run, and take final numbers on a quiet machine: time moves with the machine's load (above), bytes and calls do not.

## Where it is used

An agent's task names its cost proofs as goals ([agent](agent.md)); `bin/test` runs them with every other test; the time a proof's estimate is priced in is the unit table `seon.cost/units`. Clocks that order events are `seon.profile/now-ns` ([machine](machine.md#the-clock)); `cpu-ns` orders nothing ([clocks](clocks.md)).
