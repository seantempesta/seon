---
type: research
status: complete
tags: [research, database, flow, agent]
---

# Compiled child prompt owner — 2026-07-16

## Decision

The supervised per-agent Bun child is the one asynchronous prompt owner. It
discovers the agent's actual database-owned blocks at one coordinate, acquires
the data required by those blocks through the stable asynchronous `seon.db`
functions, runs authored functions in the same isolated child when present,
and returns ordinary rendered blocks and text. The parent turn and debug owners
await that one result. The existing recursive renderer remains synchronous over
ordinary values.

This replaces the earlier fixed 13-member ordinary-agent / 16-member root
proposal. Those counts describe the current seed, not the context interface.
Stored blocks, profiles, namespace edges, derived render functions, optional
menus/typeahead, canvas selection, root patches, and authored slots make the
real graph data-dependent.

## Dependency ledger

| Owner | Selected revision | Constraint used |
|---|---|---|
| Seon | `5271a65f` before the trusted-call implementation; implementation `ecead888` | `seon.db` is the application database API; the execution child already owns the direct session, coordinate context, deadline, cancellation, output bound, and process isolation. |
| Bun | `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | One supervised OS process per active agent supplies CPU and failure isolation. |
| Shadow CLJS | `4e72595f57618f5c43388ad13d5136cd3bede566` | The node-script target derives one module from `:main`; ordinary namespace requires define the production closure. |
| ClojureScript | `946d75f3483c0c8e784e6668bff2c71a25619a77` | Core `^:async` functions and returned Promises are the honest nonblocking boundary. |

Key first-party seams are `src/seon/execution.cljs`,
`src/seon/execution/host.cljs`, `src/seon/db.cljs:680-706`,
`src/seon/agent/ctx.cljs:2317-2654`, and
`src/seon/agent/turn.cljs:304-318`.

## Why the fixed batch was rejected

The current manifest happens to seed four ordinary prompt blocks:
namespaces, canvas, plan, and transcript. Root adds literal role content plus
core-fault, instrumentation-gap, and orphaned-agent blocks. That is not a
closed list:

- an agent's complete `:seon.agent/ctx` vector is database data;
- a profile supplies an ordered subset with patches and disables derived
  blocks;
- current namespace and require edges determine derived program surfaces;
- installed optional menu and typeahead blocks add other reads;
- authored functions can perform open-ended asynchronous `seon.db` work; and
- downstream artifacts can ship other compiled block functions.

A fixed member vector would therefore become either a stale seed catalog or a
request-key/result replay table. The latter is a remote Datahike emulation and
fails for dynamic branching. Synchronous IPC or `Atomics` would instead block
Bun's event loop. Neither is an acceptable compatibility seam.

## Selected integration points

### Stable database facade

Application and authored code keep the existing `seon.db` operation names.
Only that namespace owns the persistent session, wire protocol, coordinate
selection, cancellation, and ordinary response decoding. Datahike database
values, entities, indexes, history wrappers, and query caches remain on the
JVM.

The prompt entrypoint inherits its exact coordinate through the existing
`db/with-tx-context` scope. Every nested `seon.db` read therefore uses the same
immutable point without repeating head resolution.

### One trusted compiled entrypoint

Commit `ecead888` changes the existing execution protocol to version 2. Its
single `::function-identity` is a closed union:

- authored function symbol plus exact source digest; or
- the one fixed compiled prompt symbol plus exact runtime artifact digest.

The host's `invoke-compiled!` does not accept a function symbol. The child
checks the fixed symbol and startup-verified artifact digest before opening a
database session. This avoids both a second invocation mechanism and a false
namespace-based distinction between authored and compiled code. Downstream
compiled namespaces are not classified by their prefix, and a previously
loaded authored global cannot be relabeled as compiled.

Compiled dispatch deliberately performs zero authored-program reads. Prompt
discovery first determines whether an authored slot exists; only then does the
child reuse the one canonical authored-program acquisition/compiler cache at
the same coordinate. This avoids repeatedly sending global schema and function
contract rows for prompts containing only compiled blocks.

### Compiler-owned artifact closure

The execution artifact needs a thin `seon.execution.runtime` composition root.
Its `-main` delegates to `seon.execution/-main` and statically requires the
prompt entrypoint plus every shipped symbol-wired block namespace. Both default
and ACME execution builds point their existing `:main` at that root.

This is the idiomatic Shadow seam. The pinned node-script target accepts a main
namespace and derives its sole module entry from it. Development preloads are
not a production reachability contract, and manual module or file lists would
duplicate the compiler's dependency graph. Keeping execution and context as
sibling dependencies of the composition root also avoids an
execution-to-context cycle.

## Prompt execution sequence

1. The parent captures coordinate `C` and calls the fixed compiled entrypoint
   through the existing supervised invocation.
2. The child opens or reuses its direct database session and binds `C` in the
   existing asynchronous database context.
3. One bounded discovery acquisition returns the agent, stored blocks,
   profile input, current namespace, and metadata required to derive blocks.
4. Literal slots are already complete. Built-in AI slots start their
   asynchronous acquisitions concurrently. Each block owner may use one
   coarse `execute-many` for its own independent reads.
5. Authored AI slots are handled sequentially. On the first such slot in a
   fresh child, the existing program acquisition and compiler state load that
   exact reachable target at `C`; later slots reuse the same state and program
   digest.
6. Resolved AI values replace their function slots with ordinary strings or
   error values. The existing synchronous omission, cap, ordering, bracket,
   cache-boundary, and token formatting code assembles the prompt.
7. The child returns bounded ordinary rendered blocks and text. A timeout or
   cancellation closes its database session, poisons the child, and leaves the
   parent, siblings, and JVM alive.

The first implementation should not centralize every built-in read into one
global member catalog. Four concurrent per-block requests cost more framing
than one aggregate request, but preserve ownership and data-driven extension.
Each block can already batch its internal reads, while Datahike shares indexes,
completed query results, and identical in-flight computation at `C`. Measure
exact frames and encoded bytes before introducing cross-block composition.

## Source migration boundaries

The existing pure tail stays in place:

- `agent-blocks` sorting and profile merge;
- block omission and token caps;
- block ordering and brackets;
- stable/volatile prompt boundary; and
- generic string/Hiccup rendering and validation.

The database-reading heads move to asynchronous acquisition in their current
owners:

- namespaces and derived render functions;
- canvas selection/source;
- plan position/frontier/recent completion/escalation;
- transcript turns/evals/messages;
- root faults, instrumentation gaps, and orphaned agents; and
- optional menu/typeahead blocks when installed.

Menu prompt text and provider offers must consume the same acquired function
projection so their glyphs cannot drift. Human-only HTML surfaces are not
computed by the prompt entrypoint. Root and optional built-in functions must be
migrated before atomic cutover, but they do not enlarge the first default-agent
vertical slice.

## Performance and resilience implications

- Database work remains genuinely parallel on the JVM's bounded read workers;
  Bun Promise concurrency alone is not counted as CPU parallelism.
- Different agent children perform formatting and authored execution on
  different OS threads/cores, removing the pod's single event-loop CPU gate.
- Authored slots stay sequential inside one child because compiler globals and
  user effects are shared there; cross-agent children provide safe parallelism.
- One parent/child hop exists while the parent still owns the turn. It
  disappears for agent turns when the rest of turn execution moves into the
  same child; the debug web UI retains one supervised hop.
- No database value, Promise, function, or transport object crosses IPC.
- The outer deadline is also the runaway-loop policy. SCI is not retained as a
  second execution mechanism after process-isolated parity is proven.

## Short falsifiers and graduation evidence

1. A fresh child renders a never-before-called authored derived block on its
   first prompt at `C`.
2. Default prompt and autocomplete profile are byte-identical to their current
   results at the same database point.
3. A dynamically installed literal block and a dynamically authored block work
   without changing a seed-count or block-name catalog.
4. Every nested database request carries `C`; no head resolution or local
   Datahike value appears in the child.
5. Namespaces and transcript stay within `execute-many`, frame, result, and
   deadline bounds under large fixtures.
6. One prompt cancellation releases its outstanding database work and retires
   only that child.
7. Root and optional blocks fail locally as ordinary block errors; one bad
   block does not erase sibling prompt content.
8. Record per-block request count, exact encoded bytes, queue/run time, query
   cache/single-flight evidence, Bun CPU/RSS, and end-to-end cold/warm latency
   before deciding whether cross-block aggregation is worth its interface cost.
