---
type: issue
status: resolved
severity: friction
tags: [issue, render, schema]
---

# Refuse ambiguous render declarations from overlapping open schemas

## Problem

Open maps correctly allow one value to validate against multiple schema shapes.
The former render consumers used `some` to take the first matching declaration,
so schema rank silently chose a projection when several declarations fit.

## Evidence

The surviving selector obtains every contract-fitting public function in the
explicit owning namespace, sorts the qualified symbols, and refuses unless the
fit is unique (`src/seon/render.clj:111-149`). Schema-property producers use the
same distinct, sorted, unique-or-loud rule (`src/seon/render.clj:157-169`). The
walk derives ownership only from an explicit namespace fact or ref and calls
that selector (`src/seon/render/walk.clj:448-476,650-662`).

## Owner

The render projection-resolution chain, using declared schema and program
facts.

## Acceptance

`seon.render-simplification-test/overlapping-contracts-refuse-loudly-and-deterministically`
proves both the direct typed renderer and the distance walk return one flat
ambiguity value naming both candidates in deterministic order. The focused
step-3 gate passed 9 tests and 34 assertions except the deliberately unopened
step-7 and step-8 falsifiers. Resolved by the step-3 render implementation
commit.
