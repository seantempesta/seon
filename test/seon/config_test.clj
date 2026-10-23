(ns seon.config-test
  "The one config system: aero's tags read under Jolt (one deftest each), the shipped file
  validating against its schemas under each profile, a refused boot naming every unknown,
  invalid or missing key, the config written as facts on main and read back from the db, and
  no environment read anywhere in src but the loader (asked of Jolt's analyzer, by resolved
  host reference, never by text)."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.edn :as edn] [clojure.java.shell]
            [aero.core :as aero] [datahike.api :as d]
            [jolt.scheme :as scm] [jolt.analyzer :as an] [jolt.ir :as ir]
            [seon.config :as config] [seon.contract :as contract] [seon.db :as pdb] [seon.task :as task]))

(def ^:private dir (str (config/checkout) "/test/seon/config"))

(defn- read-file
  "A config file at p read with aero under profile prof, resolved and validated (or the error, thrown)."
  [p prof dir] (config/resolved (aero/read-config p {:profile prof ::config/checkout dir})))

(defn- tags
  "test/seon/config/tags.edn read with aero under profile p (default :default)."
  ([] (tags :default))
  ([p] (aero/read-config (str dir "/tags.edn") {:profile p ::config/checkout (config/checkout)})))

;; ---- aero under Jolt, tag by tag ---------------------------------------------------------

(deftest env-reads-a-set-variable-and-nil-for-an-unset-one
  (is (= (config/checkout) (:env (tags))))
  (is (nil? (:env-unset (tags)))))

(deftest envf-formats-variables
  (is (= (str (config/checkout) "/x") (:envf (tags)))))

(deftest or-takes-the-first-non-nil
  (is (= 7 (:or-unset (tags))))
  (is (= (config/checkout) (:or-set (tags)))))

(deftest long-parses-and-composes-with-or
  (is (= 42 (:long (tags))))
  (is (= 5 (:long-or (tags)))))

(deftest double-keyword-boolean-parse
  (is (= 1.5 (:double (tags))))
  (is (= :abc (:keyword (tags))))
  (is (true? (:boolean (tags)))))

(deftest profile-selects-by-the-profile-given
  (is (= :d (:profile (tags :default))))
  (is (= :ds (:profile (tags :deepseek)))))

(deftest ref-reads-another-key-also-under-or
  (is (= 9 (:ref (tags))))
  (is (= 9 (:ref-or (tags)))))

(deftest include-reads-a-file-relative-to-the-including-one
  (is (= {:included 42} (:include (tags)))))

(deftest join-merge-read-edn
  (is (= "a2c" (:join (tags))))
  (is (= {:a 1 :b 2} (:merge (tags))))
  (is (= {:x [1 2]} (:read-edn (tags)))))

(deftest prop-reads-a-system-property
  (is (= (System/getProperty "user.dir") (:prop (tags)))))

(deftest user-and-hostname-fall-to-default
  (is (= :someone (:user (tags))))
  (is (= :somewhere (:hostname (tags)))))

(deftest seon-checkout-is-derived-at-load
  (is (= (str (config/checkout) "/resources/seon/system.edn") (:checkout (tags)))))

;; ---- the shipped file and its schemas ----------------------------------------------------

(deftest the-shipped-file-validates-under-every-profile
  ;; the scripted model's script is stated only under :scripted (checks and tests)
  (doseq [p [:default :scripted :deepseek]]
    (let [c (read-file (config/path (config/checkout)) p (config/checkout))]
      (is (= (set (keys c)) (set (remove (cond-> #{:seon.config.ai/reasoning-effort :seon.config.source/root :seon.config.source/gate-checkout} (not= :scripted p) (conj :seon.config.ai/script))
                                         (config/keys-declared)))) p)))
  (let [shipped #(read-file (config/path (config/checkout)) % (config/checkout))]
    (is (= {:kind :none} (config/provider (shipped :default))) "no profile: no provider, never the scripted model")
    (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider (shipped :scripted))))
    (is (= :openai-compat (:seon.config.ai/provider (shipped :deepseek))))))

(deftest every-key-is-documented-by-its-schema
  (doseq [k (config/keys-declared)
          :let [f (get config/schema-forms k)
                props (some #(when (map? %) %) (take 2 (rest f)))]]
    (is (string? (:description props)) (str k " has no :description"))
    (is (string? (:seon.config/unit props)) (str k " has no :seon.config/unit"))))

(deftest machine-dependent-values-are-derived-at-load
  (is (= (str (config/checkout) "/resources/seon/tasks") (config/value :seon.config.root/tasks-dir)))
  (is (= (str (config/checkout) "/resources/seon/system.edn") (config/value :seon.config.root/system-path)))
  ;; the writer's buffer is the live agents' bound, by #ref
  (is (= (config/value :seon.config.root/max-agents) (config/value :seon.config.root/writer-buf))))

(defn- refused [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest an-unknown-key-refuses-boot-naming-it
  (let [r (refused #(read-file (str dir "/unknown-key.edn") :default (config/checkout)))]
    (is (= :seon.config/refused (:seon.error/type r)))
    (is (= [{:key :seon.config.root/max-agentz :problem :unknown-key :value "3"}] (:seon.config/problems r)))))

(deftest an-invalid-value-refuses-boot-naming-each-key
  (let [r (refused #(read-file (str dir "/invalid.edn") :default (config/checkout)))]
    (is (= :seon.config/refused (:seon.error/type r)))
    (is (= #{:seon.config.root/max-agents :seon.config.store/backend} (set (map :key (:seon.config/problems r)))))
    (is (every? #(= :invalid (:problem %)) (:seon.config/problems r)))))

(deftest a-missing-required-key-refuses-and-nil-is-absent
  (let [r (refused #(config/resolved (assoc config/config :seon.config.store/branching-factor nil)))]
    (is (= [{:key :seon.config.store/branching-factor :problem :missing-key :value "nil"}] (:seon.config/problems r))))
  ;; an optional key resolving nil is absent, not a stored nil
  (is (not (contains? (config/resolved (assoc config/config :seon.config.ai/thinking nil)) :seon.config.ai/thinking)))
  (is (= :disabled (:seon.config.ai/thinking (config/resolved (assoc config/config :seon.config.ai/thinking :disabled))))))

(deftest a-key-the-config-lacks-is-refused-not-nil
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.ai/reasoning-effort)))))
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.nope/x))))))

(deftest the-provider-is-the-config-s
  ;; bin/test runs under SEON_PROFILE=scripted
  (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider config/config)))
  (let [p (config/provider (assoc config/config :seon.config.ai/provider :openai-compat :seon.config.ai/thinking :disabled))]
    (is (= {:kind :openai-compat :base-url "https://api.deepseek.com" :model "deepseek-flash" :api-key-env "DEEPSEEK_API_KEY"
            :max-tokens (config/value :seon.config.ai/max-tokens) :thinking :disabled}
           p))
    ;; the facts say what a run handed another provider really used
    (is (= p (config/provider (config/with-provider config/config p))))
    (is (= "https://example.test" (:base-url (config/provider (config/with-provider config/config (assoc p :base-url "https://example.test"))))))))

;; ---- facts on main, read from the db -------------------------------------------------------

(deftest root-writes-the-config-on-main-and-the-db-answers-it
  (ts/test-branch! (fn [cfg conn] (let [child (keyword (str (name (:branch cfg)) "-a1"))
        c (assoc config/config :seon.config.agent/max-running 6)]
    (is (nil? (config/from-db @conn)))
    (d/transact conn {:tx-data (into (task/boot-tx "boot-1" 6) (config/facts c {:seon.config.web/port "--set"}))})
    (is (= c (config/from-db @conn)))
    (is (= {:seon.config.web/port "--set"} (d/q '[:find ?s . :where [?e :seon.root/id :root] [?e :seon.config/sources ?s]] @conn)))
    ;; the reconciler's root read sees the same config keys
    (is (= {:boot "boot-1" :max-running 6 :max-merging (config/value :seon.config.merge/max-running)} (task/root @conn)))
    ;; a branch made from main holds them too (what an agent forked onto it reads)
    (d/branch! conn (:branch cfg) child)
    (is (= c (config/from-db @(d/connect (assoc cfg :branch child)))))))))

;; a set-valued key written on main reads back as the same set, not the vector a pull of a
;; cardinality-many attribute answers (pure-tests 2026-10-23: :seon.config.source/process-vars)
(deftest a-set-valued-key-reads-back-as-the-set-it-was
  (ts/test-branch! (fn [_ conn]
    (let [vs '#{app.a/one app.b/two app.c/three}
          c (assoc config/config :seon.config.source/process-vars vs)]
      (d/transact conn {:tx-data (into (task/boot-tx "boot-1" 6) (config/facts c {}))})
      (is (= vs (:seon.config.source/process-vars (config/from-db @conn))))
      (is (set? (:seon.config.source/process-vars (config/from-db @conn))))
      (is (= c (config/from-db @conn)) "and the whole config is its db copy")))))

;; ---- no environment read outside the loader -------------------------------------------------

(defn- src-files []
  (sort (for [f (file-seq (io/file (config/checkout) "src")) :when (str/ends-with? (.getName f) ".clj")] f)))

(defn- getenv-refs
  "The System/getenv references of file f's top-level forms, by Jolt's analyzer (each form
  analyzed in its namespace, never run): [[ns form-head] ...]."
  [f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (loop [nsx 'user acc []]
      (let [form (binding [*ns* (or (find-ns nsx) *ns*)] (read {:eof ::eof :read-cond :allow} r))]
        (if (= ::eof form)
          acc
          (let [nsx' (if (and (seq? form) (= 'ns (first form))) (second form) nsx)
                ;; its namespace loaded (as the platform loads it), so its aliases resolve
                _ (when (not= nsx nsx') (require nsx'))
                node (an/analyze (scm/call "make-analyze-ctx" (str nsx')) form)
                hits (volatile! [])]
            ((fn walk [n]
               (when (and (= :host-static (:op n)) (= "getenv" (:member n)) (#{"System" "java.lang.System"} (:class n)))
                 (vswap! hits conj [nsx' (when (seq? form) (take 2 form))]))
               (ir/reduce-ir-children (fn [_ c] (walk c)) nil n))
             node)
            (recur nsx' (into acc @hits))))))))

(deftest no-environment-read-in-src-but-the-loader
  (let [refs (mapcat getenv-refs (src-files))]
    ;; the analyzer does find them: the loader's own three (checkout, profile, secret)
    (is (= #{'seon.config} (set (map first refs))))
    (is (= '#{(defn checkout) (defn profile) (defn secret)} (set (map second refs))))))

;; ---- layers: the shipped file, --config (a checkout's local.edn only when named), --set ---------------------------------------

(deftest the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
  (let [o (config/overrides ["scale" "100" "--set" ":seon.config.web/port=7791" "--config" "x.edn" "more"])]
    (is (= ["scale" "100" "more"] (:rest o)))
    (is (= [["--set" {:seon.config.web/port 7791}] ["--config x.edn" "x.edn"]] (:layers o))))
  (is (= ["root"] (config/without-overrides ["root" "--set" ":seon.config.root/server-port=7898"]))))

(deftest a-bad-set-refuses-naming-it
  (doseq [bad ["port=1" ":seon.config.web/port" "seon.config.web/port=1" ":seon.config.web/port=("]]
    (is (= :seon.config/refused (:seon.error/type (refused #(config/parse-set bad)))) bad))
  (is (= :seon.config/refused (:seon.error/type (refused #(config/overrides ["--set"]))))))

(deftest a-checkouts-local-config-is-a-layer-only-when-named
  ;; task seon.check/a-checks-roots-take-explicit-config: a checkout whose config/local.edn names
  ;; another source tree (the main tree's names the standing root's) gave every process there,
  ;; a check's root too, that tree; a layer is only what a command line names
  (let [co (str (System/getProperty "java.io.tmpdir") "/seon-config-checkout-" (random-uuid))
        local (str co "/config/local.edn")]
    (.mkdirs (java.io.File. (str co "/resources/seon/config")))
    (.mkdirs (java.io.File. (str co "/config")))
    (spit (str co "/resources/seon/config/default.edn") (slurp (str (config/checkout) "/resources/seon/config/default.edn")))
    (spit local "{:seon.config.source/root \"/elsewhere/next-merges\" :seon.config.web/port 4242}")
    (try
      (let [{c :config srcs :sources} (config/layered (config/load-layers co :default []))]
        (is (not= "/elsewhere/next-merges" (:seon.config.source/root c)) "unnamed, it is no layer")
        (is (not-any? #{"config/local.edn"} (vals srcs))))
      (let [{c :config srcs :sources} (config/layered (config/load-layers co :default ["--config" local]))]
        (is (= "/elsewhere/next-merges" (:seon.config.source/root c)) "named, it is one")
        (is (= 4242 (:seon.config.web/port c)))
        (is (= (str "--config " local) (srcs :seon.config.source/root))))
      (finally (clojure.java.shell/sh "rm" "-rf" co)))))

(deftest later-layers-win-key-by-key-and-name-their-source
  (let [ls (config/load-layers (config/checkout) :default
                               ["--config" (str dir "/local-ports.edn") "--set" ":seon.config.web/port=7999"])
        {c :config srcs :sources} (config/layered ls)]
    (is (= 7910 (:seon.config.root/server-port c)))
    (is (= 7999 (:seon.config.web/port c)))
    (is (= (str "--config " dir "/local-ports.edn") (srcs :seon.config.root/server-port)))
    (is (= "--set" (srcs :seon.config.web/port)))
    (is (= "resources/seon/config/default.edn" (srcs :seon.config.root/max-agents)))
    (is (= (set (keys c)) (set (keys srcs))))))

(deftest an-override-is-validated-by-the-same-schemas
  (is (= [{:key :seon.config.web/port :problem :invalid :value "-1"}]
         (:seon.config/problems (refused #(config/layered (config/load-layers (config/checkout) :default ["--set" ":seon.config.web/port=-1"]))))))
  (is (= :unknown-key
         (:problem (first (:seon.config/problems (refused #(config/layered (config/load-layers (config/checkout) :default ["--set" ":seon.config.web/porrt=1"])))))))))

(deftest no-port-is-listened-on-unless-the-config-says-so
  (let [c (:config (config/layered [(first (config/load-layers (config/checkout) :default []))]))]
    (is (= 0 (:seon.config.web/port c)))
    (is (= 0 (:seon.config.root/server-port c)))))

(deftest the-loader-reads-the-command-line-as-the-process-has-it
  (binding [*command-line-args* '("ports" "--set" ":seon.config.web/port=7998")]
    (is (= ["ports" "--set" ":seon.config.web/port=7998"] (config/argv)))
    (is (= 7998 (:seon.config.web/port (:config (config/layered (config/load-layers (config/checkout) :default (config/argv)))))))))

;; ---- the resolver: task -> model -> provider -> config ------------------------------------------

(deftest a-setting-resolves-task-then-model-then-provider-then-config
  (let [c (-> config/config
              (assoc-in [:seon.config.ai/providers "deepseek" :seon.config.ai/max-in-flight] 5)
              (assoc-in [:seon.config.ai/models "deepseek-v4-pro" :seon.config.ai/max-in-flight] 2))
        r #(config/resolve-in c {:seon.config.ai/max-in-flight "--set"} :seon.config.ai/max-in-flight %)]
    (is (= {:value 7 :source [:task]} (r {:task {:seon.config.ai/max-in-flight 7} :model "deepseek-v4-pro"})))
    (is (= {:value 2 :source [:model "deepseek-v4-pro"]} (r {:model "deepseek-v4-pro"})))
    (is (= {:value 5 :source [:provider "deepseek"]} (r {})))
    (is (= {:value 8 :source [:config "--set"]}
           (config/resolve-in config/config {:seon.config.ai/max-in-flight "--set"} :seon.config.ai/max-in-flight {}))))
  (is (= {:value "https://api.deepseek.com" :source [:provider "deepseek"]} (config/resolve :seon.config.ai/base-url)))
  (is (= [:config "resources/seon/config/default.edn"] (:source (config/resolve :seon.config.root/max-agents)))))

(deftest a-setting-set-nowhere-is-refused-not-nil
  (is (= :seon.config/refused (:seon.error/type (refused #(config/resolve :seon.config.ai/reasoning-effort {:model "deepseek-flash"})))))
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.nope/x))))))

(deftest the-manifest-is-validated-and-written-as-entities-on-main
  (is (= #{"deepseek-flash" "deepseek-v4-pro"} (set (keys (:seon.config.ai/models config/config)))))
  (is (= :invalid (:problem (first (:seon.config/problems
                                    (refused #(config/resolved (assoc-in config/config [:seon.config.ai/models "deepseek-flash" :seon.ai.model/input-usd-per-mtok] "cheap"))))))))
  (ts/test-branch! (fn [cfg conn]
    (d/transact conn {:tx-data (config/facts config/config config/sources)})
    (is (= 0.15 (d/q '[:find ?p . :where [?m :seon.ai.model/id "deepseek-flash"] [?m :seon.ai.model/input-usd-per-mtok ?p]] @conn)))
    (is (= "DEEPSEEK_API_KEY" (d/q '[:find ?v . :where [?p :seon.ai.model/provider-id "deepseek"] [?p :seon.config.ai/api-key-variable ?v]] @conn)))
    (is (= config/config (config/from-db @conn))))))

(deftest the-schema-forms-are-baked-once
  (testing "seon.contract's forms are seon.config's one baked map, not a second read of the directory"
    (is (identical? contract/forms config/schema-forms)))
  (testing "it holds every schema file's forms: the config keys' and the platform's"
    (is (contains? config/schema-forms :seon.config/config))
    (is (contains? config/schema-forms :seon.task/id))
    (is (every? #(contains? config/schema-forms %) (config/keys-declared)))))

(deftest bin-ports-writes-its-keys-under-their-current-names
  ;; task seon.db/stored-attributes-take-the-new-words: bin/ports owns the port keys of a
  ;; checkout's local layer and writes them on every run, keeping its ports and every other entry
  (let [root (config/checkout)
        f (str root "/target/ports-test-" (random-uuid) ".edn")]
    (try
      (spit f (str ";; the main tree's config/worktree-ports (x); edit it to move this checkout's ports.\n"
                   "{;; root's server (bin/root, bin/mcp)\n"
                   " :seon.config.root/server-port #long #or [#env SEON_SERVER_PORT 7920]\n"
                   " :seon.config.web/port #long #or [#env SEON_WEB_PORT 7921]\n"
                   " ;; the merges tree\n"
                   " :seon.config.source/root \".worktrees/merges\"}\n"))
      (let [r (clojure.java.shell/sh "env" "-u" "SEON_WEB_PORT" "-u" "SEON_SERVER_PORT" (str root "/bin/ports") f)
            text (slurp f)]
        (is (= 0 (:exit r)) (:err r))
        (is (= "7920 7921" (str/trim (:out r))) "it boots, on the ports the file held")
        (is (str/includes? text ":seon.config.root/server-port #long #or [#env SEON_SERVER_PORT 7920]"))
        (is (str/includes? text " ;; the merges tree\n :seon.config.source/root \".worktrees/merges\"}") "every other entry is kept")
        (is (= text (do (clojure.java.shell/sh "env" "-u" "SEON_WEB_PORT" (str root "/bin/ports") f) (slurp f))) "a second run writes the same file"))
      (finally (clojure.java.io/delete-file f true)))))

