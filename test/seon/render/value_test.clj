(ns seon.render.value-test
  "The render floor is one adapter over the sealed print emitter."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [seon.db :as db]
            [sci.core :as sci]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.print :as print]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn- unit
  [raw]
  {:seon.agent/id "root"
   :seon.render.call/id [:seon.render.value-test/floor]
   :seon.render/value raw
   :seon.sci.admit/caps caps})

(defn- registered-unit
  [connection raw]
  (assoc (unit raw)
         :seon.db/db @connection
         :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
         :seon.render.value/root [:seon.render.value-test/registered raw]))

(defn- render-request
  [connection raw]
  (assoc (registered-unit connection raw)
         :seon.sci.eval/time-limit-ms 2000
         :seon.config/on-core-error :panic))

(defn- lexical-hiccup-text
  [form]
  (cond
    (string? form) form
    (sequential? form)
    (let [[tag & body] form
          body (if (map? (first body)) (next body) body)]
      (if (contains? #{:summary :nav} tag)
        ""
        (apply str (map lexical-hiccup-text body))))
    :else ""))

(deftest ordinary-values-use-one-readable-structural-printer
  (let [raw {:unregistered/value 1 :unregistered/detail [2 3]}
        projection (value/prepare (unit raw))]
    (is (= raw (edn/read-string (value/render-ai-data projection))))
    (is (= "#:unregistered{:detail [2 3], :value 1}"
           (value/render-ai-data projection)))
    (is (str/includes? (hiccup/->string (value/render-html (unit raw)))
                       "seon-print-map"))))

(deftest settings-remain-data-when-block-and-problem-renderers-match
  (support/with-database
   (fn [connection]
     (let [groups [{:seon.config.ai/model "deepseek/deepseek-v4-flash-20260731"
                    :seon.config.ai/no-provider true}
                   {:seon.config.ai.retry/maximum-retries 2}
                   {:my.agent/turns-left 20}]
           request (render-request connection groups)]
       (doseq [raw [groups (first groups)]]
         (let [shown (value/render-ai (assoc request :seon.render/value raw))]
           (is (= raw (edn/read-string shown)))
           (is (not (str/includes? shown "seon.render/ambiguous")))))))))

(deftest collection-cardinality-never-changes-values-into-text-tables
  (doseq [n [0 1 2 3]
          choice [:derived true false]]
    (let [rows (mapv (fn [i] {:my.message/id (str i)
                             :my.message/content "Read this message."}) (range n))
          projection (value/prepare
                      (assoc (unit rows) :seon.print/options {:seon.print/table? choice}))
          shown (value/render-ai-data projection)]
      (is (= rows (edn/read-string shown)) shown)
      (is (false? (get-in projection [:seon.render.value/options :seon.print/table?]))))))

(deftest component-members-render-in-declared-position-order
  (support/with-database
   (fn [connection]
     (let [rows [{:my.plan.item/id "order/a" :my.plan.item/position 2}
                 {:my.plan.item/id "order/z" :my.plan.item/position 1}]
           render-rows (fn [members]
                         (edn/read-string
                          (value/render-ai
                           (assoc (unit {:my.plan/steps members})
                                  :seon.db/db @connection))))]
       (is (= (vec (reverse rows)) (:my.plan/steps (render-rows rows))))
       (is (= (set rows) (:my.plan/steps (render-rows (set rows)))))
       (let [shown (value/render-ai (assoc (unit {:my.plan/steps (set rows)})
                                          :seon.db/db @connection))]
         (is (< (str/index-of shown "order/z") (str/index-of shown "order/a"))))
       (let [unordered [(first rows) (dissoc (second rows) :my.plan.item/position)]]
         (is (= unordered (:my.plan/steps (render-rows unordered)))))
       (let [visited? (atom false)
             uncounted (lazy-cat rows (lazy-seq (reset! visited? true)
                                                (throw (ex-info "omitted tail" {}))))
             profile (assoc (render/agent-render-profile (support/effective-config))
                            :seon.render.profile/max-children 1)
             shown (value/render-ai (assoc (unit {:my.plan/steps uncounted})
                                          :seon.db/db @connection
                                          :seon.render/profile profile))]
         (is (string? shown))
         (is (false? @visited?) "ordering does not realize an uncounted component tail"))
       (is (= rows
              (:fixture/rows
               (edn/read-string
                (value/render-ai
                 (assoc (unit {:fixture/rows rows}) :seon.db/db @connection))))))))))

(deftest plan-dependencies-show-ids-without-changing-the-pulled-value
  (support/with-database
   (fn [connection]
     (let [raw {:my.plan.item/needs #{{:my.plan.item/id "b"} {:my.plan.item/id "a"}}}
           shown (value/render-ai (assoc (unit raw) :seon.db/db @connection))]
       (is (= {:my.plan.item/needs ["a" "b"]} (edn/read-string shown)))
       (is (set? (:my.plan.item/needs raw)))
       (let [detailed {:my.plan.item/needs #{{:my.plan.item/id "a" :my.plan.item/title "Keep detail"}}}]
         (is (= detailed (edn/read-string (value/render-ai
                                          (assoc (unit detailed) :seon.db/db @connection))))))))))

(deftest declared-producers-still-have-absolute-precedence
  (support/with-database
   (fn [connection]
     (let [failure {:my.message/no-recipient true
                    :seon.error/message "A recipient is required."}
           request (render-request connection failure)
           declared-row {:seon.schema/key :fixture/declared-producer
                         :seon.render/ai 'fixture/render-ai
                         :seon.render/html 'fixture/render-html}]
       (with-redefs [schema/matching-shapes-in
                     (fn [_projection _value] [declared-row])]
         (is (= 'fixture/render-ai
                (#'seon.render/producer request
                 :seon.render/ai :seon.render/ai)))
         (is (= 'fixture/render-html
                (#'seon.render/producer request
                 :seon.render/html :seon.render/html))))))))

(deftest registered-floor-elisions-retain-their-requery-identity
  (support/with-database
   (fn [connection]
     (let [rows (mapv (fn [ordinal]
                        {:my.message/id (str "m-" ordinal)
                         :my.message/at (java.util.Date. (* 1000 ordinal))
                         :my.message/preview (str "message " ordinal)})
                      (range 3))
           root [:my.message/inbox "root"]
           profile (assoc (render/agent-render-profile
                           (support/effective-config))
                          :seon.render.profile/id :seon.render.profile/test
                          :seon.render.profile/token-budget 1024
                          :seon.render.profile/max-depth 8
                          :seon.render.profile/max-children 1
                          :seon.render.profile/composition :single-line)
           floor-unit (assoc (registered-unit connection rows)
                             :seon.render.value/root root
                             :seon.render/profile profile)
           projection (value/prepare floor-unit)
           root-elision (last (:seon.print/items
                               (:seon.render.value/tree projection)))
           ai (value/render-ai-data projection)
           html (hiccup/->string (value/render-html-data projection))]
       (is (= :seon.print/elided (:seon.print/face root-elision)))
       (is (= root (:seon.print/requery-id root-elision)))
       (is (= 2 (:seon.print/omitted root-elision)))
       (is (str/includes? ai "(get-in (seon.db/pull (quote [*]) (quote [:my.message/inbox \"root\"])) [])"))
       (is (str/includes? html "requery (get-in (seon.db/pull"))))))

(deftest a-live-list-feeds-both-render-sinks
  (let [projection
        (value/prepare
         (assoc (unit '(1 2 3))
                :seon.render.call/id [:seon.render.value-test/list]))]
    (is (= "(1 2 3)" (:seon.render.value/text projection)))
    (is (= "(1 2 3)"
           (lexical-hiccup-text
            (:seon.print/hiccup
             (print/emit-both (:seon.render.value/tree projection)
                              (:seon.render.value/options projection))))))))

(deftest anonymous-roots-refuse-instead-of-colliding
  (let [anonymous {:seon.agent/id "root"
                   :seon.render/value {:same/value 1}
                   :seon.sci.admit/caps caps}
        results [(value/render-html anonymous)
                 (value/render-html anonymous)]]
    (is (= [:seon.render.value/missing-root-identity
            :seon.render.value/missing-root-identity]
           (mapv :seon.error/kind results)))
    (is (every? #(= "A rendered value root requires a caller-supplied block id."
                    (:seon.error/message %))
                results))
    (is (not-any? vector? results)
        "no anonymous Hiccup root can carry a colliding invented id")))

(deftest caller-supplied-block-ids-are-stable-and-distinct
  (let [raw {:same/value 1}
        left (assoc (unit raw) :seon.render.call/id [:test/block-a])
        right (assoc (unit raw) :seon.render.call/id [:test/block-b])
        left-id (value/node-id left [])
        right-id (value/node-id right [])]
    (is (string? left-id))
    (is (not= left-id right-id))
    (is (= left-id (value/node-id left [])))))

(deftest value-artifact-stores-only-the-print-node-source
  (let [admitted (admit/admit
                  {:seon.sci.admit/value {:alpha [1 2 3]}
                   :seon.sci.admit/interrupt-fn (fn [])
                   :seon.sci.admit/caps caps
                   :seon.config/on-core-error :record})
        artifact (value/artifact admitted)
        stored (value/artifact-edn artifact)
        restored (value/read-artifact stored)]
    (is (contains? artifact :seon.sci.admit/print-node))
    (is (not (contains? artifact :seon.sci.admit/capped?))
        "the retired key is absent from the durable artifact")
    (is (= (:seon.sci.admit/value admitted)
           (value/artifact-value restored)))
    (is (= (:seon.sci.admit/edn admitted)
           (admit/print-node-edn (:seon.sci.admit/print-node restored))))))

(deftest profile-fit-supersedes-legacy-print-cuts-with-values
  (is (= "(1 2 3)" (value/render-ai (unit '(1 2 3)))))
  (is (str/includes?
       (value/render-ai
        (assoc (unit (vec (range 20)))
               :seon.print/options {:seon.print/width 20}))
       "\n")))

(deftest rendering-live-values-does-not-apply-a-storage-bound
  (let [raw (vec (range 100))
        request (assoc (unit raw) :seon.sci.admit/caps
                       {:seon.config.eval.result/max-bytes 1})]
    (is (str/includes? (value/render-ai request) "68 more children of 100"))
    (is (str/includes? (hiccup/->string (value/render-html request)) ">99<"))
    (is (not (str/includes? (value/render-ai request) "over-bound")))))

(deftest elision-is-a-requeryable-structural-value
  ;; THE SECOND AND THIRD BOUNDS. The AI projection is cut by the render
  ;; profile — the one place presentation elides — and the cut is ordinary
  ;; data naming what was omitted, the bound that made it, and how to ask
  ;; again. HTML is not bounded at all: the same unit serves every item.
  (let [handle 'result/e0123456789ab
        raw (vec (range 100))
        profile (render/agent-render-profile (support/effective-config))
        kept (:seon.render.profile/max-children profile)
        result-unit (assoc (unit raw) :seon.repl/handle handle)
        projection (value/prepare result-unit)
        html (hiccup/->string (value/render-html result-unit))
        elision (last (:seon.print/items
                       (:seon.render.value/tree projection)))]
    (is (= {:seon.print/omitted (- (count raw) kept)
            :seon.render.data/total (count raw)
            :seon.render.data/path []
            :seon.render.data/next-offset kept
            :seon.print/bound-by :seon.render.profile/max-children
            :seon.render.profile/id (:seon.render.profile/id profile)
            :seon.print/requery-id handle}
           (select-keys elision
                        [:seon.print/omitted
                         :seon.render.data/total
                         :seon.render.data/path
                         :seon.render.data/next-offset
                         :seon.print/bound-by
                         :seon.render.profile/id
                         :seon.print/requery-id])))
    (let [text (:seon.render.value/text projection)]
      (is (str/includes? text (str (- (count raw) kept) " more children of "
                                   (count raw))))
      (is (str/includes? text
                         "bounded by :seon.render.profile/max-children"))
      (is (str/includes? text "requery (get-in result/e0123456789ab [])")))
    (is (not (str/includes? html "seon-print-elision"))
        "HTML is not bounded at all — it elides nothing to requery")
    (is (str/includes? html ">99<")
        "the last item of the whole value reaches the page")))

(deftest references-stay-opaque
  ;; A reference is never entered: it contributes its class and nothing
  ;; else, so no private state leaks through a render. A value that is
  ;; ITSELF an opaque reference projects no data at all, and that answer is
  ;; the typed marker rather than an empty panel.
  (let [projection (value/prepare (unit {:reference (atom {:private/value 42})}))
        tree (:seon.render.value/tree projection)
        [_ reference] (first (:seon.print/entries tree))]
    (is (= #{:seon.print/face :seon.print/class :seon.render.data/path}
           (set (keys reference)))
        "an opaque reference carries no value or representation field")
    (is (= :seon.print/object (:seon.print/face reference)))
    (is (= "clojure.lang.Atom" (:seon.print/class reference)))
    (is (= "{:reference #object[clojure.lang.Atom]}"
           (value/render-ai-data projection)))
    (is (= "#object[clojure.lang.Atom]"
           (value/render-ai (unit (atom {:private/value 42}))))
        "a live reference is named even when no serialization exists")))

(deftest a-window-of-one-shows-one-item-not-an-empty-claim-of-more
  (let [window (value/window [:a :b :c] 0 1)]
    (is (= 1 (:seon.render.value/shown window)))
    (is (= [:a] (:seon.render.value/window window)))
    (is (true? (:seon.render.value/more? window)))))

(deftest a-window-beyond-a-counted-value-states-the-offset-and-length
  (let [window (value/window [:a :b :c] 9 2)]
    (is (= [] (:seon.render.value/window window)))
    (is (= 9 (:seon.render.value/offset window)))
    (is (= 3 (:seon.render.value/total window)))
    (is (true? (:seon.render.value/beyond-end? window)))))

(deftest realization-failure-is-visible-data
  ;; A value whose realization throws is not silence and not an exception
  ;; escaping the render: admission projected no data, so the answer is the
  ;; typed marker, in both projections. `window` — the paging owner the
  ;; stored-value route still calls — stays total over the same value and
  ;; keeps the failure's own message.
  (let [poison (fn [] (map (fn [_] (throw (ex-info "poison" {}))) [1]))
        text (value/render-ai (unit (poison)))
        html (hiccup/->string (value/render-html (unit (poison))))
        window (value/window (poison) 0 3)]
    (is (str/includes? text "poison"))
    (is (str/includes? html "poison"))
    (is (= :seon.render.value/window-failed
           (:seon.error/kind (:seon.render.value/window window))))
    (is (= "poison" (:seon.error/message (:seon.render.value/window window))))
    (is (zero? (:seon.render.value/shown window)))))

(def ^:private probe-profile
  {:seon.render.profile/id :seon.render.profile/test
   :seon.render.profile/token-budget 1024
   :seon.render.profile/max-depth 4
   :seon.render.profile/max-children 3
   :seon.render.profile/composition :single-line})

(defn- probe-unit [raw]
  (assoc (unit raw)
         :seon.render/profile probe-profile
         :seon.render.value/root 'result/e0123456789ab))

(deftest equal-unordered-values-render-identical-bytes
  (doseq [[left right]
          [[(array-map :z/value 3 :a/value 1 :m/value 2)
            (array-map :m/value 2 :a/value 1 :z/value 3)]
           [(into #{} [{:z 2 :a 1} {:z 4 :a 3}])
            (into #{} [(array-map :a 3 :z 4) (array-map :a 1 :z 2)])]]]
    (is (= left right))
    (is (= (value/render-ai (probe-unit left))
           (value/render-ai (probe-unit right))))
    (is (= (value/render-html (probe-unit left))
           (value/render-html (probe-unit right))))))

(deftest ai-never-visits-the-omitted-tail
  (let [visits (atom [])
        raw (map (fn [i] (swap! visits conj i) i) (iterate inc 0))
        text (value/render-ai (probe-unit raw))]
    (is (str/includes? text "0 1 2"))
    (is (str/includes? text "bounded by :seon.render.profile/max-children"))
    (is (<= (count @visits) 4)
        "only the retained children and one lookahead are realized")))

(deftest strings-are-quoted-and-their-cut-path-is-associative
  (let [raw "a\n\"b\\c\t"
        text (value/render-ai (probe-unit raw))
        large (.repeat "x\n" 10000)
        request (probe-unit {:payload/text large})
        node (-> (value/prepare request) :seon.render.value/tree
                 :seon.print/entries first second)]
    (is (= (pr-str raw) text))
    (is (not (str/includes? text "\n")))
    (is (= [:payload/text] (:seon.render.data/path node)))
    (is (= '(get-in result/e0123456789ab [:payload/text])
           (:seon.print/requery-form node)))
    (is (= (count large) (:seon.render.data/total node)))
    (is (pos? (:seon.print/omitted node)))
    (is (= (pr-str large)
           (lexical-hiccup-text (value/render-html (probe-unit large)))))))

(deftest depth-cuts-name-the-bound-and-html-keeps-the-leaf
  (let [raw {:a {:b {:c {:d {:e "last-leaf"}}}}}
        request (probe-unit raw)
        ai (value/render-ai request)
        html (hiccup/->string (value/render-html request))]
    (is (str/includes? ai "depth 4"))
    (is (str/includes? ai "bounded by :seon.render.profile/max-depth"))
    (is (str/includes? ai "[:a :b :c :d]"))
    (is (str/includes? html "last-leaf"))
    (is (not (str/includes? html "seon-print-elision")))))

(deftest default-entity-map-renders-refs-as-installed-identities
  (support/with-database
   (fn [connection]
     (let [database @connection
           target (db/pull database '[:db/id :seon.ns/name] [:seon.ns/name 'seon.print])
           raw {:seon.ns/requires [(:db/id target)] :fixture/title "no render pair"}
           request (assoc (probe-unit raw) :seon.db/db database)
           shown (edn/read-string (value/render-ai request))]
       (is (:db/id target) "the reference target must really exist")
       (is (= #{[:seon.ns/name 'seon.print]} (:seon.ns/requires shown)))
       (is (= "no render pair" (:fixture/title shown)))))))

(deftest an-explicit-pull-keeps-its-nested-shape-in-shown-text
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "shape"})
     (let [written (db/transact!
                    connection
                    [[:db/add "cluster" :seon.cluster/name "shape"]
                     {:seon.agent/id "shape" :seon.agent/namespace [:seon.ns/name 'seon.print]}
                     [:db/add [:seon.ns/name 'seon.print] :seon.ns/steward [:seon.agent/id "shape"]]])
           _ (is (:db-after written) (pr-str written))
           ctx (support/fork-cluster-ctx connection "shape")
           configuration (support/effective-config)
           result (evaluation/evaluate
                   {:seon.cluster.eval/source
                    "(seon.db/pull '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}] [:seon.agent/id \"shape\"])"
                    :seon.sci.eval/ctx ctx :seon.db/db @connection
                    :seon.sci.admit/caps (config/result-caps configuration)
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                    :seon.config/on-core-error :panic})
           shown (:seon.eval/shown result)
           parsed (edn/read-string shown)]
       (is (not (:seon.cluster.eval/error result)) (pr-str result))
       (is (= "shape" (get-in parsed [:seon.agent/namespace :seon.ns/steward :seon.agent/id])) shown)
       (is (= (:seon.sci.admit/value result) parsed) shown)))))

(deftest large-values-have-bounded-ai-work-and-complete-html
  (doseq [[label raw terminal]
          [[:vector (vec (range 100000)) ">99999<"]
           [:string (.repeat "x" (* 5 1024 1024)) nil]]]
    (let [request (probe-unit raw)
          _ (dotimes [_ 3] (value/render-ai request))
          start (System/nanoTime)
          ai (value/render-ai request)
          middle (System/nanoTime)
          html (value/render-html request)
          end (System/nanoTime)
          ai-ms (/ (- middle start) 1e6)
          html-ms (/ (- end middle) 1e6)]
      (println "VALUE-RENDERER" label "AI-ms" ai-ms "HTML-ms" html-ms)
      (is (< ai-ms 100.0) (str label " AI took " ai-ms " ms"))
      (is (str/includes? ai "requery (get-in result/e0123456789ab [])"))
      (if terminal
        (is (str/includes? (hiccup/->string html) terminal))
        (is (= (pr-str raw) (lexical-hiccup-text html)))))))

(deftest elision-forms-execute-in-the-real-sci-context
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           raw {:payload/text (.repeat "x" 20000)}
           node (-> (value/prepare (probe-unit raw)) :seon.render.value/tree
                    :seon.print/entries first second)
           _ (sci/eval-form ctx '(create-ns 'result))
           _ (sci/intern ctx 'result 'e0123456789ab raw)]
       (is (= (:payload/text raw)
              (sci/eval-form ctx (:seon.print/requery-form node))))
       (let [cut (print/elision
                  {:seon.print/requery-id [:seon.ns/name 'seon.print]
                   :seon.render.data/path [:seon.ns/name]
                   :seon.render.data/total 1
                   :seon.print/omitted 1
                   :seon.print/bound-by :seon.render.profile/max-depth})]
         (is (= 'seon.print (support/agent-value ctx (pr-str (:seon.print/requery-form cut))))))))))

(deftest caller-print-bindings-do-not-change-string-bytes
  (let [raw "quoted \"line\"\nnext\t\\"
        expected (pr-str raw)]
    (binding [*print-readably* false *print-length* 1 *print-level* 1]
      (is (= expected (value/render-ai (probe-unit raw)))))))
