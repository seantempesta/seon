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
  exactly ONE namespace in src/ (`seon.render`). The namespace was
  intended as the one database surface and never adopted.
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
[[unlogged-findings-2026-08-01]] item 5 (its `seon.db` completion-wave
destination folds into this issue). Vocabulary (ruling #41 amendment):
the word is `seon.db`, never "facade" — it is the db namespace, and the
interception exists so Datahike failures return as flat error values
and custody defaults to the calling agent's cluster.

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
   TWO INTERFACES PER FUNCTION (ruling #41 amendment): Datahike's own
   positional arity AND Datahike's own argument-map arity — `{:query
   :args :offset :limit}` for `q`, `{:selector :eid}` for `pull`,
   `{:tx-data :tx-meta}` for `transact!`, `{:index :components}` for
   `datoms` (specification.cljc is the authority; never an invented
   envelope) — and BOTH forms may elide the db/conn to assume the
   calling agent's cluster's current database.
3. Every first-party `datahike.api` call site for those core functions
   migrates to `seon.db`; `datahike.api` requires remain only in
   `seon.db` itself and the store/registry custody owners. Each of the
   16 write sites is classified (runtime / boot / fixture) with its
   failure semantics stated before migration.
4. Through the door, an agent can ambiently `q`, `pull`, `transact!` a
   declared attribute, read it back, walk `history`, and get Datahike's
   own refusal value for an undeclared attribute.
5. AGENTS.md's database-access law, `architecture/data-model.md`, and
   `toolkit.md` state the landed reality in the same wave, and no
   current authority calls `seon.db` a "facade".

Blocked by: the consolidation lane's in-flight files landing (the
sweep collides with them). The ambient-connection blocker
([[agent-evals-never-bind-the-ambient-cluster-connection]]) RESOLVED
2026-08-02 (`643719904`) — the ambient default is real; its two loose
ends transfer here: `bootstrap_drive.clj:141-155` holds a connection it
does not yet pass in its evaluation request, and the render walk's own
binding site remains the render owner (custody during an agent
evaluation now comes from the evaluator request, not the walk).
