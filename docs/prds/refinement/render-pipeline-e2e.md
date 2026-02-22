# Render Pipeline E2E Verification

**Date:** 2026-02-22
**Status:** Working (custom render path)

## What Works

`/ns/seon.health.workout` renders workout data via the custom render path:

1. `GET /ns/seon.health.workout` serves skeleton page
2. SSE POST morphs in content from `seon.health.workout/render`
3. Toggle button switches between custom view and introspection view
4. `:ai` and `:raw` formats also work

## How It Works

The route handler in `seon.ns.routes` checks `namespace-has-render?` which looks for a public `render` fn with 1-arity in the namespace. `seon.health.workout/render` accepts `{:format :html :id id}` and returns an HTML string with `<main id="morph">`.

## What Does NOT Work: Datalevin Render Pipeline

The scanner/Datalevin render resolution path is **not functional**:

- `seon.graph/scanner` Integrant component runs at startup
- Scanner parses source files for `schema/register!` calls
- `link-fns-to-specs` links functions to specs by naming convention (`foo-request`/`foo-response`)
- **But**: the graph DB has 0 function entities and 0 spec entities after startup
- `find-renderer` always returns nil because there's nothing in Datalevin
- The `seon.health.workout.render/workout-set` renderer is never discoverable

### Root Cause

The scanner finds specs (schema registrations) correctly, but the **analyzer** (`seon.graph.analyzer`) that discovers function entities appears to produce no results. Without function entities, `link-fns-to-specs` has nothing to link, and `find-renderer` has nothing to query.

### To Fix (Future Work)

1. Debug `seon.graph.analyzer/analyze-project!` -- why does it return 0 functions?
2. Once functions are in Datalevin, verify `link-fns-to-specs` correctly identifies `workout-set` as a render function (it has `workout-set-request` and `workout-set-response` schemas with `:seon.render/html` key)
3. Wire `find-renderer` into `seon.ns.routes` as a fallback when no direct `render` fn exists

## Files

- `src/seon/health/workout.clj` -- new namespace with custom `render` fn (proof of life)
- `src/seon/health/workout/render.clj` -- existing Datalevin-style renderer (not yet discoverable)
- `src/seon/ns/routes.clj` -- route handler (unchanged, already supports custom render)
- `src/seon/render.clj` -- Datalevin render resolution (works but empty DB)
- `src/seon/graph/scanner.clj` -- spec scanner (works, but analyzer produces no fn entities)
