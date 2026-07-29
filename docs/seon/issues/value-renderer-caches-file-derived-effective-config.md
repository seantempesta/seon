---
type: issue
status: open
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
