---
type: issue
status: open
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
object is module-scoped, while `shadow.cljs.bootstrap.node/init` evaluates
bootstrap namespace files in global scope.

## Owner and acceptance

`seon.eval/init-bootstrap!` owns bootstrap initialization for the execution
runtime. It must publish the bundle's exact `goog` object before the loader
runs. A fresh relocated package must execute an agent-authored form, commit the
reply, retire the child normally, restart, and read the committed result back
without changing the package inventory.
