---
type: issue
status: open
severity: friction
tags: [issue, render, context, architecture]
---

# Render transcript errors as execution errors

## Problem

An eval receipt carrying `:seon.cluster.eval/error` but no
`:seon.cluster.eval/triage-edn` renders the stored error message as a bare
sentence after the form. That sentence is indistinguishable from an ordinary
value or narration instead of presenting the unmistakable execution-error
face a Clojure REPL uses.

## Evidence

Commit `c6a81988c` removed the old comment-form transcript and made each eval
entry show its source followed by the receipt renderer's output. The remaining
fallback was `seon.cluster.run/render-receipt-ai`: it used
`clojure.main/ex-str` when triage data was present but returned the raw
`:seon.cluster.eval/error` string when it was absent.

`seon.render.transcript/receipt-text` now derives a minimal execution triage
map from that receipt error and formats it through Clojure 1.12.5's
`clojure.main/ex-str`. The
`error-receipt-without-triage-has-an-execution-error-face` regression seeds one
run with one such receipt and identifies the face in both AI text and the HTML
entry structure. The explicit `seon.render.transcript-test` and
`seon.cluster.run-test` namespaces passed. Live proof remains outstanding
because the isolated `transcript-error-face` cluster currently stops in the
foreign `seon.cluster/ensure-entity!` boot boundary, and transcript rendering
then fails the foreign `seon.sci.kernel/invoke` capture-context contract.

## Owner

`seon.render.transcript` owns the transcript's AI and HTML projections.

## Acceptance

Each displayed evaluation consists of its actual form source followed by its
actual computed value or a Clojure-shaped execution error derived from the
receipt's structured attributes. Error receipts remain identifiable in both AI
and HTML projections without string classification, comment-prefixed prose,
annotations, or comment-only pseudo-entries. A real failed form on an isolated
scratch cluster provides the final before/after proof once the foreign boot
and capture-context boundary is green.

## N1 disposition — 2026-08-12

The comment-form subclass remains fixed by `c6a81988c`: the focused
`error-receipt-without-triage-has-an-execution-error-face` check passed again,
and entries are source followed by computed value/error with no comment frame.
The note remains open for its required live failed-form proof. The complete
`seon.render.transcript-test` namespace is independently red in
`same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order` because the
generated bootstrap task is absent from the prefix; that ordering defect is
not a comment-render regression and was not edited here.
