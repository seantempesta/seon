(ns seon.agent.ctx.render-fns-test
  "Behavior of the current-ns render-fn AUTO-RUN (context.md §\"Auto-run\"):

     - DETECTION is the OUTPUT SCHEMA, structurally ([[output-twin-keys]]) —
       a `:map` declaring `:seon.render/ai` / `:seon.render/hiccup` (or a
       registered ref resolving to one); plain outputs are not renderers.
     - DERIVATION ([[derived-blocks]]) — one block per discovered fn in the
       CURRENT ns only; private fns and `::pinned-syms` (the `install!`
       override) excluded; slots match the declared twins; never stored.
     - The RUNNER — an agent-authored fn runs SCI-bounded with the frozen
       db/id passed explicitly; a throw becomes a `;; ⚠` line (ai) and the
       `:seon/error` tile (html), and the render pass SURVIVES.
     - context-root MERGE — the derived blocks join the agent's stored set
       in ONE ordered list at priority 30.

   Tests assert BEHAVIOR (which blocks exist, that output/errors surface),
   never exact rendered format. Hermetic: fixtures seed `:seon.ns` /
   `:seon.fn` rows into a scratch in-memory conn — no file read, no live
   store."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing async]]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.render-fns :as rf]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.process :as process]))

(def ^:private agent-id "tst-2607020000")
(def ^:private other-agent-id "oth-2607020001")
(def ^:private cur-ns :my.agent.tst-2607020000)
(def ^:private cur-ns-str "my.agent.tst-2607020000")

(def ^:private render-spec
  "[:=> [:cat [:map [:seon.db/db {:optional true} :seon.db/db] [:seon.agent/id {:optional true} :string]]] [:map [:seon.render/ai :string] [:seon.render/hiccup [:vector :any]]]]")

(def ^:private ai-only-spec
  "[:=> [:cat :map] [:map [:seon.render/ai :string]]]")

(def ^:private plain-spec "[:=> [:cat :map] :string]")

(defn- fn-row [sym spec source & {:keys [private? read-attrs]}]
  (cond-> {:seon.fn/sym      sym
           :seon.fn/ns       [:seon.ns/name cur-ns]
           :seon.fn/source   source
           :seon.fn/fn-var?  true
           :seon.fn/private? (boolean private?)
           :seon.fn/arglists "([m])"}
    spec (assoc :seon.fn/spec spec)
    (seq read-attrs) (assoc :seon.fn/read-attrs (vec read-attrs))))

(defn- good-view-source []
  (str "(defn good-view [{pdb :seon.db/db id :seon.agent/id}] "
       "{:seon.render/ai (str \"GOOD-AI me=\" id \" db=\" (boolean pdb)) "
       ":seon.render/hiccup [:div \"GOOD-TILE\"]})"))

(defn- seed-tx []
  [{:seon.agent/id agent-id}
   {:seon.agent/id other-agent-id}
   {:seon.ns/name   cur-ns
    :seon.ns/source (str "(ns " cur-ns-str ")")}
   ;; a render-typed TWIN fn, a render-typed ai-only fn, a THROWING render
   ;; fn, a plain (non-render) fn, and a private render fn.
   (fn-row (str cur-ns-str "/good-view") render-spec (good-view-source))
   (fn-row (str cur-ns-str "/ai-view") ai-only-spec
           "(defn ai-view [_] {:seon.render/ai \"AI-ONLY\"})")
   (fn-row (str cur-ns-str "/bad-view") render-spec
           "(defn bad-view [_] (throw (ex-info \"BAD-VIEW-BOOM\" {})))")
   (fn-row (str cur-ns-str "/plain-fn") plain-spec
           "(defn plain-fn [_] \"not a renderer\")")
   (fn-row (str cur-ns-str "/secret-view") render-spec
           "(defn- secret-view [_] {:seon.render/ai \"S\"})"
           :private? true)])

(defn- with-seeded [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 ;; explicit conn — a `.then` continuation escapes the CLJS
                 ;; `binding` extent, so nothing here may rely on *conn*.
                 (-> (db/transact! {:seon.db/conn conn
                                    :seon.db/tx-data (seed-tx)})
                     (.then (fn [_] (body conn)))))))))

;; ============================================================
;; Detection — the output schema, structurally.
;; ============================================================

(deftest output-twin-keys-detects-render-outputs
  (testing "a :map output declaring the twins is a renderer"
    (is (= #{:seon.render/ai :seon.render/hiccup} (rf/output-twin-keys render-spec)))
    (is (= #{:seon.render/ai} (rf/output-twin-keys ai-only-spec))))
  (testing "a registered ref output resolves (html-response envelope)"
    (is (= #{:seon.render/ai :seon.render/hiccup}
           (rf/output-twin-keys "[:=> [:cat :map] :seon.render/html-response]"))))
  (testing "plain / non-map / multi-arity / unreadable specs are not renderers"
    (is (= #{} (rf/output-twin-keys plain-spec)))
    (is (= #{} (rf/output-twin-keys "[:=> [:cat :map] [:vector :any]]")))
    (is (= #{} (rf/output-twin-keys "[:function [:=> [:cat :map] [:map [:seon.render/ai :string]]]]")))
    (is (= #{} (rf/output-twin-keys "not a schema")))))

;; ============================================================
;; Derivation — one derived block per discovered current-ns fn.
;; ============================================================

(deftest derived-blocks-select-by-output-schema
  (async done
    (-> (with-seeded
          (fn [conn]
            (let [blocks (rf/derived-blocks {:seon.db/db @conn
                                             :seon.agent/id agent-id
                                             ::rf/current-ns cur-ns})
                  names  (mapv :seon.agent.ctx/name blocks)]
              (testing "render-typed public fns become blocks; plain + private don't"
                (is (= [:render-fn/ai-view :render-fn/bad-view :render-fn/good-view]
                       names)))
              (testing "slots match the declared twins"
                (let [by-name (into {} (map (juxt :seon.agent.ctx/name identity)) blocks)]
                  (is (contains? (by-name :render-fn/good-view) :seon.render/html))
                  (is (contains? (by-name :render-fn/good-view) :seon.render/ai))
                  (is (contains? (by-name :render-fn/ai-view) :seon.render/ai))
                  (is (not (contains? (by-name :render-fn/ai-view) :seon.render/html)))))
              (testing "every block carries the fn sym + the auto-run priority"
                (is (every? (comp qualified-symbol? ::rf/fn-sym) blocks))
                (is (every? #(= rf/auto-run-priority (:seon.agent.ctx/priority %)) blocks))))))
        (.then done))))

(deftest derived-blocks-respect-pins-and-missing-ns
  (async done
    (-> (with-seeded
          (fn [conn]
            (testing "a pinned sym (install! override) is skipped"
              (let [blocks (rf/derived-blocks
                             {:seon.db/db @conn
                              ::rf/current-ns cur-ns
                              ::rf/pinned-syms #{(symbol cur-ns-str "good-view")}})]
                (is (not (some #{:render-fn/good-view}
                               (map :seon.agent.ctx/name blocks))))))
            (testing "nil / unknown current-ns derives nothing"
              (is (= [] (rf/derived-blocks {:seon.db/db @conn})))
              (is (= [] (rf/derived-blocks {:seon.db/db @conn
                                            ::rf/current-ns :my.nowhere}))))))
        (.then done))))

(deftest derived-blocks-select-from-acquired-ordinary-rows
  (let [rows [{:seon.fn/sym "my.view/z-view"
               :seon.fn/spec render-spec
               :seon.fn/private? false}
              {:seon.fn/sym "my.view/a-view"
               :seon.fn/spec ai-only-spec}
              {:seon.fn/sym "my.view/plain"
               :seon.fn/spec plain-spec}
              {:seon.fn/sym "my.view/private-view"
               :seon.fn/spec render-spec
               :seon.fn/private? true}]
        blocks (rf/derived-blocks {::rf/fn-rows rows})]
    (testing "ordinary acquired rows need no database value or second query"
      (is (= [:render-fn/a-view :render-fn/z-view]
             (mapv :seon.agent.ctx/name blocks))))
    (testing "the acquired schema still controls each twin slot"
      (is (= #{:seon.render/ai}
             (->> blocks first keys (filter #{:seon.render/ai
                                               :seon.render/html}) set)))
      (is (= #{:seon.render/ai :seon.render/html}
             (->> blocks second keys (filter #{:seon.render/ai
                                                :seon.render/html}) set))))))

;; ============================================================
;; The runner — bounded, errors-as-values, output extraction + clip.
;; ============================================================

(defn- block-for [conn nm]
  (->> (rf/derived-blocks {:seon.db/db @conn ::rf/current-ns cur-ns})
       (filter #(= nm (:seon.agent.ctx/name %)))
       first))

(deftest runner-renders-the-twins-with-resolved-deps
  (async done
    (-> (with-seeded
          (fn [conn]
            (let [node (block-for conn :render-fn/good-view)
                  in   {:seon.db/db @conn :seon.agent/id agent-id
                        :seon.render/node node}
                  ai   (rf/render-fn-block-ai in)
                  html (rf/render-fn-block-html in)]
              (testing "the ai twin carries the fn's output with db+id resolved"
                (is (str/includes? ai "GOOD-AI"))
                (is (str/includes? ai (str "me=" agent-id)))
                (is (str/includes? ai "db=true")))
              (testing "the html twin is the fn's hiccup"
                (is (= [:div "GOOD-TILE"] html))))))
        (.then done))))

(deftest runner-surfaces-a-throw-as-error-values
  (async done
    (-> (with-seeded
          (fn [conn]
            (let [node (block-for conn :render-fn/bad-view)
                  in   {:seon.db/db @conn :seon.agent/id agent-id
                        :seon.render/node node}
                  ai   (rf/render-fn-block-ai in)
                  html (rf/render-fn-block-html in)]
              (testing "the ai side is a legible ⚠ line naming fn + failure"
                (is (str/includes? ai "⚠"))
                (is (str/includes? ai "bad-view"))
                (is (str/includes? ai "BAD-VIEW-BOOM")))
              (testing "the html side is the :seon/error tile, not a crash"
                (is (vector? html))
                (is (str/includes? (pr-str html) "render error"))))))
        (.then done))))

(deftest runner-clips-the-ai-output-at-the-token-cap
  (async done
    (-> (with-seeded
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [(fn-row (str cur-ns-str "/huge-view") ai-only-spec
                            (str "(defn huge-view [_] {:seon.render/ai "
                                 "(apply str (repeat 20000 \"x\"))})"))]})
                (.then
                  (fn [_]
                    (let [node (block-for conn :render-fn/huge-view)
                          ai   (rf/render-fn-block-ai
                                 {:seon.db/db @conn :seon.agent/id agent-id
                                  :seon.render/node node})]
                      (testing "output is clipped with the LOUD token marker"
                        (is (str/includes? ai "clipped at"))
                        (is (str/includes? ai "tokens"))
                        (is (< (count ai) 20000)))))))))
        (.then done))))

;; ============================================================
;; context-root merge — one ordered list, twins between the stable code
;; and the volatile tail.
;; ============================================================

(deftest context-root-merges-derived-blocks-in-order
  (async done
    (-> (with-seeded
          (fn [conn]
            (let [root     (ctx/context-root {:seon.db/db @conn
                                              :seon.agent/id agent-id})
                  children (:seon.agent.ctx/children root)
                  names    (mapv :seon.agent.ctx/name children)]
              (testing "the derived blocks are children of the ONE root"
                (is (some #{:render-fn/good-view} names))
                (is (some #{:render-fn/ai-view} names)))
              (testing "children stay priority-sorted (derived at 30, one list)"
                (is (= (sort-by (juxt :seon.agent.ctx/priority
                                      (comp str :seon.agent.ctx/name))
                                children)
                       children))))))
        (.then done))))

;; ============================================================
;; last-updated-surface — the derived canvas default (context.md §canvas).
;; Candidates = THIS agent's authored tile fns (tx provenance); touch =
;; max(own source tx, txs of the attrs the source names). Pure f(db).
;; ============================================================

(def ^:private purpose-tile-source
  ;; a tile whose source NAMES :seon.agent/purpose — its declared read-set.
  (str "(defn purpose-tile [{pdb :seon.db/db}] "
       "{:seon.render/ai \"purposes\" "
       ":seon.render/hiccup [:ul [:li :seon.agent/purpose]]})"))

(def ^:private clock-tile-source
  ;; a tile naming NO installed attr — follows only its own redefinition.
  (str "(defn clock-tile [_] "
       "{:seon.render/ai \"clock\" :seon.render/hiccup [:p \"tick\"]})"))

(defn- transact-as!
  "Transact `tx-data` as the agent user through the default REPL process."
  [conn aid tx-data]
  (-> (db/with-agent aid
        (fn [] (db/transact! {:seon.db/conn conn :seon.db/tx-data tx-data})))
      (.then (fn [env]
               (when-not (:seon.db/ok? env)
                 (throw (ex-info "agent/REPL fixture transaction failed" env)))
               env))))

(defn- transact-as-process!
  "Transact `tx-data` with an explicit agent user and database process."
  [conn aid process-id tx-data]
  (db/without-agent
    (fn []
      (-> (db/with-tx-context
            {:seon.db/user [:seon.agent/id aid]
             :seon.db/process (process/lookup-ref process-id)}
            (fn []
              (db/transact! {:seon.db/conn conn :seon.db/tx-data tx-data})))
          (.then (fn [env]
                   (when-not (:seon.db/ok? env)
                     (throw (ex-info "explicit-provenance fixture transaction failed"
                                     env)))
                   env))))))

(deftest last-updated-surface-derives-nothing-without-authored-surfaces
  (async done
    (-> (with-seeded
          (fn [conn]
            ;; the standard seed has NO agent tx-provenance → no candidates.
            (testing "no authored tile fns → {} (caller falls back to welcome)"
              (is (= {} (rf/last-updated-surface {:seon.db/db @conn
                                               :seon.agent/id agent-id}))))))
        (.then done))))

(deftest last-updated-surface-follows-authorship-then-data
  (async done
    (-> (with-seeded
          (fn [conn]
                (-> (transact-as! conn agent-id
                  [(fn-row (str cur-ns-str "/purpose-tile") render-spec
                           purpose-tile-source
                           :read-attrs [:seon.agent/purpose])])
                (.then (fn [_]
                  (transact-as! conn agent-id
                    [(fn-row (str cur-ns-str "/clock-tile") render-spec
                             clock-tile-source)])))
                (.then (fn [_]
                  (testing "the most recently AUTHORED tile is derived"
                    (is (= {::rf/surface-sym
                            (symbol (str cur-ns-str "/clock-tile"))}
                           (rf/last-updated-surface {:seon.db/db @conn
                                                  :seon.agent/id agent-id}))))
                  (transact-as! conn other-agent-id
                    [{:seon.agent/id agent-id
                      :seon.agent/purpose "peer write"}])))
                (.then (fn [_]
                  (testing "another agent's data write cannot steal focus"
                    (is (= {::rf/surface-sym
                            (symbol (str cur-ns-str "/clock-tile"))}
                           (rf/last-updated-surface {:seon.db/db @conn
                                                  :seon.agent/id agent-id}))))
                  (transact-as-process! conn "root" ::process/config
                    [{:seon.agent/id agent-id
                      :seon.agent/purpose "config repair"}])))
                (.then (fn [_]
                  (testing "root/config data work cannot steal agent focus"
                    (is (= {::rf/surface-sym
                            (symbol (str cur-ns-str "/clock-tile"))}
                           (rf/last-updated-surface {:seon.db/db @conn
                                                  :seon.agent/id agent-id}))))
                  ;; now the owning agent deliberately writes DATA the
                  ;; purpose-tile reads — the canvas follows with zero ceremony.
                  (transact-as! conn agent-id
                    [{:seon.agent/id agent-id
                      :seon.agent/purpose "canvas proof"}])))
                (.then (fn [_]
                  (testing "a write to a watched attr makes that tile last-updated"
                    (is (= {::rf/surface-sym
                            (symbol (str cur-ns-str "/purpose-tile"))}
                           (rf/last-updated-surface {:seon.db/db @conn
                                                  :seon.agent/id agent-id})))))))))
        (.then done))))

(deftest last-updated-surface-uses-the-persisted-read-set
  ;; A row WITH :seon.fn/read-attrs is watched by those database facts.
  ;; opaque-tile's source names no attr literal (a dynamic read), but its
  ;; declared read-set includes :seon.agent/purpose.
  (async done
    (-> (with-seeded
          (fn [conn]
            (-> (transact-as! conn agent-id
                  [(fn-row (str cur-ns-str "/opaque-tile") render-spec
                           (str "(defn opaque-tile [m] "
                                "{:seon.render/ai \"opaque\" "
                                ":seon.render/hiccup [:p \"opaque\"]})")
                           :read-attrs [:seon.agent/purpose])])
                (.then (fn [_]
                  (transact-as! conn agent-id
                    [(fn-row (str cur-ns-str "/clock-tile") render-spec
                             clock-tile-source)])))
                (.then (fn [_]
                  (testing "later-authored clock-tile is derived first"
                    (is (= {::rf/surface-sym
                            (symbol (str cur-ns-str "/clock-tile"))}
                           (rf/last-updated-surface {:seon.db/db @conn
                                                  :seon.agent/id agent-id}))))
                  (transact-as! conn agent-id
                    [{:seon.agent/id agent-id
                      :seon.agent/purpose "stored read-set proof"}])))
                (.then (fn [_]
                  (testing "a write to a declared attr surfaces the canvas"
                    (is (= {::rf/surface-sym
                            (symbol (str cur-ns-str "/opaque-tile"))}
                           (rf/last-updated-surface {:seon.db/db @conn
                                                  :seon.agent/id agent-id})))))))))
        (.then done))))

(deftest renderer-dependencies-exclude-transaction-plumbing
  (async done
    (-> (with-seeded
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data [{:seon.agent/id agent-id
                                      :seon.agent/purpose "install attr"}]})
                (.then
                  (fn [_]
                    (transact-as! conn agent-id
                      [(fn-row (str cur-ns-str "/dependency-tile") render-spec
                               purpose-tile-source
                               :read-attrs [:seon.agent/purpose
                                            :seon.db/user
                                            :seon.db/process
                                            :seon.db.protocol/request-id])])))
                (.then
                  (fn [_]
                    (is (= #{:seon.agent/purpose}
                           (set (::rf/attrs
                                  (rf/renderer-read-attrs
                                    {:seon.db/db @conn
                                     :seon.render/html
                                     (symbol (str cur-ns-str
                                                  "/dependency-tile"))}))))
                        "provenance transport attrs do not invalidate views"))))))
        (.then done))))

(deftest renderer-dependencies-come-only-from-database-facts
  (async done
    (-> (with-seeded
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data
                   [(fn-row (str cur-ns-str "/source-only-tile") render-spec
                            purpose-tile-source)]})
                (.then
                  (fn [_]
                    (is (= []
                           (::rf/attrs
                             (rf/renderer-read-attrs
                               {:seon.db/db @conn
                                :seon.render/html
                                (symbol (str cur-ns-str
                                             "/source-only-tile"))})))
                        "source text is not a parallel dependency authority"))))))
        (.then done))))

(deftest last-updated-surface-gates-on-provenance-process-and-privacy
  (async done
    (-> (with-seeded
          (fn [conn]
            (-> (transact-as! conn other-agent-id
                  [(fn-row (str cur-ns-str "/other-tile") render-spec
                           clock-tile-source)])
                (.then (fn [_]
                  (transact-as! conn agent-id
                    [(fn-row (str cur-ns-str "/hidden-tile") render-spec
                             clock-tile-source :private? true)
                     (fn-row (str cur-ns-str "/ai-only-tile") ai-only-spec
                             "(defn ai-only-tile [_] {:seon.render/ai \"a\"})")])))
                (.then (fn [_]
                  (testing "another agent's fn, a private fn, and an ai-only fn are not candidates"
                    (is (= {} (rf/last-updated-surface {:seon.db/db @conn
                                                     :seon.agent/id agent-id})))))))))
        (.then done))))

(deftest last-updated-surface-excludes-root-boot-renderers
  (async done
    (-> (with-seeded
          (fn [conn]
            (-> (transact-as-process! conn "root" ::process/boot
                  [(fn-row "seon.agent.ctx/render-namespace" render-spec
                           "(defn render-namespace [_] {:seon.render/hiccup [:div]})")])
                (.then
                  (fn [_]
                    (is (= {} (rf/last-updated-surface
                        {:seon.db/db @conn
                                 :seon.agent/id "root"}))
                        "root/boot source facts are not root-agent canvas authorship"))))))
        (.then done))))

(deftest renderer-recency-counts-only-deliberate-agent-repl-writes
  (async done
    (-> (with-seeded
          (fn [conn]
            (let [sym (symbol (str cur-ns-str "/purpose-tile"))
                  touch #(get (rf/renderer-touch
                                {:seon.db/db @conn
                                 :seon.agent/id agent-id
                                 :seon.render/html sym})
                              ::rf/touch)]
              (-> (transact-as! conn agent-id
                    [(fn-row (str sym) render-spec purpose-tile-source
                             :read-attrs [:seon.agent/purpose])])
                  (.then
                    (fn [_]
                      (let [authored-touch (touch)]
                        (is (int? authored-touch))
                        (-> (transact-as! conn other-agent-id
                              [{:seon.agent/id agent-id
                                :seon.agent/purpose "peer write"}])
                            (.then
                              (fn [_]
                                (is (= authored-touch (touch))
                                    "another agent cannot steal renderer recency")
                                (transact-as-process! conn "root" ::process/config
                                  [{:seon.agent/id agent-id
                                    :seon.agent/purpose "config repair"}])))
                            (.then
                              (fn [_]
                                (is (= authored-touch (touch))
                                    "root/config work is not an agent update")
                                (transact-as! conn agent-id
                                  [{:seon.agent/id agent-id
                                    :seon.agent/purpose "deliberate update"}])))
                            (.then
                              (fn [_]
                                (is (> (touch) authored-touch)
                                    "the owning agent's REPL write advances recency")))))))))))
        (.then done))))

(deftest context-root-skips-the-derived-canvas-tile
  (async done
    (-> (with-seeded
          (fn [conn]
            (-> (transact-as! conn agent-id
                  [(fn-row (str cur-ns-str "/clock-tile") render-spec
                           clock-tile-source)])
                (.then (fn [_]
                  (let [names (->> (ctx/context-root {:seon.db/db @conn
                                                      :seon.agent/id agent-id})
                                   :seon.agent.ctx/children
                                   (mapv :seon.agent.ctx/name))]
                    (testing "the derived-canvas fn does not double-render as its own auto-run block"
                      (is (not (some #{:render-fn/clock-tile} names))))
                    (testing "the other auto-run blocks are untouched"
                      (is (some #{:render-fn/good-view} names)))))))))
        (.then done))))
