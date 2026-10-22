# Cost proofs

A cost proof (`seon.cost/proof`) states what a function's work should cost as its input grows, measures it at a few sizes, and is red when a size costs past its estimate or grows steeper than the work needs.

## What is measured

A proof measures the function under test, on the thread that calls it, and nothing else in the process (task ``seon.cost/a-proof-measures-only-its-own-thread``):
- **time** is that thread's CPU time, `seon.profile/cpu-ns` (CLOCK_THREAD_CPUTIME_ID). Another thread's work, a pause the scheduler gave to someone else, and the machine's load are not the call's. A call that waits (on a channel, a socket, a lock, a sleep) is measured by what it does, not by how long it waits: the waiting is its dependency's cost, proved on that dependency;
- **bytes** are what that thread allocated, `seon.profile/alloc-bytes`: Chez's per-thread count on our branch `jolt/thread-bytes` (its allocation areas less the tails it dropped, and its large objects). Another thread's allocation and a collection's copying of live data are not counted. Chez's own `(statistics)` and `bytes-allocated` count the whole process: under them a proof read whatever else ran during it (1.4 GB in 200 ms while one other thread allocated), and a proof went red in one full `bin/test --reach` and green everywhere else;
- **calls** are the contracted functions it called, counted exactly (`seon.profile`).

Each size is the least of several repeats (`seon.cost/measure`), less what measuring an empty call costs. The proofs: `seon.profile-test/a-threads-allocation-is-its-own`, `seon.profile-test/a-measure-is-the-same-beside-another-thread`.

## Where it is used

An agent's task names its cost proofs as goals ([agent](agent.md)); `bin/test` runs them with every other test; the time a proof's estimate is priced in is the unit table `seon.cost/units`. Clocks that order events are `seon.profile/now-ns` ([machine](machine.md#the-clock)); `cpu-ns` orders nothing ([clocks](clocks.md)).
