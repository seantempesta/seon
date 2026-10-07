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

user=> (seon.task/task-edges (seon.db/db) "135d92ea1308")
#:seon.task{:subjects #{},
 :proofs
   #{seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it
     seon.render.value-test/a-failing-renderer-never-replaces-the-value},
 :referrers #{}}

user=> (seon.test/recorded-result
  (seon.db/db)
  'seon.render.value-test/a-failing-renderer-never-replaces-the-value)
{:seon.error/at #inst "2026-10-07T02:28:50.722Z",
 :seon.error/layer :seon.test/execution,
 :seon.error/operation seon.test/unknown,
 :seon.test/next-tier :none,
 :seon.test/unknown
   "seon.render.value-test/a-failing-renderer-never-replaces-the-value",
 :seon.error/message "No admitted execution is recorded for this test."}

user=> (clojure.repl/source-fn 'seon.render.value-test/a-failing-renderer-never-replaces-the-value)
(deftest ^{:seon/tasks #{"135d92ea1308"}} a-failing-renderer-never-replaces-the-value
  ;; AGENTS.md, the REPL path is total: a renderer that throws or refuses
  ;; leaves its value printed as data, with ONE diagnostic line beside it for
  ;; the whole value, and the failures returned for the caller to record.
  (support/with-database
   (fn [connection]
     (let [boom (fn [_] (throw (ex-info "boom" {})))
           prepared (value/prepare (render-request connection {:seon.render/ai boom :a 1 :b {:c 2}}))
           shown (:seon.render.value/text prepared)
           {diagnostics true value-lines false}
           (group-by #(str/starts-with? % ";; renderer failed") (str/split-lines shown))
           ;; Where the value breaks depends on its compiled fn class name's
           ;; width; the value printed as data is what matters, not its layout.
           value-text (str/replace (str/join " " value-lines) #"\s+" " ")
           nested (value/prepare (render-request connection [{:seon.render/ai boom :a 1}]))]
       (is (str/includes? value-text ":a 1"))
       (is (str/includes? value-text ":b {:c 2}"))
       (is (str/includes? value-text ":seon.render/ai #object["))
       (is (= 1 (count diagnostics)))
       (is (str/starts-with? (first diagnostics) ";; renderer failed at []"))
       (is (= 1 (count (:seon.render.value/diagnostics prepared))))
       (is (nil? (:seon.render.value/diagnostics nested))
           "a nested render is never invoked, so it cannot fail")))))

user=> (seon.test/recorded-result
  (seon.db/db)
  'seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it)
{:seon.error/at #inst "2026-10-07T02:28:50.727Z",
 :seon.error/layer :seon.test/execution,
 :seon.error/operation seon.test/unknown,
 :seon.test/next-tier :none,
 :seon.test/unknown
   "seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it",
 :seon.error/message "No admitted execution is recorded for this test."}

user=> (clojure.repl/source-fn
  'seon.render.value-test/a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it)
(deftest ^{:seon/tasks #{"135d92ea1308"}} a-map-that-cannot-hold-keywords-prints-as-clojure-prints-it
  ;; Smoke 2026-09-26: `(sorted-map 'b 2 'a 1)` printed a ClassCastException
  ;; from the renderer's keyword lookup in place of the map. Only a map
  ;; `keyword-lookup?` permits carries an attachment or an identity; a keyword
  ;; sorted map still carries its own face.
  (support/with-database
   (fn [connection]
     (let [prepared #(value/prepare (render-request connection %))
           text #(:seon.render.value/text (prepared %))]
       (is (= "[42 {a 1, b 2}]" (text [42 (sorted-map 'b 2 'a 1)])))
       (is (= "{a 1, b 2}" (text (sorted-map 'b 2 'a 1))))
       (is (= "{:k {\"x\" 1}}" (text {:k (sorted-map "x" 1)})))
       (is (= "{z 1}" (text (sorted-map-by compare 'z 1))))
       (is (nil? (:seon.render.value/diagnostics (prepared (sorted-map 'b 2 'a 1)))))
       (is (= "custom face" (text (sorted-map :seon.render/ai "custom face" :x 1))))
       (is (false? (value/keyword-lookup? (sorted-map 'a 1))))
       (is (true? (value/keyword-lookup? (sorted-map :a 1))))))))

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

user=> (clojure.repl/source-fn 'seon.render/render-call)
(defn render-call
  "Reuse one retained projection while its input, code, and reads are current."
  {:seon/tasks #{"c5e9496b4cb1"}
   :malli/schema [:=> [:cat :seon.render/call-request]
                  [:or :nil :seon.render/rendered :seon.render/error-result]]}
  [{database :seon.db/db
    output :seon.render/output
    call-id :seon.render.call/id
    retained-calls :seon.render/retained-calls
    captured-calls :seon.render/captured-calls
    candidate-call-ids :seon.render/candidate-call-ids
    :as request}]
  (let [profile (request-profile request)]
    (if (:seon.render/refused-member profile)
      profile
      (let [request (assoc request :seon.render/profile profile)
            previous (when (and call-id retained-calls)
                       (get retained-calls call-id))
            same-database? (and previous
                                (same-committed-database? database (:seon.db/db previous)))
            check-read-evidence?
            (and (not same-database?)
                 (or (nil? candidate-call-ids)
                     (contains? candidate-call-ids call-id)))
            previous-selected
            (get-in previous [:seon.render.call/static-evidence
                              :seon.render.call/producer])
            fast-evidence (when previous-selected
                            (call-cache-evidence request previous-selected))
            fast-reusable?
            (and previous
                 (same-call-cache-evidence? previous fast-evidence)
                 (or (not check-read-evidence?)
                     (db/read-evidence-current?
                      database (:seon.render.call/read-evidence previous))))]
        (if fast-reusable?
          (let [entry (merge (if check-read-evidence?
                               (refresh-read-evidence database previous)
                               previous)
                             fast-evidence)]
            (when (and call-id captured-calls)
              (swap! captured-calls assoc call-id entry))
            (:seon.render.call/output previous))
          (let [requested-candidate
                (:seon.render.call/selected-producer request)
                decision (or (:seon.render/selection-inspection request)
                             (if requested-candidate
                               (selection-inspection request)
                               (selection request)))
                selected
                (if requested-candidate
                  (if (compatible-selection-candidate? decision
                                                       requested-candidate)
                    requested-candidate
                    (let [observation
                          (vary-meta (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/render 'seon.render/render-call
                           {:seon.error/message "The requested renderer is not an applicable candidate."
                           :seon.error/fix "Supply the expected member and repeat the requested operation."
                           :seon.render/refused-member :seon.render.call/selected-producer
                           :seon.error/expected :compatible
                           :seon.error/offending requested-candidate
                           :seon.render/selection-observation decision}) assoc :seon.error/declared-schema :seon.render/request-error)]
                      observation))
                  (:seon.render.selection/selected decision))]
            (if (or (:seon.render/refused-member selected) (:seon.render/candidates selected) (:seon.render.unknown/reason selected))
              (vary-meta (assoc selected :seon.render/refused-member :seon.render.call/selected-producer) assoc :seon.error/declared-schema :seon.render/request-error)
              (let [static-evidence (call-static-evidence request decision
                                                          selected)
                    ;; the fast path's evidence already describes this producer
                    cache-evidence (if (and fast-evidence (= selected previous-selected))
                                     fast-evidence
                                     (call-cache-evidence request selected))
                    invocation-key (invocation-cache-key request selected
                                                         cache-evidence)
                    retained-invocations (:seon.render/invocations request)
                    captured-invocations
                    (:seon.render/captured-invocations request)
                    retained-invocation
                    (reusable-invocation database
                                         (get retained-invocations
                                              invocation-key)
                                         cache-evidence)
                    reusable? (and previous
                                   (same-call-cache-evidence?
                                    previous cache-evidence)
                                   (= static-evidence
                                      (:seon.render.call/static-evidence
                                       previous))
                                   (or (not check-read-evidence?)
                                       (db/read-evidence-current?
                                        database
                                        (:seon.render.call/read-evidence
                                         previous))))
                    captured (atom [])
                    invocation-reusable? (some? retained-invocation)
                    ;; Source intent is read against the argument the producer
                    ;; will actually receive: an attribute-declared producer
                    ;; takes the attribute's value, not the unit map.
                    source-output?
                    (and (= output :seon.render/ai)
                         (let [projection (request-projection request)]
                           (source-producer?
                            projection selected
                            [(render-invocation-argument projection request
                                                         selected)])))
                    raw (if invocation-reusable?
                          (or (when (:seon.render/source-blocks retained-invocation)
                                (select-keys retained-invocation [:seon.render/source-blocks]))
                              (:seon.render.call/source retained-invocation)
                              (:seon.render.call/output retained-invocation))
                          (raw-output
                           (assoc request
                                  :seon.render.call/selected-producer selected
                                  :seon.render.call/captured-reads captured)
                           output selected))
                    raw (if (or (:seon.render/refused-member raw)
                                (:seon.render.unknown/reason raw)
                                (:seon.render/invalid-output raw))
                          (vary-meta (assoc raw :seon.render/refused-member :seon.render/output) assoc :seon.error/declared-schema :seon.render/request-error)
                          raw)
                    source-blocks (when source-output? (:seon.render/source-blocks raw))
                    raw (if source-blocks
                          (clojure.string/join "\n\n" (map :seon.render/source source-blocks)) raw)
                    authored-source? (and source-output? (string? raw))
                    invocation-entry
                    (merge
                     (if invocation-reusable?
                       (refresh-read-evidence database retained-invocation)
                       (cond->
                        {:seon.render.call/read-evidence
                        (db/read-evidence @captured)
                        :seon.render.call/basis-transaction
                        (db/basis-t database)
                         :seon.render.call/output
                         (when-not source-output? raw)}
                         authored-source?
                         (assoc :seon.render.call/source raw)
                         source-blocks
                         (assoc :seon.render/source-blocks source-blocks)

                         (:seon.render.call/source-run-id request)
                         (assoc :seon.render.call/source-run-id
                                (:seon.render.call/source-run-id request))))
                     cache-evidence)
                    _ (retain-invocation!
                       captured-invocations invocation-key
                       (get retained-invocations invocation-key)
                       invocation-entry)
                    rendered (if authored-source?
                               (if captured-invocations
                                 (:seon.render.call/output invocation-entry)
                                 raw)
                               (if reusable?
                               (:seon.render.call/output previous)
                               raw))
                    entry (assoc
                           (merge
                            (when reusable?
                              (if check-read-evidence?
                                (refresh-read-evidence database previous)
                                previous))
                            invocation-entry)
                           :seon.render.call/static-evidence static-evidence
                           :seon.render.call/output rendered
                           :seon.render.call/invocation-key invocation-key)]
            (when (and call-id captured-calls)
              (swap! captured-calls assoc call-id entry))
            rendered))))))))

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.render/call-request])
{:seon.schema/form
   "[:map {:seon.render/ai seon.render.value/render-ai, :seon.render/html seon.render.value/render-html} [:seon.db/db :seon.db/database-value] [:seon.sci.eval/ctx :seon.sci.eval/ctx] [:seon.render/namespace {:optional true} :seon.render/namespace] [:seon.render/value :seon.render/value] [:seon.sci.admit/caps :seon.sci.admit/caps] [:seon.sci.eval/time-limit-ms :seon.sci.eval/time-limit-ms] [:seon.render/ai {:optional true} :qualified-symbol] [:seon.render/html {:optional true} :qualified-symbol] [:seon.render/form {:optional true} :qualified-symbol] [:seon.render/selection-inspection {:optional true} [:map]] [:seon.render/retained-calls {:optional true} [:map-of {:gen/return {}} :seon.render.call/id :seon.render.call/entry]] [:seon.render/captured-calls {:optional true} :seon.call-preparation/state] [:seon.render/invocations {:optional true} [:map-of {:gen/return {}} [:vector :seon.schema/value] [:vector [:map]]]] [:seon.render/captured-invocations {:optional true} :seon.call-preparation/state] [:seon.render/candidate-call-ids {:optional true} [:set :seon.render.call/id]] [:seon.render.call/id {:optional true} :seon.render.call/id] [:seon.render.call/selected-producer {:optional true} :qualified-symbol] [:seon.render.call/source-output? {:optional true} :boolean] [:seon.render.call/source-run-id {:optional true} :seon.turn/id]]"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.render/rendered])
{:seon.schema/form
   "[:or :string :seon.render/hiccup :seon.render/read-form :seon.render/ai-data]"}

user=> (seon.db/pull (seon.db/db) [:seon.schema/form] [:seon.schema/key :seon.render/error-result])
{:seon.schema/form
   "[:or :seon.render/request-error :seon.render/ambiguous-error :seon.render/invalid-output-error :seon.render/unknown :seon.render.transcript/request-error :seon.config/error :seon.db/error-result]"}

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
  [seon.render/render-call]
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
    "render-call with an explicit :seon.db/db of another branch runs its renderer's (seon.db/q ...) against the forked context's custody, not the request's database: with the same query renderer written on both branches, retained-test equal-committed-database-skips-read-replay renders \"one\" (the first branch's shown text) for the other database, retained calls or not (probed on default 2026-09-27). A renderer's omitted database must be supplied from the world of the database it renders."}
 #:seon.task{:id "991ce24cca22",
  :ns seon.db,
  :name "call-reach-is-recomputed-per-render",
  :intent
    "stored-call-reach is recomputed on every render call (about 4 ms each; 2.2 GB in one debug-page window). Memoize it per commit and call identity."}
 #:seon.task{:id "d7e9cd817cf7",
  :ns seon.render,
  :name "an-unchanged-retained-call-selects-and-invokes-again",
  :intent
    "render-simplification-test unchanged-retained-call-skips-discovery-and-invocation: over two identical render-call requests (the second handed the first's retained calls) the armed counters read 2 selections and 2 invocations where 1 and 1 are expected, and every later step is one higher; the renderer calls a private helper (cache-helper). The same pattern without a helper reuses (0 on the second call). A helper written only in the SCI context no longer changes the output (H1 stays), since the database's program is what renders."}
 #:seon.task{:id "c5e9496b4cb1",
  :ns seon.render,
  :name "captured-invocations-blank-source-faces",
  :intent
    "Passing :seon.render/captured-invocations to render-call makes every source-producing AI face return nil (render.clj ~1573 returns the invocation's :seon.render.call/output, nil for source output): the same arguments change the meaning of the output."}
 #:seon.task{:id "55974f161f71",
  :ns seon.render.web,
  :name "the-render-cache-keeps-every-debug-view",
  :intent
    "The environment's :seon.render/cache atom holds 69.8 MB at idle with no eviction: ::graph-calls 36.7 MB keeps every debug-page view forever (four keys at 12.6, 12.6, 7.9 and 3.6 MB), ::invocations 27.5 MB, and entries retain read results. The debug page costs 3.13 s cold and 1.74 s warm at n=2; its n=2 load (14.8 s) preceded the 10:10 OOM. Retain read evidence without results, and bound the cache."}
 #:seon.task{:id "ec837ee33e82",
  :ns seon.render,
  :name "a-transaction-report-prints-its-runs-bookkeeping",
  :intent
    "An agent's (seon.db/transact! [...]) answers the raw datom list of the transaction it rode in, including its own run's turn and evaluation rows and receipt (about 1,000 tokens for a two-attribute write, repl-dogfood 2026-09-27). A transaction report renders what the caller's tx-data changed (entities by identity, retracted facts), not the settlement's bookkeeping."}
 #:seon.task{:id "adabf08dc78e",
  :ns seon.render,
  :name "unit-sweep-retyped-non-map-render-inputs",
  :intent
    "b195ae037's scripted sweep retyped render functions' first input to :seon.render/unit (a map). A render function whose body and AI pair take a vector or lookup ref (seon.cluster.message/render-inbox-html, restored d55f390cf) then refused every real input. Audit every render function's first input: compare its declared schema with the values its callers and its pair actually pass (at the REPL on real values), and restore the owner's own declared schema where the sweep replaced it."}]
