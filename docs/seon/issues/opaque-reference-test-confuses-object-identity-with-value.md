---
type: issue
status: open
severity: friction
tags: [issue, test, render]
---

# Make the opaque-reference test distinguish identity bytes from value bytes

## Problem

`seon.render.value-test/references-stay-opaque` rejects the substring `"42"`
anywhere in an opaque atom's rendered object identity. The JVM identity hash is
hexadecimal and may legitimately contain those two digits, so the assertion can
fail even when the atom's value remains completely opaque.

## Evidence

The full `bin/test` run retained at
`tmp/test-runs/run.KLN0lY` failed at
`test/seon/render/value_test.clj:113` on 2026-08-02. The preceding assertion at
lines 110-112 passed, proving the text had exactly the expected
`#object[clojure.lang.Atom 0x<hex>]` shape, while the substring assertion
failed because that permitted hexadecimal identity contained `42`.

Neither `test/seon/render/value_test.clj` nor `src/seon/render/value.clj` had a
working-tree diff at the failure. The failure blocked the full gate after the
changed `seon.custody-stability-test` and `seon.db-test` namespaces had passed.

## Owner

Render test-honesty wave. The production renderer is not implicated by this
evidence; the failing assertion is the owner.

## Acceptance

- The test proves the atom's value is absent without searching inside the
  allowed hexadecimal identity segment.
- A deterministic regression exercises an identity containing `42` and passes.
- `bin/test seon.render.value-test` and one full `bin/test` run pass.
