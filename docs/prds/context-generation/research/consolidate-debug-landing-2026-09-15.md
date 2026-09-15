---
type: research
status: active
tags: [research, render, web, wave/verification-audit]
---

# Consolidate-debug — 2026-09-15

## Scope and inherited boundary

Read AGENTS.md, audit-1, its eight assigned issue notes, the active plan
README and working edge end to end. Applied data-oriented-clojure, repl,
clojure-testing, and datastar-web-ui skills. A03 is last by owner correction:
`src/seon/render.clj` and `src/seon/cluster/prompt.clj` have concurrent edits.
No default lifecycle, reseed, provider request, or foreign session operation
is authorized by this lane.

At entry default PID 23729 served HTTP 7994 and PREPL 54412. MCP runtime
status returned MapEntry-to-IPersistentMap ClassCastException at RT.java:911;
recorded in the existing development MCP issue. Supported JVM evaluation
`(clojure-version)` returned `1.12.5` in 1 ms. Health is unavailable; JVM
evaluation works. Existing dirty source/tests and untracked files were preserved.

## Dependency ledger

- Clojure prepl evaluates on the host JVM and returns terminal events:
  `reference-code/clojure/src/clj/clojure/core/server.clj:228`.
- The existing session acquires history through `seon.render/acquire-context!`;
  `src/seon/render/transcript.clj`, `render-session`, owns its display.
- Entity inspection is acquired by `debug-page-result`; `debug-response`
  composes inspection with the session. No second evaluation join is needed.
- Canonical tests use `seon.test-support/with-database` and `with-server`;
  the path snapshot gate arms the production contracts.

## Screenshot log

Baseline `tmp/consolidate-debug/before-{debug,agent}-{1440,700}.png`:
all four screenshots inspected; HTTP 200, zero horizontal overflow.
Ledger/header wrap at 700; namespace plan remains full width. Existing
runtime trigger duplication and billing-derived prefix check are assigned
later in this lane. Baseline script is read-only; it creates no turns.

## Findings

A08 in progress: removing the separate Context-now assembly, its feed
target, obsolete selector rules, and obsolete tests; entity inspection uses
the same selected-session component as ordinary debug.

A08 initial fast gate (HEAD `6785c980c` plus owned paths): 15 tests,
23 assertions, 0 failures, 13 errors. Canonical SCI acquisition loads
`seon.turn-test`, whose lines 230 and 343 still reference the deleted
`web/debug-ai-html` and `web/system-turn-html`. This is a required test
migration exposed by this deletion, not attributed to concurrent edits.
The two assertions are outside the assigned paths; scope clarification
requested. No substitute fixture or compatibility helper was added.

Screenshot iteration `a08-final-{debug,agent,inspection}-{1440,700}.png`:
all six inspected, HTTP 200 and zero horizontal overflow. Main/debug pages
retain baseline layout. Inspection's duplicate navigation was removed;
the session's asynchronous completion was awaited before capture. It exposes
the existing saved-prompt capture mismatch for turn 35; this remains A03's
shared acquisition boundary, not a successful prompt reconstruction claim.
The reproducible read-only browser script is
`consolidate_debug_browser_2026_09_15.cjs`. Screenshot scratch was removed
after inspection; all owned shell processes exited.

`bin/css` succeeded. Explicit development adoption reloaded web definitions
but exited 1 with "Source changed during development adoption"; subsequent
edit-hook publication delivered the observed header update. These screenshots
prove loaded UI behavior, not source-publication convergence. The isolated
commit gate and platform gate remain pending the two test migrations; no
finding is marked resolved and no commit has been made.

### A08 authorized test migration

Owner authorized only the two legacy-helper assertion groups in
`test/seon/turn_test.clj`; reread immediately before patching. The saved
evaluation group now renders the session's ledger card and still verifies
namespace, exact source text, absence of clipping markers/read evidence,
and unchanged database basis. The generated-turn group now checks `:none`
on the turn data and every generated source in the session card, retaining
the no-clipping assertion. The commit message records both migrations.

The final fast run at `6785c980c` plus owned paths ran 39 tests / 580
assertions: 5 failures, 0 errors. Both migrated groups pass. Remaining
failures are `saved-history-preserves-shown-text-with-numeric-lookups:315`
and `turn-details-use-the-loop-opening-and-exact-segments:114,124,148,158`:
the saved-history annotation and prompt/strip byte accounting (A03/A09).
Their production functions are unchanged by A08. No expectations were
weakened to accept those mismatches.

A08 source/test/skill/CSS counts before landing: 64 added / 248 removed,
net −184 (documentation and reproducible browser script are separate).

A08 isolated gate: 39 tests, 584 assertions, five failures, zero errors.
Failures are the two web-debug tests named above (A03/A09); the two migrated
turn assertions and entity-inspection route assertions pass. This is not a green
whole-namespace gate. Browser `a08-authorized`: all six captures HTTP 200,
zero horizontal overflow; main/debug at both widths inspected, layout retained.

## A09 — evaluation owner and per-render summaries

A08 commit: `1d5edb65c` (215 added / 249 removed including the evidence
script and landing note; implementation/test/skill/CSS net −184).

A09 removes the ledger membership query. The evaluation owner's optional pull
selector preserves its original two-argument contract, identity, transaction,
order and missing-agent diagnostic. Current acquired history segments supply
byte amounts; per-evaluation outcomes/emissions and per-turn usage/counts
serve the card, strip and problems panel. The empty ledger acquires no prompt.
The additional lines are justified by the additive contracted projection,
read-evidence expansion, interrupted outcome and regression of full/narrow
projection equivalence; there is no second census or persisted summary.

Browser `a09`: six HTTP 200 captures, zero horizontal overflow. Main/debug at
1440 and 700 inspected: retained layout. JVM metadata confirmed `of-agent`
full/narrow arities and ledger request arity loaded in default. Development
publication initially reported concurrent source change, then retried. No
cluster lifecycle operations were used. During this work context-renders
landed `bb008321d` (changed-read provenance), after which the formerly failing
saved-history assertions pass in the latest path-isolated snapshot.

A09 final: `bin/test-fast --paths` on eval/transcript and the changed
page-review/web-debug tests, running page-review, web-debug and web-context:
15 tests / 195 assertions, zero failures/errors. Matching `bin/test --paths`:
15 / 199, zero failures/errors. Source/tests: 129 added / 104 removed, net +25,
justified above. Earlier intermediate runs and failed roots are superseded
by these results. A09 commit is the commit containing this section; its hash
is recorded with the next finding.

## A02 — turn authorship and completion

A09 commit: `209a73fd2`.

The ledger derives `turn-kind` once per row. Bodies and preceding-generated
selection now consume that same provenance as headers/strip/story. A virtual
reply shows its original authored bytes and results, and stops the preceding
system-only group. The accepted closed turn disposition supplies completion;
source spelling is no longer inspected. The regression uses the canonical
virtual-turn fixture and checks exact reply bytes, grouping, and disposition
independence for direct, aliased and nested source spellings.

Source/tests: 49 added / 34 removed (net +15): source +1, strengthened regression
+14. This replaces the duplicated authorship decision and completion scanner;
the extra test lines justify this finding's net addition.

A02 verification: fast 10 tests / 146 assertions; isolated 10 / 150, both green.
Default adoption completed at source `6aa8d57a-ad06-50f4-9ea9-bd78ed94ddc9`.
Browser `a02`: six HTTP 200 captures, zero horizontal overflow; main/debug
at 1440 and 700 inspected, layout retained.
