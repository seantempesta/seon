(ns seon.dev.program-artifact-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.dev.config :as config]
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

(deftest row-derivation-reanalyzes-with-shadow-devtools-disabled
  (let [state
        {:shadow.build/config {:devtools {:enabled true}}
         :shadow.build.modules/config
         {:main {:entries
                 ['shadow.cljs.devtools.client.node 'seon.client]}}}]
    (let [derived
          (#'program-artifact/disable-shadow-devtools-config state)]
      (is (false?
           (get-in derived
                   [:shadow.build/config :devtools :enabled])))
      (is (= ['seon.client]
             (get-in derived
                     [:shadow.build.modules/config :main :entries]))))))

(deftest page-plan-build-consumes-the-operator-resolved-manifest-identity
  (let [project (fs/create-temp-dir {:prefix "seon-page-plan-manifest-"})
        selected (fs/path project "resolved-manifest.edn")
        text "{:seon.config/database {}}\n"
        sha-256 (config/config-manifest-digest text)
        environment
        {"SEON_RESOLVED_MANIFEST_PATH" (str selected)
         "SEON_RESOLVED_MANIFEST_SHA_256" sha-256
         "SEON_DB_INITIALIZATION_PAGE_ROWS" "256"}]
    (try
      (spit (str selected) text)
      (with-redefs [program-artifact/build-environment
                    (constantly environment)]
        (let [canonical (str (fs/canonicalize selected))]
          (is (= {:seon.dev.artifact/config-manifest-digest sha-256
                  :seon.dev.artifact/config-manifest-path canonical
                  :seon.dev.artifact/page-rows 256
                  :seon.dev.artifact/environment
                  (assoc environment "SEON_CONFIG" canonical)}
                 (#'program-artifact/resolved-build-configuration
                  {:project-dir (.toFile project)})))))
      (spit (str selected) "{}\n")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"resolved manifest digest changed"
           (with-redefs [program-artifact/build-environment
                         (constantly environment)]
             (#'program-artifact/resolved-build-configuration
              {:project-dir (.toFile project)}))))
      (finally (fs/delete-tree project)))))

(deftest row-flush-publishes-the-live-derivation-byte-for-byte
  (let [project (fs/create-temp-dir {:prefix "seon-program-rows-publish-"})
        source-file (fs/path project "src/example/core.cljs")
        row-output (fs/path project "out/client/program-rows.edn")
        page-plan-output (fs/path project "out/client/page-plan.edn")
        state {:project-dir (.toFile project)
               :sources {:core (source source-file "example/core.cljs")}}
        rows [{:seon.fn/sym "example.core/value"
               :seon.fn/source "(defn value [] 42)"}]
        compiled-row-text (pr-str rows)
        row-artifact-text
        (str "{:seon.dev.artifact/program-rows " compiled-row-text "}\n")
        page-plan
        {:seon.db.initialization/fingerprint "page-plan"
         :seon.db/initialization-pages
         [{:seon.db.initialization/page-index 0}]}
        page-plan-text (pr-str page-plan)]
    (try
      (fs/create-dirs (fs/parent source-file))
      (spit (str source-file) "(ns example.core)\n(defn value [] 42)\n")
      (with-redefs-fn
        {#'program-artifact/derive-program-rows
         (fn [_state program-source-text target]
           (is (= (program-artifact/artifact-text state)
                  program-source-text))
           (is (= (str (fs/canonicalize row-output))
                  (str target)))
           {:seon.dev.artifact/program-rows rows
            :seon.dev.artifact/program-row-text compiled-row-text
            :seon.dev.artifact/program-row-artifact-digest
            (program-artifact/digest row-artifact-text)
            :seon.dev.artifact/page-plan page-plan
            :seon.dev.artifact/page-plan-text page-plan-text})
         #'program-artifact/derive-base-load-plan
         (fn [_state]
           {:seon.host.context/units []})}
        #(let [changed state]
           (program-artifact/publish!
            changed "out/client/program-sources.edn")
           (let [row-state
                 (program-artifact/publish-rows!
                  changed
                  "out/client/program-sources.edn"
                  "out/client/program-rows.edn")]
             (is (not (identical? changed row-state)))
             (is (= {:seon.host.context/units []}
                    (get-in row-state
                            [:seon.dev.program-artifact/prepared-program-rows
                             "out/client/program-rows.edn"
                             :seon.dev.artifact/base-load-plan])))
             (is (identical?
                  row-state
                  (program-artifact/publish-page-plan!
                   row-state
                   "out/client/program-rows.edn"
                   "out/client/page-plan.edn"))))))
      (is (= (program-artifact/artifact-text state)
             (slurp (str (fs/path project
                                  "out/client/program-sources.edn")))))
      (is (= {:seon.dev.artifact/program-rows rows}
             (edn/read-string (slurp (str row-output)))))
      (is (= row-artifact-text
             (slurp (str row-output))))
      (is (= {:seon.dev.artifact/page-plan page-plan}
             (edn/read-string (slurp (str page-plan-output)))))
      (is (= (str "{:seon.dev.artifact/page-plan " page-plan-text "}\n")
             (slurp (str page-plan-output))))
      (is (empty? (fs/glob (fs/parent row-output) ".*.tmp")))
      (finally (fs/delete-tree project)))))

(deftest base-projection-flush-accepts-the-edn-materialized-fingerprint
  (let [project (fs/create-temp-dir {:prefix "seon-base-projection-publish-"})
        row-path "out/client/program-rows.edn"
        projection-path "out/client/base-projection.edn"
        rows [{:seon.fn/sym "example.core/value"
               :seon.fn/source "(defn value [] 42)"}]
        row-text (str "{:seon.dev.artifact/program-rows " (pr-str rows) "}\n")
        ;; `edn/read-string` turns the CLJS numeric fingerprint into Long.
        projection (edn/read-string
                    "{:seon.schema.projection/fingerprint -552006374}")
        prepared
        {:seon.dev.artifact/program-row-artifact-digest
         (program-artifact/digest row-text)
         :seon.dev.artifact/base-projection projection
         :seon.dev.artifact/base-load-plan {}
         :seon.dev.artifact/page-plan
         {:seon.db/initialization-pages
          [{:seon.db.initialization/fingerprint "page-plan"}]}}
        state {:project-dir (.toFile project)
               :seon.dev.program-artifact/prepared-program-rows
               {row-path prepared}}
        output (fs/path project projection-path)]
    (try
      (fs/create-dirs (fs/parent output))
      (spit (str (fs/path project row-path)) row-text)
      (is (identical?
           state
           (program-artifact/publish-base-projection!
            state row-path projection-path)))
      (is (= {:seon.dev.artifact/base-projection projection
              :seon.host.context/base-load-plan {}
              :seon.db.initialization/fingerprint "page-plan"}
             (edn/read-string (slurp (str output)))))
      (finally (fs/delete-tree project)))))
