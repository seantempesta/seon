(ns seon.dev.artifact-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [seon.dev.artifact :as artifact]))

(deftest artifact-digest-is-content-addressed
  (let [directory (fs/create-temp-dir {:prefix "seon-artifact-test-"})
        first-file (fs/path directory "a.txt")
        second-file (fs/path directory "nested/b.txt")]
    (try
      (fs/create-dirs (fs/parent second-file))
      (spit (str first-file) "alpha")
      (spit (str second-file) "beta")
      (let [digest (artifact/digest-paths directory [directory])]
        (is (= digest (artifact/digest-paths directory [directory])))
        (is (= digest (artifact/digest-paths directory ["."]))
            "relative inputs resolve against the declared digest root")
        (spit (str second-file) "changed")
        (is (not= digest (artifact/digest-paths directory [directory]))))
      (finally (fs/delete-tree directory)))))

(deftest cljs-build-command-is-structured
  (let [plain {:seon.dev.config/environment {}}
        extended {:seon.dev.config/environment
                  {"SEON_EXTRA_SRC" "/tmp/example"
                   "SEON_EXTRA_PRELOAD" "example.pod"}}
        plain-argv (artifact/cljs-command plain "compile" "client")
        extended-argv (artifact/cljs-command extended "watch" "client")]
    (is (= ["clj" "-M:cljs" "compile" "client"] plain-argv))
    (is (= "clj" (first extended-argv)))
    (is (some #{"-Sdeps"} extended-argv))
    (is (some #{"--config-merge"} extended-argv))
    (is (not-any? #{"bash" "-c"} extended-argv))))

(deftest acme-command-keeps-cache-and-build-identities-together
  (let [config {:seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/acme
                :seon.dev.config/shadow-cache-root
                "/checkout/tmp/shadow/acme"
                :seon.dev.config/environment
                {"SEON_EXTRA_SRC" "/checkout/acme"
                 "SEON_EXTRA_PRELOAD" "acme.pod"}}
        argv (artifact/cljs-command config "compile" "acme-client")
        merge-index (.indexOf argv "--config-merge")
        config-merge (edn/read-string (nth argv (inc merge-index)))]
    (is (= ["clj" "-Sdeps"] (subvec argv 0 2)))
    (is (= ["-M:cljs" "compile" "acme-client"]
           (subvec argv (- merge-index 3) merge-index)))
    (is (not (contains? config-merge :cache-root))
        "action config cannot select the Shadow server/cache identity")
    (is (= ['acme.pod] (get-in config-merge [:devtools :preloads])))
    (is (not-any? #{"client"} argv))))

(deftest default-command-does-not-add-a-cache-override
  (let [config {:seon.dev.config/artifact-flavor
                :seon.dev.artifact.flavor/default
                :seon.dev.config/shadow-cache-root "/checkout/.shadow-cljs"
                :seon.dev.config/environment {}}]
    (is (= ["clj" "-M:cljs" "compile" "client"]
           (artifact/cljs-command config "compile" "client")))))

(deftest legacy-manifests-upgrade-only-as-the-default-flavor
  (let [directory (fs/create-temp-dir {:prefix "seon-legacy-artifact-"})
        path (str (fs/path directory "artifact.edn"))
        digest (apply str (repeat 64 "a"))
        legacy {:seon.dev.artifact/version 1
                :seon.dev.artifact/published-at "2026-07-14T00:00:00Z"
                :seon.dev.artifact/writer-digest digest
                :seon.dev.artifact/client-digest digest
                :seon.dev.artifact/bootstrap-digest digest
                :seon.dev.artifact/css-digest digest
                :seon.dev.artifact/application-digest digest}
        base {:seon.dev.config/artifact-manifest path
              :seon.dev.config/shadow-cache-root
              (str (fs/path directory ".shadow-cljs"))
              :seon.dev.config/client-output
              (str (fs/path directory "out/client/main.js"))}]
    (try
      (spit path (pr-str legacy))
      (let [upgraded
            (artifact/read-manifest
              (assoc base :seon.dev.config/artifact-flavor
                     :seon.dev.artifact.flavor/default))]
        (is (= 2 (:seon.dev.artifact/version upgraded)))
        (is (= :seon.dev.artifact.flavor/default
               (:seon.dev.artifact/flavor upgraded)))
        (is (= "client" (:seon.dev.artifact/client-build-id upgraded))))
      (is (thrown-with-msg?
            Exception #"cannot identify this flavor"
            (artifact/read-manifest
              (assoc base :seon.dev.config/artifact-flavor
                     :seon.dev.artifact.flavor/acme))))
      (finally (fs/delete-tree directory)))))
