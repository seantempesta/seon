---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, bootstrap, context, architecture]
---

# Delete the hand-authored bootstrap plan from the generated opening

## Problem

The fresh-agent opening still reads a manually authored EDN vector, stores it
as a bootstrap-plan entity, substitutes a namespace placeholder in its source
strings, and freezes those strings as the system run. This is the exact
`bootstrap.edn` plus stored-form-plan mechanism that rulings 24 and 28 delete.

The generated-episode functions added beside it do not make the opening
generated while `seed-tx` still consumes the authored plan. The conversion has
two opening mechanisms in one namespace.

## Evidence

`resources/seon/bootstrap.edn:1-70` is a twelve-form authored episode containing
an authored help preamble, namespace placeholder, requires, queries,
demonstration function, result check, and completion prose.

The active source path remains complete:

- `src/seon/bootstrap.clj:82-114` reads the resource and exposes its authored
  help payload;
- `src/seon/bootstrap.clj:343-377` persists those rows as the default bootstrap
  plan;
- `src/seon/bootstrap.clj:379-426` queries the stored plan, checks its manual
  ordinals, and substitutes `{{seon.ns/name}}` in source text; and
- `src/seon/bootstrap.clj:451-513` digests those rows and freezes them into the
  agent's system-authored bootstrap run.

In the same current file, `src/seon/bootstrap.clj:116-186` and
`src/seon/render/walk.clj:654-729` implement the newer situation and
introduction-ordered episode pieces. This is visible in-flight churn: the
strict-dogfood audit did not edit either production file.

Ruling 24 in
`docs/prds/sci-execution-runtime/plan/self-generating-context-prd-2026-08-11.md`
requires `bootstrap.edn` and the stored form plan to be deleted; ruling 28 says
no manually assembled context may remain.

## Owner

`seon.bootstrap` owns fresh-agent opening construction. The surviving owner is
the executed render of the schema-derived walk, ordered by
`seon.render.walk/ordered-episode` and settled as ordinary system-authored
receipts.

## Acceptance

- `resources/seon/bootstrap.edn`, bootstrap-plan storage schemas, resource
  readers, placeholder substitution, stored-plan query/digest functions, and
  their tests are absent.
- A fresh agent's opening forms are derived solely from one current root pull,
  declared form/value renderers, and the introduced-symbol fixed point.
- The resulting system run records ordinary forms and terminal receipts; no
  manually authored preamble, demonstration list, or completion text enters
  context through a second path.

## Closure — 2026-08-13

Ruling 24 is implemented: `resources/seon/bootstrap.edn` is deleted and `seon.bootstrap/next-entry` generates the opening from live pulls (verified 2026-08-13; W1/W2 landed).
