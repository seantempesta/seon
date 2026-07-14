(ns seon.dev.test-roots
  "Discover the retained host test roots owned by existing runners."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File PushbackReader]))

(defn- clojure-test-file? [^File file]
  (and (.isFile file)
       (or (str/ends-with? (.getName file) "_test.clj")
           (str/ends-with? (.getName file) "_test.cljc"))))

(defn- files-below [root relative]
  (let [directory (io/file root relative)]
    (if (.isDirectory directory)
      (->> (file-seq directory)
           (filter clojure-test-file?))
      [])))

(defn- direct-files [root relative predicate]
  (let [directory (io/file root relative)]
    (if (.isDirectory directory)
      (->> (.listFiles directory)
           (filter #(and (clojure-test-file? %) (predicate (.getName ^File %)))))
      [])))

(defn- namespace-symbol [^File file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop []
      (let [form (read {:eof nil :read-cond :allow :features #{:clj}} reader)]
        (cond
          (nil? form) nil
          (and (seq? form) (= 'ns (first form))) (second form)
          :else (recur))))))

(defn- namespaces [files]
  (let [rows (mapv (fn [^File file]
                     {:seon.dev.test-root/file (.getCanonicalPath file)
                      :seon.dev.test-root/namespace (namespace-symbol file)})
                   files)
        missing (filterv (comp nil? :seon.dev.test-root/namespace) rows)
        duplicates (->> rows
                        (group-by :seon.dev.test-root/namespace)
                        (keep (fn [[namespace grouped]]
                                (when (< 1 (count grouped)) namespace)))
                        vec)]
    (when (seq missing)
      (throw (ex-info "Retained test files must declare an `ns`."
                      {:seon.dev.test-root/files
                       (mapv :seon.dev.test-root/file missing)})))
    (when (seq duplicates)
      (throw (ex-info "Retained test namespaces must have one source file."
                      {:seon.dev.test-root/namespaces duplicates})))
    (->> rows
         (map :seon.dev.test-root/namespace)
         (sort-by str)
         vec)))

(defn operator-test-files
  "Return the operator runner's dynamically discovered test files."
  [root]
  (->> (files-below root "test/seon/dev")
       (sort-by #(.getCanonicalPath ^File %))
       vec))

(defn writer-test-files
  "Return the database runner's dynamically discovered test files."
  [root]
  (->> (concat (files-below root "test/seon/db")
               (direct-files root "test/seon"
                             #(str/ends-with? % "_writer_test.clj")))
       distinct
       (sort-by #(.getCanonicalPath ^File %))
       vec))

(defn operator-test-namespaces
  "Return the operator runner's current test namespaces."
  [root]
  (namespaces (operator-test-files root)))

(defn writer-test-namespaces
  "Return the database runner's current test namespaces."
  [root]
  (namespaces (writer-test-files root)))
