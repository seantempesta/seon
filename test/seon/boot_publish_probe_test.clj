(ns seon.boot-publish-probe-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.fn.analyzer :as analyzer]
            [seon.operator.runtime :as runtime]
            [seon.profile :as profile]
            [seon.render.value :as value]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Measures three publications of eight large source files including whole-program derivation widening; the installed replay measured 34 seconds per publication."
           :seon.test/long-ms 180000}
  publication-widening-measurement
  (let [root (str "tmp/boot-publish-" (random-uuid))
        directory (str root "/checkout")
        source (or (System/getenv "SEON_BOOT_PUBLISH_INPUT") (fs/source-directory))
        output (System/getenv "SEON_BOOT_PUBLISH_OUTPUT")
        parent (support/execution-handle db/*conn*)
        paths ["src/seon/cluster.clj" "src/seon/fn.clj" "src/seon/db.clj"
               "src/seon/turn.clj" "src/seon/instrument.clj" "src/seon/context.clj"
               "src/seon/ref.clj" "src/seon/schema.clj"]]
    (try
      (when output
        (spit output (str (value/core-text
                          {:source-directory (fs/source-directory)
                           :input-directory source
                           :readiness (select-keys (boot/readiness (get @runtime/running-instances "default"))
                                                   [:seon.boot/layers :seon.boot/ready-ms])}) "\n")))
      (doseq [path ["src" "test" "script" "resources" ".clj-kondo"]]
        (#'support/clone-directory! (io/file source path) (io/file directory path)))
      (doseq [path ["deps.edn" "dependency-pins.txt" "test-input-paths.txt"]]
        (io/copy (io/file source path) (io/file directory path)))
      (#'support/clone-directory! (io/file analyzer/cache-directory) (io/file root "resolver"))
      (doseq [path paths]
        (let [file (io/file directory path)]
          (.setWritable file true)
          (spit file (str "; Publication measurement.\n" (slurp file)))))
      (doseq [sample (range 3)]
       (let [held (store/open-overlay! (:seon.store/store parent))
            began (System/nanoTime)]
        (try
          (let [measured
                (with-bindings {#'cluster/*source-progress!*
                                (fn [phase]
                                  (when output
                                    (spit output (str (value/core-text [phase (/ (- (System/nanoTime) began) 1e6)]) "\n")
                                          :append true)))}
                  (profile/measure
                   #(#'cluster/full-source-refresh!
                      root held
                      (assoc (#'cluster/publication-roots)
                             :seon.fn/root directory
                             :seon.source/changed-paths paths
                             :seon.cluster/loaded-files {}
                             :seon.source/branch :boot-publish-probe
                             :seon.fn.analyzer/cache-root (str root "/resolver")))))]
            (when output
              (spit output (str (value/core-text
                                (assoc (dissoc measured :seon.profile/value :seon.profile/called :seon.profile/ranked-by-calls)
                                       :sample sample
                                       :analysis-calls (select-keys (:seon.profile/called measured)
                                                                   '[seon.fn.analyzer/parsed-facts seon.fn.analyzer/parse-facts
                                                                     seon.fn.analyzer/invoke-kondo]))) "\n")
                    :append true))
            (is (true? (:seon.source/built? (:seon.profile/value measured)))))
          (finally (store/release-store! held)))))
      (finally (support/delete-recursively! root)))))
