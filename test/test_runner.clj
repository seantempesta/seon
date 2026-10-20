(ns test-runner
  "bin/test: loads every *_test namespace under test/ (or the namespaces named on the
  command line) and runs its deftests; exits 1 when any fails or errors."
  (:require [clojure.java.io :as io] [clojure.string :as str] [clojure.test :as t]))

(defn- test-namespaces
  "Namespace symbols of test/**/*_test.clj(c)."
  []
  (->> (file-seq (io/file "test"))
       (map #(.getPath %))
       (filter #(re-find #"_test\.cljc?$" %))
       (map #(-> % (str/replace #"^test/" "") (str/replace #"\.cljc?$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))

(defn -main [& names]
  (let [nss (if (seq names) (map symbol names) (test-namespaces))]
    (apply require nss)
    (let [{:keys [fail error]} (apply t/run-tests nss)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))
