---
type: issue
status: open
severity: friction
tags: [issue, render, test-fixture]
---

# Web-debug fixture transactions and budget expectations fail

## Evidence — 2026-09-16

After preserving the renderer ref's :db/id in the ledger selector,
seon.render.web-debug-test/turn-details-use-the-loop-opening-and-exact-segments
reaches 95 assertions: 88 pass, seven fail, zero errors. In-process runs
50137 and 50144 reproduce the same seven failures in default PID 53378.
The previous pulled-ref contract error is gone.

Two fixture writes return no :db-after: the panel-fault/message setup and
a later turn transaction. Three ensuing counts (faults, empty-replies,
fault-turns) remain zero instead of one. The prompt total is 11100 instead
of 18200, and the expected cost 0.011852 differs by 0.0008.
These are observations, not seven independently established root causes.

The writer also reports agent-already-running and missing
[:seon.message/id "panel-fault-message"]. No cause is attributed to another
lane. The fixture must first expose and resolve each transaction's actual
refusal before its downstream expectations can be assessed.

## Owner and acceptance

The existing web-debug fixture and its turn/fault setup, with the rendered
attempt-budget assertions. Use the canonical in-process loader and real
writer, without suppressing contracts or replacing fixtures with stubs.
The named test should pass all assertions and establish successful fixture
transactions before asserting their effects.

Exact call, run numbers and the independent arming repair:
[landing note](../../prds/steward-platform/research/arming-includes-referenced-schemas-2026-09-16.md).
