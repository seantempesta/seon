---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, context]
---

# Warnings misfire: unmarked-entity-kinds nags agents to re-register core schemas

## Summary

The agent-facing `unmarked-entity-kinds` warning (`seon.warn/check-unmarked-entity-kinds`,
`src/seon/warn.cljs`) fired for CORE identity attrs and instructed the agent to
"Register (or re-register) the kind's `:map` schema WITH the marker" — i.e. to
re-register a compiled-core schema, which agents are explicitly told never to
touch. It fired on every fresh-seed agent and closed the `<warnings>` block with a
"Please correct before moving on" demand that carried no actionable task for the
agent (the only valid fix is a core source change).

## Observed (live, fresh world, 2026-06-18)

Against `@seon.db/*conn*` in the pod:

```clojure
(seon.warn/check-unmarked-entity-kinds {:seon.db/db @seon.db/*conn*})
;; :seon.warn/affected => [":seon.ctx/config-id" ":seon.handler/key"]
```

Both attrs have stored datoms and no registered `:map` schema marked
`{:seon.db/entity true}`, so the BEHAVIORAL check flagged them. But both belong to
core-provenance namespaces — `(seon.db/core-kinds db)` contains `:seon.ctx` and
`:seon.handler` — meaning the kind was registered by the compiled core's boot
index, not by the agent. The agent cannot fix it.

## Root cause

`check-unmarked-entity-kinds` derived its attr universe from `identity-attrs db`
(every `:db.unique/identity` attr installed on the db, minus datahike's own
`:db/*`) and removed only the `marked-entity-id-attrs` set. It did NOT filter by
provenance. So any core identity attr that has live datoms but lacks an
`{:seon.db/entity true}` `:map` schema was flagged. The docstring even advertised
this as intended: "GLOBAL — fires on core and agents alike."

This was the one provenance-blind check in the file. Its sibling
`check-parallel-attr` already side-steps the problem because it operates on
`domain-attrs` (provenance-filtered), and `agent-registered-attrs` /
`domain-attrs` (warn.cljs ~300-360) already establish the convention that
core = `:core-seed` provenance, agent = everything else.

## Fix (two parts)

### (a) Exclude core kinds from the agent-facing warning — APPLIED

In `check-unmarked-entity-kinds` (`src/seon/warn.cljs`), bind
`core (db/core-kinds db)` in the `let`, and add one `remove` step to the affected
threading, before the per-attr instance-count filter:

```clojure
(remove #(contains? core (keyword (namespace %))))
```

`identity-attrs` yields qualified attrs (`:seon.ctx/config-id`); `core-kinds`
yields namespace keywords (`:seon.ctx`), so `(keyword (namespace %))` matches.
This reuses the canonical `:core-seed` provenance mechanism
(`seon.db/core-kinds` → `bootstrap-row-ids`) rather than a name list, so it stays
correct as the core grows. `seon.db` is already required as `db` in warn.cljs and
`db.cljs` does not require `seon.warn` (no cycle). The docstring was amended to
drop "fires on core and agents alike."

Verified live after the change: `:seon.warn/affected` is now `[]`; an
agent-authored kind (e.g. `:my.kb.doc/path`, namespace not in `core-kinds`) is
NOT excluded and would still fire if it had stored rows.

### (b) Mark the core `:map` schemas `{:seon.db/entity true}` at source — NOT DONE

Part (a) makes the warning stop nagging agents, but the underlying core schemas
(the `:map` schemas for `:seon.ctx/config-id`, `:seon.handler/key`, and any other
core identity attr lacking the marker) are genuinely unmarked. Marking them at
source — adding `{:seon.db/entity true}` to the registered `:map` schema in the
owning namespace — is the real fix: the kind becomes visible to the entity
renderer and the warning stops firing for EVERYONE, not just because it's
filtered. This is a core source change (touches `seon.ctx` / `seon.handler` and
any other affected core nses), out of scope for the warn.cljs-only task, and is
left as follow-up. If a core kind genuinely should NOT be an entity (it's an
envelope/config row, not a renderable kind), then leaving it unmarked is correct
and part (a) is the complete fix.

## Why provenance-blind global was the wrong default

A global behavioral check that fires on core kinds hands the agent a task it
structurally cannot complete. Warnings must name an EXACT defect the recipient can
fix; a core-schema nag at an agent is noise that erodes trust in the whole
`<warnings>` block. The provenance split (`core-kinds`) is the existing, correct
tool — this check was simply the last one not using it.

## Loci

- `src/seon/warn.cljs` `check-unmarked-entity-kinds` (~473-511) — the misfiring check; fix (a) applied here.
- `src/seon/warn.cljs` `identity-attrs` (~433-444) — source of the over-broad attr list.
- `src/seon/db.cljs` `core-kinds` (~790-808) / `bootstrap-row-ids` (~775-788) — the `:core-seed` provenance helper reused by the fix.
- Part (b) follow-up: the owning core nses for `:seon.ctx/config-id`, `:seon.handler/key` (mark `:map` schemas `{:seon.db/entity true}` if they are genuine kinds).

## Resolution (2026-06-28 audit)

Closed RESOLVED per `docs/seon/orchestrator/issues-audit-2026-06-28.md`:
`warn.cljs:514` removes core-kinds from the check and `:dev-only?` warnings drop
from the prompt, so the misfire on core identity attrs no longer nags agents.
