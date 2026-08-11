---
type: issue
status: resolved
severity: friction
tags: [issue, test, blob, render]
---

# Blob economics test used a now-windowed string

## Problem

`full-stored-shape-decides-an-eligible-result` intended its first case to
compare an inline result with an equal-sized rendered window. After
`e34eea186` made string elision part of the total render profile, its vector
containing one 4,000-character string instead produced a 2,041-character
window from a 4,098-character stored result. Blob plus window was correctly
smaller, so the stale fixture contradicted its own economic assertion.

## Evidence

The direct settlement probe measured `result-count 4098`, `window-count 2041`,
and `result-blob-smaller? true`. A 4,050-digit integer produces equal 4,098-byte
stored and window representations, for which the same derived comparison is
false.

## Resolution

Commit `b532ce7c8` uses that unwindowed ordinary value for the equal-size case
and retains the repeated-string vector for the blob-beneficial case. The
production comparison was correct and unchanged. The focused five-namespace
gate passed 38 tests and 313 assertions.
