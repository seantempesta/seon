---
type: issue
status: open
severity: defect
tags: [issue, db, datahike, custody, errors]
---

# `seon.db/connection-identity` throws on a released connection

## Problem

`seon.db/connection-identity` (`src/seon/db.clj:342-367`) promises a typed
`::released-connection` error value for released custody. It tests
`(map? (:config @connection))`. But dereferencing a released Datahike
connection throws before that test runs. The throw is
`ExceptionInfo "Connection has been released."` with `{:type :connection-has-been-released}`.
So the typed branch is unreachable, and every caller receives a throw from an
identity projection.

## Evidence (default JVM, pid 90471, archive `ce7384682`, 2026-09-24)

This form uses a throwaway in-memory store, created and then deleted:

```clojure
(let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :read :keep-history? false}
      _ (datahike.api/create-database cfg) c (datahike.api/connect cfg)
      live (seon.db/connection-identity c)
      _ (datahike.api/release c)]
  [live (try (seon.db/connection-identity c) (catch Throwable t (ex-data t)))])
;; => [#:datahike{:connection-id [#uuid "…" :db]} {:type :connection-has-been-released}]
```

## Fix direction

The db owner should test released state without dereferencing the connection.
Alternatively, it can catch exactly the dependency's `:connection-has-been-released`
case and return the declared error value. The consumer that relies on the typed
result is `seon.sci.eval/record-refusals-once!`. Commit `1b10216c6` converted it to
`db/connection-identity`.

A regression releases a real connection and asserts the declared
`:seon.db/released-connection` value.
