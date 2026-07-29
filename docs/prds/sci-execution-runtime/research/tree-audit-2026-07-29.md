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

## Chunk 2 — clocks, lists, projections, contracts, and skills

Chunk 2 re-swept the settled tree after `ce398cd59` and `f5b689f3e`. It read
the afternoon rulings and the next two batches in `plan/README.md`, then read
the three mandatory schema/data skills in full. Source and tests remained
read-only.

The contract inventory used the Clojure reader rather than regex alone:
348 public `defn` forms, with docstring and `:malli/schema` metadata inspected
on the forms themselves. Schema polymorphism was then cross-checked against
the EDN schema files and the dependency source.

### Ranked findings

#### Blocker — 35 public functions are invisible to contract enforcement

Thirty-five fresh public functions lack `:malli/schema`. This includes six
public work-launcher operations, the public digest/canonicalization helpers,
every public Malli-to-Datahike bridge helper, and runtime-object predicates
used by named EDN schemas. The complete file/function inventory is in
[[fresh-public-functions-lack-complete-malli-contracts]].

This is materially different from the archived old-tree issue. The earlier
audit treated opaque predicates and the schema bridge as principled
exceptions. The afternoon ruling explicitly removes that exception: opaque
objects require named predicate schemas with honest generators, and every
public function requires a complete contract.

`src/seon/test/runner.clj:210` (`-main`) is also the only public function
without a docstring. The lexical docstring sweep found no other public
function whose future-tense wording contradicted its body: for example,
`render.web/surface-html` describes the value's consumer, while
`render.root/messages-html` explicitly says its current output is a flat
cluster list. The six namespace-level stub instructions remain genuinely
false and are tracked separately.

Issue: [[fresh-public-functions-lack-complete-malli-contracts]].

#### Friction, high priority — mandatory skills teach the retired schema model

The three requested skills agree with the fresh system on the important
presence-not-kinds rule, identity attributes, refs/components, omission of
stored nil, and derived views. They do not agree on the actual construction:

- `datahike/SKILL.md:8-13,88-106,126-139` says code-level
  `schema/register!` is the single source and quick-start authoring path;
- `data-modeling/SKILL.md:8-15,43-81,288-310` teaches the same retired
  authoring workflow;
- `data-modeling/SKILL.md:234-237` and
  `data-oriented-clojure/SKILL.md:87-92` say fresh runtime instrumentation
  does not exist, contradicted by `src/seon/instrument.clj:180-220`; and
- `data-oriented-clojure/SKILL.md:99-102` turns the stored-value
  `[:maybe]` ban into a global startup ban, contradicted by the intentional
  in-memory omission contract in `schema/context.edn:101-120`.

The actual fresh source has 31 `src/seon/schema/*.edn` files, loaded by
`schema.edn/load!` (`schema/edn.clj:173-191`). A direct JVM probe resolved
both `seon.instrument` and Datahike's maintained `db?` predicate. These skill
claims will actively steer the next coding agent toward a second schema
authoring mechanism, so this is high-priority friction rather than prose
polish.

Issue: [[schema-skills-teach-the-retired-registration-model]].

#### Friction — observable graph transitions are still polled

`test/seon/cluster/agent_test.clj:139-147` polls every 25 ms and is used
throughout the agent suite; line 416 sleeps a fixed 300 ms.
`test/seon/flow_test.clj:930-944` polls an atom every 10 ms. Render and turn
fixtures also repeatedly ping until inferred state appears.

These waits stand in for internal events: armed, paused, idle/settled, and
render-state reports. Flow and Datahike already publish report events; the
production handles fail to retain the event tests need. The clock is therefore
a symptom of a hidden interface, not a justified backstop.

The audit did not count foreign child readiness, deliberately sleeping child
processes, HTTP/SSE bounds, or the workload timing test. Those guard genuinely
external or deliberately timed behavior.

Issue: [[observable-graph-transitions-are-polled-in-tests]].

#### Friction — database and transaction contracts use anonymous `:any`

The fresh source contains 74 contract occurrences beginning with
`[:cat :any ...]`; the database-domain majority use it for a Datahike database
value. Four EDN schema families also independently declare
`:seon.db/db :any`. This is not the strongest honest boundary:
Datahike maintains `datahike.db.utils/db?` over its `IDB` protocol, and the
probe confirmed it resolves in the pinned dependency.

Transaction producers separately repeat `[:vector :any]` in reconcile,
context capture, error commit, and render seed/install functions, despite the
store schema already naming the broader transaction boundary. A named
transaction-data shape is the missing shared floor.

This finding deliberately excludes the proven open boundaries in SCI
admission, arbitrary error sources, generic render values, and recursive data
inspection.

Issue: [[database-and-transaction-boundaries-use-anonymous-any-contracts]].

#### Cleanup — two derived-state vocabularies have duplicate authorities

`schema/context.edn:80-81` repeats the exact five-member enum already owned by
`:seon.render.block/band` in `schema/block.edn:48`.
`cluster/work.cljc:292-293` separately lists the three settled form states
after lines 312-320 have already derived the state from evidence.

Neither is stored-derived creep: both are in-memory projections. The defect is
the second hand-maintained classification, which can drift from its owner.

Issue: [[derived-state-contracts-repeat-hand-maintained-enums]].

#### Cleanup — the settled config owner adds a sixth lying namespace docstring

`src/seon/config.cljc:4-8` still says an implementation lane fills stub bodies
until sealed tests are green. Chunk 1 deferred it while config was in flight;
after `ce398cd59` it is the sixth instance of the same root cause.

Updated issue:
[[implemented-namespaces-still-instruct-a-stub-filling-lane]].

### Stored projection verdicts

No defect was found in the scoped context/error storage after reading the
write paths.

- Context capture stores the exact prompt, basis transaction, trusted
  live-process snapshot, and ordered contribution evidence before the remote
  call (`schema/context.edn:1-99`; `context.clj:287-311`). Omitted blocks write
  no row; render kind and contribution text are deliberately not duplicated.
  Hash, tokens, position, band, and projection describe historical
  reproducibility, not mutable current context.
- Error facts store immutable evidence and a content signature
  (`schema/error.edn:38-108`). Recurrence is computed with a query in
  `error.clj:669-681`; occurrence/notification fields exist only on derived
  notices/messages and are absent from the durable fact schema. There is no
  stored counter, acknowledged flag, or notification queue.

### What remains genuinely strong

- The stored-schema sweep remains clean: no stored `[:maybe]`, nil, entity
  `:type`, or entity `:kind` discriminator.
- Error kind is intentionally open rather than a hand-maintained enum, and
  the comments accurately identify producer keywords as the population.
- Render kinds are computed from declaration-key presence; the walk fix did
  not introduce a renderer registry or a second render path.
- The context/error comments are unusually falsifiable: each names what is
  stored, what is omitted, and why. Reading the implementations confirmed
  those claims.
- Immediate `async/poll!` assertions of absence remain appropriate; they are
  not confused with waits.
- The schema EDN loader rejects duplicate keys globally, so file boundaries
  remain editorial rather than a second namespace registry.

## Next chunk

Chunk 3 should inspect the remaining public `:any` sites individually after
the database/transaction floor is named, audit public docstrings for behavioral
overclaim beyond lexical future-tense candidates, and deepen the production
hand-list sweep around wake routing and AI disposition classification. It
should also verify whether the test-lifecycle issue is dissolved when graph
report ownership lands.
