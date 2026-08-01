---
type: issue
status: open
tags: [issue, agent, sci, database]
---

# Findings identified 2026-08-01 that had no issue until now

An end-of-day audit of the session found these identified-but-unlogged.
Each is small and independently actionable.

## 1. Agent evals can reach only `Throwable`/`Error` classes

`sci/init`'s `:classes` map in `src/seon/sci/eval.clj` exposes exactly
`Throwable`, `java.lang.Throwable`, `Error`, `java.lang.Error`. So
ordinary interop an agent would reach for — `java.time.Instant/now`,
`java.util.UUID/randomUUID`, `java.io.File` — fails. Owner direction
2026-08-01: NO hand-maintained interop allowlist; the policy must be
COMPUTED (sci's `:classes` supports allow/deny structure — read
`reference-code/sci` for the mechanism). Blocks "the agent can do
everything within reason".

## 2. Agents have no way to record their own facts

Agents can read the graph (`seon.db/q`/`pull`/`pull-many`) but the write
surface was scoped out of the facade ("transact stays on the store
owner"). Today the only agent-authored facts are messages and run
dispositions; an agent can persist CODE (defn + `:malli/schema`) but not
DATA, which blocks the memory story. Note the hazard: after `seon.db` is
published, `seon.cluster.store/transact!` ALREADY resolves in agent
evals and `seon.db/*conn*` becomes reachable, so agents effectively gain
unconstrained write access with no ownership rule. Needs
`seon.db/transact` as the intended surface plus the constraint (assert
only under schemas/namespaces you own) and an atomicity decision
(immediate vs queued into the turn's terminal transaction).

## 3. `deps.edn`'s `:cljs` alias contradicts AGENTS.md

`AGENTS.md` says the root `:writer` and `:cljs` aliases are "the
authority for Seon's maintained Datahike, Konserve, superv.async, and
partial-cps coordinates". `deps.edn:180` says `:cljs` is "retained
solely so the alias name still parses for historical scripts; nothing
may invoke it" — and it pins `org.replikativ/datahike` from Maven,
contradicting the vendored fork `:writer` uses. One of the two must
change; `:writer` is the real fork authority.

## 4. `konserve` submodule work existed only on this disk

`reference-code/konserve` sat at an uncommitted pointer bump to
`737697d` ("Implement ordered filestore multi-key operations", the
konserve-multi-assoc lane) that was on NO remote. Pushed 2026-08-01 to
`seantempesta/konserve` branch `multi-key-ordered-ops` so it cannot be
lost. STILL OPEN: the commit needs review, then the parent-repo pointer
needs committing (a pointer to an unfetchable commit would break every
other checkout, which is why it was not committed blindly).

## 5. `seon.db` facade slices are incomplete

`q`, `pull`, `pull-many` landed. `entity` (eager bounded pull — the
lazy form leaked config values through admission), `datoms` (bounded
pages), and the 27-file `datahike.api` call-site migration are unbuilt;
the lane stopped when concurrent schema/config edits blocked
publication. Resume when the tree is quiet.

## 6. Pod-era rot still tracked at the repository root

Verified dead or contradictory, not yet removed: `docker/` (a pod
entrypoint that referenced the now-deleted `config/system.edn`),
`specs/` (three pod-era spec files), `externs/`, `examples/
third-party-override` (CLJS), `bin/fix-bootstrap-macros` (CLJS
bootstrap), and root `shadow-cljs.edn` + `package.json` presenting a
live CLJS build although CLJS is OFF (owner ruling 2026-07-27). Also
`evals/runs/` holds ~700 dated pod-era transcript files — archive
material rather than rot, but it dominates the tree. `ORCHESTRATOR.md`
is already self-marked superseded.
