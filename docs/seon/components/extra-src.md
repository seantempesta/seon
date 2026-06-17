---
type: component
status: active
tags: [component, agent]
---

# Extra source roots — SEON_EXTRA_SRC

How a downstream consumer extends the pod without forking seon. Two
paths, different jobs (research:
`docs/prds/agent-runtime/research/extra-src-research-2026-06-12.md`).

## When to use which

- **Path A — the store** (default; no env, no compile): code the AGENT
  authors or the operator transacts lives in the cluster store as
  `my.*` rows, replayed at boot and rendered into context. Use for
  agent-authored/evolving code and per-cluster customization.
- **Path B — `SEON_EXTRA_SRC`** (this component): the downstream ships
  AOT-compiled CLJS namespaces (their own root prefix, e.g. `acme.*`)
  plus npm deps, compiled INTO the pod bundle and boot-indexed like the
  core's own. Use for stable product code: vendor wrappers,
  domain APIs, anything wanting compile-time checking, instrumentation,
  and replay-skip semantics.

## Path A recipe (store)

```clojure
;; render acme.* namespaces into every agent's context
(seon.db/transact!
  {:seon.db/tx-data [{:seon.ctx/config-id "substrate"
                      :seon.ctx/included-prefixes ["acme."]}]})
;; agent-authored code: just eval it — detect-and-tee persists
;; :seon.fn/:seon.ns rows; my.* replays at boot
```

## Path B recipe (compiled extension)

Downstream world dir (example name "acme"):
`acme/{deps.edn,src/acme/*.cljs,node_modules,package.json}` where
`deps.edn` is `{:paths ["src"]}` plus any mvn/git deps.

```bash
export SEON_RUNTIME_ROOT=/path/to/seon   # artifacts from the seon checkout
export SEON_EXTRA_SRC=/path/to/acme      # their deps.edn project
export SEON_EXTRA_PRELOAD=acme.pod       # their entry ns
export SEON_EXTRA_NPM=/path/to/acme/node_modules  # only if npm deps
cd /path/to/seon && bin/seon restart cljs-watch && bin/seon restart pod
```

The entry ns (`acme.pod`) `:require`s their whole surface and registers
it (mirrors `seon.dev.test-preload`):

```clojure
(ns acme.pod
  (:require [acme.core] [acme.vendor.x]
            [clojure.string :as str]
            [seon.client :as client])
  (:require-macros [seon.indexing :refer [specced-fn-vars]]))

(reset! client/!extra-substrate-vars
        ;; filter to OWN prefix: the macro's closure also sees the seon
        ;; surface (those dedup away at boot-index, but filtering keeps
        ;; the registration honest)
        (filterv #(str/starts-with? (str (:ns (meta %))) "acme.")
                 (specced-fn-vars)))
```

## Mechanics (each grounded in the research doc)

- `bin/seon` / `bin/test-cljs` inject
  `-Sdeps '{:deps {seon.extra/src {:local/root "<SEON_EXTRA_SRC>"}}}'`
  into every `clj -M:cljs` invocation (deps mode: the tools.deps
  classpath IS the build's source set) plus
  `--config-merge '{:devtools {:preloads [<SEON_EXTRA_PRELOAD>]}}'`
  (deep-merge CONCATS vectors — `seon.dev.test-preload` is kept). All
  env unset = byte-identical commands (`bin/seon print-cmd <name>`).
- Boot indexer: registered extra vars get `:seon.fn` rows, their nses
  get FULL-SOURCE `:seon.ns` rows, and they join `substrate-ns-set`
  (replay-skipped — compiled code is never re-evaled from the store).
  `seon.indexing/first-party-file?` and `seon.client/read-src-file`
  both accept the extra root.
- npm: compile-time via the `#shadow/env ["SEON_EXTRA_NPM"
  "node_modules"]` entry in shadow-cljs.edn `:js-package-dirs`;
  runtime via `NODE_PATH=$SEON_EXTRA_NPM` exported by `bin/seon`'s pod
  command (the CJS bundle's `require("pkg")` resolves from `out/`).

## Rules and edges

- **Reserved prefixes:** `seon.*` (core) and `my.*` (the human's
  store-replayed corpus) are refused at boot-index time — the pod
  fails loudly naming the offending ns. Use your own root prefix.
- Changing `SEON_EXTRA_SRC` requires `bin/seon restart cljs-watch`
  (classpath fixed at watcher launch); file edits inside the root hot
  reload like seon's own.
- `bin/test-cljs` with the env set sweeps the downstream's `-test$`
  nses too (feature). seon CI/gym stay env-clean — `bin/gym` strips
  the `SEON_EXTRA_*` vars.
- One seon checkout = one flavored bundle at a time (combined output
  lands in seon's `out/`).
- `.cljs` files only for the extra root (boot source read probes
  `.cljs` paths); release/`:advanced` builds are out of scope (the
  core itself requires dev compilation).

## Key files

- `bin/seon` (injection helpers + `print-cmd`), `bin/test-cljs`,
  `bin/gym` (env-clean)
- `src/seon/client.cljs` — `!extra-substrate-vars`, reserved-prefix
  guard, `read-src-file` extra roots, full-source ns-rows
- `src/seon/indexing.clj` — `first-party-file?` extra root
- `shadow-cljs.edn` — `:js-package-dirs` `#shadow/env` entry
- Tests: `test/seon/client/extra_substrate_test.cljs` +
  `test/acme/extra_fixture.cljs`
