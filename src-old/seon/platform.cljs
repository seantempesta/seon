(ns seon.platform
  "Resolve artifact paths and process environment for Bun runtimes.

   This leaf isolates platform-specific reads used during process setup.
   Durable configuration and application state remain database-owned.")

;; ============================================================
;; Artifact path resolution — SEON_RUNTIME_ROOT.
;;
;; The pod reads two kinds of relative paths:
;;
;;   ARTIFACTS — things the seon checkout OWNS: the self-host
;;     bootstrap output (out/bootstrap), the core source roots
;;     the boot indexer reads (src, test, guest-cljs/src), the static
;;     web assets (resources/public/*). These route through
;;     [[artifact-path]]: when the SEON_RUNTIME_ROOT env var is set
;;     they resolve against it; unset, they stay CWD-relative (seon's
;;     own usage — the pod runs from the repo root).
;;
;;   DATA — the running cluster's OWN state: the store, tmp/ (port
;;     files, sockets), logs/. These NEVER route through here; a
;;     downstream consumer running the pod from its project root keeps
;;     its data CWD-relative and points SEON_RUNTIME_ROOT at the seon
;;     checkout that owns the compiled artifacts.
;; ============================================================

(defn env-val
  "The `process.env` value for `var-name`, or nil when unset/blank.

   Also nil when the process environment is unavailable. The ONE low-level env
   reader — `seon.config`'s typed knob accessors sit on top of this, and
   `runtime-root` below reads through it. (`platform` is a leaf, so it
   cannot route through `seon.config` — config requires platform — which
   is why this primitive lives here.)"
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [var-name]
  (let [v (some-> (.. js/globalThis -process) (.-env) (aget var-name))]
    (when (and (string? v) (not= "" (.trim v))) v)))

(defn- runtime-root
  "The SEON_RUNTIME_ROOT env value with any trailing slash trimmed, or
   nil when unset/blank (or when the process environment is unavailable)."
  []
  (when-let [v (env-val "SEON_RUNTIME_ROOT")]
    (if (and (> (count v) 1) (= "/" (subs v (dec (count v)))))
      (subs v 0 (dec (count v)))
      v)))

(defn artifact-path
  "Resolve a BUILD/SOURCE artifact path under SEON_RUNTIME_ROOT.

   E.g. `\"out/bootstrap\"`,
   `\"src\"`, `\"resources/public/css/\"`: joined under
   SEON_RUNTIME_ROOT when that env var is set, else returned unchanged
   (CWD-relative — seon's own usage is byte-identical). DATA paths
   (store, logs, tmp, sockets) must NOT route through here — see the
   block comment above."
  {:malli/schema [:=> [:cat :string] :string]}
  [rel]
  (if-let [root (runtime-root)]
    (str root "/" rel)
    rel))
