---
type: notes
status: landed with limits
created: 2026-10-07
lane: s5a-fix
fixes: 7b5535cf6 (s5a-reply) review items
---

# s5a-fix: fix-forward of the s5a-reply reviews

Which step of the one loop this serves: the reply print (step "print") and the record step. It deletes
`:seon.problems/id` (declared, pulled, read, never written) and two `:maybe` contract members.

## Items

1. P1 phase-failure arm. The arm held planned forms as `{:seon.cluster.eval/ns {:seon.ns/name n}}`, a map without
   `:db/id`, which `with-saved` refuses. It now holds them through `seon.turn/fold-source` (a lookup ref), the same
   producer the fold uses. LIMIT: no entrance test could be written. The arm runs only when `evaluate-sources`
   throws before any form runs; the entrance probes tried were all refused earlier by contracts:
   a handle without `:seon.config.eval/time-limit-ms` (refused at `evaluate-source!`'s input contract), a cluster
   name with no config (refused at `evaluate-source!`'s turn-completion read). The regression
   `seon.turn-test/a-submitted-form-is-held-for-the-reply-under-its-namespace-lookup-ref` reaches the private
   `fold-source` and `with-saved`; it is not the entrance test the review named.
2. Contracts: `candidate-history`, `asked-value`, `evaluation-terminal-data` added; `:maybe` removed from
   `observed`, `with-saved`, `closed-in?` (callers pass `[]` or the key).
3. JVM call situation: `record-evaluated-call` writes `:seon.turn.work/situation :call` on the run it records.
   `seon.mcp-entrance-test/a-jvm-reply-keeps-its-duration-after-an-earlier-run` now asserts the situation and that the
   reply equals the stored response; red on HEAD (`nil`; `:ms` shown versus not), green with the change.
4. `:seon.problems/id` deleted from the three schemas, the transcript pull list and `form-settlement`; tests that used it
   as a handy identity now use `:seon.source/digest`. Errors spec line and the `observation-reads-the-run-twice`
   intent corrected.
5. Print length: the `set!` row now also asserts the saved `:seon.print/length` is 2 on the run's receipts. That the
   text still shows five elements is in `seon.render.value/print-options` and `seon.repl` (the HTML face nulls the
   length by design at value.clj:1574; the text face is printer-code's), both held by lane printer-code. Task
   1b420c6cb408 stays open and belongs to that lane.
6. `seon.render.web-context-test/an-agent-whose-prompt-refuses-still-shows-the-refusal-in-its-context-view`.
7. Refused-but-saved. Measured, not green. In `code-evaluates-as-one-ordinary-run-of-its-agent` the refusal trigger
   is a bare `ns-unmap` of a helper with a referrer. A bare `ns-unmap` no longer retracts the program entity (the
   program-deletion verb is `my.program/ns-unmap!`), so no settlement refusal happens: line 126's
   `(:seon.cluster.eval/error ...)` is empty and `(:seon.turn.loop/refusal result)` is nil. The test's trigger is
   stale for tasks c4309801a9fe and c1c8c0a34adc; a new trigger needs a form whose facts the writer refuses while the
   run is open (for example a definition digest changed between read and write), not tried.

## Tasks

The reviewers' tasks (06cd4d9307eb, b46ea832c5f0, 774c3d7fe02b, d1b88749c142, 6779fdb78ac2) exist only in the
running store, not in `resources/seon/tasks`; a `:seon/tasks` tag naming them is refused at publication
(`dangling-id`), so none of the new tests carries one. No installed operation marks a task a duplicate:
`seon.task` has `resolution` and `task-resolution` (derived), nothing that closes by hand. b46ea832c5f0 and
774c3d7fe02b describe the same defect; both resolve when their producer changes and proofs run.

## Design check

The design held. The P1 fix reuses `fold-source` instead of adding a normalizer. The situation fix is one key in the
transaction row, outside `recorded-run`, so duplicate-recording comparison is unchanged.
