---
type: issue
status: resolved
severity: cleanup
tags: [issue, architecture, config, render]
---

# Remove file-derived effective config from the value renderer

## Problem

`seon.render.value` realizes `(config/defaults)` into a process-local delay and
merges that cached file-derived map into every structural render. Runtime
configuration is supposed to come from the cluster's database value; the
universal floor instead retains a second effective-config projection outside
the database.

## Evidence

`src/seon/render/value.cljc` defines:

```clojure
(def ^:private default-effective
  (delay (config/defaults)))
```

`presentation-options` and `prepare` both merge `@default-effective` before
deriving result caps. Real block units carry database-derived
`:seon.sci.admit/caps`, so those values override the currently used hard
maxima; that is why the renderer and web suites remain green. The fallback is
still live for any generic router unit that omits or partially supplies caps,
and it caches the entire effective config rather than presentation data.

This contradicts `seon.config`'s current contract that runtime consumers read
only the database row. It is stored-derived creep introduced with the
universal renderer, not a second observed failure in the database-backed block
path.

## Owner

`seon.render.value` plus the one render-unit builder. Presentation defaults
may derive from their registered option schemas; admission maxima must arrive
from the database value or its already-derived caps at the owning render
boundary.

## Acceptance

- No render namespace calls `config/defaults` or retains an effective config
  in process-local state.
- Every live generic render receives admission caps derived from the same
  database value as the rendered unit.
- A missing required cap is a named boundary error or a documented pure
  presentation fallback, not an implicit read of `config/default.edn`.
- Changing a cluster's config affects its next render without process restart
  and cannot bleed into another cluster.

## Resolution

Resolved by `08a436d02`. Database-backed structural renders now derive the
single cluster's complete effective config from the unit's own immutable
`:seon.db/db` value. A missing, duplicate, or incomplete config row is a named
core boundary error. Database-free pure calls retain only schema-registered
presentation defaults and any explicitly supplied effective config or caps;
the renderer no longer calls `config/defaults` or caches effective config.

The regression first failed both assertions because database caps of 3 and 6
each rendered the cached default head of 8. After the fix,
`seon.render.value-test` passed 21 tests / 66 assertions, and the combined
renderer/config gate passed 106 tests / 353 assertions. The same connection
receives two `config/apply!` calls and the next immutable database value expands
the rendered head from 3 to 6 without a process or renderer restart.
