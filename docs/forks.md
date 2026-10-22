# Forks: provenance

Every customized library is a submodule in `reference-code/` pinned to its fork's
`jolt/main` (branch scheme: `../notes/DECISIONS.md`, "Libraries on Jolt"). Submodule URLs
are local paths under `~/src/seon-jolt` until the owner approves pushing to the GitHub forks.
Fixes arrive as `jolt/<topic>` branches merged into `jolt/main` by root once measured; generic
fixes are cut again from upstream's main as `upstream/<topic>`. **Generic** = an upstream
candidate; **Jolt** = specific to running on Jolt (kept on `jolt/main`).

## jolt — `reference-code/jolt`, `jolt/main` = `a9aef8ea` (A1: + jolt/flow-update, jolt/seam-claims, jolt/printer-port, jolt/go-fiber-default)

- Repo: `~/src/seon-jolt/jolt` (the worktrees `jolt-final`, `jolt-f1`, `jolt-u1`, `jolt-e14` share it). Upstream: `jolt-lang/jolt`. Fork: `seantempesta/jolt`.
- Base: `f714b0ed` (upstream main, v0.8.23 + 20). `jolt/main` = branch `final` (`40028e79`) fast-forwarded to F1's `f1-flow` (`00d090a5`); no merge was needed.
- Ours:
  - `28970daf`, `f944a449`, `971d6d0b` — perf: deftype field access by slot, `set!` dispatch, host method sites on native colls. Generic (U1: `upstream/deftype-field-access`, `upstream/host-site-coll-reads`).
  - `3659e27a` — chez-patch 0006, the fork child re-protects MAP_JIT code (E0). Generic (`upstream/fork-reprotect-jit`).
  - `40028e79` — chez-patch 0007, static code leaves MAP_JIT after a static collect, so fork children share it (E14). Generic, macOS arm64 only (`upstream/static-code-cow`; also a Chez candidate).
  - `00d090a5` — Flow `stop` awaits every proc's exit; Flow's mults run on fibers (F1; owner-approved 2026-10-20). Jolt for now: propose upstream only once proven.
- `jolt/embed-stdlib` `16a1c18e` (lane J1, 2026-10-21; on `jolt/main` `086315a1`, for root to merge) — `jolt build`: an app that keeps the compiler carries jolt's install sources (jolt-core, stdlib, vendored libraries) as one gzip-per-file blob constant in its runtime unit, attached by `jolt-source-blob-attach-bytes!` (`host/chez/java/io.ss`), so it requires a jolt namespace it did not compile in from any cwd; before, its launcher's install roots were relative to the cwd. This is what Jolt's own design already assumed (`resolve-on-roots` probes embedded keys first; `bld-dep-resources` leaves a dependency's copy of a jolt namespace out because jolt supplies it; #1232's rule that a binary never depends on the build machine's paths), so absolute roots were not taken. Binary +0.91 MB, start unchanged, RSS +1.9 MB. Proof: build-smoke's `install-sources` case. Generic (upstream candidate). Open: an app compiles each such namespace from source in every process (install-owned and embedded files are not AOT-cached, `aot-cacheable-file`): 6 ms for clojure.core.rrb-vector, measured.
- `jolt/stable-cache-keys` `f8dced2c` (lane CACHEKEYS, 2026-10-21; on `16a1c18e`, for root to merge) — the AOT cache keys a project with data readers the same on every run (the BUILD plan's J1). `load-data-readers!` merges every root's `data_readers` file before loading any reader namespace (as Clojure reads them all at startup), so `aot-readers-digest`, folded into every key, is the full table's at lookup as at publish; and `jolt.main`'s `apply-project!` sets the source roots last, after the `:jolt/provides`, features, replacements and natives, so the scan's loads see them (`datahike.readers` failed on `java.time.ZoneOffset` and was compiled and thrown away once per tag). Unchanged `bin/build seon`: 0/265 → 217/217 hits, pass 1 16-20 s → 0.8 s, total ~40 → 22 s. Proof: aot-cache-smoke (ac3). Generic (upstream candidate).
- `jolt/infer-nil-union` `be2495af` (lane WPI, 2026-10-21; on `f8dced2c`, for root to merge) — inference: an element read off a typed vector (`nth`, `first`, `second`, `peek`, `last`, `fnext`) is joined with `nth`'s default or `:nil`, so `nil?` on a destructured missing slot no longer folds false (`seon.ref/line-citation` failed on `path:50` in every built root, with or without whole-program inference). +8 lines in `jolt.passes.types`, seed re-minted. Proof: build-smoke's elem-nil case. Generic (upstream candidate).
- `jolt/cache-records-relative` `2db1551c` (lane J2, 2026-10-21; on `f8dced2c`; merged onto `jolt/main` `1fad8b83` as `jolt/j2-on-main` `fc5d2f82`, the pin `jolt/j2` carries, for root to merge) — one AOT cache shared by checkouts of a project (the BUILD plan's J2): a `.res` sidecar names each read relative to the project root (user.dir; the root itself `.`, a jar entry under it inside its `jar:file:` URL, a read outside it absolute), so a checkout reading another's sidecar hashes its own files (before, B hashed A's and was served A's artifact after B's file changed); a listed directory (`jolt-list-dir`, `File.list`, `Files/walkFileTree`, `Files/newDirectoryStream`) is a recorded read keyed by its sorted names; `.deps`/`.res`/`.ct`/`.cti` are written by temp+rename; `JOLT_CACHE_GENERATIONS` (default 3) generations are kept. Known: one sidecar holds the last compile's names, so checkouts whose read SETS differ take turns missing (ccache keeps several manifests). Proof: aot-cache-smoke (ah)-(al), 46/46. Generic (upstream candidate).
- `jolt/load-form` `44c9574a` (lane RELOAD) — dropped 2026-10-22 (RELOAD goes, `../notes/DECISIONS.md`); the pin is back on `fc5d2f82`.
- Pending: F2, J2 (`jolt-j2`, `jolt/fiber-park`) and L1 (`jolt-l1`) fixes arrive as `jolt/<topic>` branches. Note `jolt-l1` is its own clone with a branch also named `jolt/main`, at `40028e79` (behind this one): L1's work should come over as topic branches onto `00d090a5`.

## chez — `reference-code/chez`, `jolt/main` = `359f151`

- Repo: `~/src/seon-jolt/e14/chez-share` (same commit as its branch `seon-jolt-0006-0007`). Upstream: `cisco/ChezScheme`.
- Base: `e95a7ef`, v10.4.1. Ours: `359f151` applies Jolt's chez-patches 0006 (`c/thread.c`) and 0007 (`c/segment.c`, `c/gcwrapper.c`, `c/externs.h`). Both generic (Chez candidates, 0007 macOS arm64 only).
- **Not applied: patches 0001–0005** (lock-free thread activation, oversize chunk spare segment, boot load in place, vfasl string sharing, same-device). This is the kernel the running runtime (`jolt-final`) was built with: its binary has `S_share_static_code` but neither `S_lockfree_activation` nor `S_vfasl_bytes`. `notes/HANDOFF.md` and `notes/decision-bench.md` say 0001–0007; that is wrong. `e14/chez-full-install` has 0001–0007 but no committed source. See `SCAFFOLD.md` for the choice.

## datahike — `reference-code/datahike`, `jolt/run-ahead` = `fe50764` on `jolt/main` `25a6b5d` (owned by L1; moving)

- A1 `jolt/run-ahead` (for root to merge onto `jolt/main`): `032552b` `datahike.writer/apply!`, `pending?`, `commit-pending!` (run-ahead writes at a :sync writer's serial caller); `fe50764` `merge-db!` on a :sync writer applies on the calling thread (its `go` block hung a forked agent, which has no scheduler threads). `jolt/main` `25a6b5d` = b8d109b + `jolt/tests-qcache-off` 0f1b619 + `jolt/sync-through` 5ed8d8f.

- A1 (2026-10-20): `a1/jolt-main` = `jolt/main` `fde9f89` (which already holds F2's f2-final `writer.cljc` byte for byte) + `jolt/node-cache-reads` `deee8c2` + `jolt/qcache-off` `21bc8cd` + `jolt/branch-pointer` `7de2e94` (on `jolt/branch-roster` `c0f9149`). `jolt/main` itself is checked out in L1's worktree `l1/qc/dh-main`, so root moves it. Versioning tests: 13 tests, 49 pass, 3 fail, 4 error; `jolt/main` is 1 fail, 4 error. The 2 new failures are `commit-as-db-preserves-only-attached-generation-identity`'s query-cache evidence assertions (miss-owner, hit), which `qcache-off` makes false by design.

- Repo: `~/src/seon-jolt/libs/datahike` (L1 works in its worktree `l1/wt/datahike`). Upstream: `replikativ/datahike`; base is Seon's fork `seantempesta/datahike` `ccba220b` (commit `ad9fc97` "baseline").
- L1 consolidated all four copies: `libs/datahike`, `e4/datahike-a`, `proto/core/vendor/datahike` and F1's `:sync` writer from `proto/flow/vendor/datahike`.
- Ours: `a6b9b86` `:jolt` reader conditionals and Jolt index/node-cache files (Jolt); `8efacfb`, `8132b95` the stand-in index, then dropped for the Jolt PSS translation (Jolt); `70ef3a3` EDN roots/nodes over StringSerializer (Jolt); `8bd49ad` `boolean-cmp` via `compare` (generic: works on the JVM too); `9c38f95` proto/core's changes: Fressian read handlers for a JVM-written store (Jolt), writer start poll timeout 0, schema attribute classes as sets; `fde9f89` the `:sync` writer with `transact-batch!` group commit (ours, design-specific).
- **F2 is still editing the writer (`transact-batch!`), so `jolt/main` will move;** the pin follows when root merges it.
- **Jolt-first from 2026-10-22** (owner, DECISIONS "Storage is tuned for our workload"): the `jvm` branch freezes `4b93a1b`, the pin next ran on that day, holding both; the main line drops `:clj`/`:cljs`/Fressian code from each file a change touches, each deletion its own commit.
- `jolt/values-out-of-line` (lane BLOBS, 2026-10-22, on `4b93a1b`): `4ae0593` large values out of line in `CachedStorage` (`:index-config :value-out-of-line-bytes`; GC's mark keeps them), `d904dea` `:db.type/data` and a total order on plain data ([storage](storage.md#values-out-of-line), [storage](storage.md#plain-values)).

## persistent-sorted-set — `reference-code/persistent-sorted-set`, `jolt/main` = `9cce727` (L1)

- Repo: `~/src/seon-jolt/e4/pss-a` (L1 worktree `l1/wt/pss-a`). Upstream: `replikativ/persistent-sorted-set` 0.4.137.
- Ours (no upstream history; written fresh): `d397c58` the JVM core as Jolt deftypes, `d565937` EDN node forms, `df6735e` `Arrays/sort`, `9cce727` proto/core's node emitter. Jolt.
- It is an overlay, not a whole library: `deps.edn` puts its `src` on `:paths`, ahead of the mvn 0.4.137 jar datahike depends on, which still supplies the other namespaces.

## konserve — `reference-code/konserve`, `jolt/main` = `39ce147` (A1: + jolt/sync-sweep 14a376c, 39ce147)

- Repo: `~/src/seon-jolt/libs/konserve` (`jolt/main` built in S1's clone and fetched back; `main` stays at `b19e78f`). Upstream: `replikativ/konserve`; base is Seon's fork `af35a97` (commit `4991e67` "baseline").
- Ours: `88753d6` Jolt reader conditionals in serializers (Jolt); `33f81d6` StringSerializer writes and reads UTF-8 EDN through the streams (generic: fixes a JVM ClassCastException); `4f50c49`, `4ae0b8c`, `e186f51` POSIX filestore and LMDB store over `jolt.ffi`, dispatched from `konserve.store` (Jolt); `10e47f6` lock token channel `(chan 1)`, a workaround for Jolt's `poll!` missing a pending put (the generic fix is in Jolt); `b19e78f` `konserve.jolt.bin`, a measured but unwired codec (candidate to delete); `f7cdc98` X1's FaslSerializer, byte 4 (Jolt).
- `f7cdc98` places X1's files: `x1/src/x1/codec.clj` → `konserve.jolt.fasl` (`src/konserve/jolt/fasl.jolt`), `jfasl.ss` → `src/konserve/jolt/jfasl.ss`, `store_codec.clj` → `konserve.jolt.fasl-store`. X1's patch was written against proto/core's `jvm-interop` overlay of `konserve.serializers`; only its fasl hunks apply to konserve itself. The overlay's E9 Fressian reader stays in proto/core until the Fressian bridge is placed.
- `jolt/atomic-append` `b110d99` (lane STORES, 2026-10-21; generic in shape, LMDB-only in effect): `PTransactBackingStore` and the LMDB store's one write transaction, so `konserve.core/append` (Datahike's branch roster) is atomic across processes; it lost 32 of 100 branches when two processes made 50 each (`seon.db-test/two-processes-branch-one-store`).
- `jolt/lz4-ffi` `37a28e4`, `7c462af` (lane BLOBS, 2026-10-22; Jolt): `konserve.jolt.lz4`, LZ4 frames through liblz4's frame API over `jolt.ffi` (the JVM Lz4Compressor's format: 64 KiB blocks, content size recorded), and the `:jolt` branches of `konserve.compressor`; it loads `JOLT_LZ4_LIB`, which Seon's `bin/env` points at the lz4 `bin/build lz4` makes from Chez's vendored source ([storage](storage.md#compression)).
- `jolt/lz4-ffi` also holds `3dbd4d5` (lane BLOBS, 2026-10-22; Jolt): `konserve.jolt.zstd` and `ZstdCompressor` (header byte 2, `:zstd`), libzstd's simple API over `jolt.ffi`, one frame per value at level 3; it loads `JOLT_ZSTD_LIB` ([storage](storage.md#compression)).
- `konserve.jolt.fasl-store` names persistent-sorted-set node types. Its cleaner home may be the PSS fork, with konserve's serializer taking the freeze/thaw from the store; that is a design call for L1.

## crypto — `reference-code/crypto`, `jolt/main` = `902196e`

- Repo: `~/src/seon-jolt/libs/crypto`. Upstream `jolt-lang/crypto` `e6981b8`. Ours: `902196e` arms claim host tables only ({:kind :table}) (L1, `jolt/seam-claims`). Generic.

## malli — `reference-code/malli`, `jolt/main` = `5a830e14`

- Repo: `~/src/seon-jolt/l1/wt/malli` (L1's clone of `seantempesta/malli`). Base `dd13ff81` (Seon's pin). Ours: `5a830e14` `-instrument` of a one-fixed-arity function schema gets a wrapper of that arity (L1, `jolt/instrument-arity`; call 0.24 -> 0.06 µs). Generic.

## jolt-fressian — `reference-code/jolt-fressian`, `jolt/main` = `8f3eb5d`

- Repo: `~/src/seon-jolt/e9/jolt-fressian`. Upstream: `jolt-lang/jolt-fressian`, base `7e7922a`.
- Ours: `8f3eb5d` E9's decode fixes (hash-map tag lookup, code-byte dispatch table, byte-array reader; 8.6× faster). Generic.
- Off the default path: `deps.edn` alias `:fressian`, for opening a JVM-written store.

## jolt-overlay — `reference-code/jolt-overlay`, `jolt/main` = `4ecb7a8`

- Repo: `~/src/seon-jolt/libs/jolt-overlay`. No upstream: `.jolt` copies of two libraries Datahike needs.
- Ours: `355f390` clojure.spec.alpha 0.5.238 verbatim; `3427652` hasch.platform 0.4.100 verbatim, plus `res` in spec.alpha: a resolved deftype is not an emittable literal; `4ecb7a8` hasch's digest names upper-cased (`SHA-512`, `MD5`).
- **Recommendation: don't fold it into `jolt` as is.** Each change is a workaround for a generic gap elsewhere: (1) jolt-lang/crypto's `MessageDigest/getInstance` is case-sensitive where the JVM's is not, so the fix belongs in crypto and hasch.platform's copy then goes; (2) `resolve` of a deftype yields a value Jolt cannot emit as a literal, a Jolt compiler gap. Once both are fixed, what remains is spec.alpha verbatim, which Jolt could ship in its stdlib or we could take as a pinned dependency.

## Dropped: superv.async

`libs/superv.async`'s only change (`4b07d0f`) prints a stack trace in the supervisor's error
fn, a debugging aid. The library is needed (konserve and datahike require it), the fork is not:
`deps.edn` pins `seantempesta/superv.async` `501b4294`, the same commit datahike declares, with
`core.async` excluded because Jolt ships its own.

## Unmodified, pinned in `deps.edn`

ring-chez-adapter
`fa0674e3`, multipart `ca51dd47`, time `a28ab100`, http-client `5a9706e0`
(R1's), fipp 0.6.29, core.cache 1.0.225.
