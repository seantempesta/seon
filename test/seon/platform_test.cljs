(ns seon.platform-test
  "Contract tests for `seon.platform` — host detection + the
   SEON_RUNTIME_ROOT artifact-path resolution (downstream-consumer
   extensibility, 2026-06-11): build/source artifact paths resolve
   against the env root when set, stay CWD-relative when unset; data
   paths never route through the helper (callers' contract, pinned by
   the unchanged-default case here)."
  (:require
    [cljs.test :refer [deftest is]]
    [seon.platform :as platform]))

(deftest host-detection
  ;; The test build runs under Node.
  (is (= :node (platform/host)))
  (is (true? (platform/node?)))
  (is (false? (platform/wasi?))))

(deftest artifact-path-env-override
  (let [env  (.-env js/process)
        orig (aget env "SEON_RUNTIME_ROOT")]
    (try
      (js-delete env "SEON_RUNTIME_ROOT")
      (is (= "out/bootstrap" (platform/artifact-path "out/bootstrap"))
          "unset → byte-identical (CWD-relative, seon's own usage)")
      (aset env "SEON_RUNTIME_ROOT" "/opt/seon")
      (is (= "/opt/seon/out/bootstrap"
             (platform/artifact-path "out/bootstrap"))
          "set → resolved under the runtime root")
      (aset env "SEON_RUNTIME_ROOT" "/opt/seon/")
      (is (= "/opt/seon/resources/public/css/"
             (platform/artifact-path "resources/public/css/"))
          "trailing slash on the root is trimmed, not doubled")
      (aset env "SEON_RUNTIME_ROOT" "")
      (is (= "src" (platform/artifact-path "src"))
          "blank env value behaves as unset")
      (finally
        (if (some? orig)
          (aset env "SEON_RUNTIME_ROOT" orig)
          (js-delete env "SEON_RUNTIME_ROOT"))))))
