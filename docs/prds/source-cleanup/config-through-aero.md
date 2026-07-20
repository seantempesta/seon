---
type: prd
status: active
tags: [prd, database, config]
---

# Config through aero into database facts PRD

## Owner ruling (2026-07-20)

All configuration goes through aero and is read into the database as facts;
runtime looks configuration up in the database and can be adjusted at
runtime via ordinary database transactions. Ground the design in the actual
aero source (`reference-code/aero/`), and keep loading different configs
simple and reliable.

## Current truth

The intended mechanism half-exists and is the one to strengthen:

- `seon.config` already reads the selected manifest with
  `aero.core/read-config` (CLJS branch over `cljs.tools.reader` + Node fs);
  manifests already use `#include` / `#merge` / `#env` / `#or` / `#long`
  (`config/system.edn:57-68`, `config/acme.edn:24`, `config/minimal.edn:29`).
- The resolved subset reconciles into the `:seon.config` database singleton
  at boot; `bin/seon config apply <path>` is the explicit repair path;
  runtime operations acquire the singleton from the database.
- aero pinned at `aero/aero 1.1.6` (`deps.edn:115`); full source vendored at
  `reference-code/aero/`.

## Problems

Evidence: [[../runtime-reliability/research/cleanup-audit-config-startup-2026-07-20]].

1. **Runtime env gates bypass the mechanism**: `SEON_WEB`, `SEON_SHELL`,
   `SEON_RENDER_STRICT`, `SEON_BRAND_*` are read from process env at runtime
   (not at the aero boundary), plus `my/blob.cljs:200` and
   `db/transport/uds.cljs:28` re-resolve cluster/socket coordinates from env
   instead of the decoded launch descriptor.
2. **Defaults declared three times**: port 7890, port files, cluster dir in
   `script/seon/dev/config.clj:391-416`, `src/seon/launch.cljc:489-520`, and
   `src/seon/db/server.clj:316`.
3. **`SEON_EMBED` presence-gate scrub duplicated** in `config.clj` and
   `bin/acme`.
4. Env-supplied dir coordinates arrived relative (fixed in `a850b343`:
   `config/load!` now absolutizes through `root-path`).

## Recommended solution

1. **One aero read per boot, one reconciliation.** Every knob that gates
   runtime behavior becomes a declared manifest key (using aero `#env`/`#or`
   inside the manifest when an env override is genuinely wanted), reconciled
   into `:seon.config` facts. Delete the direct env reads; the two
   coordinate re-reads consume the launch descriptor.
2. **Runtime adjustment = a database transaction** against the singleton.
   Verify the pure accessors read the acquired database value (not a boot
   snapshot) so a transacted change takes effect on the next acquisition;
   this is the "adjust at runtime" requirement, no new mechanism needed.
3. **One default owner**: operator `config.clj` computes each coordinate
   once and passes it via the launch descriptor; `launch.cljc` and
   `db/server.clj` stop declaring fallbacks.
4. Dedupe the `SEON_EMBED` scrub into the config loader.

## Required grounding before implementation

Read `reference-code/aero/src/aero/core.cljc` for: tag set and extension
point (`reader` multimethod), `#profile`/`#include` resolution rules
(includes resolve relative to the including file), the shallow-merge
semantics of `#merge`, and the CLJS branch's reader differences. Record the
dependency ledger (file:line) in this PRD before the first edit. Decide
whether per-cluster variation stays `#include`+`#merge` overlays (current
pattern, works) or adds `#profile` — recommendation: keep overlays, they are
already proven by `acme.edn`/`minimal*.edn`.

## Acceptance

Operator suite; `bin/seon up` clean-checkout boot; acme boot; config-apply
idempotence (re-apply writes nothing when converged); live proof that
transacting a config fact changes behavior without restart; `rg` shows zero
runtime env reads outside the aero manifest boundary and process bootstrap.

## Owner rulings 2026-07-20 (second round)

1. Brand keys live in the downstream overlay manifest (acme.edn), never
   system.edn.
2. `bin/seon config apply` stays explicit-only.
3. **Capability grants (`SEON_WEB`, `SEON_SHELL`) live in the launch
   descriptor, not database facts** — a grant-as-datom would be
   agent-widenable through ordinary `db/transact!`. Boundary rule: process
   identity and OS resources bind at launch; behavior policy binds at
   acquisition.

## Research grounding (settled)

[[research/aero-config-seam-2026-07-20]] carries the full dependency
ledger, tag-semantics gotchas (`#boolean` is strictly `"true"` in CLJS —
wrapper `"1"` exports must flip; missing `#include` resolves silently, the
closed manifest schema stays the backstop), the per-violation migration
table, and the one adjustability gap: `db/install-configuration-context!`
is a boot snapshot — refresh it from the existing committed-transaction
delivery when a transaction touches the singleton. Every other consumer
already acquires per operation, so live transaction adjustability already
works there.
