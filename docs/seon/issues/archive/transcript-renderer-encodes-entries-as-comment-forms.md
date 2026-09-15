---
type: issue
status: resolved
severity: friction
tags: [issue, render, class/n1, wave/strict-repl-display]
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

## Live capture attempt — 2026-08-14

The Drive 1 stored capture facts (`tmp/drive-1-root`, six captures carrying a
prompt, 210 result positions) contain NO comment-encoded entries. The only
`;;` lines in the entire corpus are the two inside the `getting-started`
instruction's own code fence. The drive's model replies never settled — the
turns failed at `:seon.instrument/contract-violated` — so no agent prose ever
reached the transcript.

This neither confirms nor refutes the comment-encoding subclass; it records
that the required live failed-form proof is still outstanding and that this
drive could not supply it. Recorded during the
[results-as-data audit](../../prds/context-generation/research/results-as-data-audit-2026-08-14.md).

## N1 disposition — 2026-08-12

The comment-form subclass remains fixed by `c6a81988c`: the focused
`error-receipt-without-triage-has-an-execution-error-face` check passed again,
and entries are source followed by computed value/error with no comment frame.
The note remains open for its required live failed-form proof. The complete
`seon.render.transcript-test` namespace is independently red in
`same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order` because the
generated bootstrap task is absent from the prefix; that ordering defect is
not a comment-render regression and was not edited here.

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED — the outstanding live failed-form proof is now in hand.**

The `default` cluster's database holds 28 stored evaluations carrying
`:seon.cluster.eval/error`. Rendering one of them through the current
grammar on the live JVM (pid 69622), read-only:

```text
user=> [:find ?note :in $ ?subject :where [?note :my.note/agent ?subject]
        [?note :my.note/content ?content]
        [(clojure.string/includes? ?content "Ada")]
        [(clojure.string/includes? ?content "155")]]
#:seon.repl{:error "Execution error (ExceptionInfo) at sci.impl.utils/throw-error-with-location (utils.cljc:67).\nUnable to resolve symbol: ?note", :ns my.agents.juniper, :ms 3}
```

The entry is the evaluation's own submitted source followed by an
execution-error face derived from the receipt's structured attributes — not
a bare sentence that could pass for a value, and not a comment form. The
face comes from `seon.repl/error-text` (`src/seon/repl.clj:122-140`), which
reads the recorded `ex-triage` data as the authority and falls back to the
stored message "only what it knows: NO invented `(REPL:1)` location and no
empty class parens".

Error entries are identifiable structurally in both projections (the error
rides its own `:seon.repl/error` key rather than being classified out of a
string), and no `;;` framing, annotation or comment-only pseudo-entry
appears anywhere in the corpus. This closes the subclass that
`c6a81988c` fixed and the live proof it was waiting for.

The unrelated `seon.render.transcript-test` ordering red noted in the
2026-08-12 disposition is a separate defect and is not evidence about this
face.
