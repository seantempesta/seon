(ns seon.config-test
  "The one config system: aero's tags read under Jolt (one deftest each), the shipped file
  validating against its schemas under each profile, a refused boot naming every unknown,
  invalid or missing key, the config written as facts on main and read back from the db, and
  no environment read anywhere in src but the loader (asked of Jolt's analyzer, by resolved
  host reference, never by text)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io] [clojure.string :as str]
            [aero.core :as aero] [datahike.api :as d]
            [jolt.scheme :as scm] [jolt.analyzer :as an] [jolt.ir :as ir]
            [seon.config :as config] [seon.db :as pdb] [seon.task :as task]))

(def ^:private dir (str (config/checkout) "/test/seon/config"))

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
  (doseq [p [:default :deepseek]]
    (let [c (config/read-file (config/path (config/checkout)) p (config/checkout))]
      (is (= (set (keys c)) (set (remove #{:seon.config.ai/thinking :seon.config.ai/reasoning-effort :seon.config.root/main} (config/keys-declared)))) p)))
  (is (= :scripted (:seon.config.ai/provider (config/read-file (config/path (config/checkout)) :default (config/checkout)))))
  (is (= :openai-compat (:seon.config.ai/provider (config/read-file (config/path (config/checkout)) :deepseek (config/checkout))))))

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
  (let [r (refused #(config/read-file (str dir "/unknown-key.edn") :default (config/checkout)))]
    (is (= :seon.config/refused (:seon.error/type r)))
    (is (= [{:key :seon.config.root/max-agentz :problem :unknown-key :value "3"}] (:seon.config/problems r)))))

(deftest an-invalid-value-refuses-boot-naming-each-key
  (let [r (refused #(config/read-file (str dir "/invalid.edn") :default (config/checkout)))]
    (is (= :seon.config/refused (:seon.error/type r)))
    (is (= #{:seon.config.root/max-agents :seon.config.store/backend} (set (map :key (:seon.config/problems r)))))
    (is (every? #(= :invalid (:problem %)) (:seon.config/problems r)))))

(deftest a-missing-required-key-refuses-and-nil-is-absent
  (let [r (refused #(config/resolved (assoc config/config :seon.config.store/flush-ms nil)))]
    (is (= [{:key :seon.config.store/flush-ms :problem :missing-key :value "nil"}] (:seon.config/problems r))))
  ;; an optional key resolving nil is absent, not a stored nil
  (is (not (contains? (config/resolved (assoc config/config :seon.config.ai/thinking nil)) :seon.config.ai/thinking)))
  (is (= :disabled (:seon.config.ai/thinking (config/resolved (assoc config/config :seon.config.ai/thinking :disabled))))))

(deftest a-key-the-config-lacks-is-refused-not-nil
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.ai/thinking)))))
  (is (= :seon.config/refused (:seon.error/type (refused #(config/value :seon.config.nope/x))))))

(deftest the-provider-is-the-config-s
  (is (= {:kind :scripted :script 'seon.fixtures/reply} (config/provider config/config)))
  (let [p (config/provider (assoc config/config :seon.config.ai/provider :openai-compat :seon.config.ai/thinking :disabled))]
    (is (= {:kind :openai-compat :base-url "https://api.deepseek.com" :model "deepseek-flash" :api-key-env "DEEPSEEK_API_KEY"
            :max-tokens (config/value :seon.config.ai/max-tokens) :thinking :disabled}
           p))
    ;; the facts say what a run handed another provider really used
    (is (= p (config/provider (config/with-provider config/config p))))))

;; ---- facts on main, read from the db -------------------------------------------------------

(deftest root-writes-the-config-on-main-and-the-db-answers-it
  (let [[cfg conn] (pdb/create! 0)
        c (assoc config/config :seon.config.agent/max-running 6)]
    (is (nil? (config/from-db @conn)))
    (d/transact conn {:tx-data (into (task/boot-tx "boot-1" 6) [(config/facts c)])})
    (is (= c (config/from-db @conn)))
    ;; the reconciler's root read sees the same dials
    (is (= {:boot "boot-1" :max-running 6 :max-merging (config/value :seon.config.merge/max-running)} (task/root @conn)))
    ;; a branch made from main holds them too (what an agent forked onto it reads)
    (d/branch! conn :db :a1)
    (is (= c (config/from-db @(d/connect (assoc cfg :branch :a1)))))))

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
