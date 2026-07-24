---
type: research
status: complete
tags: [research, integration, runtime, reliability]
---

# Integration seam sweep — 2026-07-23

## Verdict

The sweep found **11 verified breaks-now seam groups**, **2 suspicious current seams**, and **2 verified breaks-on-growth seams** beyond the seven already identified by the frozen-tree checkpoint.

Of the 11 breaks-now groups:

- 6 affect production, packaging, build, or live configuration behavior.
- 5 affect canonical tests, benchmarks, fixtures, or direct-call contracts.
- Four consumer groups explain all 18 assertion failures observed before the original CLJS hang: 11 claim-epoch failures, 5 provider-descriptor failures, 1 provenance/render failure, and 1 boot-schema failure.

The highest-risk unhit contract is the new claim-epoch fence: four tracked production callers still mutate runs without the epoch, and the quiescence acquisition path cannot supply it because its query and projection discard the field.

Known breaks #6 and #7 were intentionally not re-derived. Concurrent lanes advanced `HEAD` while this read-only audit ran; the audit began with 298 same-day commits, the exhaustive inventories closed at 303, and the final production-call recheck was performed at `0e4dbeaf9` with 306 non-merge commits after the fixed pre-day base `25cdf7ac4`.

No files were edited.

## Scope and method

The audit covered:

- 534 tracked Clojure, ClojureScript, and portable Clojure files across production, tests, scripts, benchmarks, and harnesses.
- 1,891 current public definitions.
- 47 public definition forms whose positional arguments or map destructuring changed today.
- 176 added direct schema registrations, 59 changed same-key registrations, and 32 removed or moved registrations.
- Five new identity attributes.
- 15 same-day commits touching `config/system.edn`.
- 46 new leaf manifest inputs, four renamed config attributes, one split policy, one removed option, and one provider-descriptor component catalog.
- 23 files containing new or changed file reads or subprocess activity.
- Four new build artifacts and all six Shadow configurations with hooks.
- Corpus-growth consumers for admission, committed host projections, and `grep-graph`.

Every changed public symbol received an occurrence sweep across production, tests, scripts, benchmarks, and visible probes. File-backed artifacts were traced from reader to flush/publish producer, immutable runtime roots, and release members. Findings below are ranked with verified breaks-now first.

## Breaks now

### BN-1 — Claim-epoch fencing has four stale production mutation callers

**Status:** verified, breaks now  
**Producer:** `901eee2d3c736ff7a5c575fb241a064842fec9c9` — `Build claim-native portable driver checkpoint`

The producer made `:seon.agent.run/claim-epoch` required in close, renew, heartbeat, pause, and resume requests and added it to the transaction CAS fence.

Stale production consumers:

- `src/seon/client.cljs:2369-2374` closes a quiescent run with only run ID and reason.
- `src/seon/web/serve.cljs:704-706` closes the current run without its epoch.
- `src/seon/web/serve.cljs:1524-1526` closes a superseded run without its epoch.
- `src/seon/web/serve.cljs:1730-1732` pauses the current run without its epoch.

This is not only an instrumentation mismatch. A missing epoch reaches the fence as `nil` and cannot match a persisted positive claim epoch.

The client quiescence path also loses the field upstream:

- `src/seon/agent/run.cljs:296-300` omits claim epoch from `::current-runs`.
- `src/seon/agent/run.cljs:312-318` queries only agent ID and run ID.
- `src/seon/agent/run.cljs:357-365` projects only agent ID and run ID.

**Fix shape:** retain claim epoch in every current-run acquisition and destructuring path, then pass it through each mutation request.

### BN-2 — Source-free releases derive two inventory paths that do not exist

**Status:** verified, breaks now in a release package  
**Producers:**  
`3fdde32d4ccead86f6aea1eb09502b6e992b7124` — `feat(build): publish artifact function inventories`  
`69d53311bfaf1db6a3e493a2b62fc70cfe2969bb` — `Derive runtime trust from transaction provenance`

The release publishes distinct members:

- `runtime/client-program-inventory.edn`
- `runtime/execution-program-inventory.edn`

See `script/seon/dev/release.clj:51-61,818-825`.

`src/seon/client.cljs:953-980`, especially `:965-974`, instead derives each inventory by appending `program-inventory.edn` to the corresponding bundle directory.

That works in the checkout because the bundles occupy separate directories. In the package, `script/seon/dev/config.clj:479-488` places both bundles under `runtime/`, so both derived paths collapse to nonexistent `runtime/program-inventory.edn`. The packaged inventory bytes exist, but the consumer names the wrong files.

**Fix shape:** carry the exact client and execution inventory member paths and digests through the artifact/launch descriptor; do not derive them from bundle directories.

### BN-3 — Standalone `bin/test-writer` requires an artifact produced by an earlier operator run

**Status:** verified structural break, masked in the current checkout  
**Producer:** `79493e60404587c2a15c2839f9fcb1c04d4fd769` — `test(db): initialize fixtures from compiled program rows`

`test/seon/db/writer_test_support.clj:51-58,123-174` now requires:

- `tmp/seon-operator/artifact.edn`, unless `SEON_PROC_DIR` is supplied;
- the manifest-selected program-source artifact;
- the manifest-selected program-row artifact.

The canonical `bin/test-writer:17-22,83-86` prepares writer dependencies but does not build the client, derive program rows, publish an artifact manifest, or set an independently prepared process directory.

The current checkout masks the problem because the frozen-tree reset already populated `tmp/seon-operator`. A clean checkout or isolated CI invocation has no producer.

**Fix shape:** make `bin/test-writer` explicitly prepare and select a frozen program-row artifact, then pass its exact manifest path to fixtures.

### BN-4 — Program-row derivation still executes ACME’s top-level 12-second timer

**Status:** verified deterministic build stall  
**Producer:** `4b3d320934fc21482de9618dc49012e7e77fff39` — `Publish compiled program rows as build artifacts`

The derivation hook runs a temporary compiled client with Bun:

- Hooked builds: `shadow-cljs.edn:61-70,106-115,145-154`
- Subprocess: `script/seon/dev/program_artifact.clj:238-300`

The known #3 repair in `73d41179d` disables Shadow devtools, but it does not remove configured preloads already injected into module entries. Shadow’s maintained implementation confirms preload injection is mode-based:

- `reference-code/shadow-cljs/src/main/shadow/build/targets/node_script.clj:41-47`
- `reference-code/shadow-cljs/src/main/shadow/build/targets/shared.clj:252-257`

ACME preloads `acme.pod` at `shadow-cljs.edn:116-123`. That namespace executes a top-level timer at `acme/src/acme/pod.cljs:29-33`:

```clojure
(js/setTimeout #(context/install-all!) 12000)
```

Every ACME derivation therefore retains the temporary Bun process for at least 12 seconds and then attempts database-dependent initialization outside normal pod startup.

No second preload timer was found; the default and benchmark preload is definition-only.

**Fix shape:** move delayed installation into `acme.pod/-main`, keep namespace loading side-effect free, and prove the derived executable exits immediately.

### BN-5 — The reply-policy split did not reach run-limit and historical-web consumers

**Status:** verified structural mismatch; active when agent policy differs from legacy cluster mode  
**Producer:** `c9c731ad293e3d8de99884298dbd559cc6ef4f5c` — `Separate wire streaming from reply evaluation`  
**Partial follow-up:** `c0f6db879883af02bd4c542a0080fded649d8359`

The new contract separates:

- `:seon.ai/wire-stream?`
- `:seon.ai/reply-evaluation`

Current variants already diverge:

- Planning: wire streaming true, batch evaluation at `config/system.edn:401-417`.
- Execution: wire streaming false, batch evaluation at `config/system.edn:424-432`.

Stale consumers:

- `src/seon/agent/run.cljs:192-196,493-499` selects a form-denominated or turn-denominated run limit from legacy cluster `:seon.config/repl-mode`.
- `src/seon/web/serve.cljs:1140-1142,1152-1175` validates historical attempt streaming against the legacy cluster mode instead of the effective attempt/agent wire policy.

**Fix shape:** acquire agent and cluster policy rows, resolve them through `ai/reply-policy-from-rows`, use reply evaluation for run bounds, and use wire-stream policy for historical transport validation.

### BN-6 — Three new web limits validate and persist but have no runtime reader

**Status:** verified; breaks when overridden  
**Producer:** `34f0373e87eefdff4b06f389a194fa5fa648e2c5` — `Harden claimant persistence and runtime limits`

Inert facts:

- `:seon.config.web/default-link-count`
- `:seon.config.web/maximum-html-characters`
- `:seon.config.web/maximum-html-nesting-depth`

They are declared and resolved at:

- `config/system.edn:326,329-330`
- `src/seon/config/resolve.cljc:157-166,1144-1153,2020-2029`

The Bun web leaf still uses literals:

- `src/seon/agent/web/pod.cljs:26,31-32`
- Link cap at `src/seon/agent/web/pod.cljs:42-44`
- Parser guards at `src/seon/agent/web/pod.cljs:387-388`

No runtime read of the three attributes was found. Manifest overrides therefore appear accepted while behavior remains unchanged.

**Fix shape:** thread the acquired facts into the existing extraction call, or remove the facts explicitly if the retiring Bun leaf will never consume them.

### BN-7 — Computed boot schema population drops persisted attributes

**Status:** verified current test failure; broader cold-boot regression  
**Producer:** `e84e10bf5` — `refactor(schema): compute boot genesis population`

`src/seon/client.cljs:737-739` replaced the maintained bootstrap population with `schema/canonical-database-attributes`.

The computation at `src/seon/schema.cljc:1452-1482` includes:

- entries from maps marked `:seon.db/entity true`;
- standalone registrations only when they carry a persistence facet.

It therefore misses standalone persisted scalar attributes and attributes inside persisted component shapes that are not marked as entity maps.

Eighteen entries from the previous boot population are absent from the current computed result:

- `:my.kb.shared/at`
- `:my.kb.shared/text`
- `:my.kb/confidence`
- `:my.kb/source-line`
- `:my.kb/source-path`
- `:my.kb/verified-at`
- `:seon.agent.ctx/priority`
- `:seon.agent.testrun/agent`
- `:seon.agent.testrun/errors`
- `:seon.agent.testrun/failed`
- `:seon.agent.testrun/framework`
- `:seon.agent.testrun/line`
- `:seon.agent.testrun/message`
- `:seon.agent.testrun/passed`
- `:seon.agent.testrun/path`
- `:seon.agent.testrun/test-name`
- `:seon.db.id/generator`
- `:seon.render/full?`

The immediately observed failure is `test/seon/client_initialization_test.cljs:207`, which requires `:seon.render/full?`. That attribute is registered at `src/seon/render.cljc:140` and consumed before lazy repair at `src/seon/agent/ctx.cljc:578` and `src/seon/agent/ctx/transcript.cljc:819`.

Other concrete consumers include:

- Context ordering via `:seon.agent.ctx/priority`.
- Test-run transactions at `src/seon/agent/testrun.cljs:173-200`.
- Generator queries in `src/seon/db/id.cljc`, `src/seon/db/restore.cljc`, and `src/seon/runtime/recovery.cljs`.
- Knowledge provenance transactions in `src/my/kb.cljc`.

**Fix shape:** derive boot attributes from the real persisted shapes, including nested component maps and schema-metadata attributes; place standalone persisted fields in their owning entity shapes or give them an honest persistence facet. Do not restore a second hand-maintained list.

### BN-8 — Guard calibration benchmark still uses the removed “fuel” contract

**Status:** verified; breaks when the benchmark is invoked  
**Producer:** `946e1a190d4d85fb1bd4c0929d416a9ba2fb7857` — `Rename guard fuel to interpreter-step budget`

Stale consumer `bench/u1_guard_calibration.clj`:

- Lines `28-35` use `::guard/fuel`, `::guard/fuel-config-key`, and the three removed `...-fuel` config attributes.
- Line `59` calls removed `guard/steps-used`.

Current owners:

- `src/seon/host/guard.cljc:9-27` expects interpreter-step budget keys.
- `src/seon/host/guard.cljc:83-88` exports `interpreter-steps-used`.

The removed config keys occur nowhere else in live source.

**Fix shape:** rename the benchmark request keys, configuration keys, and accessor to the interpreter-step vocabulary.

### BN-9 — Claim-epoch tests and fixtures retain the pre-fence request shape

**Status:** verified test breaks  
**Producer:** `901eee2d3`

The original checkpoint recorded 11 assertion failures from this seam:

- Seven in `test/seon/agent_lifecycle_test.cljs:240-245,346,353`.
- Three in `test/seon/agent/run_test.cljs:207-215`.
- One in `test/seon/agent/ticker_test.cljs:52-55`.

The wider caller sweep found additional stale consumers:

- Eight direct mutation calls in `test/seon/agent/run_test.cljs:198-203,265-278,331-333,374-376`.
- Old watchdog row/request shapes in `test/seon/agent/ticker_test.cljs:45-55`.
- Quiescence fixtures at `test/seon/client_quiescence_test.cljs:64-69,95-101,157-160`.
- Web current-run and close fixtures at `test/seon/web/serve_test.cljs:938-964`.
- `test/seon/agent_lifecycle_test.cljs:33-36` defines a shared current run without claim epoch.

Two untracked SCI probes also retain the old request:

- `tmp/sci-probe/exec-src/seon/execution/b2_driver.cljs:270-271`
- `tmp/sci-probe/exec-src/seon/execution/u15_driver.cljs:316-317`

**Fix shape:** give every held-run fixture a known positive epoch, pass it through mutation requests, and add explicit matching-versus-stale epoch cases.

### BN-10 — Provider-descriptor policy changed while OpenAI-compatible tests and docs retained the old defaults

**Status:** verified test/documentation break  
**Producer:** `e88057afd` — `Resolve hosted providers from descriptor rows`  
**Catalog producer:** `924c8ad30` — `Add hosted provider descriptor catalog`

`src/seon/ai/openai_compat/core.cljc:20-55` now derives request fields from descriptor policies:

- DeepSeek uses its `thinking` toggle.
- `reasoning_effort` is emitted only for descriptors with `:openai-reasoning-effort`.
- Generic `:openai-compat` has a shipped DeepSeek-compatible base URL and an omit policy.

Five checkpoint assertions still expect the previous behavior:

- `test/seon/ai/openai_compat_test.cljs:57`
- `test/seon/ai/openai_compat_test.cljs:71`
- `test/seon/ai/openai_compat_test.cljs:331-332`
- `test/seon/ai/openai_compat_test.cljs:402-405`

`src/seon/ai/openai_compat.cljs:12-15` also claims generic `:openai-compat` has no shipped base URL, which is contradicted by `src/seon/ai/provider.cljc:206-215`.

The descriptor-driven runtime appears intentional; the stale consumers are the tests and namespace documentation.

**Fix shape:** select descriptor identities that actually promise each tested policy, replace the obsolete missing-base-url case with an explicitly missing/unknown descriptor case, and update the namespace documentation.

### BN-11 — Provenance classification broke the transcript’s supported direct-call path

**Status:** verified checkpoint failure; direct-call runtime impact suspicious  
**Producer:** `69d53311b` — `Derive runtime trust from transaction provenance`

`src/seon/error.cljc:211-235` now treats absent provenance as agent-authored. `src/seon/render.cljc:54-73` therefore requires the authored invocation door unless the supplied projection proves the symbol is compiled core.

`src/seon/agent/ctx/transcript.cljc:1262-1306` supplies compiled renderer functions for direct transcript calls but does not supply `:seon.schema/projection`. The renderer is consequently classified as authored and fails to resolve.

Observed failure:

- `test/seon/agent/ctx/transcript_test.cljs:645-653` returns text containing `render failed`.

This matters beyond a fixture because the source comments and `src/seon/agent.cljs:136` expose direct transcript invocation as supported behavior.

**Fix shape:** have the shared direct-call input builder supply the exact compiled projection or artifact-export evidence. Do not restore namespace-prefix trust or a static symbol whitelist.

## Suspicious current seams

### S-1 — Default `grep-graph` is at 98.75% of its frame before envelope overhead

**Status:** suspected breaks now; growth seam verified  
**Population producer:** `a332ecb5fb258256d70c351ad032751aa4e60fb6` — `feat(runtime): acquire exact artifact inventories`

Current inventory:

- 5,490 total rows.
- 2,948 functions, including 1,570 private functions newly entering the corpus.
- 2,374 schemas.
- 168 namespaces.

`src/seon/agent/search/internal.cljs:403-417,455-462,498-518` acquires the entire selected corpus in one grouped request, then applies the regex and result cap after receipt.

Measured raw EDN tuple sizes:

- Functions: 3,398,340 bytes.
- Schemas: 367,667 bytes.
- Namespaces: 376,008 bytes.
- Combined default target set: 4,142,015 bytes.

The default frame is 4 MiB at `src/seon/db/protocol.cljc:117-120`, and grouped result weight defaults to that frame at `:1483-1492`. The raw tuples consume 98.75% before protocol envelopes and wire encoding.

No live database query was executed, so immediate failure remains suspected.

**Fix shape:** page each identity stream, apply the regex page-by-page, and return only capped hits.

### S-2 — Packaged render-context declarations silently lose their files

**Status:** verified packaging mismatch; silent degradation rather than boot failure  
**Producer:** `b6183ee9d494bf4d7b6230b356ec6bda5a651d5e` — `Resolve render inputs as database config facts`

Boot readers:

- `script/seon/dev/config.clj:231-242`
- `src/seon/config.cljs:89-98,303-312`

The manifest declares `AGENTS.md` and related render-context paths at `config/system.edn:253-262`. The release assembly at `script/seon/dev/release.clj:51-79,830-855` packages the configuration directory but not arbitrary files referenced by the selected manifest.

Both readers omit missing files, so a source-free package silently derives a different configuration singleton without the declared fingerprints. `:seon.config.render-context/soul-file-path` is also absent from the path-gathering function unless separately named by a block.

**Fix shape:** package every selected manifest-declared context file as a content-addressed member and rewrite packaged paths, or reject unavailable declarations explicitly.

## Breaks on growth

### G-1 — Complete committed host projection is near its fixed row and weight ceilings

**Status:** verified breaks on growth  
**Population producer:** `a332ecb5...`  
**Payload expansion:** `69d53311b...`

`src/seon/host/context.clj` retains:

- A 4,096-row population ceiling at line `1550`.
- Three complete queries capped at 4,097 rows and 3 MiB per member at `1566-1587`.
- Explicit rejection at `1608-1617`.

Current function population is 2,948 rows, or 72% of the row ceiling. Symbol/source EDN alone is 2,850,419 bytes, leaving only 295,309 bytes below 3 MiB before repeated provenance maps and protocol structure.

Fresh readiness has passed, so this is not classified as a current break.

**Fix shape:** page canonical identities and acquire bounded rows individually; remove the global whole-population governor.

### G-2 — The known #5 pager still assumes one canonical row is bounded

**Status:** verified breaks on growth; follow-on to #5, not a re-report  
**Producer:** `7a1c5de68` — `Page committed program acquisition by canonical row`

`src/seon/runtime/admission.cljs:200,241-260` pages one identity at a time but retains a fixed 60,000 result-weight per row.

Private-function admission changed the largest source sizes:

- Largest public source: 19,229 characters.
- Largest newly admitted private source: 22,903 characters.

One-row paging prevents aggregate corpus growth from tripping the limit, but one sufficiently large function source or schema form still exceeds it.

**Fix shape:** support identity-keyed source/form chunks under the configured frame limit rather than treating an entity as a bounded payload.

## Cleared seams and negative results

### Public signatures

Of 47 changed public definition forms, no second independent tracked signature seam was verified after the claim-epoch sweep.

Confirmed aligned:

- `seon.error/fault-for` callers now pass the classification projection; known #1 is fixed.
- `seon.agent.shell.core/run-request` callers pass configuration; known #2 is fixed.
- `seon.dev.program-artifact/publish-rows!` callers pass both source and row paths.
- `seon.dev.release/assemble-package!` callers supply program rows and both inventories.
- Transcript clock/timezone and render-strictness callers pass configuration.
- Added arities in admission, schema projection, instrumentation, and host context are backward-compatible.

### Config facts

- Of 46 new leaf inputs, 43 have at least one source reader; this is occurrence evidence, not full behavioral proof.
- The old operator readiness-timeout key has zero remaining occurrences after `b8216c27a`.
- Removed transcript `result-handles?` has no live source, test, or configuration consumer.
- Provider descriptors are included in the AI acquisition pattern.
- Initialization page rows have no fixed total-page-count ceiling.

### Schemas and identities

Five new identity attributes were traced without a stale query, pull, lookup-ref, transaction, or fixture consumer:

- `:seon.ai.attempt/id`
- `:seon.ai.provider/id`
- `:seon.config.render-context/file-path`
- `:seon.db.initialization/id`
- `:seon.program.edge/terminal-symbol`

The apparent removals of `:seon.fn/sym` and `:seon.schema/key` are moves into computed portable schema forms, not semantic removals.

No tracked references remain to retired execution-context request schema names, old eval namespace-analysis keys, or transcript `result-handles?`.

### Artifacts and subprocesses

- Program sources, program rows, and both inventory files have flush/publish producers.
- `f8358abec` adds program rows and both inventories to source-checkout immutable runtime roots and checks their digests.
- Release assembly contains all four artifact byte streams; BN-2 is a consumer path mismatch.
- Of six Shadow hook configurations, only program-row preparation launches a compiled runtime.
- Execution hooks publish inventories without running the execution bundle.
- The test artifact hook copies and rewrites artifacts without launching the compiled test runtime.
- Known #3’s Shadow devtools process is fixed; BN-4 is the independent preload-timer interaction.
- Known #4’s source-checkout membership is fixed; BN-2 and S-2 are separate package contracts.

## Recommended serial repair order

1. Thread claim epoch through all production current-run acquisition and mutation paths.
2. Fix release inventory path authority and prove a source-free package boot.
3. Make `bin/test-writer` produce/select its own frozen compiled program artifact.
4. Repair computed boot schema population and prove a fresh database reset.
5. Move run bounds and historical streaming checks onto the two new reply-policy axes.
6. Connect or retire the three inert web configuration facts.
7. Remove ACME namespace-load timers from derivation closures.
8. Update claim-epoch tests, provider tests/docs, transcript direct-call evidence, and the guard benchmark.
9. Page `grep-graph` and committed host projections.
10. Replace the admission pager’s single-row size assumption with chunked source/form acquisition.

The graduation gate remains a frozen-tree source-free package proof plus complete CLJS, writer, and operator gates after these producer/consumer contracts converge.