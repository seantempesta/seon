---
type: issue
status: open
severity: friction
tags: [issue, cljs, component]
---

# Shadow deps-mode declarations imply inactive build paths

## Problem

The supported CLJS lifecycle enters Shadow through `clj -M:cljs` and derives
its classpath from `deps.edn`, but repository configuration and docs still
declare a second npm Shadow CLI, inert `:source-paths`, standalone `client:*`
scripts, and fixed-port instructions. These do not currently break the managed
operator, but they make unsupported paths look authoritative and obscure which
files actually invalidate a build.

## Evidence

- `shadow-cljs.edn` enables deps mode with `:deps {:aliases [:cljs]}` while
  retaining `:source-paths ["src" "test"]`. Vendored Shadow source at
  `reference-code/shadow-cljs/src/main/shadow/cljs/npm/cli.cljs:411-427`
  explicitly reports that `:source-paths` is ignored in deps mode and must be
  configured in `deps.edn`.
- Exact `clojure -Spath -M:cljs` output contains `src`, `resources`, `test`,
  and `script`; the inert Shadow list omits two real roots.
- `package.json` and the lockfile install npm `shadow-cljs` 3.4.10 even though
  active build, test, watcher, and operator commands use the Clojure dependency
  `thheller/shadow-cljs` 3.4.10 through `:cljs`.
- `package.json` exposes `client:watch`, `client:run`, and destructive
  `client:clean` scripts outside the one `bin/seon` operator lifecycle.
- `docs/cljs-dev-loop.md` still describes a fixed Shadow port 7889 and the old
  `mcp__seon_cljs__eval` workflow, while current Shadow binds port zero and the
  unified MCP discovers `.shadow-cljs/nrepl.port`.
- `acme/deps.edn` says npm dependencies may be added there. Clojure dependencies
  belong there; npm dependencies require a downstream `package.json` and
  `node_modules`, surfaced through `SEON_EXTRA_NPM`.

The isolated `:lora-audit` build is owned separately by
`lora-audit-runner-drift.md`; this issue owns only the general build-authority
duplication.

## Owner

The CLJS dependency/build boundary: `deps.edn`, `shadow-cljs.edn`,
`package.json`, `package-lock.json`, the active CLJS runbooks, and downstream
setup comments. `script/seon/dev/*` remains the one lifecycle owner.

## Acceptance

- Remove the inert `:source-paths` declaration and prove a clean client,
  bootstrap, complete/focused test, and ACME-flavor build from the deps-mode
  classpath.
- Remove npm Shadow and unsupported lifecycle scripts if no supported consumer
  remains; regenerate the lockfile and prove CSS/runtime npm resolution.
- Update active runbooks for dynamic port discovery and the unified MCP tool
  names. Historical evidence may retain old ports when labeled historical.
- Correct downstream dependency guidance: Clojure dependencies in
  `acme/deps.edn`, npm dependencies in downstream npm metadata reached through
  `SEON_EXTRA_NPM`.
- The operator's artifact fingerprint continues to include `deps.edn`,
  `shadow-cljs.edn`, npm metadata, downstream dependency metadata, and selected
  source/preload/build flavor. No second watcher or cleanup lifecycle is added.
