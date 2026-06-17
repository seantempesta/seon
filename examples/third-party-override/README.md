# Third-party override example

How a downstream deployment overrides a **core function** at **build time** —
per-function, no hooks, no forking core. This directory is a self-contained
example; it is **inert** unless you enable it with two env vars.

## The model

The pod is a **compiled package** (kernel + core, in `out/client/main.js`) plus
the **DB layer** (agent code, loaded from datahike at boot). A third party adds a
small **override preload** under its own namespace prefix that `(:require)`s a
core namespace and `set!`s the var it wants to change. Because the build emits in
dependency order and the pod is a dev build (late binding), the override re-points
the live var after core loads and **every existing caller picks it up with no
recompile**.

Reference: `docs/prds/agent-runtime/research/third-party-override-build-2026-06-17.md`.

## Enable it

Point `SEON_EXTRA_SRC` at this directory and name the preload ns in
`SEON_EXTRA_PRELOAD`, then (re)start the build + pod **with those vars set in the
environment**:

```sh
export SEON_EXTRA_SRC="$(pwd)/examples/third-party-override"
export SEON_EXTRA_PRELOAD=example.overrides
bin/seon restart cljs-watch     # recompiles with the extra source root + preload
bin/seon restart pod            # picks up the new bundle
```

- `SEON_EXTRA_SRC` is added as a tools.deps `:local/root` (so the dir's own
  `deps.edn`/`:paths` and any transitive deps come along).
- `SEON_EXTRA_PRELOAD` makes `example.overrides` a `:preloads` graph root, so
  shadow compiles it (classpath presence alone compiles nothing).

**Gating:** with the vars unset, `bin/seon print-cmd cljs-watch` is just
`clj -M:cljs watch client` — no `-Sdeps`, no `--config-merge`. The example is not
on the classpath and is never built. So it can live in the repo permanently and
costs nothing until you turn it on.

## Verify

In the pod REPL (or the inspector at `http://127.0.0.1:7890`):

```clojure
(seon.demo/greeting)      ;; => "hello from the third-party override example"
(seon.demo/greet-loudly)  ;; => "hello from the third-party override example!"
```

`greet-loudly` is a core caller compiled before the override existed — it returns
the overridden text, demonstrating that callers route to your version with no
hook and no recompile.

## Override your own function

1. Rename `example.*` to your prefix (not `seon.`/`my.`).
2. In `src/<your-prefix>/overrides.cljs`, `(:require)` the core ns and `set!` the
   var(s) you want to change to your implementation.
3. Set `SEON_EXTRA_PRELOAD=<your-prefix>.overrides` and restart.

You can override several vars in one preload, and add new namespaces of your own
alongside the overrides.

## Caveats

- **Dev build only.** `set!` re-pointing relies on `*cljs-static-fns* false`
  (the dev `:none` build). An `:advanced` build silently no-ops it — keep the pod
  dev-compiled, or compile the override into the package directly.
- **Config is not an override.** To change LLM provider/model/keys/etc., use the
  `SEON_AI_*` config path (data), not a code override — see `seon.ai`'s ns doc.
- **Agents cannot do this.** Agent evals run in their own namespaces and cannot
  override compiled core/third-party functions; build-time third-party overrides
  (this mechanism) are the supported way to change core behavior.
