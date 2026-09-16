---
type: issue
status: open
severity: friction
tags: [issue, test, render, wave/test-fixture]
---

# Fixture branch keywords cannot be read back as EDN

## Problem

The canonical fixture can name a database branch with a keyword whose
numeric name is not readable EDN. Structural database identity output then
cannot be checked with the EDN reader.

## Evidence

2026-09-15, default's in-process canonical fixture:
`seon.render-simplification-test/nested-ai-values-retain-data-and-html-uses-declared-faces`
first attempted to parse its structural output. Run eid 67103 recorded
`Invalid token: :seon.test-support.fixture/1`. The database identity carried
`:db-name :seon.test-support.fixture/1`.

This was a test-fixture value, not an ordinary cluster name. The render test
now checks those attributes without claiming that this keyword is readable.
The test-support owner was concurrently edited and was not changed.

## Owner

`test/seon/test_support.clj` branch naming. Derive a reader-valid name
from the fixture identity.

## Acceptance

The canonical fixture's database identity survives ordinary EDN print/read,
and parallel fixtures retain distinct branch identities.
