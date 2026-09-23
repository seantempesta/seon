---
type: issue
status: open
severity: blocking
created: 2026-09-23
tags: [issue, publication, adoption, instrumentation]
---

# Adoption refuses a publication that changes no definition

## Problem

`seon.cluster/development-source-refresh!` refuses "Development JVM
instrumentation did not restore contracts." whenever the cluster's
`:seon.config/on-core-error` is `:panic` and `instrument/apply!` instrumented
nothing (`src/seon/cluster.clj:2542-2545` in the working tree; `:2447-2451` at
`369c6e5d2`). A publication whose only change is a file row (a comment, or any
edit that leaves every definition digest equal) reloads no namespace
(`development-namespaces` seeds nothing from a `:seon.fn.file/relative-path`
identity), so `development-arming-identities` is empty, `apply!` instruments 0,
and the adoption refuses although nothing needed arming.

Observed 2026-09-23 (lane move-to-head, drill 1): scratch root `tmp/mth-root`,
`default` moved from `369c6e5d2` to `ea219a287` (one appended comment in
`src/seon/await.clj`). Boot published the file; `init --dev default` refused
with offense `#:seon.instrument{:registered 0, :instrumented 0}`
(`tmp/mth-evidence/move-1.log`). The same move with a docstring change
(`08a3227df`, drill 2) adopted.

## Exact change (holder of `src/seon/cluster.clj`)

Refuse only when something was to be armed:

```clojure
(when (or (:seon.instrument/registration-observation result)
          (and (= :panic (:seon.config/on-core-error effective))
               (seq arming-identities)
               (not (pos? (or (:seon.instrument/instrumented result) 0)))))
  (refused! "Development JVM instrumentation did not restore contracts." result))
```

## Acceptance

A regression publishes a comment-only change to a running development cluster
under `:panic` and asserts the adoption record advances with zero reloaded
namespaces.
