---
type: prd
status: active
tags: [prd, agent, database]
---

# Reorg + cleanup PRD (2026-06-10)

Companion to [[context-v3-code-first-2026-06-10]] (the WHY and the render
rules) and [[ns-audit-2026-06-10]] (the per-ns evidence table). This doc is
the HOW: every move, batched for minimum total work — fewest suite runs,
fewest agent launches, orchestrator does the mechanical work directly.

## Principles

1. **Names carry the classification.** After this PRD executes, the v3
   classifier needs NO per-ns exceptions beyond the shrinking split-pending
   set: `seon.db`/`seon.schema`/`seon.repl` = language (shown),
   `seon.agent.*` = toolbelt (shown), `*.internal` = hidden, `my.*` =
   agent-owned (shown cluster-wide), web/dev = not in relevant roots
   (hidden).
2. **Batch by mechanism, not by theme.** All deletes are one commit; all
   mechanical renames are one commit. One suite run per batch, not per ns.
3. **Mechanical recipes** (proven, see context-v3 §method): renames are
   `mv` plus one perl sweep over an explicit file list, then suite, then
   fix named fallouts. Splits = `cp` whole file → two subtractive delete
   passes → re-point callers → suite. Never re-author working code.
4. **Orchestrator executes batches 1–2 directly** (mechanical; agent
   launches cost more than the work). Agents execute splits and V3-C
   (semantic judgment required).
5. **One cluster reset at the end** of the batches — clears stale ns rows
   (old `seon.todo`, deleted nses) before the resume test. Cheap by
   decision; do it once, not per batch.

## End-state namespace map

| Layer | Namespaces | Rendered? |
|---|---|---|
| Language | `seon.db`, `seon.schema` (post-split face), `seon.repl` | YES |
| Toolbelt | `seon.agent` (post-split face), `seon.agent.todo`, `seon.agent.fs`, `seon.agent.search`, `seon.agent.inspect` | YES |
| Agent-owned | `my.*` (code), `my.kb.*` (knowledge), `my.agent.<id>` (home) | YES (cluster-wide) |
| Internal | `seon.db.internal`, `seon.eval(.internal)`, `seon.ctx(.internal)` (V3-C home), `seon.warn`, `seon.render(.*)`, `seon.handlers.*`, `seon.log`, `seon.parse` → `seon.repl.internal`, `seon.store.wire`, `seon.store.internal.wire-node`, `seon.store.internal.cbor`, `seon.analyzer-info`, `seon.platform`, `seon.indexing`, `seon.agent-view` | no |
| Web-UI (future own-process reader, §7.10) | `seon.web.serve`, `seon.web.inspector`, `seon.ui.*`, `seon.agent-view` | no |
| Dev/test-build only | `seon.dev.*` (true dev after batch 2), test preloads | no (verify not in :client where avoidable) |
| Later promotion | `seon.agent.test` (after seon.test.runner face/internal split, 35k) | face only |

## Batch 1 — DELETE (one commit, orchestrator)

User-approved: `seon.agents` (+ test), the wasm graveyard, and `seon.code`
— the last **conditional on planned correct replacements** (user,
2026-06-10), which are: (a) convention checking = the B3 item (PRD §9:
warn-registry checks re-homed as a location-aware Malli walk in
`seon.dev.compliance`, exposed as a wire op returning the clustered
warning shape — the reactive, current-law successor to seon.code's
hard gate; stays a named queue item, not a someday); (b) the
cross-agent/cross-cluster PUBLISH GATE — re-spec'd fresh against
current conventions when multi-cluster code sharing becomes real
(cluster-runtime item §7.10c is its trigger). seon.code's rulebook
predates the 2026-06-08 positional-args law and would refuse legal
code; git history preserves the parser utilities.

- `src/seon/agents.cljs`, `test/seon/agents_test.cljs`
- `src/seon/code.cljc`, `test/seon/code_test.cljc` — AND edit
  `test/seon/eval/detect_tee_test.cljs` (requires seon.code; remove the
  require + any gate assertions — read first, it may need 2-3 test edits)
- wasm graveyard: `src/seon/wasm_smoke.cljs`, wasm-eval-smoke ns,
  `:smoke`/`:eval-smoke` shadow builds, konserve-sqlite-cljs ns,
  `guest-cljs/` tree, `:guest-agent`/`:v0-probe`/`:cljs-guest` deps
  aliases, `seon.dev.wire-sync`. KEEP `replica-probe`/`replica-peer`.
- `seon.web.page` (dead — serve-root 302s past it; verify no require)
- relocate `src/seon/ui/html_test.cljc` → `test/` (mislocated)
- test-preload roster + shadow-cljs.edn + deps.edn entries for all of the
  above.
- Oracle: suite + `bin/seon restart all` boots clean + /agents 200.

## Batch 2 — RENAME sweep (one commit, orchestrator, perl recipe)

- `seon.dev.wire-node` → `seon.store.internal.wire-node` (+ inline the
  one `agent-id` fn it borrows from `seon.dev.node-agent`)
- `seon.dev.cbor` → `seon.store.internal.cbor`
- `seon.fs` → `seon.agent.fs`; `seon.search` → `seon.agent.search`
  (+ `seon.search-test` → `seon.agent.search-test`) — absorbs task #13;
  exemplar-roots + gym fixtures updated in the same sweep
- `seon.inspect` → `seon.agent.inspect`
- `seon.parse` → `seon.repl.internal` (audit candidate; same sweep)
- Affected-file lists derived per ns by grep BEFORE the sweep; regex
  literals double-checked (the todo rename lesson).
- Oracle: one suite run + restart all + one rendered-context probe
  (exemplars present under new names).

## Batch 3 — semantic units (agents, existing task order)

1. #11 entity-marker `{:seon.db/entity true}` (schema.cljc + sweep)
2. #12 home-ns → `my.agent.<id>` (3 mints + prompt text + rust + tests)
3. #14 `my.kb` scaffold + `my.kb.instruction` first domain
4. #16 V3-C → `seon.ctx` (one query/classifier/renderers; deletes the
   remaining legacy filters; agreement property test) — **DONE
   2026-06-10 evening**: `seon.ctx` carries the classifier
   (`context-model`: *.internal hidden / my.* shown / agent-tx
   provenance / `relevant-roots`), the merged composer
   (override-by-name, render guard, 8k agent-section budget), the
   `:purpose` + `:your-sections` seeds; verbs
   `add-section!`/`remove-section!`/`set-purpose!` on `seon.agent`;
   slots relaxed to `[:or :string :symbol]` / `[:or :symbol <hiccup>]`
   via the bridge's EDN-string storage (mixed-:or →
   `:db.type/string`, pr-str/read-string); `:seon.ctx/fn` deleted;
   structural first-party boundary (source under repo root) replaces
   the name prefixes in `seon.indexing` + `seon.instrument`;
   `substrate-ns-name?`/`exemplar-ns?` deleted (one `relevant-ns?`
   def). Cluster store reset once (slot valueType change). Suite
   325/1360/0; resume + purpose + guard live-proven. Deferred:
   growing `relevant-roots` to the post-split faces (context-size +
   gym check first); the exclusion-set death-condition test rides
   with that growth.
5. #18 splits, copy-then-delete: `seon.eval` face/internal,
   `seon.schema` face, `seon.warn`, then `agent.cljs` →
   `seon.agent` + `seon.agent.internal` (after V3-C removed its context
   third). `seon.test.runner` → `seon.agent.test` face last.
- Gym trio re-run after #16 and after the agent.cljs split (the two
  renders-affecting steps); not per batch.

## Remaining DECIDEs (from the audit, small — answer any time)

- `seon.dev.node-agent`: after agent-id inlines, dev-only probe — keep
  (dev) or delete (probe era over)?
- `seon.log/tail` is agent-usable — promote a `seon.agent.log` face
  later, or leave agents reading via `seon.agent.fs`? (audit d5)
- `seon.handlers.wake` is a trigger misfiled with renderers — relocate
  when `seon.ctx` forms (no rush).
- `seon.handlers.system-prompt` — deletable when V3-E's
  `my.kb.instruction` supersedes the sticky preamble.

## Execution + spend notes

Batches 1–2 are orchestrator-direct: ~zero agent tokens, two suite runs,
two commits. Batch 3 = 5 agent units (unchanged from the board). The
cluster reset + resume test (#15) run between batch 2 and batch 3's #14
(reset clears stale rows; resume test proves replay on the post-rename
substrate — strictly better coverage than pre-rename).
