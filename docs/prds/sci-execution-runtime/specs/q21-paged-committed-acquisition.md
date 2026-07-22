---
type: prd
status: active
tags: [prd, architecture, database]
---

# q21 — paged committed-program acquisition

## Grounding preamble (mandatory)

Don't make things up: read the actual source of every file you touch and
every interface you connect to before editing. Report in your summary:
(a) a better seam if you find one; (b) the existing owners' exact terms.
**Stopping early to report is FREE.** If source contradicts this spec,
stop and report.

Read FIRST — it is this unit's complete interface ledger and decision
record; every claim is file:line-grounded there:
`docs/prds/sci-execution-runtime/research/q21-committed-acquisition-grounding-2026-07-21.md`.

## Goal

`seon.runtime.admission/acquire-committed-projection!`
(`admission.cljs:232`) currently fetches every schema form and function
contract in ONE `execute-many` response — unbounded, it already exceeds
64 KiB and grows with the program corpus. Replace its internals with
cursor paging over the EXISTING mechanisms (no new read path): enumerate
`[:seon.schema/key]` and `[:seon.fn/sym]` identity attributes through
AEVT `index-page` pages at the ONE frozen database value, bounded
`pull-many` per ID page for exactly the required pairs (omit functions
lacking `:seon.fn/spec` so the set matches today's presence query),
continue until both streams are `complete?` (boot requires
completeness — no maximum-page stop), reassemble the IDENTICAL
`{schema-rows, function-contract-rows}` shape, and hand it to the
unchanged projection compiler. Atomic admission, validation,
instrumentation, generation fingerprint: all unchanged. The single
consumer is `reconcile-committed!` — no public shape change.

## Mandatory ordering (the grounding's own falsifiers)

1. FIRST (pre-implementation probe): at one frozen database value on
   the live default cluster, Transit-encode the exact response
   envelopes for candidate page sizes (1, 2, 4, …) and separately the
   LARGEST single schema row and contract row (Transit serialize +
   UTF-8 byte length — the framing operation, `uds.cljs:178`). Report
   the numbers. If one individual row exceeds the 65,536-byte minimum
   supported frame, STOP and report — the unit then needs the typed
   oversized-row error decision (grounding §4 "Required qualification")
   made explicitly, not silently.
2. Implementation with these gates baked into tests:
   - paged-scan ≡ today's two queries at the same database value (set
     equality + identity uniqueness) — falsifier 2;
   - final filtered symbol set ≡ the `:seon.fn/spec` presence query —
     falsifier 3;
   - every outgoing page/pull request carries the same complete
     `:seon.db/db` map — falsifier 4 (no generation mixing).
3. Choose page sizes conservatively from the probe so every page
   response is provably under the 64 KiB floor with margin; the page
   size may be a named config accessor if an existing config family
   fits (do not invent a new config namespace for it).

## Owned paths (touch nothing else)

- `src/seon/runtime/admission.cljs`
- its test file(s) (`test/seon/runtime/admission_test.cljs` + any
  fixture the equality gates need — enumerate)

Protected: everything else — especially `seon.db`/protocol/transport
(you are a CONSUMER of index-page and pull-many; if they lack something
you need, stop and report), `host/context.clj` (its duplicate
acquisition is q22, queued separately), and the writer. Other lanes are
active; no `bin/seon` lifecycle ops. The live default cluster is up for
the read-only probe via MCP eval.

## Gates (run them; report honest results)

- Focused admission selectors, then full `bin/test-cljs` once.
- LIVE PROOF on the default cluster (read-only + one boot exercise is
  allowed at the END if needed): the strongest honest proof available
  without a cluster reset — at minimum, evaluate the new acquisition
  path against the live database value via MCP eval and show the paged
  result equals the current-mechanism result. Report what you could
  and could not prove live.
- The 64 KiB end-to-end boot proof (small ceiling + successful boot)
  belongs to a LATER coordinated checkpoint with the orchestrator —
  note it as pending, do not run it yourself.
