# Handoff: jolt/health (2026-10-23, the session's end)

**First, read this.** The merge session merged jolt/health (9d9f55dc), saw prove go red, and REVERTED it on main (1ef9f0e9). The failure: 8 seon.prove-test tests, "seon.prove/reply invalid-input … :expected [:sequential :int] :value #{1}". Our 972b4775 had changed reply's schema to [:sequential :int], while main's callers pass a set. This branch already carries the fix: 6e08af63 makes reply take a set or a sequence. It is untested.
To land again:
1. Merge main (1ef9f0e9 or later) into jolt/health. That merge brings the REVERT, which undoes our changes on this branch too.
2. Then `git revert 1ef9f0e9` on this branch (reverting the revert) to restore them, resolving conflicts in favour of our changes plus whatever main added since.
3. Keep main's Datahike gitlink, bae7c3e.
4. Rebuild with synced pins, then run bin/prove (it must be OK) plus seon.prove-test, seon.agent-test, seon.root-test and bin/check health. Then report to the merge session.

**State.** Nearly all of jolt/health is on main: merge aac06296 "Merge jolt/health: bin/prove runs health; problem facts, wiki baseline", plus the earlier land16 and later merges. The branch tip differs from main by one line, `seon.prove/reply`, which takes the waiters as a set or a sequence (6e08af63). That line is proved only by the tests of the session that made it, not by a full `bin/prove` on this tip. Nothing of health's is running.

## What is on main and verified (bin/prove OK at each merge, the named process tests green)
- **Health is a query** (`seon.problems/health` = recorded `report` plus derived classes). It is read when someone looks, never stored or polled (docs/health.md). The derived classes:
  - `:dead-citation`, including dead links: a link to no section no longer refuses the wiki;
  - `:unnamed-site`, catch only, Seon's src, with exact parity with the old bin/laws scan;
  - `:stale-pin`;
  - `:stale-section`, attention only, with the git baseline and `Reviewed:` commit trailers;
  - `:orphan-page`;
  - `:problem-unlinked`, class-level only;
  - recorded: `:test-red`, and errors closed when their subject changed (`:seon.error/subject`).
- **`caused`** is O(changed): base and head diffed by value, 7.7–8.5 ms on Seon's program. The done rule's `:open-problem` reason refuses `seon_done` while a branch caused a problem. Stale sections go to the turn's context as attention.
- **The watcher:** native/watch.c (FSEvents, no poll), seon.watch, my.root/watch!. A dead watcher or a loader's typed error is a `:platform` problem.
- **The test service:** the `:prove` proc (one run per checkout, a running key joined, the latest wins), `seon_prove`, results as `:seon.proof/` facts by digest, process tests only when named, and `:test-red`.
- **Checks:** `bin/check health` is the one check of the derived classes. `bin/prove` runs it when docs/ or src/ changed. `bin/laws catch` calls it.

## Not verified (the subagent's final report, the session's last minutes)
- **Root's last five review items are done** and were green when run, as far as main merged them:
  - contracts on every seon.prove fn;
  - the system check's expected set read from system.edn;
  - mcp-proof comparing the tools with seon.server/ops;
  - fix 4's cost proof priced in ticks;
  - reviews counted from HEAD's history only.
- **The last bin/prove had 3 reds, none re-run on 6a326234:**
  1. `seon.prove-test`: 9 tests, because `reply`'s contract rejected a set. Fixed in 6e08af63 (a set or a sequence), untested.
  2. `seon.program-cost-test/a-probed-entity-is-sought-not-scanned` was over its estimate. It's probably a stale Datahike pin: the checkout has since moved to main's bae7c3ec.
  3. The new `health` step said HEALTH FAILED; its cause was not read.
- **To re-verify:** sync the pins, rebuild, then run `bin/prove`, `bin/test seon.prove-test seon.program-cost-test` and `bin/check health`.
- **Pin sync:** `bin/worktree --sync health` no longer finds this worktree, because worktrees now live at `next/.worktrees/NAME`. Sync by `git -C` on the submodules, or make a fresh worktree.
- **Not exercised:** a live `my.docs/reviewed!` commit by a real agent. The real-git proof covers the mechanism.
- **AGENTS.md §6** should list `seon_prove` among the MCP tools; root's to edit.
- **Possible duplicates:** main merged an earlier jolt/health tip, so check the late changes for duplicates when merging.

## Next steps, in order
1. Re-verify the tip as above: prove-test, program-cost-test and the health step. Read the cause of HEALTH FAILED (`bin/check health` prints each open row).
2. **Incremental health** (root approved, low priority): health(db') from health(db) by caused's candidates. The render proc keeps its previous value and db, and the full read happens once per process. A cost proof is flat at a fixed change. No cache.
   - Work-in-progress copies are in notes/handoff-health-wip/ (src/seon/problems.clj, src/seon/render/proc.clj, test/seon/problems_test.clj, saved with a .txt suffix).
   - They are whole files from an OLDER base: re-apply the idea by hand, never copy them over.
3. **The commit stream and procs** (approved design, notes/health-design-2026-10-23.md, "One trigger path"):
   - the writer publishes every commit as a Flow out-port (sliding notices; a reader catches up from history);
   - the reconciler and :prove read it;
   - the watcher becomes :watch → :load procs, with :load as the one loader, replacing the boot loads and my.root's;
   - cost proofs for bursts.
4. **Keep the `tests` branch in step** through pure-tests' `seon.source/refresh-tx` (jolt/refresh: per-form diff and the interface rule), called by :load.
5. **Every write is checked** (approved, notes/health-design-2026-10-23.md, "Every write is checked" and its rulings): `:uncontracted` and `:contract-fails` with mg/check results keyed by var digest, and the var render.
6. **:problem-unlinked's per-row case:** a counted row that no task answers.

## Where things are
- The design and every ruling: notes/health-design-2026-10-23.md.
- The shared notes for this session group (outside the repo, may not survive): health-*.md.
- The process (AGENTS §3, e389072a): report ready to the merge session, with "self-review: N findings, all fixed" from a Sonnet §9 review of `git diff main...HEAD`.
