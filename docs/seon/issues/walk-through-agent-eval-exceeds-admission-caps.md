---
type: issue
status: open
tags: [issue, render, sci, test]
---

# `seon.render/walk` through an agent eval exceeds admission caps

`seon.sci.eval-test/public-walk-is-callable-through-an-agent-sci-eval`
fails on the CLEAN tree (verified 2026-08-01 by stashing unrelated
edits and running `bin/test seon.sci.eval-test`: 1 failure, 1 error at
`test/seon/sci/eval_test.clj:351`). The walk's string result now
exceeds `:seon.config.eval.result/max-string` 4096 and admits as a
`:seon.sci.admit/truncated-string` map where the test expects a plain
string.

Pre-existing, not introduced by the 2026-08-01 REPL-surface commit.
Two readings to settle at the owning seam:

- if the walk is EXPECTED to fit caps for an agent-facing call, the
  walk grew and the regression caught real bloat — fix the walk size;
- if capping is the correct admission behavior for a large walk, the
  test asserts the wrong shape — assert the truncated-string envelope.

Owner: `seon.render/walk` + the admission caps contract. Acceptance:
the test states which behavior is the contract and passes for that
reason.
