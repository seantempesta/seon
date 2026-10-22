# Health: open problems as a query over facts (design, 2026-10-23)

This is session health's design. It answers root's brief as narrowed by the owner's ruling of 2026-10-23: "simple solutions … reactionary and lazily created … datahike listeners and tx-metadata and any code can just add metadata, errors, whatever is easiest". It serves THE PLAN's loop step 2: an agent finishes in few turns because the obvious breakage it caused is in front of it, and its `seon_done` is refused until it is fixed.

The dashboard question (step 1) is answered in the shared notes folder (`health-dashboard-answer.md`). Root's ruling on it is folded in below as commit 1.

## The finding that makes it small

**Health already is a query.** `seon.problems/report` (`src/seon/problems.clj:162`) is a pure function of main's db (plus root's in-memory error count). The render proc re-runs it whenever main's t moves (`src/seon/render/proc.clj:155`, about 2.7 ms), and `tile-problems` draws it. Nothing polls it.

**And the facts the first checks need are already in the store**, written by the loaders that run on every write:

| Check | The facts it reads (already transacted) |
|---|---|
| dead citation in the wiki | `:seon.section/references` (refs to var entities and sections, `src/seon/docs.clj:38-51`) |
| dead citation in a task plan | `:seon.task/references` (names, `src/seon/task.clj:47`) |
| var with no contract | `:var/kind`, `:var/contract` (`src/seon/program.clj:412`) |
| law: an unnamed catch or clock | `:form/catches`, `:form/timeouts`, `:form/banned` (the compiler's facts, `seon.facts.edn:30-36`), and the references of the `docs/failure-points.md` and `docs/clocks.md` sections |
| a stale section | the tx of `:seon.section/text` against the tx of each cited var's `:var/form` |
| a red test | `:seon.attempt/failures` (already a class in `report`) |

So **the first checks are new classes of `report`: Datalog queries, evaluated when someone looks.** No listener, no stored problem, no open or close step.

## The design

### 1. Two kinds of problem, one read

- **Derived problems** are the classes above. Each is a query over a db, and a problem is open exactly while the query returns it. Fixing the fact (restoring the var, editing the citation) closes it. Nothing is stored, so nothing can go stale and nothing needs closing.
- **Reported problems** are what only an effect can observe: a proc's defect, an effect's failure, a test that ran red outside an attempt. **Any code reports one by transacting it.** The schema exists: the `:seon.error/*` entity per signature, written by `seon.root/problem` → `flush-problems` → `err/flush-tx` (`src/seon/root.clj:67-73`).
  - Who and why come from the tx-meta Seon already puts on every transaction (`seon.db/tx-meta`, `src/seon/db.clj:111`: the runtime, so the agent, task and checkout).
  - The one addition is an optional `:seon.error/subject` (the var, path or section the error is about). It closes the problem lazily, by v1's rule (`~/src/seon/src/seon/problems.clj:235`, `open-faults`): a reported problem whose subject changed after its last occurrence is no longer open. No resolve step and no timer.

Both kinds are read by the same function, `seon.problems/report db`, on whichever db the reader holds: main for the dashboard, the agent's branch for its context, the branch at merge for the gate.

### 2. Who caused it: branch minus base, not a stored owner

A problem an agent **caused** is one that `report` returns on its branch and not on its merge base: `(problems/caused branch-db base-db)`, a set difference of rows keyed by class and subject.
- This is exact for derived problems, needs no attribution column, and costs two `report` calls.
- On main, a derived problem's cause is the last tx that touched its subject. Its tx-meta names the agent and task, read from history (`:seon.config.store/keep-history?` is on).

### 3. Reaction: whoever looks, re-reads on a new t

The owner suggested `d/listen` or the existing tx stream. Neither is needed for these checks:

- **The dashboard** already re-runs `report` when main's t moves. The new classes simply appear in the same unit. After commit 1, that unit is declared on `:seon.problems/report`'s render, grouped by area: docs, laws, contracts, tests, errors, attempts.
- **An agent's context** renders its branch on each turn. It shows `caused` (its own problems) first, each with file:line and the fix the class names.
- **The `seon_done` gate:** `seon.reconcile/done-refusals` (`reconcile.clj:37-63`) gains `:open-problems`, refused while `caused` is non-empty.
- **The merge gate:** `seon.writer/merge-refusal` (`writer.clj:38`) gains the same rule.

What already exists:
- The vendored Datahike supports `d/listen` (`reference-code/datahike/src/datahike/core.cljc:208`), but Seon never calls it, and it is unverified on Jolt.
- The writer's existing commit output is `:main {:t :ops :db}` to the reconciler, through a sliding buffer of one (`system.edn:53`), so intermediate commits can be dropped.

A listener would only matter for a check that must transact something on each write. Derived queries never do, so this design adds none. If one is ever needed, the reconciler's `:main` input is the one stream to extend, not a second listener (open ruling 1).

### 4. Record, never gate evals

Per AGENTS §4 ("Get out of an agent's way"), evals and transacts are never blocked. A problem is recorded, which for derived ones means it is simply true in the db. The agent sees it in its context. Only `seon_done` and the merge are refused while the agent owns an open problem. An agent may break a citation in one eval and fix it three evals later. Nothing stops it in between.

### 5. Triggers: root watches the checkout (owner, 2026-10-23: "seon code detecting and updating, not external scripts")

Superseded: the `bin/seon-changed` Claude Code hook. Root detects changes itself, so every writer is covered by one mechanism: Claude sessions, Seon agents, the owner's editor, and git.

**The event source (measured 2026-10-23).** No watcher is installed (`fswatch` and `watchman` are absent), and the rule is to install nothing system-wide. Two event sources can be reached:
- **FSEvents**, through a ~25-line C program built by `bin/build` beside `native/probe.c` (`native/watch.c`, linking CoreServices).
  - It watches the checkout and its git common dir (`git rev-parse --git-common-dir`, so a commit or merge on any branch or worktree moves `refs/`).
  - It prints each changed path, one per line, as FSEvents reports it: latency 0.0, `kFSEventStreamCreateFlagFileEvents | NoDefer`, on a dispatch queue. There is no timer and no poll.
  - Proven in the scratchpad: a create, a rename and an append under a watched tree were each reported as their path.
  - It exits when root exits: a GCD process source on its parent (`DISPATCH_PROC_EXIT`, kqueue `EVFILT_PROC`), not stdin (AGENTS §9: stdin as a lifecycle).
- **kqueue `EVFILT_VNODE`** through Jolt's FFI on a declared thread. It needs one open fd per watched file and per directory, so ~200 files plus every branch ref, and it must re-open after each atomic save (write-then-rename). Rejected: more code, and fds that grow with the tree.

**The mechanism.**
1. Root starts the watcher as an OS child: the one effect that spawns, and its output lines are facts, as the build's are.
2. One proc, `:watch`, reads the child's stdout on its declared edge reader. That is the one blocking-read exception (§5). On Jolt a process stream read parks a fiber.
3. It drops paths no loader reads (`.git/objects`, `target/`, `runs/`, `notes/`) and sends each batch of changed paths to `my.root/load-paths!` through root's api.
4. `load-paths!` loads each kind once (the wiki, the task files, Seon's source via `seon.root/import-source!`).
5. The commit it makes moves main's t, which every reader already reacts to.
6. A path under the git dir's `refs/` is a commit or merge: it re-imports the source.

The failure point: the watcher cannot start (not built, FSEvents refused), or it dies. It is named in `docs/failure-points.md`, and its edge is the `:watch` proc. Its answer is a recorded `:platform` error that shows on the banner: root goes on without watching, and nothing falls back to a poll.

Open for root: **the batching.** FSEvents coalesces nothing at latency 0, so one editor save can be 2–3 lines. The `:watch` proc loads what arrived while the previous load ran, by its workload (a batch is what the channel holds), so no clock decides.

### 6. The first classes: inputs, cost and owner

| Class (area) | Query (sketch) | Cost per look | Owner |
|---|---|---|---|
| `:dead-citation` (docs) | sections whose `:seon.section/references` reach a var entity with no live `:var/form` (`program/live?`); plus a `path#anchor` with no section, and tasks whose `:seon.task/references` name no live var | O(references): one avet lookup each | `seon.docs` (wiki), `seon.task` (plans) |
| `:uncontracted` (contracts) | live `:var/kind :fn` with no `:var/contract` | O(vars) | `seon.contract` |
| `:unnamed-catch`, `:unnamed-clock` (laws) | live forms with `:form/catches` or `:form/timeouts` whose var no section of `docs/failure-points.md` or `docs/clocks.md` references | O(forms with that fact), small | `seon.check` (laws) |
| `:stale-section` (docs) | a section whose text tx is older than a cited var's `:var/form` tx | O(references) | `seon.docs` |
| `:proof-red` (tests) | `:seon.attempt/failures`, already a class | already counted | `seon.reconcile` |
| reported errors (errors) | `:seon.error/*` with the subject rule | already counted | `seon.error` |

- **Cost proof:** each new class gets a cost proof in the test of its owner namespace: linear in its input attribute's datoms, measured at n = 10 and 100 with a growth bound.
- **The estimate (not measured):** a few hundred sections with a few thousand references → under 5 ms per `report`, beside today's 2.7 ms.
- **If it measures expensive,** v1's prior art applies (`~/src/seon/src/seon/health.clj:96`, `derived`): cache each class keyed by the revisions of the attributes it read. That is content-keyed reuse for a unit measured as expensive (§5 allows it). It is not built until measured.

`:stale-section` will be noisy, because every var edit stales the sections that cite it. It is shown as *attention*, never as a refusal (open ruling 3).

### 7. The dashboard

Commit 1 (root's ruling) declares every tile's input on a named schema carrying `:html` and `:ai`, and the layout uses `:schema`, not `:render`. `event-row` moves to `:seon.render/event` once printer-eval merges, and the frame is named in `docs/render.md#one-printer`.

The health tile is then the `:seon.problems/report` render:
- grouped by area (docs, laws, contracts, tests, errors, attempts);
- each row is the subject's file:line (a link) plus the causing agent or task from tx-meta;
- the agent page shows `caused` for that agent.

The page-units path updates it: the unit's input is `report`'s value, and only a changed value is re-rendered and patched.

## Build plan (after root's gate)

1. **Tiles on schemas** (root's ruling): about 10 units move from `:render` to `:schema`, plus the frame named in docs/render.md. About ±40 lines.
2. **The derived classes** in `seon.problems`:
   - `:dead-citation`, `:uncontracted`, the two law classes, `:stale-section`;
   - `caused`;
   - each class with its deftest (red on a fixture branch that deletes a cited var, green after restoring it) and its cost proof.
   - About +90 lines of src.
3. **Gates:** `:open-problems` in `done-refusals` and `merge-refusal`. About +12 lines.
4. **Context:** the agent's context render shows `caused` first. About +10 lines.
5. **Every writer:** `my.root/load-paths!` (about +20, built), then the `:watch` proc and `native/watch.c` (§5, after root's go on the event source).
6. **Reported-problem closing:** an optional `:seon.error/subject` plus v1's changed-subject rule in `error-row`. About +15 lines.
7. **Deletes:**
   - wiki-live's `docs-check` (about 40 lines, uncommitted on its worktree) becomes `report` on its imported db, filtered to `:docs`;
   - `seon.task/citation-problems` (`task.clj:54`, returned and never stored) is replaced by the class;
   - `bin/laws catch|clocks` (awk over files) is replaced by the two law classes over the compiler's facts. That is §9's "regex over code instead of the compiler's facts" fixed.
   - Also later: `seon.check/contracts`' coverage half, which duplicates `:uncontracted`.

**Size:** about +150 src lines, about −100 to −130 deleted (`docs-check`, `citation-problems`, the `bin/laws` awk modes, the duplicate coverage check). Net about +30. No new schema beyond one optional attribute, no new `:op`, no listener, no scheduler, no registry, no digest framework.

**Conversion cost** (research agent's count, checked against the code):
- About 34 producer sites make errors or problems today.
- **About 25 need no change:** attempt and task facts, and the root error fold, are already facts that `report` reads.
- **3 move:**
  - proc defects held only in root's view (`root.clj:416-424`, newest 20, read by `render/proc.clj:78-102`), about +20/−30;
  - the boot defect (`root.clj:628`);
  - the error fold gains a subject.
- **6 returned-and-dropped results** become classes, which is where the deletes come from: `citation-problems`, `docs-check`, `bin/laws`, the contract coverage, and the refusals, which stay refusals.

## Open rulings for the owner (one at a time; my recommendation first)

1. **No listener for now.**
   - (a) **Recommended:** derived queries plus "whoever looks re-reads on a new t". Zero new mechanism.
   - (b) `d/listen` in root, transacting stored problem facts. It needs a Jolt check, adds a second commit stream beside the reconciler's `:main`, and adds an open/close lifecycle to keep right.
   - (c) Extend the reconciler's `:main` input to carry every commit's ops, not just the latest (it is sliding-1 today), and run checks there. Only if (a) measures too slow.
2. **"Caused" = branch minus base** (recommended), versus a stored `:seon.error/by` on every problem. The set difference is exact for derived problems and needs no column. Reported ones carry the cause in their tx-meta already.
3. **`:stale-section` is attention, not a refusal** (recommended). Otherwise every var edit forces a doc edit before `seon_done`.
4. **The hook goes through the existing `:eval` op** (recommended), not a new `:changed` op. If root prefers an op for the record, it is one row in `seon.server/ops` and becomes the tool `seon_changed` for free.

## Not measured, stated

- The query costs above are estimates, and nothing was timed.
- `d/listen` on Jolt is unverified.
- The research agent reported that `seon.program/reach-index` (named in the brief) does not exist; `seon.program/reach-digests` is the nearest thing (`program.clj:769`). This design does not need it: dead citations and laws read the store, not the reach graph. The test-result reuse (`seon.proofs`, result files in `../test-results`, greens only) stays as it is. A red outside an attempt is a reported problem, written by the test effect's exit.
