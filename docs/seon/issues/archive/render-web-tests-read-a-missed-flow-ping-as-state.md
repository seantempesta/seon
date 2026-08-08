---
type: issue
status: resolved
severity: blocker
tags: [issue, render, web, flow, testing]
---

# `seon.render.web-test` read a missed `flow/ping` as proc state

## Problem

`flow/ping` returns a map "for those procs that reply within timeout-ms
(default 1000)"
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-142`),
and it is answered on the proc's own transform loop
(`.../flow/impl.clj:76-86,205`). A proc that is mid-transform is simply absent
from the result.

The render proc is `:io`, one derivation serializes the whole walk, and it
waits out the coalescing floor INSIDE its transform — so a pass routinely
outlasts that window. `seon.render.web-test/ping-state` returned `nil` in that
case and every oracle built on it treated `nil` as a state map.

## Evidence

Two errors in one focused run of `bin/test seon.render.web-test` on
2026-08-07, both `NullPointerException: Cannot invoke "Object.getClass()"
because "x" is null`, and both reproduced in a second run:

- `a-terminal-fact-supersedes-a-partial-after-the-lost-clear-ordering` —
  `(zero? nil)` inside an `await-ping!` predicate (`web_test.clj:837`);
- `coalesce-floor-one-derivation-test` — `(- nil before)` on the pass count
  (`web_test.clj:993`).

Both are load-dependent, which is why the namespace looked randomly red.

## Resolution

`ping-state` now OBSERVES the proc's answer instead of sampling for it: it
retries until the proc replies — paced by ping's own window, so it is not a
spin — under the shared loud backstop, which turns a genuinely wedged proc
into a loud failure rather than a hang or an NPE. Both oracles built on it
(`derivations`, `streaming-agents`) are total as a result, at one choke point.

The class regression is
`the-pass-oracle-observes-a-derivation-longer-than-flows-ping-window`
(`test/seon/render/web_test.clj`). It produces the busy window with the
production dial (`:seon.config.render/coalesce-ms 1500`) rather than a
redefinition, so the missed ping is certain instead of load-dependent, and
asserts the wanted behavior: the pass count is a number and it counts the pass
the floor held past that window.
