---
type: research
status: completed
tags: [research, flow, web, agent]
---

# Bun-only production package audit

## Question and conclusion

What exact production-package cut removes Node from the canonical container,
and what still prevents the resulting package from being a genuine no-source
release?

The JavaScript runtime cut is direct and low risk: install one verified Bun
binary per Linux architecture, use Bun for package installation and build
scripts, launch the compiled pod with that binary, and use `Bun.connect` for
the existing database-writer readiness test. The current image cannot yet be
called no-source. Its boot contract deliberately packages `src/` and `test/`
and the pod seeds the database program from those paths through
`SEON_RUNTIME_ROOT`. The development artifact root likewise links `src`,
`test`, `guest-cljs`, and `resources` into the producer checkout.

The no-source exit therefore depends on one bounded program-source/bootstrap
artifact that contains the source strings and metadata required to initialize
or update the database without access to the producer checkout. Removing the
directories before that contract exists would make a smaller image that cannot
reliably initialize a new database.

No Docker build or lifecycle command was run for this audit.

## Dependency ledger

The audit read the following maintained inputs:

- Seon source at `17e5bb3e9a0b678a7cc8d21c3c231ce0f52d20c7`.
- Bun source at
  `be77b652884b16a103cfaa4af3c1102f72f2dcd3`, with the locally proven Bun
  executable reporting version `1.3.14` and revision `1.3.14+0d9b296af`.
- Shadow CLJS at
  `4e72595f57618f5c43388ad13d5136cd3bede566`; its CommonJS
  `:node-script` output is already proven under Bun. The Shadow target name
  describes the output format and does not require the Node executable.
- Datahike at `9ada755087228e10cfb179fa5779ce227a6ed220`, Konserve at
  `b5c99bc02a7175652a610324215288b78551801f`, and Proximum at
  `9846d3e79e1aee48474bc876d3d563d7137209c6`, as selected by the active
  downstream-distribution ledger.
- Temurin JRE `26.0.1+8` and babashka `1.12.218`, as pinned in the current
  Dockerfile. Babashka remains builder-only.

The decisive Bun sources are `docs/installation.mdx`,
`dockerhub/debian-slim/Dockerfile`, `.buildkite/scripts/upload-release.sh`, and
the runtime argument and TLS sources. Seon's decisive sources are
`docker/Dockerfile`, `docker/seon-entrypoint`, `script/seon/dev/artifact.clj`,
`script/seon/dev/process.clj`, and the independent downstream distribution
research.

## Exact Bun release identity

Pin the proven stable release, never `latest`:

| Docker platform | Bun release asset | SHA-256 |
|---|---|---|
| `linux/arm64` | `bun-linux-aarch64.zip` | `a27ffb63a8310375836e0d6f668ae17fa8d8d18b88c37c821c65331973a19a3b` |
| `linux/amd64` | `bun-linux-x64-baseline.zip` | `a063908ae08b7852ca10939bbdc6ceed3ddabce8fb9402dce83d65d73b36e6c7` |

The release URL is
`https://github.com/oven-sh/bun/releases/download/bun-v1.3.14/<asset>`.
The baseline x64 artifact is the correct default for modest and heterogeneous
hardware. A non-baseline x64 image is a later measured alternative, not the
portable production default.

Verify the release in two layers:

1. Download `SHASUMS256.txt.asc` for `bun-v1.3.14` and verify it with Bun's
   release-key fingerprint
   `F3DCC08A8572C0749B3E18888EAB4D40A7B22B59`.
2. Extract the line for the exact selected asset and run `sha256sum -c` while
   also retaining the literal per-architecture digest above as a fail-closed
   Docker build argument.

After extraction, install only the executable at `/opt/seon/bun/bin/bun` and
assert both outputs during the build:

```text
/opt/seon/bun/bin/bun --version   => 1.3.14
/opt/seon/bun/bin/bun --revision  => 1.3.14+0d9b296af
```

Do not use the official Bun Debian runtime image unchanged. Its Dockerfile
intentionally creates a `node` symlink to Bun for compatibility, which would
make Seon's no-Node inventory false even though the target is Bun.

## Docker build and runtime cut

The builder makes these in-place replacements:

1. Delete the Node version, archive, per-architecture hashes, download, and
   `/opt/seon/node` tree.
2. Install the verified Bun executable and put `/opt/seon/bun/bin` on the
   builder `PATH`.
3. Check in `bun.lock`. Run `bun install --frozen-lockfile` before compilation
   because Shadow and Tailwind are build dependencies.
4. Keep the existing Clojure dependency preparation and Shadow compilation.
   Run CSS through `bun run css:build`.
5. Materialize the exact production package closure with
   `bun install --frozen-lockfile --production` before copying the runtime
   tree. Preserve required install scripts, especially the platform-specific
   `@vscode/ripgrep` installation, and prove it on both architectures.
6. Execute the pod with the absolute Bun path and the compiled client artifact.

The `node_modules` directory name is the package ecosystem layout, not a Node
runtime requirement. It stays until bundling measurements prove a smaller
complete production closure. `package-lock.json`, npm identity, and Node
runtime metadata cease to be release authorities after `bun.lock` becomes the
frozen package ledger.

The runtime stage contains no JDK, Clojure CLI, babashka, Git, Shadow compiler,
Tailwind CLI, or dependency resolver. The existing standalone writer jar should
replace the older `cp.txt` plus Maven/Git library runtime tree; those files
cannot be deleted while `docker/seon-entrypoint` still launches
`clojure.main -m seon.db.server` from that classpath.

## Launcher and readiness

The foreground Bash entrypoint remains the production owner for the writer and
pod during this cut. It already forwards signals, notices either child exit,
stops the peer, and exits non-zero on unexpected termination. Replacing that
supervision at the same time is unnecessary scope.

The concrete launcher changes are:

```bash
BUN="$SEON_HOME/bun/bin/bun"
"$BUN" --use-system-ca "$SEON_HOME/out/client/main.js"
```

The database readiness definition does not change: the writer is ready when
its request Unix socket accepts a real connection. Replace the current
`node:net` inline probe with a bounded Bun probe using `Bun.connect`:

- connect to the configured Unix socket;
- on `open`, close the socket and exit zero;
- on `error`, exit non-zero; and
- enforce the existing one-second attempt deadline.

The probe may initially be an inline `bun -e` form. The release should
eventually carry it as one immutable, digest-bound package member so shell
quoting is not part of the readiness contract. It must not introduce a second
readiness definition. Pod readiness remains the HTTP `/_seon/ready` route. A
container health check may use the intentionally retained `curl` against that
route after both processes are running.

## Certificate behavior

Retain Debian `ca-certificates`. It supplies the explicit system trust store
for container tools and private or operator-installed roots. Bun also carries
a bundled root set, but the production launcher should pass
`--use-system-ca` so outbound provider requests follow the container's managed
trust policy. Bun also implements `NODE_USE_SYSTEM_CA=1` and
`NODE_EXTRA_CA_CERTS`; those inherited environment names do not imply a Node
executable. Prefer the explicit launch flag for the ordinary packaged policy.

Acceptance includes a hermetic HTTPS endpoint signed by a test system root and
one signed by an injected private root. Both must succeed only with the intended
trust configuration. Certificate verification is never disabled. The JRE has
its own `cacerts`; any future writer outbound private-root requirement is a
separate Java trust-store configuration, not a Bun setting.

## Release manifest, SBOM, and inventory

The release compatibility manifest is distinct from development artifact
manifest version 4. It binds this complete compatibility set:

- Seon release and source revision;
- database protocol, public SDK, and config compatibility versions;
- Bun version, full reported revision, platform asset, and SHA-256;
- JRE version and runtime digest;
- writer, client, bootstrap, program-source corpus, static asset, and config
  member digests;
- `bun.lock` digest and the exact production package inventory;
- maintained dependency revisions;
- every relative package member and digest; and
- license, notices, SBOM, and corresponding-source locations.

Every member path is normalized, relative, contained by the package root,
non-symlink, present, and digest-matched. The SBOM contains the JRE, Bun binary,
writer dependencies, JavaScript production packages, Debian runtime packages,
and their licenses. It must not report npm, a Node runtime, or a `node` fallback
executable.

Delete from the production inventory:

- Node version and checksum fields, archive, executable, directory, executable
  checks, npm version, package-lock identity, and any `node` fallback symlink;
- builder-only compilers, package managers, caches, and development dependencies;
- tests and the producer checkout;
- `.shadow-cljs` development cache after a relocatable production client is
  emitted; and
- `cp.txt`, Maven cache, and Git library cache after the standalone writer jar
  is the packaged launch artifact.

Retain only the JRE, Bun, standalone writer, relocatable compiled client and
bootstrap, production JavaScript packages, static assets, base config, bounded
program-source corpus, entrypoint, CA bundle, licenses/notices/SBOM, and `curl`
if it remains an explicit health and operator tool.

## Architecture and acceptance matrix

| Boundary | Build-time owner | Runtime member | Required proof |
|---|---|---|---|
| `linux/arm64` JavaScript | pinned Bun aarch64 asset | Bun executable | version, revision, digest, pod and child execution |
| `linux/amd64` JavaScript | pinned Bun x64-baseline asset | Bun executable | version, revision, digest, pod and child execution |
| database authority | JDK and Clojure build | JRE plus standalone writer | UDS acceptance, transact, reopen, signal handling |
| pod | Shadow compile and frozen Bun install | relocatable client plus production packages | HTTP readiness, database reads/writes, agents, feeds |
| initialization/update | bounded program-source build | digest-bound program-source corpus | empty database seed and newer-program delta without checkout |
| trust | Debian CA installation | system CA bundle | public and injected-private-root HTTPS |
| release admission | offline package validator | manifest, hashes, SBOM, notices | contained members and exact compatibility set |

Offline package tests reject absolute paths, `..`, symlink escapes,
missing/changed members, undeclared production packages, a wrong Bun revision,
asset or checksum, any Node executable or fallback symlink, Node/npm release
fields, and source/test/development cache members.

The integrated matrix then builds both Linux platforms and proves:

- the writer request socket becomes accepting before the pod starts;
- `/_seon/ready`, root, data, agent pages, Datastar feeds, shell, search,
  ripgrep, and Bun agent children work;
- unexpected writer or pod exit terminates the container non-zero, while
  requested shutdown is clean;
- the database reopens from its volume without reapplying converged initial
  data;
- public and private-root HTTPS work under the explicit trust policy; and
- image inventory and SBOM match the admitted release manifest.

Final no-source acceptance runs the release on both platforms in a clean
Debian environment where `command -v node` fails. It also verifies there is no
Node executable or fallback symlink, Bun reports the exact revision, the Seon
checkout is inaccessible and unmounted, no compiler or watcher starts, an
empty database initializes from the bounded program-source corpus, a newer
program applies only its delta, agents and browser flows work, and committed
database data survives restart.

## Dependency order and risk

1. Define and prove the bounded program-source/bootstrap member. This is the
   proven no-source blocker.
2. Produce a relocatable Shadow production client and exact production package
   closure.
3. Launch the existing standalone writer jar so classpath caches leave the
   runtime image.
4. Freeze `bun.lock` and prove native package installation on both Linux
   architectures.
5. Apply the executable, entrypoint, readiness, and certificate cut.
6. Admit the result through the release manifest, SBOM, no-Node inspection,
   browser journey, restart, and soak gates.

The Bun executable replacement and UDS readiness probe are not the hard part.
Correct initialization without packaged checkout source is the first contract
that must settle before production packaging can honestly graduate.
