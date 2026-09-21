(ns seon.dev.clj-kondo-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.dev.clj-kondo :as sut]
            [seon.cluster.process :as operator.process]
            [seon.test-support :as test-support]))

(deftest incomplete-dependency-cache-is-repopulated-before-analysis
  (let [root (io/file "tmp/kondo-completeness" (str (random-uuid)))
        project (.getCanonicalFile (io/file "."))
        run-command! (fn [argv]
               (operator.process/run-process!
                {:seon.operator.subprocess/argv argv
                 :seon.operator.subprocess/directory (.getCanonicalPath root)
                 :seon.operator.subprocess/deadline-ms 300000}))]
    (.mkdirs (io/file root ".clj-kondo/.cache/v1"))
    (doseq [path ["src" "resources" "reference-code" "deps.edn"]]
      (fs/create-sym-link (io/file root path) (io/file project path)))
    (io/copy (io/file project ".clj-kondo/config.edn")
             (io/file root ".clj-kondo/config.edn"))
    (try
      (is (= :warmed (::sut/status (sut/ensure-dependency-cache! (str root)))))
      (let [recorded (edn/read-string
                      (slurp (io/file root "tmp/test-changed/dependency-cache.edn")))
            entries (::sut/contents recorded)]
        (is (seq entries) "successful population records actual cache bytes")
        (is (= :current (::sut/status (sut/ensure-dependency-cache! (str root)))))
        (let [missing (io/file root ".clj-kondo/.cache" (ffirst entries))]
          (fs/delete missing)
          (is (= :warmed (::sut/status (sut/ensure-dependency-cache! (str root)))))
          (is (.isFile missing) "a partial cache is repaired despite its directory")))
      (let [lint (run-command! ["clj-kondo" "--lint" "src/seon/flow.clj"
                        "--config" "{:output {:format :edn}}"])
            output (edn/read-string (:seon.operator.subprocess/output lint))]
        (is (empty? (filter #(= :error (:level %)) (:findings output)))
            (pr-str (:findings output))))
      (finally
        (test-support/delete-recursively! root)))))
