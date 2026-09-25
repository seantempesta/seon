---
type: landing
lane: fix-no-provider-overlay-opus
status: complete (code); verification is root's
created: 2026-09-25
---

# Landing: a sparse config apply keeps the decisions beneath it

What existed: `seon.config/apply!` compiled `shipped defaults → request manifest → environment` and exact-reconciled
the one row. It never read what the cluster already held. The approach: the row records which dials a layer above
the shipped defaults decided (`:seon.config/overlay-keys`). `apply!` reads those decisions back as a sparse manifest
and layers its own manifest over them. There is no new path: `compile-manifest` and `apply-compiled!` are unchanged
apart from recording the keys.

## Round 2: re-land after revert 81f9b1b3e (default could not boot)

- **Cause, reproduced.** On an MCP branch of default (`fix-no-provider-overlay`) that lacked the attribute,
  `apply-compiled!` with boot's compiled row gave the boot log's exact failure: "Bad entity attribute
  :seon.config/overlay-keys at {:db/id 42401 ...}".
  - Boot compiles config from the checkout's declarations (`compile-manifest`, packaged forms).
  - For an existing cluster, boot's accretion (`accrete-schema-population!`) uses the branch's own
    database-derived projection. So a declaration the branch has not adopted is never installed before boot writes
    the row.
  - This holds for any new config-entity attribute, including a new dial.
- **Fix.** `seon.config/apply-compiled!`, the one config writer, now calls `accrete-row-attributes!` first:
  - every attribute a desired row names that the branch has not installed is derived with
    `seon.schema.datahike/malli->datahike-attr-in` from the packaged projection the row compiled from;
  - it is transacted in the accretion owner's shape (`{:tx-data declarations}`), then the row is written;
  - a converged branch pays one schema-map lookup per key.
  - Boot (`seed-cluster-facts!`), `init --dev` and `config apply` all write through `apply!` / `apply-compiled!`,
    so all three are covered.
- **Proof on a branch lacking the attribute** (`fix-no-provider-overlay-2`, fresh off default's head). I evaluated
  the committed source text of `compile-manifest`, `accrete-row-attributes!`, `apply-compiled!`, `held-overlay`
  and `apply!` into a scratch namespace; private helpers were bound to seon.config's own Vars.

  | step | result | ms |
  |---|---|---|
  | boot write: development overlay compile through `apply-compiled!` | committed, 1 operation, attribute installed | 1271 |
  | `config apply` of `{max-episode-runs 7}` | committed; `no-provider true`, `snapshot-window-ms 0` kept, runs 7 | 501 |
  | same apply again | converged, 0 operations | 299 |
  | apply the shipped document | 5 operations; no-provider absent, runs 100, no overlay-keys | 557 |

  - The boot row takes over a second: accretion's `declaration-projection` of the packaged forms takes about
    450 ms, and the MCP status polling ran concurrently. It is paid once per branch that lacks the attribute.
  - The ordinary apply costs 300–500 ms, which is the unchanged `compile-manifest` plus reconcile.
  - Both branches are retired and the scratch namespace is removed.
- **Churn until adoption.** Until the branch adopts `seon.config.edn`, each boot's accretion treats the installed
  attribute as retired (`retired-attributes`, cluster.clj:1112) and drops it, and boot's config write installs it
  again. Boot's compile is exact from its own overlays, so no decision is lost. After adoption it converges.
- **Committed regression:** `seon.config-test/an-apply-on-a-branch-lacking-the-overlay-attribute-installs-it-first`.
  It removes the attribute through `seon.cluster/attribute-change-tx` (the schema-change owner), then applies. Risk:
  write admission may refuse removing an attribute the fixture's projection still declares. If so, the test needs a
  fixture built from a projection without the declaration.

## Broken or unproven first

- **Not run.** LOAD RULE: the lane ran no tests and loaded nothing into default. The changed `apply!` has not run
  as a whole. Its parts were proven at the REPL (Evidence).
- **Schema publication.** `resources/seon/schemas/seon.config.edn`, `src/seon/schema/edn.clj` and
  `src/seon/config.clj` land together. The config writer installs the attribute itself on a branch that lacks it
  (round 2).
- **`src/seon/schema/edn.clj` is outside my listed files.** I changed one line in `derive-config-forms`: the config
  entity gains `[:seon.config/overlay-keys {:optional true} ...]`. No running ledger row holds that file. Root
  should record it.
- **Expected red: `seon.schema.datahike-test` parity.** The oracle `test/seon/schema/datahike_parity.edn` has no
  entry for the new attribute, and its input digest changes. That file is not mine. Regenerate the baseline with
  `:seon.config/overlay-keys` (cardinality many, keyword) and the new `:seon.config/entity` form. The shape-mirror
  lane's schema deletions change the same digest.
- **Test worlds still resolve the shipped paid provider when they name none.** The assignment asked that "a test
  world resolves no-provider unless the test supplies a provider explicitly". That reverses root's ruled option A
  (3a7ffa69c: "test worlds compiled from config/default.edn reach their provider stand-ins"). About 15 test files
  reach the call path through `with-redefs` of `ai/complete` without naming a provider, and one of them is
  `cluster/turn_test.clj`, which another lane holds. **Root rules.** Options:
  1. Keep option A as it is now (recommended now that the owner has said the calls were cheap). A world that names a
     provider keeps it through every apply. A world that names none still resolves DeepSeek.
  2. Flip `config/default.edn` to `:seon.config.ai/no-provider true` and delete that entry from
     `config/development.edn`. Every world is then no-provider unless a layer supplies
     `:seon.config.ai/no-provider :seon.config/absent`. Cost: two EDN lines, plus a one-key opt-in in each stand-in
     fixture (about 15 files, including held `cluster/turn_test.clj`).
  3. Option 2, plus the `seon.ai/complete` guard below.
- **Defence in depth is not done.** `seon.ai/complete` ignores the `:seon.ai/no-provider` request attribute. Only
  the turn's own check stops a call. Filed as
  `docs/seon/issues/provider-completion-ignores-the-no-provider-request-attribute.md` (`src/seon/ai.clj` is not
  mine).
- **Limit of the new layering.**
  - A key stays decided until the shipped document is applied, which is the one full reset:
    `(config/apply! request config/default-manifest-path)`.
  - `init --dev` re-applies `config/development.edn` through `apply!`. A key deleted from that file therefore
    persists on the development cluster until restart. `boot/start!` still compiles exactly.
  - A dial not recorded in `overlay-keys` still follows `config/default.edn` edits on the next apply.
- **Comments in other lanes' files are now false.** Each describes the old exact apply. Owners should correct them:
  - `test/seon/cluster/turn_test.clj`: the `spending-the-last-episode-run...` comment ("a manifest apply would
    replace the fixture's whole manifest").
  - `test/seon/turn_loop_test.clj:460-464`.
  - `test/seon/web/jvm_test.clj:402-403`.

## Answers to the two questions

1. **Why a sparse apply dropped the overlay.** `apply!` compiled a manifest from the given keys only. It passed
   `select-keys request [:seon.config/manifest ...]` to `compile-manifest`, which merges `defaults`, that manifest
   and the environment (`compile-settings`). The overlay was never a layer in every read. It was applied only by
   `boot/start!` (merged into boot's compile) and by `init --dev`. The `bin/seon config apply` branch of
   `boot/request!` goes through the same `apply!`, so before this fix it also dropped the development overlay on
   `default`.
2. **Do test worlds get the development overlay?** No. Fixture worlds call `config/apply!` or `compile-manifest`
   with no overlay. Forked clusters (`registry.clj:257`) compile shipped defaults only. `config/development.edn`
   says so itself: "test worlds compile only the shipped defaults".

## Evidence (MCP `eval_clj`, JVM mode, namespace `fix-no-provider-overlay-scratch`, read-only, no provider call)

- Live default row: `{:seon.config.ai/no-provider true, :endpoint "https://api.deepseek.com/chat/completions"}`.
  It is the only config row in the store.
- `compile-manifest` with `{:seon.config.run/max-episode-runs 1}` resolves the DeepSeek endpoint and
  `DEEPSEEK_API_KEY`, with no `no-provider`. The shipped document gives the same result. With
  `(development-manifest)` it gives `no-provider true`.
- The committed entity line was applied to the packaged forms in the scratch namespace:
  - `malli->datahike-attr-in` derives `{:db/ident :seon.config/overlay-keys, :db/cardinality :db.cardinality/many,
    :db/valueType :db.type/keyword}`;
  - the attribute is among `database-attributes-in`;
  - the fixture row validates against `:seon.config/entity`.
- Round trip on `d/with` over default's value (schema tx, then the row):
  - the fixture manifest was `{no-provider true, endpoint "http://127.0.0.1:1/v1", backup/model :seon.config/absent}`;
  - the pulled `overlay-keys` were `[endpoint no-provider backup/model]`;
  - the held manifest read back equals the fixture manifest, including the absent marker;
  - layering `{max-episode-runs 1}` over it keeps `no-provider true` and the local endpoint, with no backup;
  - the old exact compile of the same sparse manifest gave the DeepSeek endpoint and the shipped backup model.
- A default-shaped world's resolved primary target, from `seon.ai/targets` over the effective row, carries
  `:seon.ai/no-provider true`.
- Timings: the added read `db/pull '[*]` of the config row takes 0.48 / 0.16 / 0.11 ms. The unchanged
  `compile-manifest` baseline takes 121 / 113 / 117 ms. The whole scratch round trip took 640 ms, including
  `packaged-forms`.

## Changes

- `src/seon/config.clj`:
  - `compile-manifest` records `:seon.config/overlay-keys`, the dial keys of manifest plus environment, only when
    nonempty;
  - new `held-overlay` reads them back, with a key the row lacks read as `:seon.config/absent`;
  - `apply!` layers the request manifest over the held manifest;
  - the shipped-document path resets exactly;
  - the namespace and `apply!` docstrings are corrected.
- `resources/seon/schemas/seon.config.edn`: `:seon.config/overlay-keys [:set {:min 1 ...} :qualified-keyword]`.
- `src/seon/schema/edn.clj`: one entity entry.
- `test/seon/config_test.clj`:
  - regression `a-sparse-apply-changes-only-its-keys-and-keeps-no-provider`;
  - `config-writer-submits-only-the-fact-difference` now expects the recorded key.
- `test/seon/test_support.clj` (`apply-config!` docstring only): deleted the false
  `:seon.config/applied-manifest-digest` claim (no such attribute exists) and the "EXACT" claim.
- Issues: the status of the existing issue is updated in place, and one new issue is filed (above).
- Other config-apply sites in tests (`rg "config/apply!|apply-config!|compile-manifest" test`, about 70 sites):
  none needed converting. Every `apply!` caller now layers through the owner. The direct `compile-manifest` users
  (render_coverage_test, evaluate_sources_test, cluster/agent_test, registry_test, plan_test, agent_identity_test)
  compile a whole row on purpose. `seon.test`'s seed apply (`src/seon/test.clj:160`) and
  `boot/request! :config-apply` also layer now, with no edit.

Root runs:

```
bin/test-check default --test seon.config-test/a-sparse-apply-changes-only-its-keys-and-keeps-no-provider
bin/test-check default --test seon.config-test/an-apply-on-a-branch-lacking-the-overlay-attribute-installs-it-first
bin/test-check default --ns seon.config-test
bin/seon down && bin/seon start                          # boot on an existing store whose default lacks the attribute
bin/test-check default --ns seon.config-application-test
bin/test-check default --ns seon.no-provider-test
bin/test-check default --ns seon.schema.datahike-test   # expected red until the parity baseline is regenerated
bin/test-check default --ns seon.turn-loop-test         # watches for any test that relied on an exact reset
```
