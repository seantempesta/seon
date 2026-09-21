---
type: reference
status: implementation running
created: 2026-09-21
tags: [agent-platform, operator, boot, implementation]
---

# B1b implementation assignment

Use one Astra agent at medium effort, alone on `default`, on the shared branch
`refactor/agent-platform`. No delegation, parallel implementation lane, worktree,
branch change or push. Launched as native agent `/root/b1b` on 2026-09-21 after the owner’s go-ahead.
The orchestrator owns integration and default's final replacement.

## Read first

Read `AGENTS.md`, the relevant REPL, Clojure, Datahike, flow and testing skills,
`docs/prds/agent-platform/plan/README.md` §4/§6/§7, and
`docs/prds/agent-platform/plan/lane-b1b-operator-and-boot-rewrite.md` end to end.
Follow the spec's source/dependency reading list. Review commits `2fac6e771`,
`fa51905b2`, `1cff03d6a` and `6b7363221` for the accepted design and corrections.
The current integrated spec supersedes historical brief wording.

## Deliverable and boundaries

Implement the B1b rewrite: argv/client, REPL-first boot, exact process down,
one-JVM destructive reset with a retained sibling store lock, caller/tool conversion
and removal of the superseded operator files and their obsolete tests.
Own exactly the implementation paths and required callers named in B1b §1.
Preserve `resources/seon/operator/runtime.clj` outside the indexed program.
Move surviving maintenance/filesystem behavior to its existing domain owners.

B1b is the first assignment. Use the current contracts, wrappers, publication and
acquisition owners; do not implement steps 1.1–1.3 as hidden prerequisites. Retain
currently required projection construction/bindings, coherence/accretion and search
calls in the new boot sequence until their owning cuts replace them. Keep publication,
registry and turn semantics; only add the ruled store destructive option and necessary
caller conversions. Keep B4's still-used export behavior until its consumers retire.
Convert the hook's transport imports now; its one prospective-lint request remains
B1 commit 12/step 1.5, not a second scope inside this assignment.

Temporary MCP, REPL and boot breakage between commits is explicitly allowed. Tool
changes are allowed. Do not retain a compatibility operator to keep intermediate
edits alive. Make path-limited commits; name intermediate breakage in the landing
note and restore loadability and development access before completion. The B1b
ruling overrides generic per-commit REPL/load requirements for this slice only.

No full suite per edit or commit. Implement the eight named destructive drills and
run them through the installed `seon.test/run` authority with real canonical fixtures,
contracts and scratch roots. The winner for drill 6 runs in the test JVM; the competing
start is a real child. Drill 8 must establish foreign-process exclusion, not merely
FileLock.isValid. Reap exact owned children and remove scratch roots in finally.
The owner deferred these drills during documentation; execute them as implementation
proofs, not as another pre-implementation research project.

The orchestrator runs the cut-level platform checkpoint and replaces `default` once.
Report when that step is ready; the lane does not reset default itself. Keep source
auto-publication paused until the coordinated restoration. Stop for a genuine new
guarantee decision or unconverted caller that cannot be owned; do not stop for the
temporary tool breakage the owner already accepted. No paid provider calls.

## Inherited state, observed before launch

Branch created from `6b7363221`; no source or test edits were pending. Existing dirty
documentation and untracked evidence were carried across unchanged: the Fable review
brief, design ideas ledger, `docs/prds/agent-platform/landing/`, and the status-rendering
issue. Do not incorporate, erase or commit these as part of B1b without reviewing them.
Native implementation agents are completed; `bin/codex-agent status` reports none running.

`bin/seon status` returned one live default, pid 25658, prepl 52660, web port 7994,
and no orphan JVMs. MCP JVM probe returned 2, a real connection, basis 536870978 and
process start `2026-09-21T20:47:17Z` in 4 ms. MCP runtime_status returned responding
agent/plumbing procs, plus four error signatures, three failed runs and one stale Var.
This is usable access, not a green baseline or proof of fresh adoption.

MCP SCI `(+ 1 1)` returned text `2`, outcome `ok`; the envelope reported 1,651 ms
evaluation duration, 1,676 ms total and 1,118,455,232 allocated bytes. These measurements
do not attribute the cost; acquisition optimization remains the later A1/B2 work.
No fresh suite, indexing or reset ran during preparation. Prior broad checkpoint:
186 executed, 51 failures, 13 errors, recorded in the retained fresh-start note;
do not repair every legacy failure before replacing its mechanism.

`.claude/seon-hook.edn` currently has `:current-source :enabled false`,
`:check-tests false` and `:schema-admission :enabled false`. No configuration change
was needed for prep. About 1.1 GiB remains in `tmp/test-runs`; retain that failed-gate
evidence until its named failures are re-observed or no longer needed. No live test JVM
was observed. Do not sweep evidence or perform a fresh index just to start the rewrite.

## Completion

Return the B1b §8 evidence in `docs/prds/agent-platform/landing/lane-b1b.md`:
commits, exact changed paths, actual `wc -l` for each new file and explained overrun
against the ≈900 target, surviving behavior moves, all eight recorded drill outcomes,
tool restoration evidence and the remaining orchestrator checkpoint. The right design
wins over the count. An unavailable observation or unexecuted drill is explicitly pending.
