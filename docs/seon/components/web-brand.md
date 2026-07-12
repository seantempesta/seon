---
type: component
status: active
tags: [component, web]
---

# Web Brand Surface

> The product name, tagline, and theme the web UI renders are DATA in the cluster store, not compiled constants. `seon.web.brand` (`src/seon/web/brand.cljs`, CLJS pod lane) lets a downstream consumer rebrand every web UI page — name, tagline, `data-theme`, and a full stylesheet override — without touching `src/`. Shipped as fix-everything PRD C-17 (commit 24671ca).

## Data model

One singleton row, identity `:seon.web.brand/id` = `"brand"`, carrying up to three optional attrs:

| Attr | Type | Default (shipped seon brand) |
|------|------|------------------------------|
| `:seon.web.brand/name` | `[:string {:min 1}]` | `"seon"` |
| `:seon.web.brand/tagline` | `[:string {:min 1}]` | the mission-control subtitle line |
| `:seon.web.brand/theme` | `[:string {:min 1}]` | `"phosphor"` |

All schemas registered via `schema/register!` in the ns. Optional = absent: a missing attr (or a missing row entirely) means the shipped default — output is byte-identical to pre-C-17 when nothing is configured.

## Public API

- `info` — the effective brand: `defaults` merged with whatever the row carries; every key present. 0-arity reads the ambient `seon.db/*conn*`, 1-arity takes an explicit db value. Render fns call it AT RENDER TIME — reactive-context, no cached atom; a row edit shows on the next render.
- `page-title` — pure helper: `(page-title b "agents")` → `"seon · agents"` for `<title>`/`<h1>` strings.
- `css-text` — the downstream brand stylesheet's text, or nil. Reads the path from `SEON_BRAND_CSS` (0-arity) or an explicit path (1-arity), FRESH per call via `fs.readFileSync`. Unreadable file logs LOUDLY and returns nil — the page renders unbranded rather than breaking (degrade, don't break).
- `env-row` — the `::row`-shaped map of whichever `SEON_BRAND_*` vars are set and non-blank.
- `sync-tx-data` — pure: given the existing row attrs and the env attrs, produces tx-data (identity-upsert asserts + explicit `:db/retract`s).
- `sync!` (`^:async`) — applies `sync-tx-data` against the ambient conn at boot. Never rejects; failures log loudly and resolve `{::synced? false}` — branding must never take the boot down. Idempotent: a second call with the same env transacts nothing.

## Env sync — env OWNS the row

`sync!` runs at boot (kicked from `seon.web.debug/install!`, after `boot-seed!` with the root conn bound, fire-and-forget). Per attr against `SEON_BRAND_NAME` / `SEON_BRAND_TAGLINE` / `SEON_BRAND_THEME`:

- env set and ≠ row value → assert (identity upsert)
- env unset but row has a value → retract
- equal, or absent on both → nothing

This deliberately differs from `my.soul`'s seed-only-if-absent: booting WITHOUT the env vars must return the defaults. The brand is the deployment's configuration, not the store's memory. A runtime edit to the row survives within a pod run; the next boot re-syncs from env.

## The CSS hook

`SEON_BRAND_CSS=<abs path>` names a stylesheet that the web UI inlines as a `[:style]` AFTER the `/css/output.css` link in every page head (`seon.web.debug/brand-css-style`), so its token overrides (`--color-base-*`, `--color-amber-*`, fonts) win the cascade. Read fresh per render — a css edit shows on the next page load. Example downstream use: an "Acme" deploy sets the four env vars and ships one css file; nothing in this repo changes.

## Consumers (seon.web)

All four page shells read `brand/info` for `<title>`, `data-theme` on `[:html]`, and the brand stylesheet:

- `debug-shell` (`/agent/<id>/debug`) — title `"<name> · agent <id> · debug"`
- the consumer agent shell (`/agent/<id>`) — title `"<name> · agent <id>"`
- `agents-index-page` (`/agents`) — title `"<name> · agents"`
- `agent-not-found-page` — title `"<name> · agent <id> not found"`

The cluster dashboard fragment additionally renders the brand h1 (`page-title b "cluster"`) and the `::tagline` as the subtitle line, re-reading `info` on every SSE re-render.

## Dependencies

- Uses: `seon.db` (query/entity/transact!/listen-side conn), `seon.schema`, `seon.log`, Node `process.env` + `fs` (pod lane only — no `.clj` sibling).
- Used by: `seon.web.serve`/`seon.web.debug`/`seon.web.datastar` (page shells, `brand-css-style`, `install!` boot kick).

## Design decisions

- **Reactive-context, not cached state** — `info` is a function of the db value at render time; no atom, no invalidation. Fix the row, the next render reflects it.
- **Env-owned, not seed-once** — the one place this surface diverges from the soul pattern, documented in the ns docstring; unsetting an env var must visibly revert to defaults on the next boot.
- **Loud degrade** — both the css read and the boot sync log errors prominently but never break a page render or the boot.
- **Downstream-clean** — no consumer product names in this repo; the brand row + css file are the entire customization surface (see CLAUDE.md hard rule).
