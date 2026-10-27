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
| `../next-NAME` (the worktrees) | bin/worktree:2, :111, :124, :147, bin/worktree-check:9, bin/update:13 (`../next-merges`), default.edn:234, docs/merging.md:7 | where worktree sessions live | **done [branch]**: one function, `bin/worktree --path NAME`, which is `<root>/.worktrees/NAME` (owner ruling 2026-10-23, §5); bin/worktree-check and bin/update ask it |
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

> **Superseded by the owner's ruling of 2026-10-23: the homes are the GitHub forks (option c), step C.** The options and measurements below stay as the record; (a1) was the first approval, and its branch-saving step B is still done and still wanted.

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
  - `git fetch <repo> 'refs/heads/*:refs/tags/history/<dir>/*'`: the outer repo as `history/prototype`, the nested ones under `history/<dir>/<branch>`, and fork-family ones as `attic/<dir>/<branch>` in their module.
  - A tag is git's name for a fixed record, and `git clone` copies every tag. History stays readable (`git log history/prototype -- proto/core`) and deduplicated.
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
- Both are moved by root **[root]**, because root edits them all the time and the outer copies have uncommitted changes. The move is one commit in next. The outer copies then get a one-line pointer, in the commit taken just before the `history/prototype` tag.

## 5. Where worktrees live (owner ruling 2026-10-23)

The root is **`~/src/seon-next`** (next, moved), and a worktree is **`<root>/.worktrees/NAME`**, gitignored. **Done [branch]:**
- `bin/worktree --path NAME` is the one answer (`$MAIN_TREE/.worktrees/NAME`, the main tree found from git's common dir, so it is right from any checkout); `bin/worktree`, `--sync`, `--remove`, `bin/update` (`.worktrees/merges`) and `bin/worktree-check` all ask it. `SEON_WORKTREES`, which an earlier draft of this note proposed, is dropped: one place, no second mechanism.
- `bin/worktree NAME` on an existing branch `jolt/NAME` checks it out (it used `-b` only), so a worktree can be recreated after the move.
- `.gitignore` has `/.worktrees/`; `/test-results/` and `/config/worktree-ports` are in the root already, so they move with it.
- Checked: nothing in Seon walks the root's directory (`seon.fx` lists only `:seon.config.source/dirs`, `test_runner` only `test/`, `bin/laws` only `src/`, `bin/build`'s `git add -A` honours `.gitignore`), so `.worktrees/` is invisible to the program, the build and the tests. The cost is that a person's `du`, Spotlight or editor search over the root sees them.
- The port registry's key is the worktree's directory name, so it is now `NAME`, not `next-NAME`: a worktree recreated after the move gets a fresh block (`bin/worktree --remove` frees the old line).
- Existing sibling worktrees (`../next-NAME`) are not found by `--sync NAME` or `--remove NAME` any more. Until they are gone, `bin/worktree --remove-at DIR` removes one, and `git -C <main> worktree list` shows them.

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
rm worktree-ports && ln -s next/config/worktree-ports worktree-ports   # next/config/worktree-ports already holds the union of both registries (2026-10-23, stale entries pruned); re-union anything a not-yet-merged branch allocated in ../worktree-ports first
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
  git -C $M show-ref -q --verify refs/heads/$b || git -C $M fetch -q $S "refs/heads/${b}:refs/heads/${b}"; done
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

**C. [root, on "go C", after A, with no session mid-merge of .gitmodules] Repoint the URLs. Owner ruling 2026-10-23: GitHub, not the module store (§2's recommendation (a1) is superseded; (c) is chosen).** `old-cleanup` pushed every non-main branch of the module stores to the forks (no force), and I checked with `gh api` that each pin at this branch's head is present: jolt 4342b73, chez 80e4b9f, datahike fd0c065, konserve 3dbd4d5, malli 5a830e1, crypto 902196e, jolt-fressian 8f3eb5d, persistent-sorted-set 9cce727, jolt-overlay 4ecb7a8.
- In `.gitmodules`: `https://github.com/seantempesta/` + `jolt`, `ChezScheme` (chez), `datahike`, `konserve`, `malli`, `crypto`, `jolt-fressian`, `persistent-sorted-set`, `jolt-overlay`. The last two are new and private: a fresh clone needs `gh` auth (a credential helper for github.com). awesome-jolt, zstd and lmdb stay on upstream. Step B still holds: the module stores keep every branch locally, so nothing depends on GitHub being up except a fresh clone.
- Then, **in the main tree only**, `git submodule sync`; commit `.gitmodules` with docs/forks.md rewritten (each "Fork repo:" line becomes its GitHub URL). Close task `seon.main/forks-live-in-one-place` with this plan's commit.
- C also edits README.md's clone line (`protocol.file.allow=always` is only for local-path submodules). Prove it once, niced, in the background, in a scratch directory (never in the tree): `git clone --recurse-submodules <main tree> <scratch>/clone` (the main tree as origin; the `.gitmodules` URLs come from the commit), then `cd <scratch>/clone && nice -n 15 bin/build seon && bin/check smoke`. Then tell `old-cleanup` (local_d488bf27) it may delete the nine source folders, **only after C is merged on main**.
- Running worktrees are unaffected: they use the module repos directly, never the URL. A branch that merges main takes the new `.gitmodules` without conflict unless it edits `.gitmodules` itself.

**D. [root] History tags in next:**
1. Root commits the outer repo's pending work (the staged renames, RESUME, DECISIONS) and does step E.
2. Then: `git -C next fetch ~/src/seon-jolt 'refs/heads/main:refs/tags/history/prototype'`.
3. Then, for each nested repo in §3: `git -C next fetch <repo> 'refs/heads/*:refs/tags/history/<dir>/*'`. Fork-family repos go to their module as `attic/...` (in step B).
4. Verify with the same LOST loop against next's tags.

Expected: +20 MiB in next/.git. A tag is a record, so it is never deleted (AGENTS §9).

**E. [root] Content moves into next (one commit):**
- `RESUME.md` → `docs/plan.md` and `notes/DECISIONS.md` → `docs/decisions.md`: **done by `wiki-live` (ed8218a, 68eb8ec)**, not here; `notes/archive/DECISIONS-superseded.md` is copied with the archive below (the docstrings and tests that cite it say `notes/archive/`);
- **Docs only (owner, 2026-10-23): the parent's `notes/archive/` and `notes/upstream/` are not copied into next.** They stay reachable in history, `git show history/prototype:notes/archive/<file>` (step D), and task `seon.docs/docs-are-the-only-documentation` folds their lasting facts into docs sections and repoints the citations. Until that task has run, the citations of `notes/archive/...` in `src`, tests and task plans name a path that is not in the tree: so step E repoints them to the tag form `history/prototype:notes/archive/<file>` (a path git reads, no file needed), in the same commit as the one `bin/prove`;
- `flowlab/`: its checks (`docs_check.clj`) become tests or are dropped by the same task; until then `docs/flow.md` and `docs/core-async.md` cite `history/prototype:flowlab/docs_check.clj`;
- the citations fixed: AGENTS.md:9/:24/:32/:36, BAGGAGE.md:3/:27, docs/forks.md:4/:23, docs/flow.md:3/:71, docs/core-async.md:3, the 21 `notes/...` citations in task plans (to `notes/archive/...`), seon.fx.edn's absolute path;
- optionally `tmp/agy/` → `runs/agy/`.
Each running session's brief names `../RESUME.md`; they read the new path after merging main.

**F. [owner] The deletion** (the move is step R).
- After A–E, nothing in next refers outside it except `~/src/seon` (v1, on purpose) and GitHub. Check with:
```sh
git -C next ls-files | grep -v '^notes/' | xargs grep -nE '\.\./|/Users/sean|seon-jolt'
```
  Only intra-repo `../` links should remain.
- Then the owner deletes the parent folder's experiment directories (the survey's list, about 15.9 GiB), once its EXTRACT items 1–3 are filed as tasks. Tags preserve their history; their untracked build output is not kept.

**R. [root, last] The fresh root: a clone at `~/src/seon-next`, not a move** (owner, via root, 2026-10-23: "a clean, fresh directory with history kept"). The old `~/src/seon-jolt/next` stays untouched until the clone is proven, so there is no rollback to plan: delete the clone. Run after C, D and E have merged on main and the open branches have merged or been parked (a branch not yet merged is a branch of the old repo: `git fetch` it into the clone from the old repo before the old one goes). What the clone is and is not, measured:
- **History kept:** the clone has every commit and tag of next, so also the `history/*` tags (D) and the branches of the old repo as `origin/*` remote-tracking refs (make the ones wanted local: `git branch jolt/NAME origin/jolt/NAME`). The outer repo's history is the `history/prototype` tag.
- **Nothing carried by accident:** `target/`, `runs/`, `config/local.edn`, `test-results/`, `config/worktree-ports` are untracked and stay behind, so nothing absolute comes along (`runs/store` is erased when `rename` merges, owner ruling: test data, so the new root starts with a fresh store and no absolute path can carry over).
- **Submodules** come from the GitHub URLs of C (the two private repos need `gh` auth); Chez's and Jolt's nested submodules from upstream GitHub. No `protocol.file.allow` is needed any more, and the README's clone line stops saying it.

```sh
# R1: the clone (network; niced build in the background, the long run, once). No remote is kept: nothing to fetch from or push to by accident.
git clone --recurse-submodules ~/src/seon-jolt/next ~/src/seon-next && cd ~/src/seon-next
git remote remove origin                  # the old repo stays the source of the old branches until step R4
git submodule status | grep -v '^ '       # empty: every submodule at its pin
# R2: the build, with the old root's build cloned in (copy-on-write, as bin/worktree does for a worktree: blocks are shared until written), so the build is only what moved
mkdir -p target && for e in ~/src/seon-jolt/next/target/*(N); do [[ ${e:t} == (runs|check|tmp|home) ]] || cp -cR $e target/; done
nice -n 15 bin/build                      # each step skips what its stamp says is up to date (stamps are keyed by the pins, not the path)
# R3: the proof of self-containment (all three must hold)
bin/prove                                 # green, or only what the clone's different path reaches reruns
git grep -nE 'seon-jolt|/Users/' -- . ':!notes' ':!reference-code'      # prints nothing
git grep -nE '(^|[^.a-zA-Z])\.\./' -- . ':!notes' ':!reference-code' | grep -v '](\.\./'   # prints nothing (a `](../X.md)` link inside the root is fine)
# R4: the branches still wanted (list them in the old repo, fetch the ones that hold work not on main), and the standing root
git -C ~/src/seon-jolt/next branch --list 'jolt/*' --no-merged main
git fetch ~/src/seon-jolt/next 'refs/heads/jolt/NAME:refs/heads/jolt/NAME'    # per branch wanted
bin/ports                                 # writes config/local.edn fresh (main tree: 7888 7777)
bin/root start                            # no store: it makes one and builds main from this checkout (docs/source.md); jolt/merges, if wanted: bin/worktree merges
# R5: test-results (197 MB): digests are content-keyed and valid anywhere, so `cp -cR ~/src/seon-jolt/next/test-results .` saves the first prove; the old runs/store is not carried
# R6: Claude memory and the shell: the project memory directory is keyed by path, so
cp -R ~/.claude/projects/-Users-sean-src-seon-jolt-next ~/.claude/projects/-Users-sean-src-seon-next
# R7: worktrees from now on: bin/worktree NAME (at ~/src/seon-next/.worktrees/NAME)
```
**Ruled by root, 2026-10-23:** the R3 grep excludes `notes/` (its 55 files are dated records of work; nine of them name the old paths as they were, and the parent's `notes/archive` and `notes/upstream` copied in step E would add 37 more; rewriting a record would falsify it). Everything else must be clean: src, test, bin, resources, docs, AGENTS, BAGGAGE, README, deps and config. No store is carried. R0 is dropped.

**Fallback, only if the clone cannot be proven:** the in-place move, `mv ~/src/seon-jolt/next ~/src/seon-next`, then `git worktree repair <each worktree path>` in the root and `git -C .git/modules/reference-code/<m> worktree repair <each submodule worktree path>` per module (rehearsed in a scratch repo: it repairs both links), with `config/local.edn` and the Claude memory directory renamed as in R6. Remove the other worktrees first (`git worktree move` refuses a worktree with submodules).

**S. [owner] The deletion of `~/src/seon-jolt`.** After R and the acceptance grep below, the parent folder holds only what `old-cleanup` bundled and the outer repo's own history (tag `history/prototype`, step D) and `history-bundles/`. The owner deletes it. `bin/agy-agent`'s old state in `../tmp/agy` (4.6 MB) goes with it unless step E copied it to `runs/agy`.

**Acceptance: next names nothing outside its root** (the dated `notes/` excepted, as they record paths as they were):
```sh
git ls-files | grep -v '^notes/' | xargs grep -nE 'seon-jolt|/Users/|\.\./' | grep -vE '^[^:]+:[0-9]+:.*(\]\(\.\./|\.\./\.\./resources)'
```
It must print nothing but v1 (`~/src/seon`, which stays on purpose) and links between files inside the root (`](../AGENTS.md)`). Today it prints the lists in §1 and "After merging land14".

## After merging land14 (3f28d3e), 2026-10-23

land14 brought new references, none fixable before step E, so they are listed here and nothing was changed for them:
- `src/seon/{fx,intr,problems,render,root,writer}.clj` docstrings and `test/seon/procs_test.clj:141` cite `../notes/archive/...` (land14 moved the in-next notes into `notes/archive/`, and the parent's `notes/archive/` is to be copied beside them in step E; the citations then drop the `../`). They are `src` edits, so they move reach digests: do them in step E's commit, with one `bin/prove`.
- `docs/forks.md` "Fork repo: ~/src/seon-jolt/..." (9 lines, 11 and 18 to 182): step C's rewrite.
- `resources/seon/tasks/seon.merge.edn:2` (`~/src/seon-jolt/l3/next`, `libs/datahike`), `seon.root.edn:55` (`flowlab/r7_cron_door_merge.clj`), `seon.context.edn:50` (`em1`), `seon.fx.edn:2` (the absolute PKG plan path), `seon.main.edn` shrink-dead-code plan (the AUDIT path): their code now lives in history tags and bundles; step E points each at `history/...` or `notes/archive`. `seon.merge`'s `0823517` is in `history-bundles/l3__next.bundle` and, after step D, tag `history/l3/next/*`.
- `web/bench/sweep.sh:5` comment (`~/src/seon-jolt/bench.lock`).
- `AGENTS.md:9,24,191,192` and `BAGGAGE.md:5`: RESUME and DECISIONS (step E).
- Fixed in the merge: `docs/source.md:47` (test-results path).

The parent folder has changed under this plan: `old-cleanup` bundled the nested repos into `~/src/seon-jolt/history-bundles/` (MANIFEST.md lists them) and deleted `l2/`, `jolt-l1/`, `e4/datahike-b` and others. Step D can fetch tags from those bundles instead of the live repos (same commits), and `history-bundles/uncommitted/` holds the outer repo's uncommitted work.

## What this branch did not do

- It changed no main-tree repo, `.gitmodules`, module store or parent-folder file.
- It deleted nothing and pushed nothing.
- The scratch measurements (relative URL resolution; the `--shared` clones that sized the tags) ran in the session's scratchpad, and nothing of them runs now.
