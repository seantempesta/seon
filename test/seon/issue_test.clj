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
            [seon.issue.detect]
            [seon.plan]
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

(clojure.test/deftest unchanged-issue-adoption-writes-no-issue-datoms
  (seon.test-support/with-database
   (fn [source-connection]
     (seon.test-support/with-database
      (fn [connection]
        (let [notes [{:seon.issue/path "docs/seon/issues/adoption-class.md"
                      :seon.issue/text
                      (str "---\ntype: issue\nstatus: open\nseverity: cleanup\n"
                           "created: 2026-09-17\ntags: [issue, class/adoption, class-kill]\n---\n"
                           "# Adoption class\nseon.issue/adopt-tx seon.issue "
                           ":seon.issue/title src/seon/issue.clj:701 "
                           "seon.issue-test/issue-worker-creation-is-atomic")}
                     {:seon.issue/path "docs/seon/issues/adoption-member.md"
                      :seon.issue/text
                      (str "---\ntype: issue\nstatus: open\nseverity: cleanup\n"
                           "created: 2026-09-17\ntags: [issue, class/adoption]\n---\n"
                           "# Adoption member\nseon.issue/adopt!")}]
              indexed (seon.issue/index! {:seon.db/connection source-connection
                                         :seon.issue/notes notes})
              first-adoption (seon.issue/adopt! connection (seon.db/db source-connection))]
          (clojure.test/is (nil? (:seon.error/kind indexed)) (pr-str indexed))
          (clojure.test/is (nil? (:seon.error/kind first-adoption)) (pr-str first-adoption))
          (let [row (seon.db/pull (seon.db/db connection) '[*] [:seon.issue/id "adoption-class"])]
            (doseq [attribute [:seon.issue/functions :seon.issue/namespaces :seon.issue/keys
                               :seon.issue/files :seon.issue/tests :seon.issue/members]]
              (clojure.test/is (seq (get row attribute)) (str "Missing adoption subject " attribute))))
          (let [again (seon.issue/index! {:seon.db/connection source-connection :seon.issue/notes notes})
                source (seon.db/db source-connection)
                rows (mapv #(#'seon.issue/identity-row source %)
                           (seon.db/q '[:find [?e ...] :where [?e :seon.issue/path]] source))
                delta (seon.issue/adopt-tx (seon.db/db connection) rows)
                report (seon.test-support/transacted! connection [[:db.fn/call #'seon.issue/adopt-tx rows]])]
            (clojure.test/is (nil? (:seon.error/kind again)) (pr-str again))
            (clojure.test/is (empty? delta) (pr-str delta))
            (clojure.test/is (empty? (remove #(= :db/txInstant (:a %)) (:tx-data report)))
                            (pr-str (:tx-data report))))
          (let [changed (seon.issue/index!
                         {:seon.db/connection source-connection
                          :seon.issue/notes
                          (mapv #(update % :seon.issue/text clojure.string/replace
                                         "# Adoption member" "# Changed member") notes)})
                adopted (seon.issue/adopt! connection (seon.db/db source-connection))]
            (clojure.test/is (nil? (:seon.error/kind changed)) (pr-str changed))
            (clojure.test/is (nil? (:seon.error/kind adopted)) (pr-str adopted))
            (clojure.test/is (= "Changed member"
                               (:seon.issue/title
                                (seon.db/pull (seon.db/db connection) [:seon.issue/title]
                                              [:seon.issue/id "adoption-member"])))))))))))

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
                                                            {:seon.issue.citation/file [:seon.fn.file/relative-path]}]}]
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
                                (get-in % [:seon.issue.citation/file :seon.fn.file/relative-path]) "src/seon/cluster.clj")
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
         _ (seon.test-support/transacted! connection [{:seon.issue/id shared :seon.issue/title "Shared spelling"
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

(clojure.test/deftest an-unchanged-note-set-indexes-without-a-transaction
 (seon.test-support/with-database
  (fn [connection]
   ;; Publication calls index! on every complete build and every changed-path
   ;; upsert. An unchanged note set must cost no transaction at all, and one
   ;; changed note must cost only its own entity's datoms.
   (let [selected [{:seon.issue/path "docs/seon/issues/probe-unchanged-a.md"
                    :seon.issue/text (str "---\ntype: issue\nstatus: open\nseverity: cleanup\ntags: [issue]\n---\n"
                                          "# Unchanged A\n## Problem\nseon.db/pull holds the answer.")}
                   {:seon.issue/path "docs/seon/issues/probe-unchanged-b.md"
                    :seon.issue/text (str "---\ntype: issue\nstatus: open\nseverity: friction\ntags: [issue]\n---\n"
                                          "# Unchanged B\n## Problem\nseon.db/transact! writes it.")}]
         report (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})
         indexed (seon.db/db connection)
         basis (seon.db/basis-t indexed)
         entity (:db/id (seon.db/pull indexed '[:db/id] [:seon.issue/id "probe-unchanged-b"]))]
     (clojure.test/is (nil? (:seon.error/kind report)) (pr-str report))
     (clojure.test/is (= [] (seon.issue/index-tx indexed selected))
                      (pr-str (seon.issue/index-tx indexed selected)))
     (let [again (seon.issue/index! {:seon.db/connection connection :seon.issue/notes selected})]
       (clojure.test/is (nil? (:seon.error/kind again)) (pr-str again))
       (clojure.test/is (= 2 (:seon.issue/count again)))
       (clojure.test/is (= basis (seon.db/basis-t (seon.db/db connection)))
                        "re-indexing an unchanged note set moved the database basis"))
     (let [changed (assoc-in selected [1 :seon.issue/text]
                             (str "---\ntype: issue\nstatus: resolved\nseverity: friction\ntags: [issue]\n---\n"
                                  "# Unchanged B\n## Problem\nseon.db/transact! writes it."))
           delta (seon.issue/index-tx indexed changed)
           touched (into #{} (map #(if (map? %) (:db/id %) (second %))) delta)]
       (clojure.test/is (seq delta))
       (clojure.test/is (= #{entity} touched) (pr-str delta))
       (seon.issue/index! {:seon.db/connection connection :seon.issue/notes changed})
       (clojure.test/is (= :resolved (:seon.issue/status
                                      (seon.db/pull (seon.db/db connection) '[:seon.issue/status]
                                                    [:seon.issue/id "probe-unchanged-b"]))))
       (clojure.test/is (= :open (:seon.issue/status
                                  (seon.db/pull (seon.db/db connection) '[:seon.issue/status]
                                                [:seon.issue/id "probe-unchanged-a"])))))))))

(clojure.test/deftest issue-worker-opening-links-its-issue
 (seon.test-support/with-database
  (fn [c]
   (seon.test-support/seed-cluster! c "issue-family-opening")
   (let [test-name "seon.issue-test/issue-worker-creation-is-atomic"
         issue-id "issue-family-opening"
         aid (seon.id/id [issue-id])]
    (seon.test-support/transacted! c [{:seon.issue/id issue-id :seon.issue/title "Verify issue opening"
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
          (clojure.test/is (= issue-id
                             (:seon.issue/id
                              (seon.db/pull (seon.db/db c) [:seon.issue/id]
                                            (get-in entries [issue-index :seon.eval/origin :db/id])))))
          (clojure.test/is (nil? (seon.db/pull (seon.db/db c) [:db/ident]
                                             [:db/ident :seon.issue/turns-remaining])))
          (clojure.test/is (every? :seon.eval/shown entries))
          (doseq [untaught ["my.shell" "my.edit" "my.turn/complete"]]
            (clojure.test/is (not (clojure.string/includes?
                                  (clojure.string/join "\n" (concat sources (map :seon.eval/shown entries)))
                                  untaught))))
          (clojure.test/is (and issue-index (<= 0 plan-index)) (pr-str sources))
          (clojure.test/is (empty? (keep :seon.cluster.eval/error entries)))
          (clojure.test/is (clojure.string/includes? (str (:seon.eval/shown (get entries issue-index))) test-name)))
        (finally
         (seon.cluster.agent/disarm! request)
         (clojure.core.async/close! faults)
         (doseq [k [:seon.cluster.wake/channel :seon.render/context-channel :seon.turn.loop/completion]]
           (clojure.core.async/close! (get handle k))))))))))))

(clojure.test/deftest detector-only-issue-starts-and-settles-from-its-subject
  (seon.test-support/with-database
   (fn [connection]
     (seon.test-support/seed-cluster! connection "issue-family")
     (let [detector "seon.issue.detect/public-without-doc"
           subject (:seon.fn/sym
                    (first (sort-by :seon.fn/sym
                                    (seon.issue.detect/public-without-doc
                                     (seon.db/db connection)
                                     {:seon.fn.file/relative-root "src"}))))
           generated (seon.issue/generate!
                      {:seon.db/connection connection
                       :seon.issue/detector detector :seon.issue/severity :cleanup})
           issue-id (seon.db/q '[:find ?id . :in $ ?symbol ?detector
                                :where [?function :seon.fn/sym ?symbol]
                                [?detector-row :seon.fn/sym ?detector]
                                [?issue :seon.issue/functions ?function]
                                [?issue :seon.issue/detector ?detector-row]
                                [?issue :seon.issue/id ?id]]
                              (seon.db/db connection) subject detector)]
       (clojure.test/is (string? subject))
       (clojure.test/is (nil? (:seon.error/kind generated)) (pr-str generated))
       (clojure.test/is (string? issue-id))
       (let [started (seon.issue/start!
                      {:seon.db/connection connection :seon.issue/id issue-id
                       :seon.issue/budget 2 :seon.config.ai/no-provider true})
             agent-id (seon.db/q '[:find ?id . :in $ ?issue-id
                                  :where [?issue :seon.issue/id ?issue-id]
                                  [?issue :seon.issue/agent ?agent]
                                  [?agent :seon.agent/id ?id]]
                                (seon.db/db connection) issue-id)]
         (clojure.test/is (nil? (:seon.error/kind started)) (pr-str started))
         (clojure.test/is (string? agent-id))
         (clojure.test/is (empty? (:seon.issue/tests started)))
         (clojure.test/is (false? (seon.issue/done? (seon.db/db connection)
                                                   [:seon.issue/id issue-id])))
         (seon.test-support/transacted!
          connection [[:db.fn/call #'seon.plan/settle-call agent-id]])
         (clojure.test/is (nil? (:seon.issue/resolved-tx
                                (seon.db/pull (seon.db/db connection)
                                              [:seon.issue/resolved-tx]
                                              [:seon.issue/id issue-id]))))
         (seon.test-support/transacted!
          connection [[:db/add [:seon.fn/sym subject] :seon.fn/doc
                       "Construct the declared buffer from its supplied fields."]])
         (clojure.test/is (true? (seon.issue/done? (seon.db/db connection)
                                                  [:seon.issue/id issue-id])))
         (seon.test-support/transacted!
          connection [[:db.fn/call #'seon.plan/settle-call agent-id]])
         (clojure.test/is (some? (:seon.issue/resolved-tx
                                 (seon.db/pull (seon.db/db connection)
                                               [:seon.issue/resolved-tx]
                                               [:seon.issue/id issue-id])))))))))

(clojure.test/deftest issue-worker-creation-is-atomic
 (seon.test-support/with-database
  (fn [c]
   (seon.test-support/seed-cluster! c "issue-family")
   (seon.test-support/transacted! c (seon.cluster.agent/creation-tx
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
     (let [refused (seon.issue/start! start)]
       (clojure.test/is (= :seon.issue/no-tests (:seon.error/kind refused)))
       (clojure.test/is (clojure.string/includes? (:seon.error/message refused) issue-id))
       (clojure.test/is (clojure.string/includes? (:seon.error/message refused) ":seon.issue/detector")))
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
