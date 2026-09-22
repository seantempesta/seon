---
type: landing
status: complete
created: 2026-09-23
tags: [agent-platform, oversight, flow, values-carry-their-world]
---

# Lane: oversight owning-instance search deleted (audit row 10)

Spec: `docs/research/agent-platform/dependency-already-does-it-audit-2026-09-23.md`
row 10 (B2, −24): oversight re-derived `{connection-id, generation}` from a db value
and scanned a late-resolved global registry (`ns-resolve 'seon.cluster
'running-instances`) to find the owning cluster instance. Law: values carry their
world (habit 1, fetching at call time instead of holding the value).

Taken over from the stopped Codex lane `oversight-owning-instance`
(`tmp/orchestrator/oversight-owning-instance-stdout.log`); every hunk in the owned
paths below was that lane's or this one's.

## What changed

- `src/seon/oversight.clj`: `connection-identity`, `running-instances`,
  `owning-instance` deleted. `unit` reads the `:seon.agent/routing` entry its caller
  hands it and the cluster graph from that entry; no routing entry or no graph
  means nil (omission, as before for detached values). `fleet-value` takes
  `db routing graph` and now carries a Malli contract. `flow-status` (the MCP
  `runtime_status` path, which already holds the instance) is unchanged in shape.
- `src/seon/cluster.clj` (`arm-agents!`, one hunk): a `start-graph!` join
  `::routing-graph` puts the cluster graph into the routing entry.
- `src/seon/cluster/agent.clj`: `routing` docstring names the new member.
- `src/seon/render/web.clj`: the root page's oversight call passes
  `:seon.agent/routing` from its request; the page request carries the handle's
  routing.
- `test/seon/oversight_test.clj`: the booted test hands routing and asserts
  the routing graph is `identical?` to the instance graph. The regression
  `the-handed-routing-is-the-only-oversight-owner` asserts the search Var is gone,
  that a detached value and a graph-less routing entry both omit the block, and
  that the unit is built from exactly the handed routing and its graph.

## Correction to the audit row's premise

The audit row said "the caller of an oversight check already holds the handle."
That holds for the GET path (`serve!` holds the instance) but not for the render
proc: it derives the SSE root page and holds `view + handle + routing`, never the
graph that contains it. core.async.flow gives a proc no handle on its own graph
(`reference-code/core.async` at `dc35f3e0d`,
`src/main/clojure/clojure/core/async/flow/impl.clj:155` passes only pid/args/
channels; `:166` "the only connection to a running flow is via channels"). The
predecessor's diff passed `:seon.boot/instance` via the web service handle only,
so the SSE path would have silently lost the fleet block (the deleted search had
supplied it; the live default JVM probe below confirms the search was what found
it). The fix hands the graph into the routing entry the render proc already
holds. `seon.flow/start-graph!` runs joins after `flow/start` and before
`flow/resume` (`src/seon/flow.clj:85-93`), so every proc pass sees the graph;
this follows the precedent of `:seon.agent/fault-channel` joining the same
entry once the fan-out stands. The routing atom's content is undeclared
(`seon.agent.edn:127-132`), so no schema resource changed and no from-zero boot is
required.

## Evidence

Live `default` (pid 51528, old code, read-only JVM probe) — the search is what
owned the instance before this change:

```clojure
(let [v (ns-resolve 'seon.cluster 'running-instances) inst (get @@v "default")
      owner (#'seon.oversight/owning-instance
             (seon.db/db (:seon.boot/cluster-connection inst)))]
  {:var v :owner-found (some? owner) :same? (identical? owner inst)})
;; => {:var #'seon.operator.runtime/running-instances, :owner-found true, :same? true}
;; 3 ms
```

Proof probe of the new mechanism, disposable file
`tmp/oversight-owning-instance/probe.clj`, run as
`clojure -M:test tmp/oversight-owning-instance/probe.clj` over the working tree
(20.9 s wall, JVM start included): a real `seon.flow/start-graph!` with one proc
and the `::routing-graph` join, then `oversight/unit` with only the routing:

```clojure
{:search-var nil,
 :graph-in-routing-at-join true,
 :value #:seon.oversight{:agents [],
                         :plumbing [#:seon.oversight{:proc :probe/p, :ping :reply,
                                                     :passes 0,
                                                     :buffers [#:seon.oversight{:count 0, :capacity 10, :port :in}]}]},
 :detached nil,
 :ms 12.97}
```

(`ping-timeout-ms` was redefined to 500 in the probe because it reads cluster
config from a real db value; everything else is the installed code.)

## Focused tests — blocked by a foreign stale base

`bin/test-fast --paths src/seon/oversight.clj src/seon/render/web.clj
src/seon/cluster.clj test/seon/oversight_test.clj -- seon.oversight-test`, runs
`6af40bd8eacf` and `1909e03da02a`: 5 executed, 3 green, 2 errors, both fixture setup:
`the-handed-routing-is-the-only-oversight-owner` refused by canonical fixture base
construction (`:my.note/note` must declare its partition), and
`a-booted-cluster-tells-its-live-fleet-story` refused at `boot.clj:263` (Store
identity mismatch). Baseline run `34fd8e2534c2` (HEAD plus only the old
`oversight.clj`, HEAD's own test file) shows the identical two errors, so both are
the stale published base (`target/test-published-bases` predates `8a069b5e4`'s
partition facts; also recorded in `lane-fault-no-history-2026-09-23.md` and
`lane-projection-writer-producer-2026-09-23.md`). The orchestrator's
`bin/test --prepare-head-base` is needed before these two can run; the test bodies
are unverified by a test run until then. Filed:
`docs/seon/issues/test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`.
RESET NEEDED: no.

## Timings (every operation over 1 s)

| operation | wall | phases |
| --- | --- | --- |
| `bin/test-fast` focused run `6af40bd8eacf` | 49.8 s | snapshot 4 s; JVM + load until projection about 35 s; contracts armed 4.1 s; five tests 2.4 s |
| `bin/test-fast` runs `1909e03da02a`, `34fd8e2534c2` | about 50 s each | snapshot 4-5 s; tests 2.4 s |
| probe JVM `clojure -M:test tmp/oversight-owning-instance/probe.clj` | 20.9 s | probe body 13 ms; everything else is JVM start and namespace load |
| HEAD `da2086452` load check: git-archive snapshot, `clojure -M -e "(require 'seon.oversight 'seon.render.web 'seon.cluster 'seon.cluster.agent)"` | 19.2 s | load only; printed `:loaded nil` (the search Var does not resolve) |

Everything over 10 s here is loading before work starts. Filed as
`docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`.
Live read-only probe on `default`: 3 ms.

## Commits

- `da2086452` — the change, tests, this note, two issue notes.

## Verification boundary

- Proved: the search is gone; the join places the graph before resume; `unit`
  builds the fleet value from the handed routing and omits it otherwise;
  `seon.cluster`, `seon.render.web`, `seon.oversight` load (probe JVM, working tree)
  and HEAD `da2086452` loads (git-archive snapshot, fresh JVM).
- Not proved: the booted fleet test and the HTTP/SSE paint on a real cluster
  (blocked by the stale base above); browser paint was not observed.
