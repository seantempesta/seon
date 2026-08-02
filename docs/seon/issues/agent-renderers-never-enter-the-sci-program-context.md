---
type: issue
status: open
severity: blocker
tags: [issue, render, sci, architecture]
---

# Run agent renderers through the guarded SCI program context

## Problem

The only render router resolves and invokes JVM Vars. Functions defined in the
cluster's live SCI program context therefore cannot render, even though the
settled design requires every agent-authored AI and HTML renderer to execute
through SCI's interrupt boundary.

The missing boundary also makes `seon.render.hiccup/raw` unsafe as a future
agent-render result: its safety documentation assumes an agent cannot call it,
while ruling #20 says every function is callable.

This finding is **in flight (schema-edn-consolidation lane)** because the two
render files are modified; their current diffs only update schema-resource
comments.

## Evidence

- `docs/prds/sci-execution-runtime/plan/README.md:1333-1352,1671-1682`
  requires guarded SCI execution for agent render and context code.
- `src/seon/render.clj:282-305,331-382` uses only `requiring-resolve` and direct
  JVM Var invocation.
- `src/seon/sci/eval.clj:71-92,1210-1233` owns a separate live SCI context.
- `src/seon/render/hiccup.clj:68-77` exposes public unescaped `Raw` construction
  while claiming agent-authored code cannot call it.
- A load-only probe defined `my.audit.renderer/render-ai` in SCI. Calling it in
  SCI returned `"SCI-only"`; the router returned
  `:seon.render/unresolvable` for the same symbol.

## Owner

The one `seon.render` projection-invocation boundary, composed with
`seon.sci.eval`'s cluster context and `seon.render.hiccup` admission.

## Acceptance

- A database/SCI renderer resolves at the cluster basis and executes under the
  one `:interrupt-fn` and time limit.
- A loop, throw, or refused output becomes a flat durable error with renderer
  provenance and cannot wedge a proc.
- HTML safety derives from the admitted grammar, not from a callability claim;
  an agent cannot emit unescaped bytes by calling `raw`.
- Hot-reloaded compiled system projections continue to use the same router,
  without a second render path.
