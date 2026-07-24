(ns seon.dev.config-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.config :as config]
            [seon.dev.release :as release]
            [seon.launch :as launch]))

(def package-members
  {:seon.release.member/bun "runtime/bun"
   :seon.release.member/writer "runtime/writer.jar"
   :seon.release.member/pod "runtime/client/main.js"
   :seon.release.member/runtime-assets "runtime/web"
   :seon.release.member/program-source "runtime/program-sources.edn"
   :seon.release.member/program-row "runtime/program-rows.edn"
   :seon.release.member/base-projection "runtime/base-projection.edn"
   :seon.release.member/page-plan "runtime/page-plan.edn"
   :seon.release.member/client-inventory
   "runtime/client/program-inventory.edn"
   :seon.release.member/babashka "runtime/bb"
   :seon.release.member/operator "runtime/operator.jar"
   :seon.release.member/detach-helper "runtime/detach.py"
   :seon.release.member/launcher "bin/seon"
   :seon.release.member/config "config"
   :seon.release.member/babashka-license
   "THIRD_PARTY_LICENSES/babashka-EPL-1.0.txt"
   :seon.release.member/bun-license "THIRD_PARTY_LICENSES/bun-MIT.txt"
   :seon.release.member/datahike-license
   "THIRD_PARTY_LICENSES/datahike-EPL-1.0.txt"
   :seon.release.member/source "SOURCE.edn"
   :seon.release.member/sbom "sbom.cdx.json"
   :seon.release.member/notices "THIRD_PARTY_NOTICES.md"})

(def package-identity
  {:seon.dev.release/bun-version "1.4.0"
   :seon.dev.release/bun-revision
   "d8ecf098572e2b8265b23e40c04efb4067e516cc"
   :seon.dev.release/babashka-version "1.12.218"
   :seon.dev.release/babashka-source-revision
   "0fb349c414e717800be775ba9cb77c95a9eb700d"
   :seon.dev.release/babashka-asset
   "babashka-1.12.218-macos-aarch64.tar.gz"
   :seon.dev.release/babashka-asset-sha-256
   "5bc992f39692b707403fc322e860fc82017da7de4a84a32267abb4d50a0c5f9d"
   :seon.dev.release/database-protocol-version 13
   :seon.dev.release/bun-member :seon.release.member/bun
   :seon.dev.release/writer-member :seon.release.member/writer
   :seon.dev.release/pod-member :seon.release.member/pod
   :seon.dev.release/runtime-assets-member :seon.release.member/runtime-assets
   :seon.dev.release/program-source-member :seon.release.member/program-source
   :seon.dev.release/program-row-member :seon.release.member/program-row
   :seon.dev.release/base-projection-member
   :seon.release.member/base-projection
   :seon.dev.release/page-plan-member :seon.release.member/page-plan
   :seon.dev.release/client-inventory-member
   :seon.release.member/client-inventory
   :seon.dev.release/babashka-member :seon.release.member/babashka
   :seon.dev.release/operator-member :seon.release.member/operator
   :seon.dev.release/detach-helper-member :seon.release.member/detach-helper
   :seon.dev.release/launcher-member :seon.release.member/launcher
   :seon.dev.release/config-member :seon.release.member/config
   :seon.dev.release/babashka-license-member
   :seon.release.member/babashka-license
   :seon.dev.release/bun-license-member :seon.release.member/bun-license
   :seon.dev.release/datahike-license-member
   :seon.release.member/datahike-license
   :seon.dev.release/source-member :seon.release.member/source
   :seon.dev.release/sbom-member :seon.release.member/sbom
   :seon.dev.release/notices-member :seon.release.member/notices})

(defn- package-fixture! []
  (let [root (fs/create-temp-dir {:prefix "seon-config-package-"})]
    (doseq [directory ["runtime/web/resources/public" "bin" "config"
                       "THIRD_PARTY_LICENSES"]]
      (fs/create-dirs (fs/path root directory)))
    (doseq [[path content]
            [["runtime/bun" "bun"]
             ["runtime/writer.jar" "writer"]
             ["runtime/client/main.js" "pod"]
             ["runtime/program-sources.edn" "{}"]
             ["runtime/program-rows.edn" "{}"]
             ["runtime/base-projection.edn" "{}"]
             ["runtime/page-plan.edn" "{}"]
             ["runtime/client/program-inventory.edn" "{}"]
             ["runtime/web/style.css" "css"]
             ["runtime/web/resources/public/seon-brand.css" ".brand {}"]
             ["runtime/bb" "bb"]
             ["runtime/operator.jar" "operator"]
             ["runtime/detach.py" "detach"]
             ["bin/seon" "launcher"]
             ["config/selected.edn" "{}"]
             ["THIRD_PARTY_LICENSES/babashka-EPL-1.0.txt" "EPL"]
             ["THIRD_PARTY_LICENSES/bun-MIT.txt" "MIT"]
             ["THIRD_PARTY_LICENSES/datahike-EPL-1.0.txt" "EPL"]
             ["SOURCE.edn" "{}"]
             ["sbom.cdx.json" "{}"]
             ["THIRD_PARTY_NOTICES.md" "notices"]]]
      (fs/create-dirs (fs/parent (fs/path root path)))
      (spit (str (fs/path root path)) content))
    (spit (str (fs/path root "release.edn"))
          (pr-str (release/create-manifest (str root) package-members
                                           package-identity)))
    root))

(deftest explicit-launch-selection-is-validated-and-artifact-bound
  (let [configuration (config/load! ".")
        descriptor (:seon.dev.config/launch-descriptor configuration)
        environment (:seon.dev.config/environment configuration)]
    (is (= "1" (get environment "SEON_FS_READ_ONLY")))
    (is (= "1" (get environment "SEON_FS_LOCK"))
        "a normal agent cannot make the source-checkout grant writable")
    (is (= descriptor
           (:seon.dev.config/launch-descriptor
            (config/select-launch-descriptor configuration descriptor))))
    (is (thrown-with-msg?
         Exception #"another artifact"
         (config/select-launch-descriptor
          configuration
          (assoc-in descriptor
                    [::launch/runtime ::launch/artifact-flavor]
                    :seon.dev.artifact.flavor/acme))))
    (is (thrown-with-msg?
         Exception #"descriptor is invalid"
         (config/select-launch-descriptor configuration {})))))

(deftest writer-heap-policy-is-bounded-data
  (let [configuration (config/load! ".")
        descriptor (:seon.dev.config/launch-descriptor configuration)]
    (is (nil? (config/writer-max-heap {}))
        "heap selection is resolved from explicit hardware, never hidden here")
    (is (= "512m"
           (config/writer-max-heap
            {:seon.dev.config/operational-envelope
             {:seon.config.database.writer/jvm-heap-mb 512}})))
    (is (= "768m"
           (config/writer-max-heap
            {:seon.dev.config/writer-max-heap "768m"})))
    (is (thrown-with-msg?
         Exception #"positive JVM size"
         (config/select-launch-descriptor
          (assoc configuration :seon.dev.config/writer-max-heap "unbounded")
         descriptor)))))

(deftest config-apply-resolution-rejects-operational-footguns-with-steering
  (let [root (fs/create-temp-dir {:prefix "seon-config-floors-"})
        process-dir (fs/path root "processes")
        configuration {:seon.dev.config/root (str root)
                       :seon.dev.config/cluster-dir (str (fs/path root "cluster"))
                       :seon.dev.config/process-dir (str process-dir)
                       :seon.dev.config/environment {}}
        hardware {:seon.hardware/cores 8
                  :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
                  :seon.hardware/fd-soft-limit 2048}]
    (try
      (doseq [[attribute value floor]
              [[:seon.config.database.writer/jvm-heap-mb 1 2]
               [:seon.config.database.read/max-result-weight 59999 60000]
               [:seon.config.database.transport/maximum-frame-bytes 65535 65536]
               [:seon.config.database.transport/maximum-connections 1 2]
               [:seon.config.database.executor/maximum-queued-request-bytes 65539 65540]
               [:seon.config.database.transport/maximum-input-bytes 65539 65540]
               [:seon.config.database.transport/maximum-output-bytes 65535 65536]
               [:seon.config.database.transport/maximum-session-output-bytes 65535 65536]]]
        (let [path (fs/path root (str (name attribute) ".edn"))
              manifest {:seon.config/database
                        (assoc {:seon.config.database.transport/maximum-frame-bytes 65536}
                               attribute
                               value)}
              _ (spit (str path) (pr-str manifest))
              error
              (with-redefs-fn
                {#'config/hardware-observations (constantly hardware)}
                (fn []
                  (try
                    (config/select-manifest configuration (str path))
                    nil
                    (catch Exception error error))))]
          (is (= value (get (ex-data error) attribute)))
          (is (= floor (:seon.config/floor (ex-data error))))
          (is (string? (:seon.config/reason (ex-data error))))
          (is (str/includes? (:seon.config/steering (ex-data error))
                             (str attribute)))))
      (let [attribute :seon.config.execution/host-respawn-backoff-ms
            path (fs/path root "host-respawn-backoff.edn")
            _ (spit (str path)
                    (pr-str {:seon.config/execution {attribute 999}}))
            error
            (with-redefs-fn
              {#'config/hardware-observations (constantly hardware)}
              (fn []
                (try
                  (config/select-manifest configuration (str path))
                  nil
                  (catch Exception exception exception))))]
        (is (= 999 (get (ex-data error) attribute)))
        (is (= 1000 (:seon.config/floor (ex-data error))))
        (is (str/includes? (:seon.config/steering (ex-data error))
                           (str attribute))))
      (finally
        (fs/delete-tree root {:force true})))))

(deftest config-apply-resolution-enforces-liveness-horizons
  (let [root (fs/create-temp-dir {:prefix "seon-config-horizons-"})
        configuration {:seon.dev.config/root (str root)
                       :seon.dev.config/cluster-dir (str (fs/path root "cluster"))
                       :seon.dev.config/process-dir (str (fs/path root "processes"))
                       :seon.dev.config/environment
                       {"SEON_TURN_TIMEOUT_MS" "900000"}}
        hardware {:seon.hardware/cores 8
                  :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
                  :seon.hardware/fd-soft-limit 2048}
        cases
        [["deadline.edn"
          {:seon.config/run {:seon.config.run/deadline-ms 359999}
           :seon.config/model-variants
           {:planning {:seon.ai/agent-attempt-timeout-ms 360000}}}
          :seon.config.run/deadline-ms 360000]
         ["watchdog.edn"
          {:seon.config/watchdog {:seon.config.watchdog/stale-ms 900000}}
          :seon.config.watchdog/stale-ms 900001]]]
    (try
      (doseq [[filename manifest attribute floor] cases]
        (let [path (fs/path root filename)
              _ (spit (str path) (pr-str manifest))
              error
              (with-redefs-fn
                {#'config/hardware-observations (constantly hardware)}
                (fn []
                  (try
                    (config/select-manifest configuration (str path))
                    nil
                    (catch Exception error error))))]
          (is (= floor (:seon.config/floor (ex-data error))))
          (is (contains? (ex-data error) attribute))
          (is (str/includes? (:seon.config/steering (ex-data error))
                             (str attribute)))))
      (finally
        (fs/delete-tree root {:force true})))))

(deftest manifest-selection-publishes-immutable-generation-named-envelopes
  (let [root (fs/create-temp-dir {:prefix "seon-config-envelope-"})
        manifest-path (fs/path root "selected.edn")
        process-dir (fs/path root "processes")
        configuration {:seon.dev.config/root (str root)
                       :seon.dev.config/cluster-dir (str (fs/path root "cluster"))
                       :seon.dev.config/process-dir (str process-dir)
                       :seon.dev.config/environment {"SEON_CONFIG"
                                                     (str manifest-path)}}
        hardware {:seon.hardware/cores 8
                  :seon.hardware/system-memory-bytes (* 32 1024 1024 1024)
                  :seon.hardware/fd-soft-limit 2048}]
    (try
      (spit (str manifest-path) "{}\n")
      (with-redefs-fn
        {#'config/hardware-observations (constantly hardware)}
        (fn []
          (let [first-selection (config/select-manifest configuration nil)
                first-path (:seon.dev.config/launch-envelope-path first-selection)
                first-value (slurp first-path)
                first-environment
                (:seon.dev.config/environment first-selection)
                resolved-manifest-path
                (:seon.dev.config/resolved-manifest-path first-selection)
                second-selection (config/select-manifest configuration nil)
                second-path (:seon.dev.config/launch-envelope-path second-selection)]
            (is (not= first-path second-path))
            (is (re-find #"launch-envelope-[0-9]+\.edn$" first-path))
            (is (= first-value (slurp first-path)))
            (is (= resolved-manifest-path
                   (get first-environment
                        "SEON_RESOLVED_MANIFEST_PATH")))
            (is (= (#'config/sha-256 (slurp resolved-manifest-path))
                   (get first-environment
                        "SEON_RESOLVED_MANIFEST_SHA_256")))
            (is (= "64"
                   (get first-environment
                        "SEON_DB_INITIALIZATION_PAGE_ROWS")))
            (is (fs/regular-file? second-path))
            (is (not (fs/exists? (fs/path process-dir "launch-envelope.edn")))))))
      (finally
        (fs/delete-tree root {:force true})))))

(deftest artifact-descriptors-own-cache-build-output-and-manifest-identities
  (let [root (str (fs/normalize (fs/absolutize ".")))
        default (config/artifact-configuration root {})
        acme (config/artifact-configuration
               root {"SEON_ARTIFACT_DESCRIPTOR" "acme/artifact.edn"
                     "SEON_CLIENT_OUT" "out-acme/client/main.js"})]
    (is (= :seon.dev.artifact.flavor/default
           (:seon.dev.config/artifact-flavor default)))
    (is (= "client" (:seon.dev.config/client-build-id default)))
    (is (= (str (fs/path root ".shadow-cljs"))
           (:seon.dev.config/shadow-cache-root default)))
    (is (= (str (fs/path root "out/client/main.js"))
           (:seon.dev.config/client-output default)))
    (is (= "artifact.edn"
           (:seon.dev.config/artifact-manifest-name default)))
    (is (true? (:seon.dev.config/test-build? default)))
    (is (= :acme.artifact/runtime
           (:seon.dev.config/artifact-flavor acme)))
    (is (= "acme-client" (:seon.dev.config/client-build-id acme)))
    (is (= (str (fs/path root "tmp/shadow/acme"))
           (:seon.dev.config/shadow-cache-root acme)))
    (is (= (str (fs/path root "out-acme/client/main.js"))
           (:seon.dev.config/client-output acme)))
    (is (= "artifact-acme.edn"
           (:seon.dev.config/artifact-manifest-name acme)))
    (is (false? (:seon.dev.config/test-build? acme)))
    (is (not= (select-keys default
                           [:seon.dev.config/client-build-id
                            :seon.dev.config/shadow-cache-root
                            :seon.dev.config/client-output
                            :seon.dev.config/artifact-manifest-name])
              (select-keys acme
                           [:seon.dev.config/client-build-id
                            :seon.dev.config/shadow-cache-root
                            :seon.dev.config/client-output
                            :seon.dev.config/artifact-manifest-name])))))

(deftest artifact-descriptor-rejects-hybrid-or-invalid-data
  (let [root (str (fs/normalize (fs/absolutize ".")))
        directory (fs/create-temp-dir {:prefix "seon-artifact-descriptor-"})
        invalid (fs/path directory "invalid.edn")]
    (spit (str invalid) "{:not/a :descriptor}")
    (testing "an output override cannot silently select another build"
      (is (thrown-with-msg?
            Exception #"does not match"
            (config/artifact-configuration
              root {"SEON_ARTIFACT_DESCRIPTOR" "acme/artifact.edn"
                    "SEON_CLIENT_OUT" "out/client/main.js"}))))
    (testing "descriptor data is validated"
      (is (thrown-with-msg?
            Exception #"artifact descriptor is invalid"
            (config/artifact-configuration
              root {"SEON_ARTIFACT_DESCRIPTOR" (str invalid)}))))
    (fs/delete-tree directory)))

(deftest acme-shadow-cache-is-selected-through-startup-environment
  (let [root (str (fs/normalize (fs/absolutize ".")))
        default (config/artifact-configuration root {})
        acme (config/artifact-configuration
               root {"SEON_ARTIFACT_DESCRIPTOR" "acme/artifact.edn"})
        ambient {"SHADOW_CLJS" "{:log {:level :debug}}"}
        default-environment (config/shadow-environment ambient default)
        acme-environment (config/shadow-environment ambient acme)
        shadow-config (read-string (get acme-environment "SHADOW_CLJS"))]
    (is (identical? ambient default-environment)
        "the default flavor does not rewrite its command environment")
    (is (= {:level :debug} (:log shadow-config))
        "unrelated explicit Shadow configuration survives")
    (is (= (:seon.dev.config/shadow-cache-root acme)
           (:cache-root shadow-config))
        "the flavor-owned root overrides ambient cache identity")))

(deftest extracted-package-is-verified-before-development-selection
  (let [root (package-fixture!)]
    (try
      (with-redefs [config/artifact-configuration
                    (fn [& _]
                      (throw (ex-info "development flavor was consulted" {})))
                    config/shadow-environment
                    (fn [& _]
                      (throw (ex-info "Shadow environment was consulted" {})))]
        (let [configuration (config/load! (str root))]
          (is (false? (:seon.dev.config/source-checkout? configuration)))
          (is (= (or (get (System/getenv) "SEON_RENDER_STRICT") "0")
                 (get-in configuration
                         [:seon.dev.config/environment
                          "SEON_RENDER_STRICT"])))
          (is (= (str (fs/canonicalize (fs/path root "runtime/bun")))
                 (:seon.dev.config/bun-executable configuration)))
          (is (= (str (fs/canonicalize (fs/path root "runtime/writer.jar")))
                 (:seon.dev.config/writer-output configuration)))
          (is (= (str (fs/canonicalize
                       (fs/path root "runtime/client/main.js")))
                 (:seon.dev.config/client-output configuration)))
          (is (= (str (fs/canonicalize (fs/path root "runtime/web")))
                 (:seon.dev.config/runtime-assets configuration)))
          (is (= (str (fs/canonicalize
                       (fs/path root
                                "runtime/web/resources/public/seon-brand.css")))
                 (get-in configuration
                         [:seon.dev.config/environment "SEON_BRAND_CSS"])))
          (is (= (str (fs/canonicalize
                       (fs/path root "config/selected.edn")))
                 (get-in configuration
                         [:seon.dev.config/environment "SEON_CONFIG"])))
          (is (= (str (fs/canonicalize
                       (fs/path root "runtime/program-sources.edn")))
                 (:seon.dev.config/program-source configuration)))
          (is (= (str (fs/canonicalize (fs/path root "runtime/detach.py")))
                 (:seon.dev.config/detach-helper configuration)))
          (is (not (str/starts-with?
                    (:seon.dev.config/process-dir configuration)
                    (str (fs/canonicalize root))))
              "mutable package process state stays outside immutable bytes")
          (is (= (str (fs/path root "release.edn"))
                 (:seon.dev.config/artifact-manifest configuration)))))
      (finally (fs/delete-tree root {:force true})))))

(deftest invalid-or-missing-package-never-derives-a-launch-descriptor
  (doseq [invalid? [false true]]
    (let [root (if invalid?
                 (package-fixture!)
                 (fs/create-temp-dir {:prefix "seon-config-missing-package-"}))
          launch-called? (atom false)]
      (try
        (when invalid?
          (spit (str (fs/path root "runtime/client/main.js")) "changed"))
        (with-redefs [launch/default-descriptor
                      (fn [& _]
                        (reset! launch-called? true)
                        (throw (ex-info "launch descriptor was derived" {})))]
          (is (thrown-with-msg?
               Exception
               (if invalid? #"digest does not match" #"manifest does not exist")
               (config/load! (str root))))
          (is (false? @launch-called?)))
        (finally (fs/delete-tree root {:force true}))))))
