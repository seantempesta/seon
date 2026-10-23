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

## Every write is checked

Owner, via root, 2026-10-23: "I want automatic testing to be a requirement of the system so we always know what isn't correct." When an agent, or anyone through the watcher, defines or changes a function, Seon reacts on one path, with no poll: the var is checked for a contract, its contract is generatively checked, the tests that reach it run, and its render shows all three. It serves THE PLAN's loop step 2 (turns to green). It shares admission with the test service (`health-test-service-plan.md`: a `:prove` proc, one run per checkout, the latest change winning, results as facts by digest). Design only; nothing is built.

### The trigger: a tx that changes a `:var/form`

A write is the commit that records a var's definition: `seon.program/record-tx` (`program.clj:~395-427`) puts `:var/form`, `:var/kind` and `:var/contract` on the db. The set of changed vars is `(keys (seon.merge/changes (program before) (program after)))` restricted to `:var` keys (`merge.clj:~104`), the one change function the judge and the merge gate already use. Two sources write it:
- **An agent's eval on its branch.** `seon.repl/wrap-record` (`repl.clj:177`) records the form's account; `wrap-instrument` (`repl.clj:262`) runs on each var it defined, today only to arm it and grow the in-process `agent/state :uncontracted` set. It gains one thing: it asks root's `:prove` proc for the var's key (a send, never a wait). An eval is never refused (§4).
- **The watcher on main.** `my.root/load-paths!` (docs/health.md#every-writer-root-loads-what-changed) commits the source load; it asks `:prove` for the changed vars of main before and after, the same `changes` call. A moved ref (a merge) is the same: the vars the merge carried.

Both are the write's own commit, so nothing runs on an idle tree and nothing polls. An agent without a fork it can spare (`lib/fork!` refused for active threads, `agent.clj:405`) records `:seon.proof/result {:status :unchecked}`, shown as attention, never green.

### 1. No contract: an open problem, one mechanism

New class `:uncontracted` (area contracts) in `seon.problems`: a live var with `:var/kind :fn` and no `:var/contract`, row subject the var. It is a query over the facts `seon.problems/dead-citations` and `unnamed-sites` already read, so `caused` (`problems.clj:172`) carries it with no new code, and `done-refusals` (`reconcile.clj:63`) already refuses `:open-problem` for it.

What it replaces, so there is one mechanism (today there are four spellings of one question):
- `seon.merge/uncontracted` (`merge.clj:112`) and `:contract-gap` (`reconcile.clj:59`, `context.clj:180-194`, `writer.clj:44-49`, `turn.clj:64-66`, `:seon.attempt/uncontracted` in `task.clj:161-283`): deleted. `caused` is the branch's head minus its merge base, the same difference `merge/uncontracted` takes over `:ours`, and the writer's `:branch-diff` already returns `:caused` (`writer.clj:191`). The merge gate asks `caused` as the judge does.
- `agent/state :uncontracted` and its warning (`repl.clj:244-261`): the warning is rendered from the open problems on the branch, so no state outside the db.
- `seon.check/contracts`' coverage half (`check.clj:229,248` `:every-fn-contracted`) and `seon.contract/uncontracted` (`contract.clj:275`): the check is `bin/check health uncontracted`. The other halves of `contracts` (armed at boot, wrong arity refused, direct link) stay.
- Reused as is: `seon.contract/function-var?` (the `:fn` rule: not a macro, test or dynamic var) is `program/var-kind` (`program.clj:47-53`), already recorded as `:var/kind`.

Who counts, read from the code, not measured:
- **The drivers** `seon.check` (60 plain `defn`s) and `seon.e2e` (28) carry no contract and are left out in two places that disagree: `platform-nss` drops them from `contracts` (`check.clj:~213`), and `laws` lists them under `:except` (`problems.clj:~119`). The class takes its exceptions from that one `laws`-style data, so they are listed once. Today `merge/uncontracted` would refuse an agent that edited a driver; the class would not.
- **Tests:** a `deftest` is `:test` kind and never counts. Test helpers are plain `defn`s (only 8 of 42 files in `test/seon` exclude `clojure.core/defn`), so they are `:fn` with no contract and today's merge gate flags them in a branch's test file. They should not count (ruling 1).

### 2. A contract: `mg/check` on that var, seeded from its digest

- **The check.** `seon.check/mg-check` (`check.clj:362-402`) today picks vars by `platform-nss` and the `pure-nss`/`pure-fns` allowlists (`check.clj:342-357`), and calls `(mg/check schema @v {:registry contract/registry :num-tests 100 :seed 42})`. On the pinned malli fork the `:seed`/`:num-tests` keys are ignored (the wall clock decides, pure-tests' finding 2, `jolt/pure-tests:notes/pure-tests/FINDINGS.md:14`). The body moves, unchanged except for its inputs, into `seon.prove` as `(check-var v seed)`, one var per call.
- **The seed rule is pure-tests'.** `(seon.proofs/seed key digest)` (`jolt/pure-tests:src/seon/proofs.clj:172`, sha of `:seon.config.proofs/seed-salt` and the parts), passed as `::mg/=>seed` to malli's `jolt/mg-seed` (`reference-code/malli` commit 0762fb6b, pin bump is root's; `generator.cljc:572-576` on today's pin has no seed). The key is `['contract sym]`, the digest the var's (below). No second rule. `mg-check` on pure-tests already seeds `(seon.proofs/seed (symbol v) base)`; this replaces `base` with the var's own digest. The failure keeps `::mg/seed` and the reproduction form (`jolt/pure-tests:src/seon/check.clj:~447-458`). Dependency: pure-tests merges first, with the malli pin.
- **The digest.** `seon.program/reach-digests db inputs roots` takes `{key [var ...]}` and answers each key's digest of the vars' forms, their namespaces' plain forms and everything they reach, contract schema entries included (`program.clj:769-800`). It is on main; `reach-index` (same walk, plus `:process?`) is on `jolt/reach-index` only (`program.clj:851`). Use `reach-index` when it merges: one call with every changed var as a root costs one walk. A callee's change moves the caller's digest, so an unchanged var never re-runs and a changed callee re-checks its callers.
- **The budget.** New config key `:seon.config.proofs/contract-ticks` (engine ticks, Jolt's run-interruptible budget as `:seon.config.agent/test-ticks`, `default.edn:204`; provenance: `with-merge` took 78 s alone). Estimate 2e10 (~5 s at 4e9 ticks/s, `default.edn:192`). Over budget is `{:status :over-budget}`, shown as attention.
- **Which vars generate.** Today a hand allowlist. `reach-index`'s `:process?` (reach of a `:seon.config.source/process-vars`) says exactly "touches a process, a socket or a fork": such a var is `{:status :not-generated}`, not called with random arguments (a generated pid reaching `kill(2)` is a real signal, the reason for `check.clj:342`). `pure-nss`/`pure-fns` are deleted if that rule holds on the 82 contracts; this is unmeasured (ruling 2).
- **The result** is `:seon.proof/result` from the test service, one attribute set for tests, steps and now contracts: `{:digest (identity) :key ['contract sym] :status :pass|:fail|:over-budget|:not-generated|:unchecked :smallest :seed :reproduce :ms :t :commit :change}`. No second schema. A lookup is one AVET seek by digest.
- **The class.** `:contract-fails` (area contracts) in `seon.problems`: the newest result per key whose `:status :fail` and whose digest is still the var's now. A fixed var has a new digest, so it closes with no write, as `:test-red` does. The row names the var, `:smallest` and `:reproduce`.

### 3. Tests: the same plan, the same admission

`seon.agent/test-plan` (`agent.clj:302`) already selects the tests whose reach digest moved against the merge base (`proofs/plan`) and runs them in `run-tests-forked` (`agent.clj:386`), reusing greens by digest. It becomes one caller of the `:prove` request, not its own fork: the request carries the changed vars, `:prove` computes the roots once (the contract keys above, and `test-plan`'s selection), runs the contract checks first (cheap, ~1 s) then the selected in-process tests, and answers each waiter once. A test that reaches a process of its own (`process-tests`, `program.clj:~860`) runs only when named. A burst of saves is one more run; a key whose var's digest moved while the run was going is dropped from it and re-asked.

### 4. The render: a var shows its status

No var render is declared: `resources/seon/schemas/*.edn` holds `:var/*` fact attributes only (`seon.facts.edn:44-51`), and `seon.context/resolve-ref` (`context.clj:~50-60`) builds an ad hoc map for a cited var. The render is a `(value opts)` function (agent-view design, 4.2), declared on a new schema `:seon.var/var` with `:seon.render/html` and `:seon.render/ai`. Its value is a pull, `{:var/name :var/doc :var/contract :status :references :tests}`:
- **status:** `green` (digest-matched pass), `counterexample` with the smallest case and the reproduction form, `needs a schema`, `over budget`, `not generated`, or `unchecked`;
- **references:** from the reach walk, the vars it reaches and the vars that reach it (`:form/refs` avet, `context/found`'s query);
- **tests that reach it:** the tests whose reach contains the var, from the same fold (`reach-fold` can answer it; today `reach-digests` keeps only the digest, so the tests-of-var index is a small addition).

Needed from agent-view: `seon.render/render` taking an identity attribute through `schema-of` (`:var/name` is `{:seon.db/identity true}`, which its design already resolves via `by-identity`), and `resolve-ref` calling `render` instead of building a map. Nothing else; no message sent.

### 5. Where it runs: the process that holds the branch, forked

Running `mg/check` in root loads the agent's code into root, and calls a function with random arguments. Reject. Options: the prove agent on the `tests` branch (holds main and tests, not the agent's branch: it would import each branch's forms and resolve its contracts), or the agent's own fork (`run-tests-forked`'s pattern: the process already holds the var, its contract and its registry on its branch).
**Recommended: the agent's fork for an agent's var, the `tests`-branch prove agent for main's vars (the watcher),** one function (`seon.prove/check-var`) in both. Reason: the code under test is already loaded and armed there, the fork isolates a crash or a spin (the tick budget ends it), the agent is already "free (a real, unrestricted REPL)", and root never loads agent code. Root writes the result (the asker records on its own branch, §5 Effects; the fork returns EDN on a pipe as `run-tests-forked` does).

### Facts and schemas (§7a words)

| Name | Where | Lines |
|---|---|---|
| `:seon.proof/result` (adds `:smallest :seed :reproduce`; key `['contract sym]`) | the test service's schema | +3 edn |
| `:seon.var/var` (render schema) | `resources/seon/schemas/seon.var.edn` | +6 edn |
| `:seon.config.proofs/contract-ticks` | `default.edn`, `seon.config.proofs.edn` | +8 edn |
| problem classes `:uncontracted`, `:contract-fails` | `seon.problems`, with `report` | +30 src |
| `:uncontracted-definition` row | `seon.repl/wrap-instrument` | rewritten, ~0 net |

### Admission

The test service's `:prove` proc, state `{:running {digest #{waiter}} :next request}`: one run per checkout, the latest wins, a digest already running joins its waiters, every waiter answered once. A contract check is a key in the run beside a test, and costs a fork slot for its duration, not a second queue. Config: `:seon.config.proofs/jobs` bounds shards; the contract checks of one request run in one fork, in sorted order.

### Cost

- **Measured (pure-tests baseline, full run, `FINDINGS.md:53,62`):** `mg-check` took 136 s on one shard of ~82 contracts at 100 cases, 78 s of it `seon.template/with-merge` alone. Excluding it, the rest is ~58 s, an estimate of ~0.7 s per var, unmeasured per var.
- **Per write (estimate):** one changed var and its changed callers, ~1 s to the 5 s budget; most are cheap. A change to a widely reached helper re-checks all its callers (the digest says so); a cold boot of main checks every contract once, as `bin/check` does today (~1 minute, parallel, then stored).
- **The queries:** `:uncontracted` is O(vars) by one aevt (a few thousand datoms), `:contract-fails` is O(results), with a cost proof each (n = 10 and 100), like the other classes (estimate: under 2 ms).

### Size

- **Added:** about +110 src lines (`check-var` and the changed-vars to roots step ~45, the two classes ~30, `wrap-instrument`'s ask ~10, the tests-of-var index ~15, `:seon.var/var` render ~15) plus ~17 edn lines.
- **Deleted or replaced, about −130:** `seon.merge/uncontracted` (11) and the `:uncontracted` plumbing in `writer.clj`, `reconcile.clj`, `context.clj`, `turn.clj`, `task.clj` (~25); `agent/state :uncontracted` (~8); `seon.check/mg-check` (~40) and its allowlists (~15); `contracts`' coverage half (~8); `bin/check`'s mg-check shard loop (~12); `pure-nss`/`pure-fns` if ruling 2 holds. Net about −15 src, with the check running on every write instead of once per release build.

### Open rulings for the owner (one at a time; my recommendation first)

1. **Test helpers and the drivers count as uncontracted?**
   - (a) **Recommended:** no. A namespace that defines a `deftest`, and `laws`' driver list, are exempt, because a test helper is not a platform function and `seon.check` is a driver; both sets named once as data.
   - (b) Everything `:fn` counts: an agent contracts its test helpers (more lines in every test file, ~42 files).
2. **Which vars get generated arguments?**
   - (a) **Recommended:** every contracted var whose reach touches none of `:seon.config.source/process-vars` (`reach-index`), the allowlist deleted.
   - (b) Keep `pure-nss`/`pure-fns` for Seon's own vars and mark an agent's with `^:pure`. Safer and a second mechanism.
3. **Does `:contract-fails` refuse `seon_done`?**
   - (a) **Recommended:** yes, for a var the branch caused (a counterexample is a defect the agent can fix in one turn, with the reproduction in hand).
   - (b) Attention only, never a refusal, until it has run on the 82 contracts for a week with no false failure.
4. **A check over its tick budget:** (a) **Recommended:** attention (`:over-budget`), the budget a config key; (b) refuse like a failure.
5. **Where the fork runs main's vars:** the `tests` prove agent (recommended) versus a fork of root (no, it loads main's code into root).
