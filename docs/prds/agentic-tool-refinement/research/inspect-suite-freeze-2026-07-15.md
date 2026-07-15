---
type: research
status: active
tags: [research, agent, milestone, database]
---

# Inspect suite freeze — 2026-07-15

## Decision

The first development slice is ten deterministic samples. It deliberately
combines Seon-native workflow tasks with four categories of upstream BFCL. The
membership is exact below, but the P0 gate is **not yet closed**: three selected
tool rows still use a legacy Python runner instead of an Inspect `Task`, and the
database and namespace contracts do not yet have disjoint generated milestone
and blind variants. The apparent `endpoint="pod"` milestone and planning tasks
also call `cluster.create_cluster`, which currently returns
`ClusterLeaseUnavailable`; they are not static-ACME-ready. Individual BFCL
static-URL samples are useful smokes, but there is no currently available
seven-sample native suite matching the promised P0 contract.

The minimum correction is to strengthen the existing freeze and task
mechanisms in place:

- expose the already-frozen shell, file, and web rows as Inspect tasks using
  their existing datasets and outcome scorers;
- generate goal-stated Seon workflow variants for database and namespace work
  using the existing structured milestone oracles; and
- record the selected positions for all three tiers in the existing
  `evals/datasets.lock`, without creating another catalog or runner.

## Dependency ledger

- Inspect AI is installed from `reference-code/inspect-ai/` through
  `src-inspect-ai/pyproject.toml` and `src-inspect-ai/uv.lock`. The selected
  source SHA is `05322696a0f784ec399ef6abbafd3d2a250ea9cc`; the installed build
  reports `0.3.247.dev0+g05322696a.d20260715`. The relevant source owners are
  `dataset/_dataset.py` for stable `Sample.id`, `_eval/task/util.py` for exact
  sample-id filtering, and `_eval/task/run.py` for the filtered sample list and
  native log.
- Inspect Evals source is `reference-code/inspect-evals/` at
  `97c99f5f6507fc5d1449fe3247f267d591f64350` (`v0.14.3` in the submodule
  description). `inspect_evals.bfcl.bfcl` owns the upstream dataset and
  `bfcl_scorer`; its dataset commit is
  `dac44e7ac9db5ff26a01ab0c1ec5de5a1e703b7a`. Seon changes only the generation
  bridge in `seon_inspect.bfcl_adapter`; the upstream AST scorer remains the
  verdict owner.
- `seon_inspect.freeze` owns the current seeded draw, tier discipline, pins,
  corpus hashes, blind complement hashes, and canaries. The current
  `evals/datasets.lock` SHA-256 is
  `ff2496fa6fcf2efe592335c4d7b31d728c162de10da08ce49dc85cee72231ee1`; its
  global seed is `20260702`.
- `seon_inspect.catalog.run_bench` is the existing Inspect-to-pod bridge.
  `seon_inspect.tasks.milestone_lift` and
  `seon_inspect.tasks.long_term_planning` are the first-party Inspect task
  examples. `seon_inspect.milestone`, `seon_inspect.planning`, and
  `seon_inspect.tool_scorers` own the structured outcome checks.
- First-party behavioral evidence is in `test_bfcl_adapter.py`,
  `test_milestone.py`, `test_planning.py`, `test_tool_generators.py`, and
  `test_freeze.py`. `test_canary_guard.py` proves that blind canaries do not
  escape `evals/` into agent-visible source or documentation.
- The Python environment lock SHA-256 is
  `34f230184c19b2c03d89eba5cdbc10c6509397a051773fca67a6a33b4de800f4`.
  The installed `openai` version is `2.45.0`; model and provider artifact
  identity remain a P1 requirement rather than part of dataset membership.

## Source findings

### Existing freeze strengths

The current external-source freeze is deterministic and disjoint. It sorts
sample IDs, shuffles under a per-source derivation of global seed `20260702`,
stratifies when configured, and slices development, milestone, and blind
reserve tiers. Development IDs are open. Milestone iteration is rejected and
its representation is aggregate-only. Blind loading is rejected unless the
caller explicitly requests a formal evaluation; that path reconstructs the
complement and verifies its locked SHA-256 before yielding it to the harness.

Generated rows use seed `1` for development, seed `2` for milestone, and a
fresh seed for a formal blind draw. Their byte hashes are already in the lock.
The shell, file, web, and planning generators state outcomes rather than API
instructions. Their scorers inspect filesystem/database results rather than
rewarding plausible prose.

### Established benchmark boundary

BFCL is the only currently integrated upstream task that directly exercises
tool selection and composition through the ordinary pod door while retaining
its upstream scorer. The selected AST subset has four strata:
`simple_python`, `multiple`, `parallel`, and `parallel_multiple`.

GAIA is not silently substituted into this slice. Its upstream task supplies
a Docker sandbox plus bash, Python, and browser tools through Inspect's agent
loop. Tau2 supplies domain environments, user agents, tool calls, and
state-based scorers. `seon_inspect.catalog` correctly rejects these case-2
tasks today because replacing their loop with a text-only pod reply would no
longer be the upstream task. They become candidates only after Seon has one
real Inspect tool/sandbox composition seam; P0 must not fake that seam.

SWE-bench and Terminal-Bench are also excluded from this small context/tool
slice. They are expensive code/environment benchmarks with separate drivers,
not evidence that an ordinary Seon agent can inspect its database, navigate
namespaces, or compose its own functions.

## Frozen development membership

Selection is structural rather than cherry-picked:

- take both existing Seon capability contracts;
- take generator position zero for shell and planning, the Clojure behavioral
  edit position for file editing, and the linked-page navigation position for
  web work; and
- take the first BFCL development member from each of its four stratified
  categories. The frozen draw already places those four categories in the
  first four positions.

| Inspect task | Exact sample ID | Capability | Verdict owner | Ready |
|---|---|---|---|---|
| `milestone_lift(milestone="db", endpoint="pod", epochs=1)` | `db` | schema registration; transact; later database query; aggregate; report | `milestone_scorer` / `check_store_recall` | no static-target adapter/evidence projection |
| `milestone_lift(milestone="namespaces", endpoint="pod", epochs=1)` | `namespaces` | namespace movement; required namespace loading; function definition and in-place update; database sum; composition; report | `milestone_scorer` / `check_ns_movement` | no static-target adapter/evidence projection |
| `long_term_planning(seed=1, n=1, endpoint="pod", epochs=1)` | `long_term_planning-seed1-000` | durable plan; store first batch; restart; retrieve and resume; verify all steps closed; final report | `planning_scorer` | no owned static restart path/evidence projection |
| proposed `shell_use(seed=1, positions=[0])` | `shell_use-seed1-000` | inspect several files; shell aggregation; write exact result | `workspace_scorer` | no Inspect task wrapper |
| proposed `file_edit(seed=1, positions=[3])` | `file_edit-seed1-003` | inspect and edit ClojureScript; parse; behavioral verification | `workspace_scorer` | no Inspect task wrapper |
| proposed `web_fetch(seed=1, positions=[2])` | `web_fetch-seed1-002` | fetch an index; follow the relevant page; extract and report evidence | `fixture_answer_scorer` | no Inspect task wrapper |
| upstream `bfcl_ast` | `parallel_multiple_29` | parallel calls to multiple functions | upstream `bfcl_scorer` / `ast_match` | yes |
| upstream `bfcl_ast` | `multiple_66` | select among multiple candidate functions | upstream `bfcl_scorer` / `ast_match` | yes |
| upstream `bfcl_ast` | `simple_python_189` | one exact function and argument contract | upstream `bfcl_scorer` / `ast_match` | yes |
| upstream `bfcl_ast` | `parallel_3` | several independent calls in one answer | upstream `bfcl_scorer` / `ast_match` | yes |

This is ten samples and covers every P0 category at least once. The `db` row
proves store then retrieve in separate evaluations. The namespace row proves
an in-place update and composition across namespaces. The planning row proves
database-backed work across an actual restart. A result is not a pass merely
because the final answer is right: the structured trajectory checks must also
hold.

The three rows marked not ready are frozen data with real outcome scorers, but
`seon_inspect.tool_rows` currently invokes them through `run_tool_row`, not an
Inspect `Task`. They must not appear in a claimed native `.eval` suite until
that wrapper is replaced by the ordinary Inspect task path.

## Milestone membership

Milestone results are aggregate-only. Exact sample IDs and answers must not be
copied into research prose. Membership is nevertheless deterministic and
auditable by source, draw position, and locked content hash:

| Source | Aggregate membership | Locked identity |
|---|---|---|
| Seon workflow generator | one database row and one namespace row from seed `2`, positions fixed by capability label | missing generator and lock entries |
| `long_term_planning` | seed `2`, position `0` | full generated milestone SHA-256 `baaca635fc8eb1496efec4a0faf38bb94d5c88ce31fbd2840c3c3debe5bdf0c2` |
| `shell_use` | seed `2`, position `0` | full generated milestone SHA-256 `6a4acad36f5bb6282d0a1df4af0018716e370185df979a8c62147e6417ba448d` |
| `file_edit` | seed `2`, position `3` | full generated milestone SHA-256 `97a81ba8ff4cdb4d0995822bb62f543d9b3381f19c1bfe959a805a7b8af0daea` |
| `web_fetch` | seed `2`, position `2` | full generated milestone SHA-256 `812f9e0749f2e3f4b9cfa5e26f253cdc6aec018cf1c44ad36ed0479606cb993f` |
| `bfcl_ast` | first four positions of the locked stratified milestone draw, one per AST category | BFCL source pin plus `evals/datasets.lock`; IDs stay behind aggregate-only runner |

This is ten aggregate samples once the two Seon workflow variants exist. The
fixed `db` and `namespaces` development prompts may continue as regression
invariants, but they do not count as milestone generalization evidence.

The current freeze API can run an entire external milestone split but has no
public tier-safe operation for a fixed positional subset. P0 therefore also
needs a runner-facing `positions` projection that selects inside
`run_split` without returning or logging individual milestone IDs to the
operator. Reaching into `MilestoneSplit._eval_sample_ids()` from a new driver
would defeat the existing discipline.

## Unopened blind membership

Blind membership is a derivation, not a list of published IDs:

- two Seon workflow rows, one database and one namespace, generated from the
  formal blind seed;
- one planning, one shell, one file, and one web row generated from that same
  seed at the positions used by milestone;
- the first member of each BFCL AST stratum from the locked blind complement.

This is ten samples. For BFCL, the current blind reserve has `n=980` and
sample-ID-set SHA-256
`41fcd505122b51e7d6a41ed2a122071a7d26649138e7fe99f032c6eb955eae79`.
The formal loader must reconstruct and verify that complement before selecting
one member per stratum. Neither those IDs nor their prompts belong in source,
documentation, config, or a pre-graduation database render.

For generated rows, mint one 256-bit seed immediately before the formal P7
run. Keep it out of agent-visible source and context until the run begins;
record it in the native `.eval` metadata afterward so the opened blind run is
reproducible. The current `"fresh-per-draw"` lock value expresses the policy
but does not yet provide the formal-run seed owner. That owner should be the
same Inspect suite metadata persisted with the run, not an environment-only
side channel or a second manifest.

The blind set remains unopened in this audit. No blind sample ID, task text,
target, or canary was loaded or copied.

## Score and execution contract

### Floors

The scorecard counts only completed, correctly attributed samples. Cluster
boot failure, bridge failure, timeout, source drift, bundle drift, and missing
evidence are infrastructure failures and invalidate the comparison; they are
never model failures.

For each ten-sample tier:

- overall deterministic success: at least `9/10` (`90%`);
- database/schema/persistence: `2/2`;
- namespace navigation/composition: `1/1`;
- planning/restart/final report: `1/1`;
- filesystem/shell/web: `3/3`; and
- upstream BFCL AST: at least `3/4`, with all four categories reported
  separately.

The category counts overlap intentionally: the database and namespace rows
also contribute to reporting and composition, but each sample counts once in
the overall denominator.

### Budget and ordering

- Execute against the static ACME target serially: one sample, one epoch, one
  open run at a time. Do not use the unfinished per-sample cluster path.
- Resolve limits from the selected database-backed config at run start. The
  current values are 100 batch turns, 300 stream forms, and a 1,800,000 ms
  deadline. Record the resolved facts in `.eval` metadata; do not duplicate
  them as runner constants.
- Use global dataset seed `20260702`. Development generator seed is `1` and
  milestone generator seed is `2`. The formal blind generator seed follows
  the sealed-at-open procedure above.
- Run one epoch for iterative P0/P2 comparisons. Repeated pass-at-k
  experiments are a later measurement over the same membership, not a reason
  to change membership.
- Preserve the native `.eval` as authority. Every sample must retain its task
  name and sample identity, exact prompt/reply bytes, Seon agent and final
  database coordinates, ordered turn bundle, artifact/config identity, model
  identity, resolved limits, score, and failure classification.

### Failure taxonomy

Use the roadmap taxonomy without adding benchmark-specific synonyms:

- tool absent;
- tool not required;
- wrong selection;
- unclear identity;
- unclear description;
- opaque schema;
- unclear arguments;
- overlap;
- misleading envelope;
- unactionable error;
- missing fact;
- plan failure;
- verification failure;
- sandbox/bridge failure;
- model reasoning failure; and
- benchmark/scorer failure.

Infrastructure status is an orthogonal field, not another model-failure
label. A sample with an infrastructure failure receives no capability score.

## Exact gaps before P0 exit

1. **No native Inspect task for three selected rows.** Shell, file, and web
   generation and scorers exist, but `run_tool_row` is a bespoke simulation
   path. Wrap the existing dataset and scorer in one ordinary Inspect task and
   retire the duplicate run path for scored work.
2. **No disjoint workflow generator.** `milestone_lift` has one fixed database
   prompt and one fixed namespace prompt. Add a goal-stated generator whose
   variants preserve the same structured checks: schema registration,
   transaction, later query/aggregate, namespace movement, dependency load,
   in-place function redefinition, composition, and final report.
3. **No tier-safe positional-subset operation.** The small milestone and blind
   sets require selection by frozen draw position and stratum inside the
   runner without exposing held-out IDs. Extend `run_split`; do not access its
   private ID method from another driver.
4. **Generated blind seed has no durable owner.** Resolve it once at formal
   open and persist it in native Inspect metadata. Environment variables alone
   cannot reproduce the run.
5. **P1 identities remain incomplete.** Inspect source is a local mutable path,
   the `reference-code/inspect-ai` checkout has a modified nested UI worktree,
   and provider/model artifact identities are not yet part of this freeze.
   Dataset membership can be selected now, but comparative claims wait for P1.
6. **No serial ACME `.eval` covers all ten members.** The seven ready samples
   can smoke the bridge; the P0 exit run waits for gaps 1–4 so its artifact is
   not a mixture of Inspect and legacy score records.

## Ordered handoff

1. Add the two generated workflow capability labels and their dev/milestone
   hashes to `evals/datasets.lock` through `seon_inspect.freeze`.
2. Convert shell/file/web to ordinary Inspect tasks while reusing their current
   generator and oracle functions; delete the scored legacy path rather than
   maintaining two runners.
3. Teach the existing freeze runner to consume the ten-member positional and
   stratified projections with tier discipline intact.
4. Run the focused freeze, canary, milestone, planning, tool-scorer, and BFCL
   adapter tests.
5. Execute the development set once, serially, against ACME and inspect every
   result before proceeding to P1/P2 comparisons.

No broad suite or cluster lifecycle operation was performed for this audit.

## Mechanical validation

- `seon.dev.markdown/validate-file` reports the document valid with zero
  violations.
- The focused offline freeze and canary gate passes 14 tests. This proves the
  existing deterministic draw, tier boundaries, lock structure, and absence
  of blind canaries outside `evals/`; it does not prove the missing task
  wrappers or a live ACME run.
- `git diff --check` passes for this report.
