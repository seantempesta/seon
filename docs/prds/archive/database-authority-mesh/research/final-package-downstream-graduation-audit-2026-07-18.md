---
type: research
status: completed
tags: [research, flow, database, agent, web]
---

# Final package and downstream graduation audit

## Verdict

The production-package mechanism is implemented and has passed strong live
proof, but the current source has **not** passed the immutable final-package
matrix. The latest source-free runtime proof used source commit `9df21b23` and
was recorded by the documentation-only commit `706abf3d`. Runtime source later
changed at `4aa2f409`, where the execution-child idle default changed from 30
seconds to five minutes. That change affects the compiled execution artifact,
so the prior application digest and recursively read-only package cannot prove
the current runtime bytes.

The earliest missing contract is therefore one source-frozen release built
from `4aa2f409` or a reviewed descendant with no later package-input changes,
followed by the complete immutable package matrix. Existing historical proof
is reusable evidence about the retained mechanisms; it is not an exact-current
artifact admission.

## Source identity

Audit checkout head was `78694607faea22b2bbc7751acdb188e6d939b55a`.
The worktree contained only the pre-existing untracked `locks/` directory when
the audit began. This audit changed no source, lifecycle, test, or runtime
state.

| Identity | Meaning |
|---|---|
| `9df21b237c7f23eee46b1bbde0c44054235cd081` | Runtime source used by the latest recorded source-free package proof. |
| `706abf3dc2c48cb8e4ca23132862831e083147fb` | Documentation-only commit that records release application `0d8bc9c2ff2088de3103f951d1bd3f94f96d2c80cb4f4ccf6a035aaa9f96197b`, external package/state paths, restart/read-back, and unchanged package digest `2528ac81...`. Its parent is `9df21b23`. |
| `1aa1a36b`, `cb770db0` | Documentation and issue evidence only; no runtime or package input changed. |
| `bc3ba0a6` | Changes one writer test fixture plus documentation; it does not change runtime/package source. |
| `117e7064` | Changes documentation and the Datastar skill instructions; it does not change runtime/package source. |
| `4aa2f409ef829d8749685174d213ded341d62644` | Changes `src/seon/execution/host.cljs` and its focused test. This is a release input because the execution-child entry is compiled from the CLJS source. |
| `78694607` | Documentation-only record after `4aa2f409`; it does not supply a new release artifact. |

The diff from the package-evidence commit through the audit head contains only
one production source file, `src/seon/execution/host.cljs`, plus tests,
documentation, and skill instructions. That makes the evidence gap narrow but
real: there is no recorded application digest or immutable runtime drive for
the five-minute child-retention source.

## Dependency and implementation ledger

| Boundary | Retained owner and selected identity | Evidence read |
|---|---|---|
| Release inventory | `script/seon/dev/release.clj`, release format 1 | The closed manifest binds relative members and SHA-256 digests; `verify-package!` rejects missing, changed, symbolic-link, and undeclared entries. |
| Production programs | `script/seon/dev/artifact.clj` | One isolated release operation builds the existing pod and execution entrypoints without Shadow devtools or cache paths. |
| Operator | `seon.dev.cli` and `seon.dev.process`, carried by Babashka 1.12.218 plus `runtime/operator.jar` | The package process graph selects writer plus pod and starts execution children only on demand. Mutable state derives from external `SEON_STATE_DIR`. |
| JavaScript runtime | Patched Bun revision `d8ecf098572e2b8265b23e40c04efb4067e516cc` | The package manifest binds the exact Bun member, version, revision, and digest. |
| Database authority | Maintained Datahike `4c55791be1fb8bb8d9332f21c576f5c20b85b760` in the standalone writer | One JVM serves isolated database identities; one ordered writer remains per database while independent databases can proceed concurrently. |
| Downstream descriptor | `acme/artifact.edn` through the generalized artifact configuration | The descriptor selects downstream client/execution build IDs, outputs, cache, and manifest without an ACME-specific runtime path. |
| SDK | `seon.dev.release/build-sdk!`, SDK format 1 | The closed SDK archives committed Seon source, maintained Datahike source, patched Bun, and pinned dependency identities. |
| Metadata | `SOURCE.edn`, licenses, `THIRD_PARTY_NOTICES.md`, `sbom.cdx.json` | Recorded CycloneDX 1.6 output contains 36 npm and 81 JVM components and is manifest-bound. |

## Requirement-by-requirement audit

Verdicts distinguish a proven retained mechanism from proof of the exact
current source. “Historical pass” means the behavior was directly observed on
a prior content-identified package but must be repeated after the package-input
change at `4aa2f409`.

| Requirement | Authoritative evidence | Verdict | Next exact gate |
|---|---|---|---|
| Closed, relocatable production inventory | `release.clj` validates normalized relative paths, a closed member set, no symbolic links, every member digest, and one application digest. `release_test.clj` covers assembly and rejection. | **Implemented and tested.** | Rebuild and run `verify-package!` against the current release; retain its manifest and application digest. |
| Immutable package while running | The `9df21b23` release ran with a separate state directory; its complete tree digest stayed `2528ac81...` before and after agent work, restart, and shutdown. The earlier configurable-compression release similarly retained `f42552b7...`. | **Historical pass; exact current missing.** | Copy the current release to a recursively read-only directory, inventory it before startup and after shutdown, and require byte identity. |
| No producer checkout at runtime | The relocated package ran outside the checkout, and process inventory contained only the JVM writer, Bun pod, and demanded Bun child. Runtime entries had no producer-checkout or Shadow-cache path. | **Historical pass; exact current missing.** | Deny all reads beneath the producer checkout while performing fresh start, agent work, restart, and read-back from the current release. |
| No production dev tooling | Package process inspection found no watcher, Shadow, Clojure CLI, or development JVM. The package carries Babashka and the operator JAR as the production operator. | **Historical pass; exact current missing.** | Record the full current package process tree before and after child demand and fail if a watcher/compiler/development process appears. |
| Deterministic fresh initialization | Fresh package and autonomous-cluster drives initialized empty databases from the bounded program source and initial data. Config-free reopen performed no reseed. | **Historical pass.** The release mechanism is present, but the exact current release has not repeated empty-state initialization. | Start the current release with a new external state directory; record database birth, one converged no-write reopen, and the admitted application digest. |
| Clean restart and committed read-back | The `9df21b23` package completed `source-free current release green: 42`, restarted writer and pod, and rendered the committed result back. | **Historical pass; exact current missing.** | Restart the current package into new writer/pod generations, read the exact committed result through the agent page/feed, then shut down cleanly. |
| Child crash recovery | An earlier immutable release ran two exact `(js/process.exit 1)` failures, bounded automatic replacement, kept the Bun pod responsive, and later completed in a fresh child. | **Historical pass on an earlier release.** | Repeat the bounded child-crash/replacement/read-back journey on the current release; verify no sibling or parent failure and one settlement. |
| Feed and tool reconnect | A real browser remained open across supervised restart and posted/read a second value; MCP reconnect logic re-resolved replaced runtime endpoints. Current package feeds proved identity and configured gzip. | **Mechanisms proven separately; no exact-current combined matrix.** | Keep a browser feed open across current package restart, prove a later morph without reload, and re-run CLJ and cluster-qualified CLJS MCP discovery against replaced endpoints. |
| One JVM authority for isolated clusters | Live autonomous sibling proof used one watcher PID and one JVM writer PID for default plus experiment databases. Store IDs, database values, writes, agents, feeds, and pod restarts were isolated. A later secondary-cluster drive again found exactly one writer JVM. | **Historical integrated pass.** | On the frozen current source, run default plus one sibling concurrently through one writer, isolate writes, restart/close only the sibling, and verify default process identities and data remain unchanged. |
| Per-database write ordering and cross-database parallelism | `25f926fa` drove real transactions through the writer: A1 and B1 entered together, A2 waited for A1, and facts stayed in their selected databases. Executor/query tests cover concurrent immutable reads. | **Implemented and focused-test proven.** | Include the focused ordering test in the exact-current writer gate and retain the live two-database result. |
| ACME shares the default writer | The latest recorded ACME drive used its own watcher and Bun pod with the default JVM writer as its only external dependency. ACME restart left default PIDs unchanged; ACME shutdown left default ready. | **Historical current-source pass before `4aa2f409`; runtime child bytes now differ.** | Build/run the ACME overlay from the frozen current SDK, prove exactly one writer JVM, run an ACME child, restart/read back, then shut down ACME without changing default. |
| Downstream source-free production overlay | Read-only ACME package `8f8fb5da...` rendered downstream surfaces, ran two downstream functions, restarted/read back, and retained package hash `3e9775bd...`. | **Historical pass.** | Repeat from the current SDK/release input because the execution-child source changed. |
| Build without producer checkout | Two pristine SDK extractions under producer-read denial produced byte-identical ACME packages with application digest `8d5877b9...`. | **Historical pass; current SDK not rebuilt.** | Build two current SDK extractions with checkout reads denied and compare complete package trees, manifests, and application digests. |
| Downstream development MCP | SDK revision `245e96f5...` installed frozen dependencies under producer-read denial, started watcher/writer/Bun pod, and returned `:sdk-writer/42` plus `:sdk-pod/42`; normal shutdown drained all three. | **Historical pass.** | Re-run from one current SDK extraction and preserve endpoint discovery plus clean shutdown evidence. |
| Source metadata, licenses, notices, and SBOM | `release.clj` declares `SOURCE.edn`, Seon/Bun/Datahike/Babashka licenses, notices, and `sbom.cdx.json` as closed manifest members. Two builds recorded identical application digest `ce1f0284...`, manifest digest `21af6e45...`, and SBOM digest `66dac763...`; the CycloneDX 1.6 inventory held 117 components. | **Implemented and reproducibility-proven on prior source.** | Generate the metadata twice from current source, validate component counts against actual production closure, compare all bytes, and verify every metadata member through the release manifest. |
| Complete maintained correctness gates on package source | Before the latest package proof, recorded gates passed CLJS 1,140/5,078, writer 219/1,821, and operator 278/1,570. After `4aa2f409`, only the focused host gate (17/77) and live retention falsifier were recorded. | **Exact current missing.** | Run the complete maintained CLJS, writer, and operator doors once under a source freeze before admitting the final package. |
| Package evidence uses exact current runtime source | The latest package source is `9df21b23`; current runtime source includes `4aa2f409`. No later release application, manifest, or immutable-package drive is recorded. | **Failed graduation condition.** | Build and drive one release from the frozen current commit. Do not reuse `0d8bc9c2...`, `2528ac81...`, or earlier artifact identities as current evidence. |

## Exact-current graduation matrix

The shortest complete next gate is one coordinated source freeze. It should
produce evidence in this order:

1. Run the complete maintained ClojureScript, writer, and operator gates on
   the frozen source.
2. Build the default release twice and require byte-identical package trees,
   release manifests, SBOMs, and application digests.
3. Copy one release to a recursively read-only external directory, deny reads
   beneath the producer checkout, and put all state beneath a separate
   `SEON_STATE_DIR`.
4. Start from an empty database and record only the JVM writer and Bun pod;
   demand one execution child, prove the configured five-minute retention past
   the former 30-second boundary, execute real work, and record its process
   retirement or explicit shutdown.
5. Prove root, `/data`, agent/debug pages, identity and configured-gzip feeds,
   browser morphs, clean restart, feed reconnect, committed read-back, bounded
   child crash replacement, and clean shutdown.
6. Reverify the complete package inventory and package-tree bytes after runtime
   use.
7. Run default and a sibling cluster through the same writer, then run the ACME
   overlay from a current SDK while preserving one JVM writer and isolated
   cluster data/process lifecycles.
8. From two pristine current SDK extractions under producer-read denial, build
   byte-identical ACME releases; exercise CLJ/CLJS MCP and verify manifest-bound
   source metadata, licenses, notices, and CycloneDX inventory.

This is a proof refresh, not a request for another package mechanism. The
retained release, operator, writer, descriptor, SDK, and metadata owners are
already the correct seams.
