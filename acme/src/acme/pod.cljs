(ns acme.pod
  "Acme's ENTRY ns — named by SEON_EXTRA_PRELOAD, appended to the pod
   build's :preloads. Requiring the whole acme surface HERE pulls it into
   this ns's compile-time require closure, which is what lets the
   `specced-fn-vars` macro (expanded HERE) actually see acme.* vars — a
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
            [clojure.string :as str]
            [seon.client :as client])
  (:require-macros [seon.indexing :refer [specced-fn-vars]]))

(reset! client/!extra-core-vars
        (filterv #(str/starts-with? (str (:ns (meta %))) "acme.")
                 (specced-fn-vars)))
