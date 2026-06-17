(ns example.overrides
  "EXAMPLE third-party override preload. Rename `example.*` to your own
   prefix (it must NOT start with `seon.` or `my.` — those are reserved).

   This namespace is COMPILED INTO THE BUNDLE ONLY when this directory is
   enabled via the SEON_EXTRA_SRC + SEON_EXTRA_PRELOAD env vars (see the
   README). With the env vars unset it is inert: not on the classpath,
   never built, zero runtime effect.

   The override pattern — \"no more hooks\":
     1. `(:require)` the core namespace you want to customize.
     2. `set!` the fully-qualified core var to your replacement fn.

   Shadow emits in dependency order, so the required core ns module-loads
   FIRST and this `set!` re-points its var SECOND. The pod is a dev build
   (`goog.DEBUG` true, `*cljs-static-fns*` false), so every existing
   caller of the core fn reads the var late and picks up your version with
   NO recompilation — including callers compiled before this override
   existed. (Under an `:advanced` build `set!` re-pointing silently
   no-ops; the pod stays dev-compiled for exactly this reason.)

   This example overrides `seon.demo/greeting` — the always-on override
   regression target. Replace it with the real core fn you need to
   customize. NOTE: this is for CODE behavior changes. To configure the
   LLM (provider/model/etc.), do NOT override a fn — use the SEON_AI_*
   config path instead (see seon.ai's ns doc: env seeds the
   :seon.ai/config row once, then the DB owns it)."
  (:require [seon.demo]))

(set! seon.demo/greeting
      (fn [] "hello from the third-party override example"))
