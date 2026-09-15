---
type: issue
status: open
severity: friction
tags: [issue, agent, docs, wave/schema-audit]
---

# Teach that every prose line needs a comment marker

The 2026-09-15 refusal-grammar change groups a contiguous unreadable prose
span into one reader error and says `Prose must start with ; on every line.`
The remaining teaching change belongs to the opening's comment sentence in
`src/seon/cluster/instruction.clj`, protected by concurrent edits during this
lane. Add: “Prose lines are comments only when every line starts with `;`;
a comment ends at the newline.” Verify the generated opening text.

This is the explicit residual from
`a-prose-line-without-a-comment-marker-becomes-one-error-per-word.md`.
The reader's completed proof and exact text are in
[the refusal landing](../../prds/context-generation/research/refusal-grammar-2026-09-15.md).
