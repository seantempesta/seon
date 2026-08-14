---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, schema, wave/live-drive-context]
---

# Capture a generated-opening refusal before closing the run

## Problem

The generated-opening prompt path returned a flat refusal before the loop
constructed or committed a context capture. The terminal run error survived,
but the exact failure evidence at the context boundary did not. That inverted
the transport law: diagnosis depended on an absent fact.

## Evidence

The read-only Drive 1 reproduction against preserved root
`tmp/drive-1-root`, cluster `default`, found run
`bootstrap:drive-one-agent` opened at `2026-08-14T05:39:41Z` and closed four
seconds later. Its only form was:

```clojure
; A new run just opened. Why am I awake — do I have messages?
(help)
```

The run carried this terminal error:

```text
The EDN-backed attribute :seon.db/read-request has an invalid logical value.
```

At observed basis `t=536871061`, the run had one form, one failed receipt,
zero `:seon.context.capture/run` facts, and zero model attempts. Thus the
terminal refusal existed while its context-boundary evidence did not.

## Owner

`seon.cluster.loop` owns ordering around prompt derivation and provider entry;
`seon.context/capture-tx` owns the one durable capture fact family.

## Resolution

Resolved in the commit that archives this note.

The loop now freezes one database value before deriving the opening database.
Whether prompt derivation succeeds or refuses, it constructs and commits one
capture transaction before taking any refusal exit. A successful capture
retains the exact prompt and contribution rows. A refused capture uses the
same `:seon.context.capture` identity derived from run id and database basis,
but records only the typed `:seon.error/kind` and `:seon.error/message`; it
does not invent prompt text. Only after that fact is durable does the loop
close the run, and it never calls the provider on this path.

The capture declaration and request schema were accreted in place: prompt and
contributions are optional on refusal captures, while the request accepts
exactly one of a rendered-context arm or a database-plus-error arm.

`refused-generated-opening-captures-evidence-before-close` exercises the real
turn boundary with the canonical database fixture. It forces prompt refusal
and asserts a closed error outcome, zero provider calls, a capture basis,
matching typed error evidence, and absence of a prompt attribute.

Focused isolated-root gate:

```text
bin/test seon.cluster.loop-test
Ran 22 tests containing 171 assertions.
0 failures, 0 errors.
```

## Acceptance

A refused generated-opening prompt commits typed context-capture evidence
before the run closes and makes no provider call. A successful prompt still
commits the exact prompt capture before its first provider call.
