---
type: issue
status: open
severity: friction
tags: [issue, agent]
---

# find-recovery-point can leak an EXECUTING inner form from a broken form

## Problem (found by adversarial review, 2026-06-28)

`find-recovery-point` recovers a parse failure at the next column-0 `(` OR `;`
(regex `\n[;\(]`). The `;` anchor is intentional ("intent attaches to the next
form"). But when a **broken (unclosed) form contains an UNINDENTED `;;` comment**
followed by an inner call, recovery splits at that interior `;`, and the inner
call is then parsed as a **top-level form that EXECUTES**.

Repro (REPL-confirmed):
```
"(defn foo []\n;; do the thing\n  (bar)"     ; unclosed defn, column-0 ;;
  → [:read :form]        ; (bar) is extracted and RUNS as a top-level call
```
A pure `(bar)` is harmless, but a side-effecting inner call (e.g.
`(db/transact! …)`) inside a syntactically-broken form would FIRE — broken code
partially executing.

## Scope / severity

- **Pre-existing** — the `;` anchor predates PRONG 2 (which only removed `[`/`{`
  from the anchor set). Not a regression from this session's commits.
- **Mitigated in practice** — agents usually INDENT internal `;;` comments, and
  `\n[;\(]` requires the `;` at column 0 (`  ;;` does not match). So it bites
  only on an unindented interior comment in an already-broken form.
- Real risk = side effects from an inner call in broken code.

## Candidate fix (needs design care — why it's filed, not auto-fixed)

Make recovery **error-kind-aware**: for an `:eof` failure (the form is UNCLOSED),
everything after the failure offset is *inside* the unclosed form until a
balancing close — so an interior `;` is NOT a real new-form boundary. For `:eof`,
recover only at a column-0 `(` that the agent plausibly meant as a new form, or
at EOF — never at an interior `;`. For non-`:eof` (localized) failures, keep the
`;` anchor (the "intent attaches to next form" behavior).

The subtlety: `find-recovery-point` currently runs BEFORE classification (text +
offset only), so it would need the error-kind threaded in; and the "new form vs
inside the unclosed list" boundary is genuinely ambiguous for `:eof`. Get the
no-regression corpus (`internal_test.cljc` `recovery-cases` +
`narration-attaches-to-failure-not-next-good`) to stay green before/after.

## Acceptance criteria

- An unclosed form with a column-0 interior `;;` + inner call does NOT emit the
  inner call as an executing `:form` (it stays inside the `:read` span).
- The existing recovery + narration corpus is unchanged.
- Decide the `:eof` recovery boundary deliberately (interior `;` suppressed).

## Links

- `seon.repl.internal/find-recovery-point` + the `:error-kind` already on `:read`
  entries (the classification that would gate this).
