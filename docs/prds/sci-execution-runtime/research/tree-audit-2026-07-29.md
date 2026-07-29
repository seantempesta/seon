---
type: research
status: active
tags: [research, audit]
---

# Fresh-tree adversarial audit, 2026-07-29

## Chunk 1 — sweep inventory and first findings

This is an independent read of the fresh JVM tree at `3ef8745fc564`.
No lane report was treated as evidence. The audit began with repository-wide
`rg` sweeps and then read the suspicious source, tests, schemas, and the
relevant vendored Flow implementation directly.

The three explicitly in-flight owners were inventoried but not judged as final:
`src/seon/render/walk.clj`, error routing, and configuration. Concurrent
unrelated edits also appeared in `src/seon/cluster/work.cljc`,
`src/seon/problems.clj`, and
`test/seon/cluster/problem_routing_test.clj`; none is evidence for a finding
below.

### Inventory

| Surface | Files | Lines | First-pass checks |
|---|---:|---:|---|
| `src/**/*.clj*` | 42 | 20,121 | 701 `defn`/`defn-` forms; atoms, throws, clocks, parsing, digests, rendering, config reads, workload tags |
| `src/seon/schema/*.edn` | 31 | 2,421 | stored nilability, discriminators, derived fields, callback/runtime-object schemas |
| `test/**/*.clj*` | 59 | 20,566 | 483 discovered `deftest`s; sleeps, polling, timeout equality, shape-only assertions, dead production seams |

The first sweep counted 17 source atom construction sites, two production
`Thread/sleep` sites, and seven test sleeps. Those are an investigation
inventory, not seven findings: the atoms read so far own process-local
resources or test observations, the model retry sleep is an actual finite
remote-call schedule, and the SCI timer guards the one declared time limit.

The stored-schema discriminator sweep was clean. The only
`[:maybe ...]` match in `src/seon/schema/*.edn` is a comment in
`context.edn` explicitly describing an in-memory shape. No stored `:type` or
`:kind` entity stamp was found. `:seon.render/kind` is a request argument, not
a persisted taxonomy.

## Ranked findings

### Blocker — agent turns bypass the bounded compute door

The agents-as-flows rebuild says evals leave the `:io` turn proc through
`seon.flow/submit!!`, but the implementation does not.

- `src/seon/cluster/agent.clj:164-224` makes `turn-step` an `:io` transform
  and calls `seon.cluster.loop/turn` inline.
- `src/seon/cluster/agent.clj:243-267` pins that transform to `:io`.
- `src/seon/cluster/loop.cljc:718-756` resolves and invokes
  `seon.sci.eval/evaluate` inline during the resume fold.
- `src/seon/flow.clj:450-484` defines `submit!!`, but the only other
  production occurrence is the claim in `cluster/agent.clj`'s namespace
  docstring. There is no production call.
- `src/seon/cluster.clj:210-233` creates a process-root compute/IO executor
  pair and stores it on the instance at line 1030, but neither
  `cluster-graph-definition` nor `cluster.agent/graph-definition` supplies
  `:compute-exec` or `:io-exec` to `create-flow`.

This contradicts the dependency's contract, not merely a Seon convention.
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:200-202`
says an `:io` workload must not do extended computation, and lines 272-282 say
the transform itself runs on the IO thread unless the proc is `:compute`.

A direct JVM probe of the production blueprint confirmed the effective
launcher description:

```clojure
(require '[seon.cluster.agent :as agent]
         '[clojure.core.async :as async]
         '[clojure.datafy :as datafy])

(let [definition
      (agent/graph-definition
       {:seon.cluster.loop/cluster
        {:seon.cluster.wake/channel (async/chan 1)}
        :seon.cluster.agent/id "audit"})
      launcher
      (get-in definition
              [:procs :seon.cluster.agent/turn :proc])]
  (datafy/datafy launcher))
;; => {:step seon.cluster.agent/turn-step,
;;     :desc {... :workload :io ...}}
```

The suite is green for the wrong reason at this seam. The Flow tests exercise
the isolated work launcher and `submit!!`; the boot test proves only that
`root-executors` returns the same two objects; and the agent tests exercise the
real turn graph while accepting its `:io` tag. No test composes an agent turn
with the bounded compute owner or proves configured compute concurrency.

Impact: every concurrent agent may execute CPU-bound SCI work on an IO thread,
while the configured bounded compute path and its backpressure are unreachable.
The 100-agent final gate therefore does not exercise the scheduling model it
claims to graduate. The orphaned launcher's two existing unbounded-start issue
notes are real but secondary: repairing that wait alone would still leave
production evals bypassing it.

Issue: [[agent-turns-bypass-the-bounded-compute-door]].

### Cleanup — five implemented namespaces still instruct a stub-filling lane

Current namespace docstrings still describe a past contract-scaffold workflow:

- `src/seon/cluster.clj:4-8`;
- `src/seon/cluster/store.clj:5-10`;
- `src/seon/cluster/ancestor.clj:4-9`;
- `src/seon/schema/edn.clj:5-9`; and
- `src/seon/sci/admit.clj:4-10`.

All five namespaces are implemented and have recurring tests. The prose says
an implementation lane “fills the stub bodies” until a named suite is green
and may not change sealed tests. That is historical sequencing presented as
current source authority, and it encourages exactly the implementation-shaped
testing the repository now rejects.

`src/seon/config.cljc:4-8` has the same residue, but configuration is explicitly
in flight and is deferred from this finding.

Issue: [[implemented-namespaces-still-instruct-a-stub-filling-lane]].

## What is genuinely in good shape

- Stored entity schemas use attribute presence rather than `:type`/`:kind`
  stamps. Optional stored values are map-entry optionality, not
  `[:maybe ...]`.
- The agent blueprint has one mailbox/turn graph definition, and the earlier
  boot-time second arming path is gone. Boot now registers the database
  listener before invoking the armer derivation.
- Digest helpers inspected so far share `seon.schema/sha-256` and represent
  distinct inputs: ancestor file-tree identity, run source identity, error
  signatures, and context contribution text. This sweep did not find a second
  SHA-256 implementation.
- The render router is structurally computed from declaration-key presence;
  it does not carry a hand-maintained renderer registry.
- Every `*_test.clj*` file inspected by the discovery sweep contains at least
  one `deftest`; there is no zero-test green namespace in the fresh test tree.
- Process-global atoms read so far are explicit owners of process-local
  artifacts (connections, graph routing, schema compilation state, or test
  capture), not durable facts masquerading as memory.

## Next chunk

Chunk 2 should deepen the seven test sleeps and polling loops, inspect
hand-maintained attribute/function lists against computed alternatives, and
trace stored context/error projections back to their source facts. It should
also re-read the three deferred in-flight owners only after their edits settle.
