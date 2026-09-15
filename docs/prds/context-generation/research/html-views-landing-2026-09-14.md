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
40 captures (20 HTML specimens, both viewport sizes).

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

