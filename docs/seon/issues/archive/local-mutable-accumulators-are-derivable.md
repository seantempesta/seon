---
type: issue
status: resolved
severity: cleanup
tags: [issue, runtime, derived-state, tooling]
---

# Replace derivable local mutable accumulators

## Problem

Several invocation-local atoms and volatiles were used only as imperative
accumulators. They coordinated no callback, thread, resource, or protocol and
could be expressed as ordinary loop/reduce state. Their locality made them
non-durable, but locality alone does not make mutation necessary.

## Evidence

- `script/seon/dev/markdown.clj:168-204,675-696,895-908` accumulated extracted
  links, output lines, and a fix count in three atoms.
- `src/seon/fs/jvm.clj:629-704` mutated one traversal map through recursive
  `glob-walk!` calls.
- `src/seon/fn.clj:1309-1331` mutated a progress count derivable from indexed
  batches.
- `src/seon/fn/schema_shape.clj:243-280` mutated a recursive `seen` set.
- `src/seon/render/walk.clj:305-377` mutated the remaining-node count and seen
  entity set through a recursive walk.
- `src/seon/flow.clj:335-343,632-640` allocated throwaway empty atoms only so a
  shared terminal helper could call `swap!` on a value nobody observed.

## Resolution

All six sites landed on 2026-08-07. A structural census of the named owners
now finds no mutable constructor at any of them.

| Site | Commit | Shape it became |
|---|---|---|
| `script/seon/dev/markdown.clj` | `7706a4279` | `extract-links` is one transducer over indexed lines with a pure `line-links`; `fix-blanks-around-headings` and `fix` are reduces |
| `src/seon/fs/jvm.clj` | `7706a4279` | traversal state is `glob-walk`'s return value; the recursive child hands it back to its parent and an early stop is one `reduced` |
| `src/seon/fn.clj` | `e9783af2c` | `commit-phase!` threads the committed row count through a reduce over batches |
| `src/seon/fn/schema_shape.clj` | `6630c1e27` | `encode-form`, `entry-row`, and `child-row` return `[row seen]`; an `ordered-rows` reduce threads it |
| `src/seon/flow.clj` | `ac831976b` | `io-terminal!` gained a three-argument UNTRACKED arity, so a refusal has no tracking argument to supply and no atom to allocate |
| `src/seon/render/walk.clj` | `88ecc7167` | `node` and `child` return `[node state]`; a `children` reduce threads the budget and rendered set |

Behavioural proof, beyond the focused namespace suites:

- `seon.fn.schema-shape/shape-row` output is `=` to the pre-change
  implementation on seven schemas including a shared-subshape dedup case
  (both namespaces loaded side by side, 2026-08-07).
- `seon.dev.markdown-test` 26 tests / 350 assertions green;
  `seon.fs.jvm-test` 10 / 45 green; both after the change.
- `seon.render.walk` verified after the tree recovered from a foreign parse
  error: `seon.render-coverage-test` 3 tests / 83 assertions green and
  `seon.render-simplification-test` green, both of which call
  `walk/neighborhood` directly. `seon.render.transcript-test` carries 28
  failures that belong to another owner — the failing tests, line numbers,
  and counts are identical with and without this change.
