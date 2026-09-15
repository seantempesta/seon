---
type: research
status: active
tags: [research, agent, database, test]
---

# Rereads 2 — 2026-09-15

## Grounding

Read end to end: AGENTS.md; the assigned empty-change issue; run 5's
`explain_probe_run5_2026_09_15.edn` including its complete `:text`; the
context-renders landing; the plan README and working edge. Read the
turn PRD §§13–15, the since-diff/system-turn/evaluation owners, the REPL
change grammar and `seon.db/diff`. Inspected commits `0dca8534e`,
`0c70a1cb4`, and `1f18b99fc` at their owning seams.

Dependency ledger:

- SCI's reusable context and fork: `reference-code/sci/src/sci/core.cljc:330`.
  `seon.turn/evaluate-sources` uses the real acquired agent context.
- Datahike writer transaction functions receive the current database:
  `reference-code/datahike/src/datahike/db/transaction.cljc:1152`.
  `seon.turn/system-turn` already compares its input history at that seam.
- Read plans and revisions: `seon.db/read-evidence`,
  `read-evidence-current?`, and `read-evidence-changes`; component replacement
  reuses `seon.turn/receipt-read-evidence-tx` and the existing schema codec.
- Editscript's equality and changed paths:
  `reference-code/editscript/src/editscript/diff/quick.cljc:76`.
  `seon.db/diff` and `apply-diff` already reconstruct previous shown values.
- Canonical fixture and real graph: `seon.context-blocks-fixture/submit!`,
  `seon.test-support/with-database`, and the existing loop proof.

## Rule 1: silent evidence refresh

Equality is decided on the previous reconstructed shown value and the new
shown value before recording. Silent evaluations consume no ordinal and
append neither evaluations nor system turns. Their prior evaluation keeps
its exact shown bytes and receives fresh component evidence and read basis.
The existing writer history comparison protects both refresh and append.
Previews make the same emission decision without transacting.

## Rule 1 checkpoint

Rule 1 commit: `60e4e13bb`. Its focused armed regression passed in both
fast and isolated execution. The combined fast and isolated gates each
passed **12 tests / 455 assertions**, with zero failures or errors.

## Rule 2: successful reads only

The existing read-only classifier now requires absence of the evaluation's
error as well as retained read evidence. Existing declaration/write/effect
and turn-dependency exclusions remain at that classifier and planner.
No function names classify documentation or inspections. The regression
uses unresolved input, a real database read followed by division by zero,
a contract refusal, a reader error, and successful documentation.
Documentation remains eligible and follows its program facts.
Focused fast gate: **2 tests / 53 assertions**, zero failures or errors.
The focused isolated gate also passed **2 / 53**. Rule 2 commit: `2795a3f3b`.
The original run's bare `Simplest:` is now rejected as no-form input by
source submission; the unresolved-form regression uses `(Simplest:)`.
The unreadable-form regression uses `(+ 1 #unknown/tag 2)` through the
ordinary source preparation and SCI evaluation path.

## Read-only run 5 measurement

Default was alive at PID 23729, prepl 54412. The requested turn
`59cf96d21042` exists and belongs to Juniper. At basis **536874142**, its
stored history contains **57 evaluations and 8 system turns**.

**Zero whole system turns would have been silent. Four empty-change
evaluations would have been omitted**, all within `e2e89497c3c2`, which
also contains two nonempty changes. The model's report correctly identifies
empty emissions; it does not establish that a whole turn was empty.

| System turn | Evaluations | Empty changes |
|---|---:|---:|
| `aa071259cfd8` | 9 | 0 |
| `88b3faaa6dd0` | 1 | 0 |
| `ed1c18ce290d` | 1 | 0 |
| `d19b85b41bbc` | 1 | 0 |
| `e2e89497c3c2` | 6 | 4 |
| `d3597309628b` | 1 | 0 |
| `594f1130d30b` | 4 | 0 |
| `404bfad994bc` | 1 | 0 |

Reproduction, JVM mode with explicit database custody (no evaluation replay):

```clojure
(let [database (seon.db/as-of @(seon.operator/connection "default") 536874142)
      rows (seon.eval/of-agent database "juniper")
      groups (group-by #(get-in % [:seon.cluster.eval/run :db/id]) rows)]
  (mapv
   (fn [[eid evaluations]]
     (let [turn (seon.db/pull database
                             '[:seon.turn/id :seon.turn.work/situation
                               {:seon.turn/attempts [:db/id]}] eid)
           empty-change? #(= {:seon.repl/changes {}}
                             (seon.repl/shown-value (:seon.eval/shown % "")))]
       {:seon.turn/id (:seon.turn/id turn)
        :seon.probe/system? (and (empty? (:seon.turn/attempts turn))
                                (not= :call (:seon.turn.work/situation turn)))
        :seon.probe/evaluations (count evaluations)
        :seon.probe/empty-changes (count (filter empty-change? evaluations))
        :seon.probe/silent? (every? empty-change? evaluations)}))
   groups))
```

## Rule 3: visible coarseness, derived from rows

The single `session-problems` hunk adds “stale-but-unchanged reads · N”.
It counts original empty-change system emissions and every historical
read-basis assertion after that evaluation's shown text was saved. The
read-basis attribute already retains history; no new schema, counter, or
entity classification is needed. Unavailable history reports an unavailable
check instead of success. The metric links to the owning turns.

The canonical panel regression checks the rendered Hiccup, repeated silent
refreshes of the same evaluations, and a mixed pass with a silent min read
before a changed max read. It independently observes read-basis changes
on retained rows, including additional coarse opening reads, and compares
their accumulated count with the panel. The emitted max read owns ordinal
zero and its actual result handle.

The measured amount writes refresh **three** unchanged reads each: min,
max, and the opening's attribute/entity-count query over the example
attributes. Two such writes record six silent refreshes; the following max
change records two more silent refreshes and one changed emission. The
initial panel fast gate passed **1 test / 23 assertions**; the final gate
also checks the mixed pass's actual result handle.

The read-only default HTTP GET `/agent/juniper/debug` returned **149,222
UTF-8 bytes**, with exactly one `data-problem="stale-but-unchanged"` and
`data-problem-count="17"`. At basis **536874338**, the independent history
query counted **13 silent refreshes**; the four retained empty emissions
make 17. Default had advanced to 61 evaluations during ordinary running
and automatic development adoption; the earlier run-5 measurement above
uses its recorded basis. This proves HTTP output, not browser paint.

A full ledger render through MCP exceeded its 60-second call bound before
the HTTP observation succeeded. Two earlier direct private-function probes
omitted required renderer inputs and failed; the successful HTTP path carries
the actual environment. No production cause is attributed to those incomplete
probe requests.

## Verification and ownership

Final combined fast and isolated gates each passed **14 tests / 504
assertions**, zero failures or errors. Plain `SEON_TEST_WORKERS=1
bin/test --platform` passed **84 tests / 505 assertions**, zero failures or
errors. The loop proof retains **zero generated evaluations / zero added
system bytes across three idle turns**, with plan/message changed responses
of **269 / 452 bytes**. No foreign gate failure blocked this lane.
Default database probes were read-only; no
stop, refork, restart, or reseed was performed. Normal edit-hook adoption
continued. Concurrent edits were present
in agent, function, render, transcript, CSS and unrelated tests/docs. Gates
use HEAD plus only this lane's paths. No foreign session was operated.

The existing unrelated diff work-bound issue remains tracked in
[shown-value diff work bound](../../../seon/issues/shown-value-diff-disables-the-dependency-work-bound.md).
No `seon.db` or `seon.repl` edit was needed for these three rules.

### Commands

Rule 1 used `--paths src/seon/turn.clj test/seon/rereads_test.clj` with
both `bin/test-fast` and `SEON_TEST_WORKERS=1 bin/test`, followed by
`-- seon.rereads-test seon.loop-proof-test seon.turn-continue-test
seon.repl-grammar-test seon.help-trial-test`.

Rule 2 used the same paths, with `-- seon.rereads-test`, through both
fast and isolated gates. Both passed 2 / 53.

Final combined commands (rules 1 and 2 already committed in HEAD):

```sh
bin/test-fast --paths src/seon/render/transcript.clj test/seon/rereads_panel_test.clj -- seon.rereads-test seon.rereads-panel-test seon.loop-proof-test seon.turn-continue-test seon.repl-grammar-test seon.help-trial-test
SEON_TEST_WORKERS=1 bin/test --paths src/seon/render/transcript.clj test/seon/rereads_panel_test.clj -- seon.rereads-test seon.rereads-panel-test seon.loop-proof-test seon.turn-continue-test seon.repl-grammar-test seon.help-trial-test
SEON_TEST_WORKERS=1 bin/test --platform
```

The one-worker isolated gates avoid the already documented shared test-base
filestore race; no fixture, assertion, contract, or test namespace was weakened.

## Cleanup and paths

All lane command sessions exited. The runner removed the fast snapshots,
including failed fixture iterations, and every successful isolated root.
No lane worktree or scratch cluster was created. The initial fixture-setup
wait was diagnosed with the owned JVM's thread dump and that test JVM was
terminated before rerunning with the graph armed. Its snapshot was removed.
Owned HTML and log scratch files were deleted after recording the results.
No foreign process, session, edit, or disposable root was cleaned.

Changed paths:

- `src/seon/turn.clj`
- `src/seon/render/transcript.clj` — one hunk, re-read before editing
- `test/seon/rereads_test.clj`
- `test/seon/rereads_panel_test.clj`
- `docs/prds/context-generation/research/rereads-2-landing-2026-09-15.md`
- The assigned issue, moved to
  `docs/seon/issues/archive/since-diff-appends-rereads-whose-changes-are-empty.md`
  after resolution. The issue index remains the orchestrator's ownership.
