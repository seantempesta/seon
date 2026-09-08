---
type: research
status: incomplete
date: 2026-09-08
tags: [research, runtime, sci]
---

# Turn-cut: incomplete landing

## Virtual submission and compaction, 2026-09-08 19:37 UTC

The exactness checkpoint is commit `823569cd3`. The next owned files are
`src/seon/turn.clj`, `resources/seon/schemas/seon.turn.edn`, and
`test/seon/turn_test.clj`. `virtual-turn!` submits fixture source to the
ordinary agent proc; `compact!` retracts that agent's evaluations and refuses
an open turn inside the database writer. The canonical fixture regression
uses real per-agent graphs, the work launcher, SCI, and database events;
there is no evaluator or loop substitution. Commit `f3bd1d58c` passed
`bin/test --paths src/seon/turn.clj resources/seon/schemas/seon.turn.edn
test/seon/turn_test.clj -- seon.turn-test`: two new tests, 24 assertions,
zero failures/errors. There are no inherited tests in this new namespace.
The first snapshot's one failure expected plain EDN instead of the existing
print-node representation; the corrected gate passed both classes.

On the live main-root `default` cluster, MCP without root/cluster arguments
submitted `(+ 1 1)` for the disposable agent `turn-cut-virtual` as
`source:130c5726-7012-4dc3-bc98-7538f90cfabd`. It closed with result 2 and no
provider attempt. Compaction changed its evaluation count from 1 to 0.
The next submission, `(inc 2)`, became
`source:eb8b720d-b797-4e85-b818-9c5900cd6047`, closed with result 3, and its
attempt query returned `[]`. These exercised in-place development adoption.
The calls were `seon.turn/virtual-turn!` with the running instance's
`:seon.cluster.loop/cluster`, `:seon.cluster.agent/routing`, and agent id;
compaction received the explicit connection and agent id.

This checkpoint does **not** claim the three-transaction cut, stored shown
text, persistent private contexts, the system walk, or automatic compaction.
It still passes through the existing source-submission storage path. All
seven deletion rows remain unfinished; their deletion counts are unchanged.

The persistent-context dependency probe used real `sci.core/init`,
`eval-string*`, and `namespace-state` on the live JVM. After `(def x 1)`,
capturing the namespace state, and `(def x 2)`, the old and current Var were
identical and both dereferenced to 2. Therefore comparing two namespace maps
alone cannot detect root changes: the saved map contains mutable Vars.
SCI's `namespace-state`/`install-namespace-state!` remain the resolver-state
owners (`reference-code/sci/src/sci/core.cljc:751`); program changes must be
observed at their publication boundary or from their database transactions,
not inferred from equality of those maps.

## Read-evidence exactness landed, 2026-09-08 18:59 UTC

The owned-path gate passed: `seon.read-evidence-test seon.db-test`, 42 tests,
277 assertions, zero failures/errors. Two new real-SCI tests (14 assertions)
prove both recipient directions with replay requests/results removed from
the retained evidence. The 40 inherited database tests also pass.

`seon.db` now retains bound E/A/V index patterns for simple datom queries,
finite explicit pulls (including nested selected entities), and datoms reads.
Its existing validity function matches historical datoms since the captured
basis against those patterns. General query/pull constructs and databases
without the necessary history keep their existing conservative/replay path;
this commit does not claim exact patterns for every Datalog construct.
Message content retains history so retractions and historical inbox reads
remain observable. The existing historical inbox regression now expects its
actual prior content, rather than a nil that violated its contract.

Before: the inbox query retained only `#{:seon.cluster.message/to}`.
After: it also retains the bound recipient value; each selected message pull
retains its entity plus attribute. On live default, MCP JVM evaluation observed
the recipient pattern for Juniper and actual SCI evaluation returned its three
messages. Live physical message history still needs a refork; the old database
retains its former no-history facet. No provider was called.

Gate paths: `src/seon/db.clj`, `resources/seon/schemas/seon.db.edn`,
`resources/seon/schemas/seon.cluster.message.edn`, `test/seon/db_test.clj`,
`test/seon/read_evidence_test.clj`. Log: `tmp/turn-cut-exactness-test-4.log`.
Earlier interrupted/red attempts are superseded by this completed gate.
All seven original deletion rows and the §16 turn functions remain pending;
their reference-count table below is unchanged. Datoms per turn are not yet
measured. The latest owner gate is the owned-path explicit namespace gate.

The zero-argument inbox call is ambiguous between its two derived arities on
live SCI. The exactness proof uses its declared full argument list. Generated
source must use an unambiguous declared call shape.

## Latest attempt: §15 and read-evidence exactness

Read §15 and the current §14 addendum in the working tree. The latest ID ruling
is a stable 12-hex `seon.schema/sha-256` digest over branch identity, turn ID,
and ordinal, with handle `result/e<12hex>`. None of the intervening random-ID
proposals was implemented. Results are actual objects in memory and shown text
in the database; automatic compaction retracts evaluations past the configured
token bound. These changes remain unimplemented.

The first gate is read-evidence exactness. The live default cluster's
`my.message/inbox` for Juniper returned three messages and five captured reads:
recipient lookup, incoming-message lookup, and three pulls. All five dependency
plans retained attribute sets only. In particular, the incoming-message plan
held `#{:seon.cluster.message/to}` without Juniper's bound recipient entity.
`seon.db/read-evidence-current?` currently uses attribute revisions followed by
semantic read replay; it does not perform the required index-pattern since check.
The reproducible read-only [probe](turn_cut_read_evidence_probe_2026_09_08.clj)
records this observation. Neither direction's required real-SCI regression has
passed; this is a structural finding, not an exactness proof.

A prospective evidence implementation was removed after the edit hook hit an
in-flight plan-renderer arity change. Four tests call the new one-argument
`my.plan/render-plan-html` with two arguments; publication refuses before
adoption. The [exact boundary](../../../seon/issues/plan-renderer-arity-change-blocks-development-publication.md)
records all four locations. No prospective database/schema edits remain, and
the other lane's files were untouched. No new deletion row or reference-count
change is claimed. The current attempt adds only this evidence, probe, and issue.
No provider was called by the probe. No lane-owned background shell remains.

No requested deletion row is complete. The resumed lane repaired the
missing-contract boundary, then stopped under the foreign-lane gate rule:
the runner's empty `snapshot_paths` expansion fails before tests start.
See [the runner boundary](../../../seon/issues/test-runner-empty-snapshot-paths-refuses-the-default-gate.md).

## Resumed attempt, after the owner identified the probe row

Prerequisite commit `5a807c144` contains the case-count repair described below.
The next prerequisite removes uncontracted proposed program rows at
`gate-function-install`; derives the required note in `seon.repl`; and fixes
completion admission to derive its schema projection from its held database.
The live fault identified `loop.clj:553` and a nil `schema/current-projection`.

A new canonical-database, real-SCI, ordinary-proc regression submits an
uncontracted function and observes closure, no program row, no base Var, and
the REPL note. The existing REPL regression covers contracted and uncontracted
definitions. No loop is replaced. The committed live probe now checks both
uncontracted rejection and shared-function propagation.

The first resumed hook converged to `6aa04b07-4b38-5bff-b5d8-622ec7b70cc8`;
MCP confirmed default recorded that exact published commit. Later edits met
concurrent-source advisories; retries were interrupted during reconciliation
by steering. Final convergence after the complete repair remains unproven.
Juniper reseeding refused a conflicting upsert: `step-render-plan` resolved
to both 34744 and 35252. Repeat installation needs repair in the bootstrap move.

The explicit three-namespace gate reached the new proc test's END event in
26,137 ms, but TERM interrupted it before a tally. The next invocation failed
before test startup with `snapshot_paths[@]: unbound variable`. Current runner
commit `cd42689b2` retains that expansion, independently reproduced under
`/bin/bash -u`. Bare and platform gates are blocked by the same default path.
New/inherited failure tallies remain unknown; no green count is claimed.

Read the working-tree PRD §14 and its addendum in full. They supersede the
earlier fresh-fork design below: one live agent context receives base changes;
system turns append changed read forms across the whole transcript, excluding
writes and effects. All seven rows and these latest requirements remain
unfinished. Shared-function propagation, byte identity, and datoms per turn
remain unmeasured. No schema reset-request lines apply.

Current touched paths: `src/seon/cluster/loop.clj`, `src/seon/repl.clj`,
`test/seon/cluster/agent_test.clj`, `test/seon/repl_test.clj`, the live probe,
this note, and the issue notes. All earlier REPL changes and concurrent
operator/config/renderer/runner edits were preserved. The literal reference
counts in the table below were remeasured and are unchanged.

Process-table checks found no lane-owned test launcher, worker, adoption
command, or background shell remaining. Other lanes' processes were identified
by output files and left alone. Interrupted test evidence remains in `tmp/`.
The following sections retain the earlier attempt's evidence.

## Authority and amendments

Read the supplied AGENTS.md and the turn-loop PRD r11 end to end, including
the later §12 amendments; read run-loop-unpacked §5.6 and the listened-
attributes landing note. Applied the data-oriented-clojure, datahike,
clojure-testing, and repl skills. The final location ruling is main-root
`default`. No reset-request lines apply. Private data and atoms must be
objects carried by agent proc state; each turn forks the current shared
base. All verification must use virtual replies, without a provider request.

## What was changed

The shared-function probe exposed an existing installation defect:
`gate-function-install` reads the case count from a handle which does not
carry it. The database config has 25; the handle has no value. The reader
now uses configuration facts from the held database value. A regression
configures three cases and asserts the installation, closure, and recorded
case count with the handle copy removed. This is a prerequisite repair,
not completion of row 5. Its [issue remains open](../../../seon/issues/function-install-case-count-is-read-from-an-absent-handle-key.md)
until verification completes.

The reproducible [ordinary-proc probe](turn_cut_probe_2026_09_08.clj) uses
two new agents, source submissions, the real SCI evaluator and database,
and a fixed reply at `seon.ai/complete`. Its first source turn opened but
did not close within the 20-second event bound. The cross-agent call was
therefore not reached. The answer to whether shared installation works
today is **not proven**, not yes and not no.

## Measured observations

Both MCP tools called without root or cluster arguments selected main-root
`default`; `eval_clj` reported only `default` in running instances. No MCP
default repair was needed on the observed system.

A disposable SCI dependency probe returned `{:a 2 :b false :base false}`:
an intern in one fork was absent from its sibling and base. This proves
only SCI fork behavior, not private-state persistence through turns.

The first baseline gate, five explicit namespaces, was interrupted before a
tally was available. The later `bin/test seon.cluster.turn-test` captured
the working tree, selected 60 tests, and was stopped after the shared-state
boundary was identified. No green tally is claimed. Bare and platform gates
remain unrun for the repair. New versus inherited test failures are unknown;
one pre-edit installation fault was reproduced live. Datoms per completed
virtual turn and byte identity remain unmeasured.

## Literal reference inventory

Dated inventory before the deletion work; counts are literal occurrences
in `.clj`, `.cljc`, and `.edn` files, not alias-expanded semantic references.
No deletion row changed these counts.

| literal | src before/after | resources before/after | test before/after |
|---|---:|---:|---:|
| `:seon.cluster.work/situation` | 25/25 | 10/10 | 56/56 |
| `:seon.cluster.run/process` | 77/77 | 29/29 | 109/109 |
| `:seon.cluster.agent/run` | 23/23 | 2/2 | 32/32 |
| `:seon.cluster.run/trigger` | 14/14 | 8/8 | 37/37 |
| `:seon.cluster.run/opening-commit-id` | 6/6 | 4/4 | 3/3 |
| `:seon.cluster.run/plan-digest` | 11/11 | 6/6 | 28/28 |
| `:seon.cluster.run/undisposed-at` | 5/5 | 2/2 | 1/1 |
| `:seon.context.capture` | 13/13 | 11/11 | 21/21 |
| `:seon.context.contribution` | 69/69 | 35/35 | 54/54 |
| `:seon.def/` | 77/77 | 39/39 | 113/113 |

## Remaining work

All seven cuts, their real-harness regressions, the three-write proof,
private data/atom/handle isolation, shared installation, boot interruption,
byte identity, bootstrap's configured Juniper fixture, and the final rename
remain unfinished. The function-capture reconstruction question was raised
under AGENTS.md §2.5: deleting stored SCI roots affects cold acquisition of
installed closures as well as private data. No capture policy was changed.

Unrelated concurrent edits to `src/seon/repl.clj`, its schema, and its test
were preserved. The lane's source changes are limited to
`src/seon/cluster/loop.clj` and `test/seon/cluster/turn_test.clj`, plus this
note, its probe, and the two linked issues.

The explicit gate exited 143 after TERM; its coordinator required the
runner's reap backstop and exited 137. The new regression reached its END
event in 4,898 ms, but no completed tally is available and that event alone
is not a pass claim. No lane-owned background shell or JVM remains. The
obsolete scratch root and both interrupted isolated test roots were removed
after the process-table and lane-status checks found no holders.
