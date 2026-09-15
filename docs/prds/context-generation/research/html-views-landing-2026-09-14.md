---
type: research
status: active
tags: [render, web]
---

# HTML views — 2026-09-14

## Boundary and grounding

Owned render pairs only; AI functions and their helpers remain unchanged.
Default PID 23557, PREPL 49971 answered status and a JVM clock probe.
The JVM `seon.plan/plan` read for Juniper timed out at 10,000 ms; the
browser independently rendered its seven steps. This repeats the existing
[MCP issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md),
which is concurrently edited and therefore preserved. No lifecycle or agent
message operation was performed.

Read AGENTS.md, Datastar skill and design principles, UI architecture,
block placement, Hiccup serializer, and input.css/blocks.css end to end.
Read each owned HTML pair and its AI twin before editing that pair.
Skills: data-oriented-clojure, repl, clojure-testing, datastar-web-ui.

Dependency ledger: existing Hiccup serializer (`src/seon/render/hiccup.clj`)
escapes text and attributes and accepts lazy child sequences; surface ids
belong to `src/seon/render/block.clj`. Existing plan derivation
(`src/seon/plan.clj`, `derived-steps`) supplies state and completion transaction
time. Java Date → Instant → ZonedDateTime supplies human timestamps, as in
the existing runtime renderer. Tailwind consumes `blocks.css` through the
existing import; no new token or safelist entry is required.

Playwright was absent from this shell's NODE_PATH. The installed copy at
`/Users/sean/.npm/_npx/e41f203b7505f1fb/node_modules` works with Chrome.

## Visual evidence method

Every live capture uses Chrome at 1440×900 and 700×900. The committed
`test/seon/html_views_browser.cjs` captures the actual Juniper namespace page;
`test/seon/html_views_fixture_browser.cjs` photographs HTML emitted by the
canonical armed fixture, clearly labelled as fixture output. The latter
covers pairs absent from the live page without writing demonstration facts
into default. `test/seon/html_views_inspect.cjs` records root overflow and
expanded settings. Screenshots remain under the requested `tmp/html-views/`.

Fixture baselines use HEAD without this lane's source edits and the pre-lane
CSS from `a1f556dc87822009d84fd5604a14dc89ff4d7ea8`. The new assertions fail
against old output (9 tests, 156 assertions, 23 failures, 1 error), which is
baseline evidence, not a gate result. Old note rendering refused acquired
references before its screenshot could be emitted; the live baseline records
that raw about-reference. Final fixtures show zero document overflow in all
38 captures (19 HTML class groups, both viewport sizes; identity/id share a group).

Live placement remains an explicit boundary: at 1440 pixels the shell gives
empty Faults the broad column and plan/settings a 261-pixel, 160-pixel-high
scrolling rail. At 700 pixels owned pairs wrap within 522 pixels. Root's
949-pixel document width is caused by `table.seon-runtime-turns` (860 pixels,
right edge 949), measured in `inspect.log`. The existing
[layout issue](../../../seon/issues/namespace-layout-confines-most-content-to-scroll-boxes.md)
now includes these observations. No shell/history ownership was crossed.

The per-block rows below name image prefixes; each prefix has `-1440.png`
and `-700.png`. Both widths were opened and visually inspected. `fixture-`
means fixture evidence; unprefixed images are the live main page.

## Plan

Objective title; ordered steps with dot + word status, completion time and dependency titles. Current work exposes its done-when; completed criteria are quieter, expandable details. No write examples or lookup-reference teaching.

Screenshot log: `fixture-my-plan-before → fixture-my-plan-verified; plan-before → plan-verified`.

Observed defects → change: Old cards hid the criteria, exposed ids and labelled completed dependencies as waiting. The first revision also gave done criteria too much visual weight; the final revision collapses only completed criteria. Desktop placement is still constrained by the shell.

## Settings

One table, overrides first and marked. Cluster defaults are collapsed and counted; absent settings are omitted and counted. Milliseconds become seconds, integer values use separators, model names have no EDN quotes.

Screenshot log: `fixture-seon-agent-settings-before → fixture-seon-agent-settings-verified; settings-2 → settings-verified`.

Observed defects → change: The original long table repeated Not set and buried overrides. A second inspection caught the misleading ms label beside a seconds value; the label now follows the displayed unit. Defaults expose native details with preserved open state.

## Inbox and messages

Message cards show sender → recipient, relative time with exact local hover text, wrapped content, and unread/handled state from the inbox edge. Reply forms remain solely on the AI side.

Screenshot log: `fixture-seon-message-entry-before → fixture-seon-message-entry-verified; fixture-seon-message-inbox-before → fixture-seon-message-inbox-verified; messages-2 and inbox-2`.

Observed defects → change: Raw recipient refs, ISO timestamps and reply teaching crowded the original output. New cards distinguish unread from handled; unresolved identities use readable text.

## Notes

Plain note title, wrapped content, plan-item title links, and time from the content transaction. Pulled refs are accepted through the existing render-unit contract.

Screenshot log: `plan-before → notes-final; fixture-my-note-entry-verified and fixture-my-notes-verified`.

Observed defects → change: The live note originally exposed {:db/id …}. Its actual about target is a transaction, so it now says the recorded transaction. The canonical fixture proves a plan-item about link labelled Prepare. No invented note title or timestamp attribute.

## Help

The exact help lines appear as a readable list; the Clojure reader identifies inline parenthesized forms for code spans. No width-based line splitting. Only render-help-html changed in bootstrap.

Screenshot log: `fixture-seon-help-before → fixture-seon-help-verified; help-2 records absence from Juniper`.

Observed defects → change: The original list used inconsistent typography and left forms undifferentiated. The final list uses the mono stack, signal markers and inline code color. Help is absent from the current main-page walk; fixture proof is not claimed as live pair paint.

## Identity

Agent, namespace and steward each get one readable line. Creation names the opening turn; partial identity handling remains in its existing owner.

Screenshot log: `fixture-seon-agent-identity-entry-before → fixture-seon-agent-identity-entry-verified; fixture-seon-agent-creation-entry-before → fixture-seon-agent-creation-entry-verified; identity-2`.

Observed defects → change: The old layout lacked consistent row alignment. All values and links now wrap; no raw agent lookup reference is shown.

## Faults

Fault kind, message, local time and turn reference are readable; evidence remains linked. Fault collection uses the same child renderer.

Screenshot log: `fixture-seon-error-entry-before → fixture-seon-error-entry-verified; fixture-seon-error-faults-before → fixture-seon-error-faults-verified; faults-2`.

Observed defects → change: Raw kind punctuation and lookup refs are removed. Live Juniper has zero routed faults; nonempty content is proven in the canonical fixture. Open/resolved cannot be derived: no resolution attribute or transition exists. See the recorded schema gap.

## Transactions and rejections

Committed changes are listed by attribute and readable value, without tempid or datom dumps. Dates are formatted, entity refs use identities, and transaction metadata is expandable. Rejection conflicts use the same value formatting.

Screenshot log: `fixture-seon-db-transaction-entry-before → fixture-seon-db-transaction-entry-verified; fixture-seon-db-rejection-entry-before → fixture-seon-db-rejection-entry-verified; transactions-2 records absence`.

Observed defects → change: The first screenshot found empty transaction/commit rows: raw database values require the existing database-value-identity projection. The final version uses that owner and hides optional metadata behind details. No transaction block is present in the live walk.

## Maintenance

Each supplied task shows its operation and derived dot + word status, time and error message. Empty reports explicitly say no tasks are recorded.

Screenshot log: `fixture-seon-maintenance-entry-before → fixture-seon-maintenance-entry-verified; maintenance-2 records absence`.

Observed defects → change: AI summary prose and diagnostic identifiers no longer determine HTML layout. A no-run specimen exposed a nil timestamp assumption; the renderer now asks for a time only when a result exists. Current root and Juniper have no maintenance pair to inspect live.

## Namespace bindings

Aliases, refers and imports retain their local name, direction and target with wrapping. Duplicate source-form disclosures are removed.

Screenshot log: `fixture-seon-namespace-alias-entry-before → fixture-seon-namespace-alias-entry-verified; corresponding refer/import prefixes; namespace-2 records absence`.

Observed defects → change: The old output repeated each binding as both a readable link and a libspec/import dump. The compact pair retains the actual binding. These standalone pairs are absent from the current Juniper walk.
## Final verification and live adoption

- `bin/test-fast --paths <owned paths> -- seon.html-views-test`: **10 tests,
  171 assertions, zero failures/errors** (`tmp/html-views/final-pairs-fast.log`).
  Twenty per-pair AI golden strings match exactly. Each HTML pair asserts
  expected visible text and rejects `#inst`, `:db/id`, and raw agent lookup refs.
- `bin/test --paths <owned paths> -- seon.html-views-test my.plan-test
  my.note-test seon.cluster.message-test seon.render.ns-test`: **62 tests,
  458 assertions, zero failures/errors** (`tmp/html-views/final-pairs-gate.log`).
- The required gate including `seon.render.web-test` ran **119 tests,
  837 assertions, zero assertion failures and one error**:
  `canonical-debug-feed-repaints-when-the-subject-changes` timed out waiting
  for its subject marker, including isolated confirmation. See the
  [recorded feed failure](../../../seon/issues/debug-feed-subject-change-regression-times-out.md).
  Log: `tmp/html-views/focused-gate.log`.
- The wider affected-namespace run had **208 tests, 1,324 assertions,
  24 failures and 3 errors** (`tmp/html-views/final-gate-2.log`). An unchanged
  HEAD snapshot reproduced all 24 assertion failures plus the identity error:
  **56 tests, 358 assertions, 24 failures and 1 error**
  (`tmp/html-views/head-boundary.log`). These concern existing identity
  creation, bootstrap expectations, maintenance fact queries and the unhanded
  database-query timing bound. No causal attribution to another lane is made.
  The other errors were the separately recorded feed timeout and the
  [published-base file loss](../../../seon/issues/parallel-test-base-connect-can-lose-a-filestore-key.md);
  that settings fixture passed isolated confirmation and the later gate.
- Final explicit `bin/test --platform`: **84 tests, 505 assertions,
  zero failures/errors** (`tmp/html-views/platform.log`). No `--all` or
  `--full` invocation was used. The runner's printed skipped-coverage suggestion
  was not executed.

The owned-path selection includes the ten source owners, `blocks.css`,
`html_views_test.clj`, its golden EDN, and the existing message/ns tests.
The final browser-inspection script edit only strengthens the read-only
browser evidence; no production Clojure or CSS changed after these gates.

Final explicit `bin/seon init --dev default --changed src/seon/plan.clj`
converged in the existing PID 23557. MCP then read both the cluster's adopted
`:seon.source/commit-id` and `seon.cluster.source/current` as
`6aa89c04-03d8-5bb8-a9f2-b6ebf608888e`. The source digest was
`1957e273a738118916517a23957759607a0a2c582d348b693322a495f2d89888`.
Log: `tmp/html-views/final-adoption.log`. Earlier source-changed adoption
refusals did not cause a restart, refork, reseed or agent message.
This proof exercised **in-place development adoption**, followed by actual
browser paint; it is not inferred from a hot-reloaded Var alone.

`test/seon/html_views_inspect.cjs` now clicks the native defaults summary and
asserts it remains open through capture at each width. The expanded live
view shows 16 defaults, including `65,536`, `32,768`, `180 s`, `0.5 s`, and
plain model names. Datastar's existing `data-preserve-attr="open"` mechanism
keeps these disclosures stable across morphs; its implementation is
`reference-code/datastar/library/src/plugins/watchers/patchElements.ts:562`.
The same attribute is used for completed criteria and transaction details.
Evidence: `tmp/html-views/inspect-adopted.log` and
`settings-expanded-1440.png` / `settings-expanded-700.png`.

### Live screenshots after convergence

Each linked image was captured on Juniper after the successful adoption.
The desktop shell restriction is visible in these images; the fixture images
above isolate the renderer's content. Nonempty faults, help, transactions,
maintenance and standalone namespace bindings remain fixture-only evidence
because those values are absent from the current live walk.

| Block | 1440 × 900 | 700 × 900 |
| --- | --- | --- |
| plan | [plan-adopted-1440.png](../../../../tmp/html-views/plan-adopted-1440.png) | [plan-adopted-700.png](../../../../tmp/html-views/plan-adopted-700.png) |
| settings | [settings-adopted-1440.png](../../../../tmp/html-views/settings-adopted-1440.png) | [settings-adopted-700.png](../../../../tmp/html-views/settings-adopted-700.png) |
| messages | [messages-adopted-1440.png](../../../../tmp/html-views/messages-adopted-1440.png) | [messages-adopted-700.png](../../../../tmp/html-views/messages-adopted-700.png) |
| inbox | [inbox-adopted-1440.png](../../../../tmp/html-views/inbox-adopted-1440.png) | [inbox-adopted-700.png](../../../../tmp/html-views/inbox-adopted-700.png) |
| notes | [notes-adopted-1440.png](../../../../tmp/html-views/notes-adopted-1440.png) | [notes-adopted-700.png](../../../../tmp/html-views/notes-adopted-700.png) |
| identity | [identity-adopted-1440.png](../../../../tmp/html-views/identity-adopted-1440.png) | [identity-adopted-700.png](../../../../tmp/html-views/identity-adopted-700.png) |
| faults | [faults-adopted-1440.png](../../../../tmp/html-views/faults-adopted-1440.png) | [faults-adopted-700.png](../../../../tmp/html-views/faults-adopted-700.png) |

### Commits and cleanup

One path-limited commit per block; no `input.css`, `output.css`, web,
transcript, REPL, dir or doc implementation was committed by this lane.
`bin/css` rebuilt output after every CSS edit. No extra token or safelist
entry is needed. The build artifact remains the orchestrator's checkpoint.

| Block | Commit |
| --- | --- |
| plan | `5d59aa991` |
| settings | `20193870c` |
| messages | `322def5d8` |
| notes | `e5c08fecb` |
| help | `09fb21ae3` |
| identity | `6fb5039af` |
| faults | `597ef8b68` |
| transactions | `78df20669` |
| maintenance | `7a4d3923d` |
| namespace | `4dc8a1831` |

The ten source paths are `src/seon/plan.clj`, `src/seon/agent.clj`,
`src/seon/cluster/message.clj`, `src/seon/note.clj`, `src/seon/bootstrap.clj`,
`src/seon/cluster/agent.clj`, `src/seon/error.clj`, `src/seon/db.clj`,
`src/seon/maintenance.clj`, and `src/seon/render/ns.clj`.
Additional touched paths: `resources/public/css/blocks.css`,
`test/seon/html_views_test.clj`, `test/seon/fixtures/html_views_ai.edn`,
`test/seon/cluster/message_test.clj`, `test/seon/render/ns_test.clj`, the
three browser scripts named above, this landing note, and the four issue
notes linked here (layout, fixture-file loss, debug-feed timeout, fault resolution).

All own gate/browser shells completed. Failed roots `run.PzoKkQ` and
`run.Mbhn31` were deleted after the finished sessions and process-table check
showed no JVM/BB holder. `run.AC8WUl` was already absent. Successful gates
removed their roots. Temporary commit-splitting copies and scripts were
removed. Requested screenshots, fixture HTML and gate logs remain as evidence
under `tmp/html-views/`; no scratch cluster or worktree was created. Foreign
working-tree edits and untracked files were preserved.

The outstanding fault-state requirement is tracked in
[fault-resolution-has-no-declared-fact.md](../../../seon/issues/fault-resolution-has-no-declared-fact.md).
A renderer cannot derive repair from an absent steward or from fault age.
