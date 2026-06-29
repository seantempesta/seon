---
type: research
status: active
tags: [research, agent, orchestrator]
---

# Core lane — night report (2026-06-28 → 29)

The recursive self-improvement loop ran all night against the CLJS pod and
delivered — every claim live-proven on the running system, not inferred. ~21
Core commits. The agent's context, the tool suite, the multi-agent layer, and
schema stability are all measurably tighter; UI's capstone reset + comprehensive
re-drive validated it (toolkit composable, fabrication fixed, delegation
end-to-end). Pairs with UI's `lane-u-night-report-2026-06-28.md`.

## Landed + live-proven (grouped)

- **Discoverability** — `#55` render the home-ns require with real aliases → a
  fresh agent's transcript **20,315 → 4,216 tok (5×)**, the 24-grep verb-flail
  gone (`discoverability-drive`). The single biggest transcript lever — NOT
  clipping (the waste was discoverability flailing, not legit history).
- **Fabrication** — pending-Promise stash self-heal (`8f2f8c50`); report=DATA /
  message=POINTER contract + EOF-truncation guidance (`#73`, `878351ce`).
- **Concise tools** (the ~15k/turn token explosion = unoptimized tool output):
  grep 4,219→724 tok 5.8× (`53550b0e`), **grep-graph** over the program graph
  (`a95db4fa`), store-inventory topology JOIN-MAP (`a24c2fbe`), render-namespace
  member-drill 4,026→264 (`6c85a193`) — all on one shared concise formatter.
- **Context efficiency** — `#42` namespaces **−43%** (`55cd5002`) + the
  drive-CAUGHT `my.data` adoption regression fixed (`c8f064e6`); `#77` toolkit
  boot-index + `canonical-full-my-ns` (`960cb489`); system-text canvas-first +
  de-KIND (`a850a804`); verb-friction steering — in-ns no longer destructive
  (`1809e9ad`); open-todos cache-stable (`d006e1e3`).
- **Multi-agent** — cron now ACTION-driving, fires run its `:fn` without burning
  a turn (`#66`, `79a533f1`); `start!` arms the child in-process + returns a
  usable id + discoverable (`#72/#30`, `67d55aa1`); `delegate!` one-form combinator
  (`#78`, `8ec70b7e`); hop-cap per-pair + named dead-letter (`#79`, `ed39aa02`).
- **Stability** — registry-stomp window closed STRUCTURALLY with a `registry*`
  watch (`#76`, `565ace0`) → fixes `my.*` fn-row sparsity + the message `from`
  "schema bug" + intermittent `:malli.core/invalid-schema`; `:seon.db/db-val`
  documented as two intentional faces, NOT a removable dup (`#65`, `c4fe1724`);
  `index_core_test` de-flaked (`#75`); `seon.result`/`seon.items` envelope (`#62`);
  wrong CLJS-async comment corrected (`#63`).

## PROVEN (UI's capstone reset + re-drive)

- Toolkit **composable** (`my.ui` 0→9-11), **fabrication fixed** (`960bfa38`).
- Multi-agent **delegation completes end-to-end** — worker stores findings as
  `my.kb` data → reports a pointer + `complete` → parent queries + synthesizes a
  data-grounded answer with REAL datom counts (`delegation-redrive-2026-06-28.md`).

## PENDING — rides the NEXT cluster reset

Mechanism fixes (hop-cap, self-heal, cron, registry guard, tools) are ALREADY
live. Only **program-graph-rendered guidance** waits for a re-index:
- `delegate!`'s namespaces-block signature + the corrected `start!` docstring
  render from stored `:seon.fn` rows → live for agents only after
  `bin/seon cluster reset default`. (The system-text bullet is already live.)
- **Then**: a multi-round delegation re-drive validates `#78`+`#79` end-to-end.

## OWNER DECISIONS / flags

- **`#19` placement**: open-todos is now byte-stable but priority 45 (volatile
  tail, below `stable-priority-max` 20). To actually ride the cached prefix it'd
  need priority ≤20 — an ordering call, not changed silently.
- **transcript-eviction tiering**: still the deferred boundedness BACKSTOP
  (re-scoped tonight — discoverability already cut transcripts 5×; clipping was
  never the lever).
- **Held for owner review (not done unattended)**: `#21` build-derived program-graph
  indexing (delicate boot path), `#52` paren-balancer refactor (risky parse path).
- **clojurescript skill note (→ U)**: `(.then promise <bare-keyword>)` is a SILENT
  no-op in CLJS (a keyword isn't JS-callable) — bites anyone using a keyword as a
  Promise handler.

## NEXT

1. A cluster reset (UI's or owner's) → re-drive delegation to validate `#78`/`#79`
   + confirm `delegate!`/`start!` guidance is live.
2. Resume the backlog worst-first, preferring live-immediately mechanism fixes
   (`#64` print-als ALS, `#49` MCP-eval misreport, `#48`, `#27`, `#28`, `#71`).
3. Keep driving realistic tasks — every drive tonight surfaced the next real bug.

## UPDATE (post-report, ~04:50)

- **The token-efficiency mission is DONE — proven by a tool-usage drive**
  (`tool-usage-drive-2026-06-29.md`): every concise tool renders tight (grep 869,
  grep-graph 416, store-inventory 1044 w/ JOIN-MAP, render-namespace 488); no
  floods; the JOIN-MAP killed the turn→run→agent spinning. **Conciseness is no
  longer the binding constraint.**
- **The next frontier is HONESTY + TOOL-REACH.** Fabrication has now hit FOUR
  drives — the agent reports figures contradicting its OWN query (`root=39` while
  its query returned `XeG=69 / 241 total`), labeled "Independently verified," and
  hallucinates schema attrs (`:seon.agent.run/state` vs real `…/status`) that sit
  in its own inventory block. It also UNDER-calls the tightened tools (zero grep /
  store-inventory in that drive).
- **#1 honesty fix BUILT** (`#80`, `ddb5ccb1`): the **cite-card** — a derived
  "values you JUST computed — cite THESE" surface in the last tokens before the
  readline cursor. Live-proven it renders the exact figure root fabricated.
- **Needs-reset batch** (committed + safe; live for agents only after the next
  `bin/seon cluster reset default`): cite-card (`#80`), `delegate!` signature +
  `start!` docstring (`#78`). A post-reset re-drive then validates `#78`/`#79`/`#80`
  together. Didn't reset overnight — the **diffusion track is actively using the
  pod** (eval/repl), so a reset would disrupt it.
- **Honesty-frontier follow-ons** (the next focus, mostly context/render so they
  ride a reset): a read-before-you-summarize / tool-reach always-on nudge;
  schema-attr grounding (read the inventory, don't guess attrs); the parser
  prose-parenthetical noise (Core eval/parse — diffusion-contended, coordinate).
- **Recommended owner action**: when you're ready, one `cluster reset default`
  lands the needs-reset batch + lets a re-drive validate delegation (multi-round)
  and the cite-card's effect on fabrication.
