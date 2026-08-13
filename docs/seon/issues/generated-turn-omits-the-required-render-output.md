---
type: issue
status: open
severity: blocker
tags: [issue, agent, render, schema, class/p3, wave/generate-call-transition]
---

# Generated turn omits the required render output

## Problem

After a generated opening settles `(help)`, its next generation pass calls
`seon.bootstrap/next-entry` with a request that fails that function's declared
input contract because `:seon.render/output` is absent.

## Evidence

Live isolated-root proof on 2026-08-12, commit `16f022fc9`, after applying only
the process-local environment repair:

```text
seon.bootstrap/next-entry violated its contract (invalid-input):
[#:seon.render{:output [{:value nil, :message "missing required key"}]}]
```

The settled `(help)` receipt was correct and no second form was appended.
`src/seon/cluster/loop.clj:1592-1603` constructs the request without
`:seon.render/output`; `seon.bootstrap/next-entry` declares
`:seon.render.walk/request`, which requires the projection selector.

A process-local wrapper adding `:seon.render/output :seon.render/form` removed
this refusal. No source was edited.

## Owner

`seon.cluster.loop/generate-turn` owns the complete request passed to the one
generated-entry derivation.

## Scheduling note — 2026-08-12

Skipped by the evolving-session defect-clear wave because
`src/seon/cluster/loop.clj` is held by the generate-call-transition lane. The
reproduction and owner are specific, but changing or testing that request
constructor would overlap the live lane. Leave this issue scheduled until that
owner is free.

## Acceptance

- The live second generation pass satisfies `next-entry`'s input contract.
- The selected projection is explicitly `:seon.render/form`.
- One regression starts from a settled root receipt and reaches the next
  dependency-ready form without an instrumentation error.
