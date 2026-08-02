---
type: issue
status: open
severity: blocker
tags: [issue, database, sci, runtime]
---

# Make `seon.db` the one agent-first database namespace and route everything through it

## Problem

Ruling 2026-08-02 #41 (plan README): all of Datahike's core functions
belong in `seon.db`, agent-first (ambient cluster connection,
errors-as-values, SCI-admit-clean returns), and ALL first-party code
goes through it. The measured state at ruling time:

- `seon.db` exposes only `q`, `pull`, `pull-many` and is required by
  exactly ONE namespace in src/ (`seon.render`). The facade was
  intended and never adopted.
- 34 namespaces require `datahike.api` directly: 143 `d/q`, 48
  `d/pull`, 5 `d/datoms`, 4 `d/history`, 1 `d/entity`, 1 `d/as-of`,
  1 `d/since` call sites (counted 2026-08-02).
- 16 `d/transact` sites bypass `seon.cluster.store/transact!` and its
  never-throw/four-outcome/Integer→Long/schema-encode protections:
  `seon.cluster` (boot ×6), `seon.cluster.source` (×3), `seon.fn`
  (×2), `seon.render.web` (×2 — runtime message submission),
  `seon.reconcile`, `seon.test.runner`, `seon.eval.drive`.
- Missing agent surface entirely: `transact!`, `entity`, `datoms`,
  `db`, `history`, `as-of`, `since`.

Supersedes the `seon.db` fragment of
[[unlogged-findings-2026-08-01]] item 5 (its "facade completion wave"
destination folds into this issue).

## Acceptance

1. `transact!` lives in `seon.db` (moved, not duplicated — store keeps
   only store/branch/flock custody; registry keeps branch management);
   the four-outcome contract, Integer→Long walk, and schema encode are
   preserved with their tests.
2. `seon.db` gains `entity`, `datoms`, `db`, `history`, `as-of`,
   `since` — each with an optional explicit db/connection first
   argument, the ambient cluster default, errors-as-values, evidence
   capture, and SCI-admit-clean returns (no lazy index seqs, no opaque
   Entity object into agent context). `listen!` stays system-side.
3. Every first-party `datahike.api` call site for those core functions
   migrates to `seon.db`; `datahike.api` requires remain only in
   `seon.db` itself and the store/registry custody owners. Each of the
   16 write sites is classified (runtime / boot / fixture) with its
   failure semantics stated before migration.
4. Through the door, an agent can ambiently `q`, `pull`, `transact!` a
   declared attribute, read it back, walk `history`, and get Datahike's
   own refusal value for an undeclared attribute.
5. AGENTS.md's facade/write-seam law, `architecture/data-model.md`,
   and `toolkit.md` state the landed reality in the same wave.

Blocked by: the ambient-connection blocker
([[agent-evals-never-bind-the-ambient-cluster-connection]]) and the
consolidation lane's in-flight files landing (the sweep collides with
them).
