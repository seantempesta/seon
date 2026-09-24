---
type: landing
status: landed
lane: sweep-hand-built-ns-rows
created: 2026-09-24
report: docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md
---

# Lane sweep-hand-built-ns-rows (2026-09-24)

The canonical writers already existed: `seon.test-support/namespace-row` and
`seon.program/declaration-row`. Test fixtures still built namespace rows by hand,
so the writer refused them because `:seon.program/definition-digest` was missing.
One rewrite-clj script, `tmp/sweep-ns-rows/sweep.clj` (run with Babashka, so no
JVM), classified every such map from its structural context. It converted fixture
rows and reported every map it skipped. After it ran, no namespace-row digest
refusal remains in any namespace this lane ran.

## Still broken (not this class, or outside this lane's files)

- **Hand-built fn and test rows** still lack `:seon.program/definition-digest`
  and `:seon.program/analyzed-source-digest`. This is the class root sent
  mid-lane, from lane fix-test-world-refusals items 3-6.
  - `bb tmp/sweep-ns-rows/sweep.clj --census` found 59 transacted
    `{:seon.fn/sym …}` / `{:seon.test/sym …}` rows in 17 files
    (`tmp/sweep-ns-rows/fn-test-census.txt`). 45 carry keys the analyzer does not
    derive, e.g. `:seon.fn/spec`, the `:seon.test/pass-count` family,
    `:seon.fn/calls`, `:seon.test/subject` or `:seon.effect/capability`. Several
    also use string symbols.
  - Converting them is per-test design work, not a mechanical sweep. A spec has
    to move into the source's `:malli/schema`. Hand calls and evidence have to be
    re-derived or dropped. Some rows (`fn_test.clj:1077`) are deliberate refusal
    probes, and some (`fn_test.clj:2209`) are upserts. Following root's
    instruction, it is listed here and not done.
  - Sites in files this lane holds: `bootstrap_test.clj` 174/181/246/253/310/317,
    `classification_test.clj` 85/89, `cluster/turn_test.clj` 1618/1625,
    `db_test.clj` 1841, `fn_test.clj` 1077/2209/2294, `reset_edges_test.clj` 102/356,
    and `test/accretion_test.clj` 131-202/288/329.
  - Files outside this lane: `test/my/program_test.clj`,
    `test/seon/cluster/publication_delta_test.clj`, `test/seon/effect_test.clj`,
    `test/seon/issue/detect_test.clj`, `test/seon/refusal_grammar_test.clj`,
    `test/seon/registry_isolation_test.clj`, `test/seon/test/one_request_test.clj`,
    `test/seon/test_failure_facts_test.clj` (20 rows),
    `test/seon/test_provenance_test.clj` and `test/seon/test_reaching_test.clj`.
- **Hand-built agent rows without `:seon.agent/branch`** were next in line. There
  are 22 such reds after the sweep, for example `problem_routing_test.clj`
  "alpha"/"planner", `resume_artifact_routing_test.clj`,
  `fn_test.clj:435` "call-edges-direct" and "settlement-parity-agent", and
  web_debug "juniper".
  - `test-support/agent-tx` is the owner. It is not a drop-in replacement:
    `creation-tx` seeds the namespace and runs `steward-call`, and these routing
    tests depend on stewardship. Each site needs its own reading.
- **Two private `namespace-row` helpers still hand-stamp digests**:
  `test/seon/turn_test.clj:34` and `test/seon/cluster/turn_test.clj:125`.
  - They call `program/definition-digest` over a name-only row, with no source or
    admission source. They are admitted, so they cause no refusal.
  - Retiring them needs a database argument at 15 call sites, plus `agent-row`
    (`cluster/turn_test.clj:132`), which has no connection. That is a follow-up.
    The script reports both as `:exception/unresolved-name`.
- **`source_reconciliation_test.clj` is rotted beyond this class.** It hand-builds
  fn rows with string `:seon.fn/sym`s and reconciles them. Its namespace rows are
  let-bound `before`/`desired` data and were left alone.
- **Publication gap (existing issue).**
  - Every hook publication of the first pass, and `bin/seon init --dev default --changed
    <my 28 files>`, refused with "Source changed during development adoption."
    The offending paths were `src/seon/turn.clj` and `test/seon/turn_next_work_refusal_test.clj`.
    Another lane committed them at eb14a2f22 and never published them.
    `verify-development-sources!` (`src/seon/cluster.clj:2384`) checks every
    reloaded dependent, so no dependent publication could land.
  - The shell-write hook offers a path only once, so a refused path is never
    retried.
  - Adding those two committed paths to the `--changed` list published in 23 s
    (commit id `6ab561fc…`).
  - Sighting for `docs/seon/issues/a-db-adoption-refuses-while-another-lane-edits-a-dependent.md`.
    The issue file is not this lane's, so the sighting is recorded here.
- **Default died of OOM at 18:08Z** while this lane's first after-run was in its
  `seon.db-test` → `seon.error-test` → `seon.fn-test` stretch. Several ~120 s
  requests were running, alongside other lanes' checks and the hook's
  post-adoption test runs. Root restarted default from the checkout at 18:13Z
  (pid 99265). This lane cannot say whether its load was the cause. The last 9
  namespaces of that run answered "check unavailable" and were re-run in pass 2.
- **Foreign reds throughout.** They include worker-global drift
  (`seon.ai/complete`, `seon.sci.eval/evaluate`), "Cannot interpret
  seon.fn/analyze-forms: Host-bound declaration", a `schema/register!
  :seon.flow/callback-result` refusal (fix-leftovers-1 mid-edit), and
  `:seon.test/program-mismatch` admission refusals during others' publications.
  Total red counts therefore move for reasons unrelated to this sweep.

## Script and classification

`tmp/sweep-ns-rows/sweep.clj` is committed with the helpers `run-all.sh`, one
`bin/test-check default --ns` request per namespace in sequence, and `count.sh`.
Each map whose first key is `:seon.ns/name` holds a quoted symbol, or a local
that an enclosing `let`/`loop` binds to one. The script classifies it by walking
up the rewrite-clj zipper:

- `:converted/namespace-row`: a direct row in transaction data. Transaction data
  is a vector or `into`/`cond->`/`:tx-data`/`:seon.agent/namespace` argument of
  `transacted!`, `transact!`, `admit!` or `transact-fixture!`. The row carries
  only the name, an `(ns NAME)` source and `:agent` admission. It becomes
  `(SUPPORT/namespace-row @CONN name)`, with CONN taken from the transacting call.
- `:converted/declaration-row`: the same position, with more declared keys
  (requires, aliases, steward, doc, context-relevant, a non-`ns` source, `:core`).
  It becomes `(program/declaration-row (db/carried-projection @CONN) MAP :all
  ADMISSION)`, where MAP drops the admission key and gains an `(ns NAME)` source
  when it has none, with the
  `seon.program` require added if missing. The script picks alias `seon-program`
  when `program` is already taken.
- `:converted/analyzer-namespace-row`: the namespace row handed to
  `seon.fn/source-rows`, or bound to a `namespace-row` local after `database`.
  `source-rows` refuses one without its digest.
- Skipped and reported:
  - `:skipped/canonical`: already under `declaration-row`.
  - `:skipped/existing`: an upsert of a name already in default's world,
    exported to `existing-namespaces.edn` with 443 names, e.g. `seon.flow`,
    `my.agents.root`, `my.turn`.
  - `:skipped/fixture-created`: the same top-level form creates it through
    `agent-tx`/`agents-tx`/`creation-tx`.
  - `:skipped/not-transaction`: expected values in `=`, pull results, emission
    maps, analyzer or plan inputs, and `let` data.
  - `:exception/*`: the two private helpers.

The reports are `sweep-report.txt` (pass 1: 46 namespace-row, 12 declaration-row,
2 canonical, 21 existing, 3 fixture-created, 21 not-transaction),
`sweep-report-pass2.txt` (local-bound names: 4 plus 3) and
`sweep-report-pass3.txt` (analyzer inputs: 3).

The script cannot do everything; these were hand-fixed:

- `:seon.ns/requires` as lookup refs was replaced by symbols, in
  `agent_situation_test.clj` and `cluster/turn_test.clj` authored.consumer.
- Two `program/definition-digest` hand stamps in `cluster/agent_test.clj` were
  replaced by `namespace-row`, and its now-unused `seon.program` require was
  removed.

`clj-kondo` reports no errors and no new warnings on the 32 changed files. The
unused-require warnings in classification, fn, drive and resume_artifact_routing
were already there. Every file loaded through publication into default, and
`:seon.test/source` rows carry `namespace-row @`.

Files held by other lanes, and so skipped:

- `test/seon/schema_test.clj` (dirty, schema lanes)
- `test/seon/schema/program_test.clj` and `test/seon/schema/datahike_test.clj`
  (fix-leftovers-1)
- `test/seon/repl_test.clj` and `test/seon/repl_grammar_test.clj`
  (errors-in-agent-responses; no edit was needed in the latter)
- `test/seon/issue_settlement_test.clj` (fix-leftovers-2)

`test/seon/db/declaration_population_test.clj` is a platform member. Its row was
converted, but it was not run, because test-check runs no isolated members.

## Evidence

Every run used `bin/test-check default --ns NS`, one request per namespace, in
sequence. The "ns-digest" column counts red members whose report names a
`#:seon.ns{…}` entity refused for a missing `:seon.program/definition-digest`, or
a `source-rows` namespace-row refusal.

- Before: `tmp/sweep-ns-rows/before/`, pid 70372, HEAD with publication on.
- After: `after2/`, pid 99265, after both publications. Pass 3 was re-run in
  `after3/`.

**31 namespace-digest reds in 14 namespaces went to 0.** The three analyzer-input
refusals that the first pass exposed also went to 0 in `after3`.

| namespace | before (run: pass/fail/err, ns-digest) | after (run: pass/fail/err, ns-digest) |
|---|---|---|
| seon.fn-test | 73bacbc63927: 187/8/34, 8 | 0b803920f1eb: 407/8/14, 0; after3 4fa412ba68e6 283/7/19, 0 (analyze-forms host-bound drift) |
| seon.turn-loop-test | d3d47a29f897: 68/15/15, 5 | 4597639cdb41: 95/17/9, 0 |
| seon.cluster.problem-routing-test | 8bde4b381ae5: 0/0/4, 4 | 80f38bab646d: 0/0/4, 0 (next: agent rows lack `:seon.agent/branch`) |
| seon.render.entity-pairs-test | 9af3bfc87604: 0/0/3, 3 | 5d9f0f3884bd: 49/8/1, 0 |
| seon.turn-test | 52eac5f7381c: 152/9/8, 2 | b8fda4b94c75: 224/12/6, 0 |
| seon.bootstrap-test | e6d483a841ff: 34/4/3, 1 | 711673e34b91: 34/5/7, 0 (3 errors are hand-built fn/test rows lacking the digest, `bootstrap_test.clj:174/181/310/317`) |
| my.program-mutation-test | a1f5391ce69b: 23/6/1, 1 | 04ceb3b9296e: 33/6/0, 0 |
| seon.cluster.instruction-test | 59d329771d25: 12/1/1, 1 | eca99fa46449: 15/1/0, 0 |
| seon.cluster.source-test | 671d395a7f31: 5/0/1, 1 | 4b679ce3e56b: 9/0/0, 0 (passed) |
| seon.context-selection-test | 4119da5a35f4: 23/21/1, 1 | a34a2a4c379d: 29/21/0, 0 |
| seon.contracts-install-test | 02a1b21b6502: 0/0/1, 1 | 36bb07ba6bdf: 0/0/1, 0 |
| seon.data-shapes-test | 0cdae8c9ce9c: 29/1/4, 1 | b12204f813aa: 37/4/3, 0 |
| seon.render.web-test | 702bf6b133f3: 34/0/21, 1 | 4c4f85c15ca0: 63/0/49, 0 (errors are foreign drift) |
| seon.schema-usage-guard-test | 648940d19762: 8/0/11, 1 | 89e4817ed60d: 21/0/8, 0 |
| seon.reset-edges-test | 7a46aa6dc97a: 4/2/0, 0 (timed out at 120 s) | after3 4c8db9d6094f: 23/2/9, 0. The two converted analyzer tests reach their assertions; one exceeds its 5 s bound at 16.2 s, and 7 errors are analyze-forms drift |
| seon.cluster.agent-test | 1c2f6b55701e: 5/0/20, 0 | f4f12b7c2b9e: 37/6/16, 0 |
| seon.sci.documentation-test | 3727f3d0fff8: 32/12/5 | ca6fc59af5fd: 93/13/0 |
| seon.db-test, seon.cluster.agent-namespace-test | request refused (foreign `:seon.flow/callback-result`) | f5af017dd740: 242/6/18, 0; b5914ce896f5: 22/0/3, 0 |

Every other namespace's full rows are in `before/COUNTS` and `after2/COUNTS`,
with the raw text beside them. Summed over all 47 namespaces, reds went from 321
to 328. That total includes the two namespaces the before-run could not run
(24 reds) and foreign drift. It is not a measure of this sweep.

- Timing:
  - The script runs in about 1 s over 47 files in Babashka.
  - The pass-1 publication succeeded in 23.0 s (`init --dev --changed`, 30 paths).
  - Pass 2 published in 4.1 s and pass 3 in 2.6 s through the hook.
  - The full namespace sweep takes about 25 min per pass. Many requests sit at
    the 120 s check bound: cluster.turn, error, fn, help-trial, loop-proof,
    rereads and web. Those are foreign costs, reported by the profile lines.
- Size: 32 test files, +184 / −135, all generated except about 8 hand-fixed
  lines. The script is 357 lines (sweep plus the fn/test census), lane-local under `tmp/`.

## Commits

96f1a0eec (cluster, my.*), 8ea554ccd (render, turn), 87b9d3bcb (the rest), then the script and this note. Released paths: every test file
listed above, plus `tmp/sweep-ns-rows/{sweep.clj,run-all.sh,count.sh}` and this note.

## Verification limits

- Numbers come from a JVM shared with other lanes' publications. Pass
  counts move with foreign drift.
- `seon.db.declaration-population-test` (platform) was not run.
- The before-run has no digest count for db-test or agent-namespace-test. Those
  requests were refused, and the prior lane's after-run showed 0 digest reds
  in both.
