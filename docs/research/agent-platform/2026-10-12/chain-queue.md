---
title: "Chain queue: small tasks for chains 4 and later"
type: research
status: complete (lane chain-queue, 2026-10-12); read-only; no test was run for this queue
created: 2026-10-12
---

# Chain queue for chains 4+

**Question.** Chain experiment 3 failed partly because its task (a 185-line scanner with an idempotence property) was far
harder than chain 2's one-line printer fix. Which red proofs, already written and tagged, have a small owner, a fix that
plausibly touches one function, and a file no running lane holds?

**Result.** Four candidates qualify today. Four more are small but held by a lane, and rank behind the free ones until
their lane releases the file. Two of the most-cited friction proofs (`seon.test/select` for H2, `seon.error/reply-value`
for H3) fail the size test and stay out of the top 3.

**Method and limits.**
- Read-only. Owner sizes are top-level form line counts measured with awk on the current source (`src/`), not profile data.
- Held paths are from `bin/lane status <lane>` at the time of writing.
- "Red" is the red recorded in the triage commit `f7c730911` (message) and in chain experiments 2 and 3, and in the
  stored run lists under the lane scratchpad (dated 2026-10-08 to 2026-10-12). Nothing was re-run. Each candidate says
  whether its current failure is observed or only inferred.
- The tagged-test sweep is partial: 274 `:seon/tasks` tags exist in `test/`. Only those named by stored reds, by the
  chain friction tables, or by the triage doc were checked. A full sweep needs a fresh run, which this read-only lane
  did not do.

Authorities: [AGENTS.md](../../../../AGENTS.md) "The goal", [chain experiment 2](chain-experiment-2.md) (G1-G12),
[chain experiment 3](chain-experiment-3.md) (H1-H6), [triage-act patches](triage-act-patches.md).

## Free now (file not held by any lane)

Ranked by difficulty first, then by how small the fix is.

### 1. `seon.render.web/the-web-worker-executor-is-never-shut-down` (difficulty 2)

- **Proof**: `seon.render.web-test/a-stopped-server-has-shut-down-its-worker-executor`, tagged with the task in
  `test/seon/render/web_test.clj:2262`. Task entry in `resources/seon/tasks/seon.render.web.edn`.
- **Owner**: `seon.render.web/stop!` (31 lines) and the return value of `seon.render.web/start!` (72 lines, which builds
  `(Executors/newVirtualThreadPerTaskExecutor)` at line 3539 and passes it as `:worker-pool` at line 3548). The fix
  adds one key to the served descriptor and shuts that key's executor down in `stop!`.
- **Current failure**: observed. The triage commit `f7c730911` records two failures on HEAD: the served descriptor has
  no `:seon.render.web/executor` key, so `(instance? ExecutorService nil)` fails, and the shutdown assertion fails.
- **Why it is small**: a single key on a descriptor and one shutdown call. Both owners are ordinary render-server code,
  not platform.
- **Caveat**: the descriptor's schema is in a `resources/seon/schemas/` file that must also gain the key; locate it
  before writing the card. No lane holds `src/seon/render/web.clj`, `test/seon/render/web_test.clj`, or any schema file
  for it.

### 2. `my.program/a-function-called-through-its-callers-is-reached-by-their-tests` (difficulty 3)

- **Proof**: `my.program-test/a-function-called-through-its-callers-is-reached-by-their-tests`, `test/my/program_test.clj:22`.
  **Not tagged and not ingested**: the task needs a `:seon/tasks` tag and an entry in a `resources/seon/tasks/` file
  (chain 2 G9 names it, but no task entry exists in `resources/` yet).
- **Owner**: `seon.test.runner/tests-reaching` (26 lines, `src/seon/test/runner.clj:1233`), which walks through
  `seon.test.runner/reach-digests` (25 lines, line 1191). The reach walk is the part that must follow a callee through
  its callers.
- **Current failure**: observed on c792b6aaf (chain 2, G9): the assertion for `seon.render.value/prepare` passes; the
  assertion for `seon.render.value/classify` fails because `tests-reaching` answers 0 tests with gap `:outside-graph`.
  Not re-run here.
- **Why it is small**: one walk, two small owners, and the proof already states both expectations.
- **Caveat**: the fix may be a fact on the call graph rather than the walk; decide with the owner's source open.

### 3. `seon.context/a-message-to-the-agent-is-not-in-the-model-context` (difficulty 3)

- **Proof**: `seon.context-test/unread-shows-each-undelivered-item-once-with-its-identity`, tagged at
  `test/seon/context_test.clj:157`. A second copy of the tag sits in `test/seon/cluster/turn_test.clj:2509`, which is held
  by root-drift, so the free copy is the one to use.
- **Owner**: `seon.context/current-state` (50 lines, `src/seon/context.clj:471`) and `seon.context/model-context`
  (34 lines, line 561). The task intent says a message reaches the model nowhere until the block shows unread messages.
- **Current failure**: **inferred, not observed**. Stored runs show a `dangling-id` refusal of this test name in 16 log
  files, all dated 2026-10-08 or earlier, so the state may have changed since. One run is needed to confirm red before
  this is assigned.
- **Why it is small**: the block already holds an `:seon.context/unread` section (the test reads it), so the gap is
  probably a single section missing from `model-context`.

### 4. `my.task/a-send-back-with-positional-arguments-is-answered-with-its-example` (difficulty 4)

- **Proof**: `my.task-test/a-send-back-with-positional-arguments-is-answered-with-its-example`, `test/my/task_test.clj:54`.
  **Not tagged**. Chain 2 G3 filed it; no task entry exists in `resources/`.
- **Owner**: the arity refusal, `seon.instrument/compiled-wrapper` (115 lines, `src/seon/instrument.clj:851`). The refused
  function `my.task/send-back!` is 21 lines (`src/my/task.clj:41`), but the examples belong to the refusal, which is in
  the arming wrapper.
- **Current failure**: observed on chain 2 (G3): the refusal is `:seon.instrument/arity-error` and carries no
  `:seon.instrument/examples`, so the proof's second assertion fails.
- **Why it is not lower**: the owner is 115 lines and arms every contracted function, so it is close to platform code.
  The fix must not change the arming path for any other call.

## Small and held (ranked after release)

These are small friction proofs whose owner file a lane holds today. Each becomes a candidate when the holding lane
commits or releases.

### 5. `seon.error/a-definitions-local-is-answered-with-that-definition` (difficulty 3, held)

- **Proof**: `seon.error-test/a-definitions-local-is-answered-with-that-definition`, `test/seon/error_test.clj:1883`.
  **Not tagged** (chain 3 H3).
- **Owner**: `seon.error/reply-value` (130 lines, `src/seon/error.clj:2056`). The owner is large; the fix must add a
  nearest-definition search that is not already in `nearest-calls`, and split the new search into its own function.
- **Held by** `error-locals` (`src/seon/error.clj`, `test/seon/error_test.clj`, `resources/seon/tasks/seon.error.edn`).
- **Current failure**: observed on chain 3 (H3): the reply's `:seon.error/nearest` names no definition, so the assertion
  "the forms offered name the definition" fails with zero errors.

### 6. `seon.test/a-test-reading-task-tags-reruns-when-a-test-is-tagged` (difficulty 4, held)

- **Proof**: `seon.test.redefined-callee-test/a-test-reading-task-tags-reruns-when-a-test-is-tagged`, tagged at
  `test/seon/test/redefined_callee_test.clj:63`. Task in `resources/seon/tasks/seon.test.edn:259`.
- **Owner**: `seon.test/select` (435 lines, `src/seon/test.clj:814`). Too large as one change; the fix would be a
  key change on one function inside it, which is still a dependency walk.
- **Held by** `root-drift` (`src/seon/test.clj`, `resources/seon/tasks/seon.test.edn`).
- **Current failure**: observed on chain 3 (H2): the red is reused at an unchanged key after a tag is written, so
  `executed-count` is 0 and `passed?` is false.

### 7. `seon.test/a-reused-answer-names-a-recorded-run` (difficulty 3, held, size unmeasured)

- **Proof**: `my.test-check-test/a-reused-check-answers-a-run-id-it-recorded`, tagged at `test/my/test_check_test.clj:125`.
  Task in `resources/seon/tasks/seon.test.edn:198`.
- **Owner**: `seon.test/run` (its size is not measured here; the awk sizing did not find a top-level `defn run` in
  `src/seon/test.clj`, so the owner's name needs checking).
- **Held by** `root-drift` (`src/seon/test.clj`).
- **Current failure**: stored run (`red-after.txt`, `red-before.txt`): the test is red, and the task's intent records the
  minted run id that no transaction stores.

### 8. `seon.context/a-lost-model-call-leaves-a-durable-readable-reason` (difficulty 4, held, size unmeasured)

- **Proof**: `seon.cluster.turn-test/a-lost-model-call-leaves-a-durable-readable-reason`, tagged at
  `test/seon/cluster/turn_test.clj:1467`. Task `seon.context/a-lost-model-call-is-not-in-the-model-context`.
- **Owner**: `seon.context/model-context` (34 lines), as for candidate 3.
- **Held by** `root-drift` (`test/seon/cluster/turn_test.clj`).
- **Current failure**: stored run (`red-after.txt`), not re-run.

## Not in the top 8

- `seon.cluster.agent/a-fresh-agents-environment-costs-one-fork` (tagged `test/seon/cluster/agent_test.clj:2132`) is an
  E3 slice of the immutable-environment plan, not a small change. Its owners are `seon.sci.eval` (held by
  `cache-weights-2`) and `seon.cluster.agent` (held by `m5`).
- The four turn proofs in [triage-act-patches.md](triage-act-patches.md) have no red proof yet, because their test file
  `test/seon/turn_test.clj` is held by root-drift. They need a proof before they can be chained.
- `seon.cluster.db-flow` G4 and G1 proofs and `seon.cluster.agent` G6 and G7 owners are held by `m5`.
- Every `:seon/tasks` test in `seon.request-cost-test`, `seon.sci.eval-test`, `seon.test-test` and
  `seon.cluster.squash-test` is either in a held file or untagged. None was ranked.

## Top 3 for chain 4

1. `seon.render.web/the-web-worker-executor-is-never-shut-down`: proof `seon.render.web-test/a-stopped-server-has-shut-down-its-worker-executor`; owner `stop!` (31 lines) plus `start!`'s return; difficulty 2.
2. `my.program/a-function-called-through-its-callers-is-reached-by-their-tests`: proof `my.program-test/a-function-called-through-its-callers-is-reached-by-their-tests` (tag it first); owner `seon.test.runner/tests-reaching` (26 lines); difficulty 3.
3. `seon.context/a-message-to-the-agent-is-not-in-the-model-context`: proof `seon.context-test/unread-shows-each-undelivered-item-once-with-its-identity`; owner `seon.context/model-context` (34 lines); difficulty 3, pending one confirming run.
