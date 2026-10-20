---
title: "The v2 move: Seon on Jolt replaces Seon v1 on a branch of ~/src/seon"
type: research
status: draft
created: 2026-10-20
---

# The v2 move plan (lane move-plan, 2026-10-20)

**Status:** draft. Sections 1–6 are complete. Nothing here has been executed. Every count was measured on
`main` = `refactor/agent-platform` = `79b1a9272` and on `~/src/seon-jolt/next` HEAD `09e81cd`. The move itself waits
for the owner's word.

**Headline.**
- **Kept:** git history (every branch, tag and `archive/*` ref, plus a new `seon-v1` tag), `LICENSE`, `SOUL.md` and
  `docs/research/agent-platform/2026-10-20/`.
- **Removed from the tree:** everything else, about 1.69M tracked lines. It all stays reachable at `seon-v1`.
- **How next arrives:** `next/`'s 15 commits come in at the repo root through one `git merge
  --allow-unrelated-histories`, so its own history and blame are kept. `~/src/seon-jolt`'s 167-commit history is
  fetched in as a tag only.
- **Submodules:** the 34 v1 submodules become next's 9 fork submodules.
- **The live store:** `default` and its 14 GB `data/` are untouched until the owner stops `default` at cutover.
- **`main`:** untouched until the owner says.

Authorities:
- `~/src/seon-jolt/notes/DECISIONS.md`: "a wipe on a branch, not a new repo"; "the DB can be wiped and nothing is
  migrated".
- `notes/NEW-REPO-PLAN.md`.
- `next/docs/forks.md` and `next/docs/SCAFFOLD.md`.
- `census/` in this directory.

## 1. What stays on `v2`

| What | Decision | Why |
|---|---|---|
| Git history | All of it. The `v2` branch is cut from `main`. No ref is deleted or rewritten: the 9 local and ~45 remote branches, the 10 `origin/archive/*` refs, the 3 tags and the 8 extra worktrees (`git worktree list`) stay as they are. A new annotated tag `seon-v1` marks the last v1 commit. | IP. `git show seon-v1:<path>` recovers any v1 file. |
| `~/src/seon-jolt` history (167 commits, 22 MB `.git`) | Fetched as the tag `archive/seon-jolt-2026-10-20`. It is not merged into the tree. | Its experiments are IP evidence and cost no tree space. Its nested repos (`NESTED-REPOS.md`) are not tracked by it, so the forks travel as submodules (§3). |
| `docs/research/agent-platform/2026-10-20/` | Kept at this path: the census, this plan and the reviews. | `next/../notes/IDEAS-MAP.md` cites the census by this path, and §4's spec manifest is derived from it. |
| The rest of `docs/` (4,535 files, 1,013,666 lines: `research` 567,698, `prds` 386,837, `seon` 58,634 including 626 issues and 224 schedule cards, `wiki`, `.obsidian`) | Removed from the tree, kept at `seon-v1`. | The owner: "I don't want to bring all the baggage over." The specs, issues and cards describe the SCI/JVM machinery. Open decision D3. |
| `~/src/seon-jolt/notes/` cited by next (`DECISIONS.md`, `option3-F1-flow.md`, plus `HANDOFF.md`, `IDEAS-MAP.md`, `NEEDS.md`, `method.md`) | Copied into `docs/notes/`. The 37 `../notes/`, `../proto/` and `~/src/seon-jolt` references in 8 next files are repointed in one commit. | next cites `../notes/DECISIONS.md` as its authority, which would not resolve after the move. The rest of `notes/` (13.8k lines in all) stays at the archive tag. |
| `AGENTS.md` (82 KB, the v1 laws; `CLAUDE.md` is a symlink to it) | **Rewritten.** next's `CLAUDE.md` (a draft stub) becomes `AGENTS.md`, with `CLAUDE.md` symlinked to it, following Seon's convention. The v1 text stays at `seon-v1:AGENTS.md`. It is the source for filling the stub (§5 step 9): the architecture-neutral laws carry over (goal, data first, contracts, errors, one printer, proofs, cost), and the SCI, branch-runtime, JVM, `bin/lane` and `bin/seon` laws do not. | v1's laws name machinery that no longer exists. Kept verbatim, they would mislead every agent. |
| `.agents/skills/` (13 skills) | Removed, except `agy-lanes` and `codex-lanes`. Those two stay with `bin/agy-agent` and `bin/codex-agent`, and both scripts' `lane_rule` is rewritten for next (`bin/test`, `bin/check`, explicit-path commits, no `bin/lane`). | The other 11 cite `seon.*` JVM, SCI and `bin/seon` code, from 2 (`agy-lanes`) to 101 (`data-oriented-clojure`) matches each. The law is "a stale skill is a high-priority defect". `data-oriented-clojure` and `clojure-testing` are rewritten against next later (D5). |
| `.claude/`, `.codex/`, `.mcp.json` | Replaced. The v1 `.claude/settings.json` runs `bin/seon-hook` on every tool call, and the v1 `.mcp.json` runs `bin/mcp-server`. Both go. v2 ships an empty hook list and an `.mcp.json` pointing at next's stdio MCP server (`seon.mcp`, commit `a721881`). | A dangling hook would fail every agent edit on `v2`. |
| `LICENSE` (AGPL-3.0), `SOUL.md` | Kept. | `SOUL.md` is the product's identity. Owner, D6. |
| `README.md`, `.gitignore` | Replaced by next's. `.gitignore` also gains `data/ tmp/ logs/ .env .DS_Store .nrepl-port`. | `data/` must stay ignored, or the 14 GB live store shows as untracked after cutover. |

### `reference-code/`: 34 v1 submodules → keep or drop

What next uses (`next/deps.edn`, `.gitmodules`, a grep of `src test resources web bin docs`):
- Customized forks: jolt, chez, datahike, persistent-sorted-set, konserve, jolt-fressian, jolt-overlay, crypto, malli.
- Pinned: superv.async, ring-chez-adapter, multipart, time, http-client, data.json, aero, fipp, core.cache.
- Jolt ships its own core.async and nREPL (`jolt.nrepl`).

next's rule is that `reference-code/` holds only the libraries we customize.

| v1 submodule | v2 | Reason |
|---|---|---|
| `datahike` (seantempesta, `seon/main`, `ccba220b`) | **Keep the path; re-pin** to the `jolt/main` fork commit next pins (`fe50764` today, on `jolt/run-ahead`) | The same GitHub fork; next's base is `ccba220b` |
| `konserve` (seantempesta, `seon/main`) | **Keep the path; re-pin** to `jolt/main` (`849d87d` today) | The same fork; Jolt stores and the fasl codec |
| `malli` (seantempesta, `seon`, `dd13ff81`) | **Keep the path; re-pin** to `jolt/main` `5a830e14` | The same fork; `dd13ff81` is its base |
| `superv.async` | Drop the submodule | next pins `501b4294` as a `:git/sha` dependency. The fork's only change was a debug print (`forks.md`) |
| `fipp`, `aero` | Drop | Used, unmodified and pinned `:mvn/version`. The source is in the deps cache |
| `core.async`, `nrepl` | Drop | Jolt ships its own (`jolt.async`, `jolt.nrepl`) |
| `rewrite-clj` | Drop | Idea #15 (write-back) is "missing". Re-add when that lane starts (measured byte-identical on Jolt, IDEAS-MAP) |
| `sci`, `edamame`, `clojure`, `clojurescript`, `babashka`, `babashka-process`, `clj-reload`, `kaocha`, `orchard` | Drop | No SCI and no JVM REPL. Jolt reads and evaluates natively; the forgiving reader is idea #8's rewrite |
| `clj-kondo` | Drop | Replaced by Jolt's analyzer IR (K1, DECISIONS) |
| `http-kit`, `datastar-clojure`, `hyperlith` | Drop | The web runs on ring-chez-adapter plus glimmer-datastar (`proto/web`) |
| `core.async.flow-monitor` | Drop | Flow on Jolt; the dashboard is our own |
| `editscript`, `langchain4clj`, `CLM`, `clerk`, `portal`, `reveal`, `morse`, `clay`, `kindly`, `puget`, `hiccup` | Drop | Nothing in next references them |
| *(new)* `jolt`, `chez`, `persistent-sorted-set`, `jolt-fressian`, `jolt-overlay`, `crypto` | **Add**, from next's `.gitmodules` | Customized; `forks.md` gives their provenance |

The result is 9 submodules: next's exactly.

## 2. What is removed from the tree (all kept at `seon-v1`)

Tracked lines measured with `git ls-files -z DIR | xargs -0 cat | wc -l` on `79b1a9272`.

| Path | Files | Lines | Notes |
|---|---|---|---|
| `src/` | 125 | 107,546 | 156 census namespaces counting `script/`. Verdicts in §4 |
| `test/` | 485 | 123,629 | 3,471 deftests (`census/tests.edn`) |
| `script/` | 37 | 9,057 | operator, sentinel, census and bench scripts |
| `bin/` | 13 | 4,113 | `seon`, `seon-hook` (1,843), `lane` (861), `lint`, `test`, `test-check`, `mcp-server`, `acme`, `css`, `_operator-program`, `_java-home-resolver`. `agy-agent` and `codex-agent` stay, rewritten (§1) |
| `resources/` | 351 | 26,190 | 236 schema files, 106 task files, `help.md`, `public/` |
| `config/` | 3 | 748 | `default.edn`, `development.edn`, `test.edn` |
| `data/` (tracked) | 3 | 945 | `data/tune/*.jsonl` only. The ignored 14 GB live data is covered below |
| `evals/` | 753 | 322,740 | 745 eval run logs plus datasets |
| `src-clm/`, `src-inspect-ai/`, `src-needle/` | 121 | 44,506 | Experimental side projects |
| `tmp/` (tracked despite `.gitignore`) | 378 | 44,036 | Old probes |
| `bench/` | 8 | 802 | |
| `docs/` except `research/agent-platform/2026-10-20/` | ~4,500 | ~1.0M | §1 |
| `.agents/skills/` except two | 11 skills | ~3,500 | §1 |
| `.clj-kondo/`, `.lsp/`, `.codex/`, `.claude/`, `.mcp.json`, `.splint.edn` | 42 | ~1,150 | Replaced (§1) |
| Root files: `deps.edn`, `bb.edn`, `tests.edn`, `package.json`, `bun.lock`, `Caddyfile`, `dev_cache.clj`, `e1_inspect_samples.jsonl`, `vt.txt`, `.env.example`, `CONTRIBUTING.md`, `seon-skills` (symlink), `AGENTS.md`/`CLAUDE.md` (rewritten) | 13 | — | next supplies `deps.edn`, `README.md` and the laws |
| `reference-code/` | 34 gitlinks | — | §1 |

**The live store and `default`.** This is the one thing that must not be lost by accident.
- `data/` is ignored and untracked, 14 GB in all:
  - `store` 1.3 G, `clusters` 106 M, `source` 1.1 G (the committed archives `default` runs from);
  - `heap` 11 G (heap dumps), `check` 452 M, `embeddings` 54 M, `tune` 3.9 M.
- `default` (pid 75094, health `:faulted`) runs from `data/source/8066f12…`, not from the working tree. The embed
  server (pid 68540, port 8701) runs beside it.
- So a `v2` worktree, which is a separate directory, cannot touch either of them.
- The owner's ruling is that nothing is migrated. At cutover (§5 step 10):
  1. stop `default` and the embed server from a v1 checkout, since `bin/seon` exists only there;
  2. optionally tar `data/store` and `data/clusters` (about 1.4 GB) to an archive outside the repo (D2);
  3. delete `data/heap` and `data/check`.
- Until the owner orders it, `data/` is left in place, still ignored, under `~/src/seon`.

## 3. How `next/` comes in

### Comparison

| Method | next's history | Layout at the root | Cost and risk |
|---|---|---|---|
| **(a) Merge unrelated histories at the root** (recommended) | Kept: all 15 commits, with `git log` and blame working on every file | Yes. next is already root-shaped (`deps.edn`, `bin/`, `src/`, `test/`, `docs/`, `reference-code/`), so no prefix is needed | One command. The only conflict is `.gitignore` (add/add). History adds about 1 MB of blobs (`.git/modules` stays in next) |
| (b) `git filter-repo`, then merge | Kept, but rewritten | Only needed to move next under a prefix or to strip paths | Rewrites 15 commits and needs `filter-repo` installed. Nothing needs rewriting, so it buys nothing |
| (c) Plain copy plus a pointer commit ("from seon-jolt/next 09e81cd") | Lost in this repo: it survives only in `~/src/seon-jolt` | Yes | Simplest, but it breaks blame and the IP trail the owner asked for |
| `git subtree add --prefix` | Kept | No: it forces a subdirectory | Wrong layout |

**Recommendation: (a).** The `v2` tree ends as next's tree, plus `LICENSE`, `SOUL.md`,
`docs/research/agent-platform/2026-10-20/` and `docs/notes/`.

- **Namespace names:** unchanged. next already uses the final `seon.*` and `my.*` names, and the wipe empties both, so
  nothing collides.
- **Test namespaces:** `test/r3/*`, `test/r5/*` and `next.smoke-test` still carry lane names. Renaming them is a
  follow-up commit, not part of the move, so the move commit stays mechanical.

### Forks become submodules

next's `.gitmodules` points at local paths under `~/src/seon-jolt`. A clone therefore needs `-c
protocol.file.allow=always` and works on this machine only. To make `v2` cloneable, each fork's `jolt/main` must
reach a remote. That is an outward push, which needs the owner's OK (D1).

| Submodule | Local repo today | GitHub remote known? | Target URL |
|---|---|---|---|
| jolt | `~/src/seon-jolt/jolt` | Yes: remote `sean` → `seantempesta/jolt` | `git@github.com:seantempesta/jolt.git`, branch `jolt/main` |
| chez | `e14/chez-share` | Upstream `cisco/ChezScheme` only | A new fork, `seantempesta/ChezScheme` (D1) |
| datahike | `libs/datahike` | Seon's existing `seantempesta/datahike` | That fork, branch `jolt/main` (next pins `jolt/run-ahead` `fe50764` today: merge it first) |
| persistent-sorted-set | `e4/pss-a` | None | A new fork of `replikativ/persistent-sorted-set` (D1) |
| konserve | `libs/konserve` | Seon's existing `seantempesta/konserve` | That fork, branch `jolt/main` |
| jolt-fressian | `e9/jolt-fressian` | Upstream `jolt-lang/jolt-fressian` only | A new fork, `seantempesta/jolt-fressian` (D1) |
| jolt-overlay | `libs/jolt-overlay` | None; it has no upstream | A new repo, `seantempesta/jolt-overlay`, or fold it away as `forks.md` recommends (D1) |
| crypto | `libs/crypto` | `origin` is a gitlibs cache path | A new fork, `seantempesta/crypto` (D1) |
| malli | `l1/wt/malli` | `seantempesta/malli` | That fork, branch `jolt/main` |

Fork branches are named `jolt/*`. They never collide with v1's `seon*` branches on the same forks (DECISIONS,
"Libraries on Jolt"), so v1's pins at `seon-v1` keep resolving.

Until D1 is decided, `v2` keeps the local URLs and works on this machine only. LMDB is built from source inside the
repo by `bin/build`, as `next/bin/build` will do before the move. Nothing is installed system-wide (owner's rule:
no system-wide installs). So `v2` never depends on `~/src/seon-jolt/vendor-c/lmdb` or Homebrew.

## 4. Census verdicts → the deletion list, and the spec tests that carry over

**Deletion.** The move removes every v1 namespace, so the verdicts decide what gets **rebuilt**, not what gets
deleted. From `census/namespaces.edn`, 156 namespaces:

| Verdict | Namespaces | Lines | What happens on `v2` |
|---|---|---|---|
| `:port` | 49 | 56,187 | The idea is re-implemented in next, in its simplest form, against the idea's tests (`ideas.md`). Code is copied only by need, and the copy cites `seon-v1:<file>` and its symbol |
| `:replaced` | 78 | 38,526 | Nothing is carried. Each namespace's `:census/reason` becomes one `BAGGAGE.md` line, grouped by cause (SCI, shared JVM, content caches, JVM tooling) |
| `:unclear` | 29 | 9,652 | Not carried until the owner rules on OD1–OD8 (`static.md`). The 29 are `my.agent`, `my.fs`, `my.message`, `my.shell`, `seon.agent`, `seon.blob`, `seon.cluster.instruction`, `seon.cluster.message`, `seon.cost.growth`, `seon.embed`, `seon.fault`, `seon.fs`, `seon.fs.jvm`, `seon.id`, `seon.order`, `seon.render`, `seon.render.{data,hiccup,ns,schema,sent,test,var}`, `seon.run`, `seon.schedule`, `seon.schema.admission`, `seon.shell`, `seon.shell.jvm` and `seon.vocabulary` |

- **The wipe commit's body** carries these totals and points to `census/namespaces.edn`. That file stays in the tree
  and is the per-namespace record of why each one went.
- **Directories outside the census** (`evals/`, `src-clm/`, `src-inspect-ai/`, `src-needle/`, `tmp/`, `bench/`) were
  never classified. They go, and D4 asks the owner to confirm that.

**The spec tests.** `census/tests-run.edn` lists the 228 deftests the 20 ideas cite:
- 210 meaningful;
- 12 red (spec-reds: 7 now green, 4 kept as red targets, 1 retired);
- 2 timed out;
- 4 flaky.

They are Seon JVM tests on `seon.test-support`, SCI and `bin/seon check`, so none of them runs on Jolt as written.

1. **The manifest.** One script, `script/spec_manifest.clj` (bb), derives `test/seon/spec/manifest.edn` from
   `tests-run.edn` and `spec-reds.md`. Each row holds the v1 symbol, its idea, the census class and a status:
   - `:ported` with the next test symbol;
   - `:pending`;
   - `:retired` with the reason.

   It starts with 210 meaningful + 4 red targets + 2 timeouts = 216 `:pending`, and 1 `:retired` (the SCI-only
   `test-vars` override). The 4 flaky rows are carried as `:pending` only after a timing bound is rewritten as an
   allocation or count bound.
2. **The port.** Each idea's tests are rewritten as next deftests in `test/seon/spec/idea_NN_test.clj`. Each asserts
   the idea on data, and its docstring cites the v1 var as `seon-v1:test/…/x_test.clj` plus the var name.
   - Order: IDEAS-MAP's "missing" and "partial" ideas first (#3, #8, #15, #16, #18, then #1, #2, #5, #7, #11, #14,
     #17, #19).
   - The 4 red targets carry over as red tests:
     1. `a]a` must stay two tokens;
     2. forgiving forgiven text changes nothing;
     3. a warm context build allocates ≤ 200 bytes per character shown;
     4. the strict cost table.
   - The printer's elided quoted map form (spec-reds, Findings) is added as a row.
   - The timed-out chain test (`seon.cluster.db-flow-test/a-filed-tasks-design-prove-implement-chain-merges-with-no-hand-act`)
     becomes a `bin/check` scenario.
3. **Keeping them runnable.**
   - The ported tests run in next's `bin/test` on every commit.
   - The manifest test asserts that every `:ported` symbol resolves and runs, and it reports `:pending` per idea.
   - The v1 originals stay runnable as the reference oracle: `git worktree add ~/src/seon-v1 seon-v1`, then from it
     `bin/seon check --at seon-v1 --test NS/VAR`. That boots a throwaway JVM from the tag and needs no `default`.
     This works until the JVM toolchain is retired (D7).

## 5. Command sequence (a listing, not run)

```zsh
#!/bin/zsh
# v2 move. Run only on the owner's word. Every step names its check; stop on any failure.
set -eu
SEON=$HOME/src/seon  NEXT=$HOME/src/seon-jolt/next  JOLT=$HOME/src/seon-jolt  V2=$HOME/src/seon-v2

# 0. Preconditions. next is committed, root has paused its lanes, and no Seon lane holds a claim.
#    (next is dirty today: 20+ modified files.)
[[ -z $(git -C $NEXT status --porcelain --untracked-files=no) ]]
[[ $(cd $SEON && bin/lane status) == "No live claims" ]]
(cd $NEXT && bin/build && bin/build seon && bin/test && bin/check 100) | tee $V2.next-baseline.log  # the baseline
V1=$(git -C $SEON rev-parse main); NEXT_SHA=$(git -C $NEXT rev-parse HEAD)

# 1. Tag v1 and cut v2 in its own worktree. The shared checkout and default are untouched.
git -C $SEON tag -a seon-v1 $V1 -m "Seon v1 (JVM + SCI): the last commit before the Jolt rebuild"
git -C $SEON worktree add $V2 -b v2 $V1
#    check: git -C $SEON rev-parse main == $V1; git -C $V2 log -1 --format=%H == $V1

# 2. Archive seon-jolt's history as a tag only (no tree change).
cd $V2
git fetch $JOLT main && git tag -a archive/seon-jolt-2026-10-20 FETCH_HEAD -m "seon-jolt experiments and notes"
#    check: git rev-list --count archive/seon-jolt-2026-10-20 == 167 (or more)

# 3. The wipe. Everything but the keep list goes; data/ stays ignored.
KEEP='^(LICENSE|SOUL\.md|docs/research/agent-platform/2026-10-20/|\.agents/skills/(agy|codex)-lanes/|bin/(agy|codex)-agent)'
git ls-files | grep -Ev "$KEEP" | tr '\n' '\0' | xargs -0 git rm -q --   # also removes .gitmodules and the 34 gitlinks
printf 'data/\ntmp/\nlogs/\n.env\n.DS_Store\n.nrepl-port\n' > .gitignore && git add .gitignore
git commit -m "v2: Seon v1 leaves the tree (history at seon-v1)" -m "<verdict totals and line counts from §2 and §4>"
#    check: [[ -z $(git ls-files | grep -Ev "$KEEP|^\.gitignore$") ]]; git ls-files | wc -l (about 40)

# 4. next's history at the root.
git fetch $NEXT $NEXT_SHA
git merge --allow-unrelated-histories --no-ff --no-commit FETCH_HEAD || true
git show FETCH_HEAD:.gitignore > .gitignore.next
cat .gitignore.next >> .gitignore && rm .gitignore.next && sort -u -o .gitignore .gitignore && git add .gitignore
git commit -m "v2: Seon on Jolt (seon-jolt/next $NEXT_SHA) at the repo root, its history kept"
#    check: git diff --stat FETCH_HEAD HEAD shows only the kept paths and .gitignore; git log --oneline -- src/seon/root.clj
#           lists next's commits

# 5. Submodules at next's exact pins.
git -c protocol.file.allow=always submodule update --init --recursive
#    check: git submodule status == git -C $NEXT submodule status (9 lines, same SHAs)

# 6. Laws file and out-of-repo references.
git mv CLAUDE.md AGENTS.md && ln -s AGENTS.md CLAUDE.md && git add CLAUDE.md
mkdir -p docs/notes && cp $JOLT/notes/{DECISIONS,HANDOFF,IDEAS-MAP,NEEDS,method,option3-F1-flow}.md docs/notes/
cat $JOLT/proto/BAGGAGE.md >> BAGGAGE.md   # then edit by hand into BAGGAGE.md's sections
#    Hand-edit the 37 refs (git grep -n -E '\.\./notes|\.\./proto|seon-jolt'), the .claude/ and .mcp.json from §1,
#    and the lane_rule of bin/agy-agent and bin/codex-agent.
git add -A docs/notes BAGGAGE.md AGENTS.md README.md CLAUDE.md docs src/README.md web/README.md .claude .mcp.json bin
git commit -m "v2: laws as AGENTS.md, notes in docs/notes, references inside the repo"
#    check: [[ -z $(git grep -n -E '\.\./notes|\.\./proto' -- ':!reference-code') ]]

# 7. Prove it (step 0's baseline is the expectation).
bin/build && target/hello && bin/build seon && bin/test && bin/check 100 | tee $V2.v2.log
#    check: target/hello prints "hello: (datahike hello)"; bin/test 0 failures; every bin/check line matches the baseline
#    log; a fresh clone also builds:
#    git -c protocol.file.allow=always clone --recurse-submodules -b v2 $SEON /tmp/v2-fresh && (cd /tmp/v2-fresh && bin/build && bin/test)

# 8. The spec manifest (§4) as its own commit, then ported tests idea by idea in later commits.
bb script/spec_manifest.clj docs/research/agent-platform/2026-10-20/census > test/seon/spec/manifest.edn
git add script/spec_manifest.clj test/seon/spec && git commit -m "v2: the census's idea proofs as a spec manifest"
#    check: bin/test runs seon.spec.manifest-test; it reports 216 pending, 0 ported, 1 retired

# 9. Fill AGENTS.md from seon-v1:AGENTS.md (the neutral laws) and F2's proc section. A lane task, not a script step.

# 10. Cutover: only on the owner's word.
#   (cd $SEON && bin/seon stop)              # default and the embed server; check pids 75094 and 68540 are gone
#   tar -C $SEON/data -cf ~/archive/seon-v1-store.tar store clusters   # if D2 says keep
#   git -C $SEON switch main && git -C $SEON merge --ff-only v2       # D8: or keep main as v1 and make v2 the default
#   git -C $SEON worktree remove $V2
#   check: git -C $SEON status clean; data/ still ignored; bin/build && bin/check 100 in $SEON
#   No push without the owner (D1).
```

**Rollback**, at any step before 10:

```zsh
git -C $SEON worktree remove --force $V2
git -C $SEON branch -D v2
git -C $SEON tag -d seon-v1 archive/seon-jolt-2026-10-20
```

`main`, every other branch, `default` and `data/` were never touched. After step 10, `git -C $SEON reset --keep
seon-v1` on `main` restores v1, and `bin/seon start` brings `default` back on its retained store, provided D2 kept
it.

## 6. Open decisions for the owner

- **D1. Remotes.** Push the 9 forks' `jolt/main`, and create new forks for chez, persistent-sorted-set, jolt-fressian,
  crypto and jolt-overlay? Until then `v2` clones on this machine only.
- **D2. The v1 store.** Archive `data/store` and `data/clusters` (about 1.4 GB) before deletion, or delete them
  outright ("nothing is migrated")? Delete `data/heap` (11 GB) and `data/check` regardless?
- **D3. Docs in the tree.** Keep only the 2026-10-20 research and `docs/notes/` (recommended), or also keep
  `docs/seon/issues` and the PRDs as browsable archive?
- **D4. Unclassified directories.** Confirm that `evals/` (322k lines of run logs), `src-clm`, `src-inspect-ai` and
  `src-needle` go. CLM is "no Seon dependency" research; it could become its own repo.
- **D5. Skills.** Rewrite `data-oriented-clojure` and `clojure-testing` for next, or start next's skills from zero?
- **D6. `SOUL.md`.** Keep it at the root?
- **D7. The v1 oracle's lifetime.** How long must `seon-v1` stay runnable (a JVM plus `bin/seon check --at`) as the
  oracle for the spec tests?
- **D8. `main` at cutover.** Fast-forward `main` to `v2` (recommended: `v2` descends from `main`), or freeze `main` as
  v1 and make `v2` the default branch?
- **D9. Path A.** HANDOFF §5 (JVM Seon with Jolt agents, the `jolt-agents` branch) ends at the move. Confirm it is
  abandoned.
- **D10. Timing.** next must be committed and quiescent, with its lanes paused, and `seon-jolt/notes/DECISIONS.md`
  freezes at the move: from then on `docs/notes/` is the authority. When?
- **D11. Help placeholders.** Inherited from spec-reds: may help teach the `my.attempt-<your attempt>` placeholder?
  This decides 3 idea #3 rows.
