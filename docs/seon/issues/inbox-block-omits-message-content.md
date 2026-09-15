---
type: issue
status: open
severity: friction
tags: [issue, render, message, test]
---

# Inbox block renders its recipient without the message content

Observed on 2026-09-14 in the canonical web fixture at HEAD `66c8960e7`:
after committing `identity-cache-message` with content “A newly connected
message.” and reading `/agent/root`, the inbox block contains only
“Addressed to” and the root link. Its `data-walk-path` is
`[:seon.message/_inbox]`. The message remains present in the database.

Evidence: `tmp/debug-product/fast-basis-2.log`, the former content assertion
in `a-new-message-does-not-reinvoke-the-identity-pair`. That test now asserts
its stated cache invariant on the ordinary agent page and checks the fixture
message exists; it does not claim the inbox renders correctly.

The message schema pair/walk relationship is the investigation boundary.
The concurrent context-render lane owns the block internals. Acceptance:
the ordinary inbox block shows each addressed message's content and sender,
with a real canonical database and HTML assertion after a new message.
