---
type: issue
status: resolved
tags: [issue, pod, cljs]
---

# Production execution child bootstrap misses `goog`

## Evidence

On 2026-07-18, relocated source-free package v8 admitted its writer and pod,
served `/`, `/data`, and both gzip Datastar feeds, and instrumented all 754
accepted functions. A real `POST /agents/run` then retired each demanded
execution child in about 80–85 ms. The child's first bootstrap file began with
`goog.provide('cljs.core$macros')` and failed with `ReferenceError: goog is not
defined`.

The execution artifact uses Shadow's simple optimization. Its Closure `goog`
and loaded `cljs` namespace objects are module-scoped, while
`shadow.cljs.bootstrap.node/init` evaluates bootstrap namespace files in global
scope. Publishing only `goog` advanced the failure into `cljs.core$macros`,
where the global script could not see the module's existing `cljs.core`.
Publishing `cljs` then advanced into `malli.core$macros`, proving that exporting
dependency roots individually would merely chase the complete bootstrap graph.
Replacing `goog.globalEval` around `boot/init` did not affect the eventual
asynchronous load call; the production stack still identified Closure's
original global evaluator.

## Owner and acceptance

`seon.eval/init-bootstrap!` owns bootstrap initialization for the execution
runtime. It must publish the bundle's exact `goog` and `cljs` namespace-owner
objects plus every other compiled root derived from Shadow's maintained
`bootstrap.env/loaded-ref`. It must not maintain a second namespace list. A
fresh relocated package must execute an agent-authored form, commit the reply,
retire the child normally, restart, and read the committed result back without
changing the package inventory.

## Resolution

Resolved by `6657f373`. The execution child publishes the compiled namespace
roots derived from Shadow's `bootstrap.env/loaded-ref`, allowing Shadow's
bootstrap loader to evaluate its dependency-ordered files against the exact
module-owned namespaces. Relocated release `114dad14…` ran a real Bun child,
committed scalar and lifecycle evals, restarted cleanly, read the result back
through gzip Datastar, and left the package inventory byte-identical.
