---
type: issue
status: resolved
severity: friction
tags: [issue, reader, errors, prompt, live-test]
created: 2026-09-15
---

# Reader error texts make the model believe the reader keeps a buffer across turns

## Observed (run 4; the model's account in `research/explain_probe_run4_2026_09_15.edn`)

Stored evaluations show the actual causes: a reply that was prose quoting
help text with a stray `)` → "Unmatched delimiter: )"; two replies that
were comments only → "Evaluation requires exactly one reader event."
The reader is stateless per reply. The model concluded the opposite:
"My previous replies that had a trailing `)` left the reader in a broken
state", "Let me try to flush", "send the exact missing token sequence",
and finally replied `:reset`. Four turns lost to a belief the error texts
invited.

## Wanted

- "Unmatched delimiter" names the reply text around the delimiter and says
  the reader reads each reply from scratch: nothing is buffered between
  turns.
- A comment-only or prose-only reply says "your reply had no form; only
  comments/prose. Send a form." — never "requires exactly one reader event".
- Both are agent errors in the agent's history (they already are); the
  texts are the fix. Regression: the two exact run-4 replies produce those
  messages.

## Resolution — 2026-09-15

Reader delimiter diagnostics now quote the complete containing reply line
and state that each reply is read from scratch. Both reply splitting and
single-form evaluation report comment-only input as:
`Your reply had no form; only comments/prose. Send a form.`

The original run-4 source strings are saved in `test/seon/run4_replies.edn`.
The canonical armed regression verifies both messages and then evaluates
`(+ 1 1)` successfully in the same SCI context. Reader recovery and the
fabricated-response guard are unchanged.

Fast gate: 51 tests / 416 assertions; isolated gate: 51 / 420. Both have
12 failures confined to the separately recorded help-trial scoring defect;
reader, reply, and REPL grammar tests pass. Live JVM evaluation on default
observed the new strings without restarting the cluster.
