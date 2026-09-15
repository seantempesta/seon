---
type: issue
status: resolved
severity: friction
tags: [issue, test, reader]
created: 2026-09-15
---

# Turn continuation test expects the retired no-form text

Run4-blockers' HEAD-plus-owned-paths fast gate at `f9ed564ef` reached
`seon.turn-continue-test/accepted-provider-replies-continue-until-done-refusal-or-bound`.
Its prose, empty and comment-only scenarios each fail two assertions because
the committed test expects the old message, while the stored error and next
prompt contain the reader fix from `4a559d658`:

`Your reply had no form; only comments/prose. Send a form.`

For example, the empty scenario still expects
`The reply carried no Clojure forms.`. The complete rendered evaluation is
present in the next prompt; the additional substring check searches for the
retired message. The other continuation assertions pass.

At observation time another lane had an uncommitted correction in
`test/seon/turn_continue_test.clj` replacing the conditional old strings with
the new message. Run4-blockers preserved that hunk and did not include it in
its owned-path snapshot. Acceptance: land the owning correction and run the
namespace with armed contracts.

Resolved by the owning correction `e7bf60541`. Run4-blockers' isolated gate
at `dabd311d0` plus its owned paths passed 16 tests / 434 assertions,
including the complete continuation namespace, with zero failures and errors.
