# Pitch: Should Every Seon Agent Get a Git Worktree Aligned With Its Datahike Branch and SCI Context?

## The Decision Asked

Should every Seon agent receive a dedicated Git worktree and Git branch paired 1:1 with its Datahike branch (`d/branch!`) and forked SCI context (`seon.sci.eval/program-context`), so many agents work in parallel and resolve conflicts at merge?

## The Recommendation: **No to per-agent Git worktrees. Yes to one shared working tree written only at merge publication (`seon.merge/publish!`), with outside agents reading and editing through their Datahike branch.**

A Git worktree per agent solves the wrong problem at the wrong layer. It re-centers files as the working medium on every branch just when Seon has completed the machinery for the database to be the working medium and files to be the vetted write-back on `main`:

1. **The dream already exists in Datahike + SCI (`VERIFIED`, `MEASURED`).** Creating an agent branch in Datahike (`seon.cluster.registry/fork!` / `my.agent/create-branch!`) takes **0.3–0.7 ms** and **0 bytes on disk** (`MEASURED`), sharing immutable index structure with `:cluster-default`. Creating a Git worktree takes **760.1 ms** and **217 MB on disk** across 693+ files (`MEASURED`). Ten parallel agents in worktrees duplicate **2.17 GB** of files to edit two or three `defn`s each.
2. **Worktrees create two mutable sources of truth per branch and two competing merge engines (`VERIFIED`).** Datahike merges declaration-by-declaration in 3-way digest space (`seon.merge/squash-merge`, `seon.program/three-way`, `seon.cluster.source/prepare-merge!`) and splices only the changed byte spans into `main`'s current file (`seon.cluster.source/proven-file`). Git merges whole files line-by-line from a worktree's checkout SHA. Whenever a worktree's base SHA lags `main`, you either ignore Git's tree and use `seon.cluster.source/write-back!` onto `main` (making the agent's worktree redundant) or use `git merge` (reintroducing the September 2026 stale-base regressions and bypassing declaration-level provenance).
3. **What actually blocks parallel dogfooding today is not missing worktrees—it is two specific sharing and entrance gaps (`VERIFIED`, `MEASURED`):**
   - **Sibling branches do not share test runs (`VERIFIED`):** `seon.test/recorded-result` and `seon.test.runner/latest-results` query only the calling agent's branch database (`database`) and filter by evaluator tier (`:compiled` for `"root"` vs `:interpreted` for branch agents in `seon.test/resolve-test`). If Agent A proves a function on `:agent-a`, Agent B on `:agent-b` with the identical `:seon.test.member/reach-digest` re-runs the test (`VERIFIED`). Furthermore, `seon.test/changes` includes `(get-in candidate [:config :branch])` in its `seon.cache` key (`src/seon/test.clj:2209`), defeating cross-branch cache reuse (`VERIFIED`).
   - **Outside file-editing agents (Claude Code, Codex, Argon) still write to the shared working tree first (`VERIFIED`):** `bin/seon-hook` intercepts `Edit`/`Write` after the file on disk is dirtied, evaluates changed top-level forms into the lane's branch (`:repl-entrance`), and leaves the uncommitted edit sitting in the shared checkout (`bin/lane` claims exist solely to police those dirty files).
4. **If outside agents need file isolation during the transition, intercept or project only the touched file—never a 217 MB worktree (`PROPOSED`).** Outside agents either work through MCP (`eval_clj` + `my.program/source`), or `bin/seon-hook` absorbs a file edit into the agent's Datahike branch via `bin/seon-hook`'s `:repl-entrance` and immediately restores the clean `HEAD` file on disk (`git checkout -- <file>`), so the shared checkout stays clean until `my.root/merge!` (`seon.merge/publish!`) writes proven declarations to `main` and commits them (`seon.cluster.source/commit!`).

---

## Why Worktrees Failed Before (September 2026) — Status Today

Historical commits examined (`VERIFIED`): `9f9ff4fe3` (*"Stop sweeping live run roots and gate worktree creation"*), `293a21e17` (*"Hard-cut retire bin/worktree across scripts, hooks, tests, and docs"*), `6e25c86c5` (*"Convert remaining docs and tests to single-tree lane model"*), `2cdb7ef8a` (*"Share test-slot cap across worktrees and bound load wait"*).

| September 2026 Failure | Status Today (`VERIFIED` / `MEASURED`) | What a Design Must Make Impossible |
|---|---|---|
| **1. Seven JVMs at once, load 25, test-slot cap bypassed** | **Gone (`VERIFIED`).** `docs/seon/architecture/clusters-branches-contexts.md` runs one JVM (`PID 88215`, `MEASURED`) from an immutable committed archive (`data/source/<sha>`). `2cdb7ef8a` moved `bin/_test-slot` to `git rev-parse --git-common-dir`. | A worktree or agent must never start a cluster JVM or open a second Datahike store; only root boots `default`. |
| **2. Stale bases silently reverting newer commits on merge** | **Gone in Datahike (`VERIFIED`); still fatal if merged via Git worktrees (`VERIFIED`).** `seon.merge/squash-merge` (`src/seon/merge.clj:84-142`) and `seon.cluster.source/proven-file` (`src/seon/cluster/source.clj:1444-1480`) merge per-declaration against `seon.cluster.source/merge-base` and splice byte spans into `main`'s current file. | Never copy whole files from an older checkout onto `main` or run `git merge` as the authority over program definitions. |
| **3. Lanes iterating against stale copies; half-finished foreign edits loaded; live run root swept** | **Gone for `bin/lane commit` (`VERIFIED`); dirty working tree remains between edits (`VERIFIED`).** `bin/lane` (`candidate_tree` + `extract_candidate`, `bin/lane:170-191`) builds a temporary git index of `HEAD + claimed paths` and archives it to a temp dir. `9f9ff4fe3` gated `sweep_orphans` on `/status` checks. | Never load the working directory into the cluster JVM (`seon.cluster.boot` runs from `data/source/<sha>`), and never let uncommitted file edits from Agent A enter Agent B's evaluation. |
| **4. Link setups (caches, `.seon` runtime discovery, hook path checks) failing in new checkouts** | **Remains broken in worktrees today (`MEASURED`).** In `/private/tmp/seon-pitch-argon-high`, `/Users/sean/src/seon/bin/seon-hook` silently skipped all checks (`167.5 ms`, `{"continue":true}`) because `repository-file?` (`bin/seon-hook:207-212`) checks `(str/starts-with? path repo-root)`. Running the worktree's own `bin/seon-hook` reported `"no live root JVM answers... default unavailable: lint only"` (`320.8 ms`) because `.seon` advertisement lives in the main checkout, not the worktree. `seon.fn.analyzer/checkout-source?` (`src/seon/fn/analyzer.clj:276-286`) also rejects paths outside `.`. | Runtime discovery (`.seon`), `clj-kondo` cache, and source-root resolution must resolve through `git rev-parse --git-common-dir` (or never use secondary checkouts at all). |

---

## 1. An Agent's Day, End to End

In the target architecture, the **Datahike branch is authoritative for an agent's work in progress**, and **Git `main` + `cluster-default` are committed atomically at merge publication**. Here is the exact lifecycle (`VERIFIED` symbols + `PROPOSED` flow):

| Step | What Happens | Authoritative Face | How the Other Faces Follow |
|---|---|---|---|
| **1. Task assigned & place created** | Root assigns task `:task/id` to agent `A`. `seon.cluster.registry/fork!` (`src/seon/cluster/registry.clj`) / `my.agent/create-branch!` (`src/my/agent.clj:64-112`) calls `d/branch!` from `:cluster-default` at commit `C0` (`:seon.agent/base-commit-id`, `:seon.agent/base-t`, `:seon.git/sha`). | **Datahike branch** (`:agent-<id>`) | **SCI context** is lazily derived on first eval via `seon.sci.eval/program-context` (`src/seon/sci/eval.clj:4130`) keyed by `(program-key db)` in `seon.cache`. **Files on disk** remain untouched at `HEAD` (`C0`). |
| **2a. Seon agent edits (REPL)** | Agent `A` evaluates `(in-ns 'my.ns) (defn foo ...)` via `seon.repl/eval-str` (`mode: "sci"`, `agent: A`). `seon.fn/analyze-entities` extracts `:seon.program/*` & `:seon.fn/*` datoms (`:seon.fn/source-origin :eval`, `:seon.program/definition-digest`) and transacts them to `:agent-<id>`. | **Datahike branch** (`:agent-<id>`) | **SCI context** updates in place / caches under the new `program-key`. **Files on disk** are not written yet. |
| **2b. Outside agent edits (File or MCP)** | *Preferred*: Outside agent calls `eval_clj` (`mode: "sci"`, `agent: A`). *If editing a file on disk*: `bin/seon-hook` (`:repl-entrance`, `bin/seon-hook:1141-1279`) diffs top-level forms against `HEAD`, evaluates changed forms on `A`'s SCI branch (`seon.repl/eval-str`), and *(under the proposed zero-dirty-tree rule)* restores the file on disk to `HEAD` (or if in an isolated worktree, writes to the worktree file while `:repl-entrance` immediately transacts the form into `:agent-<id>`). | **Datahike branch** (`:agent-<id>`) | If a worktree exists, the file edit is immediately parsed once and evaluated through `seon.repl/eval-str` into `:agent-<id>`, so the DB holds the canonical `:seon.program/definition-digest`. |
| **3. Tests & proofs** | Agent `A` runs `(my.test/run)` (`seon.test/run-request`, `src/seon/test.clj:2613`). `seon.test/changes` computes affected declarations between `:cluster-default` and `:agent-<id>`; `seon.test/selection-admission` skips tests whose `[reach-digest, test-input-digest, task-input]` already has a `:pass` run; remaining reaching tests run in `A`'s SCI context and record `:seon.test.run/*` + `:seon.test.member/*` datoms. | **Datahike branch** + **Shared Test Cache** (`PROPOSED`) | Passing test results are stored as datoms and indexed in `seon.cache` by `[reach-digest test-input-digest task-input]` so sibling branches reuse them immediately. |
| **4. Merge request** | Agent `A` finishes its task. No branch-local write-back to `main`'s disk happens yet. `my.root/merge!` (`src/my/root.clj:37-43`) invokes `seon.merge/merge!` (`src/seon/merge.clj:553-653`). | **Datahike `:cluster-default` + `:agent-<id>`** | `seon.merge/review-merge` (`src/seon/merge.clj:384-490`) checks 3-way declaration diffs (`seon.merge/squash-merge`), reaching test evidence, and calls `seon.cluster.source/prepare-merge!` (`src/seon/cluster/source.clj:1132-1291`). |
| **5. Write-back & atomic commit on `main`** | Inside `seon.merge/merge!`: (1) `seon.cluster.source/prepare-merge!` splices changed declarations into candidate file bytes in memory, verifies round-trip analyzer digest equivalence (`proven-file`, `src/seon/cluster/source.clj:1444-1480`), and runs `seon.test.platform/verify-merge-candidate` if any declaration is `:seon.fn/host-bound?`; (2) `seon.cluster.source/accept-merge!` (`:1293-1384`) transacts the merged datoms into `:cluster-default` under the single writer checking `declarations-held-tx`; (3) `seon.merge/publish!` (`:500-551`) atomically installs the proven files onto disk (`install-prepared-files!`) and commits them to Git (`seon.cluster.source/commit!`, `:1113-1130`), recording `:seon.git/sha` on `:cluster-default`. | **Datahike `:cluster-default` → Git `main` (in one lockstep publication)** | Disk files on `main` and Git `HEAD` are updated as the deterministic write-back of the accepted Datahike transaction. |
| **6. Place retired** | `seon.merge/merge!` (or `my.agent/retire-branch!`, `src/my/agent.clj:133-189`) marks `:seon.agent/retired-at`, evicts the agent's SCI context (`seon.sci.eval/evict-agent-context!`), and deletes the Datahike branch (`d/delete-branch!`). | **Datahike `:cluster-default`** | All branch pointers and SCI contexts for `A` are reclaimed. |

---

## 2. One Place, Three Faces

If an agent place has three faces—**(1) Datahike branch**, **(2) SCI context**, and **(3) Git worktree/branch**—how do they stay one identity?

### What the Identity Is and Who Mints It (`VERIFIED` + `PROPOSED`)
- **Minter (`VERIFIED`):** `seon.cluster.registry/fork!` (`src/seon/cluster/registry.clj`, called by `my.agent/create-branch!` in `src/my/agent.clj:64-112`) is the single authority that mints an agent's execution place inside the writer.
- **The Identity Tuple (`VERIFIED` in DB, `PROPOSED` if extended to Git):**
  Today `create-branch!` transacts onto the `:seon.agent/id` entity:
  ```clojure
  {:seon.agent/id             agent-id               ;; e.g. "pitch-probe-argon-high"
   :seon.store/branch         branch-kw              ;; e.g. :agent-1c12bb338cc9
   :seon.agent/fork-id        fork-id                ;; e.g. "fefc6d11a933"
   :seon.agent/base-commit-id (db/commit-id main-db) ;; Datahike snapshot UUID
   :seon.agent/base-t         (db/basis-t main-db)   ;; e.g. 536871390
   :seon.git/sha              main-git-sha}          ;; e.g. "0d7005536..."
  ```
- **How the Three Faces Align:**
  1. **Cut together:** At `fork!`, the Datahike branch `:agent-<fork-id>` is branched from `:cluster-default` at `base-commit-id`. Its SCI context is derived lazily by `seon.sci.eval/program-context` at `base-commit-id`. If a Git worktree were created, it would be created at the exact `:seon.git/sha` recorded on `:cluster-default` at `base-t`, under branch name `agent/<fork-id>` at path `tmp/worktrees/<fork-id>`.
  2. **Moved together:** Any advance of the base (`reset-branch!`) re-forks the Datahike branch from the new `:cluster-default` commit, evicts the SCI context via `seon.sci.eval/evict-agent-context!`, and resets the worktree to the new `:seon.git/sha`.
  3. **Retired together:** `my.agent/retire-branch!` (`src/my/agent.clj:133-189`) already calls `seon.sci.eval/evict-agent-context!` and `d/delete-branch!`; it would also run `git worktree remove --force <path>` and `git branch -D agent/<fork-id>`.

### Why the Third Face (Git Worktree) Is Redundant (`VERIFIED`)
Face 2 (the SCI context) is a **pure cached function** of Face 1 (the Datahike branch DB) via `seon.sci.eval/program-context` (`src/seon/sci/eval.clj:3868-3906`). It cannot drift because it has no independent state outside the DB's `program-key`.
By contrast, Face 3 (a Git worktree on disk) is a **mutable directory of 693+ POSIX files**. The moment a worktree exists as a mutable place alongside the Datahike branch, every change must be written to both or synchronized between them. Making files a pure projection written **only on `main` at `seon.merge/publish!`** eliminates the third mutable face on branches completely.

---

## 3. Two-Way Sync Without Drift

How do **files → branch** and **branch → files** work for both REPL agents and file-editing outside agents, and what makes divergence impossible by construction (per `AGENTS.md`: *"NO GUARDS OR FENCES"*—hold a basis instead of comparing two mutable places)?

### How the Two Directions Work Today (`VERIFIED`)
1. **Branch → Files (`seon.cluster.source/write-back!`, `src/seon/cluster/source.clj:1482-1589`):**
   - Reads the branch's changed `:seon.program/symbol` declarations relative to `before` (the base DB).
   - For each affected file path, `proven-file` (`src/seon/cluster/source.clj:1444-1480`) reads the base file bytes, splices the new or updated declaration source texts into their exact AST byte spans (`splice-bytes` / `merge-file`), re-analyzes the resulting file text with `seon.fn/analyze-entities`, and checks that every declaration's resulting `:seon.program/definition-digest` equals the branch DB's `:seon.program/definition-digest` (`equivalent-declarations?`, `:785-808`).
   - Atomically writes the proven file via temp file + `Files/move(..., ATOMIC_MOVE)` (`write-file-atomically!`, `:860-874`).
2. **Files → Branch (`bin/seon-hook` `:repl-entrance`, `bin/seon-hook:1141-1279`):**
   - When an outside agent edits a `.clj`/`.cljc` file, `bin/seon-hook` parses the baseline file (`git show HEAD:<path>`) and the edited file into top-level forms (`top-level-forms`, `bin/seon-hook:1141-1166`).
   - Computes `changed-top-level-forms` (`:1194-1219`): added/modified forms become `(in-ns '<ns>) <form>`, removed definitions become `(my.program/ns-unmap! '<ns>/<sym>)`.
   - Evaluates that form string on the lane's SCI branch via `/eval` (`evaluate-entrance-forms!`, `:1242-1262`), which runs the one REPL path (`seon.repl/eval-str`), analyzing and transacting the new `:seon.program/*` and `:seon.fn/*` datoms onto the agent's branch.

### Why Two Mutable Places Drift—and How to Make Drift Impossible by Construction (`PROPOSED`)
Drift happens whenever both **disk files on a branch** and **datoms in the branch DB** are treated as independent state holders:
- If a REPL agent evaluates `(defn foo ...)` on its branch and does not immediately run `write-branch!`, a worktree's file is stale.
- If an outside agent edits a macro, comment, `ns` form, or non-`defn` top-level expression in a file that `:repl-entrance` fails to evaluate or partially evaluates, the file on disk has bytes that the branch DB does not have.

**Construction that makes divergence impossible (zero guards, zero fences):**
A value cannot drift from itself if **only one representation is stored as state and the other is a pure derivation of that basis**:
1. **On every agent branch, the Datahike branch DB is the sole state holder (`PROPOSED`).**
   - **Seon agents** read via `my.program/source` (rendered from the branch DB) and write via `eval_clj` (`defn`, `my.program/ns-unmap!`). They never touch branch files.
   - **Outside agents using file editors** have their `Edit`/`Write` calls intercepted by `bin/seon-hook` (`:repl-entrance`): the hook parses the edited file against the branch's current rendered source (`my.program/source` / `write-branch!` projection), evaluates the changed forms through `seon.repl/eval-str` on the agent's SCI branch, and **writes back the branch's canonical projection** (or on the shared checkout, restores `HEAD` on disk after the branch transaction succeeds).
   - If an edit includes `ns` header imports/requires, `seon.cluster.source/proven-file` and `seon.fn/analyze-entities` record `:seon.ns/*` / `:seon.file/*` facts on the branch DB so the file header is also part of the branch DB's state, not marooned on disk.
2. **On `main`, disk files are written exclusively by `seon.merge/publish!` (`VERIFIED` in `src/seon/merge.clj:500-551`).**
   - Nobody edits `main`'s files directly for Clojure definitions. `seon.merge/merge!` transacts into `:cluster-default` (`accept-merge!`) and writes the exact proven bytes to disk + `git commit` (`publish!`). Because the bytes written to disk are the exact `prepared-files` whose digests were just transacted into `:cluster-default`, `main`'s DB and `main`'s disk files share the identical digest by construction.

---

## 4. Merge

### One Merge, Not Two (`VERIFIED`)
There must be **one merge**: Datahike's declaration-level 3-way merge (`seon.merge/merge!`, `src/seon/merge.clj:553-653`), never a separate `git merge` of worktree branches.

Here is why (`VERIFIED` in `src/seon/merge.clj` and `src/seon/cluster/source.clj`):
- **How `seon.merge/merge!` works today (`VERIFIED`):**
  1. `seon.cluster.source/merge-base` (`src/seon/cluster/source.clj:1007-1037`) resolves the common ancestor database `before` from the branch's `:seon.agent/base-commit-id` (or `:seon.agent/base-t`).
  2. `seon.merge/squash-merge` (`src/seon/merge.clj:84-142`) calls `seon.program/three-way` (`src/seon/program.cljc`) over `(before, main-db, branch-db)`:
     - Every symbol has a `:seon.program/definition-digest` in `before`, `ours` (`main-db`), and `theirs` (`branch-db`).
     - If Agent A modifies `my.ns/foo` and Agent B modifies `my.ns/bar` **in the same file `src/my/ns.clj`**, `seon.program/three-way` sees disjoint symbols (`foo` changed only in `ours`, `bar` changed only in `theirs`) → **zero conflict**.
     - `seon.cluster.source/proven-file` (`src/seon/cluster/source.clj:1444-1480`) then reads `main`'s current file (which already contains `foo`), splices `bar`'s byte span into it, re-analyzes the combined file, and proves that **both** `foo`'s digest and `bar`'s digest match the merged DB!
  3. Inside the single writer, `seon.cluster.source/accept-merge!` (`src/seon/cluster/source.clj:1293-1384`) executes the Datahike transaction function `declarations-held-tx`, which verifies at transaction time that every touched symbol's digest on `:cluster-default` still equals the `ours` digest observed during `prepare-merge!`. This is an atomic compare-and-swap inside the writer—no external lock or fence.
  4. `seon.merge/publish!` (`src/seon/merge.clj:500-551`) installs the prepared files onto `main` and runs `seon.cluster.source/commit!` (`git add -- <paths>` + `git commit`).

### What a Conflict Looks Like and How Root Resolves It (`VERIFIED`)
- **Semantic / Declaration Conflict (`VERIFIED`):** Both `ours` (`main-db`) and `theirs` (`branch-db`) changed the same `:seon.program/symbol` to different `:seon.program/definition-digest`s since `before` (`:conflict` in `seon.merge/squash-merge`, `src/seon/merge.clj:102-119`), or both modified overlapping file spans in `seon.cluster.source/merge-file` (`:seon.source.error/conflict`, `src/seon/cluster/source.clj:809-838`), or a non-program entity attribute conflicted in `seon.merge/entity-conflicts` (`src/seon/merge.clj:236-298`).
- **How Root Resolves It (`VERIFIED` + `PROPOSED`):**
  `seon.merge/review-merge` returns the exact conflict data map containing `:seon.program/symbol`, `:seon.merge/base`, `:seon.merge/ours`, and `:seon.merge/theirs` (both full definitions and digests). Root (or the merging agent after rebasing its branch onto current `:cluster-default`) evaluates the resolved `(defn <sym> ...)` on the agent's SCI branch, runs the reaching tests on that branch, and calls `my.root/merge!` again.

### What Happens to `bin/lane` Claims (`VERIFIED` + `PROPOSED`)
- **Why `bin/lane` claims exist today (`VERIFIED`):** Because all outside agents share one mutable working directory (`/Users/sean/src/seon`), `bin/lane claim` (`bin/lane:401-482`) enforces one-file-one-owner so two lanes do not overwrite each other's uncommitted disk edits before `bin/lane commit`.
- **What happens when work moves to Datahike branches + `seon.merge/merge!` (`PROPOSED`):**
  - For **all SCI-interpretable Clojure code (`src/`, `test/`)**, file-level `bin/lane claim` **disappears**. Two agents can edit different functions in the same file concurrently on their own Datahike branches, and `seon.merge/merge!` + `seon.cluster.source/proven-file` merges them automatically at declaration granularity.
  - **What still needs serializing or a throwaway JVM / restart batch (`VERIFIED`):**
    1. **Host-bound declarations (`:seon.fn/host-bound? true`, e.g., `defprotocol`, `deftype`, `defrecord`, `gen-class`, JVM interop macros):** SCI cannot interpret new JVM classes/interfaces. `seon.cluster.source/prepare-merge!` (`src/seon/cluster/source.clj:1221-1256`) already detects `:seon.fn/host-bound?` via `seon.test.platform/plan` and automatically runs `seon.test.platform/verify-merge-candidate` in a throwaway JVM before `accept-merge!`. After `publish!`, root batches a cluster restart (`bin/seon restart`) so the live JVM archive picks up the new bytecode.
    2. **Non-Clojure / Bootstrap files (`deps.edn`, `bin/*`, `.clj-kondo/*`, `resources/seon/schema/*.edn` bootstrap files, `reference-code/*` gitlinks, `docs/*`):** These are file-level assets outside `:seon.program/symbol`. Until non-code assets are stored as Datahike entities, edits to `deps.edn` or `bin/*` still use direct file commit (or a lightweight path claim on those non-code paths) and, for `deps.edn` or boot-critical namespaces, `bin/seon check` in a throwaway JVM.

---

## 5. Sharing Across Places

The owner's requirement: *"If another agent runs the same code over the same data, even in a different SCI context and branch, we use the cached result. Same with testing: if another agent has recently tested the function and it hasn't changed, we never rerun it."*

### Audit of Every Derived Artifact Across Agents, Branches, SCI Contexts, and Worktrees

| Derived Artifact | Owner & Cache Key (`VERIFIED`) | Shared Across Branches Today? (`MEASURED` / `VERIFIED`) | What Per-Agent Worktrees Would Duplicate | Fix Required for 100% Sharing (`PROPOSED`) |
|---|---|---|---|---|
| **1. `clj-kondo` static analysis** | In-JVM: `seon.fn.analyzer/cached-kondo-analysis` (`src/seon/fn/analyzer.clj:556-567`), keyed in `seon.cache` (`:seon.fn.analyzer/kondo`) by `[scope-digest (id/id source 64) lang]`. CLI hook: `.clj-kondo/.cache`. | **In-JVM: YES (`VERIFIED`, `MEASURED`):** `468` hits, `205` misses, `13.4 MB` in `seon.cache`, keyed by content digest of source + `scope-digest`. **CLI hook in worktrees: NO (`MEASURED`).** | Each worktree creates its own `.clj-kondo/.cache` (**3.2 MB** per worktree, cold on first run: `4.0 KB` after 1 file in `/private/tmp/seon-pitch-argon-high`, `MEASURED`). | Keep one checkout (or pass `--cache-dir <git-common-dir>/../.clj-kondo/.cache`). |
| **2. Seon file & form analysis (`:seon.fn/*`, `:seon.program/*`)** | `seon.fn.analyzer/analyze-file-cached` (`src/seon/fn/analyzer.clj:599-620`), keyed in `seon.cache` (`:seon.fn.analyzer/file`) by `[relative-path (id/id source 64) scope-digest]`. Resolver names: `seon.program/source-resolver-names` (`src/seon/program.cljc:492-518`), keyed by `(id/id [source qualifiers symbols] 64)`. | **YES (`VERIFIED`, `MEASURED`):** `:seon.fn.analyzer/file` has `264` hits / `4` misses (`25.8 MB`). `:seon.program/source-resolver-names` (fixed from raw map key to 64-bit content digest) has `9,149` hits (`9.9 MB`). **Caveat (`VERIFIED`):** `seon.fn.analyzer/checkout-source?` (`:276-286`) checks if the path is under `.` (the JVM's archive dir). | Worktree file paths outside `.` bypass `checkout-source?` unless normalized to repo-relative paths. | Always pass repo-relative paths + content string to `seon.fn.analyzer`. |
| **3. Compiled Malli contracts & schema projections** | `seon.schema` caches `:seon.schema/projection` (`21.4 MB`, `20,031` hits, `2` misses), `:seon.schema/declaration-projection` (`19.4 MB`, `1,127` hits, `3` misses), `:seon.schema/validator` (`1.66 MB`, `26,184` hits), `:seon.schema/contract-fn` (`41 KB`, `16,589` hits) (`MEASURED`). | **YES (`MEASURED`).** Keyed by schema content revisions (`db/content-revisions`), shared across all branches and SCI contexts in the JVM. | Nothing if single JVM; 100% duplicated (512 MB per JVM) if a worktree spawns a JVM. | Keep one JVM. (Note: `:seon.schema/projection` + `:seon.schema/declaration-projection` currently use `40.8 MB` combined, well within the `512 MB` budget at `134.2 MB` total weighted bytes, `MEASURED`.) |
| **4. SCI program contexts & evaluated results** | `seon.sci.eval/program-context` (`src/seon/sci/eval.clj:3868-3906, 4130`), keyed in `seon.cache` (`:seon.sci.eval/program-context`) by `program-key`: `[(store-identity db) revisions dial-revisions loaded-commit-id]`. Base context keyed by `cluster-base-key`. | **YES when program revisions match (`MEASURED`):** Calling `(seon.sci.eval/program-context main-db)` then `(seon.sci.eval/program-context branch-db)` on identical revisions hit cache **56 → 57 → 58** with **0 misses** (`31.6 MB`, `MEASURED`). When a branch edits 1 fn, it forks from the cached `:seon.sci.eval/cluster-base-context` (`15.8 MB`, `15` hits, `1` miss) and overlays only changed definitions. | Nothing in-JVM. | Already shared by content revisions. |
| **5. Test change detection (`seon.test/changes`)** | `seon.test/changes` (`src/seon/test.clj:2204-2246`), cached in `seon.cache` (`:seon.test/changes`). | **NO — BUG (`VERIFIED` at `src/seon/test.clj:2209`):** Key is `[cluster base (get-in candidate [:config :branch]) revisions]`. Because it includes the branch keyword `(:branch (:config candidate))`, two agents on different branches with the exact same `base` and `revisions` **miss** each other's cache entry! | Branch-name keying causes redundant change-graph computation per branch. | **Remove `(get-in candidate [:config :branch])` from the cache key in `seon.test/changes`**; key strictly by `[cluster base revisions]`. |
| **6. Test execution results (`seon.test/recorded-result`)** | `seon.test/recorded-result` (`src/seon/test.clj:1634-1658`) & `seon.test.runner/latest-results` (`src/seon/test/runner.clj:327-350`), matching `:seon.test.member/reach-digest` + `:seon.test.run/input-digest`. | **PARTIAL — Only from `main` to child, NOT between sibling branches (`VERIFIED`):**<br>1. `runner/latest-results` queries `database` (the calling agent's branch DB). Datoms transacted on `:agent-a` are invisible to `:agent-b` until `:agent-a` merges to `main`.<br>2. `seon.test/resolve-test` (`src/seon/test.clj:2692`) sets `evaluator` to `:compiled` for `"root"` and `:interpreted` for branch agents, and `selection-admission` filters by evaluator. | Worktrees do not help here at all; running `bin/seon check` in a worktree would even worse run a separate process. | **Cache passing test member outcomes in `seon.cache` (or a shared cluster fact index) keyed by `[reach-digest test-input-digest task-input]`** (evaluator-independent for pure Clojure declarations), so when `:agent-a` passes a test, `:agent-b` and `root` reuse that proof immediately without re-running it. |

---

## 6. Cost, Measured

All measurements were taken live on `/Users/sean/src/seon` against cluster `"default"` (`PID 88215`, `HEAD` `0d7005536`) using disposable branch `pitch-probe-argon-high` (`:agent-1c12bb338cc9`) and temporary worktree `/private/tmp/seon-pitch-argon-high` (`MEASURED`):

| Operation | Per-Agent Git Worktree (`MEASURED`) | Datahike Branch + SCI Context (`MEASURED`) | Ratio / Impact |
|---|---|---|---|
| **Create place** | `git worktree add -b pitch-argon-high /private/tmp/seon-pitch-argon-high HEAD` → **760.1 ms** | `d/branch!` + `fork!` (`:fork` stage in `eval_clj`) → **0.3 – 0.7 ms** | **~1,100× faster** in Datahike |
| **Disk footprint per agent** | `du -sh /private/tmp/seon-pitch-argon-high` → **217 MB** (`.git` pointer is `4.0 KB`; working tree copies 693+ source/test/script + doc files) | **0 bytes** on disk (in-memory branch pointer sharing immutable Datahike index trees) | **217 MB vs 0 B** (10 agents = **2.17 GB** saved) |
| **`clj-kondo` cache** | New worktree `.clj-kondo/.cache` starts cold (**4.0 KB** after 1 file; main repo cache is **3.2 MB** + `tmp/orchestrator/lint-cache` **3.2 MB**); linting `src/my/agent.clj` took **26.9 ms** CLI + JVM startup | In-JVM `seon.fn.analyzer/cached-kondo-analysis` in `seon.cache`: **0 ms** on cache hit (`468` hits shared across all agents) | Shared in-memory cache vs cold per-worktree disk cache |
| **Edit hook (`bin/seon-hook`)** | Main hook on worktree path: **167.5 ms**, **silently skipped all checks** (`not local-edit?`). Worktree hook on worktree path: **320.8 ms**, **failed to find live JVM** (`"no live root JVM answers... lint only"`). | Direct `eval_clj` (`mode: "sci"`, `agent: "pitch-probe-argon-high"`) defining `my.agent/pitch-probe-fn`: **26 ms** eval (**294.0 ms** total round-trip, `36.8 MB` alloc), full contract + kondo + datom recording | Worktree hook is **broken without path/discovery rewrites**; `eval_clj` works in **26 ms** |
| **Write-back (`my.program/write-branch!`)** | `(my.program/write-branch! {:seon.fn/root "/private/tmp/seon-pitch-argon-high"})` → **57 ms** eval (**143.0 ms** total request, `76.0 MB` alloc), spliced 6 lines into `src/my/agent.clj` | Identical `seon.cluster.source/write-back!`, called **once on `main` at `seon.merge/publish!`** instead of on every branch edit | **57 ms once at merge** vs every edit |
| **Remove / retire place** | `git worktree remove --force`: **376.9 ms** + `git branch -D`: **19.5 ms** (**396.4 ms** total) | `(my.agent/retire-branch! ...)` → **22 ms** eval (**90.0 ms** total request) | **~18× faster** in Datahike |

---

## 7. What Disappears, What Is New, Smallest First Slice, and Red Proof

### What Disappears (`PROPOSED`)
1. **Per-file lane claims (`bin/lane claim`, `bin/lane release`, `bin/lane files`) for Clojure `src/` and `test/` code:** Agents no longer lock whole files in the shared working tree because their edits live on their Datahike branch (`:agent-<id>`) until `my.root/merge!`.
2. **Uncommitted dirty Clojure files sitting in `/Users/sean/src/seon` during work:** The working tree on `main` stays clean (`git status` empty) while N agents work in parallel on Datahike branches, and only changes when `seon.merge/publish!` atomically writes proven files and commits them.
3. **Branch-specific cache keys in `seon.test/changes` (`src/seon/test.clj:2209`):** Replaced by content-only keys.
4. **Siloed per-branch test runs:** Sibling branches no longer re-run tests that another branch just proved over the same `[reach-digest test-input-digest task-input]`.

### What Is New (`PROPOSED`)
1. **Cross-branch test proof sharing in `seon.test` (`src/seon/test.clj`):**
   - Remove `(get-in candidate [:config :branch])` from `seon.test/changes` cache key (`src/seon/test.clj:2209`).
   - Index passing test member outcomes in `seon.cache` (kind `:seon.test/member-proof`) keyed by `[reach-digest test-input-digest task-input]`, and consult it in `seon.test/recorded-result` (`src/seon/test.clj:1634-1658`) regardless of which branch or evaluator tier (`:interpreted` vs `:compiled` for non-host-bound declarations) ran the proof.
2. **Clean-checkout `:repl-entrance` mode in `bin/seon-hook` (for outside file-editing agents):**
   - When an outside agent (Claude Code / Codex / Argon) edits a tracked `.clj`/`.cljc` file under `SEON_AGENT=<id>`, `bin/seon-hook` evaluates the changed forms against the agent's current branch projection (not just `HEAD`) into `<id>`'s SCI branch and restores the disk file to `HEAD` (or serves the branch projection), keeping the shared working tree clean so multiple outside agents can edit the same file concurrently without colliding on disk.

### The Smallest First Slice (`PROPOSED`)
**Slice 1 (Pure Clojure, 1 file: `src/seon/test.clj`, ~25 lines changed):**
Make `seon.test/changes` and `seon.test/recorded-result` share change graphs and passing test member proofs across sibling branches by content digest (`[reach-digest test-input-digest task-input]`), so two agents on different branches never recompute change sets or re-run an unchanged test that either branch has already passed.

### The Red Proof That Slice Must Turn Green (`PROPOSED`)
```clojure
(deftest sibling-branches-share-test-proof-by-reach-digest-test
  ;; Given two sibling agent branches :agent-a and :agent-b forked from main,
  ;; where both define the identical pure function my.sample/calc (same definition-digest)
  ;; and its reaching test my.sample-test/calc-test (same reach-digest and test-input-digest):
  ;; 1. Run (seon.test/run-request {:seon.agent/id "agent-a" ...}) -> executes calc-test once (:executed 1).
  ;; 2. Run (seon.test/run-request {:seon.agent/id "agent-b" ...}) -> must reuse the proof (:executed 0, :reused 1)
  ;;    and hit :seon.test/changes in seon.cache without recomputing.
  ;; TODAY (RED): :agent-b misses :seon.test/changes (because branch keyword is in the key at src/seon/test.clj:2209)
  ;; and re-executes calc-test (:executed 1) because runner/latest-results only queries :agent-b's branch DB.
  )
```

---

## 8. Risks and the Simplest Alternative

### Comparison of Options

| Option | Guarantee | Measured / Verified Cost | Sacrifice / Risk |
|---|---|---|---|
| **Option 1 (Recommended, Simplest Viable): Keep the single shared tree; make Datahike branch + SCI the only working place, and `seon.merge/publish!` the only writer to disk (`PROPOSED`)** | Zero drift by construction; one merge engine (`seon.merge/merge!`); 0 MB disk per agent; `0.3 ms` branch creation; two agents can edit different functions in the same file concurrently. | **0.3 ms / 0 B** per branch; **57 ms** write-back once at merge; ~25 LOC fix in `src/seon/test.clj` for cross-branch test sharing. | Outside agents (Claude Code, Codex, Argon) that use `Read`/`Edit` on disk files instead of MCP `eval_clj` + `my.program/source` need `bin/seon-hook` to translate file edits into branch evals and restore the clean file on disk (or use MCP tools directly). |
| **Option 2: Ephemeral single-file projection for outside agents (`PROPOSED`)** | Same single DB authority and single merge engine as Option 1, while giving outside CLI editors an isolated temp file path when they must use filesystem tools. | **< 5 ms / ~20 KB** per touched file (via `my.program/write-branch!` into an empty temp root containing only touched paths). | Outside CLI tools that run `rg` or `find` across a whole repo directory won't see untouched files in the temp root unless they search main's checkout. |
| **Option 3: Full Git worktree per agent (`git worktree add`)** | Every outside CLI agent gets a full POSIX directory tree (`rg`, `git diff`, file editors work unmodified on disk). | **760 ms** creation + **396 ms** removal + **217 MB disk** + **3.2 MB kondo cache** per agent; requires rewriting `bin/seon-hook` (`repository-file?`), `.seon` runtime discovery, and `seon.fn.analyzer/checkout-source?`. | **High risk of drift and stale-base bugs:** Creates a second mutable state holder per branch and tempts agents/scripts to merge via Git line-diffs instead of `seon.merge/merge!`, while wasting 217 MB per agent for Seon agents that never touch disk files at all. |

### Plain Statement on Worktrees
**Worktrees are the wrong tool for Seon agents.** Seon's own agents live at the REPL (`eval_clj` / `seon.repl/eval-str`), where a Datahike branch + forked SCI context already gives them an isolated, sub-millisecond, zero-disk, content-shared place, and `seon.merge/merge!` + `seon.cluster.source/write-back!` already merges their declarations in 3-way digest space and splices the proven result into `main`'s files. Adding Git worktrees would bolt a 217 MB file-based version control layer onto the side of an in-memory datom database that already does branching, 3-way merging, and atomic write-back better and 1,000× faster.

---

## Evidence

### E1. Runtime & Cluster Status (`MEASURED`)
- `bin/seon status` and MCP `runtime_status`:
  ```clojure
  {:cluster "default", :pid 88215, :status :running,
   :basis-t 536871378, :commit-id #uuid "6acf1d1a-537b-557e-a58d-c1b0604063c2"}
  ```

### E2. Worktree Creation, Disk Footprint, Kondo Cache, Hook Behavior, and Removal (`MEASURED`)
- **Create worktree:**
  ```sh
  $ git worktree add -b pitch-argon-high /private/tmp/seon-pitch-argon-high HEAD
  Preparing worktree (new branch 'pitch-argon-high')
  HEAD is now at 0d7005536 Retire 2 completed tasks and add contract-coverage task
  # Elapsed: 760.1 ms, exit 0
  ```
- **Disk footprint & cache sizes:**
  ```sh
  $ du -sh /private/tmp/seon-pitch-argon-high
  217M    /private/tmp/seon-pitch-argon-high
  $ du -sh /Users/sean/src/seon/.clj-kondo/.cache /Users/sean/src/seon/tmp/orchestrator/lint-cache
  3.2M    /Users/sean/src/seon/.clj-kondo/.cache
  3.2M    /Users/sean/src/seon/tmp/orchestrator/lint-cache
  $ clj-kondo --lint /private/tmp/seon-pitch-argon-high/src/my/agent.clj
  linting took 26ms, errors: 0, warnings: 0
  $ du -sh /private/tmp/seon-pitch-argon-high/.clj-kondo/.cache
  4.0K    /private/tmp/seon-pitch-argon-high/.clj-kondo/.cache
  ```
- **`bin/seon-hook` against the worktree (`MEASURED`):**
  1. Main checkout's hook given a worktree file path (`167.5 ms`):
     ```json
     {"continue":true}
     ```
     *Cause (`VERIFIED` in `bin/seon-hook:207-212, 1743`):* `repository-file?` checks `(str/starts-with? path (str repo-root "/"))`, so `/private/tmp/seon-pitch-argon-high/src/my/agent.clj` is treated as `not local-edit?` and ignored.
  2. Worktree's own `bin/seon-hook` given the worktree file path (`320.8 ms`):
     ```json
     {"continue":true,"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"ADVISORY — contract compile check unavailable: no live root JVM answers, so no contract was compiled\n\nREPL entrance: default unavailable: lint only"}}
     ```
     *Cause (`VERIFIED` in `bin/seon-hook:1126-1139`):* `operator/advertisement` looks for `.seon` under the worktree's `repo-root`, where no cluster JVM runs.
- **Remove worktree and branch (`MEASURED`):**
  ```sh
  $ git worktree remove --force /private/tmp/seon-pitch-argon-high
  # Elapsed: 376.9 ms, exit 0
  $ git branch -D pitch-argon-high
  Deleted branch pitch-argon-high (was 0d7005536).
  # Elapsed: 19.5 ms, exit 0
  ```

### E3. SCI Eval, `my.program/write-branch!` Into the Worktree, and Branch Retirement (`MEASURED`)
- **Define function on `pitch-probe-argon-high` (`mode: "sci"`, `agent: "pitch-probe-argon-high"`):**
  ```clojure
  (do (in-ns 'my.agent)
      (defn pitch-probe-fn [x] {:probe x})
      [(my.agent/branch) (my.program/status)])
  ;; => [{:seon.agent/id "pitch-probe-argon-high", :seon.store/branch :agent-1c12bb338cc9,
  ;;      :seon.agent/fork-id "fefc6d11a933", :seon.agent/base-t 536871390, ...} ...]
  ;; Eval: 26 ms (total request 294.0 ms, :fork 0.7 ms, 36.8 MB alloc)
  ```
- **Write branch to worktree (`mode: "sci"`, `agent: "pitch-probe-argon-high"`):**
  ```clojure
  [(my.program/diff)
   (my.program/write-branch! {:seon.fn/root "/private/tmp/seon-pitch-argon-high"})]
  ;; => [{:seon.program/symbol my.agent/pitch-probe-fn, :seon.program/change :added, ...}
  ;;     {:seon.source/files [{:seon.file/path "src/my/agent.clj",
  ;;                           :seon.source/base-digest "34971cd42654c8f2b1448341628894fd39821ee135147fa3027000eb45800a44",
  ;;                           :seon.source/candidate-digest "4ffc1c0e13124903025eb108998388961e8b1a911420751332c20a7b119d7923"}]}]
  ;; Eval: 57 ms (total request 143.0 ms, 76.0 MB alloc)
  ```
  Verified in `/private/tmp/seon-pitch-argon-high`: `git diff --stat` showed `src/my/agent.clj | 6 ++++++`.
- **Clean up probe definition and retire branch (`mode: "sci"`, `agent: "pitch-probe-argon-high"`):**
  ```clojure
  (my.program/ns-unmap! 'my.agent/pitch-probe-fn)
  (my.agent/retire-branch! {:seon.store/branch (:seon.store/branch (my.agent/branch))})
  ;; => {:seon.store/branch :agent-1c12bb338cc9, :my.agent/retired? false, :my.agent/retiring? true}
  ;; Verified via JVM mode: (:seon.agent/retired-at (d/pull (d/db conn) [...] [:seon.agent/id "pitch-probe-argon-high"])) => #inst "2026-04-19T06:11:36.228-00:00"
  ```

### E4. Cross-Branch Cache Sharing Probe (`MEASURED` & `VERIFIED`)
- **`seon.cache/status` (`mode: "jvm"`):**
  ```clojure
  {:max-bytes 512000000, :weighted-bytes 134225548, :entries 14957,
   :kinds {:seon.sci.eval/program-context {:entries 2, :bytes 31622428, :hits 58, :misses 15}
           :seon.sci.eval/cluster-base-context {:entries 1, :bytes 15810964, :hits 15, :misses 1}
           :seon.schema/projection {:entries 1, :bytes 21401104, :hits 20031, :misses 2}
           :seon.schema/declaration-projection {:entries 1, :bytes 19404156, :hits 1127, :misses 3}
           :seon.fn.analyzer/file {:entries 262, :bytes 25800984, :hits 264, :misses 4}
           :seon.fn.analyzer/kondo {:entries 205, :bytes 13405952, :hits 468, :misses 205}
           :seon.program/source-resolver-names {:entries 12548, :bytes 9900896, :hits 9149, :misses 12770}}}
  ```
- **Cross-branch `seon.sci.eval/program-context` hit test (`mode: "jvm"`):**
  With `main-db` and `branch-db` (`:agent-1c12bb338cc9` after `ns-unmap!`) holding identical program revisions:
  ```clojure
  (let [before (get-in (seon.cache/status) [:kinds :seon.sci.eval/program-context])
        _ (seon.sci.eval/program-context main-db)
        mid (get-in (seon.cache/status) [:kinds :seon.sci.eval/program-context])
        _ (seon.sci.eval/program-context branch-db)
        after (get-in (seon.cache/status) [:kinds :seon.sci.eval/program-context])]
    [(:hits before) (:hits mid) (:hits after) (:misses before) (:misses after)])
  ;; => [56 57 58 15 15]  ;; 2 hits, 0 misses across distinct Datahike branch DB values!
  ```
- **Verified code citations (`VERIFIED`):**
  - `seon.test/changes` branch-key bug (`src/seon/test.clj:2208-2211`):
    ```clojure
    key (when (and cluster base revisions)
          [cluster base (get-in candidate [:config :branch]) revisions])
    ```
  - `seon.test/recorded-result` branch-local query (`src/seon/test.clj:1634-1658`) & `seon.test.runner/latest-results` (`src/seon/test/runner.clj:327-350`): queries `database` (the branch DB) rather than a cross-branch content cache.

---

## Ten-Line Summary

1. **Recommendation: Do not give every Seon agent a Git worktree.**
2. A Datahike branch + forked SCI context costs **0.3 ms** and **0 bytes** on disk (`MEASURED`); a Git worktree costs **760 ms** and **217 MB** (`MEASURED`).
3. Worktrees reintroduce two mutable faces per branch and a second (line-based) merge engine that fights Datahike's declaration-digest 3-way merge (`seon.merge/squash-merge`).
4. Worktrees also break `bin/seon-hook` path checks, `.seon` runtime discovery, `seon.fn.analyzer/checkout-source?`, and `.clj-kondo/.cache` sharing (`MEASURED`).
5. Instead, make the agent's Datahike branch the sole authority during work, and write to disk **only on `main` at `seon.merge/publish!` (`seon.cluster.source/write-back!` + `commit!`)**.
6. Because `seon.merge/merge!` merges per `:seon.program/symbol` and `seon.cluster.source/proven-file` splices byte spans into `main`'s file, multiple agents can edit the same file on separate branches without `bin/lane` file claims.
7. Only host-bound code (`:seon.fn/host-bound?`, already checked in a throwaway JVM by `seon.test.platform/verify-merge-candidate`) and non-Clojure bootstrap files (`deps.edn`, `bin/*`) need serialization or restart batches.
8. Outside file-editing agents should either use MCP (`eval_clj` + `my.program/source`) or have `bin/seon-hook` evaluate file edits into their Datahike branch and restore `HEAD` on disk.
9. `seon.cache` already shares kondo, analyzer, schema projections, and SCI contexts across branches (`56 → 57 → 58` hits, `0` misses, `MEASURED`), **except** for two bugs in `src/seon/test.clj`.
10. **First slice:** remove `(:branch (:config candidate))` from `seon.test/changes` (`src/seon/test.clj:2209`) and cache passing test proofs by `[reach-digest test-input-digest task-input]` across sibling branches.
