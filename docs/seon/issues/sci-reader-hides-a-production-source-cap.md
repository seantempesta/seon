---
type: issue
status: open
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
