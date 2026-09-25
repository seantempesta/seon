---
type: landing
lane: fix-config-boot-opus
status: complete
created: 2026-09-25
---

# Landing: fix-config-boot (census c classes 1, 10, 11, 15)

What existed: `config/apply!` compiles defaults, then one sparse manifest, then the environment, and exact-reconciles
the result. `boot/start!` compiled only the request's `--config` manifest. Nothing read `config/development.edn`.
The approach: the development overlay is one more sparse layer, supplied at boot's existing config step for the
cluster the launcher opened the JVM for. No new registry or config path.

## Broken or unproven first

- **`seon.contracts-compile-test/each-historical-from-zero-break-is-refused-by-name` is expected RED after 53eb730d3.**
  At contracts_compile_test.clj:387-391 it expects anonymous predicates to be refused with "EDN-readable Malli form"
  (the output-contract refusal). They are now refused earlier, by name, with "contracts are data". That file is not
  mine. Its owner or root should change the fragment to `"contracts are data"`. This is a retired assumption, not a
  regression.
- **Class 10, turn-loop x2, not fixed here: the producer is `seon.error/recording`, not turn.clj.**
  - `turn.clj:3410` does write `:seon.turn.loop/phase-failed`.
  - `stored-observation` keeps it.
  - `recording`'s `:seon.error/value` and `:seon.error/fact` drop both declared members.
  - Filed as `docs/seon/issues/error-recording-drops-declared-distinguishing-members.md`. The owner is error.clj.
- **Reset is not covered.** `:reset` calls `start!` without the launcher's listener, so a reset `default` comes back
  without the overlay. That is already true of any `--config` manifest. Recover with the apply command below.
  Alternatively, root runs `bin/seon init --dev default` with no `--changed`.
- **The overlay also activates `:seon.config.db/snapshot-window-ms 0` on default.** That dial was previously
  dormant (a2-storage-retention, "orchestrator ratifies"). Implicit collection still refuses unless *every* cluster
  configuration in the store declares a window (registry.clj:485-514), so default alone deletes nothing. Root should
  ratify it or remove it.
- **LOAD RULE.** The lane ran no tests, and no namespace was reloaded into default. The changed functions were
  proven in scratch namespaces only. The live JVM still runs the old code and the old config row.

## Changes

1. `3a7ffa69c` — class 1, option A.
   - `config/default.edn:452` now reads `:seon.config.ai/no-provider :seon.config/absent`.
   - `config/development.edn` carries `no-provider true`.
   - `seon.config/development-manifest-path` and `development-manifest` are new (config.clj).
   - `boot/start!` merges the overlay under any `--config` manifest when the request carries the launcher's
     `:seon.boot/prepl-server`. This is the selection: the cluster the JVM was launched for, not a list of names.
   - `init --dev NAME` re-applies the overlay to NAME.
   - Regression: `seon.config-test/no-provider-is-a-development-overlay-decision-never-a-shipped-default`.
2. `ccfd63dc6` — class 11. `readiness` uses `(or (when database (db/q ...)) 0)`.
   - Assertion added in `seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault`.
3. `57beace43` — class 10.
   - The error-base members are removed from `:seon.eval.drive/evaluation`.
   - Shown text, value and `:seon.cluster.eval/error` are now optional.
   - `run-receipts` uses a `::absent` sentinel in `get-else` and omits absent values; no `""` is written.
   - Regression added to `seon.eval.drive-test/transcript-projects-the-evaluation-time-limit`.
   - The issue file is included in this commit.
4. `53eb730d3` — class 15.
   - A seq in a `:fn` predicate slot (`(fn ...)` or `(fn* ...)` from source metadata) now goes to `callable-symbol`.
     That throws `::unnamed-callable`, whose message now teaches "contracts are data; use a registered predicate
     schema or a quoted symbol naming an admitted predicate".
   - The class regression is the existing
     `seon.contracts-install-test/function-literal-contract-is-refused-before-printing-or-installing`.
5. `1e750628e` — follows from item 1.
   - Measured: `init --dev` would otherwise pay a config compile on every hook publication (see Evidence).
   - It now re-applies config only on a full init (no `--changed`) or when `config/default.edn` or
     `config/development.edn` changed.

Net: +130 / -31 lines across the five commits, including tests, EDN and the issue file.

## Evidence (MCP `eval_clj`, JVM mode, scratch namespaces, read-only)

- The live default row has `:seon.config.ai/no-provider true`.
- `(db/q '[:find (count ?a) . ...] db)` over no matches gives `nil`. `:seon.agent/count` is `[:int {:min 0}]`.
- Compiling from the new shipped document: `(find effective :seon.config.ai/no-provider)` gives `nil`. With
  `(config/read-manifest "config/development.edn")` as the manifest it gives `true`.
- The new `:seon.eval.drive/evaluation` form validates three receipts: successful with shown text, bare (no shown
  text or value), and with an error.
- The `get-else` keyword sentinel works in the live Datahike query (3 rows, absent values marked).
- `canonical-definition` before the fix: `'[:fn {} (fn [r] r)]` gives the output-contract refusal "must be a
  parseable, EDN-readable Malli form".
- After the fix, the new definition was loaded into `fix-config-boot-canon-scratch` via load-string:
  - `(fn [r] r)`, the run-5 `(fn [r] (seq r))` and `(fn* ...)` each give `::unnamed-callable` with the
    "contracts are data" message.
  - `[:fn clojure.core/ifn?]` and `[:fn 'clojure.core/pos-int?]` canonicalize unchanged.
- Timings:
  - `read-manifest`: 64 ms per call (it builds the declaration projection).
  - `compile-manifest`: 107 ms per call.
  - So boot pays about 170 ms once. An edit publication pays 0 ms extra unless it changed a config document.

## Root runs (serially, after adopting 3a7ffa69c..1e750628e)

Re-apply config on the live default. `config apply` compiles the new shipped document plus this one file:

```
bin/seon config apply default config/development.edn
```

Then verify at MCP:

```
(:seon.config.ai/no-provider (seon.config/effective (seon.db/db (seon.cluster.boot/connection "default")) "default"))
```

It must answer `true`, and `(find seon.config/defaults :seon.config.ai/no-provider)` must answer `nil` after
`seon.config` reloads. The next `bin/seon down && bin/seon start` applies the overlay at boot with no manual step.

```
bin/test-check default --ns seon.cluster.turn-test
bin/test-check default --ns seon.turn-test
bin/test-check default --ns seon.turn-loop-test
bin/test-check default --ns seon.turn-continue-test
bin/test-check default --ns seon.ai-test
bin/test-check default --ns seon.config-test
bin/test-check default --test seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault
bin/test-check default --test seon.cluster.mcp-test/runtime-observation-bounds-open-faults-and-counts-them-whole
bin/test-check default --ns seon.eval.drive-test
bin/test-check default --ns seon.concurrency-independence-test
bin/test-check default --test seon.contracts-install-test/function-literal-contract-is-refused-before-printing-or-installing
bin/test-check default --ns seon.contracts-compile-test
bin/test-check default --ns seon.schema-test
```

- Expected: `seon.contracts-compile-test` is red at :391 until its fragment is updated (above).
- The turn-loop phase-failed members stay red until the error.clj issue is fixed.
- mcp-test's runtime-observation member also carries the class-4 `with-redefs` premise, which fix-test-shapes-opus owns.
- Reading the live default's config needs no reset: **RESET NEEDED: none.**

## Released paths

config/default.edn, config/development.edn, src/seon/config.clj, src/seon/cluster/boot.clj,
resources/seon/schemas/seon.eval.drive.edn, src/seon/eval/drive.clj, src/seon/schema.clj, test/seon/config_test.clj,
test/seon/cluster/boot_test.clj, test/seon/eval/drive_test.clj, this landing.
