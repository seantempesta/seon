(ns seon.config-test
  "The one config system: aero's tags read under Jolt (one deftest each), the shipped file
  validating against its schemas under each profile, a refused boot naming every unknown,
  invalid or missing key, the config written as facts on main and read back from the db, and
  no environment read anywhere in src but the loader (asked of Jolt's analyzer, by resolved
  host reference, never by text)."
  (:require [seon.lib] [seon.test-store :as ts] [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io] [clojure.string :as str] [clojure.edn :as edn] [clojure.java.shell]
            [aero.core :as aero] [datahike.api :as d] [seon.model]
            [test-runner :as runner]
            [seon.config :as config] [malli.core :as m] [seon.contract :as contract] [seon.db :as pdb] [seon.task :as task] [seon.source :as source] [my.test :as mt]))

(def ^:private dir (str (config/checkout) "/test/seon/config"))

(defn- read-file
  "A config file at p read with aero under profile prof, over the schema's defaults (layered, every config's one merge),
  resolved and validated (or the error, thrown).
  Its #include is read relative to p (aero/relative-resolver): aero's default looks the path up as a
  classpath resource first, and from a worktree's test/ root ../../../ climbs into the main tree."
  [p prof dir] (:config (config/layered [["file" (aero/read-config p {:profile prof ::config/checkout dir
                                     ;; a fixture's #include of the shipped file reads what this branch states, never the disk
                                     :resolver (fn [source include]
                                                 (if (clojure.string/ends-with? (str include) "default.edn")
                                                   (java.io.StringReader. (config/shipped-text))
                                                   (aero/relative-resolver source include)))})]])))

(defn- shipped
  "The shipped config file read under profile p (resolved and validated), as this process's branch states it, never the disk (one agent's unmerged write stays its own) and never the running config (a root's overrides: its tasks directory, ports and provider)."
  [p] (read-file (java.io.StringReader. (config/shipped-text)) p (config/checkout)))

(defn- tags
  "test/seon/config/tags.edn read with aero under profile p (default :default)."
  ([] (tags :default))
  ([p] (aero/read-config (str dir "/tags.edn") {:profile p ::config/checkout (config/checkout)})))

;; ---- aero under Jolt, tag by tag ---------------------------------------------------------

;; tags.edn reads PATH, a variable every process has (JOLT_PWD is bin/test's own; an agent's test child has none)
;; docs/config.md: which layer a key came from differs by process while a digest takes no place, so a process doesn't hand it to a reader: a test reading it would be green in one process and red in another at one digest
(deftest the-config-in-force-names-no-layer
  (is (= #{:config} (set (keys (config/in-force)))))
  (is (map? (config/boot-sources)) "root's boot takes them, to write on main"))

(deftest env-reads-a-set-variable-and-nil-for-an-unset-one
  (is (= (System/getenv "PATH") (:env (tags))))
  (is (nil? (:env-unset (tags)))))

(deftest envf-formats-variables
  (is (= (str (System/getenv "PATH") "/x") (:envf (tags)))))

(deftest or-takes-the-first-non-nil
  (is (= 7 (:or-unset (tags))))
  (is (= (System/getenv "PATH") (:or-set (tags)))))

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

;; docs/config.md#the-file: #seon/cores is the machine's core count, read when the config is read
(deftest seon-cores-reads-a-positive-count
  (is (pos-int? (:cores (tags))))
  (is (= (:cores (tags)) (config/value :seon.config.machine/cores))))

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
(deftest entries-are-a-config-over-this-processs-layers
  ;; docs/config.md#a-process-holds-its-branchs-config: what a branch's entries state is what a process reads: tags read as aero reads them, a place stays where the process loaded, and a key needs its schema
  (let [es (into {} (for [{k :config/key f :config/form} (source/config-tx config/shipped-file config/built-text) :when k] [k f]))
        k :seon.config.root/view-window
        over (fn [m] (config/of-entries (merge es m) {}))]
    (is (= (config/value :seon.config.root/max-agents) (get-in (over {}) [:config :seon.config.root/max-agents])))
    (is (= 21 (get-in (over {k "21"}) [:config k])) "an entry's value is read")
    (is (= (get-in (over {:seon.config.root/max-agents "4"}) [:config :seon.config.root/writer-buf]) 4) "a tag in an entry resolves, here #ref over the entry it names")
    (let [p (over {:seon.config.root/main "\"elsewhere\""})]
      (is (= (:seon.config.root/main config/config) (get-in p [:config :seon.config.root/main])) "a place stays where this process loaded")
      (is (= "elsewhere" (get-in p [:waiting :seon.config.root/main :stated])) "and says so"))
    (is (= 1234567 (get-in (over {:seon.config.agent/sock-buf-bytes "1234567"}) [:waiting :seon.config.agent/sock-buf-bytes :stated])) "a key read at load says it waits for a rebuild")
    (is (= :unknown-key (:problem (first (:seon.config/problems (over {:seon.config.nope/key "1"}))))))
    (is (= 1 (get-in (config/of-entries (assoc es :seon.config.nope/key "1")
                                        {:seon.config/config (conj (get config/schema-forms :seon.config/config) [:seon.config.nope/key :int])})
                     [:config :seon.config.nope/key]))
        "a key this branch's schema allows reads")
    (is (= (config/in-force) (do (mt/with (mt/world) [{:config/key k :config/form "21" :config/file config/shipped-file}]) (config/in-force)))
        "a db around, a test's world, changes nothing a key reads")))

(deftest no-env-tag-names-a-variable-nothing-sets
  (let [root (config/checkout)
        names (env-tags (config/shipped-text))
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
  ;; the scripted model's script is stated only under :scripted (checks and tests); :seon.config.source/root
  ;; is `#env SEON_SOURCE_ROOT` (bin/system exports it, bin/test does not): present or absent by the process's
  ;; environment, so it is left out of both sides; max-cores and max-load are optional bounds shipped unset
  ;; (docs/merging.md#the-test-service)
  (doseq [p [:default :scripted :deepseek]]
    (let [c (dissoc (shipped p) :seon.config.source/root)]
      ;; every declared key is shipped; a key no schema declares is already refused by reading it (a branch's own key is declared by its schema: seon.config/of-entries)
      (is (= [] (remove (set (keys c)) (remove (cond-> #{:seon.config.ai/reasoning-effort :seon.config.ai/temperature :seon.config.source/root :seon.config.source/gate-checkout :seon.config.root/profile :seon.config.proofs/max-cores :seon.config.proofs/max-load} (not= :scripted p) (conj :seon.config.ai/script))
                                               (config/keys-declared)))) p)))
  ;; no profile runs on the provider the shipped file states as its default, read through the resolver, never a literal here (owner
  ;; 2026-11-15: tests don't hardcode defaults; the default itself is docs/decisions.md's, DeepSeek)
  (is (= (:seon.config.ai/provider (shipped :default)) (:kind (config/provider (shipped :default)))) "no profile: the shipped default provider")
  (is (not= :scripted (:kind (config/provider (shipped :default)))) "never the scripted model")
  (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider (shipped :scripted))))
  (is (= :openai-compat (:seon.config.ai/provider (shipped :deepseek)))))

(deftest every-key-is-documented-by-its-schema
  (doseq [k (config/keys-declared)
          :let [f (get config/schema-forms k)
                props (some #(when (map? %) %) (take 2 (rest f)))]]
    (is (string? (:description props)) (str k " has no :description"))
    (is (string? (:seon.config/unit props)) (str k " has no :seon.config/unit"))))

;; docs/merging.md#a-result-is-a-function-of-its-digest: 231 of 1,469 digests differed between a root and a
;; checkout on one tree, all by :seon.config.vector/texts, 64 in a root and 0 under bin/test
(deftest what-bin-test-sets-for-itself-moves-no-digest
  (let [checkout (config/checkout)
        set-by-test (set (map second (re-seq #"(?m)^export (SEON_\w+)=\$\{\1:-" (slurp (str checkout "/bin/test")))))
        read-by (into {} (for [[_ k v] (re-seq #"(?m)^ *:(seon\.config[\w./-]+) [^\n]*#env (SEON_\w+)" (config/shipped-text))] [v (keyword k)]))
        keys-set (keep read-by set-by-test)]
    (is (= #{:seon.config.web/port :seon.config.root/wiki-baseline :seon.config.vector/texts} (set keys-set)))
    (doseq [k keys-set]
      (is (get-in config/schema-forms [k 1 :seon.config/place]) (str k " is set by bin/test, so a root and a test run differ in it: it must be :seon.config/place")))))

(deftest machine-dependent-values-are-derived-at-load
  (let [c (shipped :scripted)]
    ;; the task files' place is the environment's when it names one (bin/check's SEON_TASKS: this test also runs inside a
    ;; check step, as an agent's), else derived from the checkout: the file's last alternative, read from its text
    (let [f (get (edn/read-string {:default tagged-literal} (config/shipped-text)) :seon.config.root/tasks-dir)]
      (is (= 'or (:tag f)))
      (is (= (tagged-literal 'seon/checkout "resources/seon/tasks") (last (:form f)))))
    (is (= (str (config/checkout) "/resources/seon/system.edn") (:seon.config.root/system-path c)))
    ;; the writer's buffer is the live agents' bound, by #ref
    (is (= (:seon.config.root/max-agents c) (:seon.config.root/writer-buf c)))))

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
  ;; a key the config may not lack, resolving nil (an unset #env), refuses the config as it is read: at boot, naming it,
  ;; never at its first read mid-run (seon.config's closed; the schema's keys stay optional for a task's single overrides)
  (let [r (refused #(config/resolved (assoc config/config :seon.config.store/branching-factor nil)))]
    (is (= [{:key :seon.config.store/branching-factor :problem :missing-key :value "nil"}] (:seon.config/problems r))))
  ;; a key resolving nil is absent, not a stored nil
  (is (not (contains? (config/resolved (assoc config/config :seon.config.ai/thinking nil)) :seon.config.ai/thinking)))
  (is (= :disabled (:seon.config.ai/thinking (config/resolved (assoc config/config :seon.config.ai/thinking :disabled))))))

(deftest a-key-the-config-lacks-is-refused-not-nil
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.ai/temperature)))))
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.nope/x))))))

(deftest the-provider-is-the-config-s
  ;; the shipped file under --profile scripted (what bin/test runs under), not the running process's
  (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider (shipped :scripted))))
  (let [p (config/provider (assoc config/config :seon.config.ai/provider :openai-compat :seon.config.ai/thinking :disabled))]
    (is (= {:kind :openai-compat :base-url (:value (config/resolve :seon.config.ai/base-url)) :model (config/value :seon.config.ai/model) :api-key-env (:value (config/resolve :seon.config.ai/api-key-variable))
            :max-tokens (config/value :seon.config.ai/max-tokens) :replay-reasoning (config/value :seon.config.ai/replay-reasoning) :thinking :disabled
            :reasoning-effort (config/value :seon.config.ai/reasoning-effort)}
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
    (d/transact conn {:tx-data (into (task/boot-tx "boot-1" "sean") (seon.task/config-tx c {:seon.config.web/port "--set"}))})
    (is (= c (config/from-db @conn)))
    (is (= {:seon.config.web/port "--set"} (d/q '[:find ?s . :where [?e :seon.system/id :root] [?e :seon.config/sources ?s]] @conn)))
    ;; the reconciler's root read sees the same config keys
    (is (= {:boot "boot-1" :max-running 6 :max-merging (config/value :seon.config.merge/max-running)} (task/root @conn)))
    ;; a branch made from main holds them too (what an agent forked onto it reads)
    (d/branch! conn (:branch cfg) child)
    (is (= c (config/from-db @(pdb/connect (assoc cfg :branch child)))))))))

;; owner, 2026-11-09 (stage 3, docs/users.md#the-stages-agreed-2026-11-09): the config in force is an entity of its own, so root's
;; entity is root, not the config by the best match of its attributes (the entity index's :entity-schemas)
(deftest the-config-in-force-is-its-own-entity
  (ts/test-branch! (fn [cfg conn]
    (d/transact conn {:tx-data (into (task/boot-tx "boot-1" "sean") (seon.task/config-tx config/config {}))})
    (let [ix (pdb/index)
          best (fn [m] (key (apply max-key val (frequencies (keep (:entity-schemas ix) (keys m))))))
          root (d/entity @conn [:seon.system/id :root])]
      (is (= :seon.system/root-fact (best (d/pull @conn '[*] [:seon.system/id :root]))) "root's entity reads as root")
      (let [c (:seon.system/config root)]
        (is (some? c) "root refs the config in force")
        (is (= :seon.config/config-fact (get (:identity-schemas ix) :seon.config/id)) "named by the config's own identity")
        (is (pdb/extends? ix :seon.config/config-fact :seon.config/config) "an entity schema holding the config's attributes")
        (is (= (:seon.config.web/port config/config) (:seon.config.web/port c)) "holding its keys")
        (is (nil? (:seon.config.web/port root)) "which root's entity does not")
        (is (= config/config (config/from-db @conn)) "read back whole through the ref"))))))

(deftest the-same-config-on-two-branches-is-one-entity
  (ts/test-branch! (fn [cfg conn]
    (let [a (keyword (str (name (:branch cfg)) "-a")) b (keyword (str (name (:branch cfg)) "-b"))
          _ (do (d/branch! conn (:branch cfg) a) (d/branch! conn (:branch cfg) b))
          on (fn [br] (let [c (pdb/connect (assoc cfg :branch br))]
                        (d/transact c {:tx-data (seon.task/config-tx config/config {})})
                        (:db/id (:seon.system/config (d/entity @c [:seon.system/id :root])))))
          [ea eb] [(on a) (on b)]]
      (is (some? ea))
      (is (= ea eb) "one entity on every branch")))))

;; owner, 2026-10-28: "Fix the model provider not reading the config. A config can override at any point even
;; between turns." The provider was copied once at boot (seon.system/start!); it is resolved at the call now
(deftest the-provider-is-the-config-as-it-stands-at-the-call
  (ts/test-branch! (fn [cfg conn]
    (let [c (merge config/config {:seon.config.ai/provider :none})
          tick! (fn [m] (d/transact conn {:tx-data (seon.task/config-tx (merge c m) {})}))]
      (d/transact conn {:tx-data (into (task/boot-tx "boot-1" "sean") (seon.task/config-tx c {}))})
      (is (= {:kind :none} (config/provider-at @conn @conn nil)) "main's config at the first turn")
      (testing "a change to main's config applies at the next call, with no restart"
        (tick! {:seon.config.ai/provider :scripted :seon.config.ai/script 'seon.fixtures/reply})
        (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider-at @conn @conn nil))))
      (d/transact conn {:tx-data [{:seon.task/id "a/own" :seon.task/ns (quote a) :seon.task/name "own" :seon.task/plan "p" :seon.task/execution :held :seon.task/branch :task-a-own :seon.task/kind :work :seon.config.ai/provider :none} {:seon.task/id "a/other" :seon.task/ns (quote a) :seon.task/name "other" :seon.task/plan "p" :seon.task/execution :held :seon.task/branch :task-a-other :seon.task/kind :work}]})
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
      (d/transact conn {:tx-data (into (task/boot-tx "boot-1" "sean") (seon.task/config-tx c {}))})
      (is (= vs (:seon.config.source/process-vars (config/from-db @conn))))
      (is (set? (:seon.config.source/process-vars (config/from-db @conn))))
      (is (= c (config/from-db @conn)) "and the whole config is its db copy")))))

;; ---- no environment read outside the loader -------------------------------------------------

(deftest no-environment-read-in-src-but-the-loader
  ;; the analyzer's references of each src form (:form/refs: a host call is Class/member) as the program holds them, so
  ;; no file is analyzed again here (that was the test's work, every src form): the forms naming System/getenv
  (let [db (runner/program)
        getenv? #(and (= "getenv" (name %)) (#{"System" "java.lang.System"} (namespace %)))
        calling (into #{} (keep #(when (getenv? (:v %)) (:e %))) (d/datoms db :aevt :form/refs))
        refs (for [[nsn src] (d/q '[:find ?nsn ?src :in $ [?f ...] :where [?f :form/src ?src] [?f :form/ns ?ns] [?ns :ns/name ?nsn]
                                    [?ns :ns/file ?file] [(clojure.string/starts-with? ?file "src/")]] db (vec calling))]
               [nsn (take 2 (binding [*read-eval* false *ns* (the-ns nsn)] (read-string {:read-cond :allow :features #{:clj}} src)))])]
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
    (is (= (:seon.config.ai/provider (shipped :default)) (get-in none [:config :seon.config.ai/provider])) "no profile: the shipped default, as the resolver reads it")
    (is (= :none (get-in (under ["--set" ":seon.config.ai/provider=:none"]) [:config :seon.config.ai/provider])) ":none only when set explicitly")
    (is (= :scripted (get-in scripted [:config :seon.config.ai/provider])))
    (is (= :openai-compat (get-in deepseek [:config :seon.config.ai/provider])))
    ;; recorded in the config in force, with its layer, and written as facts on main
    (is (= :default (get-in none [:config :seon.config.root/profile])))
    (is (= :scripted (get-in scripted [:config :seon.config.root/profile])))
    (is (= "--profile scripted" (get-in scripted [:sources :seon.config.root/profile])))
    (is (= "no --profile" (get-in none [:sources :seon.config.root/profile])))
    (is (= :deepseek (:seon.config.root/profile (some #(when (:seon.config/id %) %) (seon.task/config-tx (:config deepseek) (:sources deepseek))))))
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
    (spit (str co "/resources/seon/config/default.edn") (config/shipped-text))
    (spit local "{:seon.config.source/root \"/elsewhere/write-back\" :seon.config.web/port 4242}")
    (try
      (let [{c :config srcs :sources} (config/layered (config/load-layers co []))]
        (is (not= "/elsewhere/write-back" (:seon.config.source/root c)) "unnamed, it is no layer")
        (is (not-any? #{"config/local.edn"} (vals srcs))))
      (let [{c :config srcs :sources} (config/layered (config/load-layers co ["--config" local]))]
        (is (= "/elsewhere/write-back" (:seon.config.source/root c)) "named, it is one")
        (is (= 4242 (:seon.config.web/port c)))
        (is (= (str "--config " local) (srcs :seon.config.source/root))))
      (finally (seon.lib/remove-tree! co)))))

(deftest later-layers-win-key-by-key-and-name-their-source
  (let [ls (config/load-layers (config/checkout)
                               ["--config" (str dir "/local-ports.edn") "--set" ":seon.config.web/port=7999"])
        {c :config srcs :sources} (config/layered ls)]
    (is (= 7910 (:seon.config.root/server-port c)))
    (is (= 7999 (:seon.config.web/port c)))
    (is (= (str "--config " dir "/local-ports.edn") (srcs :seon.config.root/server-port)))
    (is (= "--set" (srcs :seon.config.web/port)))
    (is (= "resources/seon/config/default.edn" (srcs :seon.config.root/max-agents)))
    (is (= "schema" (srcs :seon.config.root/view-window)) "a key no layer states is its schema's default, and says so")
    (is (= (set (keys c)) (set (keys srcs))))))

(deftest an-override-is-validated-by-the-same-schemas
  ;; -1 is a port the kernel assigns; -2 is no port
  (is (= [{:key :seon.config.web/port :problem :invalid :value "-2"}]
         (:seon.config/problems (refused #(config/layered (config/load-layers (config/checkout) ["--set" ":seon.config.web/port=-2"]))))))
  (is (= :unknown-key
         (:problem (first (:seon.config/problems (refused #(config/layered (config/load-layers (config/checkout) ["--set" ":seon.config.web/porrt=1"])))))))))

(defn- shipped-default
  "The value the shipped file states for key k when its #env variables are unset: the last alternative of
  its `#or`, read from the file's text (edn tagged literals, as env-tags does), since the resolved value
  is the process's environment's (a root's SEON_SERVER_PORT) when one is set."
  [k]
  (let [f (get (edn/read-string {:default tagged-literal} (config/shipped-text)) k)
        alts (-> f :form :form)]
    (assert (= 'long (:tag f)))
    (assert (= 'or (:tag (:form f))))
    (last alts)))

(deftest no-port-is-listened-on-unless-the-config-says-so
  (is (= 0 (shipped-default :seon.config.web/port)))
  (is (= 0 (shipped-default :seon.config.root/server-port))))

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
    (is (= {:value (config/value :seon.config.ai/max-in-flight) :source [:config "--set"]}
           (config/resolve-in config/config {:seon.config.ai/max-in-flight "--set"} :seon.config.ai/max-in-flight {}))))
  (is (= {:value (get-in config/config [:seon.config.ai/providers "deepseek" :seon.config.ai/base-url]) :source [:provider "deepseek"]}
           (config/resolve :seon.config.ai/base-url)) "the provider's own entry answers, and says so")
  (is (= [:config "resources/seon/config/default.edn"] (:source (config/resolve :seon.config.root/max-agents)))))

(deftest a-setting-set-nowhere-is-refused-not-nil
  (is (= :seon.config/refused (:seon.error/type (refused #(config/resolve :seon.config.ai/temperature {:model "deepseek-flash"})))))
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.nope/x))))))

(deftest the-manifest-is-validated-and-written-as-entities-on-main
  (is (= #{"deepseek-flash" "deepseek-v4-pro"} (set (keys (:seon.config.ai/models config/config)))))
  (is (= :invalid (:problem (first (:seon.config/problems
                                    (refused #(config/resolved (assoc-in config/config [:seon.config.ai/models "deepseek-flash" :seon.ai.model/input-usd-per-mtok] "cheap"))))))))
  (ts/test-branch! (fn [cfg conn]
    (d/transact conn {:tx-data (seon.task/config-tx config/config config/sources)})
    (is (= 0.15 (d/q '[:find ?p . :where [?m :seon.ai.model/id "deepseek-flash"] [?m :seon.ai.model/input-usd-per-mtok ?p]] @conn)))
    (is (= (get-in config/config [:seon.config.ai/providers "deepseek" :seon.config.ai/api-key-variable]) (d/q '[:find ?v . :where [?p :seon.ai.model/provider-id "deepseek"] [?p :seon.config.ai/api-key-variable ?v]] @conn)))
    (is (= config/config (config/from-db @conn))))))

(deftest the-schema-forms-are-baked-once
  (testing "seon.contract's forms are seon.config's one baked map, not a second read of the directory"
    (is (identical? contract/forms config/schema-forms)))
  (testing "it holds every schema file's forms: the config keys' and the platform's"
    (is (contains? config/schema-forms :seon.config/config))
    (is (contains? config/schema-forms :seon.task/id))
    (is (every? #(contains? config/schema-forms %) (config/keys-declared)))))

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
  ;; (task seon.system/root-repairs-a-failed-merge-from-its-repl, gap 1). The config names it from the
  ;; checkout (#seon/own-branch), not an env var (audit cut 11). Two fabricated trees in the temp dir, never the real checkout:
  ;; a main tree (a .git directory) and a worktree (a .git file naming the main tree's worktrees/x)
  ;; Estimate: three small file writes and three config reads ~200k ticks
  (let [base (str (System/getProperty "java.io.tmpdir") "/seon-own-branch-" (random-uuid))
        own (fn [dir] (let [f (str base "/own.edn")]
                        (spit f "{:b #seon/own-branch \"jolt/seon-merges\"}")
                        (:b (aero/read-config f {:seon.config/checkout dir}))))
        main-tree (str base "/main") wt (str base "/x")]
    (try
      (.mkdirs (java.io.File. (str main-tree "/.git")))
      (.mkdirs (java.io.File. wt))
      (spit (java.io.File. wt ".git") (str "gitdir: " main-tree "/.git/worktrees/x\n"))
      (is (= "jolt/seon-merges" (own main-tree)) "the main tree's is the shipped name")
      (is (= "jolt/seon-merges-x" (own wt)) "a worktree's, by its .git file")
      (finally (seon.lib/remove-tree! base)))))

;; docs/config.md#a-process-holds-its-branchs-config: a branch's config file is checked against the schema the same change
;; states: a key its schema file adds is read, never refused by this process's older schema (Tests' prove of a branch that
;; changed both, through the system, was skipped: 2026-11-12). Red before: text-refusal took no schema changes
(deftest a-config-file-is-checked-against-the-schema-its-change-states
  ;; the file and the config map as this process's branch states them (the binary's forms, the branch's held changes over them),
  ;; never the disk's file beside the binary's map: an older binary's map requires a key the branch's file no longer states
  (let [text (config/shipped-text)
        with-key (clojure.string/replace-first text "{" "{:seon.config.zz/new-key 3\n ")
        form (get (merge config/schema-forms (:changes @@#'seon.config/held)) :seon.config/config)
        changes {:seon.config/config (conj form [:seon.config.zz/new-key {:optional true} :seon.config.zz/new-key])
                 :seon.config.zz/new-key [:int {:description "A key a branch adds."}]}]
    (is (string? (config/text-refusal with-key)) "this process's schema has no such key")
    (is (nil? (config/text-refusal with-key changes)) (str "the change's own schema has it: " (config/text-refusal with-key changes)))
    (is (nil? (seon.fx/unreadable config/shipped-file with-key changes)) "and the file reads as its reader reads it")))

;; docs/config.md#a-process-holds-its-branchs-config: a test child of the service is a fork of root's agent and held root's
;; config (--profile deepseek, local.edn's source root and branch), so its tests ran under it: runtime's prove read main's
;; "jolt/merges" and the armed-call unit measured 6.1 ticks, 15.1 under scripted. Cost: the shipped file read and resolved
;; twice (layered: aero, then the whole config validated against its schema), ~15M ticks each, and held twice = ~40M
;; (was 10M at 3M a resolve: measured 39M, the resolve's validation of every key was the work it missed).
(deftest ^{:seon.test/estimate {:ticks 40000000}} a-test-process-of-a-checkout-holds-its-config-not-its-parents
  (let [held @#'seon.config/held h @held c config/config s config/sources layers0 @#'seon.config/built-layers
        parent (config/layered (config/load-layers (config/checkout) ["--profile" "deepseek" "--set" ":seon.config.source/branch=\"jolt/merges\""]))]
    (try
      ;; the parent: root's profile and a layer of its own, held and as loaded
      (swap! held merge (select-keys parent [:config :sources]))
      (alter-var-root #'config/config (constantly (:config parent)))
      (alter-var-root #'config/sources (constantly (:sources parent)))
      (config/hold-args! ["--profile" "scripted"] nil {})
      (is (= [:scripted :scripted] [(config/value :seon.config.root/profile) (config/profile)])
          "the profile it reads either way is the test one, not its parent's")
      (is (= (:seon.config.source/branch config/test-system-config) (:seon.config.source/branch config/config) (config/value :seon.config.source/branch))
          "and no layer of its parent's is left")
      (finally (reset! held h) (alter-var-root #'config/config (constantly c)) (alter-var-root #'config/sources (constantly s))
               (alter-var-root #'seon.config/built-layers (constantly layers0))))))


;; docs/config.md#a-process-holds-its-branchs-config: an import's config file is checked against its branch's schema with the change's own
;; entries over it, never the binary's with the change's: seon_done of a checkout that touched a schema file was refused "max-behind
;; missing-key" (data, 2026-11-16), the binary still requiring a key the branch had deleted. Estimate: two config-map compiles ~10M each
;; and one resolve of the shipped file ~15M ~ 40,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 40000000}} a-config-file-is-checked-against-its-branchs-schema-with-the-changes-over-it
  (let [held @#'seon.config/held h @held
        ;; a key the file states (a key with a schema :default is never missing: seon.config/defaults supplies it)
        k :seon.config.root/main
        ;; the config map as this process's branch states it (the binary's forms, the branch's held changes over them), and its file
        form (get (merge config/schema-forms (:changes h)) :seon.config/config)
        ;; the branch's: its config map no longer declares k, and its file no longer states it
        branch (assoc (:changes h) :seon.config/config (into [] (remove #(and (vector? %) (= k (first %)))) form))
        text (clojure.string/replace (config/shipped-text) (re-pattern (str "(?m)^\\s*" k " .*$")) "")
        ;; the change's own schema entry, beside the branch's
        change {:seon.config.zz/unread [:int {:description "An entry no key reads."}]}]
    (try
      (swap! held assoc :changes branch)
      (is (nil? (config/text-refusal text change)) (str "the branch's schema with the change's over it: " (config/text-refusal text change)))
      (is (string? (config/text-refusal (str text) (assoc change :seon.config/config form))) "a change that states k again requires it")
      (finally (reset! held h)))))

;; Estimate: the shipped file resolved and held twice ~40M (the test above) + two config-map compiles ~10M each ~ 60,000,000 ticks
(deftest ^{:seon.test/estimate {:ticks 60000000}} a-process-holds-its-branchs-config-when-its-binary-states-a-key-the-branch-deleted
  ;; docs/config.md#a-process-holds-its-branchs-config: root's binary, built before a land deleted :seon.config.merge/max-behind
  ;; from its schema and file, ships the key; every test child on main held that shipped text to main's schema first and died
  ;; "max-behind unknown-key" (root, 2026-11-16, result/de554c4c). A value is held to the schema of its own source: the binary's
  ;; text to the binary's schema, the branch's entries to the branch's. Cost: as the test above (~40M), plus two compiles of the
  ;; config map (~10M each): ~60M ticks
  (let [held @#'seon.config/held h @held c config/config s config/sources layers0 @#'seon.config/built-layers
        text0 config/built-text schema0 config/config-schema
        k :seon.config.zz/gone
        es (into {} (for [{ek :config/key f :config/form} (source/config-tx config/shipped-file text0) :when ek] [ek f]))
        form (get config/schema-forms :seon.config/config)
        ;; the binary's: its shipped text and its compiled schema both state k
        binary-schema (#'seon.config/closed (malli.core/deref (malli.core/schema :seon.config/config
                        {:registry (malli.registry/composite-registry (malli.core/default-schemas)
                                    (malli.registry/simple-registry (assoc config/schema-forms :seon.config/config (conj form [k {:optional true} :int]))))})))
        ;; the branch's: its schema entries differ from the binary's, and none declares k
        changes {:seon.config.zz/unread [:int {:description "An entry no key reads."}]}]
    (try
      (alter-var-root #'config/built-text (constantly (clojure.string/replace-first text0 "{" (str "{" k " 3\n "))))
      (alter-var-root #'config/config-schema (constantly binary-schema))
      (is (nil? (config/hold-args! ["--profile" "scripted"] es changes)) "the branch's entries hold under the branch's schema")
      (is (not (contains? config/config k)) "and the key the branch deleted is not in the config it holds")
      (is (= :scripted (config/value :seon.config.root/profile)) "its own layers over them")
      (finally (reset! held h) (alter-var-root #'config/config (constantly c)) (alter-var-root #'config/sources (constantly s))
               (alter-var-root #'seon.config/built-layers (constantly layers0))
               (alter-var-root #'config/built-text (constantly text0)) (alter-var-root #'config/config-schema (constantly schema0))))))

;; Estimate: three layered resolutions of the shipped file, ~3M ticks each; ~10M ticks
(deftest ^{:seon.test/estimate {:ticks 10000000}} a-config-with-a-source-root-gates-on-it
  ;; :seon.config.source/gate-checkout absent is the source root as it resolves, whatever layer names the root (the checkout's
  ;; local.edn for the standing root). Derived once, in the one merge of the layers (config/layered), so every config this
  ;; process holds has it: boot's own assoc lost it at 14:02 on 2026-11-15, when a landing moved a config entry and the writer
  ;; wrote main's config again from the held config (seon.config/moved), so every process-test task ended :closed
  (let [co (config/checkout)
        base (config/load-layers co [])
        at (fn [& layers] (config/layered (into base layers)))
        {c :config srcs :sources} (at ["config/local.edn" {:seon.config.source/root "/merges"}])]
    (is (= "/merges" (:seon.config.source/gate-checkout c)) "absent: the source root")
    (is (= "config/local.edn" (get srcs :seon.config.source/gate-checkout)) "its layer is the root's")
    (is (= "/gate" (:seon.config.source/gate-checkout
                    (:config (at ["config/local.edn" {:seon.config.source/root "/merges" :seon.config.source/gate-checkout "/gate"}]))))
        "a stated checkout wins")
    (is (not (contains? (:config (at ["no root" {:seon.config.source/root nil}])) :seon.config.source/gate-checkout))
        "no source root: none, and a merge reaching process tests is held saying so")))

;; Estimate: two small map schemas compiled (~0.2M), two layered resolutions of 2-3 keys (~0.1M each): ~0.5M ticks; 2,000,000
(deftest ^{:seon.test/estimate {:ticks 2000000}} a-key-no-layer-states-resolves-to-its-schemas-default
  ;; the owner, 2026-11-16: config defaults live in the schema; task seon.config/config-is-schema-defaults-and-datoms. A key
  ;; whose schema entry states :default and that no layer states resolves to that default, its source "schema"; a layer that
  ;; states it wins; a key with no default and no layer stays absent. Red before: layered merged the layers alone
  (let [schema (m/schema [:map [:t.cfg/size {:optional true} [:int {:default 5}]]
                               [:t.cfg/name {:optional true} [:string {:default "seon"}]]
                               [:t.cfg/bare {:optional true} :int]])
        {:keys [config sources]} (config/layered [["a layer" {:t.cfg/name "mine"}]] schema)]
    (is (= 5 (:t.cfg/size config)) "the schema's default where no layer states it")
    (is (= "schema" (get sources :t.cfg/size)) "named as the schema's")
    (is (= "mine" (:t.cfg/name config)) "a layer that states a key wins over its default")
    (is (= "a layer" (get sources :t.cfg/name)))
    (is (not (contains? config :t.cfg/bare)) "no default and no layer: absent")
    (is (= {:t.cfg/size 5 :t.cfg/name "seon"} (config/defaults schema)) "defaults reads each entry's :default")))

