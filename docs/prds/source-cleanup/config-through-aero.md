---
type: prd
status: active
tags: [prd, database, architecture]
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

## Grants for direct-launch pods (design, 2026-07-20)

Not every pod is operator-launched: `docker/seon-entrypoint:110-125` execs
`bun out/client/main.js` with env only, and the inspect-ai harnesses grant
the same way (`src-inspect-ai/src/seon_inspect/tb_agent.py:159` sets
`SEON_SHELL` `'1'`/`'0'`; `swebench_arm.py:219` likewise). A
descriptor-only `granted?` with no env path would default-deny every
container/eval pod — shell-dependent benches would uniformly fail (the
"uniform 0 = harness defect" class). The design that keeps the descriptor
authority AND the direct-launch seam:

1. `src/seon/launch.cljc` gains `::launch/web-granted?` /
   `::launch/shell-granted?` `:boolean` descriptor fields, and defines the
   coercion ONCE as a shared cljc fn — `(defn env-grant? [v] (boolean (and
   v (not (str/blank? v)) (not= "0" v))))` — preserving today's exact edge
   in both `granted?` fns: any non-blank value other than `"0"` grants;
   absent/blank/`"0"` = deny. A naive boolean coercion would flip `"1"` or
   `"yes"` to deny.
2. `default-process-descriptor` populates both fields via
   `(env-grant? (platform/env-val "SEON_WEB"))` / `"SEON_SHELL"`. This is
   the process-bootstrap seam the acceptance already exempts ("zero runtime
   env reads outside the aero manifest boundary and process bootstrap"), so
   no acceptance change is needed. The env-fallback descriptor branch
   (launch.cljc:489-520) is the launch seam for the test runner AND every
   direct-launch pod (docker entrypoint, inspect-ai harnesses) — it does
   not shrink to test-runner-only use.
3. Operator `config.clj:300-301` computes the same two fields into the
   encoded descriptor using the SAME `env-grant?` fn (required from
   launch.cljc), so operator and fallback coercion cannot drift.
4. Both `granted?` fns then read `launch/process-launch-descriptor` only;
   their direct env probes are deleted; envelope text updates to name the
   launcher grant.
5. Direct-launch consumers change nothing: `docker/seon-entrypoint:110-125`,
   `tb_agent.py:159`, and `swebench_arm.py:219` keep exporting env, which
   the fallback descriptor consumes.

Acceptance addition for this unit: rerun one shell-dependent inspect-ai
smoke bench inside the container after the change; a uniform-0 score is
the regression signature.

## Boot `configure!` snapshots (classification, 2026-07-20)

Several knobs are captured once at boot into process-local atoms by
`configure!` calls, so a transacted datom changes the database while the
atom serves stale values until restart. These are NOT live-adjustable
today and must be classified honestly:

- **Reactive timings** — `serve.cljs:1721` calls `datastar/configure!`
  once at boot, resetting `reactive.cljs` `!policy` (:43-45, :73-78).
  Transacting `:seon.config/reactive` datoms has no effect until restart.
  Fix options (one mechanism, pick after costing): (a) when a committed
  transaction touches `[:seon.config/id "cluster"]`, the client's existing
  committed-transaction handling re-calls `datastar/configure!
  (config/reactive-policy configuration)` — valid because `configure!`
  resets an ordinary atom, not an ALS context (the probe's
  refresh-in-place death applies only to `enterWith` contexts); or
  (b) cleaner: delete `!policy` + both `configure!` fns and have
  `settle-delay`/max-latency (reactive.cljs:153-162) read
  `config/reactive-policy` off the configuration acquired with the
  delivered database value — evaluate cost first since it runs per
  delivered transaction, not per timer tick.
- **`execution.host/configure!`** (client.cljs:2112) and
  **`log/configure!`** (client.cljs:2808) consume launch-descriptor facts:
  boot-only under the dividing rule — classification only, no mechanism
  change.
- **`agent/fs`, `agent/shell/internal`, `agent/search` `configure!`
  sites**: sweep with `rg -n "configure!" src/` and record a one-line
  disposition each in the implementation plan.

Acceptance addition: transact a larger `:seon.config/reactive-settle-ms`
and observe the next feed coalesce window widen (reactive.cljs:153-156)
with no restart — the falsifier that distinguishes the fixed system from
today's.

## Research grounding (settled)

[[research/aero-config-seam-2026-07-20]] carries the full dependency
ledger, tag-semantics gotchas (`#boolean` is strictly `"true"` in CLJS —
wrapper `"1"` exports must flip; missing `#include` resolves silently, the
closed manifest schema stays the backstop), and the per-violation migration
table. Its "refresh the installed context from committed-transaction
delivery" option for the ambient snapshot is superseded by the validated
design below.

## Ambient configuration design (validated by probe, 2026-07-20)

**Owner ruling**: AsyncLocalStorage is the right carrier for ambient
operation context — facts a database request intrinsically needs attached
recursively (agent id, provenance, config). Configuration stops being a
once-at-boot `enterWith` snapshot and joins the SAME per-operation context
entry the identity/provenance ambient already uses: at each operation
boundary (turn start, web request, scheduled fire, execution invocation,
boot phases) the owner acquires one database value and enters
{identity + config-at-that-basis} together through the existing
`db/with-tx-context`. Descendants inherit; the next operation acquires
anew; no live-context mutation is ever needed.

Validated by executable probe —
[[research/als-config-probe-2026-07-20]]:

- **Refresh-in-place is dead**: two pre-existing independent fibers never
  observe a later `enterWith` replacement (Bun 1.3.14 and Node v26.4.0,
  identical output). The previously proposed committed-delivery refresh of
  `db/install-configuration-context!` cannot work; do not resurrect it.
- **Per-operation `run` is fully inherited** by awaited chains, `.then`
  chains, nested async fns, and `setTimeout` continuations, and does not
  leak to the caller. `run-with-tx-context` merges the current context, so
  nested `with-tx-context` scopes carry the boundary's configuration
  automatically.
- **`read-resource-options` (db.cljs:745) works unchanged**, proven live:
  a query inside `with-tx-context {:seon.config/configuration <decoded
  singleton>}` observed the entered ceiling; with no operation context the
  default policies (`config/default-database-query-policy` and the pull
  twin) governed — the correct fallback for early boot and operator probes.
  Boundaries must enter the full decoded singleton; a partial map fails the
  `:seon.config/singleton` instrumentation (observed).
- `execution/runtime.cljs:589-604` (`eval-batch!`) already implements the
  target idiom; the probe report's boundary inventory table names every
  other boundary with file:line and its exact change, plus the two
  deletions (`db/install-configuration-context!`,
  `internal/enter-tx-context!` — after which `seon.db` contains no
  `enterWith`, also sidestepping the Bun `enterWith` segfault recorded at
  [[../../seon/issues/bun-enterwith-toplevel-segfault]]).
