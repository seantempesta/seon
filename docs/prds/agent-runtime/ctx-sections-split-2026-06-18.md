---
type: prd
status: draft
tags: [prd, agent, flow]
---

# Refactor: split `seon.ctx` into per-section namespaces

## Why

`src/seon/ctx.cljs` is ~2500 lines and growing — it holds the context
composer AND every section fn AND their schemas AND the system-text. It is
the most-contended file in the tree (multiple tracks edit it at once). The
section-twin model (`:seon.render/ai` + optional `:seon.render/html` per
section) makes the natural unit obvious: **a section is a namespace with an
`ai` fn, an optional `html` fn, and its own schemas.**

## The load-bearing fact: sections are symbol-wired, not require-wired

`core-default-ctx` (`ctx.cljs:2404`) lists sections as DATA:

```clojure
{:seon.ctx/name :transcript :seon.ctx/priority 50
 :seon.render/ai   'seon.ctx/transcript-section
 :seon.render/html 'seon.ctx/transcript-section-html}
```

`render-section` / `render-section-html` resolve those symbols LATE via
`seon.eval/lookup-value` at render time. **`ctx.cljs` does not `(:require)`
the section fns.** Consequences:

- Moving a section fn to its own ns creates NO require cycle — the section
  ns requires `seon.render`/`seon.db`/etc. freely; `ctx.cljs` only names
  the symbol.
- The ONLY wiring cost: the new section nses must be loaded (compiled into
  the bundle) so their munged symbols exist for `lookup-value`. A section
  not required anywhere = its symbol silently fails to resolve →
  `render-section`'s "fn … does not resolve" self-heal line.

So the choice "one big `sections.cljs` vs chain off ctx" is a false one:
one big file is just `ctx.cljs` renamed, and there is no require chain to
hang off. The answer is **one namespace per section**.

## Target shape

Sections live DIRECTLY under `seon.ctx.*` — no `.section.` segment.
`src/seon/ctx.cljs` (ns `seon.ctx`, the spine) coexists with
`src/seon/ctx/<name>.cljs` (ns `seon.ctx.<name>`) — the standard
ns-with-children layout.

```
src/seon/ctx.cljs              ; the SPINE only:
                               ;   - schemas: :seon.ctx/section, the
                               ;     assemble request/response
                               ;   - core-default-ctx (symbols-as-data)
                               ;   - merge-sections / agent-sections
                               ;   - render-section / render-section-html
                               ;   - assemble-context
                               ;   - system-text / system-section
src/seon/ctx/namespaces.cljs   ; namespaces-section + ns-render helpers
src/seon/ctx/your_entity.cljs
src/seon/ctx/live_tile.cljs    ; live-tile-section (the awareness twin)
src/seon/ctx/warnings.cljs
src/seon/ctx/transcript.cljs   ; transcript-section + transcript-section-html
src/seon/ctx/inventory.cljs
src/seon/ctx/prompt.cljs
```

(`system-section` stays in the spine — see below.) `open-todos` and
`turns` already live in their owning nses (`seon.agent.todo`,
`seon.agent.turns`) — leave them; they prove the cross-ns pattern already
works.

Each `seon.ctx.<name>` ns owns: its section fn(s), its `:seon.render/html`
twin (if any), and any schemas only that section uses. `core-default-ctx`
symbols update to the new qualified names
(`'seon.ctx.transcript/transcript-section`).

Loading: add the section nses to the boot require set (the same place
`seon.agent`/`seon.agent.todo` are required so their symbols resolve —
`client.cljs`). Optionally a thin `seon.ctx.sections` ns (sibling, plural)
whose whole body is `(:require [seon.ctx.transcript] …)` is the single
touch-point the boot requires once — or just list them in boot directly.
There is NO `seon.ctx.section` parent namespace/file; the prefix is only a
directory.

Keep `system-text` and `system-section` in the spine — it is a `def`
constant referenced by name and by the universality test
(`agent_context_test.cljs:453`); moving it buys nothing and risks the
sentinel test.

## Migration order (one section at a time, each independently verifiable)

For each section, in its own atomic step:

1. Create `seon.ctx.<name>`; move the section fn + its html twin +
   its private helpers + its schemas verbatim. Keep fn names identical.
2. Add it to the aggregator/boot require.
3. Update the symbol(s) in `core-default-ctx`.
4. Grep for DIRECT callers of the moved fn (not via symbol) — e.g.
   `live-tile-section` may be called directly by the awareness path; the
   inspector or tests may call `transcript-section`. Update those requires.
5. Live-verify: `(seon.ctx/assemble-context {:seon.agent/id "<id>"
   :seon.db/db @seon.db/*conn*})` still returns the section's text (and
   html twin) in order; the section did not turn into the "does not
   resolve" self-heal line.

Do the leaf sections first (`inventory`, `prompt`, `warnings`,
`your-entity`) — fewest helpers — then `transcript` (has the html twin +
card markup), `live-tile` (awareness twin, direct callers), and
`namespaces` last (largest, owns the ns-render machinery).

## Sequencing vs the rest

- This refactor heavily rewrites `ctx.cljs`, which is currently contended
  by the findings-nuke / system-text reword track. **Do NOT start until
  that track lands and the tree is clean** — otherwise the move collides
  with in-flight prose edits.
- Bundle the debug-view **step-4 deletion** (removing the orphaned
  entity-card path) and the **sparkline restore** into the SAME clean pass
  if convenient — all three touch the render/inspector/ctx triangle and
  benefit from one settled checkpoint, avoiding three separate rewrites of
  the same files.

## Risk

Low mechanically (verbatim moves + symbol updates), but high CONTENTION —
the whole value is realized only if it lands atomically on a clean tree.
The symbol-wiring means a missed boot-require fails LOUD (self-heal line in
the section's place), so mistakes are visible, not silent.
