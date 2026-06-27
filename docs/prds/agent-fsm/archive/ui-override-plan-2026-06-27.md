---
type: prd
status: draft
tags: [prd, web, agent, architecture]
---

# UI Override Plan — "the page is data"

Owner-approved direction (2026-06-27): a third party (acme / any consumer) can
**completely override the entire agent UI** — shell, layout, views, routes,
client, CSS — **maximally DB-driven**, with a **default function** that loads
the shipped shell + wires the live tiles, kept **clean** and **fully tested by
overriding everything in the acme product**. Research: [[ui-override-research-2026-06-27]].

## The one idea — uniform symbol resolution, default = seed

Every layer of the page is resolved from the DB the SAME way a tile already is:
it **names a symbol** (or carries data), resolved at render through the ONE
existing resolver (`core-views` direct → consumer `SCI` / `eval/lookup-value`).
The **default is a seeded config**, not a hardcoded fallback — so there is no
"hardcoded-vs-derived" bifurcation, and a consumer overrides any layer by
transacting different rows/symbols (or injecting code via `SEON_EXTRA_SRC` for
genuinely-code things like a custom view fn or client). This is the "clean":
**one mechanism, applied to shell + tiles + routes + client**, defaults shipped.

The **default function** the owner described = `seed-default-console!`: on
agent/cluster creation it transacts the default shell symbol + default tiles +
default routes + default scripts. The shipped UI IS that seed. Override =
retract/replace rows.

## Layers + override mechanism (target state)

| Layer | Override mechanism | Default |
|---|---|---|
| **Shell** (head/grid/header) | `:seon.console/shell` SYMBOL, resolved like a tile | shipped `console-shell` fn |
| **Layout** (tiles/order/span) | `:seon.tile/*` rows (seeded, editable) | seeded `default-tiles` rows |
| **Views** (tile content) | `:seon.render/html` symbol (already works) | the 9 core views |
| **Routes** | `:seon.route/*` registry rows (method/pattern/handler-symbol), consulted before the hardcoded dispatch | seeded default routes |
| **Client** | shell references client script(s) from `:seon.console/scripts` data; `SEON_EXTRA_PUBLIC` serves consumer `/js` | `packetstar.js` |
| **CSS** | `SEON_BRAND_CSS` env+file, injected on **all** heads (bug-fix: today only inspector) | Phosphor `output.css` |

## Phases (build order; lands incrementally)

**Phase 0 — Foundation (R schema/seed + U resolver).** *Keystone; everything sits on it.*
- **[R]** `schema/register!` + boot-seed: `:seon.tile/id [:string {:seon.db/identity true}]` (fixes the lookup-ref throw), `:seon.tile/console :string`, `:seon.tile/span :int`; `:seon.console/shell`, `:seon.console/scripts`; `:seon.route/method :seon.route/pattern :seon.render/html`. Install in the boot-seed lane.
- **[R]** Propagate the **silent write rejection** — pod `db/transact!` of an unregistered attr currently resolves SUCCESS but no-ops at the `:write` store; make it throw so overrides fail loudly.
- **[R]** `seed-default-console!` — the "default function": transact the default shell symbol + default tile rows + default route rows + default scripts on agent creation.
- **[U]** Generalize `resolve-view` into ONE resolver used for shell + tiles + routes (symbol → core/SCI/lookup-value).

**Phase 1 — Shell-as-symbol (U).** Promote `console-shell`/`head`/`header-bar` to overridable (a resolved `:seon.console/shell` symbol, default = shipped). The default fn loads the default shell + wires the live tiles.

**Phase 2 — CSS reaches the tile pages (U).** Unify all 6 page heads onto ONE head fn that injects the `SEON_BRAND_CSS` brand CSS — fixes the bug where it only reaches the inspector, not the tile/console UI. (Owner's choice: keep env+file, not a datom.)

**Phase 3 — Routes-as-data (U + R schema).** `serve.cljs` consults the `:seon.route/*` registry before the hardcoded dispatch; default routes seeded; a consumer adds/overrides a route by transacting a row (handler code via `SEON_EXTRA_SRC`).

**Phase 4 — Client-as-data (U).** The shell references client script(s) from `:seon.console/scripts` (default packetstar). Add `SEON_EXTRA_PUBLIC` static root so a consumer serves their own `/js` + `/css`. The server↔client wire (per-tile `data:` frames, console `event: patch {id,html}`, the POST endpoints) becomes a documented public contract any conformant client can honor.

**Phase 5 — Acme full-override proof (U).** In the acme product, override EVERY layer via acme's own data + `SEON_EXTRA_SRC`/`PUBLIC`: a custom acme shell symbol, custom tiles + layout, custom routes, a custom client, custom CSS — and render a COMPLETELY different acme UI end-to-end. Acceptance: zero `src/seon` edits by the consumer, `bin/acme build` 0 warnings, the different UI renders + updates live, the default (seon) UI unchanged.

## Lane split

- **R** (schema/seed/db/boot): the `:seon.tile/*` + `:seon.console/*` + `:seon.route/*` schema install + the `seed-default-console!` default-seed + the write-rejection fix. *(Foundation — requested via coordination; fits R's `my.*`/seed lane.)*
- **U** (`src/seon/web/**` + CSS/JS): the unified resolver, shell-as-symbol, CSS head-unification, route-registry consult in `serve.cljs`, client-as-data + `SEON_EXTRA_PUBLIC`, and the acme override + the full test. *(The bulk.)*

## Sequencing / dependency

Phase 0 (R's schema/seed) gates the rest. R is mid `my.*` convergence — the
tile/console/route schema install fits the same seed lane; I'll request it and
sequence. U can build the resolver + CSS fix (Phase 1-2 partial) in parallel
since they don't need new schema. Biggest-bang-first: Phases 0-2 deliver
layout + shell + CSS overridability; Phases 3-4 are the maximal routes + client.

## Open risk flags

- Per-cluster keying: today brand is a single global `::id "brand"` singleton —
  if one wire-server must theme N clusters differently, the keying changes now
  (R). Surfaced as a question if multi-consumer-simultaneous is in scope.
- `/eval` route bug (task #4): `packetstar.js` POSTs typed console input to
  `/eval` but `serve.cljs` has no such route → 404. Fold the fix into Phase 3.
