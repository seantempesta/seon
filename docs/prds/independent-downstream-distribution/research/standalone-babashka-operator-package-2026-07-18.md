---
type: research
status: completed
tags: [research, architecture, flow, agent]
---

# Standalone Babashka operator package

## Conclusion

The extracted release keeps the existing `seon.dev.cli` and
`seon.dev.process` implementation. It ships one platform-specific Babashka
executable, one generated operator uberjar, and a package-relative `bin/seon`.
The launcher invokes `runtime/bb --jar runtime/operator.jar`, so operation does
not need Clojure CLI, Shadow, a producer checkout, Maven, or a writable home or
dependency cache.

This is a carrier for the one operator, not a package-only supervisor. The
same CLI selects the package's writer and pod process graph from the admitted
release manifest.

## Dependency ledger

- Babashka `1.12.218`, source tag `v1.12.218`, SHA
  `0fb349c414e717800be775ba9cb77c95a9eb700d`, mirrored at
  `reference-code/babashka`. Its uberjar implementation is in
  `src/babashka/main.clj:1135-1157`.
- `babashka.process` is built into the selected Babashka executable. The
  maintained API source mirror remains tag `v0.6.25`, SHA
  `16a84e0af0da51b8c84e289970f6b7cc35b35d18`, at
  `reference-code/babashka-process`.
- The operator uberjar embeds Malli `0.20.0`, Cheshire `5.13.0`, their
  transitive dependencies, the `script/seon/dev` CLI/process namespaces, and
  the non-test CLJ/CLJC namespaces they require from `src`. The MCP namespace
  and `nrepl/bencode` are not in the production operator closure.
- The first-party entrypoints remain `bin/seon`, `seon.dev.cli`,
  `seon.dev.config`, and `seon.dev.process`. `seon.dev.release` builds the
  carrier; it does not redefine lifecycle behavior.

## Exact platform assets

Each release is built for its host platform. Linux uses Babashka's portable
static assets; macOS uses the matching native architecture asset.

| Platform | Asset | SHA-256 |
|---|---|---|
| Linux arm64 | `babashka-1.12.218-linux-aarch64-static.tar.gz` | `e9e9190afb0dd33abbcd3aa6c1382184a88a5498800324719be3be6e1aa68302` |
| Linux amd64 | `babashka-1.12.218-linux-amd64-static.tar.gz` | `7bd028cc794732ffde3da31ce4379840893c8e54f1046f92a8dfc4f4b3cddaf8` |
| macOS arm64 | `babashka-1.12.218-macos-aarch64.tar.gz` | `5bc992f39692b707403fc322e860fc82017da7de4a84a32267abb4d50a0c5f9d` |
| macOS amd64 | `babashka-1.12.218-macos-amd64.tar.gz` | `2b7640a919b79406142b12c488ee83f7ba070c04b82bee8f74ad4eab074ddaeb` |

Windows is not claimed. The current Seon authority uses Unix-domain sockets,
POSIX signal/process behavior, and a POSIX launcher. A Windows package needs a
separate measured platform decision rather than an untested archive choice.

## Release inventory and licensing

The closed release manifest now binds these additional members:

- `runtime/bb`;
- `runtime/operator.jar`;
- `bin/seon`;
- `config/system.edn`; and
- `THIRD_PARTY_LICENSES/babashka-EPL-1.0.txt`.

The identity also binds the Babashka version, source revision, exact platform
asset, and archive SHA-256. The package retains Seon's AGPL license separately.
Babashka is distributed under EPL-1.0; its unmodified license text is copied
from the exact mirrored revision.

The default config is explicit content rather than an implicit runtime-asset
side effect. Fresh package initialization therefore selects a verified
`config/system.edn`; reopening a born database remains config-free under the
existing `seon.dev.config/select-manifest` rule.

## Executable proof

On macOS arm64, the verified `1.12.218` binary is 68 MiB and the staged
operator uberjar is 1.4 MiB. A complete admitted fixture was copied to a second
directory and executed as:

```text
env -i PATH=/usr/bin:/bin HOME=/nonexistent XDG_CACHE_HOME=/nonexistent \
  <relocated>/bin/seon help

```

It printed the canonical operator help without consulting or downloading a
dependency. The packaged binary reported `babashka v1.12.218`, and neither the
uberjar nor `release.edn` contained the producer checkout path. This proof
started no Seon lifecycle process.

Focused verification passes 75 tests and 368 assertions across
`seon.dev.release-test`, `seon.dev.config-test`, and `seon.dev.process-test`.
