---
type: research
status: dig (facts only, no recommendation); pinned to HEAD dfd6c00f6 at reading
created: 2026-10-14
question: does the REPL-first commit path (agent branch defn, reaching tests on the branch, my.program/write-branch!, bin/lane commit --branch-evidence) work end to end reliably, or does data get lost?
---

# Dig: the REPL-first commit path, end to end

Each row is VERIFIED (read in source or git history, or observed in a task/note entry with its date) or UNVERIFIED (inferred, or not run this session). No source was edited; no JVM was started.

## 1. History

| Fact | Status |
|---|---|
| `bin/lane commit --branch-evidence` was introduced by 9bfc3cc93 (impl-rf2, `git log -S` on `bin/lane`), after cards RF-1..RF-6 (bfc7e04c7). | VERIFIED |
| 11 commits carry a `Branch-evidence: agent` trailer: 3 on 2026-10-08, 4 on 10-09, 2 on 10-10, 2 on 10-12. | VERIFIED (`git log --all --grep 'Branch-evidence: agent'`) |
| About 63 commit subjects mention write-branch, branch-evidence or `--branch`. Subject grep only, not a full count of bodies. | VERIFIED (approximate) |
| The 2026-10-08 dogfood pilot (`docs/research/agent-platform/2026-10-08/dogfood/pilot.md`) logged write-back friction: an undigested scratch namespace refused; "The accepted merge changed no declaration" when write-branch! ran in the same request as the defn (friction 10); a declaration replaced at the wrong span. | VERIFIED (note) |
| `bin/lane` still advertises `--accept-red-before TEST` (usage line 10, `branch_evidence` argv), though the R9 plan (`docs/research/agent-platform/2026-10-12/hybrid-test-results.md`) says that list goes. | VERIFIED (stale-doc discrepancy) |

Failures the lanes or the owner reported (each with its cause as recorded):

| Failure (task or note) | Cause as recorded | Status |
|---|---|---|
| `a-lane-branch-does-not-survive-a-default-restart` (seon.cluster.branch-evidence.edn), 2026-10-08: after restart on e3b7bae3a, impl-rf2-dogfood held no branch; evidence unavailable until the defn was re-evaluated. | Recorded as observed; cause not stated. Current code (below) keeps branches of open agents, so the 10-08 cause is UNVERIFIED at HEAD. | UNVERIFIED now |
| `the-branch-evidence-commit-is-dominated-by-whole-tree-lint`: first commit took 10.8 s wall, gate 175 ms. | clj-kondo over whole `src script test`. | VERIFIED (task text) |
| `the-branch-evidence-gate-rarely-sees-red-before-on-main`: main holds no recorded results; tests run in throwaway JVMs. | by design of the gate. | VERIFIED (task text) |
| `write-branch-writes-files-the-lane-has-not-claimed` (my.program.edn): writes into unclaimed files; `bin/lane claim` then refuses them. | No claim check before write. | VERIFIED (task text); not reproduced |
| `diff-answers-empty-after-a-branch-write-back` (my.program.edn): after write-back, diff empty. | Cause unknown in the task. | UNVERIFIED |
| `write-branch-in-the-defn-request-answers-a-merge-changed-nothing`: same-request write reads the branch before the recording transaction. | Ordering: the write is evaluated before the defn settles. | VERIFIED (task text, pilot 10-08) |
| `write-branch-in-the-running-agent-harness-compares-with-a-home-branch-the-agent-did-not-fork-from`: :one-file refusals under the test harness; no end-to-end MCP regression possible. | Compares with `seon.cluster.branch/cluster-branch`, not the member's fork. | VERIFIED (task text) |
| `write-branch-of-one-function-allocates-a-gigabyte` (1,032 MB / 517 ms for one docstring change, 2026-10-09). | `seon.cluster.source/proven-file` and analysis. | VERIFIED (task text) |
| seon.cluster.source.edn 2026-10-09: second write-back of the same branch refused `:conflict`; base `:seon.program/absent`, because the write-back base does not advance past the branch's own first write. | Base tracking. | VERIFIED (task text) |
| seon.cluster.source.edn 2026-10-10: a `require` added to a test ns form refused "Unresolved namespace seon.db" (proof analysed against the published snapshot, which lacks the new require's namespaces). | Stated in the task; not reproduced. | VERIFIED (task text) |
| seon.db.edn 2026-10-14: on agent branches, defns that evaluate correctly in SCI are refused by `seon.db/write-digest-error` ("exists in your SCI context only"), e.g. defs of `(fn ...)` with `#(some-> ...)`, and long docstrings with `->`. | Stored digest vs definition digest mismatch on first define. | VERIFIED (task text, 2026-10-14); cause not in the task |
| chain-experiment-9.md P17 (2026-10-12): a model's defn of `scan` dropped all 15 `;;` comment lines; a write-back of it would delete them. | write-back replaces the declaration's span with accepted source; the owner has not ruled on comments. | VERIFIED as an observation; loss not reproduced |
| `seon.test.platform` 2026-10-10: a changed host-bound function cannot be proven at the REPL; only `bin/seon check --test` proves it. | Old compiled Var runs in tests. | VERIFIED (task text) |
| Lane template note: a file test on a live branch answers "no executable test Var" (`seon.sci.eval/an-interpreted-file-test-has-no-executable-var-on-a-live-agent-branch`, cited in ad6d70476 history). | | VERIFIED (commit message) |

## 2. The code

### 2a. `my.program/write-branch!` (src/my/program.clj)

- Takes the context and `:seon.fn/root`; identities default to the branch's `added`, `changed-on-branch` and `retracted` from `head-comparison`. Holding the cluster's own branch answers "nothing to write" (refusal, nothing written). VERIFIED.
- Calls `seon.cluster.source/write-back!` with `:seon.source/accepted {identities, base-commit-id = cluster head, commit-id = branch commit}`. It writes no merge. VERIFIED.
- `write-back!` (src/seon/cluster/source.clj, around line 2000 and `write-back-commits` around 1894):
  - refuses `:row-changed` if a declaration moved on the branch after the accepted merge (`moved-digests`); VERIFIED.
  - proves each file (`proven-files`) against the before and after commits; refuses `:file-changed` if the disk bytes changed since analysis; VERIFIED.
  - reduces over files with `install!` (sibling write, then ATOMIC_MOVE rename). On a failed install it stops and answers `:install-failed` naming the files already installed. Files installed earlier **stay installed**: there is no rollback across files. VERIFIED (code and its comment at ~1934).
- Each file's base digest is checked against disk. A file another lane changed after the analysis is refused, not merged. VERIFIED. A file another lane changed before the analysis is read as the new base, so the write can replace that lane's uncommitted declaration if the same declaration is on the branch. UNVERIFIED (inferred from the base check; the claim check itself is absent, see the task above).

### 2b. `seon.cluster.branch-evidence/branch-evidence` (src/seon/cluster/branch_evidence.clj)

- `file-evidence`: analyzes each path's working-tree bytes (`fn/analyze-entities`, with the branch as previous database). Its declarations' definition digests are compared with the branch head (`:seon.source/differing`). Any mismatch is a refusal. VERIFIED.
- `behavior-changed?`: a docstring-only change demands no test (behavior digest); a changed function or test (branch versus cluster head) is a change. VERIFIED.
- `test-evidence`: reaching tests via `test/reaching`; `test/verified?` on the branch for each; `red-before` only when the branch result is red and red at the head's current reach; `unreached` when a changed function has no reaching test. VERIFIED.
- `platform-proven`: a host-bound change needs its reaching tests' latest results from a platform run (`git-sha` on the run). VERIFIED.
- `verdict`: `proven?` is true only when `differing`, `unanalyzable`, `host-bound`, `unproven`, `unreached` are all empty, and every red-before is accepted. VERIFIED.
- Returns data; errors come back as error values. Reads the live root over its prepl through `bin/lane`'s `branch_evidence`. VERIFIED.

### 2c. `bin/lane commit --branch-evidence` (bin/lane)

- `branch_evidence()` (around line 405) asks the live root (`operator/live-root-value!`) with the branch id from `:seon.agent/branch` on main, and the cluster head branch. Exit codes: 0 proven (writes trailers `Branch-evidence`, `Branch-evidence-tests`, `Branch-evidence-runs`, `Branch-evidence-red-before-accepted`); 2 refused; 3 unavailable, or host-bound, or unanalyzable. VERIFIED.
- In `commit()`: exit 2 refuses the commit; exit 3 **falls back to the throwaway check** (`bin/seon check` with `--test` names), which refuses if no test was named. So a 3 is not a silent pass. VERIFIED.
- Proven commits skip `bin/seon check`. Then the commit lands with `git commit --only` and the landed commit is checked for parent, paths and message (exit 3 "LANE COMMIT MISMATCH"; history not rewritten). VERIFIED.
- The shared index must be clean (`staged_only`); the candidate bytes must be unchanged since proof (`commit_locked`). VERIFIED.
- Evidence reads the file bytes on disk at commit time. A file edited after write-back and before commit is analyzed as the edited bytes, so the differing check catches a mismatch with the branch. VERIFIED by reading; not run.

### 2d. What happens to an agent branch on restart and reset

| Operation | Effect on an agent branch | Status |
|---|---|---|
| `bin/seon start` (restart, store kept) | Boot calls `registry/retire-unclaimed!` before acquiring branches (src/seon/cluster/boot.clj ~line 202). It keeps branches that are held, the main `:db`, cluster branches, and each **open** agent row's branch (`cluster-claims`: `:seon.agent/branch` where the agent has no `:seon.agent/admission-closed-tx`). Branches of closed agents or of no agent row are deleted. | VERIFIED (code) |
| `bin/seon start --head` | Same boot; the store and caches are kept; the replacement JVM resumes (script/seon/operator.clj ~1225). Branch heads are captured before the child boots and restored on a failed replacement (`head-restore-form`). | VERIFIED (code and comments) |
| `bin/seon reset --force` | `:reset` in boot.clj (~line 724) stops the cluster's JVM, calls `registry/retire-branch!` **on the cluster branch only**, then starts again. Agent branches are not deleted by this code; their survival then depends on the boot-time `retire-unclaimed!`. CLAUDE.md states "Reset deletes branches and forks from program entities": that is a claim about intent. The code says main only. | VERIFIED (code); the runtime effect on agent branches UNVERIFIED |
| `bin/lane release LANE` | Retires the lane's agent branch through `my.agent/retire-branch!` (task seon.cluster.agent.edn, M13). The evidence commit must be made before release. | VERIFIED (task text), code path not read |
| Store wipe (`bin/seon nuke`) | Deletes the store and all caches; every branch goes. | VERIFIED (script/seon/operator.clj, nuke-derived-paths) |

Also VERIFIED from the task text: about 110 agent branches were captured by `start --head` at one restart, because closed agents' branches are not unlinked (seon.cluster.agent.edn, 10-12 era). That is a cost, not loss.

## 3. Open tasks on this path (rg over resources/seon/tasks)

- seon.cluster.branch-evidence.edn: `the-branch-evidence-gate-rarely-sees-red-before-on-main`; `an-agent-check-costs-ten-times-its-one-test`; `the-branch-evidence-commit-is-dominated-by-whole-tree-lint`; `a-lane-branch-does-not-survive-a-default-restart`; `a-reaching-red-is-proven-red-before-by-its-result-at-the-base-key` (R9).
- my.program.edn: `write-branch-writes-files-the-lane-has-not-claimed`; `diff-answers-empty-after-a-branch-write-back`; `write-branch-in-the-defn-request-answers-a-merge-changed-nothing`; `write-branch-in-the-running-agent-harness-compares-with-a-home-branch-the-agent-did-not-fork-from`; `write-branch-of-one-function-allocates-a-gigabyte`; `write-branch-identities-take-tuples-and-the-docs-do-not-say`; `a-merged-change-reaching-the-files-is-unproven`; `an-in-ns-namespace-blocks-diff-and-write-branch` (pilot.md, not yet in tasks).
- seon.cluster.source.edn: second write-back `:conflict` (10-09); `require` refused as unresolved (10-10).
- seon.db.edn: `write-digest-error` on agent branches (10-14).
- seon.test.platform.edn: host-bound changes need `bin/seon check`.
- seon.cluster.agent.edn: branches of finished agents never unlinked.
- Not found in tasks: any task for comment loss in write-back (P17 has only the chain-experiment note); any task for multi-file partial install (`:install-failed`); any task reconciling the CLAUDE.md reset claim with the code.

## 4. Answer to "does data get lost?"

- Verified as safe in the code: the commit gate refuses when a declaration's file bytes do not match the branch digests, when a reaching test is not current green, and when the landed commit differs from the request. A failed gate refuses loudly (exit 2) or falls back to the throwaway check (exit 3), not to a silent pass. Trailers record the evidence.
- Verified loss vectors:
  1. Comments inside a redefined defn are dropped when the new source omits them (write-back replaces the span; P17 is the observation; no task yet).
  2. A multi-file write-back that fails partway leaves the earlier files installed (`:install-failed`; no rollback).
  3. Branches of closed or unlinked agents are deleted at boot and at lane release, so evidence is only available before release (intended, but it means a restart between the test run and the commit can remove evidence; the 10-08 observed case is UNVERIFIED at HEAD).
- Unverified: whether the 10-08 branch loss still occurs at HEAD; whether a reset leaves agent branches (code says yes, by boot's claim rule); the 2026-10-14 digest refusals (task text only); whether `write-branch!` can overwrite another lane's uncommitted declaration (inferred, not run).
- Reliability: 11 trailer commits in four days, against roughly 63 subject-level mentions of write-branch or branch-evidence. The gate has been used, and the failure list above is the measured record. Counting how many lane attempts failed was not possible from git alone.

## 5. Verification limits

- No JVM, no eval_clj, no test run. Nothing in the task was reproduced.
- Read: src/my/program.clj (write-branch!), src/seon/cluster/source.clj (write-back!, write-back-commits, install!), src/seon/cluster/branch_evidence.clj, src/seon/cluster/registry.clj (retire-unclaimed!, retire-branch!, reset-cluster!), src/seon/cluster/boot.clj (boot start and :reset), script/seon/operator.clj (start --head, reset, nuke), bin/lane (branch_evidence, commit), bin/seon (help and head selection), resources/seon/tasks/*.edn, docs/research/agent-platform/2026-10-08/dogfood/pilot.md, 2026-10-12/chain-experiment-9.md.
- git: `git log --all` counts and `-S` history.
