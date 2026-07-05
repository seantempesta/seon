# The canonical Seon image — one container running the full cluster:
# wire-server (JVM datahike writer) + pod (Node CLJS runtime), UDS between
# them, durable store on /seon-data (volume), pod HTTP published.
#
# Design: docs/prds/agent-ctx/research/result-driven-benchmark-suite-design-2026-07-05.md
# §2a. This is the user-facing distribution artifact AND the bench substrate.
#
# The /opt/seon tree is fully SELF-CONTAINED (bundled JRE + Node — invoked by
# absolute path, never PATH): mounting it into any glibc container yields a
# bootable cluster (the A-overlay bench composition, §2b).
#
# Build (arm64-only for slice 1; multi-arch is a later unit):
#   docker build -f docker/Dockerfile -t seon:dev .
# Run:
#   docker run -d --name seon -p 7999:7890 -v seon-data:/seon-data \
#     -e DEEPSEEK_API_KEY seon:dev

# ============================================================
# Builder — all compilation happens HERE; none at container boot.
# ============================================================
FROM eclipse-temurin:25-jdk-noble AS build

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates git xz-utils \
 && rm -rf /var/lib/apt/lists/*

# --- Bundled runtimes (they ship INSIDE /opt/seon — hard requirement of the
# --- A-overlay composition: bench task images carry neither java nor node).
ARG NODE_VERSION=22.23.1
ARG NODE_SHA256=0294e8b915ab75f92c7513d2fcb830ae06e10684e6c603e99a87dbf8835389c1
RUN curl -fL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-arm64.tar.xz" -o /tmp/node.tar.xz \
 && echo "${NODE_SHA256}  /tmp/node.tar.xz" | sha256sum -c - \
 && mkdir -p /opt/seon/node \
 && tar -xJf /tmp/node.tar.xz --strip-components=1 -C /opt/seon/node \
 && rm /tmp/node.tar.xz

ARG JRE_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jre_aarch64_linux_hotspot_25.0.3_9.tar.gz"
ARG JRE_SHA256=d12d5b19ff7f6c4a99fd4f9eecede2c96e64df7d1f41cc84f2e9c9b38408600b
RUN curl -fL "${JRE_URL}" -o /tmp/jre.tar.gz \
 && echo "${JRE_SHA256}  /tmp/jre.tar.gz" | sha256sum -c - \
 && mkdir -p /opt/seon/jre \
 && tar -xzf /tmp/jre.tar.gz --strip-components=1 -C /opt/seon/jre \
 && rm /tmp/jre.tar.gz

# --- Build tooling: clojure CLI + babashka (bin/fix-bootstrap-macros).
RUN curl -fL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh -o /tmp/clj-install.sh \
 && bash /tmp/clj-install.sh && rm /tmp/clj-install.sh
ARG BB_VERSION=1.12.218
RUN curl -fL "https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-aarch64-static.tar.gz" \
      -o /tmp/bb.tar.gz \
 && tar -xzf /tmp/bb.tar.gz -C /usr/local/bin bb && rm /tmp/bb.tar.gz

ENV PATH=/opt/seon/node/bin:$PATH
# Maven cache + gitlibs land INSIDE the runtime tree — the baked classpath
# (cp.txt below) points at them, so the runtime stage needs no clojure CLI.
ENV GITLIBS=/opt/seon/gitlibs
RUN mkdir -p /root/.clojure \
 && echo '{:mvn/local-repo "/opt/seon/m2"}' > /root/.clojure/deps.edn

WORKDIR /opt/seon

# Linux-native node_modules (NEVER copy a mac tree — @vscode/ripgrep installs
# a platform binary via postinstall).
COPY package.json package-lock.json ./
RUN npm ci

# Dep prep + classpath warm (same steps as `bin/seon prep`): downloads m2 +
# gitlibs and compiles the datahike fork's Java sources (:deps/prep-lib).
COPY deps.edn shadow-cljs.edn ./
COPY dev-resources dev-resources
COPY reference-code/datahike/src-secondary reference-code/datahike/src-secondary
RUN clojure -X:deps prep :aliases '[:simd :fork-deps :writer]' \
 && clojure -P -M:simd:fork-deps:writer \
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
RUN npm run css:build

# Prune .shadow-cljs to the one dir the compiled loader actually loads from
# (out/client/main.js resolves __dirname/../../.shadow-cljs/builds/client/dev/out/cljs-runtime).
RUN mkdir -p /tmp/keep \
 && mv .shadow-cljs/builds/client/dev/out /tmp/keep/out \
 && rm -rf .shadow-cljs \
 && mkdir -p .shadow-cljs/builds/client/dev \
 && mv /tmp/keep/out .shadow-cljs/builds/client/dev/out

# Bake the resolved writer classpath (ABSOLUTE paths — the entrypoint runs
# `java -cp $(cat cp.txt) clojure.main -m seon.server.boot` from /seon-data,
# so repo-relative entries must be rooted under /opt/seon).
RUN clojure -Spath -M:simd:fork-deps:writer > /tmp/cp.raw \
 && tr ':' '\n' < /tmp/cp.raw \
    | sed 's|^\([^/]\)|/opt/seon/\1|' \
    | paste -sd: - > /opt/seon/cp.txt

COPY docker/seon-entrypoint /opt/seon/seon-entrypoint
RUN chmod +x /opt/seon/seon-entrypoint

# ============================================================
# Runtime — debian-slim + the self-contained /opt/seon tree. No JDK, no
# clojure CLI, no npm, no compile at boot.
# ============================================================
FROM debian:bookworm-slim

# ca-certificates + curl: operator convenience (the exec-curl door in bench
# composition) — the pod's own TLS (DeepSeek egress) uses Node's bundled CA
# store, and the JRE ships its own cacerts.
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
