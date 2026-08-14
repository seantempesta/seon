---
type: issue
status: resolved
severity: friction
tags: [issue, sci, config, wave/sci-reader-limit]
---

# Give the SCI source-size cap a declared owner

## Problem

The one reader silently applies a private 1 MiB source cap to production calls.
Its comment says there are no production callers or config seam, but both reply
freezing and evaluation call it without a bound.

## Evidence

- `src/seon/sci/reader.cljc:7-10` hard-codes `1048576` and carries the false
  no-production-caller comment.
- `src/seon/sci/reader.cljc:569-619` defaults every omitted bound to it.
- `src/seon/sci/eval.clj:651-666` omits `:seon.sci.reader/max-chars`.
- `src/seon/cluster/reply.cljc:115-134,272-281` also omits it on model reply
  parsing.

## Owner

The single configured result/source limit family and `seon.sci.reader/read`.

## Acceptance

The cap is a schema-derived config fact passed by every production caller, or
the owner explicitly proves and documents a different derived bound. No private
fallback number decides production admission.

## Re-grounded evidence — 2026-08-13

**STILL-REAL at `06e654c76`.** The `.cljc` reply anchor was only renamed:

- `src/seon/sci/reader.cljc:7-10,569-596` still owns the private 1 MiB fallback
  and still claims there is no production caller.
- `src/seon/sci/eval.clj:467-488` calls the reader for live evaluation without
  `:seon.sci.reader/max-chars`.
- `src/seon/cluster/reply.clj:129-145,303-312` calls it for reply freezing and
  prose recovery without the bound.
- No schema resource or shipped config fact declares
  `:seon.sci.reader/max-chars`.

## Census cross-reference — 2026-08-14

Member #12 of the outward-bounding census
([context-clipping-census-2026-08-14](../../prds/context-generation/research/context-clipping-census-2026-08-14.md)).
This is not an isolated dial: the census found the same private-constant shape
at `src/seon/flow.clj:1093-1096` (bare `160`), `src/seon/ai.clj:706-708,1386-1387`
(bare `500`, twice), `src/my/note.clj:22,266` (bare `50`), `src/my/message.clj:22`
(bare `160`), and `src/seon/render/ns.clj:234-240,393,409` (bare `78`). The
proposed class regression derives the member list from the program graph rather
than maintaining it by hand, so this issue's fix should land as part of that
class rather than as a one-site repair.

## Resolution — 2026-08-14

Resolved by `fd75232f3` (`admission-cap-declared`). The private fallback and
its false no-production-caller comment are deleted. The shipped manifest and
schema now declare `:seon.config.eval.result/max-source`; `seon.config/result-caps`
carries it with the admission-cap family. Live evaluation and model-reply
freezing pass that fact explicitly. Checks over already-stored source pass the
source's exact derived length instead of inventing another policy.

Before, omitting the reader option silently selected 1,048,576 characters.
After, the same call returns `:seon.sci.reader/unreadable` with the absent
`:seon.config.eval.result/max-source` in error data. An over-cap raw reply
returns a typed refusal carrying both the observed length and configured cap;
prose recovery happens only after that admission and uses the recovered
value's exact length.

The focused reader/reply gate passed 28 tests and 214 assertions. The broader
source-family run exercised 165 tests and 905 assertions; its only remaining
red outside the subsequently fixed reply case is the pre-existing
`seon.config-application-test/applied-values-shape-the-running-system`
expectation, which omits the launcher-owned
`:seon.config.agent/turn-completion-backstop-ms` fact.
