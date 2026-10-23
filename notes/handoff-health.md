# Handoff: jolt/health (2026-10-23, the session's end)

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

## Not verified
- A live `my.docs/reviewed!` commit made by a real agent. The real-git proof covers the mechanism in a temp repo.
- The last root review items, which a fresh session should check on main:
  1. **Contracts:** seon.prove/* had no contracts (bin/check contracts red).
  2. **System:** the system check's expected set lacked `:prove`.
  3. **MCP:** the mcp check's literal "five tools", now that `seon_prove` makes six.
  4. **Ticks:** fix 4's cost proof should state its estimates in ticks, not ns.
  5. **Reviews:** a review must count only from HEAD's history (`git log HEAD --grep`), not `--all`.

  These were briefed to a subagent when the session ended. Check whether main already has them (bin/check contracts, bin/check system, bin/check mcp; grep `--all` in seon.fx's wiki-history). Any that's missing is the next task.

## Next steps, in order
1. On main, run `bin/check contracts`, `bin/check system` and `bin/check mcp`, and grep seon.fx for `--all`. Fix what's red (items 1–5 above).
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
