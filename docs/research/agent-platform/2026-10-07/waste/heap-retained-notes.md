# heap-retained lane notes (2026-10-07)

Task: seon.turn/each-trivial-evaluation-retains-two-hundred-kilobytes.

## Cause (measured on default, pid 30874)

The holder is `seon.db/*read-evidence-sink*`: an atom of read-evidence entries, each
holding the `:seon.db/db` it read and the read result. It was bound in the probe's
JVM-mode evaluation (the waste-rank measurement and this lane both drove the agent
through `seon.mcp/call` inside a `jvm` eval) when the agent's branch connection
was opened and its graph armed. Datahike's writer go-loops
(`reference-code/datahike/src/datahike/writer.cljc:121,230`, `go-try`) capture the
opening thread's binding frame for the connection's lifetime, so every
transaction function the writer runs for that branch (`seon.turn/system-run-call`
-> `open-call`, pulls and queries through `seon.db`) appended to that one atom
forever: +26 entries per `(+ 1 2)` run, 3,241 after ~125 runs, 6,451 on the
waste-rank probe's branch. Flow's mult/tap go-loops (`flow/impl.clj:100,139`)
captured the same frame (holding the atom and the dead batch's `seon.batch/*world*`
and `seon.db/*conn*`, the ROOT connection).

Path found by a reflective reachability walk from all threads' ThreadLocals and
Var roots: writer `:transaction-queue` take handler -> go state -> `Var$Frame` ->
`*read-evidence-sink*` TBox -> atom -> vector -> entry -> `datahike.db.DB`.
Graphs and branches armed through a real MCP connection (no enclosing batch) have
empty seon frames; only the two armed from inside an evaluation leak.

Numbers (class histograms around runs, other lanes active):
- branch opened inside an evaluation: datahike.db.DB +321 instances / 80 runs,
  sink +26 entries/run, live heap +20 MB / 80 runs (250 KB/run, matches the
  finding's 200-229 KB);
- branch opened from a root frame (same code path, first call wrapped in a fresh
  virtual thread): datahike.db.DB -1 / 80 runs, sink absent.
- Idle drift from other lanes was +136 MB / 20 s, so Datom/total deltas are noise;
  the DB instance count and sink length are the clean signals.

Not legitimate retention: nothing reads the captured sink (its evaluation ended);
the writer also ran transaction functions under a dead evaluation's world and
the root connection as `*conn*`, which `seon.db` and supply would read.

## Fix (smallest correct)

One function `seon.flow/call-with-root-bindings`: run a thunk on a fresh virtual
thread (whose binding frame is Clojure's root: `Var.dvals` is a plain ThreadLocal)
and return its value or rethrow its cause. Long-lived machinery that conveys the
caller's frame is started through it:
- `seon.cluster.store/open-branch!` and `open-overlay!` around `d/connect`;
- `seon.flow/start-graph!` around create/start/joins/resume.

Cost: one virtual thread start + join per connection open or graph start
(microseconds); per run nothing. Retention after: no evaluation frame reaches a
writer or a graph.

Not doing: `open-store!` at boot (its writer conveys only the boot projection
state; changing boot needs a restart proof, outside this slice); no cache, no
clipping of evidence, no change to the read-evidence design; not touching Datahike
(the fork could stop conveying, but Seon owns where it opens connections).
The "~600 Datoms per run" share that is real facts cannot be separated from
other lanes' concurrent writes on default now (see drift); re-measure after
root's restart on a quiet JVM.
