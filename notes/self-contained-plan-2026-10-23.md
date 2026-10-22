# Making next/ self-contained, without losing history (2026-10-23)

The owner's goal (2026-10-23): "the goal is for the next folder to be the new seon root folder so please make sure it's self contained and isn't referencing other code or projects", keeping "the useful parts of the git history". This note has the inventory of every reference from next/ to something outside it, the options for the forks' homes and for the parent folder's history (with measurements), a recommendation, and the steps in order. Each step is marked **[branch]** (done on `jolt/self-contained`), **[root]** (touches the main tree or the parent folder, so it waits for root's go and for running sessions to merge) or **[owner]** (a decision or a deletion).

Sources: `old-code-survey-2026-10-23.md` and `wiki-audit-2026-10-23.md` (in `next/.claude/worktrees/repo-overview-prioritize-5dd84e/notes/`), and the task `seon.main/forks-live-in-one-place` (`resources/seon/tasks/seon.main.edn`), which this plan replaces: that task proposed `~/src/seon-jolt/forks/`, outside next.

## 1. Inventory: what next/ reaches outside itself

Every tracked file outside `reference-code/` and `notes/` was grepped for `../`, `/Users/sean`, `~/src`, `seon-jolt` and `flowlab`. `notes/` are dated records, which keep the paths they had when written.

| Reference | Where | What it is | How it comes inside |
|---|---|---|---|
| 9 fork URLs, absolute (`jolt`, `e14/chez-share`, `libs/{datahike,konserve,jolt-overlay,crypto}`, `e4/pss-a`, `e9/jolt-fressian`, `l1/wt/malli`) | `.gitmodules`, and the main tree's `.git/config` | where `git submodule update` and a clone fetch each fork | relative URLs into next's own module store (§2) **[root]** |
| `../RESUME.md` | AGENTS.md:9, :24, :32, :36 | root's state: THE PLAN and "In flight" | `docs/plan.md`, a wiki page (§4) **[root]** |
| `../notes/DECISIONS.md` (147 lines) | BAGGAGE.md:27, docs/forks.md:4, :23, and about 20 citations of the form `DECISIONS "..."` in src, test, tasks and docs | the owner's rulings | `docs/decisions.md`, one section a ruling, so a citation can be `decisions#slug`, which `seon.ref` resolves (§4) **[root]** |
| `../notes/archive/*` (82 files, 1.9 MB) and `../notes/upstream/*` | 21 citations from task plans, e.g. `notes/option3-PKG-plan.md` (seon.fx.edn, as an absolute path), `notes/AUDIT-2026-10-21-laws.md` (4), `notes/option3-F1-flow.md` (3) | the experiments' write-ups the tasks cite | copied into `next/notes/archive/` and `next/notes/upstream/` **[root]**. Then the citations are fixed to `notes/archive/...` (a wiki edit) |
| `../bin/agy-agent` | AGENTS.md:33, :75 | the Gemini reviewer runner | **done [branch]**: `bin/agy-agent`, its state moved from `../tmp/agy` to the checkout's `runs/agy` (gitignored) |
| `../tmp/agy/*` (4.6 MB) | the agy runs' records; RESEARCH notes cite `tmp/agy/research-*` | earlier reviews | moved to the main tree's `runs/agy/` **[root]**, optional |
| `../test-results` (197 MB) | `default.edn:262` (`#seon/checkout "../test-results"`), docs/parity.md:57, docs/machine.md:37 | test results by reach digest, shared across checkouts | **done [branch]**: the main tree's `test-results/` (gitignored). `bin/env` exports `SEON_TEST_RESULTS=$MAIN_TREE/test-results`, found from git's common dir as the compile caches are. The shipped default is the checkout's own `test-results`. This also fixes a latent fault: a generation worktree (`runs/generations/<commit>`) resolved `../test-results` to `runs/generations/test-results`. The merge moves the directory **[root]** |
| `../worktree-ports`, `.lock` | bin/ports:17, bin/worktree:126, docs/config.md:26 | the worktrees' port blocks | **done [branch]**: the main tree's `config/worktree-ports` (gitignored). The merge moves the file **[root]** |
| `bin/ports`: the main tree is the one named `next` | bin/ports:14 | how a checkout knows it gets 7888/7777 | **done [branch]**: the main tree is the checkout whose git dir is the common dir, whatever its folder is called |
| `../next-NAME` (the worktrees) | bin/worktree:2, :111, :124, :147, bin/worktree-check:9, bin/update:13 (`../next-merges`), default.edn:234, docs/merging.md:7 | where worktree sessions live | **done [branch]**: one function, `bin/worktree --path NAME`. With `SEON_WORKTREES` set, a worktree is `$SEON_WORKTREES/NAME`. Otherwise it sits beside the main tree, named after it (`../next-NAME` today, `../seon-NAME` if next is renamed `seon`). bin/worktree-check and bin/update ask it (§5) |
| `../flowlab/docs_check.clj`, `flowlab/run.sh`, `flowlab/tasks.edn` | docs/flow.md:3, :71, docs/core-async.md:3, task jolt.async.edn:3 | the checks behind the `[F..]` and `[A..]` claims in those pages | copied to `next/notes/flowlab/` **[root]**. Not under `test/`, which the standing root imports as program. The citations are then fixed |
| `../vendor-c/lmdb` | README.md:22, docs/failure-points.md:141 | a stale citation: `bin/build` builds LMDB from `reference-code/lmdb` (bin/build:111) | **done [branch]** |
| `../proto/*`, `../proto/BAGGAGE.md` | src/README.md:2, BAGGAGE.md:3 | the first prototype | src/README.md reworded **[branch]**. Its history is kept by §3's tag. BAGGAGE.md:3 is fixed in the same commit as §3 **[root]** |
| `~/src/seon-jolt/next` in the clone line | README.md:9 | — | **done [branch]** (`PATH/TO/next`) |
| `~/src/seon-jolt/...` fork homes | docs/forks.md:5–77 | provenance | rewritten in step C **[root]** |
| `~/src/seon` (v1) | AGENTS.md, docs/parity.md, README.md, tasks | v1, read-only reference | **stays**. It is the project being replaced, not part of next. Also, `~/src/seon-jolt/seon` (1.1 GB) is a clone of it whose HEAD is in `~/src/seon` |
| `/Users/sean/src/seon-jolt/notes/option3-PKG-plan.md` | seon.fx.edn:2 | an absolute path | fixed with the notes/archive copy **[root]**; also `seon.main.edn:7` (task `shrink-dead-code`'s plan) cites `/Users/sean/src/seon-jolt/notes/AUDIT-2026-10-21-growth.md`, to `notes/archive/AUDIT-2026-10-21-growth.md` in step E |
| deps.edn `:local/root` | deps.edn | all are `reference-code/*` | already inside |
| `.mcp.json` | — | `./bin/mcp` | already inside |
| `#include "../../../resources/..."` | test/seon/config/*.edn | inside the repo | fine |

## 2. The forks' homes

### Measurements

| Fork | Module store (`next/.git/modules/reference-code/<n>`) | Source repo today | Branches only in the module (tonight's and others) | Source branches the module does not hold on any branch |
|---|---|---|---|---|
| jolt | 167 MB (135 MiB pack), 15 heads, 123 tags | `jolt/` (366 MB .git, 32 heads) | `jolt/fork-carriers`, `argv`, `cache-records-relative`, `eval-outside-build`, `infer-nil-union`, `j2-on-main`, `load-form`, `missing-local-root`, `runtime-fingerprint`, `thread-roster`, `tick-budget` | absent: `pf1-f2only` f29a176, `upstream/fiber-park` a00c194, `upstream/remote-chan` ad28b62, `upstream/fork-reprotect-jit` d761676 (the same patch as the module's cb8dada, re-committed; keep both). Remote-tracking only: `e4-arraycopy`, `pf1`, `u1-base`, `upstream/{arraycopy-object-block,deftype-field-access,host-site-coll-reads,static-code-cow}` |
| chez | 51 MB (6.5 MiB pack, **shallow**, as is its source), 5 heads | `e14/chez-share` (11 MB) | `jolt/thread-bytes`, `jolt/signal-wakes-wait`, `jolt/gc-sweepers` | none |
| datahike | 5.5 MB | `libs/datahike` (5 MB, 16 heads) | `jolt/deref-config`, `eid-lease`, `values-out-of-line`, `jvm` | absent: `jolt/merge-diff` 8311254 (L3's merge by index diff, survey EXTRACT 1), `jolt/pss-cursors`, `jolt/qcache-lineage`, `jolt/qcache-noinherit`, `upstream/datoms-diff-portable` |
| konserve | 2.3 MB | `libs/konserve` | `jolt/atomic-append`, `jolt/lz4-ffi` | absent: `l1/lockwait-probe` (a probe) |
| malli | 9.8 MB | `l1/wt/malli` | — | remote-tracking only: `master` |
| persistent-sorted-set, jolt-fressian, jolt-overlay, crypto | under 1 MB each | `e4/pss-a`, `e9/jolt-fressian`, `libs/*` | — | none |
| awesome-jolt, lmdb, zstd | small | GitHub | — | not ours; they keep their GitHub URLs |

"Absent" means the commit is not in the module store at all. "Remote-tracking only" means it is there only as `refs/remotes/origin/*`. A clone does not copy those, and they go once origin changes and anyone prunes. Every other source branch is contained in a module branch. Nested submodules (Chez's lz4, nanopass, stex, zlib, zuo; Jolt's `vendor/*`) all have GitHub URLs and need nothing.

Copies of these forks elsewhere in the parent folder hold more unique commits, all small. Fetching them as tags into the module stores, measured on `--shared` scratch clones:
- jolt + `jolt/` + `l2/jolt` (19 unpushed) + `jolt-l1` (14 unpushed) → +111 objects, 0.9 MB;
- datahike + `libs/datahike` + `e4/datahike-b` (5) → 0.5 MB;
- konserve + `libs/konserve` → 24 KB.

### How git resolves a relative submodule URL (measured, git 2.54, in the scratchpad)

A `./x` or `../x` URL is resolved against the superproject's default remote. With no remote, it is resolved against the superproject's own directory. next has no remote. Results:
- main tree, no remote: `./forks/lib.git` resolves to `<main>/forks/lib.git` ✓; after a rename and `git submodule sync` it resolves to the new path ✓;
- `git clone next c1`: resolves to `next/forks/lib.git` (origin's) ✓;
- clone of a clone (`c1` → `c2`): resolves to `c1/forks/lib.git`, which a clone does not have ✗;
- with the URL `./.git/modules/<path>` instead: main ✓, clone ✓, **clone of a clone ✓**, because every clone has its own `.git/modules`;
- `git submodule sync` run inside a worktree resolves against the worktree (`wt/forks/...`) and rewrites the shared `.git/config` URL wrongly. That is one more reason for the existing rule: never `git submodule` in a worktree. `bin/worktree` reads the module repos directly, so worktrees never use the URL.

### Options, simplest first

**(a1) The module store is the home. Recommended.** This is the owner-approved "Move into next/.git/modules". `.gitmodules` URL: `./.git/modules/reference-code/<name>`. There is no second copy and no new directory: the module repos already hold every pin and all of tonight's branches. Each module's `origin` becomes itself (a no-op fetch).
- Precondition: every branch is a local head or tag in the module, so a clone copies it and nothing hangs off `refs/remotes`. A clone of next gets each fork's branches as its own module's remote-tracking refs, and the pinned commits as objects. A clone of that clone builds, but sees only the default branch of each fork.
- Cost: +1.4 MB of stray branches. The URL path looks unusual, but git handles it.

**(a2) Bare mirrors inside next, at `next/forks/<name>.git` (gitignored), with URL `./forks/<name>.git`.**
- Cleaner to read, and `git clone --mirror` hardlinks objects, so there is no extra disk.
- But it is a second copy to keep in step: tonight's branches are made in the module repos by submodule worktrees, so each would be fetched into forks/ too.
- A clone of a clone fails.

**(b) Git subtree: each fork's history merged into next's own repo.**
- next's pack grows from 7.6 MiB to about 160 MiB (jolt 135 + 17 loose, malli 8.7, chez 6.5 but shallow, so its subtree history would stop at the graft).
- The fork's files become tracked next files. `git log -- reference-code/jolt` stays readable if merged without `--squash`.
- But:
  - pins and `jolt/<topic>` branches stop being separate (a dependency fix becomes a next branch);
  - an upstream PR needs `git subtree split`, which takes minutes on Jolt's 4.5k commits;
  - Jolt's and Chez's own nested submodules would hang off next;
  - `bin/worktree`'s setup, `bin/build`'s stamps (`git rev-parse HEAD` per submodule) and `seon.fx` (`ls-files -s -- reference-code`) all change.
- It moves AGENTS §1's "a cause in a dependency is fixed there, on a `jolt/<topic>` branch". Not recommended.

**(c) Hosted forks on GitHub (`seantempesta/<name>`, which exist for jolt, datahike, malli, konserve).**
- The only option that works for a clone on another machine.
- Needs the owner's push of every `jolt/*` branch. AGENTS §8 says never push.
- Can be added later beside (a1): `.gitmodules` keeps the relative URL, and a GitHub remote is added to each module for pushing.

### Recommendation

(a1) now, and (c) when the owner wants a second machine. It meets "self-contained" with zero new copies, survives renaming or moving next (after `git submodule sync` in the main tree), and clones work two hops deep.

## 3. The parent folder's history, and the nested repos

### Measurements

- The outer repo `~/src/seon-jolt`: 313 commits (2026-10-20 to 10-22), an 18.9 MiB pack, 52 MB of tracked files, root commit 10a9e22 "Checkpoint: Seon on Jolt exploration…". Its history is unrelated to next's (next's root is 5833bd3).
- It also has **uncommitted work**:
  - `RESUME.md`, `notes/DECISIONS.md`, `proto/BAGGAGE.md` and two e15 files are modified;
  - 85 staged renames into `notes/archive/`;
  - 3070 tracked files are deleted on disk (e1, e1b, t5…), which history keeps.
- Fetched into a `--shared` scratch clone of next, it adds 19.8 MiB. The nested repos below add 0.4 MiB more after gc (20.16 MiB in all), because the outer repo already tracks most of their source.

Nested repos with commits found nowhere else (the survey's EXTRACT 6, re-measured):

| Repo | Commits | Unpushed | Family | Home |
|---|---|---|---|---|
| `proto/core` | 20 | 20 | own root 8044790 | next, tag `history/proto/core/*` |
| `proto/flow`, `scratch/f1head`, `scratch/f2pg` | 21 each | 21 | one root, a84fc27 | next, `history/...` |
| `pf1/core-fixed` | 3 | 3 | own | next |
| `r3/{next,base0,rebase}` | 16 each | 16 | root 78e5811 | next |
| `l3/next`, `r5/next`, `fx1/next` | 16, 18, 9 | 1, 10, 9 | **next's own root** 5833bd3 | next |
| `e4/rust` | 6 | 6 | own (dhix, C ABI) | next |
| `e9/datahike-jolt-src`, `e9/konserve-jolt-src` | 2 each | 2 | own ("baseline: libs copy") | next |
| `l2/jolt`, `jolt-l1` | 4564, 4548 | 19, 14 | Jolt | jolt module, tag `attic/l2-jolt/*`, `attic/jolt-l1/*` |
| `e4/datahike-b` | 8 | 5 | Datahike (root ad9fc97, its baseline) | datahike module, `attic/e4-datahike-b/*` |

### Options

- **A tag per history, fetched into the repo it belongs to. Recommended.**
  - `git fetch <repo> 'refs/heads/*:refs/tags/history/<dir>/*'`: the outer repo as `history/seon-jolt`, the nested ones under `history/<dir>/<branch>`, and fork-family ones as `attic/<dir>/<branch>` in their module.
  - A tag is git's name for a fixed record, and `git clone` copies every tag. History stays readable (`git log history/seon-jolt -- proto/core`) and deduplicated.
  - No file lands in the working tree, so grep, the build and the program import never see it.
  - Cost: +20 MiB to next's .git (now 256 MB with modules), +1.4 MiB to the modules.
- **A `git bundle` per repo** (the owner's suggestion): the same content as one file each.
  - Tracked in next, it would be 20+ MB of binary blobs that git cannot deduplicate against anything.
  - Gitignored, a clone would not carry it.
  - Unreadable until unbundled. A worse form of the tags.
- **A subtree under `history/`**: puts 52 MB of prototype files into next's working tree and into every worktree checkout. No.
- **A doc that points at the outer repo**: loses everything when the parent folder is deleted. No.

## 4. RESUME and DECISIONS

- `../RESUME.md` (48 lines: THE PLAN, In flight) becomes **`docs/plan.md`**. It is root's state, kept current like any wiki page. AGENTS.md:9, :24, :32, :36 then say `docs/plan.md`.
- `../notes/DECISIONS.md` (147 lines) becomes **`docs/decisions.md`**: the owner's rulings, a section each, in the order they were made. A ruling superseded later stays, marked superseded with a link to its successor. `../notes/archive/DECISIONS-superseded.md` goes to `notes/archive/`.
- With a section per ruling, the existing prose citations (`DECISIONS "Callbacks are data"`) can become links (`[decisions#callbacks-are-data](docs/decisions.md#...)`) that `seon.ref` resolves into the task's or docstring's references. That is a follow-up wiki edit, not part of the move.
- Both are moved by root **[root]**, because root edits them all the time and the outer copies have uncommitted changes. The move is one commit in next. The outer copies then get a one-line pointer, in the commit taken just before the `history/seon-jolt` tag.

## 5. Where worktrees live

**Done [branch]:**
- `bin/worktree --path NAME` is the one answer;
- with `SEON_WORKTREES` set, a worktree is `$SEON_WORKTREES/NAME`;
- otherwise it sits beside the main tree, named after it.

So today nothing moves (`../next-NAME`), and if next is renamed `seon`, the worktrees are `../seon-NAME`. The main tree is found from git's common dir, so the name is right from any checkout.

**Proposal [owner]:**
- `SEON_WORKTREES=<main>/.worktrees`, which makes the folder fully self-contained: deleting it deletes everything, and nothing is left beside it.
- Checked: nothing in Seon walks the main tree's directory. `seon.fx` lists only `:seon.config.source/dirs`, `test_runner` only `test/`, `bin/laws` only `src/`, and `bin/build`'s `git add -A` honours `.gitignore`. So a gitignored `.worktrees/` is invisible to the program, the build and the tests. Claude's desktop app already does the same (`.claude/worktrees/`).
- The cost: tools a person runs over the whole folder (`du`, Spotlight, an editor's search) see the worktrees.
- To adopt it, set one default in `bin/worktree`'s `wt_path` and add `/.worktrees/` to `.gitignore`. Existing worktrees stay where they are until removed (`--sync` and `--remove` for them need `SEON_WORKTREES` unset).

## 6. The steps, in order

**A. [branch, done] Paths in bin, docs and config** (commit on `jolt/self-contained`):
- `bin/worktree --path`;
- bin/ports (main by git dir, the registry in `config/`);
- bin/update and bin/worktree-check via `--path`;
- `bin/env` exports `SEON_TEST_RESULTS`, with the default.edn default inside the checkout;
- `bin/agy-agent` with state in `runs/agy`;
- `.gitignore` gains `/test-results/` and `/config/worktree-ports*`;
- README, docs/{config,failure-points,machine,merging,parity}.md, src/README.md, AGENTS.md (agy-agent).

**Merging A needs, in the same sitting [root]:** (otherwise new runs start an empty results directory and a new registry)
```sh
cd ~/src/seon-jolt
mv test-results next/test-results && ln -s next/test-results test-results
mv worktree-ports next/config/worktree-ports && ln -s next/config/worktree-ports worktree-ports
mv worktree-ports.lock next/config/worktree-ports.lock && ln -s next/config/worktree-ports.lock worktree-ports.lock
```
- The symlinks keep worktrees still on old main writing to the same files until they merge main; they are deleted with the parent folder.
- `default.edn` changed, so `bin/prove` reruns what reaches the config.

**B. [root] Make every fork branch a local ref in its module store.** Additive only: no ref is moved or deleted, and running sessions see new branch names and nothing else. For each module `M=next/.git/modules/reference-code/<n>` and its current source `S`:
```sh
# 1. remote-tracking-only branches become local heads (jolt: e4-arraycopy pf1 u1-base upstream/{arraycopy-object-block,deftype-field-access,host-site-coll-reads,static-code-cow}; malli: master)
git -C $M for-each-ref --format='%(refname:lstrip=3) %(objectname)' refs/remotes/origin | grep -v '^HEAD ' |
  while read b s; do git -C $M show-ref -q --verify refs/heads/$b || git -C $M update-ref refs/heads/$b $s; done
# 2. the source's branches the module lacks (jolt: pf1-f2only upstream/{fiber-park,remote-chan,fork-reprotect-jit};
#    datahike: jolt/{merge-diff,pss-cursors,qcache-lineage,qcache-noinherit} upstream/datoms-diff-portable; konserve: l1/lockwait-probe)
git -C $S for-each-ref --format='%(refname:lstrip=2)' refs/heads | while read b; do
  git -C $M show-ref -q --verify refs/heads/$b || git -C $M fetch -q $S "refs/heads/$b:refs/heads/$b"; done
# every source branch, also those whose name the module uses at another commit (jolt/main, and
# upstream/fork-reprotect-jit: the module's cb8dada and the source's d761676 are one patch committed twice): a tag each, nothing overwritten
git -C $M fetch -q $S 'refs/heads/*:refs/tags/attic/source/*'
# 3. the other copies: jolt ← l2/jolt, jolt-l1; datahike ← e4/datahike-b
O=~/src/seon-jolt
git -C $O/next/.git/modules/reference-code/jolt fetch -q $O/l2/jolt 'refs/heads/*:refs/tags/attic/l2-jolt/*'
git -C $O/next/.git/modules/reference-code/jolt fetch -q $O/jolt-l1 'refs/heads/*:refs/tags/attic/jolt-l1/*'
git -C $O/next/.git/modules/reference-code/datahike fetch -q $O/e4/datahike-b 'refs/heads/*:refs/tags/attic/e4-datahike-b/*'
```
Then **verify** that every branch tip of every source repo and every copy is an ancestor of a ref in its module. The step is done only when this prints nothing:
```sh
# for each pair (M, S): the module and its source, and the module and each copy in step 3
git -C $S for-each-ref --format='%(objectname) %(refname)' refs/heads |
  while read s r; do [ -n "$(git -C $M for-each-ref --contains $s refs/heads refs/tags | head -1)" ] || echo "LOST $S $r $s"; done
```

**C. [root, after B, with no session mid-merge of .gitmodules] Repoint the URLs.**
- In `.gitmodules`, the 9 forks get `url = ./.git/modules/reference-code/<n>`.
- Then, **in the main tree only**, `git submodule sync`, and check that `git -C reference-code/<n> remote get-url origin` names the module itself. Commit `.gitmodules` with the rewritten docs/forks.md, whose "Repo:" lines say "next's module store; branches …". Close task `seon.main/forks-live-in-one-place` with this plan's commit.
- Prove it with a fresh clone, niced and in the background (this is the long run: once):
```sh
git -c protocol.file.allow=always clone --recurse-submodules ~/src/seon-jolt/next /tmp/next-clone
cd /tmp/next-clone && nice -n 15 bin/build seon && bin/check smoke
```
  Then a clone of that clone: `git submodule update` resolves into its `.git/modules`.
- Running worktrees are unaffected: they use the module repos directly, never the URL. A branch that merges main takes the new `.gitmodules` without conflict unless it edits `.gitmodules` itself.

**D. [root] History tags in next:**
1. Root commits the outer repo's pending work (the staged renames, RESUME, DECISIONS) and does step E.
2. Then: `git -C next fetch ~/src/seon-jolt 'refs/heads/main:refs/tags/history/seon-jolt'`.
3. Then, for each nested repo in §3: `git -C next fetch <repo> 'refs/heads/*:refs/tags/history/<dir>/*'`. Fork-family repos go to their module as `attic/...` (in step B).
4. Verify with the same LOST loop against next's tags.

Expected: +20 MiB in next/.git. A tag is a record, so it is never deleted (AGENTS §9).

**E. [root] Content moves into next (one commit):**
- `RESUME.md` → `docs/plan.md`; `notes/DECISIONS.md` → `docs/decisions.md`;
- `notes/archive/` → `notes/archive/`; `notes/upstream/` → `notes/upstream/`;
- `flowlab/` → `notes/flowlab/`;
- the citations fixed: AGENTS.md:9/:24/:32/:36, BAGGAGE.md:3/:27, docs/forks.md:4/:23, docs/flow.md:3/:71, docs/core-async.md:3, the 21 `notes/...` citations in task plans (to `notes/archive/...`), seon.fx.edn's absolute path;
- optionally `tmp/agy/` → `runs/agy/`.
Each running session's brief names `../RESUME.md`; they read the new path after merging main.

**F. [owner] The deletion and the move.**
- After A–E, nothing in next refers outside it except `~/src/seon` (v1, on purpose) and GitHub. Check with:
```sh
git -C next ls-files | grep -v '^notes/' | xargs grep -nE '\.\./|/Users/sean|seon-jolt'
```
  Only intra-repo `../` links should remain.
- Then the owner deletes the parent folder's experiment directories (the survey's list, about 15.9 GiB), once its EXTRACT items 1–3 are filed as tasks. Tags preserve their history; their untracked build output is not kept.
- To make next the root folder: `mv`, then in the moved main tree:
  - `git worktree repair <each worktree>` (their `.git` files name absolute paths);
  - `git submodule sync`;
  - each module's worktrees need `git -C reference-code/<n> worktree repair` too;
  - `bin/root` restarts on the same store.
- Doing F after every running worktree session has merged and been removed makes it a plain `mv`.

## What this branch did not do

- It changed no main-tree repo, `.gitmodules`, module store or parent-folder file.
- It deleted nothing and pushed nothing.
- The scratch measurements (relative URL resolution; the `--shared` clones that sized the tags) ran in the session's scratchpad, and nothing of them runs now.
