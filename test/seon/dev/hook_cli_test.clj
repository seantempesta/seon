(ns seon.dev.hook-cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def repo-root
  (str (fs/normalize (fs/absolutize (System/getProperty "user.dir")))))

(defn- run-hook
  [event config-path]
  (let [result (shell/sh
                 {:cmd [(str (fs/path repo-root "bin/seon-hook"))]
                  :dir repo-root
                  :env (assoc (into {} (System/getenv))
                              "SEON_CONFIG" config-path)
                  :in (json/generate-string event)
                  :out :string
                  :err :string
                  :continue true})]
    {:seon.dev.hook-test/exit (:exit result)
     :seon.dev.hook-test/stderr (:err result)
     :seon.dev.hook-test/response
     (json/parse-string (str/trim (:out result)) true)}))

(defn- fixture []
  (let [directory (fs/path repo-root "tmp" (str "hook-cli-" (random-uuid)))
        config (fs/path directory "system.edn")]
    (fs/create-dirs directory)
    (spit (str config) "{:seon.config/on-core-error :log}\n")
    {:seon.dev.hook-test/directory directory
     :seon.dev.hook-test/config (str config)}))

(deftest claude-write-still-validates-prospective-content
  (let [{:seon.dev.hook-test/keys [directory config]} (fixture)
        path (str (fs/path directory "broken.cljs"))]
    (try
      (let [result (run-hook
                     {:hook_event_name "PreToolUse"
                      :tool_name "Write"
                      :tool_input {:file_path path
                                   :content "(ns broken.core\n"}}
                     config)]
        (is (zero? (:seon.dev.hook-test/exit result)))
        (is (= "block"
               (get-in result
                       [:seon.dev.hook-test/response :decision]))))
      (finally (fs/delete-tree directory)))))

(deftest codex-multifile-patch-checks-every-resulting-clojure-file
  (let [{:seon.dev.hook-test/keys [directory config]} (fixture)
        relative-dir (str (fs/relativize repo-root directory))
        paths (mapv #(str (fs/path directory %))
                    ["updated.cljs" "added.cljs" "moved-to.cljs"])
        [updated added moved-to] paths
        patch (str "*** Begin Patch\n"
                   "*** Update File: " relative-dir "/updated.cljs\n"
                   "*** Add File: " relative-dir "/added.cljs\n"
                   "*** Delete File: " relative-dir "/deleted.cljs\n"
                   "*** Update File: " relative-dir "/moved-from.cljs\n"
                   "*** Move to: " relative-dir "/moved-to.cljs\n"
                   "*** End Patch")]
    (try
      (doseq [path paths] (spit path "(ns broken.core\n"))
      (let [result (run-hook
                     {:hook_event_name "PostToolUse"
                      :tool_name "apply_patch"
                      :tool_input {:command patch}}
                     config)
            feedback (get-in result
                             [:seon.dev.hook-test/response
                              :hookSpecificOutput
                              :additionalContext])]
        (is (zero? (:seon.dev.hook-test/exit result)))
        (is (string? feedback))
        (doseq [path [updated added moved-to]]
          (testing path
            (is (str/includes? feedback (str (fs/relativize repo-root path)))))))
      (finally (fs/delete-tree directory)))))

(deftest codex-patch-cannot-mix-checkout-and-outside-paths
  (let [{:seon.dev.hook-test/keys [directory config]} (fixture)
        local-path (str (fs/relativize
                          repo-root
                          (fs/path directory "inside.cljs")))
        patch (str "*** Begin Patch\n"
                   "*** Update File: " local-path "\n"
                   "*** Delete File: ../outside.cljs\n"
                   "*** End Patch")]
    (try
      (let [result (run-hook
                     {:hook_event_name "PreToolUse"
                      :tool_name "apply_patch"
                      :tool_input {:command patch}}
                     config)]
        (is (= "block"
               (get-in result
                       [:seon.dev.hook-test/response :decision]))))
      (finally (fs/delete-tree directory)))))
