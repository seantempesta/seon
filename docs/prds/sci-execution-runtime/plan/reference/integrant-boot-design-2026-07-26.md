---
type: research
status: active
tags: [research, runtime]
---

# Integrant boot design (2026-07-26)

**Decision: adopt Integrant narrowly as the in-process construction and
destruction owner when writer, driver, and web-render merge into one JVM.**
Keep the operator's process graph: it orders external processes and observes
readiness, while Integrant orders live handles inside the scheduled runtime.
Do not land Integrant ahead of the merge or use it for readiness, supervision,
claims, or configuration after the database exists.

I agree with the 2026-07-24 evaluation's facts and its answer to the question
it asked: Integrant would have prevented none of the measured wedges and has no
readiness model (`git show 24053c64e^:docs/prds/sci-execution-runtime/research/integrant-evaluation-2026-07-24.md`).
That report already settled Integrant-as-crash/readiness-mechanism; this report
does not redo it. The question is reopened because the old 5,715-line host was
deleted in `8dc8623ad`, `src/seon/host.clj:1-58` is now only a linear launcher,
and the target is one process containing N per-connection Datahike writers,
M agent flows, and one web server
(`docs/prds/sci-execution-runtime/research/scheduling-design-2026-07-26.md:387-438`).

## 1. Dependency ledger

| Source read | Revision | Finding used |
|---|---|---|
| `reference-code/integrant/src/integrant/core.cljc` | `bcad6bcf35b62d3a32a453dc26b6d3a4d659dc01` | Complete 702 lines: composite/derived keys `:29-75`; `ref`/`refset` `:77-103`; dependency graph/order and private `dependent-keys` functions `:182-222`; ref resolution/build `:351-455`; lifecycle/assert multimethods `:472-548`; `expand` `:608-648`; `init`/`halt!`/`resume`/`suspend!` `:650-702`. |
| `reference-code/integrant/deps.edn:1-4` | same | Adoption adds Integrant plus `weavejester/dependency` 0.2.1. `rg -n integrant deps.edn src/` returned no hits at `d29138d1d`. |
| Archived Seon system, config schemas, Datahike component, hierarchy, and Aero reader | parent of `6c1079c8d` | Read complete via `git show 6c1079c8d^:{src/seon/system.clj,src/seon/system/config.clj,src/seon/db/datahike/system.clj,resources/integrant/hierarchy.edn,src/seon/config.clj}`. |
| Archived HTTP, Tailwind, Caddy, pool, and `resources/system.edn` | parent of `6c1079c8d` | Read via `git show 6c1079c8d^:<path>`; `git grep` at that parent enumerated every `ig/*-key` method. |
| Current process, host, writer, web, config, and database initialization owners | `d29138d1d` | All unqualified current file:line citations resolve at this commit: `script/seon/dev/process.clj:27-54,219-242,843-893`; `src/seon/host.clj:15-58`; `src/seon/db/server.clj:481-617`; `src/seon/web/server.clj:85-167,256-337`; `src/seon/config/resolve.cljc:953-975`; `src/seon/db/protocol.cljc:1894-2007`. |
| Datahike writer | vendored working tree | `create-writer :self` receives a connection and returns a `LocalWriter` with its own two queues and two threads (`reference-code/datahike/src/datahike/writer.cljc:286-306`). |
| Scheduling decision | `d29138d1d` | One dispatch substrate, platform-thread SCI constraint, and multi-cluster writer finding (`docs/prds/sci-execution-runtime/research/scheduling-design-2026-07-26.md:253-326,387-438`). |

The other relevant deletions were read as history: `314e3cafd` removed the orphaned Integrant-era nREPL entry point; `8dcf64c58` removed the submodule before it was restored as reference code.

## 2. Archive verdict

The hierarchy declared thirteen component keys (`git show 6c1079c8d^:resources/integrant/hierarchy.edn`):

| Archived key | Live analogue and verdict |
|---|---|
| `:seon.schema/registry` | Yes: `seon.schema/registered-schemas` (`src/seon/schema.cljc:1711-1737`). Restore as a process component. |
| `:seon.db.schema/consistency-check` | Behavior survives in schema/initialization admission, not as a separate runtime owner (`src/seon/db/protocol.cljc:1894-2007`). Do not restore the key. |
| `:seon.dev/nrepl` | Dev access survives as writer `io-prepl` plus the external MCP bridge (`src/seon/db/server.clj:460-479`; `script/seon/dev/mcp.clj:715-950`). Restore a process-wide dev endpoint, not the old entry point. |
| `:seon.web.server/http-server` | Yes: current http-kit web-render server (`src/seon/web/server.clj:256-337`). Restore the lifecycle shape, not the old routes/SSE implementation. |
| `:seon.web/tailwind` | Build/operator concern now (`script/seon/dev/artifact.clj:1174`). Do not put it in runtime boot. |
| `:seon.web/caddy` | No maintained runtime analogue. Do not restore. |
| `:seon.db/flow` | The live analogue is one Datahike `LocalWriter` per connection, not the archived namespace/core.async flow (`reference-code/datahike/src/datahike/writer.cljc:286-306`). Design fresh. |
| `:seon.flow/infrastructure` | Replaced by database interests plus the run driver (`src/seon/agent/driver.clj:813-888`). Do not restore. |
| `:seon.flow/pool` | Replaced by shared executors and the SCI semaphore (`src/seon/sci/eval.clj:33-56`). Do not restore. |
| `:seon.orchestrator/sessions` | Replaced by durable runs/receipts and replaceable compute (`docs/seon/architecture/agent-runtime.md:41-87,173-198`). Do not restore. |
| `:seon.graph/scanner` | Build/database program indexing owns this now (`docs/seon/architecture/agent-runtime.md:296-307`). Do not restore a boot scanner. |
| `:seon.dev/instrumentation` | Yes, but per-cluster and database-program-derived (`src/seon/instrument.cljc:926-1048`). |
| `:seon.ai.claude/sdk` | Hosted providers are database descriptor rows; there is no runtime SDK component. Do not restore. |

The strongest borrow is the one `ig/assert-key :seon/component` choke point (`git show 6c1079c8d^:src/seon/system.clj`), because Integrant resolves refs before assertion (`reference-code/integrant/src/integrant/core.cljc:424-431`). Keep schemas colocated in their owning namespaces and look them up by the derived component key; do **not** restore the archive's central schema map or its `:any` handle schemas (`git show 6c1079c8d^:src/seon/system/config.clj`).

Also borrow derived key families, declarative refs, reverse halt, and partial init. Do not borrow `current-flow`'s fallback atom, disabled `#_` methods, runtime Tailwind/Caddy, Datalevin-era flow/pool/session components, or Aero as a post-database configuration authority (`git show 6c1079c8d^:src/seon/db/datahike/system.clj`).

## 3. Adoption and line-count case

Integrant earns its dependency only in the topology merge. The merge makes at least 360 current first-party lines eligible for deletion:

- 58 lines of standalone host launcher (`src/seon/host.clj:1-58`);
- 137 lines of standalone writer composition/main (`src/seon/db/server.clj:481-617`); and
- 165 lines of web-render remote bootstrap plus standalone lifecycle/main (`src/seon/web/server.clj:85-167,256-337`).

The HTTP handlers, writer mechanics, driver, and database config projection remain. A root system, component methods, and generated cluster config should fit in roughly 140-180 first-party lines, leaving about 180-220 net deleted lines [UNVERIFIED until the implementation diff]. If the same series does not delete these standalone owners, reject the dependency: wrapping today's 58-line host in Integrant would be accretion, not simplification.

The outer process sort remains. It orders conditionally owned OS processes, readiness, generations, and durable process records (`script/seon/dev/process.clj:219-242,843-893`); an Integrant system map cannot replace that protocol. Integrant's `dependent-keys` is private (`reference-code/integrant/src/integrant/core.cljc:204-215`), and translating process specs into fake refs would add a second representation. Two sorts are justified because their nodes are disjoint: outer OS processes versus inner live JVM handles.

## 4. Component graph as data

The root system owns shared resources and one **composite cluster-system key** per selected cluster. The exact cluster set and stage-1 values are generated from the already-resolved boot manifest, never reread by a component (`script/seon/dev/config.clj:180-260`).

```clojure
{:seon.schema/registry {}
 :seon.runtime/stage-1
 {:seon.runtime/boot-config resolved-boot-config}
 :seon.scheduling/executors
 {:seon.runtime/stage-1 (ig/ref :seon.runtime/stage-1)}
 :seon.sci/eval-semaphore
 {:seon.runtime/stage-1 (ig/ref :seon.runtime/stage-1)}
 :seon.sci/base-ctx
 {:seon.schema/registry (ig/ref :seon.schema/registry)
  :seon.scheduling/executors (ig/ref :seon.scheduling/executors)}
 :seon.cluster/registry {}

 ;; Generated for every selected qualified cluster-id.
 [:seon.cluster/default :seon.cluster/system]
 {:seon.cluster/boot default-stage-1
  :seon.cluster/registry (ig/ref :seon.cluster/registry)
  :seon.schema/registry (ig/ref :seon.schema/registry)
  :seon.scheduling/executors (ig/ref :seon.scheduling/executors)
  :seon.sci/eval-semaphore (ig/ref :seon.sci/eval-semaphore)
  :seon.sci/base-ctx (ig/ref :seon.sci/base-ctx)}
 :seon.dev/nrepl
 {:seon.runtime/stage-1 (ig/ref :seon.runtime/stage-1)}
 :seon.web/http-server
 {:seon.runtime/stage-1 (ig/ref :seon.runtime/stage-1)
  :seon.cluster/systems (ig/refset :seon.cluster/system)
  :seon.cluster/registry (ig/ref :seon.cluster/registry)}
 :seon.dev/endpoints
 {:seon.dev/nrepl (ig/ref :seon.dev/nrepl)
  :seon.web/http-server (ig/ref :seon.web/http-server)
  :seon.cluster/systems (ig/refset :seon.cluster/system)}}
```

Each composite cluster key initializes this **nested Integrant system** from
one shared template:

```clojure
{:seon.db/writer
 {:seon.cluster/stage-1 cluster-stage-1}
 :seon.db/initialization
 {:seon.db/writer (ig/ref :seon.db/writer)
  :seon.cluster/initialization-pages initialization-pages
  :seon.cluster/config-desired config-desired}
 :seon.config/database
 {:seon.db/writer (ig/ref :seon.db/writer)
  :seon.db/initialization (ig/ref :seon.db/initialization)}
 :seon.db/interest
 {:seon.db/writer (ig/ref :seon.db/writer)
  :seon.config/database (ig/ref :seon.config/database)}
 :seon.sci/program
 {:seon.db/writer (ig/ref :seon.db/writer)
  :seon.config/database (ig/ref :seon.config/database)
  :seon.sci/base-ctx shared-base-ctx}
 :seon.instrument/runtime
 {:seon.sci/program (ig/ref :seon.sci/program)
  :seon.config/database (ig/ref :seon.config/database)}
 :seon.ai/transport
 {:seon.config/database (ig/ref :seon.config/database)}
 :seon.agent/run-driver
 {:seon.db/writer (ig/ref :seon.db/writer)
  :seon.db/interest (ig/ref :seon.db/interest)
  :seon.sci/program (ig/ref :seon.sci/program)
  :seon.instrument/runtime (ig/ref :seon.instrument/runtime)
  :seon.ai/transport (ig/ref :seon.ai/transport)
  :seon.sci/eval-semaphore shared-eval-semaphore}
 :seon.web/cluster-view
 {:seon.db/writer (ig/ref :seon.db/writer)
  :seon.db/interest (ig/ref :seon.db/interest)
  :seon.config/database (ig/ref :seon.config/database)}
 :seon.dev/io-prepl
 {:seon.db/writer (ig/ref :seon.db/writer)
  :seon.config/database (ig/ref :seon.config/database)}}
```

Stage 1 supplies only code/schema discovery, cluster/store identity, backend
and path, socket/port/file ownership, artifact identity and initialization
pages, executor capacity, and Datahike
`transaction-queue-size`/`commit-queue-size`/`commit-wait-time`. The last three
must be present before `d/connect`, because `create-writer :self` consumes them
while constructing the connection's `LocalWriter`
(`reference-code/datahike/src/datahike/writer.cljc:286-306`;
`src/seon/db/registry.clj:533,587,777`).

`:seon.db/initialization` is the sole bridge: it reconciles the manifest's
desired config singleton under boot/config provenance, then returns only after
the initialization phases commit
(`src/seon/config/resolve.cljc:953-975`;
`src/seon/db/protocol.cljc:1894-2007`). Every later component reads the
ordinary singleton returned by `:seon.config/database`: reactive policy,
provider descriptors, run/lease/SCI limits, instrumentation policy, and
web-render behavior. The http-kit bind/port is stage 1, but its behavior and
per-cluster views are stage 2; it starts after every initial cluster system.

Nested halt order is the reverse graph: `io-prepl` and `cluster-view`, then
`run-driver`, AI/instrumentation/program, interest, database-config and
initialization values, and finally the one writer connection. Driver halt must
remove interests and lease wakes before the writer closes
(`src/seon/agent/driver.clj:813-888`). Root halt is endpoints → http-kit →
cluster systems → nREPL → shared SCI/scheduling/schema resources.

## 5. Multiplicity and one-cluster halt

Do **not** build one flat `refset` system of all writers. `refset` resolves all
derived instances as a set (`reference-code/integrant/src/integrant/core.cljc:92-99`),
and `halt!` on a key walks its transitive dependents in reverse
(`reference-code/integrant/src/integrant/core.cljc:213-215,383-397,660-666`).
A shared semaphore or web server depending on all cluster keys would therefore
make one selected writer halt pull shared resources into the stop set.

Use one root system plus N nested systems. The root composite key
`[cluster-id :seon.cluster/system]` is a stable slot registered in
`:seon.cluster/registry`; the HTTP handler resolves the current slot rather
than capturing a connection. Reset marks only that slot unavailable, calls
`ig/halt!` on **that nested system map**, creates/applies the reset database,
calls `ig/init` on the same generated template, then replaces the slot. Other
nested systems, shared executors, nREPL, and http-kit stay live; requests for
the resetting cluster return unavailable while other clusters continue.
Full process shutdown alone calls `ig/halt!` on the root system.

This preserves exactly one write connection per store while allowing N stores
in one process. It does not preserve process fault isolation: one JVM OOM still
takes every co-hosted cluster down
(`docs/prds/sci-execution-runtime/research/scheduling-design-2026-07-26.md:399-423`).

Do not add custom `suspend-key!`/`resume-key` methods initially. Integrant's
defaults are halt and init (`reference-code/integrant/src/integrant/core.cljc:503-523`);
custom archive methods created a second lifecycle based on config equality
(`git show 6c1079c8d^:src/seon/system.clj`). Late-bound
handler Vars and nested cluster reset already provide the fast REPL loop. Add a
custom resume only after a measured restart cost names one resource that cannot
be made cheap.

## 6. What Integrant does not solve

- It orders synchronous init/halt; `init-key` returning means ready. There is
  no readiness event, health check, watcher, or restart policy in the complete
  core source (`reference-code/integrant/src/integrant/core.cljc:430-548,650-702`).
- It does not make a writer, interest callback, run driver, SCI eval, or
  http-kit handler crash-proof.
- It does not bound heap or reclaim a wedged platform thread. SCI remains on a
  platform `:compute` thread under the one time-based `:interrupt-fn`; process
  replacement remains the only stronger containment
  (`src/seon/sci/eval.clj:82-139`).
- It does not replace run process/epoch CAS, leases, receipts, or database
  interests (`docs/seon/architecture/agent-runtime.md:59-87,173-198`).
- It does not make lease expiry observable: Datahike `listen` fires on a
  transaction, not the passage of time
  (`reference-code/datahike/src/datahike/api/specification.cljc:1066-1094`).
- It does not automatically roll back partial init. A build exception carries
  the partial `:system`; the root launcher must extract and halt it
  (`reference-code/integrant/src/integrant/core.cljc:409-421`).

The biggest adoption risk is an incorrect root edge turning per-cluster reset
into cross-cluster halt. Nested systems are therefore an acceptance condition,
not an implementation preference.

## 7. Could not settle

- The 140-180-line replacement estimate and 180-220 net deletion are
  `[UNVERIFIED]` until the topology merge diff exists.
- Whether one co-hosted cluster's heap failure gives acceptable recovery time
  to its siblings remains unmeasured; the required two-cluster OOM experiment
  is specified in the scheduling design
  (`docs/prds/sci-execution-runtime/research/scheduling-design-2026-07-26.md:783-799`).
- The exact process-wide executor/eval capacity keys owned by the concurrent
  writer-dials lane were not settled here; they must remain stage 1 if consumed
  during `d/connect`, and no source owned by that lane was changed.
