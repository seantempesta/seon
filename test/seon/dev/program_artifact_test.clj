(ns seon.dev.program-artifact-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.dev.program-artifact :as program-artifact]))

(defn- source [file resource-name]
  {:file (str file) :resource-name resource-name})

(deftest artifact-selects-only-admitted-cljs-sources
  (let [parent (fs/create-temp-dir {:prefix "seon-program-artifact-"})
        project (fs/path parent "project")
        cljs (fs/path project "src/example/core.cljs")
        cljc (fs/path project "src/example/schema.cljc")
        macro (fs/path project "src/example/macros.clj")
        dependency (fs/path parent "dependency/vendor.cljs")]
    (try
      (doseq [[file text] [[cljs "(ns example.core)\n"]
                           [cljc "(ns example.schema)\n"]
                           [macro "(ns example.macros)\n"]
                           [dependency "(ns vendor)\n"]]]
        (fs/create-dirs (fs/parent file))
        (spit (str file) text))
      (let [state {:project-dir (.toFile project)
                   :sources
                   {:dependency (source dependency "vendor.cljs")
                    :macro (source macro "example/macros.clj")
                    :schema (source cljc "example/schema.cljc")
                    :core (source cljs "example/core.cljs")}}
            sources (program-artifact/program-sources state)]
        (is (= ["example/core.cljs" "example/schema.cljc"]
               (vec (keys sources))))
        (is (= "(ns example.core)\n" (get sources "example/core.cljs")))
        (is (= "(ns example.schema)\n"
               (get sources "example/schema.cljc"))))
      (finally (fs/delete-tree parent)))))

(deftest artifact-text-and-digest-are-stable-and-source-sensitive
  (let [project (fs/create-temp-dir {:prefix "seon-program-stability-"})
        alpha (fs/path project "src/example/alpha.cljs")
        beta (fs/path project "src/example/beta.cljs")]
    (try
      (fs/create-dirs (fs/parent alpha))
      (spit (str alpha) "(ns example.alpha)\n")
      (spit (str beta) "(ns example.beta)\n")
      (let [resources [[:alpha (source alpha "example/alpha.cljs")]
                       [:beta (source beta "example/beta.cljs")]]
            left {:project-dir (.toFile project)
                  :sources (into (array-map) resources)}
            right {:project-dir (.toFile project)
                   :sources (into (array-map) (reverse resources))}
            text (program-artifact/artifact-text left)
            original-digest (program-artifact/digest text)]
        (is (= text (program-artifact/artifact-text right)))
        (is (= original-digest (program-artifact/digest text)))
        (is (re-matches #"[0-9a-f]{64}" original-digest))
        (spit (str beta) "(ns example.beta)\n(def changed true)\n")
        (is (not= original-digest
                  (program-artifact/digest
                   (program-artifact/artifact-text left)))))
      (finally (fs/delete-tree project)))))

(deftest artifact-refuses-unsafe-resource-names-and-symlink-escapes
  (let [parent (fs/create-temp-dir {:prefix "seon-program-refusal-"})
        project (fs/path parent "project")
        outside (fs/path parent "outside.cljs")
        link (fs/path project "src/example/escape.cljs")]
    (try
      (fs/create-dirs (fs/parent link))
      (spit (str outside) "(ns outside)\n")
      (fs/create-sym-link link outside)
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"escapes its admitted root"
           (program-artifact/program-sources
            {:project-dir (.toFile project)
             :sources {:escape (source link "example/escape.cljs")}})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unsafe resource name"
           (program-artifact/program-sources
            {:project-dir (.toFile project)
             :sources {:escape (source outside "../outside.cljs")}})))
      (finally (fs/delete-tree parent)))))

(deftest flush-hook-publishes-one-readable-artifact
  (let [project (fs/create-temp-dir {:prefix "seon-program-publish-"})
        cljs (fs/path project "src/example/core.cljs")
        state {:project-dir (.toFile project)
               :sources {:core (source cljs "example/core.cljs")}}
        output (fs/path project "out/client/program-sources.edn")]
    (try
      (fs/create-dirs (fs/parent cljs))
      (spit (str cljs) "(ns example.core)\n")
      (is (identical? state
                      (program-artifact/publish!
                       state "out/client/program-sources.edn")))
      (is (= (program-artifact/artifact-value state)
             (edn/read-string (slurp (str output)))))
      (is (empty? (fs/glob (fs/parent output) ".*.tmp")))
      (finally (fs/delete-tree project)))))
