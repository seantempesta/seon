---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, ui, datastar, rendering]
---

# Web UI

The web UI renders the same database facts and evaluation history the agent uses.
Entity schemas select their render pairs; routes select the subject and custody.
[B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) owns the target walk and
whole-view delivery change. Existing package/revision delivery remains until its
replacement proves first paint, freshness, slow-consumer behavior and cleanup.

## 1. One walk, explicit output

A block is an entity rendered through its schema's AI/HTML pair. Scalars remain in
the entity's block; components and declared derived queries supply related blocks.
When no pair exists, the default attribute-map printer is total. Renderer selection
comes from the program facts and contracts, not a surface-specific registry.

The walk takes a requested output. Evaluation history uses `seon.repl`'s pair:
AI renders saved shown text; HTML renders a retained live result or saved text when
the object is unavailable. The current history walk requests AI at
`src/seon/render/walk.clj:1005`; B2 makes that choice explicit and removes the
parallel history composition only after equivalence is proven.

Only AI render functions and the value renderer apply presentation limits under
the supplied render profile. Historical shown text is never clipped again. HTML
has no presentation clipping. A query-work bound can still stop an expensive
read, but it yields an explicit elision/refusal naming that bound. Datahike's current
many-pull default of 1,000 is not complete HTML data: A2's complete-read contract
must replace silent truncation or report the cut honestly.

Namespace AI rendering is bounded content or an elision naming the omitted
definitions and `(dir ns)` requery. Namespace HTML renders the complete requested
view under query-work admission. A generalized agent-authored canvas remains a
separate contract; ordinary namespace pages do not imply arbitrary form/control
execution semantics.

## 2. Whole-view delivery

The target subscribes before first paint. A refresh notification is payload-free:
the tab reads one current database value and derives its requested view. It may skip
rendering only when its retained read evidence is current and no code/private-object
notification invalidates the result. Database revisions alone cannot detect a new
loaded function or mutated private result.

A dropping-1 tap coalesces pending notifications. Rendering and encoding run on the
compute executor; IO owns waits and socket sends. One patch sends the whole view.
The HTTP connection's drain-or-close completion bounds pending bytes and remains
part of the design. Compression and an SDK send queue are not backpressure. A slow
tab delays its own delivery, and disconnect closes its tap and releases resources.

The source model is `reference-code/hyperlith/src/hyperlith/impl/datastar.clj:122-181`:
subscribe, initial render, compute dispatch and disconnect cleanup. The SDK buffers
sends; http-kit's maintained `write-state` seam supplies the actual drain completion
(`reference-code/http-kit/src/org/httpkit/server.clj:321-326`). Seon's current wait
is in `src/seon/render/web.clj:2704-2757` and must not disappear with package machinery.

Per-tab rendering costs tabs × full view, including selected history. Current shared
derivation can be cheaper. B2 measures tabs, history size, returned/visited work,
encoding and pending bytes before choosing shared or per-tab derivation. No new
memoization cache or bandwidth claim is assumed. Brotli uses the SDK profile when
host dependencies resolve; the implementation records that proof or presents the
supported fallback choices.

## 3. Routes and task context

The existing route owner is `src/seon/render/route.clj`. Namespace and agent views
receive explicit cluster custody, render profile and subject. The namespace page
shows its declarations and responsible agents; B3's target `:seon.ns/agents` is
many-to-many, independent of an agent's current REPL namespace. Assignment comes
from tasks, so responsibility does not imply a separate dispatcher.

An agent view exposes its record, tasks, messages and ordered evaluations through
the same facts. No fixed-size pull is claimed to contain every related row.
Task completion, unavailable test evidence, budget exhaustion and stopped/failed
graphs are visible states, not disappearing sections interpreted as success.
Error presentation composes the base and domain schema blocks, preserving declared
evidence under B3's durability decision.

Candidate addresses remain cluster-qualified. The shared task names its candidate;
messages and replies use that candidate's existing owner. D1 retains history and
run evidence before retiring a branch. The UI must not infer that a candidate-local
green resolved the shared task or merged source.

## 4. Controls and proof

Debug controls submit through the ordinary turn/evaluation owner with explicit
custody and the same serial permit. A preview can render an as-of value or stored
shown text; it does not replay historical writes/effects. Result handles refer to
actual in-memory objects, and report unavailability after restart.

Acceptance requires observed initial paint, two tabs converging after a burst,
code/private changes invalidating a view without unrelated datoms, a slow client
with bounded pending bytes, disconnect cleanup and a live ordinary turn. An HTTP
200, publication commit or empty error query alone does not establish these facts.

[B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) owns these proofs;
[A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md) owns complete reads;
[B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md) owns task/error data.
