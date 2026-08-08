---
type: issue
status: open
severity: blocker
tags: [issue, database, render, context, live-drive]
---

# Give an as-of database value a dependency revision

## Problem

`seon.db/read-evidence` refuses every read taken against an as-of database
value, so `seon.render/walk` fails whole and the agent's ENTIRE rendered
context becomes one 127-token error string. A real DeepSeek turn then runs
with no context at all.

`seon.db/dependency-revision` (`src/seon/db.clj:256-276`) reads
`(:cache-context database)` as a MAP KEY. `datahike.db.DB` carries
`:cache-context`; `datahike.db.AsOfDB` does not. The revision map therefore
comes back with `:datahike.cache/connection-id` nil,
`:datahike.cache/generation` nil, and `:datahike.read/attributes` a set rather
than `:all`, and `read-evidence`'s output contract throws.

This is the same class as the capture-basis defect fixed in the same drive:
**reading a database value through a map key breaks for every non-current
value shape.** Datahike's own interface (`dbi/-max-tx`, `d/commit-id`) is the
reader; `:cache-context` currently has no interface equivalent.

## Evidence

Live on cluster `default` (pid 79576), 2026-08-08, basis 536871000:

```clojure
(let [db   (seon.db/db conn)
      asof (datahike.api/as-of db 536870990)
      ev   (fn [d] (try [:ok (first (seon.db/read-evidence
                                     [{:seon.db/db d
                                       :seon.db/source-argument-position 0
                                       :datahike.read/dependency-plan :all}]))]
                     (catch Throwable t [:threw (ex-message t)])))]
  {:current (ev db) :asof (ev asof)})
```

```clojure
{:current [:ok {:seon.db/source-argument-position 0
                :datahike.read/dependency-plan :all
                :datahike.read/revision
                {:datahike.cache/connection-id
                 ["28506195-c708-3720-9f98-58561569cea7" "cluster-default"]
                 :datahike.cache/generation "62b582bb-a988-4373-b6ef-4f5ebf8ce831"
                 :datahike.read/attributes :all
                 :datahike.read/revision "6a76b2d6-8a09-584d-abcd-e04d7724663d"}}]
 :asof    [:threw "seon.db/read-evidence violated its contract (invalid-output): ..."]}
```

Direct cause, same session:

```clojure
(:cache-context (datahike.api/as-of db 536870990))  ;=> nil
(type (datahike.api/as-of db 536870990))            ;=> datahike.db.AsOfDB
```

The consequence, verbatim — this is the COMPLETE prompt committed as context
capture `a7e24a23-14b7-41ab-8a96-5f3c06a9a8ee-context-536870998`
(509 characters, 127 estimated tokens, one contribution named `walk`):

```text
;; (seon.render/walk) => error
Walk failed: seon.db/read-evidence violated its contract (invalid-output): [#:datahike.read{:revision {:datahike.cache/connection-id [{:value nil, :message "missing required key"}], :datahike.cache/generation [{:value nil, :message "missing required key"}], :datahike.read/attributes [{:value #, :message "should be :all"}], :datahike.read/revision [… 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]}}]
```

DeepSeek-flash was sent exactly that and, rationally, spent the whole turn
debugging it: attempt `a7e24a23-...-attempt-0` recorded 225 prompt tokens,
10,502 completion tokens of which **9,840 reasoning**, finish reason `stop`.
Every one of the thirteen forms it planned was about the error; the requested
completion value never appeared.

## Owner

`seon.db/dependency-revision` and `seon.db/read-evidence` (`src/seon/db.clj`),
with `resources/seon/schemas/seon.db.edn`'s `:seon.db/read-evidence`
declaration.

## Acceptance

- A read against an as-of, since, or history value produces a well-formed
  dependency revision — most likely one keyed on the fixed point itself, since
  such a value is immutable and can never go stale.
- `seon.render/walk` against a run's opening basis renders the full context,
  not an error.
- The revision is read through a database INTERFACE call, not a `:cache-context`
  map key, so no fourth value shape can reintroduce the class.
- One class regression asserts `read-evidence` totality across all four value
  shapes (current, as-of, since, history).

## Note for whoever fixes this

Consider whether the turn should render at the run's opening basis at all. The
capture's `basis-t` does not record the as-of point: `dbi/-max-tx` on an
`AsOfDB` returns the ORIGIN's max-tx (probed: as-of 536870990 reports
536870997), so the capture id names the current basis while the content is
historical. That is a separate honesty defect in the same seam.
