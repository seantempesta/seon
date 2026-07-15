---
type: research
status: completed
tags: [research, component, cljs, flow]
---

# No-source package inventory executable slice

## Question and decision

What is the earliest distribution work that can advance without operating the
separately owned ACME cluster or assuming unsettled runtime/database contracts?

The writer uberjar is already a standalone artifact. The pod-side artifact is
not: manifest version 3 is a development readiness record whose paths are
absolute, and its content-addressed runtime root deliberately symlinks `src`,
`test`, `guest-cljs`, and `resources` back into the producer checkout. A
release-package inventory must reject the current runtime root even though its
bootstrap digest is immutable.

Do not extend the development manifest into a guessed release contract. First
restore exact dependency-source grounding and settle the database protocol plus
config/SDK ABI version declarations. The next safe executable implementation is
then one pure, offline compatibility-manifest and package-inventory test. It
constructs a temporary package tree and starts no process.

## Dependency ledger at the audited head

The audit was run at Git revision `124e5a23a90f2ecc41ecfd81e1ba61ac509cc7c9`.

| Input | Selected identity | Exact `reference-code/` source |
|---|---|---|
| Clojure | Maven `1.12.0` | absent |
| ClojureScript | Maven `1.12.145` | absent; the mirror is `1.12.41` at `946d75f3483c0c8e784e6668bff2c71a25619a77` |
| Shadow CLJS | `4e72595f57618f5c43388ad13d5136cd3bede566` | exact in `reference-code/shadow-cljs` |
| tools.build | Maven `0.10.5` | absent |
| Datahike | `417649383c65e13f15ea41d394fb1ed742477965` | exact in `reference-code/datahike` |
| Konserve | `df6818d43ea3363a808cd051c0d68917f1b987a9` | exact in `reference-code/konserve` |
| superv.async | `3e6ed755f83634c9e9bbb58707f9446420d32ce9` | absent |
| partial-cps | `1e119b03ea908ad925b98f9ba0a26371c65441e3` | absent |
| Node/npm | Node `26.4.0`, npm `11.17.0` | external toolchain; lockfile is the package ledger |
| JDK/Clojure CLI | JDK `26.0.1`, CLI `1.12.5.1654` | external toolchains |

The prior reconciliation correctly found several missing mirrors, but treated
the ClojureScript mirror as selected-source evidence. Its `pom.xml` proves it
is not the selected `1.12.145` source. That mismatch blocks a grounded release
schema under the repository source policy.

Audited producer-file digests:

- `deps.edn`: `99ded1efeb8e8a95413b9b66447aaa96bba088457aeaf98563d30e2fbab5949f`
- `shadow-cljs.edn`: `ba5422e9a83adb428f62d580cb4ec9547f3b9e8dcd4cb9cd501c29f372488b2a`
- `package.json`: `873cc40284ab05847db5aad8d22084f5fd5f6fa3b0470704d57da1f60c916267`
- `package-lock.json`: `2bbbbcf8a612fcad5f63b32ad2607521179cf9a78831beb183f18d174c1d0580`
- `LICENSE`: `0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0`
- `build.clj`: `f52071b7e8a5f1934c1046200cd64769f708962d4f443948d01ac3044a6bd94af`

These are audit evidence, not release identities. A release command must derive
them from its frozen input and bind them atomically into the manifest.

## Already proven

- `build.clj` produces the standalone writer uberjar from the maintained
  `:writer` basis.
- Default and ACME select the same maintained Datahike, Konserve, superv.async,
  and partial-cps revisions in this checkout.
- Development manifest version 3 binds immutable bootstrap bytes to a
  content-addressed runtime root. The artifact test proves a later flavor
  publication cannot mutate those bytes.
- Shadow's Node development output loads cache-owned runtime files; it is not a
  relocatable production bundle. The exact Shadow fork's
  `shadow.build.node/flush-unoptimized` implementation grounds this behavior.
- The npm inventory is lock-resolved, but no release emits it, notices, or an
  SBOM. `package.json` declares ISC while the repository license is AGPL-3.0.

## Still unproven

- No closed release schema or single release-version authority exists.
- Database protocol and config/public-SDK ABI versions are not bound to the
  writer, runtime, and consumer inputs as one compatibility set.
- There is no declared public CLJS source/macro inventory, relocatable
  devtools-free runtime, packaged operator, or package inventory validator.
- No clean downstream repository has built or operated while the Seon checkout
  is inaccessible.

## Smallest next executable slice

After exact sources are mirrored and the protocol/config/SDK version owners
settle, add one release-package namespace and one focused offline test:

1. Input is one namespaced map containing release and compatibility versions,
   required members, producer inputs, dependency revisions, npm lock identity,
   and license/SBOM locations.
2. Every member path is relative, normalized, contained by the package root,
   non-symlink, present, and digest-matched.
3. SDK source inventory is explicit; production npm dependencies equal the
   lock-derived inventory; maintained forks equal producer coordinates.
4. Validation returns a namespaced success/error value and performs no process,
   database, network, or global-state mutation.
5. Fixtures prove a valid package and reject absolute paths, `..`, symlink
   escape, missing or changed members, undeclared npm production dependencies,
   license mismatch, and mixed writer/runtime/SDK versions.

Acceptance command:

```bash
bb --config bb.edn --deps-root "$PWD" -m seon.dev.test-runner seon.dev.distribution-test
```

Existing development artifact regression gate:

```bash
bb --config bb.edn --deps-root "$PWD" -m seon.dev.test-runner seon.dev.artifact-test
```

This establishes executable package semantics without touching the process
graph. The later release command consumes it; it must not duplicate the
validator or promote the development manifest into release authority.
