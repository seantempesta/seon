---
type: research
status: draft
created: 2026-09-21
tags: [deletion, publication, adoption, operator, clj-kondo, datahike, one-jvm, indexing]
---

# Deletion audit — source indexing, publication, adoption, operator

Read-only audit. No JVM, no gate, no MCP evaluation was run; every row below
was verified by reading the cited source in this checkout, and dependency
semantics come from the vendored forks. Grounding read end to end:
AGENTS.md §1, §2, §6; [the one-JVM redesign plan](../../steward-platform/plan/one-jvm-publication-redesign-2026-09-22.md);
[its landing note](../../steward-platform/research/one-jvm-redesign-2026-09-22.md);
the uncommitted lane draft [`one-jvm-lane-items-6-8-draft-2026-09-21.patch`](one-jvm-lane-items-6-8-draft-2026-09-21.patch)
(identical to `git diff` in the tree).

Area sizes as read: `src/seon/fn.clj` 3494, `src/seon/cluster.clj` 3603,
`src/seon/program.cljc` 1092, `src/seon/fn/analyzer.clj` 719,
`src/seon/cluster/source.clj` 575, `src/seon/test/cache.clj` 546,
`src/seon/operator.clj` 1219, `src/seon/operator/state.clj` 1627,
`script/seon/fresh_operator.clj` 3727, `bin/seon-hook` 1987, `src/seon/id.clj` 73.

**Estimated deletable: ~3,700 lines** — ~2,800 production/script/hook and
~900 test. Nothing below adds a mechanism, cache, constant or noun.

## 1. Layers kept beside a tool's own state

| src span | lines | duplicates / tool seam | recommendation | what preserves the function | risk |
|---|---:|---|---|---|---|
| `seon.fn/database-manifest` `src/seon/fn.clj:3269-3313`; `manifest-data`/`replace-manifest-artifacts` `:2187-2233`; `artifact-by-path` `:2162`; `manifest-function-symbols` `:2175` | ~115 | The manifest is a second copy of the published rows: `database-manifest` reads them back out of Datahike (`published-index-rows`, `file-identities`) and re-groups them into `:seon.fn.file/artifact` maps so `build-manifest` can diff maps that the database already distinguishes by datom | delete the manifest value; `build-manifest` takes changed paths + the previous database and returns rows | the digest difference (`cluster.clj:1839`) and the transaction report (`fn.clj:3129` `report-identities`) already name what changed | medium — `test.cache/manifest`/`head-manifest` read `manifest.edn` from a base directory (row 3) |
| `seon.fn/build-artifact` `:2116-2158`, `plan-file-change` `:2443-2551` + its only helpers `scalar-upsert-rows` `:2424`, `full-rebuild` `:2437`, `row-by-identity` `:2414`; `rows` `:2552`; `reconcile-tx` `:3115`; `backfill-contract-facts!` `:2663-2760`; output-path family `:1807-2001`; `currently-failing-functions` `:1593`; `functions-without-tests` `:1610` | ~660 | **No production caller.** Verified: `grep -rn "\bplan-file-change\b" src/ script/ bin/ resources/` and the same for each name returns nothing outside its own `defn`; only `test/` references them (`rows` 81 test refs, `build-artifact` 27, `reconcile-tx` 20). `reconcile-tx-in` `:3047` is the live owner | delete the vars and every test that only exercises them | the live path calls `reconcile-tx-in`, `analyzed-artifacts` `:2234`, `index!` `:3314` | low |
| `seon.fn.analyzer/discard-obsolete-cache-entries!` `src/seon/fn/analyzer.clj:223-259` and the second `clj-kondo/run!` it triggers `:285-288` | ~42 | clj-kondo's own cache: `from-cache-1` `reference-code/clj-kondo/src/clj_kondo/impl/cache.clj:26-35` reads an entry without validating `:filename`; `to-cache` `:65-79` rewrites it. Our sweep reads **every** transit entry in the cache under the file lock to find entries whose recorded file is gone — O(namespaces) per analysis, and can run the whole analysis twice | delete; **fork change**: make `from-cache-1` skip a `:disk` entry whose `:filename` no longer exists (~3 lines in our clj-kondo fork) | clj-kondo then answers with the same staleness rule for every consumer, not only ours | low — fork is ours |
| `seon.fn.analyzer/program-prelude` `:595-612`, `function-stub` `:580-594`, `stored-arglists` `:570-579`, used by `analyze-forms` `:690-698` with `{:cache false}` | ~55 | clj-kondo's cache already holds every first-party namespace's `var-definitions` with `:arglist-strs`, `:private`, `:macro`, `:fixed-arities` (`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:94-111`) and `load-when-missing` `impl/cache.clj:129-142` supplies them for required namespaces. We re-synthesize stubs from the database and then disable the cache | lint the agent's forms with the project cache dir instead of a synthesized prelude | the namespace prelude (`:557`) still supplies aliases; findings offsets unchanged | **medium** — an agent's SCI-only definitions are not in the cache; needs a probe before landing |
| `seon.cluster/source-artifact-file` + `write-source-artifact!` + `source-artifact` `src/seon/cluster.clj:1765-1798` (`build/current-src.edn`) | 34 | Datahike's branch head already is the published identity: `source/current` `src/seon/cluster/source.clj:139-149` returns branch + commit id from `registry/branch-commit-id`; the digest is a datom read at `cluster.clj:1801` | delete the file artifact | `current-publication` `cluster.clj:1799-1814` already answers from the commit | low, once row 3's base reader stops reading it |
| `seon.cluster/program-currentness` + `count-installed` + `require-coherent-program!` `cluster.clj:1698-1764` | 67 | Health inferred by counting `:seon.ns/name` and `:seon.fn/sym` rows and asserting exactly one recorded digest. Datahike already answers "is this the published commit": compare the cluster row's `:seon.source/commit-id` with `source/current` | delete; refuse on commit-id mismatch or absence | the adoption record `cluster.clj:2141-2149` is the fact | low |
| `seon.test.cache/ensure-base!` `src/seon/test/cache.clj:414-475`, `prepare-base!` `:379-413`, `manifest` `:476`, `head-manifest` `:484-501`, `newest-base` `:502-535`, `newest-manifest` `:536`, `reap!` `:366-378`, `referenced?` `:358`, `alive?` `:321`, `snapshot-git-sha` `:269`, `-main` `:542` | ~200 | `target/test-published-bases/<digest>/{ready.edn,references/<pid>.edn,base/manifest.edn}` is a hand-built content-addressed store with its own GC, liveness files and a `RandomAccessFile` lock — over an exported copy of a Datahike branch that is already content-addressed by commit id and already has a registry (`seon.cluster.registry`) and GC | delete; a worker forks `current-src` by commit id in the one JVM | `source/database` `source.clj:151-162` materializes any commit; the fork is a branch pointer | **high** — it is the gate's isolation boundary; belongs to the test-system lane, listed here because publication owns the export |
| `seon.test.cache/worker-checkout!` `:331-356` + `copy-checkout!` `:308-320` + `child!` `:276-307` | 80 | Git is the checkout tool; `cp -cRP` of a whole tree per worker duplicates what a `git worktree`/branch already is, and `input-paths` `:45-65` already shells to `git ls-files` | delete with row above once workers share the one JVM | — | high (same lane) |
| `seon.test.cache/toolchain-dependencies` `:217-231`, `gitlink-digests` `:190-216`, `test-input-digest` `:240-254`, called per publication from `cluster/source-snapshot` `cluster.clj:1619-1644` and `source/publication-input-digest!` `source.clj:352-369` | ~60 | Git already stores gitlink SHAs; `:seon.fn.file/digest` rows already store per-file digests (`fn.clj:3296`); the aggregate `:seon.source/test-input-digest` is a mirror of that set | delete the aggregate; compare the per-path rows | **blocked**: `seon.test` reads the aggregate at `src/seon/test.clj:916`, `:1365`, `:2161` — the gate must move to per-path comparison first (plan slice 4, item 2) | medium |
| `script/seon/fresh_operator.clj` process records: `valid-process-record?` `:144`, `read-process-records` `:156-185`, `write-process-record!` `:186-200`, `clear-process-record!` `:201-210`, `record-alive?` `:216`, `process-record-matches-advertisement?` `:220`, `matching-process-handle` `:228-243`; and `src/seon/operator/state.clj` claim files `:801-914`, `:940-1054`, `:1125-1149` | ~450 | The OS already answers this: `operator.state/observed-property-processes` `state.clj:1199-1223` reads `ProcessHandle/allProcesses`, extracts `-Dseon.operator.root` / `-Dseon.operator.generation` and the start instant — exactly the fields the EDN claim files mirror | delete the claim/record files; derive from the process table, and from the prepl for anything about the program | `process-identity` `cluster.clj:2284-2296` is already (pid, start-instant) and nothing else | medium — reclaim/cleanup ordering must be re-derived |
| Advertisement files and their repair: `advertisement-observations` `fresh_operator.clj:757-770`, `inconsistency-values` `:1323-1347`, `derive-cluster-truth` `:1348-1491`, `cluster-truth` `:1492-1569`, `repair-actions` `:1695-1740`, `apply-repair!` `:1741-1755`, `reconciled-truth!` `:1756-1769`, `clear-stale-advertisements!` `:3351-3362`, `print-process-record-census!` `:3363-3382`, `discard-unreadable-process-records!` `:3383-3392` | ~400 | A running JVM's own `running-instances` is the authority; the advertisement file is a mirror that goes stale, which is why `:stale-advertisement` / `:missing-advertisement` / `:misnamed-advertisement` and a repair pass exist at all. `responsive-advertisement?` `state.clj:1262-1298` already asks the prepl — and the prepl answer is the authority that the file pre-read re-decides (AGENTS.md §2.1) | delete the truth/repair layer; `status` is one prepl request; the file keeps one job, telling a cold command which port to dial | `mcp-runtime-observation` `cluster.clj:630`, `readiness` `:3432`, `banner` `:3476` already render live state | medium |
| Phase logs: `phase-log-complete?` `fresh_operator.clj:3008`, `phase-log-path` `:3014`, `operation-id` `:3019`, `latest-phase-log` `:3027`, `phase-logs` `:3036`, `reset-incomplete-phase` `:3046-3074` | 67 | An interrupted reset is derived by grepping `data/operator/operations/*.log` for the string `phase=<name> complete` — durable state as parsed log text, outside the database (AGENTS.md §2.2) | delete; a reset's progress is a fact or it is re-run (database data is disposable by ruling) | `bin/seon reset --force` is idempotent from zero | low |
| Offline readers: `offline-roster-form` `:881-894`, `offline-roster` `:895-919`, `test-status-reader-form` `:937-962`, `test-status-form` `:963-985`, `offline-test-status` `:986-1017`, `derive-namespace-test-statuses` `:1018-1082` | ~220 | A second program that opens the store directly and re-implements roster and test-status queries that `seon.cluster.registry` and `seon.test` already own | delete; cold status prints "no JVM running" and the one command that needs facts boots one | the same queries exist once, in the JVM | low |
| `bin/seon-hook` clj-kondo cache diagnostic: `cache-languages` `:318`, `cache-entry-files` `:329`, `read-transit-entry` `:337`, `readable-recorded-source` `:347`, `uncached-definition-analysis` `:356`, `source-defines-target?` `:379`, `packaged-builtin-defines-target?` `:392`, `cache-entry-missing-var-diagnostic` `:412-445`, `diagnose-blocking-findings` `:446-456` | ~130 | The hook lints with `--cache false` (`:250-292`) and then, on an unresolved var, reaches into clj-kondo's transit files and runs clj-kondo two more times to decide whether the cache lied | delete with the fork change in row 3 | publication's analysis (cache on) remains the authority for unresolved names | low |
| `bin/seon-hook` session digests: `content-digest` `:1688`, `scan-roots` `:1696`, `tree-digests` `:1715`, `session-key` `:1726`, `recorded-digests` `:1742`, `record-digests!` `:1765`, `derived-write-refusal` `:1774-1819` | ~90 | A third digest layer (after `:seon.fn.file/digest` rows and `test.cache/input-digests`) walking `src test resources script bin` after every tool call to find shell writes | replace with one prepl request carrying the paths the JVM should re-read; the JVM already holds the previous digests as datoms | `:seon.fn.file/digest` rows | medium — covers tools that name no path |
| `seon.fn/sha-256` `fn.clj:106-110`, `seon.test.cache/sha-256` `cache.clj:39-44`, `seon.schema/sha-256` `schema.clj:767` | ~18 | `seon.id/sha-256` `src/seon/id.clj:17-27` already takes ordered byte arrays and is the ruled one generator | delete the three copies, call `seon.id/sha-256` | identical output | low |

## 2. Duplicate paths

**Publication entry points (7 that reach one owner).** `refresh-source!`
`cluster.clj:2174-2228` is the single owner; reached by (1) `bin/seon init`
(`fresh_operator.clj:2680` `init-form`, no name), (2) `init --changed`,
(3) `init --dev NAME --changed` (adds `development-source-refresh!`),
(4) the edit hook, which spawns a detached bb worker (`bin/seon-hook:1594`
`launch-source-worker-unlocked!`) that spawns `bin/seon` (`:1531`) that
prepl-sends to the JVM, (5) `seon.operator/publish!` `operator.clj:551-560`,
(6) `seon.test.cache/prepare-base!` `cache.clj:379-413` (live prepl, else a
child JVM), (7) `seon.bootstrap-drive` `:450`. Plus `publication-base!`
`cluster.clj:2230-2282`, a second publication that exports a store copy.
`bin/seon init NAME [--force]` `fresh_operator.clj:2621-2679` is a fork, not
a publication.

**The one path that should survive:** the running JVM's prepl receives
`(refresh-source! root changed-paths development-cluster)`; it reads the
changed paths' bytes, the published database at `source/current`, and the
`:seon.source/commit-id` of the cluster row — and returns the new commit id.
Every other entry point becomes that request; only a cold start and
`reset --force` spawn a JVM. Deleting (5), (6), (7) and `publication-base!`
removes the second and third publication implementations; deleting the
hook's worker/queue/result files (`:1486-1665`, ~180 lines) leaves ~30 lines
that send the request and print the reply.

**Adoption/reload paths (4).** `development-source-refresh!`
`cluster.clj:2037-2156` (in place); fork from `current-src`
(`named-init-form`); import of an exported store (`publication-base!` →
`export!` → `test.cache/ensure-base!`); `require :reload` via
`load-development-definitions!` `cluster.clj:1971-1982`. Only the first two
should survive; both read the same published commit.

`reload-order` `cluster.clj:1922-1946` + `namespace-requires` `:1947-1962`
re-implement dependent ordering that `reference-code/clj-reload` owns
(`clj-reload.core/topo-sort`, its `:dependents` graph). It is 25 lines here
and correct, so the deletion is only worth taking if a reload ever needs
unload/reload semantics; until then, keep, and **do not grow it**.

**Progress/phase reporting: 9 mechanisms for one operation.**
`fn/report-index-progress!` `fn.clj:34-38` (14 call sites) with
`progress-line-budget`/`progress-stride` `:40-45` (one use, `:2636`);
`cluster/report-source-progress!` `cluster.clj:98-118` (31 call sites) which
*also* writes the lock-holder phase and probes `PrintWriter.checkError`;
`cluster/*boot-progress!*` + `boot-phase` `:247-270`;
`source/publish!`'s `progress!` argument `source.clj:384`;
`init-form`'s progress atom + phase clock `fresh_operator.clj:2755-2790`;
`publication-output!` `:2804-2814`, which parses the string `"● current-src: "`
back out of stdout; the lifecycle `:seon.operator.lock/progress` atom
(`state.clj:1241` `event-silence-backstop-ms`); the hook's `SOURCE_PROGRESS`
log lines `bin/seon-hook:1554`; and `bin/test: SOURCE` printing in
`cache.clj:388`. One `progress!` argument threaded from the request, printed
once by the caller that asked, replaces all nine; deleting the stdout
re-parse is required to stop a formatting change becoming a data change.

**Bounds: 17 declarations for one path.** `operator.clj:65`
`lifecycle-lock-bound-ms`; `cluster.clj:1563`
`source-refresh-acquisition-bound-ms`; `state.clj:413`
`lifecycle-lock-timeout-ms`; `state.clj:1241` `event-silence-backstop-ms`;
`state.clj:22` `subprocess-cleanup-ms`; `fresh_operator.clj:81`
`operator-silence-backstop-ms`, `:244` `source-preflight-bound-ms`, `:273`
`publication-bound-ms`; `cache.clj:34` `inventory-bound-ms`;
`selection.clj:24` a second `inventory-bound-ms`; `bounds/silence-seconds`;
hook `:timeout-seconds` and `:call-timeout-seconds`
(`.claude/seon-hook.edn`); `dev/clj_kondo.clj:13`; `dev/dependency_digest.clj:52`;
`dev/changed_test.clj:19,311`; `operator.clj:919`
`projected-delete-ms-per-file`. Each publication phase should carry the one
declared bound the request hands it (plan slice 5); every other constant here
is deletable once the phases fail on their own bound.

## 3. O(program) steps still on the edit path

Measured phases for a 2723 ms one-file docstring edit
([landing note](../../steward-platform/research/one-jvm-redesign-2026-09-22.md), "Item 5 measured"):
source build 553 ms, analysis/artifact replacement 475 ms, publication
reconciliation 303 ms, adoption reconciliation 406 ms, instrumentation 76 ms,
and a separate 248 ms projection derivation.

| Phase | Where | What is proportional to the whole program | What it should be | Seam that already does it |
|---|---|---|---|---|
| "source build" (553 ms) | `cluster/source-snapshot` `cluster.clj:1619-1644` → `test.cache/toolchain-dependencies` `:217` + `gitlink-digests` `:190` + `input-paths` `:45`; and `full-source-refresh!` `cluster.clj:1832` re-observes the same digests a second time at `:1893-1896` for the race check | two `git ls-files` subprocesses and a hash of every toolchain file, twice per publication | hash the named changed paths only; compare gitlinks by Git's recorded SHA, not by hashing | `source/path-digests` `source.clj:109-124` already hashes only named paths; `stored-path-digests` `:125-138` reads the stored ones |
| complete publication ("program population compiled: 30320 entities", 117 s at reset) | `fn/compile-index-transaction` `fn.clj:2899`, `commit-index-phase!` `:2858`, `index-tempids` `:2873` | the whole population rebuilt as one transaction with string tempids minted per identity | unchanged at reset (a declared once-only cost), but the tempid map is O(rows) work that Datahike's upsert on `:db.unique/identity` already performs | Datahike identity upsert; `reconcile-tx-in` `:3047` |
| first development adoption | `development-source-refresh!` `cluster.clj:2067-2069`: with no prior commit it calls `(published-index-rows published-database)` — `fn.clj:3149-3167` enumerates **every** identity attribute value and pulls each entity | O(program) pull to compute identities that the publication's own report already produced | take the identities from `source/changed-identities` `source.clj:164-192` in all cases; on a first adoption the cluster forked the commit, so the answer is "converged" | `report-identities` `fn.clj:3129-3147`; `:seon.source/commit-id` compare `cluster.clj:2049` |
| projection derivation (248 ms / 1560 contracts) | `source/database` `source.clj:151-162` calls `schema/projection-from-database` on every newly materialized commit; `db/carry-derived-projection` `db.clj:272-292` does it again with a delta compose | a full Malli registry compile per database value, on the edit path | carry the projection the publisher already holds onto the commit value it just wrote; for a changed schema key, `schema/projection-with-schema` `schema.clj:2811-2880` already recompiles only the affected keys and their dependents | `db/carried-projection` `db.clj:1219-1225`; `projection-with-schema` |
| caller lint (278 ms) | `build-manifest` `fn.clj:2322` expanded `changed` with `caller-files` **before** analysis (HEAD) | every caller file of a changed declaration re-linted even for a docstring edit | lint callers only when a committed datom changed a contract | the uncommitted draft already moves this after the transaction and filters `:seon.fn/spec`/`:seon.schema/form` datoms — the right algorithm, but it lands as a **second** analysis pass and a **second** transaction (`fn.clj:3440-3467` in the draft). The same result fits in the publication's own scratch transaction, which is still open |
| namespace selection | draft `cluster.clj:2093-2094` unions `development-namespaces` over the previous **and** the current database | two full dependent traversals per adoption | one traversal from the report's identities; a removed namespace is named by `deleted-identities` `:2092` | `report-identities`, `:seon.ns/requires` |
| agent-turn analysis | `fn/analyze-forms` `fn.clj:951-1016` → `analyzer/program-prelude` `analyzer.clj:595` | synthesizes a stub for every referenced program function on every agent evaluation | clj-kondo's cache supplies required namespaces | `load-when-missing` `impl/cache.clj:129` |
| snapshot publication | `require-publication-resources!` `cluster.clj:2157-2173` | hashes **all** declared inputs of two trees (`test.cache/input-digests` twice) to compare resources | compare the two checkouts' Git SHAs, or drop the check with the snapshot | `snapshot-git-sha` `cache.clj:269` |

## 4. The operator

| Component | Lines | Survives once every command is a prepl request |
|---|---:|---|
| `bin/seon` | 26 | yes — argv → bb → operator |
| `script/seon/fresh_operator.clj` | 3727 | ~600: argument parsing, the prepl client (`prepl-eval!` `:1820-1910`, `read-prepl-reply` `:1166`, `live-root-value!` `:1609`), cold `launch!` `:2129-2163`, printing |
| `src/seon/operator.clj` | 1219 | ~500: `start!`/`stop!`/`restart!` `:70-96`, `connection` `:168`, `status` `:180`, `collect!` `:1112` and the store-collection owners, `refork!` `:1204` |
| `src/seon/operator/state.clj` | 1627 | ~700: the lifecycle `flock` (`:436-776`), footprint/space (`:1457-1522`), destructive-path admission (`:1585+`) |
| **total** | **6599** | **~1800** |

Derivable instead of stored: **process records** → `ProcessHandle` +
`-Dseon.operator.root` (`state.clj:1199-1223`); **generations** → (pid,
start-instant), which `cluster/process-identity` `cluster.clj:2284-2296`
already declares is the whole identity; **orphan census** →
`observed-property-processes` filtered by root, with the prepl deciding
responsiveness (`state.clj:1262-1298`); **advertisements** → the JVM's
`running-instances`, with one file per root holding only the prepl port for a
cold dialer. `await-advertisement!` `fresh_operator.clj:2266-2402` (136
lines) polls a file for a readiness that `cluster/readiness` `:3432-3475`
already returns as a value: with one JVM, start is a request that returns
when the graph is standing.

What genuinely needs a child process: the cold boot (no JVM yet) and
`reset --force`. Everything else — init, init --dev, fork, status, config
apply, stop — is a form the live JVM evaluates.

## 5. Tests in this area

~30 namespaces named `publication_*`/`source_*`/`adoption_*`, most holding a
single `deftest`. Classification:

| Test | Verdict |
|---|---|
| `test/seon/fn_test.clj` sections exercising `build-artifact` (27 refs), `rows` (81), `reconcile-tx` (20), `plan-file-change` (4), `artifact-by-path` (6), `manifest-function-symbols` (5), `output-path-report` (2) | **delete with the vars** — they test mechanisms with no production caller (§1 row 2); ≈700-900 lines |
| `test/seon/fn/publication_toolchain_test.clj:9` `analyzer-configuration-is-a-toolchain-input` | delete with the aggregate toolchain digest (§1) |
| `test/seon/cluster/publication_facet_test.clj:11` (`:seon.test/long-ms 600000`) | **algorithm defect + wrong harness**: it publishes the entire canonical program to assert three Malli error facets validate. Retarget at a projection fixture; the bound then is milliseconds |
| `test/seon/cluster/publication_host_test.clj:19` (`long-ms 1200000`) | genuinely long (boots a real cluster in the test JVM) but 20 minutes is the absence of a bound; after slice 1 the measured boot is 12.8 s — declare ≤ 60 s |
| `test/seon/cluster/source_nochange_test.clj:8` (`600000`) | keep the assertion (zero transactions, zero populations); the bound is paid by the initial complete publication — **algorithm defect**: measured no-change is 264 ms, so the declared allowance is 2,000× the work |
| `publication_adoption_test.clj:16,40`, `publication_reuse_test.clj:23`, `publication_export_test.clj:17`, `fn/publication_cache_test.clj:10`, `fn/publication_test.clj:40,114`, `test/publication_test.clj:13` (600000-900000) | each pays one complete publication for fixture construction — **algorithm defect at the fixture**, not the assertion; one shared published base in the JVM (a branch fork, milliseconds) removes the bound. The assertions are class-correct; keep them |
| `boot_test.clj:875,965,1144,1800` (600000), `cohost_boot_test.clj:101` (180000) | genuinely long: real boots. Declare against the measured 12.8-16.2 s boot, not 10 minutes |
| `publication_convergence_test.clj`, `publication_delta_test.clj:81`, `publication_notes_test.clj`, `publication_inputs_test.clj:14,38,78,116`, `adoption_rows_test.clj`, `publication_findings_test.clj`, `cluster/publication_test.clj` | keep, fast, one class each |
| ~10 single-`deftest` `publication_*` namespaces | consolidate into one namespace per class (≈150 lines of preamble); a smaller suite is the wanted outcome |
| `test/seon/cluster/publication_delta_test.clj` + `test/seon/fn/publication_cache_test.clj` (uncommitted) | the draft's own regressions; 3 errors recorded in the landing note — not green, not landed |

Of the 120 `:seon.test/long-ms` declarations in `test/`, 21 are in this area;
**15 are algorithm defects** (a complete publication or a spawned JVM inside a
fixture), 6 are genuinely long boots, 2 are deletable with their mechanism.

## 6. What is genuinely needed, and how each survives

| Function | How it survives |
|---|---|
| An edit becomes program facts in ~1 s | one prepl request: changed paths → digest difference → clj-kondo lint of those files with its own cache → transact the difference on `current-src` |
| An edit becomes loaded code | `require :reload` over the namespaces named by the transaction report, ordered by `:seon.ns/requires` (`cluster.clj:1922`) |
| Contracts stay armed | re-arm only wrappers whose contract digest changed (`src/seon/instrument.clj:844` `current-wrapper?`) |
| A fork is a branch | `registry/branch!` from the published commit id — milliseconds (`source.clj:229`, measured 0.34 s in slice 1) |
| Reset from zero is one command | `bin/seon reset --force`: destroy, one complete analysis (10.2 s measured), one population, start |
| A cold machine can still act | one child JVM boot; every later command is a request to it |
| The gate proves a commit | the gate forks the published commit in the same JVM instead of copying a checkout and exporting a store |
| Publication refuses honestly | `assert-clean-analysis!` `fn.clj:1093`, deletion refusal in the writer, typed `:seon.error` — unchanged |

## Not verified

- No measurement was taken here; every number quoted is from the landing note
  or a comment in the cited source.
- Whether `seon.test`'s three reads of `:seon.source/test-input-digest`
  (`test.clj:916`, `:1365`, `:2161`) can be satisfied by per-path rows — that
  is the test-system lane's call.
- Whether clj-kondo's cache can answer for an agent's SCI-only definitions
  (§1, `program-prelude` row); needs a live probe.
- Line estimates for `fn_test.clj` deletions are from reference counts, not
  from reading all 2,967 lines.
- **Documentation drift found while verifying:** AGENTS.md and
  [the plan](../../steward-platform/plan/one-jvm-publication-redesign-2026-09-22.md)
  both cite `src/seon/instrument.clj:593` as the re-arm comparison. At this
  HEAD that line is inside an error-key list; the comparison is
  `current-wrapper?` `src/seon/instrument.clj:844-858` and the digest is
  recorded by `arm-var!` `:873`. Fix both citations in the commit that next
  touches either file.
