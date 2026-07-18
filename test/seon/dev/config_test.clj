(ns seon.dev.config-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [seon.dev.config :as config]
            [seon.launch :as launch]))

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
