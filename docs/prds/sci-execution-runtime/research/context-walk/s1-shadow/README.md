---
type: research
status: active
tags: [research, agent, context, render, experiment]
---

# S1 shadow render

## Method

No model consumed these outputs. For each S0 capture, the script takes the
final scratch database and calls `d/as-of` at the capture's
`:seon.context.capture/basis-t`. The block side is the exact prompt captured
from that database value; the walk side derives from that same immutable
database value. The comparison therefore does not mix a historical block
prompt with current walk facts.

The shadow is one experimental rendering of the agent entity:

1. retain the static execution scaffold;
2. render agent identity and its namespace as bounded structural data;
3. render the agent namespace as reader-validated Clojure statements, walking
   persisted require edges dependency-first to distance 2;
4. derive chat versus goal-seeking mode from the trigger's current shape;
5. render chat, routed-problem, and error-wake messages as different ordinary
   data shapes, following `about` to the problem or error; and
6. use `seon.render.value/render-ai` as the universal structural floor.

The script reads but does not modify `seon.render.walk`. Its immutable
distance, persisted-ref, dependency-first, and bounded-floor disciplines shape
the experimental renderer. No production source changed.

## Sizes

| Case | Block estimated tokens | Walk estimated tokens | Difference |
|---|---:|---:|---:|
| helper chat | 331 | 214 | -117 |
| root chat | 41 | 211 | +170 |
| helper routed problem | 533 | 253 | -280 |
| root routed problem | 41 | 249 | +208 |
| helper error wake | 829 | 306 | -523 |
| root error wake | 41 | 308 | +267 |

The helper reductions are not an end-state efficiency claim: S1 has no
transcript, while the block prompt includes accumulated history. Conversely,
root grows because the shadow finally includes what woke it.

## What the walk version shows that blocks do not

- Root sees its actual chat request, routed problem, or error wake. The block
  baseline exposes only fleet process status for all three.
- Both agents see explicit identity, namespace, trigger shape, and mode as
  ordinary data rather than reconstructing those facts from several prose
  paragraphs.
- A routed problem preserves sender, recipient, problem identity, and content
  together. An error wake preserves error identity, kind, message, and the
  full explanation content together.
- The namespace section is executable reader-valid Clojure syntax. Even the
  empty current namespaces say the honest thing, `(ns my.agents.helper)` or
  `(ns my.agents.root)`, instead of presenting a map-shaped namespace card as
  code.

## What it loses

- Helper loses the concrete peer-send grammar and the exact
  `my.message/decline` form. The routed facts are clearer, but facts alone do
  not teach the only valid response shape.
- Helper loses prior paused-run notes, settlement, older messages, and error
  neighbourhood facts. S3 is explicitly responsible for the transcript; S1
  must not pretend this omission is a win.
- Root loses its fleet-oversight sentence. A general agent walk needs a
  root-scoped full-system neighbour or renderer; otherwise the new context
  fixes root's trigger blindness by creating fleet blindness.
- The agent namespaces contain no durable functions, schemas, source, or
  require edges at any captured basis. Consequently these outputs exercise the
  namespace statement fallback but cannot demonstrate whether distance-2
  function bodies and dependency summaries are useful.

## Where it is unhelpful or noisy

- The static execution scaffold is 322 repeated characters in every shadow.
  It is valid scaffold, but it dominates the tiny root comparisons.
- Error wake content and the nested error's shorter message repeat the core
  failure sentence. The second copy earns its cost by preserving the
  `about` relation, but a schema-specific error renderer should test a more
  compact representation.
- A lone `(ns …)` section is useful as a missing-program-facts detector, not
  useful code context. Its value depends on the code graph landing.
- `:context-walk/mode` is inferred in the experiment: chat for an ordinary
  message, goal-seeking for a routed problem or error wake. No current mode fact
  rides the trigger, so this is a hypothesis made visible, not evidence that
  the mode contract exists.

## Renderer iterations performed inside S1

1. The first universal-floor output clipped routed instructions and error
   notices. The message-shape renderer now spends the available string cap so
   the work request remains complete.
2. The first draft repeated mode and trigger shape in both the agent and
   trigger sections. They now live only on the trigger, matching the ruling
   that mode rides the message.
3. The dormant full-namespace branch emitted compact declarations before full
   definitions. It now chooses one detail level, so a populated distance-2
   namespace will not duplicate every function.
4. Every emitted namespace section is read back form-by-form with Clojure's
   reader before it is written.

## Gaps and stage verdict

1. Land durable program facts for the agent namespaces, then rerun S1 with
   real function bodies, schemas, and at least two require hops. The central
   namespace claim is not evidenced by `(ns …)` alone.
2. Put the actual mode value on the trigger message before treating the
   inferred mode projection as a contract.
3. Design root's full-system reach through the same walk; do not retain the
   fleet block as a hidden second context mechanism.
4. Preserve the small scaffold actions agents must know: send, decline, wait,
   and complete. Determine whether schema-attached renderers or static
   instructions own each one.
5. Add the S3 transcript before making quality or size claims against the
   helper baseline.
6. Move the message-specific value-render options into the eventual
   schema-attached renderer. A production composer should not carry a hand
   list of per-shape caps.

S1 is promising but not ready for S2 on this evidence alone. The trigger
render is materially clearer, especially for root, but the code-graph absence,
root oversight loss, and missing response grammar are substantive gaps for the
owner review.

The verbatim comparisons are the six `*.side-by-side.txt` files;
`metrics.edn` records both sides' sizes, capture basis, and floor used.
