---
type: defect
status: open
severity: friction
tags: [tooling, docstring, context]
---

# The comment-shaped-result guard was inverted; re-arm it

Ruling 45 (2026-08-17) bans comment-shaped results (`;; =>`, `; ⟹`)
everywhere — the pattern teaches agents to fabricate results as
comments and was observed doing so in both implementations. The
docstring checker ONCE FLAGGED stale `;; =>` echoes and was inverted:
`test/seon/dev/docstring_test.clj:104` asserts "a stale `;; =>` echo
is NO LONGER flagged (rule inverted)", implementation in
`script/seon/dev/docstring.clj`. Re-invert: the guard flags any
comment-shaped result echo in docstrings and doc examples; the test
asserts the WANTED behavior. Note the same script-side/indexed-test
boundary as the resolved markdown issue applies to this pair. The
2026-08-17 sweep converted 300+ teaching instances across docs and
the repl skill; the guard is what keeps them from reseeding.
