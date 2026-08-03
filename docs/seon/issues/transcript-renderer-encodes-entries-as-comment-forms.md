---
type: issue
status: open
severity: friction
tags: [issue, render, context, architecture]
---

# Render transcript entries as forms and actual values

## Problem

The transcript AI projection wraps message and evaluation entries in Clojure
`comment` forms and prefixes entry metadata and elision notices with `;;`.
Those pseudo-entries model comments as output instead of showing the source
form followed by its computed value.

## Evidence

`src/seon/render/transcript.clj:327-425` emits `;; transcript/entry` headers and
`(comment ...)` entry bodies; `:503-505` emits a comment-framed elision notice.
`test/seon/render/transcript_test.clj:111-116,332` recognizes those markers.
The superseding ruling is decision 11 in
[messaging, state, and reply-norm design](../../prds/sci-execution-runtime/research/messaging-state-design-notes-2026-08-03.md).

## Owner

`seon.render.transcript` owns the transcript's AI and HTML projections.

## Acceptance

Each displayed evaluation consists of its actual form source followed by its
actual computed value. Messages, errors, caps, and elision notices remain
visible as ordinary values or printed output, never as `(comment ...)`,
comment-prefixed headers, annotations, or comment-only pseudo-entries. The
recurring transcript tests assert identities and values rather than comment
markers.
