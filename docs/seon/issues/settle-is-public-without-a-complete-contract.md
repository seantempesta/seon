---
type: issue
status: open
severity: friction
tags: [issue, runtime, schema, test, wave/unreadable-reply]
---

# Give `seon.cluster.loop/settle!` a complete public contract

## Problem

`settle!` is a public function but has no complete Malli contract. The public
contract census correctly makes the omission red.

## Evidence

At clean commit `48eb25ab7`,
`seon.public-contract-test/every-fresh-public-function-has-a-complete-contract`
reported exactly `[settle!]` from `src/seon/cluster/loop.clj`. This is not the
vacuity class in [[public-contract-census-can-pass-with-no-subjects]] and not
an anonymous leaf from [[anonymous-runtime-contracts-have-recurred]]; the
function has no complete declaration at all. Evidence:
`tmp/full-gate-2026-08-10b.log:3449-3453`.

## Owner

Suspected owner: `seon.cluster.loop/settle!` and its existing named request,
transition-data, or result schemas. The `unreadable-reply` lane currently owns
this source file, so contract work must coordinate rather than overlap.

## Acceptance

- `settle!` has a complete named input/output contract matching every current
  call site.
- The public contract census reports no missing function.
- Generated contract values exercise the terminal transition without adding
  an anonymous `:any` or `:some` boundary.
