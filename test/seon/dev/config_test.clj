(ns seon.dev.config-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.config :as config]
            [seon.dev.release :as release]
            [seon.launch :as launch]))

(def package-members
  {:seon.release.member/bun "runtime/bun"
   :seon.release.member/writer "runtime/writer.jar"
   :seon.release.member/pod "runtime/pod.js"
   :seon.release.member/execution "runtime/execution.js"
   :seon.release.member/runtime-assets "runtime/web"
   :seon.release.member/program-source "runtime/program-sources.edn"})

(def package-identity
  {:seon.dev.release/bun-version "1.4.0"
   :seon.dev.release/bun-revision
   "d8ecf098572e2b8265b23e40c04efb4067e516cc"
   :seon.dev.release/database-protocol-version 10
   :seon.dev.release/execution-protocol-version 3
   :seon.dev.release/bun-member :seon.release.member/bun
   :seon.dev.release/writer-member :seon.release.member/writer
   :seon.dev.release/pod-member :seon.release.member/pod
   :seon.dev.release/execution-member :seon.release.member/execution
   :seon.dev.release/runtime-assets-member :seon.release.member/runtime-assets
   :seon.dev.release/program-source-member :seon.release.member/program-source})

(defn- package-fixture! []
  (let [root (fs/create-temp-dir {:prefix "seon-config-package-"})]
    (fs/create-dirs (fs/path root "runtime/web"))
    (doseq [[path content]
            [["runtime/bun" "bun"]
             ["runtime/writer.jar" "writer"]
             ["runtime/pod.js" "pod"]
             ["runtime/execution.js" "execution"]
             ["runtime/program-sources.edn" "{}"]
             ["runtime/web/style.css" "css"]]]
      (spit (str (fs/path root path)) content))
    (spit (str (fs/path root "release.edn"))
          (pr-str (release/create-manifest (str root) package-members
                                           package-identity)))
    root))

(deftest explicit-launch-selection-is-validated-and-artifact-bound
  (let [configuration (config/load! ".")
        descriptor (:seon.dev.config/launch-descriptor configuration)]
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
    (is (= "512m" (config/writer-max-heap {})))
    (is (= "768m"
           (config/writer-max-heap
            {:seon.dev.config/writer-max-heap "768m"})))
    (is (thrown-with-msg?
         Exception #"positive JVM size"
         (config/select-launch-descriptor
          (assoc configuration :seon.dev.config/writer-max-heap "unbounded")
          descriptor)))))

(deftest artifact-flavors-own-cache-build-output-and-manifest-identities
  (let [root (str (fs/normalize (fs/absolutize ".")))
        default (config/artifact-configuration root {})
        acme (config/artifact-configuration
               root {"SEON_ARTIFACT_FLAVOR" "acme"
                     "SEON_CLIENT_OUT" "out-acme/client/main.js"})]
    (is (= :seon.dev.artifact.flavor/default
           (:seon.dev.config/artifact-flavor default)))
    (is (= "client" (:seon.dev.config/client-build-id default)))
    (is (= "execution" (:seon.dev.config/execution-build-id default)))
    (is (= (str (fs/path root ".shadow-cljs"))
           (:seon.dev.config/shadow-cache-root default)))
    (is (= (str (fs/path root "out/client/main.js"))
           (:seon.dev.config/client-output default)))
    (is (= (str (fs/path root "out/execution/main.js"))
           (:seon.dev.config/execution-output default)))
    (is (= "artifact.edn"
           (:seon.dev.config/artifact-manifest-name default)))
    (is (= :seon.dev.artifact.flavor/acme
           (:seon.dev.config/artifact-flavor acme)))
    (is (= "acme-client" (:seon.dev.config/client-build-id acme)))
    (is (= "acme-execution"
           (:seon.dev.config/execution-build-id acme)))
    (is (= (str (fs/path root "tmp/shadow/acme"))
           (:seon.dev.config/shadow-cache-root acme)))
    (is (= (str (fs/path root "out-acme/client/main.js"))
           (:seon.dev.config/client-output acme)))
    (is (= (str (fs/path root "out-acme/execution/main.js"))
           (:seon.dev.config/execution-output acme)))
    (is (= "artifact-acme.edn"
           (:seon.dev.config/artifact-manifest-name acme)))
    (is (not= (select-keys default
                           [:seon.dev.config/client-build-id
                            :seon.dev.config/execution-build-id
                            :seon.dev.config/shadow-cache-root
                            :seon.dev.config/client-output
                            :seon.dev.config/execution-output
                            :seon.dev.config/artifact-manifest-name])
              (select-keys acme
                           [:seon.dev.config/client-build-id
                            :seon.dev.config/execution-build-id
                            :seon.dev.config/shadow-cache-root
                            :seon.dev.config/client-output
                            :seon.dev.config/execution-output
                            :seon.dev.config/artifact-manifest-name])))))

(deftest artifact-flavor-rejects-hybrid-or-unknown-coordinates
  (let [root (str (fs/normalize (fs/absolutize ".")))]
    (testing "an output override cannot silently select another build"
      (is (thrown-with-msg?
            Exception #"does not match"
            (config/artifact-configuration
              root {"SEON_ARTIFACT_FLAVOR" "default"
                    "SEON_CLIENT_OUT" "out-acme/client/main.js"}))))
    (testing "the flavor selector is closed data"
      (is (thrown-with-msg?
            Exception #"Unknown Seon artifact flavor"
            (config/artifact-configuration
              root {"SEON_ARTIFACT_FLAVOR" "invented"}))))))

(deftest acme-shadow-cache-is-selected-through-startup-environment
  (let [root (str (fs/normalize (fs/absolutize ".")))
        default (config/artifact-configuration root {})
        acme (config/artifact-configuration
               root {"SEON_ARTIFACT_FLAVOR" "acme"})
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
        (let [configuration (config/load! (str root))
              runtime (get-in configuration
                              [:seon.dev.config/launch-descriptor
                               ::launch/runtime])]
          (is (false? (:seon.dev.config/source-checkout? configuration)))
          (is (= (or (get (System/getenv) "SEON_RENDER_STRICT") "0")
                 (get-in configuration
                         [:seon.dev.config/environment
                          "SEON_RENDER_STRICT"])))
          (is (= (str (fs/canonicalize (fs/path root "runtime/bun")))
                 (:seon.dev.config/bun-executable configuration)))
          (is (= (str (fs/canonicalize (fs/path root "runtime/writer.jar")))
                 (:seon.dev.config/writer-output configuration)))
          (is (= (str (fs/canonicalize (fs/path root "runtime/pod.js")))
                 (:seon.dev.config/client-output configuration)))
          (is (= (str (fs/canonicalize
                       (fs/path root "runtime/execution.js")))
                 (:seon.dev.config/execution-output configuration)
                 (::launch/execution-output runtime)))
          (is (= (str (fs/canonicalize (fs/path root "runtime/web")))
                 (:seon.dev.config/runtime-assets configuration)))
          (is (= (str (fs/canonicalize
                       (fs/path root "runtime/program-sources.edn")))
                 (:seon.dev.config/program-source configuration)))
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
          (spit (str (fs/path root "runtime/pod.js")) "changed"))
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
