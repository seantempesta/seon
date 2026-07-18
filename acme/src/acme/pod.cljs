(ns acme.pod
  "Acme's ENTRY ns — named by SEON_EXTRA_PRELOAD, appended to the pod
   build's :preloads. Requiring the whole acme surface HERE pulls it into
   this ns's compile-time require closure, which is what lets the
   `public-fn-vars` macro (expanded HERE) actually see acme.* vars — a
   helper living in a seon ns could NOT, because the macro expands against
   its own caller's closure.

   The (reset! …) below is THE registration that makes acme source
   boot-index (fn rows + full-source ns rows) and join the replay-skip
   set. BUG B is precisely: omit this and the entire acme surface is
   SILENTLY invisible to indexing → context → retrieval, with no error.
   To reproduce the bug, comment out the reset! (or use a preload that
   omits it)."
  (:require [acme.brand]
            [acme.helpers]
            [acme.notes]
            [acme.overrides]
            [acme.widget]
            [acme.context :as context]
            [clojure.string :as str]
            [seon.client :as client])
  (:require-macros [seon.indexing :refer [public-fn-vars]]))

(reset! client/!extra-core-vars
        (filterv #(str/starts-with? (str (:ns (meta %))) "acme.")
                 (public-fn-vars)))

;; Lane-U: after the pod boots its agents (conn open + roster started),
;; install acme's context blocks + canvas wiring into every live agent's own
;; ctx scope via the seon override primitive `seon.agent.ctx/install!`. A
;; one-shot timer because the preload runs BEFORE agents start.
(js/setTimeout #(context/install-all!) 12000)

(defn -main
  "Start the packaged ACME pod after this namespace registers its extensions."
  {:malli/schema [:=> [:cat [:* :any]] :any]}
  [& args]
  (apply client/-main args))
