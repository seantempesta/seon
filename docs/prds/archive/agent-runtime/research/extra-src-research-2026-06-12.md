---
type: research
status: active
tags: [research, agent]
---

# SEON_EXTRA_SRC — third-party CLJS source roots in the pod bundle

Research for task #36 (design phase), 2026-06-12. How a downstream
consumer adds their own AOT-compiled CLJS namespaces (and npm deps) to
the pod bundle WITHOUT forking seon. Every claim below is grounded in
vendored shadow-cljs source (`reference-code/shadow-cljs`, HEAD
8236315a, 2026-05-18), our HEAD-committed build files, or a live
`clj -Spath` probe run during this unit.

## TL;DR — recommended end-to-end shape

ONE env knob: `SEON_EXTRA_SRC` = absolute path to a downstream
directory that is a tiny deps.edn project (`{:paths ["src"]}` plus any
mvn/git deps they need). Sibling of `SEON_RUNTIME_ROOT` /
`SEON_CLUSTER_DIR` in spirit: env-parametrized, defaults to "absent =
today's behavior byte-identical".

1. **Classpath** — when `SEON_EXTRA_SRC` is set, `bin/seon` (the
   `cljs-watch` process command) and `bin/test-cljs` inject it into
   every `clj -M:cljs …` invocation via
   `-Sdeps '{:deps {seon.extra/src {:local/root "<SEON_EXTRA_SRC>"}}}'`.
   Live-proven: this puts `<root>/src` on the `:cljs` classpath
   (probe below). Works identically for `watch`, `compile`, `release`,
   and the `:test` build — they are all the same CLI.
2. **Into the build** — the downstream ships ONE well-known entry ns
   (convention: named by a second env var `SEON_EXTRA_PRELOAD`, e.g.
   `acme.pod`) that `:require`s their whole surface. bin/seon appends
   `--config-merge '{:devtools {:preloads [acme.pod]}}'` to the same
   invocations. shadow's `deep-merge` CONCATS vectors
   (`shadow/build/api.clj:37-40`), so `seon.dev.test-preload` is kept,
   not replaced.
3. **Indexing** — the entry ns calls `(seon.indexing/specced-fn-vars)`
   itself (the macro restricts to the CALLING ns's require closure)
   and registers the result into a new
   `seon.client/!extra-substrate-vars` atom — the exact
   `test_preload.cljs:68` / `!indexed-test-vars` precedent. Three
   small seon-side edits make their nses index/replay-skip/render like
   ours (see §d).
4. **npm** — compile-time: top-level
   `:js-package-dirs ["node_modules" #shadow/env ["SEON_EXTRA_NPM" "node_modules"]]`
   in shadow-cljs.edn (the `#shadow/env` reader tag is real, with
   defaults — verified in source). Runtime: `bin/seon` exports
   `NODE_PATH=$SEON_EXTRA_NPM` when set, because `:node-script`'s
   default `:js-provider :require` leaves `require("pkg")` for Node to
   resolve at runtime from the bundle's own location.
5. **Artifacts** — the downstream runs OUR `bin/seon cljs-watch` (cwd =
   seon checkout, env set); the combined bundle lands in seon's `out/`
   — exactly where their `SEON_RUNTIME_ROOT` already points. NO third
   artifact leg needed for the first rung.

Effort: bin/seon + test-cljs injection **S**; indexer extension trio
**S** each (atom concat, `first-party-file?` extra root,
`read-src-file` extra root); npm knobs **S**; docs/quickstart **S**;
full-source-roots store-config + per-product `out/` redirect **M**,
both deferrable. **Ship first:** the `-Sdeps` + `--config-merge`
injection in bin/seon plus the indexer trio — that alone gives
"their nses compile into the pod and the boot indexer treats them
like ours".

Downstream quickstart (what the doc/runbook would say):

```bash
# their world dir: acme/{deps.edn,src/acme/*.cljs,node_modules,package.json}
export SEON_RUNTIME_ROOT=/path/to/seon     # already required (ask #2)
export SEON_EXTRA_SRC=/path/to/acme        # NEW — their deps.edn project
export SEON_EXTRA_PRELOAD=acme.pod         # NEW — their entry ns
export SEON_EXTRA_NPM=/path/to/acme/node_modules   # only if they have npm deps
cd /path/to/seon && bin/seon restart cljs-watch && bin/seon restart pod
```

## Ground truth (what the source actually says)

### Our build runs shadow in DEPS MODE — `:source-paths` is dead weight

`shadow-cljs.edn` (HEAD) opens with `{:deps {:aliases [:cljs]}}` and
its own comment block records the corrected 2026-06-09 finding: in
deps mode the classpath comes ENTIRELY from deps.edn; the
`:source-paths ["src" "test"]` vector is ignored (kept for
documentation only). `bin/seon` runs `clj -M:cljs watch client` —
shadow's JVM IS the tools.deps JVM, classpath fixed at launch. So the
extension point is the tools.deps classpath, not shadow config.

### `#shadow/env` is real — but only helps where shadow READS the value

`shadow/cljs/devtools/config.clj:103-108` installs reader tags
`shadow/env` and `env` → `shadow.cljs.config-env/read-env`, which
supports `"VAR"`, `["VAR" "default"]`, and
`["VAR" :as :int|:bool|:symbol|:keyword :default x]`
(`config_env.cljc:17-73`). Useful for `:js-package-dirs` (§c). Useless
for source roots in deps mode (above). Also noted in source: a
`SHADOW_CLJS` env var carrying a full EDN config deep-merges over
everything (`config.clj:114-118`) — a bigger hammer we don't need.

### Live probe — `-Sdeps` injection works (run 2026-06-12)

```bash
clj -Sdeps '{:deps {acme/base {:local/root "tmp/extra-src-probe"}}}' \
    -A:cljs -Spath | tr ':' '\n' | grep probe
# => /Users/sean/src/seon/tmp/extra-src-probe/src
```

where `tmp/extra-src-probe/deps.edn` is `{:paths ["src"]}`. An
absolute external `:extra-paths` also resolved cleanly on the current
CLI (no warning), but `:local/root` is strictly better: the
downstream's deps.edn brings their OWN mvn/git deps transitively,
which `:extra-paths` cannot.

### `--config-merge` exists and concats `:preloads`

`shadow/cljs/devtools/cli_opts.cljc:67` — `--config-merge DATA`
"merges additional EDN data into the build config"; accepts inline
EDN, a file path, or a classpath resource (lines 37-43). Applied in
`shadow/build.clj:333-334` via `build-api/deep-merge`, whose vector
case is `(concat a b) → distinct → vec` (`build/api.clj:37-40`). So
`--config-merge '{:devtools {:preloads [acme.pod]}}'` APPENDS to the
:client build's existing `[seon.dev.test-preload]`. (Its map/string
cases also mean a later rung could override `:output-to` per product.)

### npm resolution — compile-time vs runtime are different machines

- Compile-time: the npm service is started with the WHOLE top-level
  shadow-cljs.edn config (`devtools/server/common.clj` `:npm
  {:depends-on [:config] :start build-npm/start}`).
  `build/npm.clj:1049-1089`: `:js-package-dirs` is a top-level VECTOR
  of package dirs, each absolutized; default is
  `<project-dir>/node_modules` only when both it and
  `:node-modules-dir` are unset. `find-package*` (npm.clj:227-235)
  checks every configured dir in order — multiple roots are a
  first-class, supported shape. Duplicates are harmless.
- Runtime: `build/api.clj:121-122` — default `:js-provider :require`,
  with the comment "don't change the :js-provider default, node
  targets assume it is :require". npm packages are NOT bundled into
  `out/client/main.js`; Node resolves `require("pkg")` at runtime
  walking up from the BUNDLE's directory (seon's `out/`), i.e. it
  finds seon's `node_modules` only. A downstream package therefore
  needs `NODE_PATH` (honored by Node for CJS require — our bundle is
  CJS `:node-script`) or installation into seon's `node_modules`.

### The boot indexer's three hard edges (HEAD `src/seon/client.cljs`, `src/seon/indexing.clj`)

- `substrate-vars` (client.cljs:922-936) = curated base +
  `(seon.indexing/specced-fn-vars)` expanded IN seon.client — the
  macro only sees the calling ns's transitive require closure
  (indexing.clj:10-14), so downstream nses can never appear there.
  The extension precedent already exists: `seon.dev.test-preload`
  line 68 does `(reset! client/!indexed-test-vars (deftest-vars))` —
  a preload expands the macro in its OWN closure and registers into a
  client atom; `substrate-ns-set` and the test fn-rows concat it in.
- `first-party-file?` (indexing.clj:54-71) classifies a def as
  first-party iff its analyzer `:file` resolves under the
  macroexpanding JVM's `user.dir` (= seon checkout, since bin/seon
  runs shadow from `SEON_ROOT`). A downstream root outside the
  checkout is excluded — even from the downstream's OWN macro
  expansion. Needs `(System/getenv "SEON_EXTRA_SRC")` as a second
  accepted root (JVM-side, compile-time — trivially available).
- `read-src-file` (client.cljs:979-996) probes exactly
  `["src" "test" "guest-cljs/src"]`, each through
  `seon.platform/artifact-path` (i.e. under `SEON_RUNTIME_ROOT` when
  set). Downstream sources live elsewhere — append
  `process.env.SEON_EXTRA_SRC + "/src"` as a probe root, NOT routed
  through artifact-path (it is not a seon-checkout artifact).

### Rendering side is already data-driven

`:seon.ctx/included-prefixes` (ctx.cljs:168-205) is a store row on the
`[:seon.ctx/config-id "substrate"]` entity; downstream transacts
`"acme."` once (this is closed ask #1 in
`tmp/2026-06-11-seon-asks.md`). Agent eval needs NOTHING new: eval
runs `:analyze-deps? false` and tolerates undeclared-var warnings when
the symbol resolves on `globalThis` at munged paths
(`eval.cljs/lookup-value` + `truly-undeclared?`, eval.cljs:284-363);
dev-compiled downstream fns land there exactly like seon's.

### The asks-file sibling test

Rows 2/3/7 of `tmp/2026-06-11-seon-asks.md` establish the family:
`SEON_RUNTIME_ROOT` (artifacts vs data), `bin/seon` env overrides
(`SEON_CLUSTER_DIR`, sockets, ports), `bin/seon prep`. `SEON_EXTRA_SRC`
fits: one env var, default-absent = byte-identical seon behavior, read
by bin/seon + the compile-time macro + the boot indexer. Note:
bin/seon's auto-prep fingerprint hashes seon's deps.edn `:git` lines
only — the `-Sdeps` injection adds no git deps, so prep is unaffected;
if the downstream's deps.edn pins git deps, their first build pays the
download inside the watch window (acceptable; document, or extend the
fingerprint later).

## The questions, ranked

### a. How does SEON_EXTRA_SRC reach the build's source paths?

1. **RECOMMENDED — `-Sdeps` `:local/root` injection in bin/seon +
   bin/test-cljs.** When the env var is set, every `clj -M:cljs …`
   becomes `clj -Sdeps '{:deps {seon.extra/src {:local/root "…"}}}'
   -M:cljs …`. Live-proven; covers watch + release + test with the
   SAME mechanism (one CLI); the downstream's deps.edn carries their
   own JVM-side deps; zero committed-file changes per product; absent
   env = today's command byte-identical.
2. `:extra-paths` with an absolute path via an injected alias — also
   proven to work, but cannot bring the downstream's own dependencies;
   strictly weaker, same plumbing cost.
3. `#shadow/env` in shadow-cljs.edn `:source-paths` — does NOT work:
   deps mode ignores `:source-paths` entirely (our own config comment
   records the live proof).
4. API-driven config generation (`shadow.cljs.devtools.api` from a clj
   script) — maximum power, but replaces a working one-line CLI with
   new machinery; not justified. `SHADOW_CLJS` full-config env is the
   same idea with worse legibility.

Plus, for membership in the BUILD (classpath presence alone compiles
nothing): `--config-merge '{:devtools {:preloads [<SEON_EXTRA_PRELOAD>]}}'`
appended by bin/seon. Preloads load before `:main`, so the entry ns's
atom registration happens before `index-substrate!` runs. Caveat:
`:preloads` is `:devtools`-scoped — for a `release` of the :client
build the entry ns must instead be merged into the module entries
(e.g. `--config-merge '{:entries […]}'` or an `:init-fn`-adjacent
shape); the pod currently ships dev-compiled (bin/test-cljs comment:
release flattening breaks goog.global resolution), so this is a
documented edge, not a first-rung blocker.

### b. Where does compilation happen, and where do artifacts land?

**RECOMMENDED:** the downstream builds a COMBINED bundle by running
our supervisor from the seon checkout with env set
(`SEON_EXTRA_SRC=… bin/seon restart cljs-watch`, or a one-shot
`clj -Sdeps … -M:cljs compile client --config-merge …` documented as
`bin/seon`'s build path). `:output-to "out/client/main.js"` is
cwd-relative → artifacts land in seon's `out/`, which is precisely
what their `SEON_RUNTIME_ROOT` already points at. The artifacts/data
split is untouched: their store, tmp, logs stay in THEIR world dir
(platform.cljs:42-59). **No third leg.**

Constraint to document: one seon checkout = one flavored bundle at a
time (two products sharing a checkout would overwrite each other's
`out/client/main.js`). The clean later rung (M) is a per-product
output: `--config-merge '{:output-to "<their>/out/client/main.js"}'`
(deep-merge string case replaces) + a `SEON_POD_BUNDLE` env in
bin/seon's pod verb (`node ${SEON_POD_BUNDLE:-out/client/main.js}`),
with `SEON_RUNTIME_ROOT` still serving `out/bootstrap` + resources
from the seon checkout. Defer until someone actually runs two
products off one checkout.

### c. npm deps from the downstream's package.json

**RECOMMENDED:** two halves, both S:

- Compile-time: add top-level
  `:js-package-dirs ["node_modules" #shadow/env ["SEON_EXTRA_NPM" "node_modules"]]`
  to shadow-cljs.edn (top-level key, NOT build config — so
  `--config-merge` can't set it; the env-tag-with-default keeps
  seon-only builds byte-identical, and a duplicate "node_modules"
  entry is harmless since `find-package*` just probes each dir).
- Runtime: bin/seon's pod command exports `NODE_PATH=$SEON_EXTRA_NPM`
  when set, because with `:js-provider :require` the bundle's
  `require("pkg")` calls resolve at runtime from `out/`'s location and
  would otherwise only see seon's `node_modules`. (`NODE_PATH` is CJS
  resolution — fine, the bundle is `:node-script` CJS by deliberate
  decision recorded in shadow-cljs.edn.)

Alternative considered: telling downstreams to `npm install` their
packages into seon's checkout — works with zero code but pollutes the
shared checkout and dies the moment two products coexist. Rejected as
the documented path; fine as an emergency workaround.

### d. Boot indexer + context — making their nses first-class

Four pieces (first three are the must-have trio, each S):

1. **Var registration** — new `seon.client/!extra-substrate-vars`
   atom; `substrate-vars` consumers (`index-substrate!` fn-rows,
   `substrate-ns-set`) concat its deref, exactly as
   `!indexed-test-vars` already does. The downstream entry ns does
   `(reset! client/!extra-substrate-vars (seon.indexing/specced-fn-vars))`
   — their deftests ride the existing `deftest-vars` path the same
   way if their entry requires the test nses. Registration also makes
   their nses replay-SKIPPED (compiled code must not be re-evaled
   from the store — same rule as ours, for the same reason).
2. **`first-party-file?`** accepts files under
   `(System/getenv "SEON_EXTRA_SRC")` in addition to `user.dir` —
   without this their OWN macro expansion filters out every one of
   their defs.
3. **`read-src-file`** probes `$SEON_EXTRA_SRC/src` (and `/test`)
   after the three artifact roots, raw (not via `artifact-path`).
   `bin/seon`'s pod command already inherits env through nohup, so the
   pod sees the var at boot.
4. **Rendering** — `:seon.ctx/included-prefixes` row is shipped and
   sufficient for inclusion. GAP flagged: `full-source-roots`
   (ctx.cljs:234) is a hardcoded set, so downstream nses render as
   `(ns …)` stubs, not full source, unless that set learns a store
   row like included-prefixes did (its own docstring says the list
   dies when the `*.internal` splits land). Don't block on it; file
   as the natural follow-up (M, and it's a seon-internal cleanup with
   value independent of this feature).

The B1 teachings harness and gym suites run env-clean (no
`SEON_EXTRA_SRC`), so the substrate they measure is the stock one —
this must be stated in the implementation PRD as an invariant, not
left implicit.

### e. What breaks

- **Name collisions** — classpath ordering silently picks one file if
  the downstream defines a ns that already exists. Rule to document:
  downstreams use their OWN root prefix (e.g. `acme.*`). `my.*` is
  RESERVED for the human's live, store-replayed corpus — a COMPILED
  `my.*` ns would enter `substrate-ns-set` and replay-skip what
  should be agent-authored rows. Cheap guard (S, optional): the entry
  ns registration warns/fails-loud if any registered var's ns starts
  with `seon.` or `my.`.
- **Hot reload across two roots** — shadow's watcher watches all
  classpath dirs including the `:local/root` dep, so their edits
  rebuild like ours. The classpath itself is fixed at watcher launch:
  setting/changing `SEON_EXTRA_SRC` requires
  `bin/seon restart cljs-watch` (document; same as any deps change).
  A brand-new ns not yet required by the entry ns isn't in the build —
  the macro's documented freshness rule applies to them too.
- **Test suite with extra src present** — `:test` build's
  `:ns-regexp "-test$"` sweeps the whole classpath, so
  `SEON_EXTRA_SRC=… bin/test-cljs` compiles and runs THEIR tests too.
  Feature for them; hazard for us only if we run suites with the env
  set — keep seon CI/gym env-clean (above).
- **`.cljc` downstream files** — `ns-file-path`/`read-src-file` probe
  only `.cljs` filenames; a downstream `.cljc` ns compiles fine but
  its boot-indexed source read misses. Either document ".cljs only
  for extra src" (first rung) or add a `.cljc` probe fallback (S).
- **Release builds** — `:preloads` is dev-tooling; and the substrate
  itself currently requires dev compilation (goog.global resolution).
  A downstream wanting `:advanced` is out of scope until seon itself
  supports it.
- **`out/bootstrap`** — downstream nses are not in the self-host
  bootstrap `:entries`; they don't need to be (their compiled fns
  resolve via globalThis like ours), but agent code doing
  `(require 'acme.x)` inside cljs.js eval won't load analysis for it.
  Same status as most seon nses today — not a regression.

## Smells reported along the way

- `shadow-cljs.edn`'s `:source-paths ["src" "test"]` is admitted
  dead config in deps mode (its own comment says so). Harmless, but a
  reader trap — worth deleting when the file is next touched.
- `ctx.cljs/full-source-roots` hardcoded set vs the store-row pattern
  its sibling `included-prefixes` already uses (see §d.4).
- bin/seon auto-prep fingerprint covers only seon's deps.edn; a
  downstream deps.edn with git deps gets no pre-spawn prep (see the
  asks-sibling note above).

## Probe artifacts

`tmp/extra-src-probe/` (scratch, gitignored): `deps.edn` =
`{:paths ["src"]}`, `src/acme/demo.cljs` — used for the two `-Spath`
probes recorded above. Safe to delete.
