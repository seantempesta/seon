---
type: research
status: active
tags: [research, runtime, test]
---

# Multi-cluster concurrency — partial landing, 2026-09-08

Stopped at the assignment's explicit foreign-gate boundary. This is not a
green concurrency proof. No production files or held files were changed.

## Verdict by angle

| Angle | Observation | Verdict |
|---|---|---|
| Two clusters in one root/JVM | default and beta both alive, PID 14049 | Proven for boot and MCP JVM access |
| Separate cluster carriers | SCI ctx, projection state, connection, routing, render view, and plumbing graph were distinct objects | Proven object ownership; behavior isolation remains unproven |
| Concurrent agent turns | Two probe agents created in each cluster; five-way wave included existing Juniper | Incomplete: source submission refused while probe agents had open runs |
| Process-global state audit | Inspected root holders, SCI forks, schema caches, search registry, thread-local guard, and render allocation | Partial; no exhaustive clearance |
| Development adoption | Explicit init and actual edit hook both refused with two instances | Refuted |
| Regression gate | Both requested gate forms failed before this regression ran | Blocked |
| Second root/store | gamma reached a separate JVM, PID 16200, but lacked current-src | Partial boot only; no running-cluster or MCP proof |

## Measurements and exact boundaries

`bin/seon status` reported:

```text
beta     14049 alive 50007 http://127.0.0.1:7711
default  14049 alive 58925 http://127.0.0.1:7994
2/2 clusters alive
root footprint: 10.41 GiB; filesystem usable: 555.31 GiB
```

The initial status command waited roughly 200 seconds on the lifecycle lock,
first behind an existing `init --dev default --changed src/seon/flow.clj`
(PID 27725), then behind this probe's beta start. Neither holder was displaced.
Beta boot reached ready at approximately 19:45:45 UTC. MCP JVM evaluation
selected both `root=/Users/sean/src/seon` and each explicit cluster name.

The object comparison used `identical?` between the default and beta instance
members: `:seon.sci.eval/ctx`, `:seon.flow/graph`,
`:seon.cluster.agent/routing`, `:seon.render.web/view`, and
`:seon.boot/cluster-connection`; all returned false. The contexts'
`:seon.sci.eval/projection-state` comparison also returned false. This does
not prove every nested value is isolated. Default's queried source commit
was `6aa063cd-d528-5e8c-94b1-2c408fdf4001`; beta had no matching cluster
`:seon.source/commit-id` fact in this query. Its fork commit was not recovered;
absence is not proof that it had no source publication.

`bin/seon init --dev default --changed src/seon/operator.clj` exited 1:

```text
Development updates require their own running JVM.
:seon.error/kind :seon.boot/refused
:seon.boot/offense {:seon.boot/cluster-name "default"}
```

The real edit hook reproduced the refusal on the new regression. The held
refresh owner rejects `(not= 1 (count @running-instances))` at
`src/seon/cluster.clj:2024`. No edit was adopted, and no browser repaint or
beta page-serving claim is made.

Agent creation used `seon.cluster/ensure-entity!`, in each cluster's carried
projection, with ids `concurrency-a` and `concurrency-b` and namespaces
`my.agents.concurrency.concurrency-a` and `.concurrency-b`. Both default
bootstrap runs closed at 19:47:35–36 UTC; both beta bootstrap runs closed at
19:47:33–34 UTC. A later wave submitted one literal vector to Juniper and
these four agents concurrently through `submit-source!`, using the new
regression helper. It raised `Concurrent source submission refused` with
`:seon.error/kind :seon.cluster.run/refused`. Follow-up facts showed open
non-source runs on default's probe agents. This was not diagnosed as a wedge;
the full refusal data was not preserved by MCP's exception projection.

**Throughput: unavailable.** No complete successful wave was observed. The
four creation calls took 618 ms together; that is creation time, not turns/s.
Private def, installed defn, result-handle, and message-wake isolation were
not reached. No claim of provider-free completion is made for the automatic
work that followed boot/agent creation.

## Thread picture

At 19:47:50 UTC, while both clusters and probe agents existed:

```sh
jcmd 14049 Thread.print > tmp/multi-cluster-concurrency/platform-threads.txt
jcmd 14049 Thread.dump_to_file -format=json /Users/sean/src/seon/tmp/multi-cluster-concurrency/all-threads.json
```

The JSON dump contained 15 thread containers and 132 threads: 59 explicitly
virtual and 73 without the virtual flag. Of those, 39 had a stack frame
containing `clojure.core.async.flow`; **all 39 were virtual**. No platform
thread in that snapshot carried a Flow stack. This is a snapshot, not a
measured scaling slope or an attribution of each thread to one named agent.
`Thread.print` alone omits virtual threads and is insufficient for this proof.

Source grounding: `agent/graph-definition` constructs mailbox, turn, and
schedule with explicit `:io`; `cluster/projection-executor` delegates to the
root I/O executor while carrying projection state. The pinned core.async
`impl/dispatch.clj:82` uses `Thread.startVirtualThread` when available. SCI's
`core.cljc:345` forks the env atom; `fork-cluster-ctx` additionally reconstructs
connection custody, projection state, and supplied-default state.

## Gate evidence and scope left unfinished

`bin/test --platform` exited 1 at load 119/152:

```text
Syntax error compiling var at (seon/render/web_prompt_test.clj:26:6).
Unable to resolve var: seon.render.web/prospective-prompt in this context
```

Its retained root is `tmp/test-runs/run.Spfc7z`, HEAD `38d2fc91b`.
`bin/test --paths test/seon/concurrency_test.clj -- seon.concurrency-test`
also exited 1, earlier, reading a missing
`:seon.dev-cache/test-classpath-file` from the selection. The script/cache
interface straddled HEAD and the foreign cache edit. Neither is an assertion
failure of this regression. No retry or foreign-session intervention followed.

The added namespace currently covers four concurrent source turns per wave
on two booted fixture clusters, three waves, closure, evaluation presence,
and exact cluster/agent-tagged stored values. It uses the canonical published
base, real SCI and real agent procs, no mocks. It remains **unverified and
incomplete against the full assignment**: private objects, installed function
visibility, handles, messages, and adoption are not yet regression assertions.

The state audit found root-store holders keyed by store directory, a search
registry keyed by index/connection, and schema projection caching keyed by
Datahike committed-value identity. Those are not automatically leaks because
they are process-global. Source refresh and JVM instrumentation need further
analysis: shared host Var mutation is the unresolved cross-cluster boundary.
No speculative guard-removal hunk is offered as a safe fix.

Named skills read: flow architecture, REPL, data-oriented Clojure, and Clojure
testing. AGENTS.md was supplied in full; the requested PRD sections were read.
The broader requested source/authority reading was not completed before the
stop boundary; this note does not claim an end-to-end audit.

See the [adoption issue](../../../seon/issues/development-adoption-refuses-cohosted-clusters.md)
and [gate boundary evidence](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md).

## Cleanup and changed files

Beta was stopped using `bin/seon stop beta`. Gamma's incomplete publication
was cancelled by terminating this lane's exact init CLI PID 37010, followed
by root-scoped `down --force`; default was never stopped or reset.
Root-scoped down exited successfully with `flock free; roster readable
(1 branches)`. `tmp/other-root` was deleted without following symlinks.
Final `bin/seon status` reported default alone, PID 14049, alive, and no
orphan JVMs. All owned operator and test shell commands exited. The two
probe agent records in default remain; Juniper was not reseeded or reset.

Owned changes: `test/seon/concurrency_test.clj`, this landing note,
`docs/seon/issues/development-adoption-refuses-cohosted-clusters.md`, and the
additional observation in
`docs/seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md`.
