;; why: Your task: what to change and why. (~339 tokens)
user=> (seon.db/pull
  (seon.db/db)
  [:seon.task/id
   :seon.task/ns
   :seon.task/name
   :seon.task/kind
   :seon.task/intent
   :seon.task/must-read
   :seon.task/evidence]
  [:seon.task/id "135d92ea1308"])
#:seon.task{:id "135d92ea1308",
 :ns seon.render,
 :kind :function,
 :must-read [seon.render/render-call],
 :name "a-renderer-is-chosen-by-key-presence",
 :intent
   "A plain value is rendered through an entity's declared renderer because it contains that entity's key: (select-keys issue [:seon.issue/id :seon.issue/status]) printed 'Issue ... still open.'; a map with :seon.turn/id 12 went through the turn renderer, which refused, and the evaluation was reported as an exception (7 KB, value printed twice); a schema definition whose properties hold :seon.render/ai renders its properties map through that renderer (about 10 KB of context dumped three times). Select by schema validity, and a render refusal is not an evaluation failure.",
 :evidence
   ["docs/research/agent-platform/dogfood-author-opus-2026-09-26.md"
    "docs/research/agent-platform/dogfood-report-2026-09-26.md"
    "docs/seon/issues/a-plain-value-with-an-entity-key-renders-through-that-entitys-renderer-and-fails-the-evaluation.md"
    "docs/seon/issues/schema-rows-render-through-the-pair-they-declare.md"]}

;; why: The task's edges: the proof tests that define done, the subjects it names, and who must read them. (~66 tokens)
user=> (seon.task/task-edges (seon.db/db) "135d92ea1308")
#:seon.task{:subjects #{},
 :proofs
   #{seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it
     seon.render.value-test/a-failing-renderer-never-replaces-the-value},
 :referrers #{}}

;; why: A proof test: the task is done when every proof is green on your branch. Its latest recorded result. (~102 tokens)
user=> (seon.test/recorded-result
  (seon.db/db)
  'seon.render.value-test/a-failing-renderer-never-replaces-the-value)
{:seon.error/at #inst "2026-10-07T05:29:31.197Z",
 :seon.error/layer :seon.test/execution,
 :seon.error/operation seon.test/unknown,
 :seon.test/next-tier :none,
 :seon.test/unknown
   "seon.render.value-test/a-failing-renderer-never-replaces-the-value",
 :seon.error/message "No admitted execution is recorded for this test."}

;; why: The proof's source: what it asserts. (~483 tokens)
user=> (clojure.repl/source-fn 'seon.render.value-test/a-failing-renderer-never-replaces-the-value)
"(deftest ^{:seon/tasks #{\"135d92ea1308\"}} a-failing-renderer-never-replaces-the-value\n  ;; AGENTS.md, the REPL path is total: a renderer that throws or refuses\n  ;; leaves its value printed as data, with ONE diagnostic line beside it for\n  ;; the whole value, and the failures returned for the caller to record.\n  (support/with-database\n   (fn [connection]\n     (let [boom (fn [_] (throw (ex-info \"boom\" {})))\n           prepared (value/prepare (render-request connection {:seon.render/ai boom :a 1 :b {:c 2}}))\n           shown (:seon.render.value/text prepared)\n           {diagnostics true value-lines false}\n           (group-by #(str/starts-with? % \";; renderer failed\") (str/split-lines shown))\n           ;; Where the value breaks depends on its compiled fn class name's\n           ;; width; the value printed as data is what matters, not its layout.\n           value-text (str/replace (str/join \" \" value-lines) #\"\\s+\" \" \")\n           nested (value/prepare (render-request connection [{:seon.render/ai boom :a 1}]))]\n       (is (str/includes? value-text \":a 1\"))\n       (is (str/includes? value-text \":b {:c 2}\"))\n       (is (str/includes? value-text \":seon.render/ai #object[\"))\n       (is (= 1 (count diagnostics)))\n       (is (str/starts-with? (first diagnostics) \";; renderer failed at []\"))\n       (is (= 1 (count (:seon.render.value/diagnostics prepared))))\n       (is (nil? (:seon.render.value/diagnostics nested))\n           \"a nested render is never invoked, so it cannot fail\")))))"

;; why: A proof test: the task is done when every proof is green on your branch. Its latest recorded result. (~107 tokens)
user=> (seon.test/recorded-result
  (seon.db/db)
  'seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it)
{:seon.error/at #inst "2026-10-07T05:29:31.201Z",
 :seon.error/layer :seon.test/execution,
 :seon.error/operation seon.test/unknown,
 :seon.test/next-tier :none,
 :seon.test/unknown
   "seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it",
 :seon.error/message "No admitted execution is recorded for this test."}

;; why: The proof's source: what it asserts. (~353 tokens)
user=> (clojure.repl/source-fn
  'seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it)
"(deftest ^{:seon/tasks #{\"135d92ea1308\"}} a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it\n  ;; Smoke 2026-09-26: `(sorted-map 'b 2 'a 1)` printed a ClassCastException\n  ;; from the renderer's keyword lookup in place of the map. Only a map\n  ;; `keyword-lookup?` permits carries an attachment or an identity; a keyword\n  ;; sorted map still carries its own face.\n  (support/with-database\n   (fn [connection]\n     (let [prepared #(value/prepare (render-request connection %))\n           text #(:seon.render.value/text (prepared %))]\n       (is (= \"[42 {a 1, b 2}]\" (text [42 (sorted-map 'b 2 'a 1)])))\n       (is (= \"{a 1, b 2}\" (text (sorted-map 'b 2 'a 1))))\n       (is (= \"{:k {\\\"x\\\" 1}}\" (text {:k (sorted-map \"x\" 1)})))\n       (is (= \"{z 1}\" (text (sorted-map-by compare 'z 1))))\n       (is (nil? (:seon.render.value/diagnostics (prepared (sorted-map 'b 2 'a 1)))))\n       (is (= \"custom face\" (text (sorted-map :seon.render/ai \"custom face\" :x 1))))\n       (is (false? (value/keyword-lookup? (sorted-map 'a 1))))\n       (is (true? (value/keyword-lookup? (sorted-map :a 1))))))))"

;; why: A function the task names: its documentation and contract. (~625 tokens)
user=> (doc seon.render/render-call)
{:body "",
 :example "",
 :supplied [:seon.db/db :seon.sci.eval/ctx],
 :summary
   "Reuse one retained projection while its input, code, and reads are current.",
 :arglists
   ([{database :seon.db/db,
      output :seon.render/output,
      call-id :seon.render.call/id,
      retained-calls :seon.render/retained-calls,
      captured-calls :seon.render/captured-calls,
      candidate-call-ids :seon.render/candidate-call-ids,
      :as request}]),
 :in
   [:cat
    [:map
     #:seon.render{:ai seon.render.value/render-ai,
      :html seon.render.value/render-html}
     [:seon.render/namespace {:optional true} :seon.render/namespace]
     [:seon.render/value :seon.render/value]
     [:seon.sci.admit/caps :seon.sci.admit/caps]
     [:seon.sci.eval/time-limit-ms :seon.sci.eval/time-limit-ms]
     [:seon.render/ai {:optional true} :qualified-symbol]
     [:seon.render/html {:optional true} :qualified-symbol]
     [:seon.render/form {:optional true} :qualified-symbol]
     [:seon.render/selection-inspection {:optional true} [:map]]
     [:seon.render/retained-calls
      {:optional true}
      [:map-of
       {:gen/return {}}
       :seon.render.call/id
       :seon.render.call/entry]]
     [:seon.render/captured-calls
      {:optional true}
      :seon.call-preparation/state]
     [:seon.render/invocations
      {:optional true}
      [:map-of
       {:gen/return {}}
       [:vector :seon.schema/value]
       [:vector [:map]]]]
     [:seon.render/captured-invocations
      {:optional true}
      :seon.call-preparation/state]
     [:seon.render/candidate-call-ids
      {:optional true}
      [:set :seon.render.call/id]]
     [:seon.render.call/id {:optional true} :seon.render.call/id]
     [:seon.render.call/selected-producer
      {:optional true}
      :qualified-symbol]
     [:seon.render.call/source-output? {:optional true} :boolean]
     [:seon.render.call/source-run-id {:optional true} :seon.turn/id]]],
 :out [:or :nil :seon.render/rendered :seon.render/error-result]}

;; why: Its stored source, the code to change. (~656 tokens)
user=> (clojure.repl/source-fn 'seon.render/render-call)
"(defn render-call\n  \"Reuse one retained projection while its input, code, and reads are current.\"\n  {:seon/tasks #{\"c5e9496b4cb1\"}\n   :malli/schema [:=> [:cat :seon.render/call-request]\n                  [:or :nil :seon.render/rendered :seon.render/error-result]]}\n  [{database :seon.db/db\n    output :seon.render/output\n    call-id :seon.render.call/id\n    retained-calls :seon.render/retained-calls\n    captured-calls :seon.render/captured-calls\n    candidate-call-ids :seon.render/candidate-call-ids\n    :as request}]\n  (let [profile (request-profile request)]\n    (if (:seon.render/refused-member profile)\n      profile\n      (let [request (assoc request :seon.render/profile profile)\n            previous (when (and call-id retained-calls)\n                       (get retained-calls call-id))\n            same-database? (and previous\n                                (same-committed-database? database (:seon.db/db previous)))\n            check-read-evidence?\n            (and (not same-database?)\n                 (or (nil? candidate-call-ids)\n                     (contains? candidate-call-ids call-id)))\n            previous-selected\n            (get-in previous [:seon.render.call/static-evidence\n                              :seon.render.call/producer])\n            fast-evidence (when previous-selected\n                            (call-cache-evidence request previous-selected))\n            fast-reusable?\n            (and previous\n                 (same-call-cache-evidence? previous fast-evidence)\n                 (or (not check-read-evidence?)\n                     (db/read-evidence-current?\n                      database (:seon.render.call/read-evidence previous))))]\n        (if fast-reusable?\n          (let [entry (merge (if check-read-evidence?\n                               (refresh-read-evidence database previous)\n                               previous)\n                             fast-evidence)]\n            (when (and call-id captured-calls)\n              (swap! captured-calls assoc call-id entry))\n            (:seo"…+7736

;; why: A schema the contracts above name: the shape of the data in and out. (~427 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.render/call-request])
{:seon.schema/form
   "[:map {:seon.render/ai seon.render.value/render-ai, :seon.render/html seon.render.value/render-html} [:seon.db/db :seon.db/database-value] [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:seon.render/namespace {:optional true} :seon.render/namespace] [:seon.render/value :seon.render/value] [:seon.sci.admit/caps :seon.sci.admit/caps] [:seon.sci.eval/time-limit-ms :seon.sci.eval/time-limit-ms] [:seon.render/ai {:optional true} :qualified-symbol] [:seon.render/html {:optional true} :qualified-symbol] [:seon.render/form {:optional true} :qualified-symbol] [:seon.render/selection-inspection {:optional true} [:map]] [:seon.render/retained-calls {:optional true} [:map-of {:gen/return {}} :seon.render.call/id :seon.render.call/entry]] [:seon.render/captured-calls {:optional true} :seon.call-preparation/state] [:seon.render/invocations {:optional true} [:map-of {:gen/return {}} [:vector :seon.schema/value] [:vector [:map]]]] [:seon.render/captured-invocations {:optional true} :seon.call-preparation/state] [:seon.render/candidate-call-ids {:optional true} [:set :seon.render.call/id]] [:seon.render.call/id {:optional true} :seon.render.call/id] [:seon.render.call/selected-producer {:optional true} :qualified-symbol] [:seon.render.call/source-output? {:optional true} :boolean] [:seon.render.call/source-run-id {:optional true} :seon.turn/id]]"}

;; why: A schema the contracts above name: the shape of the data in and out. (~31 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.render/rendered])
{:seon.schema/form
   "[:or :string :seon.render/hiccup :seon.render/read-form :seon.render/ai-data]"}

;; why: A schema the contracts above name: the shape of the data in and out. (~68 tokens)
user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.render/error-result])
{:seon.schema/form
   "[:or :seon.render/request-error :seon.render/ambiguous-error :seon.render/invalid-output-error :seon.render/unknown :seon.render.transcript/request-error :seon.config/error :seon.db/error-result]"}

;; why: Other tasks naming the same functions: work that may overlap or conflict. (~910 tokens)
user=> (seon.db/q
  '[:find
    [(pull ?t [:seon.task/id :seon.task/ns :seon.task/name :seon.task/intent]) ...]
    :in
    $
    [?f ...]
    ?h
    :where
    [?t :seon.task/must-read ?f]
    [?t :seon.task/id ?id]
    [(not= ?id ?h)]]
  (seon.db/db)
  '[seon.render/render-call]
  "135d92ea1308")
[#:seon.task{:id "407f7d0a84c6",
  :ns seon.render,
  :name "a-cold-prompt-renders-every-evaluation-again",
  :intent
    "Each turn's first prompt renders every stored evaluation of the agent again through seon.render/render-call: 149 evaluations of root took 0.33-0.59 s armed after the plan decode and call-reach fixes (turn-overhead lane, default, 2026-09-27), and the cost grows with history although each evaluation's shown text is stored and unchanged. A render is cached by the value's content digest (AGENTS.md Presentation); reuse across commits, so a turn renders only the evaluations it has not shown."}
 #:seon.task{:id "76e1261553f3",
  :ns seon.render,
  :name "cold-renders-recompute-read-evidence",
  :intent
    "A cold task-rooted debug page (48 nodes) made 2.3 M canonical-value-string calls and spent 1.1 s in seon.db/read-evidence for 115 render calls; root at n=2 costs 2.25 s and 6.8 GB cold. Render cost must follow what changed."}
 #:seon.task{:id "7a86dd51ca51",
  :ns seon.render,
  :name "a-render-against-another-database-reads-the-context-custody",
  :intent
    "render-call with an explicit :seon.db/db of another branch runs its renderer's (seon.db/q ...) against the forked context's custody, not the request's database: with the same query renderer written on both branches, retained-test equal-committed-database-s"…+230}
 #:seon.task{:id "991ce24cca22",
  :ns seon.db,
  :name "call-reach-is-recomputed-per-render",
  :intent
    "stored-call-reach is recomputed on every render call (about 4 ms each; 2.2 GB in one debug-page window). Memoize it per commit and call identity."}
 #:seon.task{:id "d7e9cd817cf7",
  :ns seon.render,
  :name "an-unchanged-retained-call-selects-and-invokes-again",
  :intent
    "render-simplification-test unchanged-retained-call-skips-discovery-and-invocation: over two identical render-call requests (the "…+405}
 #:seon.task{:id "c5e9496b4cb1",
  :ns seon.render,
  :name "captured-invocations-blank-source-faces",
  :intent
    "Passing :seon.render/captured-invocations to render-call makes every source-producing AI face return nil (render.clj ~1573 retur"…+122}
 #:seon.task{:id "55974f161f71",
  :ns seon.render.web,
  :name "the-render-cache-keeps-every-debug-view",
  :intent
    "The environment's :seon.render/cache atom holds 69.8 MB at idle with no eviction: ::graph-calls 36.7 MB keeps every debug-page v"…+272}
 #:seon.task{:id "ec837ee33e82",
  :ns seon.render,
  :name "a-transaction-report-prints-its-runs-bookkeeping",
  :intent
    "An agent's (seon.db/transact! [...]) answers the raw datom list of the transaction it rode in, including its own run's turn and "…+238}
 #:seon.task{:id "adabf08dc78e",
  :ns seon.render,
  :name "unit-sweep-retyped-non-map-render-inputs",
  :intent
    "b195ae037's scripted sweep retyped render functions' first input to :seon.render/unit (a map). A render function whose body and "…+356}]
