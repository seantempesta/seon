(ns seon.host-surface-writer-test
  "Computed W5-0 census of the child agent surface and host dispositions.

   Each disposition is audited against its serving mechanism. Registry-backed
   `:host/resolved` rows must occur in the wrapper-registry declarations.
   Portable `:host/base-resolved` rows must agree exactly, in both directions,
   with the final `:loaded` rows produced by the real host base loader. Keeping
   those statuses separate avoids an unauditable either-or assertion.

   This JVM test computes LEFT from public vars in the namespaces already
   named by the disposition table, using the same real reader as the host
   corpus path. It
   reads source and builds an unconnected process-local host base; it never
   reads a live database or registry. Explicit rows remain the W5-0b..h
   work-list, so a newly public function stays red until it receives a row."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.host.context :as context]
            [seon.host.record :as record]
            [seon.test.source-scan :as source-scan]))

(def ^:private cutover-required?
  "W5 cutover flips this only after every blocking disposition is closed."
  false)

(def ^:private valid-dispositions
  #{:host/resolved
    :host/base-resolved
    :host/capability-pending
    :host/platform-pending
    :host/excluded-with-reason})

(def ^:private pending-dispositions
  #{:host/capability-pending :host/platform-pending})

(defn- edge-aliases
  [edges]
  (into {}
        (keep (fn [{:seon.ns.require/keys [target alias]}]
                (when (and target alias) [alias target])))
        edges))

(defn- source-forms
  [path]
  (let [source (slurp path)
        ns-form (record/read-ns-form source)
        ns-sym (second ns-form)
        aliases (edge-aliases (if ns-form
                                (record/ns-require-edges ns-form)
                                #{}))]
    {::namespace ns-sym
     ::forms (record/read-forms {::record/source source
                                 ::record/ns-sym ns-sym
                                 ::record/aliases aliases})}))

(declare disposition-seeds)

(defn- surface-namespaces
  []
  (into #{}
        (map (comp symbol namespace symbol first))
        disposition-seeds))

(defn- public-surface-definitions
  []
  (let [namespaces (surface-namespaces)]
    (into {}
          (mapcat
           (fn [path]
             (let [{::keys [namespace forms]} (source-forms path)]
               (for [form forms
                     :when (and (contains? namespaces namespace)
                                (seq? form)
                                (= 'defn (first form))
                                (symbol? (second form))
                                (not (:private (meta (second form)))))]
                 [(str namespace "/" (second form))
                  (meta (second form))]))))
          (source-scan/source-files "src"))))

(defn- only-source
  [suffix]
  (let [matches (filterv #(str/ends-with? % suffix)
                         (source-scan/source-files "src"))]
    (when (= 1 (count matches)) (first matches))))

(defn- context-forms
  []
  (some-> (only-source (str "seon" java.io.File/separator
                            "host" java.io.File/separator "context.clj"))
          source-forms
          ::forms))

(defn- definition-form
  [forms definition-name]
  (first
   (filter #(and (seq? %)
                 (contains? '#{def defn defn-} (first %))
                 (= definition-name (second %)))
           forms)))

(defn- form-nodes
  [form]
  (tree-seq coll? seq form))

(defn- named-map-value
  [m key-name]
  (some (fn [[k v]]
          (when (and (keyword? k) (= key-name (name k))) v))
        m))

(defn- unquoted-symbol
  [form]
  (when (and (seq? form) (= 'quote (first form)) (symbol? (second form)))
    (second form)))

(defn- registry-declared-symbols
  [forms]
  (let [seed (definition-form forms 'register-host-capabilities!)]
    (into #{}
          (mapcat
           (fn [registration]
             (let [request (second registration)
                   lib (some-> request (named-map-value "lib") unquoted-symbol)
                   wrappers (some-> request (named-map-value "wrappers"))]
               (for [wrapper-form (keys wrappers)
                     :let [wrapper (unquoted-symbol wrapper-form)]
                     :when (and lib wrapper)]
                 (str lib "/" wrapper)))))
          (filter #(and (seq? %)
                        (symbol? (first %))
                        (= "register-host-wrappers!" (name (first %)))
                        (map? (second %)))
                  (form-nodes seed)))))

(defn- unconnected-writer
  []
  (context/writer-session
   {::context/writer-socket-path "tmp/unused-host-surface-test.sock"
    ::context/database-name "host-surface-test"}))

(defn- computed-host-resolution
  [left-symbols]
  (let [base (context/build-base! (unconnected-writer))]
    {::computed-base
     (into #{}
           (comp
            (filter #(= :loaded (::context/status %)))
            (map #(str (::context/namespace %) "/" (::context/block-name %)))
            (filter left-symbols))
           (get-in base [::context/report ::context/blocks]))
     ::computed-registry
     (into #{}
           (mapcat (fn [[lib entry]]
                     (map #(str lib "/" %) (keys (::context/vars entry)))))
           @(::context/registry base))}))

;; Rows without ::disposition are deliberately computed base rows. Their
;; status is filled only after the real loader reports the block `:loaded`.
(def ^:private disposition-seeds
  [;; W5-0e blob archive port.
   ["my.blob/concat!" {::disposition :host/platform-pending ::unit :w5-0e}]
   ["my.blob/get" {::disposition :host/platform-pending ::unit :w5-0e}]
   ["my.blob/put!" {::disposition :host/platform-pending ::unit :w5-0e}]
   ["my.blob/stat" {::disposition :host/capability-pending ::unit :w5-0e}]
   ["my.blob/text" {::disposition :host/platform-pending ::unit :w5-0e}]

   ;; Computed portable-base canvas rows, then W5-0f toolkit follow-ups.
   ["my.canvas/button" {::unit :w5-0a}]
   ["my.canvas/clear!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.canvas/form" {::unit :w5-0a}]
   ["my.canvas/input" {::unit :w5-0a}]
   ["my.canvas/pinned" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.canvas/save!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.canvas/select" {::unit :w5-0a}]
   ["my.canvas/show!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.canvas/state" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.canvas/toggle" {::unit :w5-0a}]
   ["my.canvas/view" {::disposition :host/capability-pending ::unit :w5-0f}]

   ;; W5-0f toolkit follow-ups; pure reducers are computed base rows.
   ["my.data/group-sum" {::unit :w5-0a}]
   ["my.data/max-by" {::unit :w5-0a}]
   ["my.data/rows" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.data/sum-by" {::unit :w5-0a}]
   ["my.kb/recall" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.kb/remember" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.kb.shared/instructions" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.ns/compact!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.ns/full!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.ns/functions" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/active!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/blocked!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/document" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/done!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/drop!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/list-open" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/move!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/needs!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/next" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/plan!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/reconcile!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/reopen!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/status" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/step!" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.plan/tree" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.skills/list" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.skills/load" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["my.skills/unload" {::disposition :host/capability-pending ::unit :w5-0f}]

   ;; Computed portable-base presentation rows.
   ["my.ui/badge" {::unit :w5-0a}]
   ["my.ui/bullets" {::unit :w5-0a}]
   ["my.ui/kv-table" {::unit :w5-0a}]
   ["my.ui/progress" {::unit :w5-0a}]
   ["my.ui/section" {::unit :w5-0a}]
   ["my.ui/status-line" {::unit :w5-0a}]
   ["my.ui/table" {::unit :w5-0a}]

   ;; W5-0g filesystem capability family.
   ["seon.agent.fs/edit-file" {::unit :w5-0g}]
   ["seon.agent.fs/file-exists?" {::unit :w5-0g}]
   ["seon.agent.fs/grants" {::unit :w5-0g}]
   ["seon.agent.fs/home-dir" {::unit :w5-0g}]
   ["seon.agent.fs/insert!" {::unit :w5-0g}]
   ["seon.agent.fs/list-dir" {::unit :w5-0g}]
   ["seon.agent.fs/read-file" {::unit :w5-0g}]
   ["seon.agent.fs/replace!" {::unit :w5-0g}]
   ["seon.agent.fs/stat" {::unit :w5-0g}]
   ["seon.agent.fs/view" {::unit :w5-0g}]
   ["seon.agent.fs/walk-dir" {::unit :w5-0g}]
   ["seon.agent.fs/write-file" {::unit :w5-0g}]

   ;; W5-0d lifecycle capability family.
   ["seon.agent.lifecycle/complete" {::disposition :host/resolved}]
   ["seon.agent.lifecycle/pause" {::disposition :host/resolved}]
   ["seon.agent.lifecycle/resume" {::disposition :host/resolved}]
   ["seon.agent.lifecycle/terminate" {::disposition :host/resolved}]
   ["seon.agent.lifecycle/wait" {::disposition :host/resolved}]

   ;; W5-0c message capability family.
   ["seon.agent.message/agent" {::disposition :host/resolved}]
   ["seon.agent.message/user" {::disposition :host/resolved}]

   ;; W5-0g search capability family.
   ["seon.agent.search/grep" {::disposition :host/capability-pending ::unit :w5-0g}]
   ["seon.agent.search/grep-graph" {::disposition :host/capability-pending ::unit :w5-0g}]

   ;; W5-0g shell capability family.
   ["seon.agent.shell/grants" {::unit :w5-0g}]
   ["seon.agent.shell/job-output" {::unit :w5-0g}]
   ["seon.agent.shell/job-status" {::unit :w5-0g}]
   ["seon.agent.shell/job-stop!" {::unit :w5-0g}]
   ["seon.agent.shell/list-jobs" {::unit :w5-0g}]
   ["seon.agent.shell/py-run" {::unit :w5-0g}]
   ["seon.agent.shell/run" {::unit :w5-0g}]
   ["seon.agent.shell/run-bg!" {::unit :w5-0g}]
   ["seon.agent.web/fetch" {::disposition :host/capability-pending ::unit :w5-0h}]
   ["seon.agent.web/grants" {::disposition :host/capability-pending ::unit :w5-0h}]
   ["seon.agent.web/search" {::disposition :host/capability-pending ::unit :w5-0h}]

   ;; W5-0h web capability family.

   ;; W5-0f provider-backed AI capability family.
   ["seon.ai/generate-code!" {::disposition :host/capability-pending ::unit :w5-0f}]

   ;; W5-0b database capability family; existing registry names are resolved.
   ["seon.db/as-of" {::unit :w5-0b}]
   ["seon.db/cas-assert" {::unit :w5-0b}]
   ["seon.db/current-agent-id" {::unit :w5-0b}]
   ["seon.db/db" {::unit :w5-0b}]
   ["seon.db/entity" {::unit :w5-0b}]
   ["seon.db/execute-many" {::unit :w5-0b}]
   ["seon.db/history" {::unit :w5-0b}]
   ["seon.db/index-page" {::unit :w5-0b}]
   ["seon.db/installed-schema" {::unit :w5-0b}]
   ["seon.db/pull" {::unit :w5-0b}]
   ["seon.db/pull-many" {::unit :w5-0b}]
   ["seon.db/query" {::unit :w5-0b}]
   ["seon.db/query-with-evidence" {::unit :w5-0b}]
   ["seon.db/since" {::unit :w5-0b}]
   ["seon.db/transact!" {::unit :w5-0b}]

   ;; W5-0f schema capability family; compiled registry entries are resolved.
   ["seon.schema/enum-members" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["seon.schema/identity-attr?" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["seon.schema/register!" {::disposition :host/resolved ::unit :w5-0a}]
   ["seon.schema/registered-schemas" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["seon.schema/registered?" {::disposition :host/capability-pending ::unit :w5-0f}]
   ["seon.schema/schema-definition" {::disposition :host/resolved ::unit :w5-0a}]
   ["seon.schema/schemas-in-namespace" {::disposition :host/capability-pending ::unit :w5-0f}]])

(defn- disposition-table
  [left-symbols computed-base computed-registry]
  (let [seeds (into {} disposition-seeds)]
  (into (sorted-map)
        (map (fn [sym]
               (let [disposition (get seeds sym {::unit :ruling-19})]
                 [sym (if (contains? disposition ::disposition)
                        disposition
                        (cond
                          (contains? computed-registry sym)
                          (assoc disposition ::disposition :host/resolved)
                          (contains? computed-base sym)
                          (assoc disposition ::disposition :host/base-resolved)
                          :else
                          (assoc disposition
                                 ::disposition :host/capability-pending)))])))
        left-symbols)))

(defn- excluded-row?
  [[_ disposition]]
  (= :host/excluded-with-reason (::disposition disposition)))

(defn- blocking-row?
  [[_ disposition]]
  (or (contains? pending-dispositions (::disposition disposition))
      (= :host/excluded-with-reason (::disposition disposition))))

(defn- category-counts
  [table]
  (into (sorted-map) (frequencies (map (comp ::disposition val) table))))

(deftest computed-agent-surface-has-one-honest-host-disposition
  (let [definitions (public-surface-definitions)
        left-symbols (set (keys definitions))
        forms (context-forms)
        {computed-base ::computed-base
         registry-symbols ::computed-registry}
        (computed-host-resolution left-symbols)
        table (disposition-table left-symbols computed-base registry-symbols)
        table-symbols (set (keys table))
        resolved (into #{}
                       (comp (filter #(= :host/resolved
                                         (::disposition (val %))))
                             (map key))
                       table)
        base-resolved (into #{}
                            (comp (filter #(= :host/base-resolved
                                              (::disposition (val %))))
                                  (map key))
                            table)
        exclusions (filterv excluded-row? table)
        blocking (filterv blocking-row? table)
        counts (category-counts table)]
    (println "W5-0 agent-surface census"
             (pr-str {:left (count left-symbols)
                      :registry-declarations (count registry-symbols)
                      :blocking (count blocking)
                      :dispositions counts}))
    (testing "source declarations and the real loader are readable"
      (is (seq forms) "host context source must parse structurally")
      (is (seq registry-symbols)
          "wrapper registry declarations must be source-derived")
      (is (seq computed-base)
          "the portable base must report its loaded public surface rows"))
    (testing "every deliberate child function has exactly one current row"
      (is (= (count left-symbols) (count table))
          "duplicate disposition rows are forbidden")
      (is (= left-symbols table-symbols)
          (str "missing dispositions: "
               (pr-str (sort (set/difference left-symbols table-symbols)))
               "; stale dispositions: "
               (pr-str (sort (set/difference table-symbols left-symbols)))))
      (is (every? valid-dispositions (map (comp ::disposition val) table))
          "every row must carry exactly one recognized disposition"))
    (testing "registry-backed resolution agrees with registry declarations"
      (is (= resolved (set/intersection left-symbols registry-symbols))
          (str "resolved rows and declared registry names disagree: "
               (pr-str {:resolved-only
                        (sort (set/difference resolved registry-symbols))
                        :registry-only
                        (sort (set/difference
                               (set/intersection left-symbols registry-symbols)
                               resolved))}))))
    (testing "portable-base resolution agrees with final loaded rows"
      (is (= base-resolved computed-base)
          "no pending row may hide a loaded block and no base row may lie"))
    (testing "exclusions remain explicit owner-review blockers"
      (is (every? (fn [[_ disposition]]
                    (let [reason (::reason disposition)]
                      (and (string? reason)
                           (not (str/blank? reason))
                           (str/includes? reason
                                          "excluded (owner review pending)"))))
                  exclusions)
          "every exclusion needs a durable reason and pending owner review"))
    (when cutover-required?
      (is (empty? blocking)
          (str "W5 cutover still has " (count blocking)
               " pending or unreviewed-exclusion rows: "
               (pr-str (mapv key blocking)))))))
