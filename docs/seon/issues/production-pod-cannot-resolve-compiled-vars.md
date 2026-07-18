---
type: issue
status: open
severity: blocker
tags: [issue, cljs, pod, web]
---

# Keep compiled ClojureScript vars resolvable in production

## Problem

The relocated source-free package reached writer and pod readiness, but `/`
and `/agent/root/feed` returned HTTP 500. The pod log reported that
`seon.web.datastar/serve-root!` and `seon.web.datastar/open-agent-feed!` were
unresolved. Startup instrumentation rejected all 754 selected functions as
`:seon.instrument/no-var` and instrumented none.

The package producer used Shadow release's advanced optimization while
`seon.eval/lookup-value` expected stable namespace and member names. Closure
may rename or remove functions reached only through qualified-symbol data.

## Dependency evidence

ClojureScript's bootstrap namespace implementation in
`reference-code/clojurescript/src/main/cljs/cljs/core.cljs` explicitly states
that its namespace helpers are incompatible with advanced compilation. For a
compiled Node target under simple optimization, `find-ns-obj` uses direct
`eval` to reach module-scoped namespaces. Shadow's release path defaults to
advanced optimization; Seon's execution child already selects simple
optimization for the same self-host runtime requirement.

## Owner

`seon.eval/lookup-ns-object` is the one qualified-symbol resolution and
namespace-enumeration owner. The `:client`, `:acme-client`, and `:bench-client`
Shadow builds are the production pod artifact owners. Do not add a route
registry or export list to compensate for a compiler/runtime mismatch.

## Acceptance

- A production package resolves representative route, render,
  instrumentation, and self-host-authored functions through `lookup-value`.
- Startup instrumentation no longer classifies the complete compiled program
  as `:seon.instrument/no-var`.
- The relocated package serves `/`, `/data`, `/agent/root/feed`, and
  `/data/feed` through the existing router and Datastar owners.
- The package inventory and application digest remain unchanged while the pod
  runs; logs and temporary paths are outside the package root.
- Real agent execution, clean restart/read-back, and clean shutdown pass from
  the same package.
