---
type: issue
status: resolved
severity: friction
tags: [issue, agent, flow]
---

# Changed-test hook drops build inputs from mixed patches

## Problem

The changed-test operation understands dependency and Shadow build inputs, but
the edit hook forwarded only paths ending in `.clj`, `.cljs`, or `.cljc`. A
mixed patch that changed `deps.edn` and CLJS therefore selected against a stale
managed artifact without knowing the dependency graph had changed.

## Evidence

The 2026-07-14 Datahike budget integration changed `deps.edn`, `seon.db`, and
tests in one patch. The hook forwarded only the ClojureScript paths and ran a
91-namespace stale artifact whose generated code referenced the new
`datahike.resource` namespace but whose dependency bundle did not contain it.
The false failures were `TypeError: ... make_budget` across unrelated tests.
A fresh focused build resolved the dependency and passed.

## Owner

`bin/seon-hook` path selection and the existing `seon.dev.changed-test` build
input classification.

## Acceptance

Codex and Claude mixed patches forward `deps.edn`, `shadow-cljs.edn`,
`package.json`, `package-lock.json`, and `bb.edn` alongside source paths. A
dependency change waits for or builds an artifact with the new classpath and
never runs a stale bundle as affected-test evidence.

## Resolution

`bin/seon-hook` now forwards Clojure source paths and all five build inputs to
the existing changed-test operation. A direct mixed-path proof with `deps.edn`
and `src/seon/db.cljs` selected all three boundaries with widening reason
`shared-or-build-input`; operator passed 81 tests/532 assertions, writer passed
50/308, and the pod artifact contained `datahike.resource` rather than the
stale dependency closure. The pod gate exposed a separate full-widening parity
defect, which has its own issue and does not invalidate build-input forwarding.
