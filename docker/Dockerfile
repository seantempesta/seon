# The canonical Seon image — one container running the full cluster:
# database server (JVM Datahike writer) + pod (Bun CLJS runtime), UDS between
# them, durable database on /seon-data (volume), pod HTTP published.
#
# Design: docs/prds/agent-ctx/research/result-driven-benchmark-suite-design-2026-07-05.md
# §2a. This is the user-facing distribution artifact AND the bench substrate.
#
# The /opt/seon tree is fully SELF-CONTAINED (bundled JRE + Bun — invoked by
# absolute path, never PATH): mounting it into any glibc container yields a
# bootable cluster (the A-overlay bench composition, §2b).
#
# Multi-arch: builds BOTH linux/arm64 (native on Apple Silicon) and
# linux/amd64 (emulated under Rosetta). The three arch-hardcoded downloads
# (Bun, Temurin JRE, babashka) are parameterized on buildx's TARGETARCH;
# every other layer already adapts (multi-arch bases, frozen `bun install`,
# arch-independent CLJS/Clojure bytecode). Each download sha-verifies both
# per-arch assets against the official checksums.
#
# Build (buildx to per-arch local tags — the default docker image store can't
# hold a multi-platform manifest under --load, so build each arch separately):
#   docker buildx build --platform linux/arm64 -f docker/Dockerfile -t seon:multiarch-arm64 --load .
#   docker buildx build --platform linux/amd64 -f docker/Dockerfile -t seon:multiarch-amd64 --load .
# Run:
#   docker run -d --name seon -p 7999:7890 -v seon-data:/seon-data \
#     -e DEEPSEEK_API_KEY seon:multiarch-arm64

# ============================================================
# Builder — all compilation happens HERE; none at container boot.
# ============================================================
FROM eclipse-temurin:26-jdk-noble AS build

# buildx auto-populates TARGETARCH per --platform (arm64 | amd64). Each
# arch-specific download below selects its asset name + sha256 from it.
ARG TARGETARCH

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git unzip \
 && rm -rf /var/lib/apt/lists/*

# --- Bundled runtimes (they ship INSIDE /opt/seon — hard requirement of the
# --- A-overlay composition: bench task images carry neither Java nor Bun).
# Bun: asset arch arm64→aarch64, amd64→x64-baseline for modest hardware.
ARG BUN_VERSION=1.3.14
ARG BUN_SHA256_ARM64=a27ffb63a8310375836e0d6f668ae17fa8d8d18b88c37c821c65331973a19a3b
ARG BUN_SHA256_AMD64=a063908ae08b7852ca10939bbdc6ceed3ddabce8fb9402dce83d65d73b36e6c7
RUN case "$TARGETARCH" in \
      arm64) BARCH=aarch64;      BSHA="$BUN_SHA256_ARM64" ;; \
      amd64) BARCH=x64-baseline; BSHA="$BUN_SHA256_AMD64" ;; \
      *) echo "unsupported TARGETARCH=$TARGETARCH" >&2; exit 1 ;; \
    esac \
 && curl -fL "https://github.com/oven-sh/bun/releases/download/bun-v${BUN_VERSION}/bun-linux-${BARCH}.zip" -o /tmp/bun.zip \
 && echo "${BSHA}  /tmp/bun.zip" | sha256sum -c - \
 && unzip -q /tmp/bun.zip -d /tmp/bun \
 && mkdir -p /opt/seon/bun/bin \
 && mv "/tmp/bun/bun-linux-${BARCH}/bun" /opt/seon/bun/bin/bun \
 && chmod +x /opt/seon/bun/bin/bun \
 && test "$(/opt/seon/bun/bin/bun --version)" = "$BUN_VERSION" \
 && test "$(/opt/seon/bun/bin/bun --revision)" = "${BUN_VERSION}+0d9b296af" \
 && rm -rf /tmp/bun /tmp/bun.zip

# Temurin JRE: asset arch arm64→aarch64, amd64→x64.
ARG JRE_VERSION_TAG=jdk-26.0.1%2B8
ARG JRE_FILE_VER=26.0.1_8
ARG JRE_SHA256_ARM64=bf5f733066599065de5e720edda550b39d85876f5bf23a94fee2cb6a8379cb36
ARG JRE_SHA256_AMD64=2e90cf68d31b28553fb2c8202d5a4c3a5e99a60285e125dc28c94ba5fb2ac1ef
RUN case "$TARGETARCH" in \
      arm64) JARCH=aarch64; JSHA="$JRE_SHA256_ARM64" ;; \
      amd64) JARCH=x64;     JSHA="$JRE_SHA256_AMD64" ;; \
      *) echo "unsupported TARGETARCH=$TARGETARCH" >&2; exit 1 ;; \
    esac \
 && curl -fL "https://github.com/adoptium/temurin26-binaries/releases/download/${JRE_VERSION_TAG}/OpenJDK26U-jre_${JARCH}_linux_hotspot_${JRE_FILE_VER}.tar.gz" -o /tmp/jre.tar.gz \
 && echo "${JSHA}  /tmp/jre.tar.gz" | sha256sum -c - \
 && mkdir -p /opt/seon/jre \
 && tar -xzf /tmp/jre.tar.gz --strip-components=1 -C /opt/seon/jre \
 && rm /tmp/jre.tar.gz

# --- Build tooling: clojure CLI + babashka (bin/fix-bootstrap-macros).
RUN curl -fL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh -o /tmp/clj-install.sh \
 && bash /tmp/clj-install.sh && rm /tmp/clj-install.sh
# babashka: asset arch arm64→aarch64, amd64→amd64 (sha from the release's
# per-asset .sha256 sidecar).
ARG BB_VERSION=1.12.218
ARG BB_SHA256_ARM64=e9e9190afb0dd33abbcd3aa6c1382184a88a5498800324719be3be6e1aa68302
ARG BB_SHA256_AMD64=7bd028cc794732ffde3da31ce4379840893c8e54f1046f92a8dfc4f4b3cddaf8
RUN case "$TARGETARCH" in \
      arm64) BARCH=aarch64; BSHA="$BB_SHA256_ARM64" ;; \
      amd64) BARCH=amd64;   BSHA="$BB_SHA256_AMD64" ;; \
      *) echo "unsupported TARGETARCH=$TARGETARCH" >&2; exit 1 ;; \
    esac \
 && curl -fL "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-${BARCH}-static.tar.gz" -o /tmp/bb.tar.gz \
 && echo "${BSHA}  /tmp/bb.tar.gz" | sha256sum -c - \
 && tar -xzf /tmp/bb.tar.gz -C /usr/local/bin bb && rm /tmp/bb.tar.gz

ENV PATH=/opt/seon/bun/bin:$PATH
# Maven cache + gitlibs land INSIDE the runtime tree — the baked classpath
# (cp.txt below) points at them, so the runtime stage needs no clojure CLI.
ENV GITLIBS=/opt/seon/gitlibs
RUN mkdir -p /root/.clojure \
 && echo '{:mvn/local-repo "/opt/seon/m2"}' > /root/.clojure/deps.edn

WORKDIR /opt/seon

# Linux-native node_modules (NEVER copy a mac tree — @vscode/ripgrep installs
# a platform binary via postinstall).
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

# Dep prep + classpath warm (same steps as `bin/seon prep`): downloads m2 +
# gitlibs and compiles the datahike fork's Java sources (:deps/prep-lib).
COPY deps.edn shadow-cljs.edn ./
COPY dev-resources dev-resources
RUN clojure -X:deps prep :aliases '[:writer]' \
 && clojure -P -M:writer \
 && clojure -X:deps prep :aliases '[:cljs]' \
 && clojure -P -M:cljs

# Sources — the pod re-seeds the core from these at boot (read via
# SEON_RUNTIME_ROOT), so they ship in the runtime tree too.
COPY src src
COPY test test
COPY resources resources
COPY externs externs
COPY config config
COPY seon-skills seon-skills
COPY bin/fix-bootstrap-macros bin/fix-bootstrap-macros

# Compile the pod bundle + the self-host bootstrap bundle + CSS.
RUN clojure -M:cljs compile client
RUN clojure -M:cljs compile bootstrap && bb bin/fix-bootstrap-macros
RUN bun run --bun css:build

# Prune .shadow-cljs to the one dir the compiled loader actually loads from
# (out/client/main.js resolves __dirname/../../.shadow-cljs/builds/client/dev/out/cljs-runtime).
RUN mkdir -p /tmp/keep \
 && mv .shadow-cljs/builds/client/dev/out /tmp/keep/out \
 && rm -rf .shadow-cljs \
 && mkdir -p .shadow-cljs/builds/client/dev \
 && mv /tmp/keep/out .shadow-cljs/builds/client/dev/out

# Bake the resolved writer classpath (ABSOLUTE paths — the entrypoint runs
# `java -cp $(cat cp.txt) clojure.main -m seon.db.server` from /seon-data,
# so repo-relative entries must be rooted under /opt/seon).
RUN clojure -Spath -M:writer > /tmp/cp.raw \
 && tr ':' '\n' < /tmp/cp.raw \
    | sed 's|^\([^/]\)|/opt/seon/\1|' \
    | paste -sd: - > /opt/seon/cp.txt

COPY docker/seon-entrypoint /opt/seon/seon-entrypoint
RUN chmod +x /opt/seon/seon-entrypoint

# ============================================================
# Runtime — debian-slim + the self-contained /opt/seon tree. No JDK, no
# clojure CLI, no package manager, no compile at boot.
# ============================================================
FROM debian:bookworm-slim

# ca-certificates + curl: operator convenience (the exec-curl door in bench
# composition) and the explicit system trust store selected by the Bun pod.
# The JRE ships its own cacerts.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /opt/seon /opt/seon

# Artifacts (src indexing, out/bootstrap, resources, skills) resolve off the
# immutable install root; DATA stays under /seon-data (the volume).
ENV SEON_RUNTIME_ROOT=/opt/seon
VOLUME /seon-data
EXPOSE 7890

ENTRYPOINT ["/opt/seon/seon-entrypoint"]
