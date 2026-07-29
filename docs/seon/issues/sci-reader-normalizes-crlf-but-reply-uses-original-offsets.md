---
type: issue
status: open
severity: blocker
tags: [issue, sci, parser]
---

# Preserve original line endings in SCI reader source spans

## Problem

`seon.sci.reader` returns source text normalized from CRLF to LF while its
cursor offsets address the original input. `seon.cluster.reply` combines those
two coordinate systems and can invent an extra plan source.

## Evidence

`src/seon/sci/reader.cljc:317-348` takes `:seon.sci.reader/source` from
SCI's `parse-next+string`, but calculates `start`, `end`, and `source-start`
against the original input. SCI's source reader normalizes CRLF in the returned
source string.

`src/seon/cluster/reply.cljc:126-134` then derives its event end as
`source-start + (count form-source)`. The normalized string is shorter than the
original CRLF span.

A direct JVM probe of `"; note\r\n(+ 1 2)\r\n"` observed:

- the fresh reader returned source `"; note\n(+ 1 2)"` with original-input
  offsets; and
- `seon.cluster.reply/sources` returned the intended form plus a bogus trailing
  `"; )"` source.

The old parser covered byte-faithful CRLF payloads at
`test-old/seon/repl/parse_test.cljc:1204-1232`. The complete merge audit and
ordered repair boundary are recorded in
`docs/prds/sci-execution-runtime/research/parser-merge-2026-07-29.md`.

## Owner

`seon.sci.reader`, with `seon.cluster.reply` consuming the reader's explicit
original-source span rather than reconstructing one.

## Acceptance

- Every public reader source is the exact substring of the original input at
  its declared source offsets.
- Consumed spans partition LF, CRLF, lone-CR, and Unicode input without loss or
  overlap.
- `seon.cluster.reply/sources` produces no extra plan source for CRLF input.
- A recurring reader test combines comments, multiple forms, CRLF, and a
  surrogate-pair character.

## Triage 2026-07-29

**PRESSING — parser-merge wave.** The current reader still combines SCI’s
normalized source text with original-input offsets, and the parser-merge audit
reproduces the bogus trailing source.
