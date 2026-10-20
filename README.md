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
```

`protocol.file.allow=always` is needed while the submodule URLs are local paths.

**Prerequisites** (not installed by the build): a C compiler and make (macOS: the Xcode
command line tools), git, network access to GitHub and Maven Central on the first build,
and OpenSSL 3 at run time (Homebrew `openssl@3` on macOS; jolt-lang/crypto loads it).

## Layout

- `reference-code/` — our forks as submodules on their `jolt/main` branches (`docs/forks.md`)
- `deps.edn` — `:local/root` only into `reference-code/`; everything else pinned
- `bin/` — `build`, `test`
- `src/`, `web/`, `resources/` — the platform, arriving by need (see each README)
- `test/` — deftests; `test/build/` holds the build's smoke binary
- `docs/` — `SCAFFOLD.md` (state of this repo), `forks.md` (provenance)
- `CLAUDE.md` — the laws (draft), `BAGGAGE.md` — what we left behind
