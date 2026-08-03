---
type: issue
status: resolved
severity: friction
tags: [issue, test, render]
---

# Make the opaque-reference test distinguish identity bytes from value bytes

## Problem

`seon.render.value-test/references-stay-opaque` rejected the substring `"42"`
anywhere in an opaque atom's rendered object identity. The JVM identity hash is
hexadecimal and may legitimately contain those two digits, so the assertion
could fail even when the atom's value remained completely opaque.

The same substring-scanning premise also appeared in
`seon.print-test/honest-named-and-object-faces`.

## Evidence

The full `bin/test` run retained at `tmp/test-runs/run.KLN0lY` failed at
`test/seon/render/value_test.clj:113` on 2026-08-02. The preceding assertion
passed, proving the text had exactly the expected
`#object[clojure.lang.Atom 0x<hex>]` shape, while the substring assertion failed
because that permitted hexadecimal identity contained `42`.

Commit `f05267a66` replaces both substring assertions with the structural
contract. An opaque `IDeref` node has exactly `:seon.print/face`,
`:seon.print/class`, and `:seon.print/address`; its face is
`:seon.print/object`, its class names the reference type, its address is
hexadecimal, and the emitted marker is derived exactly from those fields. A
deterministic `0x1f42ab` identity proves that value-like digits inside an
identity remain permitted.

A deliberate leaky projection added `:seon.print/rep "value=42"` and emitted
that representation inside the marker. The repaired test rejected it with 1
test, 3 passing assertions, 2 failures, and 0 errors. Against the current
implementation:

- `bin/test seon.render.value-test`: 9 tests, 29 assertions, 0 failures, 0
  errors.
- `bin/test seon.print-test`: 7 tests, 32 assertions, 0 failures, 0 errors.
- `bin/test`: 856 tests, 4,251 assertions, 0 failures, 0 errors.

The successful full-suite isolated operator root was
`tmp/test-runs/run.A6v84Y` and was removed by the runner on success.

## Owner

Render test honesty. Production rendering required no change.

## Acceptance

- The test proves the atom's value is absent by asserting the admitted node's
  exact closed shape.
- A deterministic identity containing `42` passes.
- A deliberately value-bearing object projection fails the repaired test.
- The focused render-value and print gates pass.
- The full suite passes.
