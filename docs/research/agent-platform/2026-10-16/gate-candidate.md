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

**Input: the candidate's own platform host (root ruling, 2026-10-16).** A test that reads the program as files is
host-bound: it reads the working directory of the JVM that runs it. So a test declaring `:seon.test/reads` is an
isolated member (`seon.test/isolated-members`), which a development JVM never runs, and the merge gate runs the
isolated members its selection reaches on the candidate's own platform host: `seon.cluster/reaching-run` asks
`seon.test/run` with `:seon.test/isolate? true` (every isolated member deferred, whatever JVM asks), then
`seon.test.platform/check-files!` writes the `proven-files` candidate bytes (the files write-back would install) over
the published commit (`seon.test.platform/candidate-commit!`), runs the deferred members with `bin/seon check --at`
in a throwaway JVM booted from that commit (`seon.test.platform/run-check!`), and records their results on the merge
branch (`seon.test.platform/record!`). The prepared merge names that run (`:seon.source/platform-run`), and
`seon.cluster.source/merge-verdict` judges its results beside the gate run's, with every deferred member required:
one never run is unknown, never green. This is git CI's model whole: the tests run in a checkout of the merge
result. Earlier phases threaded a candidate root through test worlds (`candidate-root!`, `:seon.branch/program-root`,
`seon.test/program-root`, a reach digest keyed by read content); each step found another layer, and they are deleted.

**Selection: declared reads, as a selector over namespace metadata.** Recording per-test reads in
`seon.test.runner` cannot see this class at all: the bb test's reads happen in a child process. So the test declares
what it reads, `{:seon.test/reads #{:seon/babashka}}`: every namespace whose `ns` metadata carries that key. The
marker is lifted to an indexed fact by the existing rule (`seon.program/test-marker-attributes`,
`seon.program/test-markers`), and `seon.test/selection-seeds` joins a change in a namespace (its `ns` row or any
declaration in it, since bb loads the whole file) whose metadata holds `k` to the tests with `:seon.test/reads k`.
`reaching-run` passes namespace changes through instead of dropping them.

## Phases

1. Selection: `:seon.test/reads` attribute (`seon.test.edn`, `:seon.test/test`), lifted marker, `selection-seeds`
   join, `reaching-run` passing `:seon.ns/name`. Committed 5588d1329.
2. Input: a `:seon.test/reads` test is an isolated member; the gate runs its isolated members on the candidate's
   platform host. Proof: `seon.merge-test/the-gate-runs-a-file-reading-test-against-the-candidate-and-refuses`
   (a fixture branch that breaks a bb namespace refuses at the gate; a merge selecting no isolated member boots no
   JVM).

Status (2026-10-16): phase 1 committed (5588d1329); phase 2 by lane gate-platform (see its commit). No sweep of the
other program-reading tests is needed: a test reading the program tree declares `:seon.test/reads` when it should
judge a merge.
