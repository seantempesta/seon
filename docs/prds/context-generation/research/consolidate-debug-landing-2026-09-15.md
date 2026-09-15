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
