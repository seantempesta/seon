---
type: research
status: evidence — read-only census; implementation frozen
created: 2026-09-24
scope: every with-redefs, with-redefs-fn, alter-var-root and intern in test/, fixture root restores, second worlds, hand-built worlds
pins: working tree of refactor/agent-platform at 8bb0eea92 (plus uncommitted lane edits in test/), analysed 2026-09-24 ~18:35Z; live default JVM (MCP jvm mode, read-only)
companion: test-survey-2026-09-24.md (per-namespace verdicts)
---

# Test redefinition census (2026-09-24)

**What exists.** `seon.test/run` already runs each test as one isolated agent: branch + SCI fork + custody
(`member-result`, `src/seon/test.clj`). Around it the tests build worlds of their own. They redefine
production Vars (359 sites), take a second branch (`with-database`, 936 sites), fork a second context
(`fork-cluster-ctx`, 238 sites), hand-build cluster handles and environments (108 sites), and open raw stores
and SCI contexts (≈90 sites). **The principal approach** is one world value per member that carries
its capabilities (provider, environment, roots, dials). The production owner reads those capabilities
from the world instead of from an ambient Var. Each redefinition then becomes world data, an explicit
argument, a PLATFORM member, or a deletion.

## Numbers first

| measure | count |
|---|---:|
| redefinition sites (`with-redefs` 261, `with-redefs-fn` 58, `alter-var-root` 17, `intern` 23) | **359** in **108** files |
| redefined target rows (one site may bind several Vars) | 484 |
| distinct targets | 227 (148 first-party `src` Vars, the rest dependency or test Vars) |
| enclosing form of the 484 rows: `deftest` / private or public helper / fixture | 448 / 34 / 2 |
| of the 148 `src` targets, `:seon.fn/host-bound? true` on default | 42 (incl. `seon.ai/complete`, `seon.sci.eval/evaluate`, `seon.sci.kernel/invoke`, `seon.turn/step`) |
| `use-fixtures` sites | 12 in 11 files: 5 restore Var roots, 3 mutate the global Malli registry (`call-with-registration-delta`), 4 other |
| second world: `with-database` sites / deftests with exactly 1 / with 2+ | 936 / 887 / 24 (kondo `:from-var`) |
| hand-built worlds (sites): `cluster-handle` 58, `environment` 50, `store/open-branch!` 44, `sci.eval/acquire!` 33, `agent/acquire-context!` 29, `d/connect` 19, `d/create-database` 17, `sci/init` 11, `registry/branch!` 9, `d/branch!` 5 | 275 |
| immutable `d/with` worlds (admissible when validated like the writer, skill §Canonical fixtures) | 44 sites in 11 files |

The inventory is scripted, with no regex over code. clj-kondo analysis of `src` and `test`
(`--analysis {:var-definitions true}`, 6.9 s) supplies resolved Var usages with enclosing `:from-var`.
A rewrite-clj walk of all 342 test files finds each redefinition form and its bindings (5.2 s). Each
bound symbol is resolved by joining its position to the kondo usage at that position. The 58 targets
bound through a local (`(ns-resolve 'seon.turn 'error-tx)`, `gate-var`, `private-var`, `bridge-var`) were
resolved by reading their binding site. Scripts live in the session scratchpad and are not committed
(they are one-shot census scripts, not checks).

## Classification by purpose

Classes follow the rows' own values: a throwing replacement is failure injection; a replacement
that calls the original and records is a spy. "src" means the target is defined under `src/`;
"dep" means a dependency Var (Datahike, Malli, http-kit, clojure.core).

| class | rows | sites | files | homes | top targets (rows) |
|---|---:|---:|---:|---|---|
| **(a) external-effect stub** | 136 | 120 | 34 | src 105, dep 12, tooling 19 | `seon.ai/complete` 77, `seon.ai/credential` 6, `clojure.java.io/resource` 5, `seon.ai/send-request` 5, `seon.schema.edn/read-schema-resource` 4, `seon.operator/prepl-value!` 3, `babashka.process/process` 2, `org.httpkit.server/send!` 2, `seon.fs/delete-recursively!` 2, `seon.web.jvm/credential` 2, dev-cache roots 7 |
| **(b) failure injection** | 78 | 71 | 36 | src 68, dep 9 | `seon.db/transact!` 10, `seon.db/q` 6, `seon.cluster.store/release-branch!` 3, `seon.cluster.prompt/prompt` 3, `seon.cluster.boot/readiness` 2, `seon.render.web/derive-page!` 2, `seon.db/pull` 2, `seon.blob/stage!` 2, `seon.blob/with-publication!` 2, `datahike.api/force-branch!` 1, `datahike.api/release` 1, `org.httpkit.server/run-server` 1 |
| **(c) timing / bound** | 15 | 14 | 10 | src 12, dep 3 | `seon.ai/delays`, `seon.ai/retry-strategy`, `seon.render.web/await-feed-package!`, `seon.blob/binary-threshold`, `seon.fs.jvm/{window-pass,parent-access,precondition-state}`, `datahike.gc-guard/acquire-{reachability,sweep}-permit!`, `seon.flow/submit!`, `seon.cluster.agent/{arm!,armer-step}`, `datahike.api/transact!` (never-acknowledged write) — 1 each |
| **(d) spy / observation** | 160 | 116 | 47 | src 139, dep 18 | `seon.sci.kernel/invoke` 9, `seon.sci.eval/evaluate` 9, `seon.db/transact!` 7, `seon.schema/load-projection` 4, `seon.db/{pull,datoms}` 4+4, `seon.sci.eval/install-row!` 4, `seon.schema/projection-from-database` 4, `malli.registry/fast-registry` 4, `seon.config/effective` 4, `malli.core/schema` 3 |
| **(e) first-party behaviour change mid-path** | 42 | 37 | 22 | src 39 | `seon.fn.analyzer/analyze` 3, `seon.render/acquire-context!` 3, `seon.render.web/debug-page-result` 2, `seon.turn/step` 2, `seon.turn/next-agent-work` 2, `seon.schema/handed-projection` 2, `seon.sci.eval/evaluate` 2, `seon.turn/{declared-sources,more-agent-work?,opening-db}`, `seon.bootstrap/next-entry`, `seon.oversight/flow-status` |
| **(f) throwaway Var the test created (allowed)** | 45 | 44 | 19 | test | 23 `intern`s into the test's own namespace or a forked SCI ctx (5 are `sci/intern`), plus redefinitions of test-namespace Vars (`seon.cluster.turn-test/injected-evaluation` 6, `seon.sci.eval-test/compiled-runtime-victim` 3, `seon.test-support/with-fresh-database` 2 as a spy) |
| **(g) root restore of armed Vars** | 8 | 8 | 5 | — | `alter-var-root … mi/-f->original` / `(constantly root)` loops in `instrument_test`, `profile_test`, `registry_isolation_test`, `test_support_test`, `instrument_replaced_roots_test` |

Where the enclosing test lands in the survey: (a) HARNESS 97 / PLATFORM 24 / helpers 14 / DELETE 1;
(b) 66 / 11 / 0 / 1; (c) 11 / 4; (d) 135 / 14 / 6 / 5; (e) 32 / 4 / 4 / 2; (f) 20 / 17 / 8;
(g) PLATFORM 4, helpers and fixtures 4.

**Is the target a default (production) Var?** In every class except (f), yes. All 431 rows in (a)–(e) rebind a JVM-global Var of the default JVM, and 384 of them are first-party. This is the law breach ("never `with-redefs` …
over default's Vars"): while a body runs, every concurrent member and live agent in the JVM sees the
stub.

## Replacement in a one-harness world

**SCI-fork-private redefinition is not a general replacement.** Every test in `test/` is a `:core`
test (measured on default: 2,163 of 2,163 source-bearing test rows carry
`:seon.schema.admission/source :core`), and its one executable body is the compiled Var (`seon.cluster/host-bound-declarations`,
`src/seon/cluster.clj:2794`). A compiled body calls compiled production Vars directly and never enters
SCI. The docstring of `host-bound-declarations` says a branch interprets only differing rows whose
`:seon.fn/host-bound?` is false. For a compiled caller that is the wrong question: the path never
reaches the interpreter at all. A fork-private redefinition is valid only for an agent-authored (SCI)
test whose whole call path is interpreted. That covers **0 of the 359 current sites**. Of the 148 `src`
targets, 106 are marked interpretable (`host-bound? false`). That fact would matter only after a test
moved into SCI, and the heaviest target, `seon.ai/complete`, is host-bound.

| class | replacement | production change? |
|---|---|---|
| (a) AI provider (`complete` 77, `send-request` 5, `credential` 6) | **Loopback provider as world data.** Start an http-kit server on port 0 in the member world and write its endpoint plus `:seon.config.ai/no-auth` into the cluster's AI config with `apply-config!`. The seam exists: `with-provider` / `a-loopback-provider-receives-the-descriptor-output-budget` (`test/seon/ai_stream_fold_test.clj:285`), and `a-no-auth-config-row-assembles-and-sends-without-authorization` (`ai_test`). This exercises the real HTTP leaf, streaming and attempt recording. It is UNVERIFIED that every stubbed turn test reaches the loopback through config alone. | none if config suffices; otherwise `seon.turn` (call at `src/seon/turn.clj:4543`) reads a provider capability from its cluster handle |
| (a) credentials (`seon.ai/credential`, `seon.web.jvm/credential`) | environment value in the world; no-auth for AI | `credential` reads the world's environment, not `System/getenv` (`src/seon/ai.clj:1032`, `src/seon/web/jvm.clj:369`). The ai docstring says the function exists so a test can redefine it |
| (a) resources and roots (`io/resource` 5, `seon.schema.edn/*` 7, `fn.analyzer/cache-directory` 1, dev-cache roots 7) | explicit root in the request | `seon.schema.edn` population functions take a resource root / manifest location; `seon.fn.analyzer/analyze` takes its cache directory; `dev-cache` takes a roots map (tooling, PLATFORM) |
| (a) processes, operator, prepl, http-kit socket writes | PLATFORM members with real children and sockets; `org.httpkit.server/write-state` backpressure → a real slow reader | none |
| (b) database refusals (`transact!` 10, `q` 6, `pull` 2, `projection-from-database` 2) | a real refusing input: invalid data through the real writer, or an unreadable database value (the pattern of `plan-reads-and-ownership-refuse-an-unreadable-database`) | none |
| (b) resource-failure injection (`release-branch!`, `retire-branch!`, `open-branch!`, `d/release`, `d/force-branch!`, `blob/stage!`, `run-server`) | real conditions where cheap (port already bound, released connection); the rest stay PLATFORM | open question: an execution handle carrying its own release functions as data |
| (b) prompt/turn internals (`prompt` 3, `derive-page!` 2, `submit-source!` 2, `readiness` 2) | build the refusing state through canonical writers (e.g. a budget config smaller than the history) | none |
| (c) retry and bound dials (`ai/delays`, `retry-strategy`, `await-feed-package!` backstop) | config dials in the world (`:seon.config.ai.retry/*` exist) | `await-feed-package!` caller reads its backstop from config; `seon.blob/binary-threshold` (a `def`) becomes a config dial |
| (c) race interleavings (`fs.jvm` 3, `gc-guard` permits 2, `arm!`/`armer-step`, `flow/submit!`) | PLATFORM with real interleavings | open: a hook argument is new machinery, so it needs review first |
| (d) spies | "happens once" / "derives once" regressions (≈90 tests) → the cache owner's miss count (law "Count misses") where one exists, otherwise delete as implementation detail. Call-argument capture → assert the durable facts the call produced | UNVERIFIED how many owners expose miss counts; `seon.profile` cells are JVM-global, so concurrent members would perturb counts |
| (e) behaviour changes | construct the real state through canonical writers (turn situations, readiness, analysis findings via the prebuilt-manifest seam `indexing-uses-a-prebuilt-manifest-without-analysis`), or delete | `seon.cluster.prompt/prompt` takes the rendered context value, separating rendering from pricing and selection (prompt_test ×3) |
| (f) | keep (throwaway namespaces or SCI ctx) | none |
| (g) and root-restore fixtures | PLATFORM only; `preserving-instrumentation-state` survives there. The 3 registration-delta fixtures go away once the projection derives from the database value (shrink-db row D) | none |

**Production functions that need an explicit capability argument (the real design changes):**
1. `seon.ai/credential` and `seon.web.jvm/credential`: environment from the world.
2. `seon.schema.edn` population readers (`default-resource`, `schema-resource-paths`, `read-schema-resource`,
   `resource-population`, `declaration-stamp`) and the config manifest location: an explicit resource root.
3. `seon.fn.analyzer/analyze`: an explicit cache directory.
4. `seon.blob/binary-threshold`: a config dial.
5. The `seon.render.web/await-feed-package!` caller: backstop from config.
6. `seon.cluster.prompt/prompt`: takes the rendered context.
7. Conditional: `seon.turn`'s provider call, only if the loopback-by-config route fails for some turn tests.
Items 1–6 are small (≤ ~10 lines each by inspection, UNVERIFIED). Item 7 would add one handle member.

## Helpers in `test/seon/test_support.clj` that exist only for second or hand-built worlds

| helper (line, lines) | uses | fate in one harness |
|---|---:|---|
| `with-database` (:654, 26), `with-branched-database` (:638, 15), `run-database-body` (:587, 15) | 936 | the member **is** the world; keep ≈8 lines for the 24 sibling-world tests |
| `held-handle` (:246, 15), `execution-handle` (:262, 15) | 37 | replaced by the world the body receives |
| `cluster-names` (:278, 5), `seeded-cluster-name` (:284, 13) | 1 | delete (they guess which cluster a fixture seeded) |
| `fork-cluster-ctx` (:298, 25) | 238 | delete: the member's ctx is already the production fork |
| `cluster-handle` (:357, 35) | 58 | delete: it builds a turn-loop handle from `config/defaults` with fresh channels, and the law says `seon.config/defaults` is not a cluster's configuration |
| `environment` (:393, 20) | 50 | delete: the member carries its environment |
| `preserving-schema-registry` (:816, 26) | 1 | delete after shrink-db D |
| `agent-value` (:325, 31) | 11 | keep as a thin agent-entrance helper, but read the member's effective config, not `config/defaults` |

That is ≈200 support lines, plus ≈1,280 call-site wrappers (936 + 238 + 58 + 50). The wrapper count
is by site; the lines saved per site are UNVERIFIED until a scripted sweep runs. `with-fresh-database`
(:603) stays for store-global subjects, but it calls `d/create-database`/`d/connect` directly, so it
should move to the store owner under the `datahike.api` law.

## Answer to the owner

Yes, the testing story can be coherent. One pipeline exists and is already the right owner. The
redefinitions fall into a few classes, and most have a replacement that is data. The biggest one, the
AI provider stub (≈88 rows), needs no production change if a loopback endpoint in config reaches every
turn test. Failure injection mostly becomes real refusing inputs. Spies become miss counts or
deletions. Six small owner changes remove the external stubs that remain. SCI-private redefinition
does not help `:core` tests, so the harness must pass capabilities in the world rather than rebind
Vars.

## Limits

Tree pinned at 8bb0eea92 plus uncommitted lane edits in `test/`. HEAD moved to 85a7214ed during the
census, adding +102/−9 lines in two test files, not recounted. Classification is by replacement value
shape (throw / records / calls original), with per-target review of classes (a), (c) and (e). A
multi-purpose target keeps its per-row class. Host-bound facts were read on default's live branch. No
test was run and nothing but this file was written.
