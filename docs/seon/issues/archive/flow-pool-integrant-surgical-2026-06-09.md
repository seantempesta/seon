---
type: issue
status: resolved
severity: cleanup
tags: [issue, flow]
---

# Flow + pool Integrant components: surgical removal

## TL;DR

The dev-JVM Phase-2 boot throws `No such namespace: seon.flow` /
`Error on key :seon.flow/pool when building system`
(`:integrant.core/build-threw-exception`). Root cause: four Integrant keys are
still **declared in `resources/system.edn`** but their `init-key`/`halt-key!`
methods were `#_`-discarded (DISABLED 2026-06-03). With no method, Integrant
falls through to the `:default` `init-key`, which resolves the key's namespace —
`:seon.flow/pool` → namespace `seon.flow` — and throws because `seon.flow` does
not exist (only `seon.flow.pool`, `seon.flow.topology`, etc. do).

Disabling the `defmethod`s was the wrong lever: a missing method is **worse**
than a no-op because the `:default` method throws. The fix is to remove the four
dead keys from the **config** (`resources/system.edn`) so Integrant never tries
to build them.

Bonus: this is why the dev JVM currently runs **degraded** — Phase 2 aborts on
`:seon.flow/pool`, the exception is caught (`core/start-app` keeps Phase 1),
and so `:seon.db.schema/consistency-check`, `:seon.dev/instrumentation`, and
`:seon.web/caddy` never start either. Removing the dead keys lets Phase 2
complete and brings those MVP keepers up.

## Evidence (live REPL, orchestrator session, read-only)

- Running system has only the 5 Phase-1 keys:
  `(:seon.ai.claude/sdk :seon.dev/nrepl :seon.schema/registry :seon.web/tailwind
  :seon.web.server/http-server)` — Phase 2 aborted.
- `@seon.db.datahike.system/current-flow` => `nil` (datahike flow never built).
- Of the 12 config keys, exactly these 4 have **no** `init-key` method:
  `:seon.db/flow`, `:seon.flow/infrastructure`, `:seon.flow/pool`,
  `:seon.orchestrator/sessions`.
- `integrant.core/key->namespaces` for `:seon.flow/pool` =>
  `[seon.flow seon.flow.pool]`; `seon.flow` does not exist as a namespace.
- Reproduced the exact error:
  `(ig/init (ig/expand (config/system-config {:profile :dev})) [:seon.flow/pool])`
  => cause `"No such namespace: seon.flow"`.

## Where the dead keys are defined

| Key | Config declaration | Disabled `defmethod`s |
|-----|--------------------|------------------------|
| `:seon.flow/pool` | `resources/system.edn` lines 63-75 | `src/seon/flow/pool.clj` lines 847-879 (`#_`) |
| `:seon.orchestrator/sessions` | `resources/system.edn` lines 77-82 | `src/seon/system.clj` lines 153-178 (`#_`) |
| `:seon.flow/infrastructure` | `resources/system.edn` lines 51-61 | `src/seon/system.clj` lines 197-241 (`#_`) |
| `:seon.db/flow` | `resources/system.edn` lines 103-213 | `src/seon/db/datahike/system.clj` lines 55-82 (`#_`) |

Hierarchy entries (all derive `:seon/component`):
`resources/integrant/hierarchy.edn` lines 14 (`:seon.db/flow`), 15
(`:seon.flow/infrastructure`), 16 (`:seon.flow/pool`), 17
(`:seon.orchestrator/sessions`).

Config-validation schemas: `src/seon/system/config.clj` — `:seon.flow/pool`
(lines 43-48), `:seon.orchestrator/sessions` (lines 50-54), `:seon.db/flow`
(lines 61-67). (No schema entry for `:seon.flow/infrastructure`.)

## Dependency map (what refers to these keys)

`#ig/ref` edges in `resources/system.edn` (the ONLY incoming Integrant deps):

- `:seon.orchestrator/sessions` `{:pool #ig/ref :seon.flow/pool}` — line 82.
- That is the **only** edge into any of the four. `:seon.db/flow`,
  `:seon.flow/infrastructure`, and `:seon.flow/pool` have **zero** incoming
  `#ig/ref`. Both ends of the one edge are in the removal set, so removing all
  four leaves no dangling ref.

Runtime (non-Integrant) consumers of `:seon.db/flow` — `seon.db`,
`seon.runtime`, `seon.session` — resolve the flow **lazily at request time**
via `seon.db.datahike.system/current-flow` (an atom, currently `nil`) or the
running system map; they already handle absence by throwing a "not registered"
error per call rather than at boot. These are NOT Integrant dependencies and
are unaffected by removing the config key. This matches the existing documented
behavior in `resources/system.edn` lines 11-13.

Neither MVP keeper init-key touches the DB: `seon.db.schema` (consistency-check)
and `seon.dev.instrumentation` contain no `transact!`/`query`/`pull` calls, so
they do not need `:seon.db/flow`.

## Minimal surgical removal set

Remove these four top-level keys (and their config maps) from
`resources/system.edn`:

1. `:seon.flow/infrastructure` (lines 51-61, including the preceding comment
   block).
2. `:seon.flow/pool` (lines 63-75, including comment).
3. `:seon.orchestrator/sessions` (lines 77-82, including comment) — its only
   purpose was to wire the pool; it is disabled and depends solely on the pool.
4. `:seon.db/flow` (lines 103-213, including comment) — the large
   `:namespace-schemas` block goes with it.

That is the **complete** change required to stop the boot error. The four keys
disappear from the config, Integrant never builds them, the `:default`
`init-key` is never reached for them, and Phase 2 completes.

Optional follow-up cleanup (NOT required to fix the error; do later in its own
chunk to avoid scope creep):

- Drop the four hierarchy entries in `resources/integrant/hierarchy.edn`
  (lines 14-17). Harmless to leave — a hierarchy entry with no config key and
  no method is inert.
- Drop the three stale schemas in `src/seon/system/config.clj` (lines 43-67 for
  the removed keys). Harmless to leave — `validate` only runs via `assert-key`,
  which only fires for keys present in the config.
- The `#_`-discarded `defmethod`s in `flow/pool.clj`, `system.clj`, and
  `db/datahike/system.clj` can stay (they are already inert) or be deleted when
  those namespaces are next touched.

## What must NOT be touched

- Do NOT delete the `seon.flow.*` source namespaces (`pool.clj`, `topology.clj`,
  `trace.clj`, `status.clj`, `msg.clj`, the harness) — they are required by
  `seon.system` and by runtime code; only the **Integrant config keys** are dead.
- Do NOT touch `phase-1-keys` in `src/seon/core.clj` — Phase 1 is healthy.
- Do NOT touch the keeper config keys: `:seon.schema/registry`,
  `:seon.db.schema/consistency-check`, `:seon.dev/nrepl`,
  `:seon.web.server/http-server`, `:seon.web/tailwind`, `:seon.web/caddy`,
  `:seon.dev/instrumentation`, `:seon.ai.claude/sdk`.
- Do NOT touch the pod `:client` build or `guest-cljs`.
- Do NOT remove anything beyond the four flow/pool/sessions Integrant keys.

## Verification

1. Build the expanded config and confirm no key lacks an init-key method:

   ```clojure
   (require '[seon.config :as config] '[integrant.core :as ig])
   (let [cfg (config/system-config {:profile :dev})]
     (remove #(contains? (methods ig/init-key) %) (keys cfg)))
   ;; => () after the edit (was (:seon.db/flow :seon.flow/infrastructure
   ;;                            :seon.flow/pool :seon.orchestrator/sessions))
   ```

2. After `(user/reset)` (orchestrator does this when Track 2 is clear), confirm:
   - No `No such namespace: seon.flow` in `logs/startup.log` / `logs/app.log`.
   - `(user/status)` healthy; `(keys integrant.repl.state/system)` now includes
     `:seon.db.schema/consistency-check`, `:seon.dev/instrumentation`, and
     `:seon.web/caddy` (previously absent because Phase 2 aborted).
   - Phase 2 no longer enters `:degraded` (`health/set-startup-phase!` reaches
     `:ready`, given other readiness checks pass).

## Status of application

APPLIED. The edit was made to `resources/system.edn` — all four keys
(`:seon.flow/infrastructure`, `:seon.flow/pool`, `:seon.orchestrator/sessions`,
`:seon.db/flow`) removed; a comment block records the removal and points here.
Verified WITHOUT restarting the live JVM (to avoid disrupting Track 2):

- Aero re-parses the file cleanly: `(config/system-config {:profile :dev})`
  returns a map with 8 keys.
- Build-time check (step 1) now returns `()` — every remaining config key has
  a real `init-key` method (was the 4 dead keys before).

NOT yet done (orchestrator, when Track 2 is clear): run `(user/reset)` and
confirm step 2 — no `No such namespace: seon.flow`, system reaches `:ready`
(not `:degraded`), and `consistency-check` / `instrumentation` / `caddy` now
appear in `integrant.repl.state/system`. A full `ig/init` was NOT run here
because it would collide with the live nREPL/HTTP ports.

Note on `hierarchy.edn` / `system/config.clj`: left as-is (inert without the
config keys). The `#_`-discarded `defmethod`s in `flow/pool.clj`, `system.clj`,
`db/datahike/system.clj` also left as-is. These are optional not part
of the error fix.

## Resolution (2026-06-28 audit)

Closed RESOLVED per `docs/seon/issues-audit-2026-06-28.md`: the body
records the fix as APPLIED — all four dead Integrant keys were removed from
`resources/system.edn`. JVM-track-so the live `(user/reset)` confirmation
is moot until that track resumes.
