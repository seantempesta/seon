(ns sidecar-poc.seon-bootstrap
  "Probe namespace — requires a growing set of V0 substrate namespaces to
   validate the sidecar overlay build. Each require here is a probe; the
   namespace either compiles cleanly under wasm32-wasip2 + the overlay or
   it doesn't.

   Layering, easy → hard:

   1. `seon.schema`  — pure Malli, .cljc, zero Node deps. SHOULD compile.
   2. `seon.error`   — pure CLJS, .cljs, ex-info walking. SHOULD compile.
   3. `seon.parse`   — pure CLJS, .cljc, reader. SHOULD compile.
   4. `seon.code`    — pure CLJC. SHOULD compile.
   5. `seon.db` (OVERLAY) — sidecar-flavored. SHOULD compile.
   6. `seon.log`     — requires seon.db + seon.schema. SHOULD compile w/
                       overlay.
   7. `seon.platform`— host detection (no requires). SHOULD compile.
   8. `seon.render`  — requires seon.eval (which has node:fs deps).
                       LIKELY BLOCKED.
   9. `seon.agent`   — requires seon.db + seon.eval + seon.render + ...
                       LIKELY BLOCKED.

   Run via `clj -M:cljs:cljs-sidecar release sidecar-agent` (alias
   stacking ensures `src-overlay` is on the classpath).

   When a layer fails to compile, comment out the offending require and
   document the blocker in `bench/v0-port-survey.md`."
  (:require
    [seon.schema :as schema]
    [seon.error  :as err]
    [seon.parse  :as parse]
    [seon.code   :as code]
    [seon.db     :as db]
    [seon.log    :as log]
    [seon.platform :as plat]
    ;; Layer 8 — render (light, requires seon.eval transitively)
    [seon.render :as render]
    ;; Layer 9 — eval (cljs.js / shadow.cljs.bootstrap.node — likely
    ;; compile-time success, runtime depends on node:fs availability)
    [seon.eval :as seval]
    ;; Layer 10 — agent (the big one, requires all of the above)
    [seon.agent :as agent]))

(defn probe-info []
  {:schema-keys-count (count (schema/current-keys))
   :error-shape       (err/->map (ex-info "probe" {}))
   :platform          (plat/host)
   :code-loaded?      (some? code/parse)
   :parse-loaded?     (some? parse/parse-forms)
   :db-loaded?        (some? db/transact!)
   :log-loaded?       (some? log/info!)})

(set! (.-sidecarBootstrapProbe js/globalThis) probe-info)
