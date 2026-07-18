---
type: research
status: complete
tags: [web, research, cljs]
---

# Datastar fanout, rendering, and backpressure evidence

## Conclusion

The one Datastar mechanism correctly suppresses irrelevant database changes,
shares one complete render and serialized event across equivalent clients, and
retains only the newest event under socket pressure. Client count is not the
current rendering bottleneck.

The material latency is cold acquisition of the agent's Bun execution child.
A root render after the 30-second child idle retirement takes about 1.35--1.45
seconds. The same complete render through a warm child takes about 28 ms. This
is an intentional memory/latency tradeoff, not evidence for a second renderer
or feed.

## Dependency and mechanism ledger

- Bun direct-stream response and `flush(true)` behavior:
  `src/seon/web/datastar.cljs` and the selected patched Bun runtime at
  `reference-code/bun` revision `d8ecf0985`.
- Complete agent projection:
  `seon.execution.runtime/render-agent-view!` in
  `src/seon/execution/runtime.cljs`.
- Execution-child supervision and 30-second idle retirement:
  `src/seon/execution/host.cljs`.
- Pure Hiccup assembly: `src/seon/ui/agent_view.cljs`.
- Datahike interest and changed-attribute filtering:
  `src/seon/web/datastar.cljs` and the authority interest in `seon.db`.

## Live method

The default cluster was ready at its dynamic loopback port. Server-side clients
opened `/agent/root/feed` with distinct validated `view` values, so they owned
independent sockets while normalizing to the same
`[:seon.web.feed/agent "root"]` subscription.

`seon.web.datastar/reset-performance!` and `performance-snapshot` supplied the
existing bounded process-local counters. No benchmark runtime or telemetry
store was added. Controlled transaction callbacks reused the subscription's
captured immutable database value and supplied either an unrelated attribute or
the learned dependency `:seon.render.surface/touch`; this isolates subscription
selection and rendering without persisting benchmark datoms.

For backpressure, one raw TCP client sent a valid feed request and deliberately
did not read the response. The existing `push-event!` owner received the same
one-megabyte event twenty times. This directly exercises Bun's negative-write,
`flush(true)`, latest-pending-event, and drain behavior without creating a
second socket path.

## Results

### Equivalent-client fanout

- 16 open sockets normalized to one subscription.
- The cold render produced one 27,185-byte serialized event.
- That one event recorded fanout 16 and 16 accepted writes.
- No client owned an independent render or serialization.

### Changed-attribute selection

- An unrelated attribute produced zero render requests.
- `:seon.render.surface/touch`, one of the learned root-view dependencies,
  produced exactly one affected subscription and one render.
- The relevant render still fanned out once, independent of client count.

### Cold and warm render latency

- Repeated cold complete renders after idle retirement measured approximately
  1,351--1,444 ms.
- A relevant update issued immediately after child use measured 27.879 ms.
- The complete serialized root event was 27,185 bytes in both cases.

Source inspection explains the discontinuity. `seon.execution.host` retains an
idle child for 30 seconds. After retirement, `render-agent-view!` must spawn the
digest-verified Bun process, open its database session, prepare its current
program state, and invoke the compiled renderer. A warm child performs only the
ordinary invocation and database/render work.

### Backpressure

- The first large write entered backpressure.
- One newest event occupied the pending slot; 18 later events replaced obsolete
  pending values rather than extending a queue.
- Two drains completed in 2.692 ms total, with 2.465 ms maximum.
- After drain, the connection had no pending event and was not marked draining.

## Decision implications

Do not add per-client rendering, a second feed, a second renderer, or another
buffer. The normalized subscription, learned attribute set, one serialized
event, and Bun direct-stream latest-value pressure policy are working.

Do not keep every execution child alive merely to improve UI latency. Current
measured retained physical footprint is roughly 170--220 MiB per child. The
next test should compare real visible-page behavior under three policies using
the existing supervisor owner:

1. the current 30-second idle retirement;
2. an open live subscription retaining only its agent's existing child; and
3. a longer measured idle timeout without feed coupling.

The acceptance evidence is actual interaction latency, number of retained
children, physical footprint, and clean retirement after the final relevant
feed closes. Keep the simplest policy that feels responsive on ordinary agent
and root journeys while remaining inside the 1/2/4-child memory budgets.

Browser morph timing remains a separate missing sample. It should use the same
complete event and real page, not infer browser cost from server completion.

## Real Chrome follow-up

A headless installed Google Chrome loaded the actual root shim, Datastar
JavaScript, and long-lived feed. The cold page reached a populated `#app-view`
in 1,466.8 ms with no console errors, matching the server's cold-child sample.

A real `POST /agents` changed both database data and the shared program by
adding the new agent namespace. Chrome observed the updated 14-agent view at
1,969.4 ms. The server recorded one 28,117-byte event and 1,388.6 ms rendering;
the structural coalescer accounts for approximately 300 ms. Source inspection
and the subsequent warm sample establish that this was current-program
reacquisition, not browser morph cost or per-client rendering.

Two root-canvas writes then isolated data-only behavior. The first arrived
after idle retirement and recorded 1,262.3 ms server rendering. A second write
issued immediately afterward recorded 19.228 ms server rendering, and Chrome's
first DOM mutation occurred 709.3 ms after the external measurement was armed.
That browser figure is a conservative end-to-end upper bound because it
includes the cross-tool transaction request plus the 300 ms structural settle;
the browser-specific morph is not separately instrumented.

These samples strengthen the existing owner rather than suggesting another
renderer: ordinary warm rendering is small, while idle retirement and program
reacquisition dominate visible latency.

## Selected retention experiment

The first policy cut changes the one execution-host idle default from 30
seconds to five minutes. This is long enough to cover ordinary reading,
thinking, and interactive work without tying child lifetime to browser sockets.
It still reclaims inactive process memory deterministically and retains the
existing immediate explicit stop path. Focused host proof asserts the selected
default and preserves lazy spawn, reuse, reconfiguration, cancellation, and
shutdown behavior.

The five-minute value is not a permanent universal constant. Final load proof
must report retained child count and physical footprint during realistic
one-off and specialist work. A small configurable pool of ordinary generated
agents, proactively current with the shared program, remains a separate
measured design candidate for instant assignment.
