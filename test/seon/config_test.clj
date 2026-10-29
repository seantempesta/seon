(ns seon.config-test
  "The one config system: aero's tags read under Jolt (one deftest each), the shipped file
  validating against its schemas under each profile, a refused boot naming every unknown,
  invalid or missing key, the config written as facts on main and read back from the db, and
  no environment read anywhere in src but the loader (asked of Jolt's analyzer, by resolved
  host reference, never by text)."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.edn :as edn] [clojure.java.shell]
            [aero.core :as aero] [datahike.api :as d] [seon.model]
            [jolt.scheme :as scm] [jolt.analyzer :as an] [jolt.ir :as ir]
            [seon.config :as config] [seon.contract :as contract] [seon.db :as pdb] [seon.task :as task]))

(def ^:private dir (str (config/checkout) "/test/seon/config"))

(defn- read-file
  "A config file at p read with aero under profile prof, resolved and validated (or the error, thrown).
  Its #include is read relative to p (aero/relative-resolver): aero's default looks the path up as a
  classpath resource first, and from a worktree's test/ root ../../../ climbs into the main tree."
  [p prof dir] (config/resolved (aero/read-config p {:profile prof ::config/checkout dir :resolver aero/relative-resolver})))

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

;; ---- no #env without a setter ------------------------------------------------------------

(defn- env-tags
  "The names every #env tag of the config file text read (as edn tagged literals) names."
  [text]
  (->> (edn/read-string {:default tagged-literal} text)
       (tree-seq #(or (coll? %) (tagged-literal? %)) #(if (tagged-literal? %) [(:form %)] (if (map? %) (mapcat identity %) (seq %))))
       (filter #(and (tagged-literal? %) (= 'env (:tag %))))
       (map #(str (:form %)))
       distinct sort))

(def ^:private env-awaiting-their-task
  "#env tags nothing in bin/, src/, test/ or config/ sets, kept for now: the owner's switches for a
  paid model's name and for benches (docs/model.md, docs/vocabulary.md); task
  seon.config/per-run-keys-ride-the-command-line turns each into a --set and empties this set."
  #{"SEON_AI_MODEL" "SEON_INSTRUMENT"})

;; docs/config.md#layers: `#env` appears only where a script sets one
(deftest no-env-tag-names-a-variable-nothing-sets
  (let [root (config/checkout)
        names (env-tags (slurp (config/path root)))
        sources (concat (filter #(.isFile %) (file-seq (java.io.File. (str root "/bin"))))
                        (filter #(.isFile %) (file-seq (java.io.File. (str root "/src"))))
                        (filter #(and (.isFile %) (not (str/ends-with? (.getName %) "config_test.clj"))) (file-seq (java.io.File. (str root "/test"))))
                        [(java.io.File. (str root "/config/local.edn"))])
        text (apply str (for [f sources :when (.isFile f)] (slurp f)))
        set-somewhere? (fn [n] (boolean (some #(str/includes? text %) [(str n "=") (str "export " n)])))]
    (is (seq names) "the reader finds the file's #env tags")
    (is (= [] (vec (remove set-somewhere? (remove env-awaiting-their-task names))))
        "an #env tag whose variable no script, source or test sets is a hidden layer nothing uses: delete it")
    (is (= env-awaiting-their-task (set (filter (set names) env-awaiting-their-task)))
        "an exception whose tag has gone leaves the set")
    (is (every? (complement set-somewhere?) env-awaiting-their-task)
        "an exception that something now sets leaves the set")))

;; ---- the shipped file and its schemas ----------------------------------------------------

(deftest the-shipped-file-validates-under-every-profile
  ;; the scripted model's script is stated only under :scripted (checks and tests)
  (doseq [p [:default :scripted :deepseek]]
    (let [c (read-file (config/path (config/checkout)) p (config/checkout))]
      (is (= (set (keys c)) (set (remove (cond-> #{:seon.config.ai/reasoning-effort :seon.config.source/root :seon.config.source/gate-checkout :seon.config.root/profile} (not= :scripted p) (conj :seon.config.ai/script))
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
  ;; bin/test runs its processes under --profile scripted
  (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider config/config)))
  (let [p (config/provider (assoc config/config :seon.config.ai/provider :openai-compat :seon.config.ai/thinking :disabled))]
    (is (= {:kind :openai-compat :base-url "https://api.deepseek.com" :model "deepseek-flash" :api-key-env "DEEPSEEK_API_KEY"
            :max-tokens (config/value :seon.config.ai/max-tokens) :replay-reasoning (config/value :seon.config.ai/replay-reasoning) :thinking :disabled}
           p))
    ;; whether earlier reasoning goes back is the config key, obeyed as written (owner, 2026-10-24)
    (let [off (config/provider (assoc config/config :seon.config.ai/provider :openai-compat :seon.config.ai/thinking :disabled
                                      :seon.config.ai/replay-reasoning false))
          said {:messages [{:role "assistant" :content "" :reasoning "why" :tool-calls [{:id "c1" :code "(+ 1 2)"}]}] :task "t/a" :turn 1}]
      (is (false? (:replay-reasoning off)))
      (is (= "why" (get-in (seon.model/request-body p said) [:messages 0 :reasoning_content])) "on: the reasoning goes back as returned")
      (is (not (contains? (get-in (seon.model/request-body off said) [:messages 0]) :reasoning_content)) "off: the request leaves it out"))
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

;; owner, 2026-10-28: "Fix the model provider not reading the config. A config can override at any point even
;; between turns." The provider was copied once at boot (seon.root/start!); it is resolved at the call now
(deftest the-provider-is-the-config-as-it-stands-at-the-call
  (ts/test-branch! (fn [cfg conn]
    (let [c (merge config/config {:seon.config.ai/provider :none})
          tick! (fn [m] (d/transact conn {:tx-data [(assoc m :seon.root/id :root)]}))]
      (d/transact conn {:tx-data (into (task/boot-tx "boot-1" 6) (config/facts c {}))})
      (is (= {:kind :none} (config/provider-at @conn @conn nil)) "main's config at the first turn")
      (testing "a change to main's config applies at the next call, with no restart"
        (tick! {:seon.config.ai/provider :scripted :seon.config.ai/script 'seon.fixtures/reply})
        (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider-at @conn @conn nil))))
      (d/transact conn {:tx-data [{:seon.task/id "a/own" :seon.config.ai/provider :none} {:seon.task/id "a/other"}]})
      (testing "a task's own setting wins over main's, from its next turn"
        (is (= {:kind :none} (config/provider-at @conn @conn "a/own")))
        (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider-at @conn @conn "a/other"))
            "and a task that names none uses main's"))))))

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
    ;; the analyzer does find them: the loader's own two (checkout, secret); the profile is a
    ;; command-line layer (--profile), not an environment read
    (is (= #{'seon.config} (set (map first refs))))
    (is (= '#{(defn checkout) (defn secret)} (set (map second refs))))))

;; ---- layers: the shipped file, --config (a checkout's local.edn only when named), --set ---------------------------------------

(deftest the-command-line-s-overrides-are-taken-out-of-a-mode-s-args
  (let [o (config/overrides ["scale" "100" "--set" ":seon.config.web/port=7791" "--config" "x.edn" "more"])]
    (is (= ["scale" "100" "more"] (:rest o)))
    (is (= [["--set" {:seon.config.web/port 7791}] ["--config x.edn" "x.edn"]] (:layers o))))
  (is (= ["root"] (config/without-overrides ["root" "--set" ":seon.config.root/server-port=7898"]))))

;; --profile NAME is a command-line layer, recorded in the config in force: docs/config.md#layers
(deftest the-profile-is-read-from-the-command-line
  (is (= :default (config/profile [])))
  (is (= :scripted (config/profile ["root" "--profile" "scripted" "--set" ":seon.config.web/port=1"])))
  (is (= ["root"] (config/without-overrides ["--profile" "scripted" "root"])))
  (is (= :seon.config/refused (:seon.error/type (refused #(config/overrides ["--profile"])))))
  ;; with no args it answers the profile in force, even where *command-line-args* is unbound (an
  ;; eval's thread in root answered :default under --profile deepseek)
  (is (= (:seon.config.root/profile config/config) (binding [*command-line-args* nil] (config/profile))))
  (let [under #(config/layered (config/load-layers (config/checkout) %))
        none (under [])
        scripted (under ["--profile" "scripted"])
        deepseek (under ["--profile" "deepseek"])]
    ;; the profile's own values
    (is (= :none (get-in none [:config :seon.config.ai/provider])))
    (is (= :scripted (get-in scripted [:config :seon.config.ai/provider])))
    (is (= :openai-compat (get-in deepseek [:config :seon.config.ai/provider])))
    ;; recorded in the config in force, with its layer, and written as facts on main
    (is (= :default (get-in none [:config :seon.config.root/profile])))
    (is (= :scripted (get-in scripted [:config :seon.config.root/profile])))
    (is (= "--profile scripted" (get-in scripted [:sources :seon.config.root/profile])))
    (is (= "no --profile" (get-in none [:sources :seon.config.root/profile])))
    (is (= :deepseek (:seon.config.root/profile (first (config/facts (:config deepseek) (:sources deepseek))))))
    ;; a later layer still wins over the profile's value
    (is (= :none (get-in (under ["--profile" "scripted" "--set" ":seon.config.ai/provider=:none"]) [:config :seon.config.ai/provider])))))

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
    (spit local "{:seon.config.source/root \"/elsewhere/write-back\" :seon.config.web/port 4242}")
    (try
      (let [{c :config srcs :sources} (config/layered (config/load-layers co []))]
        (is (not= "/elsewhere/write-back" (:seon.config.source/root c)) "unnamed, it is no layer")
        (is (not-any? #{"config/local.edn"} (vals srcs))))
      (let [{c :config srcs :sources} (config/layered (config/load-layers co ["--config" local]))]
        (is (= "/elsewhere/write-back" (:seon.config.source/root c)) "named, it is one")
        (is (= 4242 (:seon.config.web/port c)))
        (is (= (str "--config " local) (srcs :seon.config.source/root))))
      (finally (clojure.java.shell/sh "rm" "-rf" co)))))

(deftest later-layers-win-key-by-key-and-name-their-source
  (let [ls (config/load-layers (config/checkout)
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
         (:seon.config/problems (refused #(config/layered (config/load-layers (config/checkout) ["--set" ":seon.config.web/port=-1"]))))))
  (is (= :unknown-key
         (:problem (first (:seon.config/problems (refused #(config/layered (config/load-layers (config/checkout) ["--set" ":seon.config.web/porrt=1"])))))))))

(deftest no-port-is-listened-on-unless-the-config-says-so
  (let [c (:config (config/layered [(first (config/load-layers (config/checkout) []))]))]
    (is (= 0 (:seon.config.web/port c)))
    (is (= 0 (:seon.config.root/server-port c)))))

(deftest the-loader-reads-the-command-line-as-the-process-has-it
  (binding [*command-line-args* '("ports" "--set" ":seon.config.web/port=7998")]
    (is (= ["ports" "--set" ":seon.config.web/port=7998"] (config/argv)))
    (is (= 7998 (:seon.config.web/port (:config (config/layered (config/load-layers (config/checkout) (config/argv)))))))))

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
      (let [r (ts/binary-sh "env" "-u" "SEON_WEB_PORT" "-u" "SEON_SERVER_PORT" (str root "/bin/ports") f)
            text (slurp f)]
        (is (= 0 (:exit r)) (:err r))
        (is (= "7920 7921" (str/trim (:out r))) "it boots, on the ports the file held")
        (is (str/includes? text ":seon.config.root/server-port #long #or [#env SEON_SERVER_PORT 7920]"))
        (is (str/includes? text " ;; the merges tree\n :seon.config.source/root \".worktrees/merges\"}") "every other entry is kept")
        (is (= text (do (clojure.java.shell/sh "env" "-u" "SEON_WEB_PORT" (str root "/bin/ports") f) (slurp f))) "a second run writes the same file"))
      (finally (clojure.java.io/delete-file f true)))))

(deftest bin-ports-writes-at-once-from-many-callers
  ;; bin/build, bin/test and bin/check each run `bin/ports --write` first, so several run at
  ;; once on one layer. Before, every run wrote the one name FILE.new: one run renamed or removed
  ;; it while another's mv still named it ("mv: ...local.edn.new: No such file or directory", the
  ;; last line of a-failed-seed-says-failed in the full run of 2026-10-25). Each writes its own now
  (let [root (config/checkout)
        f (str root "/target/ports-test-" (random-uuid) ".edn")
        text (str "{:seon.config.root/server-port #long #or [#env SEON_SERVER_PORT 7920]\n"
                  " :seon.config.web/port #long #or [#env SEON_WEB_PORT 7921]}\n")]
    (try
      (spit f text)
      (clojure.java.shell/sh (str root "/bin/ports") "--write" f)
      (let [written (slurp f)
            runs (mapv deref (doall (for [_ (range 16)] (future (clojure.java.shell/sh (str root "/bin/ports") "--write" f)))))]
        (is (= [] (remove #(and (= 0 (:exit %)) (= "" (:err %))) runs)) "every run writes the layer, none fails on another's file")
        (is (= written (slurp f)) "the layer is the one the first run wrote")
        (is (= [] (filter #(str/starts-with? (.getName %) (str (.getName (clojure.java.io/file f)) ".new"))
                          (.listFiles (clojure.java.io/file root "target"))))
            "no run leaves its file"))
      (finally (clojure.java.io/delete-file f true)))))


(deftest thinking-on-refuses-reasoning-left-out
  ;; owner ruling 2026-10-24 (docs/decisions.md, "A config key does what it says"): with
  ;; thinking :enabled and tools, DeepSeek answers 400 when earlier reasoning_content is left
  ;; out (measured live, docs/model.md), so that config is refused at load, naming
  ;; each model and explained by m/explain; thinking off may leave it out
  (let [r (refused #(config/resolved (assoc config/config :seon.config.ai/thinking :enabled :seon.config.ai/replay-reasoning false)))]
    (is (= :seon.config/refused (:seon.error/type r)) (pr-str r))
    (is (= (set (for [id (keys (:seon.config.ai/models config/config))] [:seon.config.ai/models id])) (set (map :key (:seon.config/problems r)))))
    (is (str/includes? (:value (first (:seon.config/problems r))) "replay-reasoning") "explained"))
  (is (false? (:seon.config.ai/replay-reasoning (config/resolved (assoc config/config :seon.config.ai/thinking :disabled :seon.config.ai/replay-reasoning false))))
      "thinking off: nothing to send, left out is valid")
  (is (true? (:seon.config.ai/replay-reasoning (config/resolved (assoc config/config :seon.config.ai/thinking :enabled)))) "the default replays"))

(deftest a-worktree-commits-its-merges-on-a-branch-of-its-own
  ;; worktrees share refs: a root in one that wrote jolt/seon-merges would move the main tree's merges branch
  ;; (task seon.root/root-repairs-a-failed-merge-from-its-repl, gap 1). The config names it from the
  ;; checkout (#seon/own-branch), not an env var (audit cut 11)
  (let [root (config/checkout)
        git (fn [& args] (str/trim (:out (apply clojure.java.shell/sh "git" "-C" root args))))
        worktree? (not= (git "rev-parse" "--git-dir") (git "rev-parse" "--git-common-dir"))
        own (fn [dir] (let [f (java.io.File/createTempFile "own-branch" ".edn")]
                        (spit f "{:b #seon/own-branch \"jolt/seon-merges\"}")
                        (try (:b (aero/read-config (str f) {:seon.config/checkout dir})) (finally (.delete f)))))
        main-tree (str/replace (git "rev-parse" "--path-format=absolute" "--git-common-dir") #"/\.git$" "")]
    (is (= (if worktree? (str "jolt/seon-merges-" (.getName (java.io.File. ^String root))) "jolt/seon-merges")
           (:seon.config.source/branch config/config))
        "this checkout's: a worktree's own")
    (is (= "jolt/seon-merges" (own main-tree)) "the main tree's is the shipped name")
    (let [wt (doto (java.io.File. (str (System/getProperty "java.io.tmpdir") "/own-branch-" (System/nanoTime) "/x")) .mkdirs)]
      (spit (java.io.File. wt ".git") (str "gitdir: " main-tree "/.git/worktrees/x\n"))
      (is (= "jolt/seon-merges-x" (own (str wt))) "a worktree's, by its .git file")
      (.delete (java.io.File. wt ".git")) (.delete wt) (.delete (.getParentFile wt)))))
