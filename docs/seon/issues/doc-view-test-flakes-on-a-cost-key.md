---
type: issue
status: open
severity: friction
tags: [issue, test, context, flake]
---

# `a-referenced-function-shows-its-doc-view` flakes: `(doc x)` can carry `:cost`, the must-reads entry not

`seon.context-test/a-referenced-function-shows-its-doc-view` asserts
`(= (doc seon.render/render-call) <its must-reads entry minus :seon.ref/name and :seon.ref/level>)`.

Observed 2026-10-19 (lane merge-p8, candidate of P8, a change to `seon.cluster.source/done-refusals` and the merge
gate only): red in two `bin/seon check` runs (07fbce4d9035, 76dc22b53982), then green in two more on the same
candidate (3994bd4879ba, b7e4969f5698); green on HEAD ce3b89669 in 86ba8ff2dc81 and 150a25af4123.

Evidence for the cause (verified on default, not yet in a red check run): evaluating the comparison's two sides on
default, `(keys (doc seon.render/render-call))` was `(:arglists :cost :in :out :schemas :summary :supplied)` while the
must-reads entry's keys lacked `:cost`, with the profile evidence `:seon.profile/alloc-max-bytes ...` under it. In a
fresh check JVM both sides had the same six keys and were equal. So the doc view gains `:cost` once
`seon.render/render-call` holds enough profile evidence, and the context's must-reads entry is built without it (or
before it): two derivations of one doc view.

Owner: the doc view's producer (`doc` and the must-reads reference resolver should be one derivation). The test
asserts the right law; do not loosen it.
