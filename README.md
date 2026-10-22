# next — Seon on Jolt

The clean home for the Jolt version of Seon: one runtime (Chez + Jolt, our forks),
Datahike on Jolt, every process a Flow proc. Local only; never pushed.

## Clone and build

```sh
git -c protocol.file.allow=always clone --recurse-submodules ~/src/seon-jolt/next
cd next
bin/build        # Chez (our fork) -> Jolt (our fork) -> target/hello; all output in target/
target/hello     # => hello: (datahike hello)
bin/test         # test/'s deftests on target/jolt/jolt
bin/check        # the end-to-end suite on target/seon (smoke ... scale 100)
```

`protocol.file.allow=always` is needed while the submodule URLs are local paths.

**Prerequisites** (not installed by the build): a C compiler and make (macOS: the Xcode
command line tools), git, network access to GitHub and Maven Central on the first build,
OpenSSL 3 at run time (Homebrew `openssl@3` on macOS; jolt-lang/crypto loads it), and
LMDB's source at `../vendor-c/lmdb` (branch `mdb.master3`): `bin/build lmdb` builds it with
robust locks into `target/lib/liblmdb.dylib`, which `bin/env` points `JOLT_LMDB_LIB` at.

**Working here as an agent:** start with `AGENTS.md` (also `CLAUDE.md`). next/ replaces
`~/src/seon`; `docs/parity.md` tracks each v1 capability.

## Layout

- `reference-code/` — our forks as submodules on their `jolt/main` branches (`docs/forks.md`)
- `deps.edn` — `:local/root` only into `reference-code/`; everything else pinned
- `bin/` — `build`, `test`, `check`, `env`
- `native/` — `probe.c` (Mach memory accounting), built into `target/lib`
- `src/`, `web/`, `resources/` — the platform, arriving by need (see each README)
- `test/` — deftests; `test/build/` holds the build's smoke binary
- `docs/` — `forks.md` (provenance), `vocabulary.md` (the words), `failure-points.md` (the named catch sites)
- `AGENTS.md` — how work is handed out and done; `resources/seon/tasks/` — the work itself, as task files
- `BAGGAGE.md` — what we left behind, and why
