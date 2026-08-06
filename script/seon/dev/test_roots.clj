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

(defn- namespace-form [^File file features]
  (with-open [reader (PushbackReader. (io/reader file))]
    (loop []
      (let [form (read {:eof nil :read-cond :allow :features features} reader)]
        (cond
          (nil? form) nil
          (and (seq? form) (= 'ns (first form))) form
          :else (recur))))))

(defn- namespace-symbol [^File file features]
  (second (namespace-form file features)))

(defn- namespace-require-targets [form]
  (->> (drop 2 form)
       (filter #(and (seq? %) (= :require (first %))))
       (mapcat rest)
       (map #(if (sequential? %) (first %) %))))

(defn- cljs-only-require-target? [target]
  (or (string? target)
      (and (symbol? target)
           (boolean (re-find #"^(?:cljs|goog)(?:\.|$)" (str target))))))

(defn- jvm-test-namespace? [^File file]
  (let [form (namespace-form file #{:clj})]
    (and form
         (not-any? cljs-only-require-target?
                   (namespace-require-targets form)))))

(defn- below? [root relative ^File file]
  (.startsWith (.toPath (.getCanonicalFile file))
               (.toPath (.getCanonicalFile (io/file root relative)))))

(defn- writer-test-file? [root ^File file]
  (and (clojure-test-file? file)
       (not (below? root "test/seon/dev" file))
       (jvm-test-namespace? file)))

(defn- namespaces [files features]
  (let [rows (mapv (fn [^File file]
                     {:seon.dev.test-root/file (.getCanonicalPath file)
                      :seon.dev.test-root/namespace
                      (namespace-symbol file features)})
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

(defn test-files
  "Return every retained Clojure test file below `test`."
  [root]
  (let [directory (io/file root "test")]
    (if (.isDirectory directory)
      (->> (file-seq directory)
           (filter clojure-test-file?)
           (sort-by #(.getCanonicalPath ^File %))
           vec)
      [])))

(defn operator-test-files
  "Return the operator runner's dynamically discovered test files."
  [root]
  (->> (files-below root "test/seon/dev")
       (sort-by #(.getCanonicalPath ^File %))
       vec))

(defn writer-test-files
  "Return the database runner's dynamically discovered test files."
  [root]
  (let [directory (io/file root "test")]
    (if (.isDirectory directory)
      (->> (file-seq directory)
           (filter #(writer-test-file? root %))
           (sort-by #(.getCanonicalPath ^File %))
           vec)
      [])))

(defn operator-test-namespaces
  "Return the operator runner's current test namespaces."
  [root]
  (namespaces (operator-test-files root) #{:clj}))

(defn writer-test-namespaces
  "Return the database runner's current test namespaces."
  [root]
  (namespaces (writer-test-files root) #{:clj}))
