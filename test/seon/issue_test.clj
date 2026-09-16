(ns seon.issue-test
  (:require [clojure.test]
            [clojure.core.async]
            [clojure.java.io]
            [clojure.string]
            [seon.cluster]
            [seon.cluster.agent]
            [seon.db]
            [seon.eval]
            [seon.flow]
            [seon.id]
            [seon.issue]
            [seon.turn]
            [seon.test-support]))

(clojure.test/deftest indexed-issues-replace-facts-and-retain-identities
 (seon.test-support/with-database
  (fn [connection]
   (let [wanted #{"class-classification-is-inferred-from-hand-lists.md"
                   "agent-form-calls-to-core-namespaces-are-not-indexed.md"
                   "a-search-contract-predicate-cannot-be-made-durable.md"}
         selected-real (filterv #(contains? wanted (.getName (clojure.java.io/file (:seon.issue/path %))))
                                (seon.issue/notes "."))
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected-real})
         d (seon.db/db connection)
         member (seon.db/pull d '[:seon.issue/commits {:seon.issue/tests [:seon.test/sym]}]
                             [:seon.issue/id "agent-form-calls-to-core-namespaces-are-not-indexed"])
         search-value (seon.db/pull d '[{:seon.issue/tests [:seon.test/sym]}] [:seon.issue/id "a-search-contract-predicate-cannot-be-made-durable"])
         class-value (seon.db/pull d '[{:seon.issue/members [:seon.issue/id]}]
                                  [:seon.issue/id "class-classification-is-inferred-from-hand-lists"])]
     (clojure.test/is (= 3 (count selected-real)))
     (clojure.test/is (= 3 (:seon.issue/count report)) (pr-str report))
     (clojure.test/is (some #(= "seon.search-test/index-step-contract-has-durable-generative-host-predicates"
                               (:seon.test/sym %)) (:seon.issue/tests search-value)))
     (clojure.test/is (some #{"5deb40e4e"} (:seon.issue/commits member)))
     (clojure.test/is (some #(= "agent-form-calls-to-core-namespaces-are-not-indexed" (:seon.issue/id %))
                           (:seon.issue/members class-value)))
     (clojure.test/is (empty? (:seon.issue/refusals report)) (pr-str (:seon.issue/refusals report)))
     (clojure.test/is (contains? (set (:seon.issue/unresolved
                                       (seon.db/pull d '[:seon.issue/unresolved]
                                                     [:seon.issue/id "agent-form-calls-to-core-namespaces-are-not-indexed"])))
                                 "my.run/complete")))
   (let [real (first (filter #(clojure.string/includes? (:seon.issue/text %) "seon.fn")
                             (seon.issue/notes ".")))
         selected [{:seon.issue/path "docs/seon/issues/probe-class.md"
                    :seon.issue/text "---\ntype: issue\nstatus: open\nseverity: cleanup\ntags: [issue, class/n7, class-kill]\n---\n# Class\n## Problem\nFind facts."}
                   {:seon.issue/path "docs/seon/issues/probe-member.md"
                    :seon.issue/text "---\ntype: issue\nstatus: open\nseverity: friction\ntags: [issue, class/n7]\n---\n# Member\n## Problem\nseon.db/pull seon.issue-missing/absent abcdef123"}
                   real]
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})]
     (clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))
     (clojure.test/is (= 3 (:seon.issue/count report)))
     (clojure.test/is (empty? (:seon.issue/refusals report)) (pr-str (:seon.issue/refusals report)))
     (clojure.test/is (contains? (set (:seon.issue/unresolved
                                       (seon.db/pull (seon.db/db connection) '[:seon.issue/unresolved]
                                                     [:seon.issue/id "probe-member"])))
                                 "seon.issue-missing/absent"))
     (let [member (seon.db/pull (seon.db/db connection) '[*] [:seon.issue/id "probe-member"])
           class-row (seon.db/pull (seon.db/db connection) '[{:seon.issue/members [:seon.issue/id]}] [:seon.issue/id "probe-class"])]
       (clojure.test/is (= #{"abcdef123"} (set (:seon.issue/commits member))))
       (clojure.test/is (seq (:seon.issue/functions member)))
       (clojure.test/is (some #(= "probe-member" (:seon.issue/id %)) (:seon.issue/members class-row)))
       (clojure.test/is (clojure.string/includes? (seon.issue/render-ai member) "(my.issue/status"))
       (clojure.test/is (= :section (first (seon.issue/render-html member))))
       (clojure.test/is (= "probe-member" (:seon.issue/id (seon.issue/status {:seon.db/db (seon.db/db connection) :seon.issue/id "probe-member"}))))
       (seon.issue/index! {:seon.db/connection connection
                          :seon.issue/notes (mapv #(update % :seon.issue/text clojure.string/replace "status: open" "status: resolved") selected)})
       (clojure.test/is (= :resolved (:seon.issue/status (seon.db/pull (seon.db/db connection) '[*] [:seon.issue/id "probe-member"]))))
       (seon.issue/index! {:seon.db/connection connection :seon.issue/notes []})
       (clojure.test/is (= {:db/id (:db/id member) :seon.issue/id "probe-member"}
                           (seon.db/pull (seon.db/db connection) '[*] [:seon.issue/id "probe-member"])))
       (clojure.test/is (empty? (seon.issue/issues {:seon.db/db (seon.db/db connection)}))))))))

(def ^:private converted-notes
  "The eight notes converted by hand in the R6 research page, with the citation
  shape each one proves the derived resolver must reach."
  {"anonymous-runtime-contracts-have-recurred" [:seon.issue/functions :seon.issue/keys :seon.issue/files]
   "class-outward-values-bypass-total-render-contract" [:seon.issue/functions :seon.issue/files]
   "complete-publication-takes-seventy-seconds" [:seon.issue/functions :seon.issue/keys :seon.issue/files]
   "runtime-block-html-is-raw-ids-and-instants" [:seon.issue/functions :seon.issue/keys]
   "a-platform-test-leaves-its-worker-stripped-of-every-contract" [:seon.issue/tests :seon.issue/files]
   "development-adoption-can-mix-host-and-sci-generations" [:seon.issue/files :seon.issue/namespaces]
   "fresh-cljc-files-are-jvm-only" [:seon.issue/files :seon.issue/namespaces]
   "pre-rename-root-claims-are-unreadable-noise-on-every-status" [:seon.issue/keys]})

(clojure.test/deftest cited-identities-resolve-through-one-derived-resolver
 (seon.test-support/with-database
  (fn [connection]
   (let [wanted (set (map #(str % ".md") (keys converted-notes)))
         selected (filterv #(contains? wanted (.getName (clojure.java.io/file (:seon.issue/path %))))
                           (seon.issue/notes "."))
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})
         d (seon.db/db connection)
         pull-issue (fn [slug]
                      (seon.db/pull d '[:seon.issue/id :seon.issue/opened :seon.issue/unresolved
                                        {:seon.issue/functions [:seon.fn/sym]}
                                        {:seon.issue/tests [:seon.test/sym]}
                                        {:seon.issue/keys [:seon.schema/key]}
                                        {:seon.issue/namespaces [:seon.ns/name]}
                                        {:seon.issue/files [:seon.issue.citation/id :seon.issue.citation/row
                                                            :seon.issue.citation/end-row
                                                            {:seon.issue.citation/file [:seon.fn.file/path]}]}]
                                    [:seon.issue/id slug]))]
     (clojure.test/is (= (count converted-notes) (count selected)) (pr-str (map :seon.issue/path selected)))
     (clojure.test/is (empty? (:seon.issue/refusals report)) (pr-str (:seon.issue/refusals report)))
     (doseq [[slug attributes] converted-notes
             :let [row (pull-issue slug)]]
       (doseq [attribute attributes]
         (clojure.test/is (seq (get row attribute))
                          (str slug " has no " attribute " — " (pr-str row))))
       (clojure.test/is (inst? (:seon.issue/opened row)) (str slug " has no opened instant")))
     (let [adoption (pull-issue "development-adoption-can-mix-host-and-sci-generations")
           spans (filter :seon.issue.citation/row (:seon.issue/files adoption))]
       (clojure.test/is (seq spans) (pr-str (:seon.issue/files adoption)))
       (clojure.test/is (some #(clojure.string/ends-with?
                                (get-in % [:seon.issue.citation/file :seon.fn.file/path]) "src/seon/cluster.clj")
                              spans)
                        (pr-str spans))
       (clojure.test/is (some :seon.issue.citation/end-row spans)))
     (let [before (pull-issue "anonymous-runtime-contracts-have-recurred")
           citations (count (seon.db/q '[:find [?e ...] :where [?e :seon.issue.citation/id]] d))
           again (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})
           after-db (seon.db/db connection)]
       (clojure.test/is (nil? (:seon.error/kind again)) (pr-str again))
       (clojure.test/is (= citations (count (seon.db/q '[:find [?e ...] :where [?e :seon.issue.citation/id]] after-db))))
       (clojure.test/is (= before (pull-issue "anonymous-runtime-contracts-have-recurred"))))))))

(clojure.test/deftest an-archived-note-reports-unresolved-tokens-without-a-refusal
 (seon.test-support/with-database
  (fn [connection]
   (let [archived (filterv #(clojure.string/includes? (:seon.issue/path %) "docs/seon/issues/archive/")
                           (seon.issue/notes "."))
         selected (into [{:seon.issue/path "docs/seon/issues/probe-deleted.md"
                          :seon.issue/text (str "---\ntype: issue\nstatus: open\nseverity: cleanup\ntags: [issue]\n---\n"
                                                "# Deleted names\n## Problem\n"
                                                "seon.cluster.loop/settle! com.cognitect/transit-clj "
                                                "java.lang.Thread/sleep seon.db/pull")}]
                        (take 20 archived))
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})
         row (seon.db/pull (seon.db/db connection) '[:seon.issue/unresolved {:seon.issue/functions [:seon.fn/sym]}]
                           [:seon.issue/id "probe-deleted"])]
     (clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))
     (clojure.test/is (empty? (:seon.issue/refusals report)) (pr-str (:seon.issue/refusals report)))
     (clojure.test/is (= #{"com.cognitect/transit-clj" "java.lang.Thread/sleep" "seon.cluster.loop/settle!"}
                        (set (:seon.issue/unresolved row))))
     (clojure.test/is (some #(= "seon.db/pull" (:seon.fn/sym %)) (:seon.issue/functions row)))
     (clojure.test/is (= (count selected) (:seon.issue/count report)))
     (clojure.test/is (pos? (count (:seon.issue/unresolved report))))
     (clojure.test/is (every? #(and (string? (key %)) (pos-int? (val %))) (:seon.issue/unresolved report)))))))

(clojure.test/deftest a-token-naming-two-identities-is-reported-never-guessed
 (seon.test-support/with-database
  (fn [connection]
   ;; One spelling, two identities: an issue slug that is also a function
   ;; symbol. The resolver must refuse to pick, and say which token it refused.
   (let [shared (first (sort (filter #(clojure.string/starts-with? % "seon.db/")
                                     (seon.db/q '[:find [?sym ...] :where [_ :seon.fn/sym ?sym]]
                                                (seon.db/db connection)))))
         _ (seon.db/transact! connection [{:seon.issue/id shared :seon.issue/title "Shared spelling"
                                           :seon.issue/status :open :seon.issue/severity :cleanup
                                           :seon.issue/problem "Two identities, one spelling."}])
         selected [{:seon.issue/path "docs/seon/issues/probe-ambiguous.md"
                    :seon.issue/text (str "---\ntype: issue\nstatus: open\nseverity: cleanup\ntags: [issue]\n---\n"
                                          "# Ambiguous\n## Problem\n" shared)}]
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})
         row (seon.db/pull (seon.db/db connection)
                           '[{:seon.issue/functions [:seon.fn/sym]} {:seon.issue/issues [:seon.issue/id]}]
                           [:seon.issue/id "probe-ambiguous"])]
     (clojure.test/is (string? shared))
     (clojure.test/is (empty? (:seon.issue/refusals report)) (pr-str (:seon.issue/refusals report)))
     (clojure.test/is (some #(= shared (:seon.issue/value %)) (:seon.issue/ambiguous report))
                      (pr-str (:seon.issue/ambiguous report)))
     (clojure.test/is (empty? (:seon.issue/functions row)))
     (clojure.test/is (empty? (:seon.issue/issues row)))))))

(clojure.test/deftest issue-worker-opening-links-its-issue
 (seon.test-support/with-database
  (fn [c]
   (seon.test-support/seed-cluster! c "issue-family-opening")
   (let [test-name "seon.issue-test/issue-worker-creation-is-atomic"
         issue-id "issue-family-opening"
         aid (seon.id/id [issue-id])]
    (seon.db/transact! c [{:seon.issue/id issue-id :seon.issue/title "Verify issue opening"
                          :seon.issue/status :open :seon.issue/severity :cleanup
                          :seon.issue/problem "Read the issue and its success tests."
                          :seon.issue/tests #{[:seon.test/sym test-name]}}])
    (let [started (seon.issue/start! {:seon.db/connection c :seon.issue/id issue-id
                                    :seon.issue/budget 1 :seon.ns/name 'my.agents.issue-opening
                                    :seon.config.ai/no-provider true})
          ctx (seon.test-support/fork-cluster-ctx c)
          environment (seon.test-support/environment "issue-family-opening" c)
          routing (seon.cluster.agent/routing)
          faults (clojure.core.async/chan (clojure.core.async/sliding-buffer 16))]
     (swap! routing assoc :seon.agent/fault-channel faults)
     (clojure.test/is (nil? (:seon.error/kind started)) (pr-str started))
     (with-open [launcher (seon.test-support/closeable
                            (seon.flow/start-work-launcher!
                             {:seon.env/environment environment
                              :seon.flow/configuration
                              (select-keys (seon.test-support/effective-config) seon.flow/flow-workload-attributes)})
                            seon.flow/stop-work-launcher!)]
      (let [handle (seon.test-support/cluster-handle
                     {:seon.env/environment environment :seon.db/connection c
                      :seon.cluster/name "issue-family-opening" :seon.sci.eval/ctx ctx
                      :seon.flow/work-launcher @launcher
                      :seon.flow/executor (seon.cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                      :seon.db.process/id seon.cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id aid}
            opening-id (seon.id/id [:seon.issue/opening issue-id])]
       (try
        (seon.cluster.agent/arm! request)
        (seon.test-support/await-event! c ::issue-opening
          (fn [d] (some? (:seon.turn/closed-tx (seon.db/pull d [:seon.turn/closed-tx] [:seon.turn/id opening-id])))))
        (let [entries (seon.eval/of-agent (seon.db/db c) aid)
              sources (mapv :seon.cluster.eval/source entries)
              plan-index (.indexOf sources "(seon.plan/plan {})")
              issue-index (first (keep-indexed (fn [i s] (when (clojure.string/includes? s "my.issue/status") i)) sources))]
          (clojure.test/is (seq entries))
          (clojure.test/is (every? :seon.eval/shown entries))
          (clojure.test/is (and issue-index (<= 0 plan-index) (< plan-index issue-index)) (pr-str sources))
          (clojure.test/is (empty? (keep :seon.cluster.eval/error entries)))
          (clojure.test/is (clojure.string/includes? (str (:seon.eval/shown (get entries issue-index))) test-name)))
        (let [submitted (seon.turn/virtual-turn! (assoc request :seon.cluster.reply/text
                           "(my.issue/status {:seon.issue/id \"issue-family-opening\"})"))
              tid (:seon.turn/id submitted)]
          (clojure.test/is (string? tid) (pr-str submitted))
          (when tid
           (seon.test-support/await-event! c ::issue-virtual-turn
             (fn [d] (some? (:seon.turn/closed-tx (seon.db/pull d [:seon.turn/closed-tx] [:seon.turn/id tid])))))
           (clojure.test/is (empty? (keep :seon.cluster.eval/error (seon.eval/of-agent (seon.db/db c) aid))))))
        (finally
         (seon.cluster.agent/disarm! request)
         (clojure.core.async/close! faults)
         (doseq [k [:seon.cluster.wake/channel :seon.render/context-channel :seon.turn.loop/completion]]
           (clojure.core.async/close! (get handle k))))))))))))

(clojure.test/deftest issue-worker-creation-is-atomic
 (seon.test-support/with-database
  (fn [c]
   (seon.test-support/seed-cluster! c "issue-family")
   (seon.db/transact! c (seon.cluster.agent/creation-tx
                         {:seon.agent/id "issue-author" :seon.ns/name 'my.agents.issue-author :seon.cluster/name "issue-family"}))
   (let [d (seon.db/db c)
         test-ref [:seon.test/sym (first (sort (seon.db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] d)))]
         request {:seon.db/connection c :seon.agent/id "issue-author"
                  :seon.issue/title "Probe issue" :seon.issue/problem "Verify atomic creation."
                  :seon.issue/severity :cleanup}
         added (seon.issue/add! request)
         issue-id (:seon.issue/id added)
         start {:seon.db/connection c :seon.issue/id issue-id :seon.issue/budget 1
                :seon.ns/name 'my.agents.issue-worker :seon.config.ai/no-provider true}]
     (clojure.test/is (string? issue-id) (pr-str added))
     (clojure.test/is (= :seon.issue/not-a-test (:seon.error/kind (seon.issue/add! (assoc request :seon.issue/title "Invalid success ref" :seon.issue/tests #{[:seon.agent/id "issue-author"]})))))
     (clojure.test/is (= :seon.issue/no-tests (:seon.error/kind (seon.issue/start! start))))
     (clojure.test/is (nil? (:seon.error/kind (seon.issue/tests! {:seon.db/connection c :seon.agent/id "issue-author"
                                                               :seon.issue/id issue-id :seon.issue/tests #{test-ref}}))))
     (let [started (seon.issue/start! start)
           d (seon.db/db c)
           agent-id (seon.id/id [issue-id])
           agent (seon.db/pull d '[{:seon.agent/plan [* {:my.plan/steps [*]}]}
                                   {:seon.agent/settings [*]}] [:seon.agent/id agent-id])
           opening (seon.db/pull d '[*] [:seon.turn/id (seon.id/id [:seon.issue/opening issue-id])])]
       (clojure.test/is (nil? (:seon.error/kind started)) (pr-str started))
       (clojure.test/is (= 1 (count (get-in agent [:seon.agent/plan :my.plan/steps]))))
       (clojure.test/is (= 1 (get-in agent [:seon.agent/settings :seon.config.run/max-episode-runs])))
       (clojure.test/is (true? (get-in agent [:seon.agent/settings :seon.config.ai/no-provider])))
       (clojure.test/is (some? (:seon.turn/trigger opening)))
       (clojure.test/is (nil? (:seon.turn/closed-tx opening)))
       (clojure.test/is (= :seon.issue/already-started (:seon.error/kind (seon.issue/start! start))))
       (clojure.test/is (nil? (seon.db/q seon.issue/done-query d [:seon.issue/id issue-id])))
       (clojure.test/is (= :seon.issue/not-a-test
                           (:seon.error/kind (seon.issue/tests! {:seon.db/connection c :seon.agent/id "issue-author"
                                                               :seon.issue/id issue-id :seon.issue/tests #{[:seon.agent/id "issue-author"]}})))))))))
