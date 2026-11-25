(ns seon.health.corpus
  "12-scenario deterministic Situation Corpus (`seed = 42`), exact rule ablation,
   and empirical eval trace-miss detection."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike.db :as ddb]
            [hasch.core :as hasch]
            [seon.ctx :as ctx]
            [seon.ctx.diff :as diff]
            [seon.ctx.project :as proj]
            [seon.render.fit :as fit]
            [seon.rule :as rules]
            [seon.schema :as schema]
            [seon.store :as store :refer [db-schema]]))

(defonce ^:private ^:seon.flow/edge fixtures-cache (store/lru-cache 4))
(defonce ^:private ^:seon.flow/edge corpus-report-cache (store/lru-cache 16))

(def ^:private child-err-id (hasch/uuid [:sit/seed-42 :child-error]))
(def ^:private child-fix-id (hasch/uuid [:sit/seed-42 :child-fix]))
(def ^:private chat-msg-id  (hasch/uuid [:sit/seed-42 :chat-msg]))
(def ^:private sys-err-id   (hasch/uuid [:sit/seed-42 :sys-error]))

(def ^:private fixture-schema-keys
  [:seon.ui/hiccup :seon.task/entity :seon.code.var/entity :seon.code.ns/entity
   :seon.error/entity :seon.schema/entity])

(def ^:private fixture-attr-prefixes
  #{"seon.agent" "seon.agent.call" "seon.agent.eval" "seon.agent.message"
    "seon.task" "seon.code.ns" "seon.code.var" "seon.code.form" "seon.code.doc"
    "seon.error" "seon.fix" "seon.test" "seon.test.run" "seon.schema"
    "seon.system" "seon.rule"})

(defn- pfx [ns-s m]
  (reduce-kv (fn [a k v] (assoc a (if (namespace k) k (keyword ns-s (name k))) v)) {} m))

(defn- ns* [n] {:seon.code.ns/name n :seon.code.ns/path (str "src/" (str/replace (name n) "." "/") ".clj")})
(defn- fm* [m] (pfx "seon.code.form" m))
(defn- vr* [m] (pfx "seon.code.var" m))
(defn- tk* [m] (pfx "seon.task" (merge {:done? false} m)))
(defn- ag* [m] (pfx "seon.agent" m))

(defn- eav1 [db e a] (when (and db e ((db-schema db) a)) (:v (first (d/datoms db :eavt e a)))))
(defn- avet1 [db a v] (when (and db ((db-schema db) a)) (:e (first (d/datoms db :avet a v)))))
(defn- unwrap-sym [x] (if (and (seq? x) (= 'quote (first x)) (symbol? (second x))) (second x) x))

(defn- valid-dh-schema [db]
  (let [ok? (fn [k v] (and (keyword? k) (map? v) (:db/valueType v) (:db/cardinality v)
                           (contains? fixture-attr-prefixes (namespace k))))
        clean (into {} (filter (fn [[k v]] (ok? k v))) (or (when db (db-schema db)) {}))]
    (if (contains? clean :seon.agent/id)
      clean
      (into clean (keep (fn [sp] (when (and (map? sp) (ok? (:db/ident sp) sp)) [(:db/ident sp) sp])))
            (:declarations (schema/registry-for-db nil))))))

(defn- tx-with [db tx-data] (:db-after (d/with db tx-data)))

(defn- mk-fix
  ([base m-tx] (let [d (tx-with base m-tx)] {:main-db d :branch-db d}))
  ([base m-tx b-tx] (mk-fix base m-tx b-tx nil))
  ([base m-tx b-tx extra]
   (let [m (tx-with base m-tx)] (merge {:main-db m :branch-db (tx-with m b-tx)} extra))))

(defn- build-core-fixtures [base]
  (let [dc* #(pfx "seon.code.doc" (merge {:order 1} %))
        scoped (mk-fix base
                 [(assoc (ns* 'seon.sit.alpha) :seon.code.ns/doc "Alpha.")
                  (tk* {:id :sit/prereq :title "Prepare schema" :done? true :summary "Done."})
                  (tk* {:id :sit/main-task :title "Implement alpha" :scope 'seon.sit.alpha :needs #{:sit/prereq} :plan "Wire."})
                  (vr* {:name 'seon.sit.alpha/entry :ns 'seon.sit.alpha :doc "Entry."})
                  (ag* {:id :sit/scoped-agent :branch :branch/sit-scoped :scope 'seon.sit.alpha :goal "Alpha"
                        :tasks #{[:seon.task/id :sit/main-task]} :pinned [[:seon.code.var/name 'seon.sit.alpha/entry]]})])]
    {:sit/scoped-task scoped :sit/responsive-viewport-fit scoped
     :sit/vague-request
     (mk-fix base
       [(ns* 'seon.sit.cache)
        (vr* {:name 'seon.sit.cache/invalidate-cache :ns 'seon.sit.cache :doc "Invalidates LRU."})
        (dc* {:id "docs/sit-caching.md#lru-invalidation" :path "docs/sit-caching.md" :heading "## LRU" :body "Commit keys."})
        (tk* {:id :sit/vague-task :title "Fix stale LRU cache invalidation" :plan "Fix stale LRU."})
        (ag* {:id :sit/vague-agent :branch :branch/sit-vague :goal "Fix stale LRU cache invalidation" :tasks #{[:seon.task/id :sit/vague-task]}})])
     :sit/forked-review
     (mk-fix base
       [(tk* {:id :sit/worker-task :title "Refactor parser"})
        (ag* {:id :sit/worker :branch :branch/sit-worker :paused? true :goal "Refactor" :tasks #{[:seon.task/id :sit/worker-task]}})
        (ag* {:id :sit/reviewer :branch :branch/sit-reviewer :goal "Review" :forked-from [:seon.agent/id :sit/worker] :target [:seon.agent/id :sit/worker]})])
     :sit/active-code-neighborhood
     (mk-fix base
       [(ns* 'seon.sit.core) (ns* 'seon.sit.api) (ns* 'seon.sit.core-test)
        (fm* {:key "seon.sit.core/compute" :ns 'seon.sit.core :hash "c0" :src "(defn compute [t] t)"})
        (vr* {:name 'seon.sit.core/compute :ns 'seon.sit.core :form "seon.sit.core/compute" :input #{[:seon.schema/key :seon.task/entity]} :output #{[:seon.schema/key :seon.code.var/entity]}})
        (vr* {:name 'seon.sit.api/handler :ns 'seon.sit.api :refs #{'seon.sit.core/compute} :calls #{[:seon.code.var/name 'seon.sit.core/compute]}})
        (vr* {:name 'seon.sit.core-test/compute-test :ns 'seon.sit.core-test :test? true :refs #{'seon.sit.core/compute} :calls #{[:seon.code.var/name 'seon.sit.core/compute]}})
        (ag* {:id :sit/editor :branch :branch/sit-editor :scope 'seon.sit.core :goal "Update compute"})]
       [(fm* {:key "seon.sit.core/compute" :ns 'seon.sit.core :hash "c1" :src "(defn compute [t] (assoc t :ok true))"})])
     :sit/concept-doc-teaching
     (mk-fix base
       [(ns* 'seon.sit.ui)
        (fm* {:key "seon.sit.ui/paint-view" :ns 'seon.sit.ui :hash "p0" :src "(defn paint-view [m] {:seon.ui/hiccup [:div]})"})
        (vr* {:name 'seon.sit.ui/paint-view :ns 'seon.sit.ui :form "seon.sit.ui/paint-view" :refs #{'seon.fork.calls/set-canvas!} :output #{[:seon.schema/key :seon.ui/hiccup]}})
        (dc* {:id "docs/sit-canvas.md#canvas-guide" :path "docs/sit-canvas.md" :heading "## Canvas" :body "Use set-canvas!."
              :references #{[:seon.code.var/name 'seon.sit.ui/paint-view] [:seon.schema/key :seon.ui/hiccup]}})
        (ag* {:id :sit/doc-agent :branch :branch/sit-doc :scope 'seon.sit.ui :goal "Canvas" :pinned [[:seon.schema/key :seon.ui/hiccup]]})]
       [(fm* {:key "seon.sit.ui/paint-view" :ns 'seon.sit.ui :hash "p1" :src "(defn paint-view [m] {:seon.ui/hiccup [:section]})"})])
     :sit/visual-ui-authoring
     (mk-fix base
       [(ns* 'seon.sit.ui)
        (fm* {:key "seon.sit.ui/render-task-mini" :ns 'seon.sit.ui :hash "r0" :src "(defn render-task-mini [m] {:seon.ui/hiccup [:span]})"})
        (vr* {:name 'seon.sit.ui/render-task-mini :ns 'seon.sit.ui :form "seon.sit.ui/render-task-mini" :input #{[:seon.schema/key :seon.task/entity]} :output #{[:seon.schema/key :seon.ui/hiccup]}})
        (vr* {:name 'seon.sit.ui/render-task-wide :ns 'seon.sit.ui :input #{[:seon.schema/key :seon.task/entity]} :output #{[:seon.schema/key :seon.ui/hiccup]}})
        (ag* {:id :sit/ui-author :branch :branch/sit-ui :scope 'seon.sit.ui :goal "Task renderer"})]
       [(fm* {:key "seon.sit.ui/render-task-mini" :ns 'seon.sit.ui :hash "r1" :src "(defn render-task-mini [m] {:seon.ui/hiccup [:span 1]})"})])}))

(defn- build-ops-fixtures [base]
  (let [er* #(pfx "seon.error" (merge {:status :open :severity :error :source :eval} %))]
    {:sit/open-error-and-child-escalation
     (mk-fix base
       [(ag* {:id :sit/parent :branch :branch/sit-parent :goal "Coord"})
        (ag* {:id :sit/child-ok :branch :branch/sit-child-ok :goal "OK" :parent :sit/parent :parent-ref [:seon.agent/id :sit/parent] :waiting? true})
        (ag* {:id :sit/child-err :branch :branch/sit-child-err :goal "Err" :parent :sit/parent :parent-ref [:seon.agent/id :sit/parent]})
        (er* {:id child-err-id :agent :sit/child-err :agent-ref [:seon.agent/id :sit/child-err] :kind :sit/schema-drift :message "Drift" :at 1000})
        (pfx "seon.fix" {:id child-fix-id :error-class :sit/schema-drift :symptom "Drift" :root-cause "Stale" :change-summary "Fixed" :regression-test 'seon.sit.core-test/drift-test})])
     :sit/failing-reach-test
     (mk-fix base
       [(ns* 'seon.sit.math) (ns* 'seon.sit.math-test)
        (vr* {:name 'seon.sit.math/add-tax :ns 'seon.sit.math :doc "Tax."})
        (fm* {:key "seon.sit.math-test/add-tax-test" :ns 'seon.sit.math-test :hash "ft1" :src "(deftest add-tax-test (is (= 10 (add-tax 9))))"})
        (vr* {:name 'seon.sit.math-test/add-tax-test :ns 'seon.sit.math-test :test? true :form "seon.sit.math-test/add-tax-test" :refs #{'seon.sit.math/add-tax} :calls #{[:seon.code.var/name 'seon.sit.math/add-tax]}})
        (pfx "seon.test" {:digest "sit-fail-dg-1" :var 'seon.sit.math-test/add-tax-test :var-ref [:seon.code.var/name 'seon.sit.math-test/add-tax-test] :pass? false :kind :test :report "FAIL" :ms 12.0})
        (pfx "seon.test.run" {:branch :branch/sit-test :pass? false :failed-digests #{"sit-fail-dg-1"}})
        (ag* {:id :sit/tester :branch :branch/sit-test :goal "Fix"})])
     :sit/branch-health-regression
     (mk-fix base
       [(ns* 'seon.sit.legacy) (ns* 'seon.sit.clean)
        (fm* {:key "seon.sit.legacy#ns" :ns 'seon.sit.legacy :hash "ln" :src "(ns seon.sit.legacy)"})
        (fm* {:key "seon.sit.legacy/old-debt" :ns 'seon.sit.legacy :hash "l1" :src "(defn old-debt [x] x)"})
        (vr* {:name 'seon.sit.legacy/old-debt :ns 'seon.sit.legacy :form "seon.sit.legacy/old-debt" :arglists '([x])})
        (fm* {:key "seon.sit.clean#ns" :ns 'seon.sit.clean :hash "cn" :src "(ns seon.sit.clean)"})
        (ag* {:id :sit/health-agent :branch :branch/sit-health :scope 'seon.sit.clean :goal "Help"})]
       [(fm* {:key "seon.sit.clean/bad-fn" :ns 'seon.sit.clean :hash "cb1" :src "(defn bad-fn [x] x)"})
        (vr* {:name 'seon.sit.clean/bad-fn :ns 'seon.sit.clean :form "seon.sit.clean/bad-fn" :arglists '([x])})]
       {:derived-touches {:written #{} :written-nss #{'seon.sit.clean} :counts {} :energy {} :novel-frontier {} :max-tx {} :sig [:sit/bh]}})
     :sit/unread-chat-and-zero-repeat
     (mk-fix base
       [(tk* {:id :sit/chat-task :title "Respond"})
        (ag* {:id :sit/chatter :branch :branch/sit-chat :goal "Chat" :tasks #{[:seon.task/id :sit/chat-task]}})
        (pfx "seon.agent.message" {:id chat-msg-id :seq 1 :from :user :to :sit/chatter :type :chat :body "Status?"})])
     :sit/system-and-entity-browser
     (mk-fix base
       [{:seon.system/id :root :seon.system/title "Seon"}
        (tk* {:id :sit/sys-task :title "Ship"})
        (ag* {:id :sit/sys-worker :branch :branch/sit-sys-w :goal "Ship"})
        (er* {:id sys-err-id :kind :sit/boom :message "Sys err" :at 1001})
        (vr* {:name 'seon.sit.sys/inspect-me :ns 'seon.sit.sys :doc "Inspect."})])}))

(defn- ^:seon.flow/edge cached-fixtures [db]
  (let [dh (valid-dh-schema db)
        ck [(count dh) (contains? dh :seon.agent/tasks) (contains? dh :seon.code.doc/references)]]
    (store/lru-get! fixtures-cache 4 ck
                    #(let [base (tx-with (ddb/empty-db dh {:schema-flexibility :write :keep-history? true})
                                         (mapv (fn [k] {:seon.schema/key k}) fixture-schema-keys))]
                       (merge (build-core-fixtures base) (build-ops-fixtures base))))))

(def scenario-specs
  "Canonical ordered vector of the 12 universal situation corpus scenarios."
  [{:id :sit/scoped-task :title "Scoped task + prereq + scope + pin" :focus-ref [:seon.agent/id :sit/scoped-agent] :target :wire
    :expected-present [[:seon.task/id :sit/main-task] [:seon.task/id :sit/prereq] [:seon.code.ns/name 'seon.sit.alpha] [:seon.code.var/name 'seon.sit.alpha/entry]]}
   {:id :sit/vague-request :title "Vague 1-sentence goal, zero scope/files" :focus-ref [:seon.agent/id :sit/vague-agent] :target :wire
    :expected-present [[:seon.task/id :sit/vague-task] [:seon.code.doc/id "docs/sit-caching.md#lru-invalidation"] [:seon.code.var/name 'seon.sit.cache/invalidate-cache]]}
   {:id :sit/forked-review :title "Forked review targeting paused parent (zero repeat)" :focus-ref [:seon.agent/id :sit/reviewer] :target :wire :check :forked-review
    :expected-present [[:seon.agent/id :sit/worker] [:seon.task/id :sit/worker-task]]}
   {:id :sit/active-code-neighborhood :title "Branch-edited var + I/O schema + caller + test" :focus-ref [:seon.agent/id :sit/editor] :target :wire
    :expected-present [[:seon.code.var/name 'seon.sit.core/compute] [:seon.schema/key :seon.task/entity] [:seon.code.var/name 'seon.sit.api/handler] [:seon.code.var/name 'seon.sit.core-test/compute-test]]}
   {:id :sit/concept-doc-teaching :title "Touching canvas/schema surfaces referencing doc once" :focus-ref [:seon.agent/id :sit/doc-agent] :target :wire :check :doc-once
    :expected-present [[:seon.code.doc/id "docs/sit-canvas.md#canvas-guide"]]}
   {:id :sit/visual-ui-authoring :title "Authoring :seon.ui/hiccup renderer sees siblings" :focus-ref [:seon.agent/id :sit/ui-author] :target :wire
    :expected-present [[:seon.code.var/name 'seon.sit.ui/render-task-mini] [:seon.code.var/name 'seon.sit.ui/render-task-wide]]}
   {:id :sit/open-error-and-child-escalation :title "Healthy child compact + failing child error + fix" :focus-ref [:seon.agent/id :sit/parent] :target :wire :check :child-compact
    :expected-present [[:seon.agent/id :sit/child-ok] [:seon.error/id child-err-id] [:seon.fix/id child-fix-id]]}
   {:id :sit/failing-reach-test :title "Failing verdict + failing test form + called prod var" :focus-ref [:seon.agent/id :sit/tester] :target :wire
    :expected-present [[:seon.test/digest "sit-fail-dg-1"] [:seon.code.form/key "seon.sit.math-test/add-tax-test"] [:seon.code.var/name 'seon.sit.math/add-tax]]}
   {:id :sit/branch-health-regression :title "Branch structural issue surfaced without legacy debt" :focus-ref [:seon.agent/id :sit/health-agent] :target :wire
    :expected-present [[:seon.code.var/name 'seon.sit.clean/bad-fn]] :expected-absent [[:seon.code.var/name 'seon.sit.legacy/old-debt]]}
   {:id :sit/unread-chat-and-zero-repeat :title "Unread message Turn 1 + zero repeat Turn 2" :focus-ref [:seon.agent/id :sit/chatter] :target :wire :check :zero-repeat
    :expected-present [[:seon.agent.message/id chat-msg-id] [:seon.task/id :sit/chat-task]]}
   {:id :sit/system-and-entity-browser :title "System root overview + perspective var inspection" :focus-ref [:seon.system/id :root] :target :html :check :system-and-inspect
    :expected-present [[:seon.task/id :sit/sys-task] [:seon.agent/id :sit/sys-worker] [:seon.error/id sys-err-id]]}
   {:id :sit/responsive-viewport-fit :title "Responsive 390px vs 1440px HTML fit, 0 overflow" :focus-ref [:seon.agent/id :sit/scoped-agent] :target :html :check :responsive-fit
    :expected-present [[:seon.task/id :sit/main-task]]}])

(defn- resolve-ref-eid [mdb bdb ref]
  (when (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)))
    (or (avet1 bdb (first ref) (second ref))
        (when (and mdb (not= mdb bdb)) (avet1 mdb (first ref) (second ref))))))

(defn- fit-cands [main-db branch-db cands opts]
  (fit/fit-projections branch-db (filterv #(not= :agent-prefix (:zone %)) cands)
                       (merge {:main-db main-db :fallback-renderers fit/fallback-entity-renderers} opts)))

(defn- run-extra-check [spec {:keys [main-db branch-db]} cands fitted]
  (case (:check spec)
    :forked-review
    (let [pfx? (boolean (some #(and (= :agent-prefix (:zone %)) (= [:seon.agent/id :sit/worker] (:ref %))) cands))
          f2 (fit-cands main-db branch-db cands {:target :wire :token-budget 16000 :already-shown (:shown fitted)})]
      (and pfx? (zero? (long (:tokens f2 0)))))
    :doc-once
    (let [dr [:seon.code.doc/id "docs/sit-canvas.md#canvas-guide"] de (resolve-ref-eid main-db branch-db dr)
          eq? #(or (= dr (:ref %)) (and de (= de (:entity %))))]
      (if-not (some eq? (:selected fitted)) true
          (let [f2 (fit-cands main-db branch-db cands {:target :wire :token-budget 16000 :already-shown (:shown fitted)})
                d2 (some #(when (eq? %) %) (:selected f2))]
            (or (nil? d2) (= :unchanged (:status d2)) (zero? (long (:tokens d2 0)))))))
    :child-compact
    (let [c (some #(when (= [:seon.agent/id :sit/child-ok] (:ref %)) %) (:selected fitted))]
      (boolean (and c (<= (long (:tokens c 999)) 60) (<= (long (:min-width c 999)) 360))))
    :zero-repeat
    (zero? (long (:tokens (fit-cands main-db branch-db cands {:target :wire :token-budget 16000 :already-shown (:shown fitted)}) 0)))
    :system-and-inspect
    (let [fi {:entity (resolve-ref-eid main-db branch-db [:seon.code.var/name 'seon.sit.sys/inspect-me])
              :ref [:seon.code.var/name 'seon.sit.sys/inspect-me] :schema :seon.code.var/entity :priority 100}]
      (boolean (:chosen-fn (first (:selected (fit-cands main-db branch-db [fi] {:target :html :max-items 1 :avail-w 960 :area-budget 600000}))))))
    :responsive-fit
    (let [wf (fit-cands main-db branch-db cands {:target :html :viewport-w 1440 :viewport-h 900 :sidebar-open? true :slot :main})
          ok-w? (fn [f] (every? #(and (<= (long (:min-width % 0)) (long (:avail-w f 10000))) (not (:overflow-x? %))) (:selected f)))]
      (boolean (and (seq (:selected fitted)) (seq (:selected wf)) (ok-w? fitted) (ok-w? wf)
                    (or (not= (mapv :chosen-fn (:selected fitted)) (mapv :chosen-fn (:selected wf)))
                        (> (long (:area wf 0)) (long (:area fitted 0)))))))
    true))

(defn- score-scenario [spec {:keys [main-db branch-db] :as fix} cands fitted rule-trace ms]
  (let [sel (:selected fitted) srefs (into #{} (keep :ref) sel) seids (into #{} (keep :entity) sel)
        exp-p (vec (:expected-present spec))
        hit? #(or (contains? srefs %) (when-let [e (resolve-ref-eid main-db branch-db %)] (contains? seids e)))
        miss-p (filterv (complement hit?) exp-p) unexp-p (filterv hit? (:expected-absent spec))
        ok-extra? (run-extra-check spec fix cands fitted)
        fired (into #{} (comp (filter #(pos? (:matched-count %))) (map :rule)) rule-trace)]
    (with-meta
      {:id (:id spec) :title (:title spec) :focus-ref (:focus-ref spec)
       :pass? (and (empty? miss-p) (empty? unexp-p) (boolean ok-extra?))
       :expected-present exp-p :missing-present miss-p :expected-absent (vec (:expected-absent spec))
       :unexpected-present unexp-p :tokens (long (:tokens fitted 0))
       :budget (long (:budget fitted 16000)) :ms ms}
      {:seon.corpus/fired-rules fired :seon.corpus/candidates cands})))

(defn- slot-opts [spec]
  (if (= :html (:target spec))
    (if (= :responsive-fit (:check spec))
      {:target :html :viewport-w 390 :viewport-h 844 :sidebar-open? false :slot :main}
      {:target :html :viewport-w 1440 :viewport-h 900 :sidebar-open? true :slot :main})
    {:target :wire :token-budget 16000}))

(defn- eval-one-scenario [spec {:keys [main-db branch-db derived-touches] :as fix} rules disabled]
  (let [t0 (System/nanoTime)
        p-opts (cond-> (merge {:rules rules :use-cache? false :semantic? false :enrich? false
                               :use-live-touches? false :include-unmatched? false :include-focal-fit? false}
                              (slot-opts spec))
                 (seq disabled) (assoc :disabled-rules disabled :include-rule-trace? false)
                 derived-touches (assoc :derived-touches derived-touches))
        r1 (ctx/project-entity main-db branch-db (:focus-ref spec) p-opts)
        ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)]
    (score-scenario spec fix (:candidates r1) (:fitted r1) (:rule-trace r1) ms)))

(defn- ablate-scenario [spec fix rules base-row rid wm?]
  (let [cands (:seon.corpus/candidates (meta base-row))
      rem-c (filterv #(not= [rid] (:rules %)) cands)
      sole-drop? (< (count rem-c) (count cands))
      tight? (>= (long (:tokens base-row 0)) (long (* 0.85 (long (:budget base-row 2400)))))
      shared-impact? (and tight? (boolean (some #(and (some #{rid} (:rules %)) (> (count (:rules %)) 1)) cands)))]
  (cond
    (or wm? shared-impact?) (eval-one-scenario spec fix rules #{rid})
    (not sole-drop?) base-row
    :else (score-scenario spec fix rem-c (fit-cands (:main-db fix) (:branch-db fix) rem-c (slot-opts spec)) [] 0.0))))

(defn ablation-report
  "Disables each enabled `:context` rule one at a time across active corpus scenarios."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db] [:vector :map] [:vector :map]] [:vector :map]]]
   :cost/ticks 60000000}
  ([db]
   (let [rs (ctx/load-context-rules db db nil) fx (cached-fixtures db)]
     (ablation-report db rs (mapv #(eval-one-scenario % (get fx (:id %)) rs nil) scenario-specs))))
  ([db rules base-results]
   (let [fx (cached-fixtures db) by-id (into {} (map (juxt :id identity)) base-results)
         enabled (->> rules (filter #(and (not (false? (:seon.rule/enabled? %)))
                                          (or (= :context (:seon.rule/category %)) (some map? (:seon.rule/assert %)))))
                      (sort-by (juxt #(long (or (:seon.rule/stratum %) 50)) #(str (:seon.rule/id %)))))]
     (mapv (fn [r]
             (let [rid (:seon.rule/id r) wm? (boolean (some vector? (:seon.rule/assert r)))
                   sits (filterv #(contains? (:seon.corpus/fired-rules (meta (get by-id (:id %)))) rid) scenario-specs)
                   contrib (vec (keep (fn [sp]
                                        (let [b (get by-id (:id sp)) w (ablate-scenario sp (get fx (:id sp)) rules b rid wm?)]
                                          (when (or (and (:pass? b) (not (:pass? w)))
                                                    (> (count (:missing-present w)) (count (:missing-present b)))) (:id sp))))
                                      sits))]
               {:rule-id rid :ablation-needed? (boolean (seq contrib)) :contributing-situations contrib}))
           enabled))))

(defn ^:seon.flow/edge corpus-report
  "Runs the 12 Universal Scenarios Situation Corpus + rule ablation against live rules in `db` (<1ms warm)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] :map]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :map]] :map]]
   :cost/ticks 80000000}
  ([db] (corpus-report db nil))
  ([db opts]
   (let [o (if (map? opts) opts {}) custom-rs (:rules o)
         rs (or custom-rs (ctx/load-context-rules db db nil))
         use-c? (and (nil? custom-rs) (not (false? (:use-cache? o))))
         rsig (when (and use-c? db) (rules/rules-db-sig db))
         csig (when (and use-c? db) (diff/code-db-sig db))
         sid (when (and use-c? db) (store/store-id db))
         run (fn []
               (let [fx (cached-fixtures db)
                     rows (mapv #(eval-one-scenario % (get fx (:id %)) rs nil) scenario-specs)
                     abl (ablation-report db rs rows)
                     passed (count (filter :pass? rows)) total (count rows)]
                 {:counts {:corpus-total total :corpus-passed passed :corpus-failed (- total passed)
                           :redundant-rules (count (remove :ablation-needed? abl))}
                  :corpus (mapv #(with-meta % nil) rows) :ablation abl}))]
     (if (and use-c? rsig csig) (store/lru-get! corpus-report-cache 16 [sid rsig csig] run) (run)))))

(def ^:private ref->schema
  {:seon.code.var/name :seon.code.var/entity :seon.schema/key :seon.schema/entity
   :seon.code.ns/name :seon.code.ns/entity :seon.code.doc/id :seon.code.doc/entity :seon.task/id :seon.task/entity})

(defn- ^:seon.flow/edge extract-lookup-refs [code]
  (when (and (string? code) (not (str/blank? code)) (some #(str/includes? code %) ["source" "schema" "callers" "tests-for" "grep-src"]))
    (let [fs (try (read-string (str "[" code "]")) (catch Exception e {:err (.getMessage e)}))]
      (when (vector? fs)
        (vec (distinct
              (for [n (tree-seq coll? seq fs) :when (and (seq? n) (symbol? (first n)) (>= (count n) 2))
                    :let [op (name (first n))] :when (#{"source" "schema" "callers" "tests-for" "grep-src"} op)
                    a (let [x (second n)] (if (vector? x) x [x])) :let [u (unwrap-sym a)]
                    :let [ref (case op
                                "source" (cond (qualified-symbol? u) [:seon.code.var/name u] (symbol? u) [:seon.code.ns/name u]
                                               (qualified-keyword? u) [:seon.schema/key u] (and (string? u) (str/includes? u "#")) [:seon.code.doc/id u])
                                "schema" (when (qualified-keyword? u) [:seon.schema/key u])
                                "callers" (when (qualified-symbol? u) [:seon.code.var/name u])
                                "tests-for" (cond (qualified-symbol? u) [:seon.code.var/name u] (symbol? u) [:seon.code.ns/name u])
                                "grep-src" (when (and (string? u) (not (str/blank? u)) (not (re-find #"\s" u)))
                                             (if (str/includes? u "/") [:seon.code.var/name (symbol u)] [:seon.code.ns/name (symbol u)])) nil)]
                    :when ref] ref)))))))

(defn- open-persisted-misses [db]
  (if-not (and db ((db-schema db) :seon.ctx.miss/status)) []
      (mapv (fn [dt]
              (let [{:seon.ctx.miss/keys [id agent turn ref schema hop via-attr status]}
                    (d/pull db '[:seon.ctx.miss/id :seon.ctx.miss/agent :seon.ctx.miss/turn :seon.ctx.miss/ref
                                 :seon.ctx.miss/schema :seon.ctx.miss/hop :seon.ctx.miss/via-attr :seon.ctx.miss/status] (:e dt))]
                {:id id :agent agent :turn (long (or turn 0)) :missed-ref ref :schema schema
                 :hop (long (or hop 1)) :via-attr via-attr :status (or status :open)}))
            (d/datoms db :avet :seon.ctx.miss/status :open))))

(defn ^:seon.flow/edge detect-trace-misses
  "Scans recent bounded evals for manual lookup calls on 1-2 hop neighbors not projected into context."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db]] [:vector :map]]
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] [:vector :map]]]
   :cost/ticks 40000000}
  ([db] (detect-trace-misses db db))
  ([main-db branch-db]
   (let [bdb (or branch-db main-db) mdb (or main-db bdb)]
     (if-not bdb []
         (let [dh (db-schema bdb)
               pers (concat (open-persisted-misses bdb) (when (not= mdb bdb) (open-persisted-misses mdb)))
               res-e (fn [[a v]] (or (avet1 bdb a v) (when (not= mdb bdb) (avet1 mdb a v))))
               aids (when (and (dh :seon.agent/id) (dh :seon.agent.eval/agent))
                      (->> (d/datoms bdb :aevt :seon.agent/id)
                           (keep #(let [aid (:v %)]
                                    (when (and (qualified-keyword? aid) (not= "example" (namespace aid))
                                               (not (eav1 bdb (:e %) :seon.agent/archived?)))
                                      aid)))
                           (take 16) vec))
               det (mapcat
                    (fn [aid]
                      (let [lks (->> (d/datoms bdb :avet :seon.agent.eval/agent aid) (take 8)
                                     (mapcat (fn [dt] (let [c (eav1 bdb (:e dt) :seon.agent.eval/code)
                                                            t (long (or (eav1 bdb (:e dt) :seon.agent.eval/turn) 0))]
                                                        (for [ref (extract-lookup-refs c) :let [te (res-e ref)] :when te] [t ref te])))) vec)]
                        (when (seq lks)
                          (let [nb (:neighbor-meta (proj/entity-world-datoms mdb bdb [:seon.agent/id aid] true))
                                in-w (filterv #(contains? nb (nth % 2)) lks)]
                            (when (seq in-w)
                              (let [pr (ctx/project-entity mdb bdb [:seon.agent/id aid]
                                                           {:target :wire :include-unmatched? false :include-focal-fit? false
                                                            :include-rule-trace? false :semantic? false :enrich? false :use-live-touches? false})
                                    sel (into #{} (keep :entity) (get-in pr [:fitted :selected]))]
                                (keep (fn [[t ref te]]
                                        (when-not (sel te)
                                          (let [{:keys [hop via-attr]} (get nb te)]
                                            {:id (hasch/uuid [:seon.ctx/miss aid ref]) :agent aid :turn t :missed-ref ref
                                             :schema (or (ref->schema (first ref)) :seon.schema/entity)
                                             :hop (long (or hop 1)) :via-attr via-attr :status :open}))) in-w)))))))
                    aids)]
           (->> (reduce #(let [k [(:agent %2) (:missed-ref %2)]] (if (contains? %1 k) %1 (assoc %1 k %2))) {} (concat pers det))
                vals (sort-by (juxt #(str (:agent %)) #(pr-str (:missed-ref %)))) vec))))))
