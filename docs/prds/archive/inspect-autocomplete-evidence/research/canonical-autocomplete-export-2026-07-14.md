---
type: research
status: active
tags: [research, agent, database, schema]
---

# Canonical autocomplete export evidence — 2026-07-14

## Dependency ledger

- ClojureScript `1.12.145` and Node's `crypto`, filesystem, path, and child
  process APIs implement deterministic serialization, hashing, and artifact
  publication. Relevant source grounding:
  `reference-code/clojurescript/src/main/cljs/cljs/core.cljs` (`sort`,
  `sort-by`, `clj->js`, and printing semantics).
- Datahike's immutable temporal database semantics are consumed only through
  `seon.db/at-coordinate`; the exact source owner is
  `reference-code/datahike/src/datahike/api/impl.cljc` (`as-of`). Historical
  config identity uses a Datalog attribute-presence query rather than assuming
  that old `:seon.config/id` schema is already unique.
- Malli's schema-reference walk is the existing implementation in
  `seon.agent.ctx/referenced-schema-block`, grounded in
  `reference-code/malli/src/malli/core.cljc` (`walk`, `-ref-schema?`, `-ref`).
  Export calls that owner once per row projection and deduplicates the resulting
  content-addressed closures at manifest level.
- The canonical runtime artifact identity is selected from the operator's
  published `artifact.edn`/`artifact-acme.edn`; publication remains owned by
  `script/seon/dev/artifact.clj`.
- Inspect-side code depends only on Python's standard `json`/`hashlib` plus the
  locked `src-inspect-ai` environment. It verifies and selects frozen rows; it
  does not own rendering, schema closure, row construction, or split assignment.

## Observed contract

`seon.repl.autocomplete/export!` retains its existing as-of turn walk and
serving projection. Its output is now one envelope:

- `manifest_id` is SHA-256 of a canonical `content` map;
- Git/projection/diff and runtime artifact identities are manifest-wide. The
  artifact application digest is the authoritative transitive identity for the
  compiled renderer; the content digest separately binds every exact rendered
  context, card, and schema-definition byte. A diagnostic runtime-root Git diff
  hash is not mislabeled as a dependency closure;
- each observed row carries one complete database coordinate, config/profile/
  schema-closure refs, stable content id, and deterministic split;
- referenced schema definitions are top-level content-addressed records and are
  emitted once per distinct closure; and
- exclusions, missing evals/coordinates, unresolvable coordinates, and context
  nondeterminism are retained as stable rejection records rather than counters
  alone.

Observed historical eval bundles declare `projection_mode = observed`.
Counterfactual and substantive targets are deliberately not fabricated by this
slice; they require the staged-world replay/scorer owner.

## Proof

- Live pure probe: two calls to `seon.repl.autocomplete/context` for `root` over
  the same running database value returned equal bytes; the active profile was
  `[:plan :transcript]`.
- Focused CLJS gate: `bin/test-cljs --test=seon.repl.autocomplete-test` — four
  tests, 43 assertions, zero failures. This includes repeat-export byte identity,
  complete coordinate fields, manifest-wide identities, row/split ids, schema
  closure refs, and retained excluded-turn evidence.
- Focused offline Inspect gate:
  `uv run pytest -q tests/test_autocomplete_manifest.py` — six passed. It rejects
  envelope, row, split, schema-closure, runtime-artifact, and rejection tampering.

The broad changed-test hook concurrently reports failures in the separately
edited turn-capture/runtime lane. They are not evidence against this focused
slice; the retained focused gates above are the acceptance evidence.
