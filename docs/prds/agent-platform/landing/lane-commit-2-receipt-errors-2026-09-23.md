---
type: landing
status: diagnosis complete; whole-cluster health unproved; held database boundary
created: 2026-09-23
---

# Commit 2 receipt errors — bounded comparison

The receipt-error classes predate commit 2. No acquisition repair is justified by
this evidence. A fresh pre-commit-2 cluster executes ordinary replies and records
three authored-input errors; the exact `(my.note/?)` failure also reproduces in its
SCI context. The unknown turn ping is an observation of a busy transform, with
stacks in system-turn refresh/transactions, not evidence of an acquisition crash.
**The requested whole-cluster HEALTHY proof is not obtained.** No production or
test code was changed, and no passing regression or gate is claimed.

## Correct comparison and frozen inputs

The requested `008e4ae1e` is **after** `1ada78050`, not before it:
`3357133c5 -> 1ada78050 -> 008e4ae1e -> 4aeaab1d7 -> 30851360d`.
`git diff 008e4ae1e 30851360d -- src script resources test` is empty.
The causal baseline used the actual parent, `3357133c5`; HEAD was frozen at
`30851360d`. The authorized detached checkouts contained those committed bytes,
with linked dependencies. No lane files, sessions or hooks were changed.

The shared-tree command requested by the owner was attempted first. PID 75144
refused publication: `test/seon/dev/hook_test.clj:90:21` called `prepl-value!` with
five arguments against a four-argument snapshot; `test/seon/program_test.clj:1108:30`
had unresolved `clojure.set`. Those foreign boundaries were bypassed by freezing
HEAD, not by editing their files.

Both snapshots used the same checked-out Datahike `41c79c1a70f108cf969b8c5ec6d3ba81c8835eb8`
(the already-landed complete-pull change); the commits pin `cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`.
This is a controlled first-party comparison with a shared newer dependency, not an
exact historical dependency reconstruction. SCI is `fcbd8862800e638dc0f8f5521111f999279cbcd2`.
The source diff and pinned dependency roster are retained with the raw evidence.

Repository default, PID 51528, was read through `bin/seon status` and MCP status
only. Both tools answered. Scratch MCP status/REPL also answered during boot with
honest degraded/connection-not-acquired results. No missing tool was bypassed.

## Cold boots and first ordinary execution

| Frozen source | PID / start UTC | Readiness ms | Recovery | Proof |
| --- | --- | ---: | ---: | --- |
| HEAD `30851360d` | 79111 / 19:43:17.011 | 160,929 | 0 | Generated bootstrap `12a2b18544e6` closed at tx 536870943. First ordinary turn `8ca443e36fcb` closed at 536870950, second `c7d30b1fa12a` at 536870957; both ran `(dir my.note)` without errored receipts. |
| Parent `3357133c5` | 81159 / 19:44:50.400 | 167,440 | 0 | Generated bootstrap closed at tx 536870943. First ordinary turn `8ca443e36fcb` closed at 536870949. Next ordinary turn `c7d30b1fa12a` closed at 536870957 with three errored receipts below. |

Earlier cold attempts reached readiness at 163,057 ms (parent PID 76252) and
166,459 ms (HEAD PID 77073), but their launcher tool sessions exited and the child
JVMs disappeared before first-turn observation. They are not first-turn proof.
A parent warm start (PID 78652, 8,355 ms, one recovered turn) was used for initial
diagnosis, then replaced by the zero-recovery cold proof above. Final launchers
used `bin/seon --root <scratch> reset --force; read -r receipt_hold` in retained PTYs.

The configured bootstrap provider ran normally. These were the same shipped task,
not identical stochastic replies. No additional provider experiment was requested;
the controlled `(my.note/?)` probes were local SCI evaluations with no provider.

## Exact receipt evidence

The original lane's scratch store was already deleted. Its retained landing note
says two prose-only replies and unresolved `my.note/?`, but does not retain those
three full receipt records. Their original IDs, exact text and frames cannot be
recovered from that note. The records below are fresh observations, not invented
reconstructions of the deleted receipts.

Parent cold turn `c7d30b1fa12a`:

| Receipt / ordinal | Authored source / message | Declared error and owning boundary | Recorded frame |
| --- | --- | --- | --- |
| `29d2b0ed4bb5` / 4 | `([:cat [:vector [:map]]] :map)` → `Key must be integer` | `:seon.sci.kernel/error`; operation `seon.sci.kernel/failure-value`, layer `:seon.sci.kernel/evaluation` | `sci.impl.analyzer$return_call$reify__82500/eval`, `analyzer.cljc:1818` |
| `7c372f2cff64` / 9 | Prose starting `] but that requires :example/amount...` → `Unmatched delimiter: ]` | `:seon.sci.reader/unreadable-error`; operation `seon.sci.reader/error-value`, layer `:seon.sci.reader/source` | `seon.sci.eval/evaluate$fn$fn$fn$fn`, `eval.clj:3035` |
| `b59b85425003` / 5 | `(defn largest ... [rows] ...)` with literal body `...` → `Unable to resolve symbol: ...` | `:seon.sci.kernel/error`; operation `seon.sci.kernel/failure-value`, layer `:seon.sci.kernel/evaluation` | `sci.impl.utils/throw-error-with-location`, `utils.cljc:67` |

Schemas/operations above come from the installed producer declarations and saved
triage, not from a stored discriminator: `src/seon/sci/kernel.clj:532,563`,
`src/seon/sci/reader.cljc:12`, `resources/seon/schemas/seon.sci.kernel.edn:15`,
`resources/seon/schemas/seon.sci.reader.edn:18`.

The earlier parent warm run also recorded receipt `89787bf340e9`, turn
`355fab5ff381`, ordinal 1, source `;; check what attributes exist in my.* namespaces\n`.
Message: `Your reply had no form; only comments/prose. Send a form.` Saved value
has `:seon.sci.eval/reader-event-count 0`, operation `seon.sci.eval/one-event`,
layer `:seon.sci.eval/reader`; declared schema is
`:seon.sci.eval/reader-event-count-error` (`seon.sci.eval.edn:194`). Triage is
`seon.sci.eval/evaluate$fn$fn$fn$fn`, `eval.clj:3035`. Complete envelope took 6 ms.
This actual receipt proves the prose-only class exists before commit 2.

The exact missing-name probe `(my.note/?)` on the parent cold SCI context returned
`Unable to resolve symbol: my.note/?`, 344 ms evaluation / 475 ms envelope.
The installed namespace rows list only `my.note/add!`, `my.note/notes`, and
`my.note/forget!`. A separate 5 ms producer probe confirms operation
`seon.sci.kernel/failure-value`, layer `:seon.sci.kernel/evaluation`, declared
`:seon.sci.kernel/error`, and `sci.impl.utils/throw-error-with-location`,
`utils.cljc:67`. Its supplied zero-duration record is normalization input, not a
measurement of an agent evaluation. No placeholder function is warranted.

HEAD later recorded `d7809de8935b` for
`(my.agents.root/search-source "example/amount")`, with unresolved-symbol message
and the same kernel boundary / `utils.cljc:67` frame. The final store read saw one
error; the later status saw two as the agent continued. These are different
observations, not an atomic census; the second late receipt was not pulled before
shutdown. The original three-error cardinality did not reproduce identically.

## Unknown ping: exact observation and held owner

Both versions report `:health :observed`, all readiness layers, and replying
mailbox, schedule, armer and render procs. Only `:seon.agent/turn` was unknown.
Final HEAD oversight: episode-runs 13, mailbox passes 48, armer passes 15, render
passes 455; baseline: episode-runs 6, mailbox passes 27, armer passes 8, render
passes 533. The rows do not contain a turn pong/pass count. Do not infer one.
A direct HEAD Flow ping with a 5,000 ms window also returned unknown for turn
(5,009 ms envelope); this is not a health pass.

Dependency guarantee: core.async `flow/impl.clj:276-305` handles ping on the same
proc loop that invokes the transform. Its `:76-87` ping window returns only actual
replies. `src/seon/oversight.clj:95-113,119-125` preserves a missing reply as
unknown. A transform in progress cannot process the queued control command.
No stored oversight row promises otherwise; these rows are derived observations.

The retained parent thread dump has the turn in
`seon.turn/system-plan:2039 -> seon.db/read-evidence-changes:1072 ->
read-evidence-current?:1120 -> index-evidence-current:1101 ->
index-pattern-change:1052 -> Datahike temporal_datoms:268`.
HEAD dumps show `seon.turn/system-turn:2270 -> seon.db/transact-call:4290` waiting
for the writer. The writer was in Datahike `retract-entity:1002` in one dump and
index insertion/transaction retry in the final dump. Boot's longer cost was also
positively sampled in `seon.db/write-owned-values-error:3740`.
These identify active work, not a deadlock, termination failure, or acquisition
exception. Stack samples do not assign an entire elapsed interval to one function.

`src/seon/db.clj` is held by the running database lanes. The smallest already-ruled
change for the observed currency scan is A2 c1: use the supplied dependency
revision comparison and remove `index-evidence-current`'s historical scan before
it, with the existing system-turn consumer converted coherently. See
[the A2 owner](../plan/lane-a2-datahike-one-answer.md).
Do not increase the ping window or label absent pongs healthy. System-turn
transaction cost remains a separately measured owner concern; this note does not
claim c1 alone guarantees the requested all-pong checkpoint. No held file was
edited. `cluster/agent.clj` also acquired foreign edits during diagnosis; they were
preserved.

## Repair and verification boundary

The existing regression
`test/seon/cluster/turn_test.clj:1817`,
`a-prose-only-provider-reply-settles-without-parking-the-turn-proc`, deliberately
asserts an errored receipt for prose-only input. A zero-error assertion on that
same input would contradict surviving behavior. The producer at
`src/seon/sci/eval.clj:493-527` refuses zero reader events; the namespace API at
`src/my/note.clj:12,25,42` never declares `?`. These owners are unchanged by commit 2.
No evidence supports weakening either boundary or adding an acquisition guard.

No production repair or fail-before/pass-after regression was authored. No
`bin/test`, cold gate, platform suite, test-fast run, reload or default adoption
was performed. Requested first-turn execution was observed on both cold clusters;
requested zero-error/all-procs-pong whole-cluster HEALTHY remains **unproved**.
This is the bounded diagnosis stop at the held database work, not a green landing
of a repair.

## Raw evidence and cleanup

`tmp/orchestrator/receipt-errors-evidence/` retains exact read forms
(`read-store.clj`, `read-prepl.clj`), full store reads, MCP envelopes, status rows,
boot logs, commit diff and thread dumps. In particular:
`beforeErrors.json`, `beforeMissingNote.json`, `beforeMissingNoteOwner.json`,
`before-cold-store-4.edn`, `head-store-2.edn`, `head-store-3.edn`,
`head-final-store.edn`, `before-final-store.edn`, `headFinalStatus.json`,
`beforeFinalStatus.json`, `headBoundedPing.json`, `before-turn-threads.json`,
`head-turn-threads.json`, and `head-final-threads.json`.

Both final `down --force` calls verified their exact PID/start pair and returned
`:process-exit? true`. Both retained PTY shells then exited 0. `ps` found none of
75144, 76252, 77073, 78652, 79111 or 81159; `lsof +D` returned empty/status 1 for
the scratch root and both checkouts before deletion. Logs and evidence were copied
out first. Recursive cleanup does not traverse dependency symlinks. Repository
default PID 51528 was never stopped or reset.
