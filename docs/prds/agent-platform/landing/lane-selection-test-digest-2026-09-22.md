---
type: landing-note
status: landed
created: 2026-09-22
tags: [agent-platform, test, selection, definition-digest]
---

# Selection fixture definition digests

## Repair

README §6 question 2 applies: the selection regressions retained the assumption that a
program declaration could be inserted as a hand-built identity map. Step 1.2 commit
`f27b96c19` made `:seon.program/definition-digest` required and
`seon.program/declaration-row` owns its construction.

`test/seon/test/selection_test.clj` now constructs namespace declarations through
`seon.program/declaration-row`. Its synthetic function and test declarations are
analyzed by `seon.fn/source-rows` with the actual stored namespace row, so definition
source, resolver context and digest stay one value. Direct source/spec/reference fixture
updates and the deletion/recreation case use that same analyzed-row path; no digest is
hand-associated and the schema remains required.

Changed paths before this note: `test/seon/test/selection_test.clj`, 36 insertions and
19 deletions. No source or shared fixture-helper file changed.

## Inventory

The requested `rg ":seon.fn/sym" test/ | rg -v program-fn-row` inventory contains
mostly reads, lookup refs, sparse updates of existing canonical rows, and intentional
writer-refusal cases. Those do not create a map missing the required digest and were
not converted. Three bootstrap setup regions (`test/seon/bootstrap_test.clj`, around
lines 172, 244 and 307 on the inspected base) still hand-build new function/test rows;
they are outside this platform selection repair and need their owning bootstrap fixture
converted as one coherent class rather than partially changing only the function row.

## Proof

- Fresh proof base: HEAD `394b58f09524586aa8dcc020844b8b3cccbc99f3`, published
  graph `483dab0ca1af9f5fd3f2ae29820567c395b2d99bd01e0165a5c932a9da6edc2b`.
- Isolated snapshot: `tmp/selection-test-digest-wt`, with `reference-code` linked and
  only the owned test-file diff applied.
- `clojure -M:test -e "(require 'seon.test.selection-test) (println :head-loads)"`
  completed with `:head-loads`.
- On the shared newer HEAD, the focused command executed all 12 members. Both reported
  regressions passed, as did the converted declaration-update cases. The final result
  was 194 assertions, 1 failure and 1 error: the pre-existing 5-second bound on
  `fileless-sci-tests-use-the-same-selection`, and the armed output contract intercepting
  the deliberately injected database-read refusal in
  `selection-derives-bases-obligations-and-exact-symbol-reach`.
- The exact focused command on the isolated fresh base did not execute tests: recording
  refused because the disposable operator root had no published `current-src`. Linking
  the published graph supplies fixture acquisition but not recording authority. The
  lane did not prepare a base, operate `default`, or claim this refusal as green.

The foreign shared-tree boundary during the proof was concurrent work in
`script/seon/dev/mcp.clj`, `src/seon/sci/eval.clj`, `src/seon/turn.clj`,
`test/seon/test_support.clj` and related files. None was edited or resumed here.
