---
type: design
status: design note (lane gate-candidate, Opus, 2026-10-16)
created: 2026-10-16
---

# The merge gate judges the candidate (lane gate-candidate, 2026-10-16)

Task: `seon.merge/the-merge-gate-judges-main-s-files-not-the-candidate`. Live case: a9e4e9904 merged green while
breaking `seon.babashka-load-test/declared-namespaces-load-in-babashka`.

## What exists, and the data flow

`seon.cluster.source/prepare-merge!` computes the delta, forks a merge branch S off main, and
`seon.cluster/candidate-gate!` writes the delta onto S and runs `seon.cluster/reaching-run` there: one
`seon.test/run` (`:named`, `:seon.test/changed`) under S's checked-out connection. Two separate defects:

1. **Selection.** `reaching-run` keeps only `:seon.fn/sym` and `:seon.test/sym` changes. `seon.test/selection-seeds`
   already accepts `[:seon.ns/name n]` (it seeds the namespace's members), but nothing selects a test that reads a
   namespace's *file* rather than calling its functions. The bb-load test calls nothing in `seon.edit`; it reads
   `src/seon/edit.clj` and hands it to a `bb` subprocess.
2. **Input.** Under the gate the test body runs in default's JVM, whose `user.dir` is the committed archive it booted
   from (`data/source/fce9d823e…` at the time of writing, probed with `(System/getProperty "user.dir")`). A test that
   reads `"src"` reads that archive: neither main's current files nor the candidate's. The candidate's bytes exist only
   as `proven-files` output (`:seon.source/candidate-bytes`, which the gate computes under `:root` for its lint) and
   reach disk at write-back, after acceptance.

## Counts (`rg -l` over `test/`, 2026-10-16)

| pattern | files |
|---|---|
| `slurp` | 55 |
| `io/file` | 93 |
| `shell/sh`, `process/process`, `babashka.process` | 17 |
| a literal `"bb"` | 7 |
| reads the program tree (`"src"`, `"script"`, `"resources"`, `"src/…"`) | ~70 |

Most `io/file`/`slurp` hits read fixtures the test itself wrote under a temp dir; those judge nothing of the program.
The ~70 that read the program tree are mostly whole-program checks (the census tests, docstrings, public contracts,
publication over the real tree, bb-load). Reading the program from disk is therefore **not rare**: the rule must be
general, not a bb special case.

## Choice

**Input: materialize the candidate root, as `bin/seon check` does (HEAD plus the slice).** The gate already holds
the candidate's file bytes (`proven-files` under `:root`); write them over a copy of main's tree into a disposable
directory keyed by the merge branch's commit id, and let program-reading tests resolve their root from the
checked-out branch (`seon.test/program-root`: the candidate directory of the checked-out commit when the gate made
one, else the JVM's `user.dir`). This is git CI's model whole: the tests run in a checkout of the merge result.
The alternative, giving tests the program text from namespace rows and file facts, fails on the count: the database
holds each file's digest (`:seon.fn.file/digest`) and each declaration's source, not the file bytes (comments,
order, reader conditionals), so every reading test would need a second write-back to reconstruct them, and a `bb`
subprocess needs real files on a classpath anyway.

**Selection: declared reads, as a selector over namespace metadata.** Recording per-test reads in
`seon.test.runner` cannot see this class at all: the bb test's reads happen in a child process. So the test declares
what it reads, `{:seon.test/reads #{:seon/babashka}}`: every namespace whose `ns` metadata carries that key. The
marker is lifted to an indexed fact by the existing rule (`seon.program/test-marker-attributes`,
`seon.program/test-markers`), and `seon.test/selection-seeds` joins a change in a namespace (its `ns` row or any
declaration in it, since bb loads the whole file) whose metadata holds `k` to the tests with `:seon.test/reads k`.
`reaching-run` passes namespace changes through instead of dropping them.

## Phases

1. Selection: `:seon.test/reads` attribute (`seon.test.edn`, `:seon.test/test`), lifted marker, `selection-seeds`
   join, `reaching-run` passing `:seon.ns/name`. Proof: a changed `^{:seon/babashka true}` namespace selects the
   bb-load test.
2. Input: gate writes the candidate root from `proven-files`; `seon.test/program-root`; the bb-load test reads its
   roots from it. Proof: a fixture branch that breaks a bb namespace refuses at the gate.
3. Sweep: convert the remaining program-reading tests to `seon.test/program-root` (scripted, one commit).

Status: see the lane report; phases not committed are listed there as remaining.
