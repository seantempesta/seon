---
type: research
status: complete
tags: [research, runtime, web, architecture]
---

# Execution-child value-sampling boundary (2026-07-20)

## Decision

The Stage 1.5 child transport is locally blocked on the Unit 1B drill and
configuration contract. The program is not blocked: schema projection,
generic rendering, route preparation, and unrelated roadmap work may continue
in dependency order.

The missing prerequisite is concrete. The current `render-html-data` accepts
only a configuration, eval id, and whole value and returns `eval-id`, summary,
truncation, and tree fields. The sampler has no path or offset input. Existing
configuration bounds depth, keys, items, strings, and shape samples, but does
not define maximum path length, maximum offset, page size, or a total
realization-work cap. Implementing transport now would force the execution
owner to invent a consumer and configuration contract.

Unit 1B must first freeze the ordinary drill request and projection, including
the rule that offset plus page work stays within one explicit total-work
bound. Only then should the execution-child transport implement those shapes.

## Dependency ledger

| Dependency | Selected revision | Grounding | Existing Seon boundary |
|---|---|---|---|
| Bun | `d8ecf098572e2b8265b23e40c04efb4067e516cc` | `reference-code/bun/docs/runtime/child-process.mdx:232-284` documents bidirectional subprocess IPC and structured cloning | `src/seon/execution.cljs:176-198` deliberately narrows Bun IPC to Transit strings containing eager ordinary data; native clone identity is not authority |
| Transit CLJS | `3d8a2c49ff1911fd7adfacce2776c3a6b8cc1fce` (`com.cognitect/transit-cljs` `0.8.280`) | `reference-code/transit-cljs/`; dependency selected in `deps.edn` | `src/seon/execution.cljs:162-198` owns the single writer, reader, encode, and decode boundary |
| Orchard | `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `reference-code/orchard/src/orchard/inspect.clj:44,96-141,150-200` grounds head-plus-one paging and path descent | `src/seon/render/value.cljs:363-463` owns Seon's bounded sampler; no second inspector state belongs in execution |
| ClojureScript | `946d75f3483c0c8e784e6668bff2c71a25619a77` | `reference-code/clojurescript/` grounds the self-host runtime and process-local JavaScript values | `src/seon/eval.cljs:1522-1581` owns child-local `lookup-result` and its `globalThis.result` slot |

`seon.db.protocol/ordinary-wire-value?` is the maintained wire predicate.
It accepts eager ordinary scalars and collections, Transit values, and
database values while rejecting functions, lazy sequences, records that own
host behavior, atoms, delays, Promises, Errors, and JavaScript host objects.
Every new execution frame must satisfy it before encoding and after decoding.

## Current facts

The execution protocol is version 3. Parent messages are closed
`invoke`, `cancel`, and `shutdown` maps. Child messages are closed `ready`,
`result`, `error`, and `stopped` maps. Compiled invocation is the only current
work request, and its result schema deliberately permits arbitrary values
before the terminal byte and ordinary-data checks.

The host serializes invocations per agent. Settlement is guarded by agent,
host generation, child id, invocation id, current artifact digest, immutable
database value, and optional run fence. Child exit settles an active request
with `:seon.execution/child-retired? true`; stale generations cannot claim a
current request.

`lookup-result` reads the owning execution child's bounded
`globalThis.result` slot. It returns the live value or an error value that
distinguishes a nonexistent eval, an eval that errored, and a slot evicted or
owned by a prior process. The last case already directs the reader to rerun
the recorded source. It never throws a miss into the agent loop.

These facts rule out three tempting designs: the parent cannot dereference a
child value, sampling cannot masquerade as a compiled authored function, and
the browser cannot persist arbitrary values to conceal retirement.

## Recommended closed frames

After Unit 1B freezes the named schemas, extend the existing protocol with a
specific read request and response rather than widening `/call`:

```clojure
{:seon.execution/message :seon.execution.message/value-sample
 :seon.execution/protocol-version 3
 :seon.execution/agent-id "agent-id"
 :seon.execution/request-id "correlation-id"
 :seon.execution/eval-id "eval-id"
 :seon.render.value/path [...]
 :seon.render.value/offset 0
 :seon.render.value/sampling-limits {...}}

```

```clojure
{:seon.execution/message :seon.execution.message/value-sample-result
 :seon.execution/protocol-version 3
 :seon.execution/agent-id "agent-id"
 :seon.execution/request-id "correlation-id"
 :seon.render.value/projection {...}}

```

Both are closed Malli maps. The result projection is the frozen Unit 1B
ordinary browser projection, never `:any`, a raw result, a Promise, a database
handle, or an object shared by identity. Failure uses a distinct correlated
sample-error frame, or a deliberately generalized correlated error frame,
whose payload remains the existing closed `:seon.error` value.

The parent validates and caps every request from the selected configuration.
The child repeats closed-schema, ordinary-data, and work-bound validation as a
defense at the authority boundary. It awaits `lookup-result`, descends and
samples only inside the child, and returns only the eager ordinary projection.
An evicted result is rendered through the same projection from the existing
honest error value.

## Addressability and lifecycle laws

- Sampling addresses only the currently retained child for the trusted agent
  id. Absence is an unavailable result; it must not call the current
  `ensure-entry!` path, because a newly spawned child cannot own the prior
  process's result slot.
- A request is correlated by request id, agent id, host generation, child id,
  and artifact digest. A stale response cannot settle a current request.
- Retirement, restart, or exit while sampling settles unavailable with
  `:seon.execution/child-retired? true`. Sampling never retries on a fresh
  child.
- Sampling shares the existing per-agent ordering mechanism. It cannot
  overtake an active invocation or introduce a second mutable active-request
  registry.
- Cancel, shutdown, timeout, and malformed-frame paths settle exactly once.
  No path leaves a queued Promise or retained child entry wedged.
- The parent never calls `lookup-result`; the child never returns the raw live
  value.

## Route ownership and authorization

Before any host call, `/agent/{id}/value` must join the requested
`:seon.eval/id` through `:seon.eval/agent` to the route's
`[:seon.agent/id id]`. A missing owner or a different owner is a refusal, and
the host-send count must remain zero. The handler passes the route-authorized
agent id as trusted data; it does not accept an agent id from the query or
request body.

Recorded `:seon.eval/source` supplies the recomputation affordance after an
eviction or prior process. It is neither authorization nor persisted value
state. Entity drill remains parent-owned over an acquired immutable database
value; eval drill remains child-owned. They share the Unit 1B projection, not
an umbrella value store.

## Unit 1B decisions required before transport

Unit 1B must name and test all of the following:

1. Maximum path-element count and the configuration attribute that owns it.
2. The legal path-element domain and HTTP codec. The broad ordinary-wire
   predicate also accepts dates, binary, URIs, and tagged values, so it is not
   by itself an adequate route grammar.
3. Maximum offset, page size, and their configuration attributes.
4. A hard total realization-work bound with `offset + page-size` inside that
   bound, including the one overflow sentinel.
5. Child behavior when the requested offset exceeds that bound.
6. Whether and how maps page deterministically. The current map sampler ranks
   one bounded candidate window; it does not expose offset paging.
7. The exact projection for a drilled path, including path, offset,
   truncation, schema status, and honest errors.

The existing `value-max-items` default cannot silently become the total
offset budget: with a page size equal to that value, page two would always be
illegal. A separate bound or an explicit changed meaning is therefore a real
contract decision, not transport detail.

## Ownership and dependency order

After Unit 1B freezes the drill/config contract, the child transport owner may
change only:

- `src/seon/execution.cljs` for closed frames and child dispatch;
- `src/seon/execution/host.cljs` for current-child addressability, ordering,
  retirement, and settlement;
- the minimum `src/seon/eval.cljs` exposure needed to call the existing
  `lookup-result`; and
- focused execution, host, process, and eval-result tests.

The route owner separately owns router/debug handlers, parsing,
authorization, and route-host seam tests. The generic-rendering owner owns the
Unit 1B request/projection schemas and sampler behavior. The top-level owner
integrates their direct translation and live proof.

Execution and host files overlap active lifecycle and host-tier mechanisms.
Their transport edit requires an explicit path handoff and coordinated source
freeze; no concurrent lane should edit either mechanism. Turn, AI, and retry
paths remain protected and unrelated.

## Shortest falsifiers

1. Transit round-trip every new frame; reject unknown keys, lazy sequences,
   Promises, host objects, and nonordinary projection leaves.
2. Page an infinite or counter-bearing sequence at offsets zero and one;
   touches stay within offset plus page size plus the overflow sentinel.
3. Reject excessive offset and path length in the parent before child send,
   and reject the same malformed frame independently in the child.
4. Request agent A's eval through agent B's route and prove zero host sends.
5. Request a value with no retained child and prove no process spawn.
6. Retire or replace the child mid-request and prove one honest unavailable
   result, `child-retired?`, no retry, and no stale-generation settlement.
7. Evict a live result and require the existing prior-process/eviction message
   plus the recorded-source recomputation affordance; keep nonexistent and
   errored eval messages distinct.
8. Prove the response contains only the bounded ordinary projection and stays
   under the protocol frame ceiling.
9. Queue sampling behind an active invocation and prove ordering, cancellation,
   shutdown, and timeout settle every request exactly once.
10. Drill a path into a process-local object and prove the child resolves the
    live identity while a parent `lookup-result` spy remains untouched.
