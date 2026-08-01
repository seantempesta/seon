---
type: issue
status: open
severity: friction
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
`reference-code/sci` for the mechanism).

OWNER DIRECTION 2026-08-01, settled shape: **DEFAULT-ALLOW, DENY AT THE
SITE WITH A REASON.** Expose everything, and exclude only what genuinely
blows up — recorded as METADATA AT THE DEFINITION SITE carrying the very
real and specific issue that justifies it, never as a curated list in a
config file. That satisfies the no-hand-maintained-lists rule the same
way `^{:seon.workload :io}` does: the exclusion set is DERIVED at index
time from decorations colocated with the thing they describe, so the
reason travels with the code and cannot rot into an unexplained list.

For first-party functions this is exact: a `defn` carries the deny
metadata. For JDK classes, which we cannot decorate, the analogous
honest form is a SMALL denial set where each entry names its specific
failure (process control, JVM exit, unbounded native effects) and is
justified in place — everything else is exposed by default. The
inversion to avoid is the one we have today: an allow list of four
classes with no stated reason, which silently blocks
`java.time.Instant/now`.

Blocks "the agent can do everything within reason".

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


## 7. `:seon.render.value/max-collection` now has TWO defaults authorities

The caps/blob wave added `:seon.render.value/max-collection 8` to
`config/default.edn:39` while `resources/seon/schema/render_value.edn`
still declares `:seon.render.value/default 8` for the same attribute —
two places shipping the same number, which is the second-registry smell
the repo forbids. The schema file's own comment argues against exactly
this ("adding these as `seon.config.*` dials would create a second
defaults authority outside config/default.edn"), so the comment and the
code now contradict each other. One must own it; the rest reads it.

Verified NOT a problem while auditing this: the printer is ONE mechanism
and the REPL is not clipped. `seon.render.transcript` passes the
admission caps AS the presentation options, and the floor takes
`min(option, cap)`, so a transcript renders at full cap width — measured
2026-08-01, a 500-element result renders all 500 elements with no
elision marker. The small schema default applies only to the paged
`/data` drill window, which is correct for a paged UI. The open question
is the opposite of crippling: with presentation pinned to the safety
ceiling, a genuinely huge result would flood the context, and the
per-entry detail policy that should decide is the deliberately-unwritten
compaction layer (ruling #24's dynamic transcript).
