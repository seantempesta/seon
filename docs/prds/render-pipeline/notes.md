# Render Pipeline Notes

## Phase 1-2 (2026-02-19)

### What was done
- Created `seon.health.workout.render` as first `.render` companion namespace
- Added resolution cache (`resolution-cache` atom) keyed by `[format (set (keys data))]`
- Added `*conn` atom + `set-conn!` for Datalevin connection (needs to be wired in system.clj startup)
- Removed old registry: `*renderers`, `register-renderer!`, `get-renderer`, `clear-renderers!`, `registered-renderers`
- Updated `render` to single-arity `(render value format)` -- removed `default-schema` 3rd arg
- Updated `for-ai` to try Datalevin resolution before recursive map rendering
- Updated `render/example.clj` to use spec-driven pattern instead of `register-renderer!`
- Added `invalidate-render-cache!` calls in both `ingest-analysis!` and `ingest-incremental!`
- Fallback rendering now uses `pprint-clipped` (500 char limit) instead of `pr-str`

### Gotchas
- `render/set-conn!` needs to be called at system startup with the master Datalevin conn. Without it, all Datalevin resolution returns `::no-renderer` and falls back to pprint-clipped. This is safe but means no custom renderers fire.
- The `render` function signature changed from 2/3 arity to just 2 arity. No callers used the 3-arg form so this was clean.
- The pre-existing test error in `session-resume-ctx-state-test` ("cached plan must not change result type") is a Datalevin issue, not related to render changes.
- Scanner `link-fns-to-specs` uses naming convention: for fn `ns/foo`, looks for specs `:ns/foo-request` and `:ns/foo-response`. The response spec must have `:seon.render/html` or `:seon.render/ai` in its `:seon.spec/contains-keys` for the fn to be classified as a render function.
