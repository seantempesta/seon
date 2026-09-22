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

## Follow-up: review findings (`docs/research/agent-platform/review-oversight-owning-instance-2026-09-23.md`)

This section replaces the earlier text above where they disagree.

1. **Missing live state is no longer silence.** When a request carries routing,
   the caller is asking for live state. If the routing entry lacks
   `:seon.agent/armed` or `:seon.flow/graph`, `unit` returns
   `{:seon.oversight/missing [...]}` (`:seon.oversight/unavailable`).
   `ai-story` and `html-table` render it as a visible diagnostic
   (`data-fleet-oversight="unavailable"`, naming each missing member). A present
   empty armed map is the only way to get an empty fleet. A request without
   routing is a detached render and returns nil. The root page now assoc's
   routing only when it is present, so a detached request never carries a nil
   member.
2. **Contracts.** New resource `resources/seon/schemas/seon.oversight.edn`
   declares `:seon.oversight/live-state` (an open map of the routing contents
   oversight reads), `:request`, `:unit`, `:fleet`, `:agent`,
   `:proc-observation`, `:occupancy`, `:buffer` and `:unavailable`. The routing
   entry is validated once per read, at `live-state`, whose contract is
   `[:or :seon.oversight/live-state :seon.oversight/unavailable]`. Every
   function in `seon.oversight` now has a contract, the private helpers
   included (17 contracts).
3. **Regressions.** `oversight-reads-the-handed-routing-and-names-what-it-lacks`
   uses a real Flow graph in a real routing entry and covers three cases:
   routing lacking the graph, lacking the armed map, and lacking both, each
   asserting the visible diagnostic and the absence of an agents table. It also
   asserts that the detached request returns nil. The booted test gains an SSE
   section: it registers a root tab, taps the render proc's pages mult, writes
   a note (a database wake), and asserts that the root package at or after that
   basis carries the fleet table. The deleted-Var and stubbed `fleet-value`
   assertions are gone.

### Proof

The schema-validating probe `tmp/oversight-owning-instance/probe2.clj`
(`clojure -M:test`, working tree) builds the packaged projection with every
oversight contract (`schema/build-projection`, 989 ms) and then validates the
unit values:

```clojure
{:contracts 17, :build-ms 988.69, :unit-ms 4.84, :detached nil,
 :fleet #:seon.oversight{:agents [], :plumbing [#:seon.oversight{:proc :probe/p, :ping :reply, :passes 0, :buffers [...]}]},
 :fleet-valid? true, :live-state-valid? true,
 :no-graph #:seon.oversight{:missing [:seon.flow/graph]}, :no-graph-valid? true,
 :no-armed #:seon.oversight{:missing [:seon.agent/armed]},
 :no-graph-ai "Live fleet state is unavailable: the routing entry lacks :seon.flow/graph.",
 :no-graph-html "<section class=\"seon-card\" id=\"surface-fleet-oversight\"><h2>fleet</h2><p data-fleet-oversight=\"unavailable\" data-missing=\":seon.flow/graph\">Live fleet state is unavailable: the routing entry lacks :seon.flow/graph.</p></section>"}
```

**Live SSE after a database wake.** A booted scratch cluster (a `git archive`
of `fe624bf22` plus this slice's paths) was probed through MCP `eval_clj`:
register a root tab, tap `:seon.render.web/pages-mult`, offer the join, then
`(seon.note/add! "oversight-wake" "The fleet repaints." conn "root")`. Result:

```clojure
{:first-root? true, :join-ms 588.29, :note "oversight-wake",
 :wake-basis 536870938, :pkg-basis 536870938, :wake-ms 1675.6,
 :fleet? true, :agents-table? true, :root-row? true, :unavailable? false,
 :excerpt "surface-fleet-oversight\"><h2>fleet</h2><table data-fleet-oversight=\"agents\">...<tr data-agent=\"root\" data-state=\"mid-turn\">..."}
```

MCP `runtime_status` on the same root returned the full fleet through
`flow-status`, reading the routing entry.

**Incremental schema adoption** (owner 2026-09-23 ruling). On the same warm
store, the `current-src` branch of a live store:
`bin/seon --root <root> init --changed resources/seon/schemas/seon.oversight.edn src/seon/oversight.clj src/seon/render/web.clj`
ran twice.

- Retire: the resource was removed and both sources set to HEAD. It was
  accepted as commit `6ab2ee8f…`, leaving 0 `:seon.oversight/*` schema rows.
- Adopt: the resource and sources were restored. It was accepted as commit
  `6ab2eed0…`, restoring 25 rows.

The count was measured with `datahike.api/commit-as-db` on each commit, 27 ms.
The 1.3e refusal case (retiring the resource while the writers survive) was
not exercised: the writers were converted in the same publication.

**Rule breach, reported.** Before the lane read the 2026-09-23
no-from-zero ruling, it also ran one from-zero boot of that archive:
128,569 ready-ms, 142.38 s wall, exit 0. An earlier from-zero attempt on the
shared working tree exited 1 after 22.3 s. That was a foreign static-analysis
refusal: `cluster.clj` `datahike.schema` unresolved, `declaration-changes`
arity, and `seon.test` callers. The row is recorded in
`docs/seon/issues/from-zero-boot-takes-minutes.md`.

**Tests.** Not run. The coordinator states that armed fixture tests are blocked
for every lane by
`docs/seon/issues/incremental-publication-refuses-a-deletion-whose-unchanged-caller-edge-survives.md`,
and the stale base above also still stands. The updated
`seon.oversight-test` bodies are unverified by a test run.

### Timings (follow-up, over 1 s)

| operation | wall | phases |
| --- | --- | --- |
| probe2 JVM (`clojure -M:test`) | 13.7 s | projection build 989 ms; unit 4.8 ms; the rest is JVM start and load |
| from-zero boot attempt, working tree | 22.3 s | refused by foreign static analysis |
| from-zero boot, archive (rule breach) | 142.4 s | ready-ms 128,569 |
| warm `start`, archive root | 31.5 s | ready-ms 14,774 |
| `init --changed` retire | 60.4 s | no phase lines printed |
| `init --changed` adopt | 60.6 s | no phase lines printed |
| live SSE wake: note to root package | 1.68 s | join first package 588 ms |

Everything over 10 s is filed:
`a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md` (kept,
since other notes cite it; it now points to the twenty-second note of the same class),
`from-zero-boot-takes-minutes.md` (rows appended), and the new
`a-three-file-changed-path-publication-takes-a-minute.md`. The 1.68 s wake is
over 1 s and under 10 s; its split between the note transaction, the wake and
the render pass was not measured.

### Follow-up commits

- `b44585c0b`: the three findings, the schema resource and the issue rows.
- `8a43e596a`: the thirty-second test-JVM note is kept, since other notes
  cite it.

HEAD `8a43e596a` loads in a fresh JVM from a `git archive` copy:
`(require 'seon.oversight 'seon.render.web 'seon.cluster)` printed
`:loaded 17 true`, meaning 17 contracted Vars and the resource present on the
classpath. Wall time 17.5 s, all of it JVM start and load (see the test-JVM
note). RESET NEEDED: no.
