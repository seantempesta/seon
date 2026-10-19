---
title: "P8 review: c2665897b (automatic testing never boots a JVM)"
type: research
status: review, 2026-10-19
created: 2026-10-19
---

# P8 review: `c2665897b` (automatic testing never boots a JVM)

Lane p8-review (Opus, independent; did not write the change), 2026-10-19. Reviewed HEAD `c2665897b` against
CLAUDE.md, `2026-10-17/merge-hybrid.md` (P8 row and the deletion table), `2026-10-17/merge-rulings.md` ("No
automatic JVM boots") and the diff. Probes: two `bin/seon check` runs of a disposable test over HEAD plus that test
(candidate `fbd54253`, runs `298f32c99fed` 7/7 and `76ef639a0cd2` 2/2; the test is kept at
`tmp/p8-review/p8_review_probe_test.clj`, never committed), and read-only JVM-mode queries on default (running
`a92ba5b94`, the parent, whose `merge-members` and `seon.fn/selection-walk` P8 does not change). No SCI request was
made as root on default.

## Verdict: NOT SAFE TO RESTART ONTO

The JVM boot is gone from the merge, and the new refusal is computed once and judged identically by done and the
merge. But on HEAD (runtime-exact reverted by `a92ba5b94`) the refusal fires for most ordinary changes, and once
it fires there is no path that lets the chain merge, whatever root proves. After a restart, a chain that changes a
`src` function with indirect test coverage bails permanently.

Blockers:

1. **B1. Most `src` changes bail** (finding 1). About 80% of `src` functions defer their reaching tests, so done
   refuses those chains.
2. **B2. Nothing root does clears the refusal** (finding 2). No form exists that proves the tests and then lets the
   chain merge.
3. **B3. The step still offers a JVM boot to the agent, and the refusal offers forms that cannot clear it**
   (findings 3 and 4). One of those forms is the removal of the function the agent is changing.

Either land P8 together with runtime-exact's runtime, so the platform-caller tests leave `:seon.source/deferred`
(the commit already names this dependency), or keep P8 off the restart until B1–B3 have their fixes.

## Findings, ranked

### 1. Blocker: on HEAD, about 80% of `src` function changes bail the chain to root

**Reproduced.**

`seon.cluster.source/merge-members` puts `seon.fn/selection-walk`'s `::platform-tests` into
`:seon.source/deferred`. These are tests that reach the change only through a loaded `src` caller. The ruling's
case, a host-bound declaration, is a different and much smaller set. Before P8 the done rule exempted deferred
members, and the merge booted a JVM for them. P8 now refuses them in `done-refusals`. With the runtime that would
interpret those callers reverted, that turns almost every `src` change into a bail.

Measured with read-only JVM-mode queries on default:

- **Population.** Of 200 sampled non-host-bound `src` functions (4,964 total), `selection-walk` gave 160 a
  non-empty `::platform-tests` set. 29 had branch-observable tests only.
- **Examples.** `merge-members` of one changed function:

  | Changed function | Members | Deferred |
  |---|---|---|
  | `seon.db/conflict-value` | 17 | 17 |
  | `seon.profile/quantile-position` | 6 | 6 |
  | `seon.mine/top` | 3 | 3 |
  | `seon.task/template` | 5 | 2 |

  Every one of these is a done refusal under P8.
- **The commit's own test agrees.** `seon.cluster.source-test/a-hub-change-defers-the-tests-above-its-src-callers-to-root`
  asserts more than 100 deferred members for `seon.db/as-of-key`.
- **Check-JVM reproduction.** `p8-a-src-hub-change-with-every-member-green-is-still-refused` (run `298f32c99fed`)
  takes the real member list of `seon.db/as-of-key` and records a green result for every member. `done-refusals`
  still answers exactly one `:seon.source/test-evidence-error`, whose `:seon.error/offending` is the whole sorted
  deferred set.

The ruling reads: "When a chain's change reaches a host-bound declaration, the done rule says so as data and the
chain bails to root." A test reaching a change through an ordinary SCI-runnable `src` caller is not that case. The
hybrid's P8 row also expects these tests to leave `:seon.source/deferred` once runtime-exact lands. P8 is therefore
correct only on top of runtime-exact, and HEAD does not have it.

### 2. Blocker: once refused, the chain has no path to merge

**Reproduced.**

The refusals are unconditional:

- `done-refusals` emits the deferred refusal on `(seq deferred)` alone.
- It emits the host-bound refusal on `(seq host-bound)`, read from the branch's changed identities.
- Neither reads any result. The probe above shows that green results for every deferred member change nothing.

What root has, and why none of it works:

- **`bin/seon check`** (the refusal's `:seon.error/fix`: "Root proves it deliberately with bin/seon check, one
  --test per test.") runs in a throwaway JVM. It records nothing on the chain branch, and the rule would not read
  it if it did.
- **`my.root/run!`**, the documented response to a bail (`my.root/instructions`: "Change the task there and run it
  again"), reruns the same task. It gets the same refusal, and each rerun costs a hand act.
- **`my.root/merge!`** goes through `seon.merge/merge!` → `prepare-merge!` → `judged-merge!` → `done-refusals`, and
  is refused the same way.
- **`my.test/check-platform`** records results with `:seon.test.run/git-sha`. `done-refusals` does not read those
  either.

The only way out is outside the model: root hand-writes the branch's files, commits them with `bin/lane commit`,
and then abandons the chain. Neither TASK-CHAIN §2.4 (as amended), MERGE-STEP nor `my.root/instructions` documents
that. MERGE-STEP does not mention the P8 refusal at all.

A design question for the owner follows: what value, once root's proof exists, should make `done-refusals` stop
refusing? One candidate is a run on the chain branch carrying `:seon.test.run/git-sha` that covers the deferred
members, which is what `seon.cluster.branch-evidence/branch-evidence` already accepts for `bin/lane commit`.
Without such a value, "bails to root" means "can never merge through Seon".

### 3. High: the automatic step still offers the agent a form that boots a JVM

**Reproduced from code.**

`seon.turn/platform-proof` is still called on every eval request that changes a host-bound function, from the
automatic test step's run-reaching path in `seon.turn`. It returns `:seon.turn.loop/platform` with
`:seon.render/operations ['(my.test/check-platform {})]`. `my.test/check-platform` →
`seon.test.platform/run-check!` → `bin/seon check --at`, a throwaway JVM that takes about 120 s (`seon.mcp` advises
`timeout_ms` 120000).

The commit keeps this for P9a/P9b on the grounds that it boots nothing automatically. That is literally true of the
step. But the step's output now tells a product agent (DeepSeek) to boot a JVM, which the ruling says is root's
alone. The result of that boot also cannot clear the refusal (finding 2). The agent spends about 120 s and a JVM
slot, and the chain bails anyway.

No other boot remains:

- `run-check!`'s only caller is `my.test/check-platform`.
- `isolated-run` and `check-files!` are deleted.
- The remaining `babashka.process` uses are git, clj-kondo, `seon.cluster.process` and `my.shell`.

### 4. High: the refusal's forms send the agent the wrong way, and its type cannot be told apart

**Reproduced.**

`seon.context/refusal-operations` builds each refusal's forms from `:seon.error/offending`. Probe
`p8-the-agent-is-offered-forms-that-cannot-clear-the-bail` (run `76ef639a0cd2`) shows two cases:

- **Host-bound function no test reaches** (`tq.p8b/hosted`, using `reify`). The agent is offered
  `(my.program/callers 'tq.p8b/hosted)` and `(my.program/ns-unmap! 'tq.p8b/hosted)`, which is the removal of the
  function it is changing.
- **Deferred member.** The agent is offered `(my.test/check {:seon.test/identities '#{…}})`, a branch run of a test
  that, by definition, the branch cannot observe.

Both refusals reuse `:seon.source/test-evidence-error`, the same variant as "a proof with no current result". The
distinction lives only in `:seon.error/message` prose. So nothing can branch on it as data:

- not the agent;
- not `seon.context/step-section`;
- not a future immediate bail.

The agent keeps working a refusal it cannot act on until it waits or hits its cap. Only then does `advance-chains!`
bail with "The done rule refused its done." CLAUDE.md's "a changed meaning takes a new name" applies: this needs
its own declared variant, or a declared member, so that the context renders "root's" instead of offering forms.

### 5. Medium: missed refusal when a host-bound function is deleted

**Reproduced.**

The host-bound query is `[?e :seon.fn/sym ?f] [?e :seon.fn/host-bound? true]` over the branch, so a function the
branch retracted is not found. In the probe `p8-a-deleted-host-bound-function-is-not-refused` (run
`298f32c99fed`), the identities hold `[:seon.fn/sym seon.cluster/warn-low-space!]`:

- With the entity present, `done-refusals` answers one refusal naming it.
- After `[:db/retractEntity [:seon.fn/sym seon.cluster/warn-low-space!]]`, it answers `[]`.

Deleting a compiled declaration is platform work that a branch cannot prove either: the platform keeps the
compiled Var until merge and reload. Reading host-bound status from the merge base, or from main, for identities
the branch no longer holds would close the gap.

### 6. Medium/low: the host-bound refusal reads the raw delta, not what the merge carries or a test observes

**Reproduced in part.**

`judged-input` passes `:seon.program/identities` as the raw `(change database)`, not
`seon.cluster.source/merge-changes`, which drops the agent's own namespace, and not
`seon.test/observable-changes`. Two consequences:

- **Scratch namespaces.** A host-bound helper in an agent's own scratch namespace bails the chain even though the
  merge never carries it. Reproduced: `tq.p8/hosted` in a fresh namespace is refused, while `tq.p8/plain` beside it
  gets `[]` and has no deferred member. Whether the agent-owned filter would apply on a live branch is not
  reproduced.
- **Docstring-only edits.** A docstring-only edit to a host-bound function bails, although the member list treats
  docstring-only changes as observing nothing. This is inferred from the code and not reproduced.

`contract-gaps` reads the same raw delta, so this is existing practice, not new to P8.

### 7. Low: stale references to the deleted platform run

**Reproduced with `git grep` at HEAD.**

These still say the merge's platform run proves the deferred tests:

- Docstrings of `seon.fn/closure-walk` ("the merge's platform run proves the tests above them"),
  `seon.fn/selection-walk` ("proven by the merge's platform run") and `seon.fn/reaching-tests` ("only the platform
  run observes them").
- `seon.test/host-exclusions`' docstring ("a merge gate … runs them on that candidate's own platform host").
- `seon.test/isolated-members`' docstring ("which the platform host does").
- The `seon.sci.branch-execution-test` test
  `a-file-test-reaching-a-change-only-through-src-callers-is-left-to-the-platform-run` and its comment ("the
  merge's platform run proves it").

The keys themselves were fully converted:

- `isolated-run`, `check-files!`, `:seon.source/platform-run` and `:seon.source/platform-ms` have no live callers
  in `src`.
- The remaining hits are `seon.cluster.branch-evidence`'s private `platform-run?`, which is a different concept:
  runs carrying a git sha.
- `seon.merge-test`'s negative assertion that `:seon.source/platform-run` is absent.

These are context defects under CLAUDE.md ("living documents cite code by symbol … a stale skill is a
high-priority defect").

### 8. Low: the merge test's "booted no JVM" assertion is a deletion check

`seon.merge-test/a-file-reading-test-bails-the-merge-to-root-and-boots-no-jvm` asserts that
`profile/calls-of 'seon.test.platform/run-check!` is 0. With `check-files!` deleted, nothing on the merge path can
reach `run-check!`, so the assertion cannot fail. It records the deletion rather than guarding the behavior. It is
harmless, but it is not evidence that no other boot path exists; finding 3 shows one is still offered.

## Answers to the assignment

1. **Is any JVM boot left?** None in the done rule, the merge or publish. The automatic step still hands every
   agent that changes a host-bound function `(my.test/check-platform {})`, which boots one (finding 3).
2. **Is the new refusal exact?**
   - It is one derivation: `done-refusals`, used by `advance-chains!`, `finish` and `judged-merge!`.
   - It names its subjects as data under `:seon.error/offending`.
   - It is not exact. On HEAD it refuses about 80% of `src` changes for tests the ruling does not name (finding 1).
   - It misses deleted host-bound functions (finding 5).
   - It refuses scratch-namespace host-bound helpers, and by code reading docstring-only edits, that the merge or a
     test would never see (finding 6).
3. **Deletions:** all callers were converted; stale prose remains (finding 7).
4. **Can root act?** No. No form makes a refused chain merge after root proves the tests (finding 2). The refusal's
   `:seon.error/fix` names `bin/seon check`, whose results nothing reads. TASK-CHAIN §2.4 says root "proves them
   deliberately" and stops there. MERGE-STEP is silent.
5. **Laws:**
   - No fence was added. The refusal is a judgment in the one done rule, and the deleted code is a real deletion.
   - Two different meanings share one error variant, `:seon.source/test-evidence-error`, and differ only in prose
     (finding 4).
   - No new key: `:seon.source/deferred`'s description was revised in place, and `:seon.source/platform-run` was
     removed from both schema and producer.
   - The host-bound `db/q` takes symbols only, so its invalid-read variant (nil inputs) cannot arise. There is no
     unhandled variant.

## Issues

The assignment allowed committing only this document, so the root causes above are recorded here and not yet filed
in `docs/seon/issues/`. Root should file:

- **Finding 1 with finding 2:** deferred-through-`src`-callers refuses with no merge path.
- **Finding 3 with finding 4:** the agent-facing platform offer and refusal forms.
- **Finding 5:** the deleted host-bound function the rule misses.
