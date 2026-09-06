#!/usr/bin/env bb
;; Reproducible probe for the edit hook's current-src admission predicate.
;; Run from the repository root: bb this-file.bb

(binding [*in* (java.io.StringReader. "{}")] 
  (load-file "bin/seon-hook"))

(let [admit (deref (resolve 'source-index-path?))
      path #(str (.getCanonicalPath (java.io.File. %)))]
  (prn {:config-default (admit (path "config/default.edn"))
        :config-other (admit (path "config/test.edn"))
        :src (admit (path "src/seon/render.clj"))
        :test (admit (path "test/seon/render_test.clj"))
        :schema (admit (path "resources/seon/schemas/seon.render.edn"))}))
