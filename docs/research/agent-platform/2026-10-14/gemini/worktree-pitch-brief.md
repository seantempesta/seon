Write a pitch, not code: should every Seon agent get a git worktree aligned with its Datahike branch and SCI context, so many agents work in parallel and conflicts are resolved at merge? The owner decides from your pitch before anything is built. Do not edit any tracked file; your only output is the pitch file.

## The dream (the owner's words, 2026-10-14)

"The goal is the DB is the source of truth and we write to the files once we've vetted it. We actually need reliable two way syncing. If we could dogfood the desired system where we experiment in a branch on SCI, after everything is working we merge to main and then we write to disk, that would be awesome. The goal is to get rid of the lanes, to have more work happening in parallel and to figure out things when we are merging them."

Today the truth is the files on disk: the database is wiped regularly and rebuilt from the files. The target is the database as the source of truth, with files as the vetted write-back. Until then, the two must sync reliably in both directions.

## Shared, incremental, minimal work (the owner's requirement, 2026-10-14)

"I want everything that can be shared to be shared if the computations are immutable. If another agent runs the same code over the same data, even in a different SCI context and branch, we use the cached result. Same with testing: if another agent has recently tested the function and it hasn't changed, we never rerun it. We never run full test suites unless explicitly requested. Everything is smart, incremental and minimal work."

Parallel places must never mean per-place computation. Analysis, compiled contracts, schema projections, evaluated results and test results are functions of immutable inputs (file content, definition digests, a database basis, a test's reach digest); keyed by those inputs they are shared by every agent, branch, SCI context and worktree. What exists: `seon.cache` (one content-keyed Caffeine cache per JVM), test results reused by reach digest, Datahike's structural sharing between branches. Known failure live today: two diagnosis lanes found `seon.program/used-resolver-context` keying `seon.cache` by a whole 3,749-entry map value (a full map equality per lookup), and `seon.cache` at its 512 MB budget evicting the schema projection while it holds ten copies of a 105 MB declaration projection. A design that keys by value or by place instead of by content digest defeats sharing.

## What exists (verify each against the code; cite by fully qualified symbol)

- One JVM hosts many realities (docs/seon/architecture/clusters-branches-contexts.md). Each agent holds a Datahike branch (`d/branch!`, cheap: a pointer to shared immutable indexes) and an SCI context forked onto it. Default's program derives from the files; other branches interpret their differing definitions in SCI. JVMs run committed archives (data/source/<sha>), never a working tree.
- Branch to files: `my.program/write-branch!` writes a branch's changed definitions into source files under a given `:seon.fn/root`, through `seon.cluster.source/write-back!`, proven equivalent before an atomic install.
- Files to branch: the edit hook (bin/seon-hook, .claude/seon-hook.edn `:repl-entrance`) lints an edited src/ or test/ file and evaluates its changed top-level definitions on the editing lane's branch in the live root; the lane comes from `SEON_LANE` or from bin/lane's claim on the file.
- Merge: root merges a branch's program entities onto main (`my.root/merge!`, `seon.merge/merge!`), gated on green tests, then writes back.
- Commits: bin/lane (read its usage header). Lanes claim files (one file, one owner), and `bin/lane commit` proves a candidate tree (HEAD plus exactly the claimed paths) by lint plus either a throwaway JVM check (`bin/seon check`) or `--branch-evidence AGENT` (the agent's branch declares exactly those files' definitions and every reaching test is green there).
- Outside agents (Claude Code, Codex, Argon) edit files with editor tools; Seon's own agents work at the REPL.

## Why worktrees failed before (September 2026; the history is in git log around 9f9ff4fe3, 293a21e17, 6e25c86c5)

- Each worktree ran its own JVM: seven JVMs at once, machine load 25, the test-slot cap bypassed.
- Stale bases: a worktree cut from an old HEAD, merged back, silently reverted newer commits.
- Lanes iterated against stale copies; half-finished foreign edits were loaded; a live run root was swept by mistake.
- Link setups (caches, generated config) failed in new checkouts.
State for each which is gone now, which a design must make impossible, and which remains.

## What the pitch must answer

1. **An agent's day, end to end**, in the dream: task assigned, place created, edits (REPL defn and file edits), tests, write-back, merge, files on main, place retired. Which face is authoritative at each step, and how the other follows.
2. **One place, three faces.** How a Datahike branch, its SCI context and a git worktree on a git branch stay one identity: cut from the same commit, moved together, retired together. What the identity is, and who mints it.
3. **Two-way sync without drift.** Files to branch and branch to files, from both kinds of agents. What makes divergence impossible by construction (no guard, check or retry added beside a mechanism to catch it doing the wrong thing; AGENTS.md "NO GUARDS OR FENCES").
4. **Merge.** One merge or two (Datahike's program merge and git's file merge): how they agree, what a conflict looks like, and how root resolves it holding both sides. What happens to bin/lane claims, and whether any work (boot, the writer, schemas, bin/, platform code SCI cannot interpret) still needs serializing or a throwaway JVM.
5. **Sharing across places.** For each derived thing (clj-kondo analysis, Seon's analysis and program entities, compiled contracts and projections, evaluated results, test results), what key makes it shared across agents, branches, SCI contexts and worktrees, whether it is shared today (MEASURED where you can: run the same read from two agent ids and compare cache hits), and what a worktree per agent would duplicate unless designed against it (e.g. one clj-kondo cache per worktree).
6. **Cost, measured.** Create and remove a worktree, its disk, clj-kondo's cache, the edit hook firing in it, `write-branch!` into it. Compare with today's lanes.
7. **What disappears** (code, rules, tools) and what is new; the smallest first slice that proves the idea on one real task; the red proof that slice must turn green.
8. **Risks and the simplest alternative**, including "keep the shared tree and make write-back and merge the only path to files", with the trade-offs stated plainly. If the honest answer is that worktrees are the wrong tool, say so.

## What you may do

- Read anything in the repository and reference-code/.
- Use the seon MCP tool `eval_clj`: mode jvm for read-only forms; mode sci only as agent id `pitch-probe-REPORTNAME` (its own branch, disposable). Never act as root on main, merge, reset, restart or reload.
- Create at most ONE git worktree for measurement, at /private/tmp/seon-pitch-REPORTNAME (git branch `pitch-REPORTNAME`), and remove it (`git worktree remove`) and its branch before you finish; report both commands' output. You may point `write-branch!` from `pitch-probe-REPORTNAME` at that worktree. Retire the `pitch-probe-REPORTNAME` branch when done (`my.agent/retire-branch!`).
- Label every claim MEASURED (with the exact form and numbers), VERIFIED (code read; quote it exactly) or PROPOSED.

Write the pitch to tmp/model-eval/worktree-pitch-REPORTNAME.md: one page of pitch (the decision asked, the recommendation, why), then the answers above, then the evidence. End with a ten-line summary.
