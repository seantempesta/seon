---
type: research
status: active
tags: [research, operator, runtime, flow]
---

# Integrant fit for operator integration (2026-08-03)

## Verdict

**Do not adopt Integrant for the operator integration.** Move the callable
control operations into the packaged JVM as ordinary Seon functions, keep the
pre-database process-root custody deliberately tiny, and preserve the existing
division of lifecycle ownership:

- the outer launcher owns OS process creation, stdio/log capture, and any fence
  that must exist before the JVM can safely open the physical store;
- `seon.cluster` owns the REPL-first cluster boot sequence and its reverse unwind;
- core.async.flow owns proc and graph lifecycle; and
- the database owns durable desired state, outcomes, and queryable control
  facts.

Integrant would add a second dependency graph and a second lifecycle protocol
without supplying readiness, supervision, event publication, or database
authority. Its strongest benefit—dependency-ordered construction and reverse
halt—is already present in a more domain-specific form. The 2026-07-26
conditional adoption case also no longer exists: it was justified only if the
one-JVM topology merge deleted approximately 360 lines of standalone host,
writer, and web-server lifecycle scaffolding. That merge has happened without
Integrant, and all three cited source files are absent.

This is a recommendation for a new owner ruling, not a claim that the recorded
2026-07-26 ruling has silently changed. The current roadmap still records
narrow conditional adoption at
[docs/prds/sci-execution-runtime/plan/unsettled.md](../plan/unsettled.md#L2755-L2766).

## Sources read and dependency ledger

I read the following named sources end to end before reaching the verdict:

- `reference-code/integrant/README.md` (704 lines),
  `reference-code/integrant/src/integrant/core.cljc` (702 lines),
  `reference-code/integrant/CHANGELOG.md`, `reference-code/integrant/deps.edn`,
  and `reference-code/integrant/project.clj`;
- the prior conditional-adoption report,
  [docs/prds/sci-execution-runtime/research/integrant-boot-design-2026-07-26.md](integrant-boot-design-2026-07-26.md);
- the complete deleted predecessor report via Git,
  `git show 24053c64e^:docs/prds/sci-execution-runtime/research/integrant-evaluation-2026-07-24.md`;
- `.agents/skills/seon-flow-architecture/SKILL.md`; and
- the localized runtime instructions at
  [docs/prds/sci-execution-runtime/AGENTS.md](../AGENTS.md).

The current comparison additionally read the boot, teardown, and readiness
owners in `src/seon/cluster.clj`, the process-root holder in
`resources/seon/operator/runtime.clj`, agent graph lifecycle in
`src/seon/cluster/agent.clj`, Seon's Flow owner in `src/seon/flow.clj`, and the
maintained core.async Flow implementation and SPI.

| Dependency or mechanism | Selected revision | Maintained source and finding |
|---|---|---|
| Integrant | `bcad6bcf35b62d3a32a453dc26b6d3a4d659dc01`, described as `1.0.1-2-gbcad6bc` | Gitlink and URL are recorded at `.gitmodules:341-343`; `project.clj:1` declares 1.0.1. `deps.edn:1-4` selects `weavejester/dependency` 0.2.1, while `project.clj:6-7` selects 1.0.0; any adoption must resolve that maintained-coordinate mismatch rather than copying either blindly. |
| core.async Flow | `dc35f3e0d7bc2eef502e77982f48641f025c8051`, `v1.10.874-alpha3` | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-163,165-245`; `flow/impl.clj:94-197,199-323`; `flow/spi.clj:11-58`. Flow already owns graph/proc start, pause, resume, stop, ping, injection, transitions, error channels, and resource cleanup. |
| Seon process-root custody | current tree at `67e92bec9` | `resources/seon/operator/runtime.clj:1-28` holds only running instances, store/flock custody, and shared executors outside every cluster program graph. |
| Seon cluster boot sequence | same | `src/seon/cluster.clj:1472-1571,1573-1677,1780-1867` publishes each stood layer, keeps the REPL alive after later failure, unwinds an addressed instance in reverse, and reforks through the one registry owner. |
| Seon graph lifecycle | same | `src/seon/cluster.clj:1251-1469`; `src/seon/cluster/agent.clj:344-407`; `src/seon/flow.clj:418-455,626-676`. Cluster, agent, work-launcher, and fault-committer runtime activity already uses Flow's lifecycle. |
| Current architecture target | same | [docs/seon/architecture/architecture.md](../../../seon/architecture/architecture.md#L30-L54) makes the process root own one fenced physical store and shared executors, while each cluster branch owns its connection, REPL, graphs, web endpoint, and facts. |

No production dependency or call site currently uses Integrant: searches of
`deps.edn`, `src/`, `resources/`, `script/`, and `test/` found no
`integrant.core` or `ig/init`/`ig/halt!` use. The submodule is reference code,
not an active runtime dependency.

## What Integrant actually supplies

Integrant is a small, coherent answer to one question: given a configuration
map whose values contain `ref` or `refset` records, in what order should
in-process values be constructed and destroyed?

- `ref` and `refset` resolve initialized values from top-level configuration
  entries (`reference-code/integrant/src/integrant/core.cljc:77-103`).
  `dependency-graph` tree-walks those records into a
  `weavejester.dependency` graph, and `key-comparator` produces deterministic
  topological order (`reference-code/integrant/src/integrant/core.cljc:177-215`).
- `build` resolves references, runs an assertion, invokes a constructor, and
  accumulates an ordinary system map in dependency order
  (`reference-code/integrant/src/integrant/core.cljc:351-455`).
- `init-key`, `halt-key!`, `resume-key`, `suspend-key!`, and `assert-key` are
  global multimethod lifecycle seams
  (`reference-code/integrant/src/integrant/core.cljc:457-548`). `init` and
  `halt!` apply them in dependency and reverse-dependency order
  (`reference-code/integrant/src/integrant/core.cljc:650-666`).
- An initialization failure carries the partial system in exception data, but
  core performs no rollback; the caller must extract and halt it
  (`reference-code/integrant/src/integrant/core.cljc:409-421`).
- Integrant has no readiness event, health observation, post-init supervision,
  restart policy, process identity, or cross-process model anywhere in the
  complete core source. An `init-key` is treated as ready when its synchronous
  call returns.

Several optional Integrant conveniences are actively poor fits for Seon:

- The default `init-key` derives a function symbol by concatenating the
  keyword's namespace and name, then uses `find-var`
  (`reference-code/integrant/src/integrant/core.cljc:478-494`). Seon's current
  law rejects naming conventions as substitutes for declared facts.
- Annotations live in a private process-global atom
  (`reference-code/integrant/src/integrant/core.cljc:10-23`), and composite
  keys mutate Clojure's global keyword hierarchy
  (`reference-code/integrant/src/integrant/core.cljc:29-40`). Neither makes
  the resulting declaration queryable in a cluster database.
- `suspend!` and `resume` define a second pause/reuse lifecycle
  (`reference-code/integrant/src/integrant/core.cljc:506-526,677-702`) beside
  Flow's transition protocol. Flow already invokes proc resource cleanup on
  its `::flow/stop` transition
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:234-243`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:199-217,271-323`).

These are not defects in Integrant. They are mismatches between the problem it
solves and Seon's chosen authorities.

## Current tower versus an Integrant system

| Required property | Current owner | Integrant fit |
|---|---|---|
| REPL exists before store/facts/Flow and survives later boot failure | `start!` opens and advertises the io-prepl first, publishes the partial instance after every layer, and throws with that exact instance (`src/seon/cluster.clj:1573-1677`). The recurring failure proof is `test/seon/cluster/boot_test.clj:1096-1128`. | `init` returns only after the selected system is built. A throw exposes a partial map in exception data, but keeping the REPL registered and independently stoppable would still require Seon's existing publication/registry path around Integrant. |
| Fixed layer law: process → store → facts → context → Flow → web | `stack-tower!` is the executable order and publishes every stood value (`src/seon/cluster.clj:1472-1571`). | A ref graph can encode the same order, but that is a relocation of an already explicit invariant, not a simplification. |
| Per-cluster isolation over one process-root store | `running-instances`, the store holder count, and instance-addressed `stop!` ensure one cluster cannot stop a replacement or a sibling (`resources/seon/operator/runtime.clj:11-22`; `src/seon/cluster.clj:315-407,1679-1695,1780-1841`). | The prior nested-system design could preserve isolation. A flat root `refset` could instead make reverse halt traverse shared resources. Integrant adds representation risk without adding an isolation guarantee. |
| Orderly proc shutdown joins active work before releasing a connection | Flow supplies the stop transition; Seon adds proc-published completion because Flow `stop` is asynchronous (`src/seon/cluster.clj:1414-1469`; proof at `test/seon/cluster/boot_test.clj:620-675`). | `halt!` is synchronous only with respect to calling each `halt-key!`; it has no knowledge of Flow completion. Every component method would still need to call and join the existing owner. |
| Readiness and status are derived, not stored | `readiness` reads the instance and database, and MCP adds Flow observation (`src/seon/cluster.clj:204-217,1697-1740`). | A system map is a construction result, not live health. Reading it as status would create a stale parallel view. |
| Durable control and configuration are database facts | Config is applied before root-agent and graph creation; later work reads effective config from a database value (`src/seon/cluster.clj:1515-1548`). | An authoritative Integrant config would be a second configuration source. Generating it from database facts makes it a derived process-local projection whose only remaining contribution is ordering already owned by the tower. |

The critical difference is not linear code versus declarative data. Seon's
tower publishes readiness and failure position at each boundary; Integrant
computes construction order from refs. Those are different guarantees.

## The prior conditional adoption has expired

The 2026-07-26 report recommended Integrant only at the topology merge and
made deletion the acceptance test: approximately 360 lines across
`src/seon/host.clj`, `src/seon/db/server.clj`, and
`src/seon/web/server.clj` had to disappear, with roughly 180-220 net lines
deleted. See
[docs/prds/sci-execution-runtime/research/integrant-boot-design-2026-07-26.md](integrant-boot-design-2026-07-26.md#L64-L74).

All three cited files are absent now. Their surviving responsibilities were
designed fresh into `seon.cluster`, Flow graphs, and branch-local web/runtime
owners. There is therefore no remaining 360-line wrapper layer for Integrant to
replace. Adoption now would require translating the current tower and teardown
back into `init-key`/`halt-key!` methods while retaining the same Flow joins,
partial-instance publication, registry fences, database config application,
and instance-addressed stop checks. That fails the repository's conversion
test: complexity would move, not disappear.

The topology also changed materially from the earlier proposal. The current
target is one process-root physical store with one branch per cluster
([docs/seon/architecture/architecture.md](../../../seon/architecture/architecture.md#L30-L54)),
not the earlier report's root/nested graph conceived around separately owned
writer resources. The store holder count and `refork!` extra hold now express a
specific shared-store lifetime invariant (`src/seon/cluster.clj:359-407,1844-1867`)
that a generic ref graph would not make safer.

## Adoption postures

### Option 1 — no Integrant; strengthen the tower in place (recommended)

**Guarantee.** One lifecycle mechanism per layer: OS launcher for the foreign
process, `seon.cluster` for the boot sequence and branch instance, Flow for every
proc/graph, database facts for durable requested and observed state. Runtime
functions such as source publication, branch creation/refork, config apply,
status/readiness, and cluster stop remain ordinary functions callable through
the REPL/MCP and, where policy permits, the program graph.

**Cost and risk.** The migration must separate the truly pre-JVM shell work
from JVM-callable functions without weakening exact process identity, flock
custody, degraded-REPL access, or stop retry. The tower remains explicit code;
there is no automatic topological sort for non-Flow resources.

**Operational trade-off.** Dependencies remain visible as function arguments
and the fixed tower order rather than as an independent component graph. In
return, every live fact and control outcome has one authority and current
readiness stays derived.

**Capability given up.** Arbitrary runtime assembly of a new component DAG by
editing an Integrant configuration map. No current requirement needs that
capability; Seon's runtime topology is constrained and its extensible work is
already functions, schemas, facts, and Flow graph definitions.

### Option 2 — Integrant only for the minimal pre-database base

**Guarantee.** A very small Integrant system could order the REPL, root store,
and root executors, then return the handles used by the ordinary Seon tower.
Reverse halt would be mechanically derived for those base values.

**Cost and risk.** This puts Integrant in the most sensitive bootstrap classpath
while leaving every meaningful lifecycle behavior outside it. The caller must
still preserve the REPL across partial failure, publish the degraded instance,
join Flow procs, enforce instance identity, and retry failed release. Integrant
adds its config/ref graph, global multimethods, and `weavejester/dependency`; it
also has no automatic rollback. A pre-database system map cannot be the
database-queryable control authority by definition.

**Operational trade-off.** Base teardown order becomes declarative, but
diagnosis now crosses two lifecycle vocabularies before the database is even
available.

**Capability given up.** None compared with Option 1 that is material today;
the gain is only automatic ordering of a fixed three-resource base. Adopt this
only if an implementation spike deletes more first-party lifecycle code than
it adds and preserves the degraded-REPL proof unchanged.

### Option 3 — full root plus nested per-cluster Integrant systems

**Guarantee.** This is the earlier conditional design: a root system owns
process resources and one nested system per cluster, with refs expressing
construction order and reverse halt isolated inside each nested system.

**Cost and risk.** Highest. Every Flow graph keeps its own lifecycle; the tower
still needs readiness publication; the database still owns config; the running
instance registry still fences generations; and the store holder still owns
shared flock lifetime. Integrant becomes a coordinating wrapper around all
four rather than an owner replacing any of them. A bad flat ref/refset edge can
pull shared resources into one cluster's halt. The default naming-based
initializer must be forbidden, and the system map must never become a status
authority.

**Operational trade-off.** The complete static construction DAG becomes
inspectable as process-local data, at the cost of maintaining a second graph
beside the database program graph and Flow graphs.

**Capability given up.** Simplicity and single-authority diagnosis. This option
should be rejected under the current architecture.

## Recommended migration rule

Choose Option 1 and treat the minimal packaged base as a **loading and custody
boundary**, not as a component framework:

1. The outer launcher starts the JVM with only the bootstrap paths, stream/log
   routing, and any pre-open process fence it must own.
2. The JVM publishes its REPL immediately and exposes ordinary functions that
   take namespaced request maps.
3. The root store and selected branch are opened through the existing owners;
   database facts then become the authority for initialization, config apply,
   desired clusters, and observable outcomes.
4. The surviving tower functions start/reconcile cluster instances and Flow
   graphs. Control calls reach these functions through REPL/MCP/agents rather
   than reimplementing them in Babashka.
5. `bin/seon` becomes a thin foreign-process client, not a second lifecycle
   engine.

### Acceptance evidence

- `deps.edn`, `src/`, `resources/`, and `script/` remain free of
  `integrant.core` unless a later measured deletion case earns it.
- The existing degraded-store proof still shows an answering REPL and a
  stoppable partial instance (`test/seon/cluster/boot_test.clj:1096-1128`).
- The existing active-pass proof still shows that orderly stop waits for the
  proc's completion before releasing the branch connection
  (`test/seon/cluster/boot_test.clj:620-675`).
- Status is reproduced from the instance, database value, and Flow ping rather
  than read from a stored component/system status map.
- A before/after source inventory names operator code deleted. If the migration
  only relocates Babashka branches into Clojure without collapsing them onto
  existing functions, it has failed the simplification test.

## Honesty ledger

- This report evaluates `integrant.core` at the pinned revision. It does not
  evaluate `integrant-repl`, which is not vendored and is not needed to answer
  whether core should own packaged-runtime lifecycle.
- No runtime performance claim is made. The rejection is architectural: the
  prerequisite deletion has already happened and Integrant supplies none of
  the current tower's distinctive guarantees.
- The active roadmap's conditional adoption ruling and this recommendation
  disagree. The owner must explicitly retain or supersede that ruling; a code
  lane must not infer the decision from this research note.
