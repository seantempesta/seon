(ns seon.instrument-replaced-roots-test
  "A Var's source recorded by path, relative or absolute, is its own compile."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.instrument :as instrument]))

(deftest a-namespace-loaded-by-its-absolute-path-reports-no-replaced-roots
  (let [probe-ns 'seon.replaced-roots-probe
        directory (.toFile (java.nio.file.Files/createTempDirectory
                            "replaced-roots" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file directory "src" "seon" "replaced_roots_probe.clj")
        root-tuple [(symbol (str probe-ns) "probe") "src/seon/replaced_roots_probe.clj"]]
    (try
      (io/make-parents source)
      (spit source "(ns seon.replaced-roots-probe)\n(defn probe [] :probe)\n")
      (load-file (.getAbsolutePath source))
      (is (= (.getAbsolutePath source) (:file (meta (find-var (first root-tuple)))))
          "load-file records the absolute path")
      (is (= [] (instrument/replaced-roots [root-tuple])))
      (alter-var-root (find-var (first root-tuple)) (constantly (fn [] :replaced)))
      (is (= [(first root-tuple)] (mapv :seon.instrument/replaced-var (instrument/replaced-roots [root-tuple])))
          "a replaced root is still reported")
      (finally
        (remove-ns probe-ns)
        (doseq [file (reverse (file-seq directory))] (io/delete-file file true))))))
