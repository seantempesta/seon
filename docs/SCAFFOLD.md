# Scaffold (lane S1, 2026-10-20)

## What exists

- A git repo, local only, with no remotes.
- `reference-code/`: seven forks as submodules, each on `jolt/main` (provenance: `forks.md`).

  | submodule | commit | source repo |
  |---|---|---|
  | jolt | `00d090a5` | `~/src/seon-jolt/jolt` |
  | chez | `359f151` | `~/src/seon-jolt/e14/chez-share` |
  | datahike | `fde9f89` | `~/src/seon-jolt/libs/datahike` (L1) |
  | persistent-sorted-set | `9cce727` | `~/src/seon-jolt/e4/pss-a` (L1) |
  | konserve | `f7cdc98` | `~/src/seon-jolt/libs/konserve` |
  | jolt-fressian | `8f3eb5d` | `~/src/seon-jolt/e9/jolt-fressian` |
  | jolt-overlay | `4ecb7a8` | `~/src/seon-jolt/libs/jolt-overlay` |

- `deps.edn`: customized libraries are `:local/root` into `reference-code/`; malli, ring-chez-adapter, crypto, multipart, time, http-client and superv.async are `:git/sha`; fipp and core.cache are `:mvn/version`.
- `bin/build`: Chez → Jolt → `target/hello`, from the pins only.
  - Each source tree is copied into `target/src` first (an APFS clone), so the submodules stay clean.
  - Output goes to `target/`. `HOME` is set to `target/home` for the Jolt steps, so dependency downloads and Jolt's caches stay there too.
  - Each step is skipped while its stamp matches its pin. `BUILD_FORCE=1` rebuilds everything; `BUILD_JOBS` sets parallelism (default 4).
- `bin/test`: runs every `test/**/*_test` namespace through `test/test_runner.clj` on `target/jolt/jolt`. The one deftest, `next.smoke-test/fasl-round-trip`, round-trips a value through konserve's new fasl codec.
- Stubs:
  - `CLAUDE.md`: the step-fn rules and process model, copied from `DECISIONS.md` under a DRAFT heading.
  - `BAGGAGE.md`: points to `../proto/BAGGAGE.md`.
  - `src/`, `web/`, `resources/`: a README each.

## Manual steps that are now scripted

| Step in E14 / jolt-final | Now |
|---|---|
| Chez source with 0006+0007 applied by hand in `e14/chez-share` | Committed on chez `jolt/main`; `bin/build` copies and configures it (`--threads --disable-x11 --disable-curses CFLAGS+=-fPIC`) |
| `make install` into a hand-picked `chez-share-install` | `target/chez`, then a check that it is threaded |
| `make CHEZ=…` inside the shared jolt worktree | `build-jolt.ss release target/jolt/jolt` in a copy, with `JOLT_VERSION` taken from `tools/version.sh` in the pinned checkout |
| Library copies wired by relative paths (`../../libs/…`) | Submodules plus `deps.edn` |

**Documented prerequisites** (in the README): a C compiler and make (on macOS, the Xcode CLT); git; network access to GitHub and Maven Central on the first build; OpenSSL 3 at run time. jolt-lang/crypto loads `libcrypto.3`/`libssl.3`, so the binary needs Homebrew `openssl@3` on the host. The submodule URLs are local paths, so cloning needs `git -c protocol.file.allow=always`.

## Fresh-clone proof

Command: `git -c protocol.file.allow=always clone --recurse-submodules ~/src/seon-jolt/next <scratch>/next-fresh && bin/build && target/hello && bin/test`. It ran under `nice -n 15 lockf build.lock` with `-j4`, at machine load around 40.

| step | time |
|---|---|
| clone, recursive (Chez's and Jolt's GitHub submodules included) | 21 s |
| Chez configure + make -j4 + install | 98 s |
| Jolt `build-jolt.ss release` | 15 s |
| hello: deps fetched into `target/home`, Datahike compiled, binary linked | 58 s |
| **bin/build total** | **171 s** (user 211 s) |
| `target/hello` | prints `hello: (datahike hello)`, exit 0, 1.75 s wall |
| `bin/test` | 1 test, 1 assertion, 0 failures |
| `git status` after the build, recursive over submodules | clean |

**Jolt's own gates on `jolt/main`**, run in a copy built with the same `target/chez`:
- `values` 174/174.
- `flow`: FLOW-TEST OK.
- `fibers`: all PASS.
- `corpus` 7126/7136, with 0 crashes and 0 new divergences (10 known).
- `unit` 2194/2194.
- `smoke` 275/276. The one failure, `jolt.socket` connect-timeout (`IOException` instead of `SocketTimeoutException`), fails the same way with the `jolt-final` binary, so `00d090a5` did not cause it.
- The full `make test` gate was not run.

## Pending

- **L1:** Datahike `jolt/main` will move, because F2 is still editing the writer (`transact-batch!`); the pin follows when root merges it. `jolt-l1` has its own branch named `jolt/main` at `40028e79`, which is behind `00d090a5`. L1's Jolt fixes should arrive as `jolt/<topic>` branches.
- **F2, J2, L1:** Jolt fixes arrive as `jolt/<topic>` branches, merged into jolt `jolt/main` by root, then the submodule pin moves.
- **F2:** the "How to write a proc" section, for `CLAUDE.md`.
- **Root:** decide on Chez patches 0001–0005 (below). Assigning the application code (`proto/*` into `src/`, `web/`) waits on F2, M1 and G1.
- **Owner:** GitHub remotes for the repo and the forks; the URLs stay local until then.

## Findings

1. **The running runtime has Chez patches 0006 and 0007 only, not 0001–0007.** `jolt-final`'s binary contains `S_share_static_code` but not `S_lockfree_activation` (0001) or `S_vfasl_bytes` (0003). `notes/HANDOFF.md` and `notes/decision-bench.md` claim 0001–0007, and that is wrong. The pin reproduces the runtime as measured, but Jolt's own CI builds Chez with 0001–0007 (`ci/apply-chez-patches.sh`).
   - Option A: keep the pin.
   - Option B: add 0001–0005 to chez `jolt/main` (a `jolt/<topic>` commit), then measure.
2. **superv.async is no longer a fork.** Its only change was a debug stack-trace print, so `deps.edn` pins the commit Datahike already declares.
3. **jolt-overlay should not be folded into `jolt`** as is. Its two changes work around a case-sensitive `MessageDigest` in jolt-lang/crypto and a Jolt `resolve`-of-deftype gap (`forks.md`).
4. **The X1 patch targeted proto/core's `jvm-interop` overlay, not konserve.** Only its fasl hunks went onto konserve; the E9 Fressian reader stays in that overlay for now.
