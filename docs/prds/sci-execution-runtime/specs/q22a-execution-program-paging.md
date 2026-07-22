---
type: prd
status: active
tags: [prd, architecture, database]
---

# q22a — the execution child's program acquisition pages and preserves errors

## Grounding preamble (mandatory)

Read the actual source of every file you touch before editing. Report:
(a) a better seam if found; (b) the owners' exact terms. **Stopping
early to report is FREE.** If source contradicts this spec, stop and
report.

Read FIRST, all three: the audit
`docs/prds/sci-execution-runtime/research/live-turn-frame-defect-2026-07-22.md`,
the open issue
`docs/seon/issues/unbounded-runtime-acquisitions-exceed-frame.md`, and
the landed paging precedent in `src/seon/runtime/admission.cljs`
(q21: index-page cursor enumeration + bounded pull-many batches of 32
at ONE frozen database value, set-equality falsifiers).

## Goal (the turn-breaking slice of q22)

1. **Page the acquisition**: `src/seon/execution.cljs`'s complete
   authored-program + configuration acquisition (~422 KB in one
   `execute-many` at the audit's database value; unsafe extraction at
   `:718-:725`) converges on the q21 precedent: enumerate identity
   attributes via `db/index-page` cursors, bounded `pull-many`
   batches, ONE frozen database value throughout, identical final
   program shape for the unchanged consumers. Conservative page size
   provably under the 64 KiB minimum frame with margin (probe first,
   report sizes — the admission numbers suggest 32 works).
2. **Preserve top-level errors**: the nil-`subvec` crash dies — a
   top-level database error (frame-too-large or any other) becomes an
   honest `:seon/error` value through the child's existing
   error-as-value path, never a type crash. Same discipline at every
   member-result access this acquisition touches.
3. **Falsifiers**: paged ≡ current at one frozen basis (set equality,
   the q21 idiom); an injected frame-too-large yields the structured
   error (not IVector); page responses probe under the floor.

NOT in scope: the namespaces/warnings ctx renderers, host context
sentinel, web value (later q22 slices).

## Owned paths (touch nothing else)

- `src/seon/execution.cljs`
- its test file(s) (enumerate)

Protected: everything else. No commits, no lifecycle ops (another lane
owns `src/seon/host/*`). Live read-only probes via MCP allowed.

## Gates

Focused execution selectors, then full `bin/test-cljs` once (baseline
1503/7251 — record after).
