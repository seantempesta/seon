---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [issue, boot, agent, platform]
---

# A warm restart hangs in `seon.cluster.agent/arm!` waiting on an atom monitor

**Evidence (2026-09-22, lane reload-per-declaration).** `bin/seon --root
tmp/reload-b-root start head` from a `git archive` of `c1d2e6d7f` returned after
301,957 ms with the client failing (`:seon.error/message nil`); the JVM (pid 44611)
was alive with no readiness. `jstack` (`tmp/reload-per-declaration-evidence/restart-d-threads.txt`):
`main` BLOCKED "waiting to lock <0x000000701018b330> (a clojure.lang.Atom)" at
`seon.cluster.agent$arm_BANG_ (agent.clj:876)` ← `armer_step (agent.clj:1172)` ←
`seon.cluster/arm-agents! (cluster.clj:3093)` ← `seon.cluster.boot/stand-cluster-runtime!
(boot.clj:93)`. No platform thread in the dump holds that monitor, so the holder is
probably a virtual thread (`jstack` omits them). The same root restarted in 10,298 ms at
`2bd568c08`; `cluster/agent.clj` changed since in `7f6718507`, `da2086452`, `678009fcd`.
The store had one interrupted adoption before this start (the prior JVM was stopped by
`down` while an adoption request had exceeded the 300 s prepl bound).

**Not yet known.** Which thread holds the monitor (`jcmd <pid> Thread.dump_to_file -format=json`
includes virtual threads), and whether a clean root reproduces it.

## Cause (lane resume-in-seconds, 2026-09-23)

From `restart-d-threads.txt` and the source at `c1d2e6d7f`:

- `main` waits on the `routing` atom's monitor. Only two sites take it:
  `arm!` (`agent.clj:876`) and `disarm!`. Nothing stops agents at boot, so the
  holder is the cluster graph's armer proc running `arm!` for the same root
  agent on a virtual thread (`jstack` omits virtual threads). `main` is making
  its own prime call (`cluster.clj:3093` → `armer_step`).
- `arm!` keeps the monitor across the whole of `acquire-context!`. That call
  runs the SCI `acquire!` (`sci/eval.clj`). `acquire!` loads every `:core`
  namespace, then `record-acquisition-refusals!` records EACH refusal as its
  own `commit-fault!` transaction, one after another (`mapv`).
- The only busy thread in the dump is the Datahike writer (`async-mixed-3`,
  80.7 s CPU). It is inside `seon.error/commit-call` (`error.clj:1415`) →
  `db/carried-projection` → `schema/load-projection` → `projection-fingerprint`.
  A `:db.fn/call` receives the in-transaction value, and that value has no
  committed identity (`datahike/db.cljc:385-411`). `carried-projection`
  therefore re-derives the whole projection from rows on EVERY call, on the
  one writer thread. Measured on a live scratch store (`tmp/ris-root`, 454k
  datoms): 164 ms per in-transaction derivation under load 12. A
  committed-value read is 0 ms (memo hit).
- The work is proportional to (acquisition refusals × whole projection), all
  serialized on the writer and all under the routing monitor. The store had just
  had an interrupted adoption, which is the condition that produces acquisition
  refusals. The refusal count in that JVM is not recoverable, because the root
  is deleted.

So the monitor is the symptom, not the cause. Holding it across the arm is
correct serialization per its docstring: a second caller needs the same armed
entry either way. The fix belongs to the two owners of the work:

1. `src/seon/db.clj` `carried-projection` (lane projection-writer-producer):
   stop re-deriving for a speculative value whose declaration datoms are its
   committed origin's. Alternatively, `seon.error/commit-call` could stop
   reading the projection at the writer, since it only selects diagnostic
   attributes to retract.
2. `src/seon/sci/eval.clj` `record-acquisition-refusals!` (lane
   realities-commit-4): record all refusals in ONE transaction. The branch
   without `commit-fault!` already builds a single `tx-data` with `mapcat`.

Wanted regression (sci/eval owner): an acquisition with N row refusals
commits one transaction, and a warm restart of such a store reaches readiness
within `operator-boot-bound-ms`.
