---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow]
---

# Enforce the eval render cap for failed diagnostics

## Problem

Failed evaluation source, stdout, Malli diagnostics, and ordinary/read errors
can bypass the configured eval render cap whenever the row requests
`:seon.render/full?` or the agent enables escape clipping. A model mistake can
therefore inject hundreds of thousands of repetitive characters into dynamic
context even though the database-derived render policy sets a much smaller
limit.

## Evidence

`seon.agent.ctx/format-eval-row` derives its local `small-full?` as
`(or full? escape?)` and passes it to every small failed-result component. A
pure live REPL probe rendered a failed row containing 100,000-character source,
stdout, and error strings as 300,006 characters with no truncation marker while
`seon.config/eval-render-cap` resolved to 1,500.

`seon.eval/read-error-message` can also construct a complete malformed source
line plus a caret run as wide as the reported column. A 100,002-character line
produced a 200,284-character diagnostic; `format-eval-row` emitted 200,290
characters without truncation when `:seon.render/full?` was true. Raw reply
capture is not the cause and remains correctly separate from this derived
agent-visible rendering.

## Owner

The one `seon.agent.ctx/format-eval-row` renderer and its existing
database-derived `:seon.config.render/eval-cap`. Preserve successful authored
full results and raw evidence; do not introduce a read-error-specific renderer,
another cap, or output-repair regexes.

## Acceptance

- Full and escape-clipping flags bypass the small-component cap only for
  successful evaluations.
- Failed source, stdout, Malli diagnostics, runtime errors, and read-error
  excerpts use the existing eval render cap under every flag combination and
  retain a loud truncation marker.
- A focused matrix proves failed rendering is byte-identical with no flag,
  full only, escape only, and both flags, while existing successful full-result
  behavior remains unchanged.
- A 100,000-character malformed line cannot produce an unbounded agent-visible
  diagnostic, cannot expose a stack trace, and preserves its useful headline
  and coordinate.
- The live REPL falsifiers above render within the configured component caps
  after the change.

## Resolution

Resolved by `b043589e`. Failed eval rows now force their source, captured
stdout, Malli body, and ordinary/read/runtime error through the existing eval
cap under no flag, `full?`, escape-clipping, or both. Successful authored
source/stdout and successful citable results retain their prior release
semantics. Runtime error projection continues to discard stacks; raw reply and
blob evidence are unchanged; core/dev escalation still follows the existing
`:seon.config/on-core-error` policy.

The live before/after probes measured:

- 100K source + 100K stdout + 100K error: 300,006 rendered characters without
  truncation before; 4,934 with loud truncation after, with all tail sentinels
  absent;
- 100K malformed source line: 200,284 producer characters and 200,290 rendered
  characters before; 3,278 producer characters and 3,287 rendered characters
  after, with the exact line/column preserved.

The exact focused gate passed four tests and 20 assertions for ordinary
runtime failures, Malli failures, read failures, 100K components, stack
suppression, all four flag combinations, and unchanged successful rendering:

```bash
bin/test-cljs --test=seon.ctx-test/failed-eval-hard-cap-ignores-full-and-escape-flags,seon.ctx-test/failed-malli-diagnostic-hard-cap-ignores-render-flags,seon.ctx-test/large-read-error-is-windowed-before-the-transcript-hard-cap,seon.ctx-test/successful-eval-retains-authored-full-and-escape-semantics
```
