---
type: research
status: active
tags: [testing, fixture, worktree]
---

# Preflight fixture common-directory resolution — 2026-09-19

Read AGENTS.md sections 0–5 and the assigned issue end to end. Applied the
data-oriented-clojure, clojure-testing and repl skills. The assignment's
no-default boundary supersedes ordinary development-cluster probing; no
command or evaluation targeted the shared default cluster.

## Verified evidence and dependency ledger

At HEAD `733d0f422dd080fc154f22fa3cc7a08ea90b223d`, these commands returned:

```text
git rev-parse --git-common-dir
.git
git worktree add --detach tmp/preflight-fixture-path-wt HEAD
git -C tmp/preflight-fixture-path-wt rev-parse --git-common-dir
/Users/sean/src/seon/.git
git worktree remove tmp/preflight-fixture-path-wt
```

The throwaway worktree was removed. The fixture's historical change is
`4133085b3`; `8dcd6faf7` changes platform metadata, which this fix preserves.
Clojure's vendored `reference-code/clojure/src/clj/clojure/java/io.clj:411–429`
explicitly refuses an absolute child in `io/file`. JDK 25's
`java.base/java/nio/file/Path.java:465–513` (installed source archive under
`/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/lib/src.zip`)
declares that resolve returns an absolute argument unchanged and resolves a
relative argument against the receiver. First-party Path resolution already
appears at `src/seon/fs.clj:193`.

The fixture now uses Path.resolve before canonicalization and parent selection.
Its default Git invocation remains unchanged. Its additional arity supplies
Git path-format options for one regression: real Git emits relative and
absolute common-directory spellings, both complete source fixtures are built,
their populated cache directories are checked, and their committed Git tree
identities must match. No subprocess or database stand-in is used.

## Verification

Command (one foreground invocation):

```sh
bin/test-fast --paths test/seon/dev/fresh_operator_reset_test.clj -- seon.dev.fresh-operator-reset-test
```

The runner selected HEAD plus only the owned test file. Its snapshot was
`tmp/test-runs/run.rMTzDH`; the announced published graph was
`ef38fb0f0f60f42767cd2fbb2ebc02196f1acbc3fd83e977767e36261a726df3`, seven
commits behind HEAD. Contracts armed at `2026-09-19T18:26:36.697661Z`:
1230 registered and instrumented, 1227 program-armable. The namespace ran
from `18:26:37.080915Z` through `18:32:12.923611Z`.

Fast tally: **12 tests, 146 assertions, 1 failure, 1 error; exit 1**.
The three assigned preflight tests and the new two-spelling regression passed.
The unchanged-cache test reported lint 170 ms and cache-current check 120 ms.
The syntax-refusal test reached reset, start and init assertions, reporting
elapsed milliseconds `4686797/2000`, `2432672667/1000000`, and
`134397763/62500`, respectively.

Exactly these namespace tests ran:

- `an-exceeded-preflight-bound-names-its-phase-and-subprocess`
- `reset-phase-records-derive-incomplete-status-and-continuation`
- `a-child-deadline-keeps-output-and-stops-at-its-phase`
- `reset-phase-failure-stops-the-command-and-retains-evidence`
- `lifecycle-holder-evidence-is-immediate-and-stale-records-are-reclaimed`
- `an-unchanged-dependency-digest-preflights-without-a-cache-population`
- `phase-duration-is-visible-on-success-and-refusal`
- `source-syntax-refuses-before-lock-or-destruction`
- `reset-census-and-stale-repair-name-the-source-process-and-log`
- `cluster-boot-omits-test-namespaces-and-in-process-run-loads-one`
- `managed-root-cleanup-loads-no-program-and-never-follows-symlinks`
- `preflight-source-checkout-accepts-both-git-common-directory-spellings`

## Foreign verification boundary

Both red counts belong to
`cluster-boot-omits-test-namespaces-and-in-process-run-loads-one` at lines
380/382. Its publication child PID 92606, started `18:28:53.353Z`, exceeded
the declared 180000 ms process-exit deadline; the error reported
`:seon.error/kind :seon.operator.subprocess/deadline-exceeded` and
`:seon.operator.subprocess/reaped? true`. This is earlier than the recovery
failure previously recorded in
`docs/seon/issues/isolated-reset-boot-test-closes-readiness-during-recovery.md`;
it is not evidence for that earlier failure's cause.

Raw child output was at
`tmp/test-runs/run.rMTzDH/tmp/fresh-operator-test/4fecfe9b-ad71-4983-9f17-18c2d5ea8ed3/data/operator/operations/jvm-7b638271-ebbc-46a6-84ab-9f1b53f1d23a.log`.
Before fixture cleanup, its latest observed progress was:

```text
program population compiled: 30619 entities, 23656 identities, 39295 keyword facts
```

The fixture then reported zero process records, no recorded JVMs to stop,
and a free store flock. The fast runner continued through both remaining
tests and exited. No held implementation was changed or foreign lane operated.
The documentation hook also reported 46 existing citation errors, including
stale gitlink citations in the unrelated
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`.

Cold proof remains owed by the orchestrator: `bin/test --platform`.
No cold gate was run by this lane.

## Owned files

- `test/seon/dev/fresh_operator_reset_test.clj`: fixture and one regression only.
- `docs/seon/issues/preflight-source-fixture-rejects-absolute-git-common-directory.md`: resolution status and evidence.
- `docs/prds/steward-platform/research/preflight-fixture-path-2026-09-19.md`: this note.

Unrelated working-tree edits were preserved; no held source file was edited.
