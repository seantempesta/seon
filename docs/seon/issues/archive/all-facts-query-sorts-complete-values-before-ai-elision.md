---
type: issue
status: resolved
severity: blocker
tags: [issue, render, performance, wave/agent-context]
---

# All-facts query sorts complete values before AI elision

On 2026-09-09 the exact saved reply in
`docs/prds/context-generation/research/help_trial_2026_09_09.edn` passed the
amended reply reader, but its query of every datom did not finish rendering
within the canonical loop test's event backstop. Both source entries were
stored; neither result had settled. There was no Flow fault at that point.

A live virtual-thread stack identified `seon.render.value/value-node*` →
sort-by → key-text → `seon.print/emit-text`, repeatedly rendering set
members as complete HTML print keys before the AI child limit was applied.
The reproducible no-provider script is
`docs/prds/context-generation/research/reply_reader_trial_probe_2026_09_09.clj`.

Once concurrent value-renderer edits landed, the reply-reader slice fixed
the existing renderer: AI set traversal visits only its retained members;
HTML retains its complete ordering. Saved shown text owns prompt stability,
as ruled in §18a. No new clipping location or limit was added.

`test/seon/loop_proof_test.clj` now submits the exact unchanged saved trial
reply, including its unrestricted query. The real query result and its read
evidence settle before the fabricated response is recorded as an error.
The initial fast run after this fix completed that scenario; its only
failure was the separately exposed empty-namespace directory error. Final
gate counts and the slice commit are in the context-blocks landing note.
