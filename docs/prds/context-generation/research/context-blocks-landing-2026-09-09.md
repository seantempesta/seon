---
type: research
status: active
tags: [agent, context, render]
---

# Context blocks — 2026-09-09

Read AGENTS.md's verbatim lane rules and turn PRD §10, §13–§16, and §18 end to end before implementation. The initial default page was read before source inspection. Default was alive (PID 40078, port 7994); MCP runtime status returned health/Flow unknown with `Read timed out`. JVM evaluation subsequently returned 2 from `(+ 1 1)`. This is a failed health observation, not proof of a dead cluster.

## Initial observation

The would-be system turn has no help/instructions, exposes a raw identity pull, emits three plan reads, shows two ExceptionInfo objects for blocked steps, dumps the complete settings keyword set after overrides, and emits `(my.message/inbox {})`. Four inbox messages include two fixture messages and two unrelated probe messages. The saved prefix contains only `(+ 1 1)`; historical probe turns also remain in the debug view. No provider was invoked by this assignment.

## Dependency ledger and verified boundary

- SCI's call-preparation hook (`reference-code/sci/src/sci/core.cljc:310`) receives evaluated arguments and returns prepared arguments or a reduced refusal. `src/seon/call_preparation.clj:657` derives shorter calls from declared contracts; request-map defaults belong there.
- Datahike's transaction function (`reference-code/datahike/src/datahike/db/transaction.cljc:1152`) receives the transaction database. Existing plan mutations make ownership and position decisions there.
- `src/seon/db.clj` owns pulls and read evidence; `src/seon/plan.clj` owns the moved plan queries, transactions, and render functions. No alternate storage family was introduced.
- With the live database's projection explicitly supplied, JVM `my.plan/blocked` returned ordinary maps containing dependency strings under `:my.plan.item/needs`. That attribute declares stored refs. The AI projection treated these strings as entity refs and failed. Derived summaries now carry the existing `:my.plan/needs` stable-reference maps.

## Slice 1 — in progress

Positional operations move to system namespaces; `my.*` exposes request-map calls. Fully namespaced keys remain mandatory; §18's abbreviated key examples do not introduce unqualified attributes. The section's blanket request-map rule governs its two positional plan examples.

Verification and live adoption results will be recorded with the slice commit. Scratch root: `tmp/context-blocks-root`, no-provider configuration. Default has not been stopped, restarted, or reforked.

### Slice 1 verification

- Fast: 43 tests / 430 assertions / zero failures or errors.
- Path-limited gate: 47 tests / 456 assertions / zero failures or errors across
  the my API namespaces, filesystem/edit predicates, and real SCI shown-text regression.
- Expanded gate: 71 tests / 574 assertions / 6 failures / 3 errors. The
  detached HEAD-only baseline at `0b3d31b26` reproduces those same failures:
  24 tests / 123 assertions / 6 failures / 3 errors in call-preparation and
  bootstrap. This is a pre-existing consumer-fixture boundary, not an
  attribution to another session. Details are recorded in
  `docs/seon/issues/turn-consumer-fixtures-read-retired-result-storage.md`.
- Fresh scratch fork, no-provider: Juniper seeded through the maintained
  fixture installer and read by HTTP at port 7833. The HTML has 65,811 bytes;
  rendered page text has zero `ExceptionInfo` strings, four bare inbox calls,
  and zero inbox calls carrying `{}`. Blocked dependencies are stable maps.
- Computer Use reports no browser available. Page-text verification is
  complete; visual layout and browser repaint are not claimed.

While slice 1 ran, owner commits `02c77c542` and `0b3d31b26` amended §18/18a.
The original assignment and amended help/scenario requirements differ; the
owner was asked asynchronously which governs the remaining slices.
- Path-limited platform gate passed; exact tally is recorded in the slice commit message.
