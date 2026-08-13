---
type: issue
status: resolved
severity: friction
tags: [issue, render, sci, mcp, class/n1, wave/class-kill-queue]
---

# Contract refusal envelope repeats one blob digest six times

## Problem

A door-mode contract violation for a simple wrong-argument-type call
rendered a ~9 KB envelope in which the same blob digest (`7ccbab2e…`)
appears SIX times at six different paths, burying the one useful sentence
("should be a string") under repeated requery scaffolding. A one-line typed
refusal must render as one line plus one requery identity; shared
substructure in an admitted envelope should be emitted once.

## Evidence

2026-08-13 comprehension test deployment: `mcp__seon__eval_clj` (door) of
`(seon.fn/tests-reaching (seon.db/db) 'seon.cluster.run/open-tx)` — the
symbol-vs-string violation. The envelope repeated the identical elision
value/digest at six paths.

## Owner

`seon.print` fit/elision emission for admitted envelopes (one elision value
per distinct digest, referenced thereafter) and the MCP envelope
projection.

## Acceptance

- A contract refusal for one bad argument renders its message first and
  its evidence once; an identical digest appears at most once per envelope
  with subsequent references by identity.
- One regression pinning the single-emission property.

## Resolution — 2026-08-13

The repeated digest was not a `seon.print` deduplication defect. The shared
constructor in `seon.instrument/violation` stored the same contract evidence
as semantic data, canonical EDN, rendered text, and a serialized print tree;
`seon.sci.kernel/failure-value` then wrapped that refusal as a second
`nested-refusal`. The terminal fit honestly elided every duplicate it was
handed. The constructor now retains one bounded semantic problem and the
kernel preserves an existing flat refusal while accreting only the new eval
record.

The identical door form was read from an isolated live cluster before and
after. Before, the admitted artifact was 9,266 characters and the same digest
appeared six times:

```clojure
#:seon.error{:kind :seon.instrument/contract-violated, :message ""… 134 more characters of 134; requery by [:seon.blob/digest "7040b41a..."] at path [1 1] offset 0 with :seon.render.profile/agent,
  :data {:seon.error/diagnostic-evidence #:seon.eval{:fn-entries 0, :host-interop-count
      0, :duration-ms 137, :allocated-bytes 395585104, :outcome :error}, :seon.error/diagnostic-expected
    :successful-evaluation, :seon.instrument/schema ""… 43 more characters of 43; requery by [:seon.blob/digest "7040b41a..."] at path [2 1 2 1] offset 0 with :seon.render.profile/agent,
    :seon.instrument/problem-count 1, :seon.instrument/fn ""… 22 more characters of 22; requery by [:seon.blob/digest "7040b41a..."] at path [2 1 4 1] offset 0 with :seon.render.profile/agent,
    :seon.error/diagnostic-member :nested-refusal, :seon.sci.eval/throwable
    ""… 26 more characters of 26; requery by [:seon.blob/digest "7040b41a..."] at path [2 1 6 1] offset 0 with :seon.render.profile/agent,
    :seon.instrument/problems ""… 449 more characters of 449; requery by [:seon.blob/digest "7040b41a..."] at path [2 1 7 1] offset 0 with :seon.render.profile/agent,
    … 11 more children of 19; requery by [:seon.blob/digest "7040b41a..."] at path [2 1] offset 8 with :seon.render.profile/agent}}
```

After, the complete agent-facing text is inline, contains no digest, and the
MCP envelope reports `:seon.sci.admit/capped? false`:

```clojure
{:seon.error/message "seon.fn/tests-reaching violated its contract (invalid-input): should be a string",
  :seon.instrument/contract-violated "seon.fn/tests-reaching", :seon.error/data
  {:seon.error/diagnostic-evidence #:seon.instrument{:problem-count 1, :problems
      [#:seon.instrument.problem{:message "should be a string"}]}, :seon.error/diagnostic-expected
    [:cat :seon.db/database-value :seon.fn/sym], :seon.instrument/problem-count
    1, :seon.error/diagnostic-member :arguments, :seon.sci.eval/throwable
    "clojure.lang.ExceptionInfo", :seon.error/diagnostic-evidence-availability
    :seon.error/known, :seon.instrument/malli :malli.core/invalid-input, :seon.sci.admit/record
    #:seon.eval{:fn-entries 0, :host-interop-count 0, :duration-ms 127, :allocated-bytes
      208952576, :outcome :error}, :seon.error/diagnostic-layer :instrumentation,
    :seon.error/diagnostic-offending [seon.cluster.run/open-tx], :seon.error/diagnostic-operation
    seon.fn/tests-reaching, :seon.error/diagnostic-cause :malli.core/invalid-input,
    :seon.instrument/arm :input}, :seon.error/kind :seon.instrument/contract-violated}
```

The class census in
`every-indexed-outward-path-crosses-its-total-render-projection` asserts a
non-empty visible sink population and fails on any bypass or unresolved path.
