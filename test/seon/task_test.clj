(ns seon.task-test
  "The task writer derives what a task cites (seon.doc.edn's one-reference-reader, task
  seon.task/a-task-cites-what-it-needs): references from the plan, never authored, and a
  citation that resolves to nothing answered beside the write."
  (:require [seon.test-store :as ts] [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.repl :as repl]
            [seon.db :as pdb] [seon.task :as task] [seon.ref :as ref] [seon.template :as template] [clojure.string :as str]))

(defn- db-with-program []
  ;; one recorded definition, its form (seon.program's facts), written as data
  (d/db-with (ts/test-db)
             [{:db/id "ns" :ns/name 'demo :ns/file "src/demo.clj"}
              {:db/id "v" :var/name 'demo/f :var/ns "ns" :var/form "form"}
              {:db/id "form" :form/id "demo/f" :form/src "(defn f [x] x)" :form/ns "ns" :form/order 0 :form/defines ["v"]}]))

(defn- decl [plan] {:seon.task/ns 'demo :seon.task/name "t" :seon.task/plan plan})

(defn- cited-in
  "What transaction tx makes its entities cite: each cite entity it writes, {target depth}."
  [tx] (into {} (for [m tx :when (and (map? m) (:seon.cite/id m))] [(or (:seon.cite/address m) (:seon.cite/name m)) (:seon.cite/depth m)])))

;; docs/references.md#task-references: a task's text is parsed once at its write, and each target it cites is a cite entity
(deftest the-writer-writes-a-tasks-cites
  (let [plan "Make `demo/f` pass; ``demo/later`` is only mentioned; `(demo/f 1)` is code. Read [vectors](docs/vector.md#the-pyramid), then ```demo/f``` whole."
        tx (task/create-tx (db-with-program) [(decl plan)])
        cites (filterv :seon.cite/id tx)]
    (is (= {'demo/f :code "docs/vector.md#the-pyramid" :doc} (cited-in tx)) "one per target, at the strongest depth its text asks: no literal, no form")
    (is (= [0 1] (mapv :seon.cite/order cites)) "in the order of first mention")
    (is (= #{"demo/t demo/f" "demo/t docs/vector.md#the-pyramid"} (set (map :seon.cite/id cites))) "each named by its task and its target")
    (is (every? #(= "demo/t" (:seon.cite/of %)) cites) "each of the task the same write makes (its tempid)")
    (is (= {} (cited-in (task/create-tx (db-with-program) [(decl "No names here.")]))) "no names, no cites")))

(deftest a-page-cited-whole-is-its-top-section
  ;; docs/references.md#an-entity-cites: a path with no anchor cites the page's top section, whose sections join below it
  (let [db (d/db-with (db-with-program) [{:db/id "top" :seon.doc/address "docs/x.md#x" :seon.doc/heading "X"}
                                         {:seon.doc/address "docs/x.md#y" :seon.doc/heading "Y" :seon.doc/parent "top"}])]
    (is (= {"docs/x.md#x" :doc} (cited-in (task/create-tx db [(decl "Read [x](docs/x.md).")]))) "the page the db holds: its top section")
    (is (= {"docs/nowhere.md" :doc} (cited-in (task/create-tx db [(decl "Read [n](docs/nowhere.md).")]))) "a page it does not: the address as written, which joins nothing")))

(deftest a-rewritten-text-leaves-exactly-its-cites
  ;; the write of a task's text (seon.agent/transact!'s with-citations calls rederive-tx): its cites become the new
  ;; text's, one per target at its strongest depth, upserted by their content id, and the ones it no longer cites retracted
  (let [db (d/db-with (db-with-program) (task/create-tx (db-with-program) [(decl "Make `demo/f` pass; see [v](docs/vector.md#the-pyramid).")]))
        db (d/db-with db [[:db/add [:seon.task/id "demo/t"] :seon.task/plan "Make ```demo/f``` pass, and `demo/g`."]])
        db (d/db-with db (task/rederive-tx db (task/task db "demo/t")))
        cites (task/cites-of db (pdb/eid db :seon.task/id "demo/t"))]
    (is (= [['demo/f :code 0] ['demo/g :doc 1]] (mapv (juxt :seon.cite/name :seon.cite/depth :seon.cite/order) cites)) "the new text's cites, demo/f deepened in place")
    (is (empty? (d/datoms db :avet :seon.cite/id "demo/t docs/vector.md#the-pyramid")) "the section it no longer cites, retracted whole")
    (is (not-any? #(some-> (:a %) keyword? (and (= "seon.cite" (namespace (:a %))))) (:tx-data (d/with db (task/rederive-tx db (task/task db "demo/t")))))
        "written again: no cite datom changes")))

(deftest an-agent-is-made-citing-what-every-agent-cites
  ;; :seon.config.agent/cites written on a new agent's record (seon.task/agent-cites-tx, at its first start: seon.agent/begin-run!), never
  ;; read as a default at draw time: a page whole is its top section, a section its address, each at its ?depth
  (let [db (d/db-with (db-with-program) [{:db/id "top" :seon.doc/address "docs/testing.md#testing" :seon.doc/heading "Testing"}
                                         {:seon.doc/address "docs/data-modeling.md" :seon.doc/heading "Data modeling"}
                                         {:seon.doc/address "docs/references.md#an-entity-cites" :seon.doc/heading "An entity cites"}])
        db (d/db-with db (task/create-tx db [(decl "Do.")]))
        tx (into (task/start-tx db "demo/t" "b1" :a1 {:branch :b :model nil :max-turns 4} 0) (task/agent-cites-tx db "a1" "a1"))
        db (d/db-with db tx)]
    (is (= (count (seon.config/value :seon.config.agent/cites)) (count (filter :seon.cite/id tx))) "one per address the config names")
    (is (= (mapv (fn [a] [a :doc]) (seon.config/value :seon.config.agent/cites))
           (mapv (juxt :seon.cite/address :seon.cite/depth) (task/cites-of db (pdb/eid db :seon.agent/key "a1"))))
        "on the agent's record, in the config's order (the references it is told it can read, not pulled into its context whole)")))

(deftest the-writer-cites-what-names-something
  ;; audit item 2a: a reproduction's text names a test helper (demo-test/fixture) and a handle (result/e-none) that name
  ;; nothing: a text word is cited only when it names something (seon.ref/kept); the program's names among the cites
  ;; are what the judge reads (seon.judge/subject). A handle no eval makes: seon.ref also asks this process, and a shard's
  ;; other tests define result/e1
  (let [db (d/db-with (db-with-program) [{:ns/name 'demo-test :ns/file "test/demo_test.clj"}])
        plan (str "Make demo/f exact and add `demo/g`. Reproduce: (let [db (demo-test/fixture)] (demo/f db)) answers result/e-none.")
        names (set (keep :seon.cite/name (task/create-tx db [(decl plan)])))]
    (is (every? names '[demo/f demo/g]) "the defined name and the one to make (cited by its backticks)")
    (is (not-any? names '[demo-test/fixture result/e-none]) "and no text word that names nothing")
    (testing "a test the plan names is cited as any name; the page shows it as the task's proof"
      (let [tx (task/create-tx db [(assoc (decl "Make `demo-test/f-test` pass.") :seon.task/proofs #{'demo-test/f-test})])]
        (is (= #{'demo-test/f-test} (:seon.task/proofs (first tx))))))))

(deftest a-templates-instructions-are-cited-as-a-plan-is
  ;; owner, 2026-10-22: instructions are cited text; a var a kind's instructions cite is one of the task's cites,
  ;; and so its context, exactly as a plan's is
  (doseq [kind [:design :implement :test :merge]
          :let [db (db-with-program)
                ins (template/instructions kind)
                kept (keep :seon.ref/name (ref/kept db (ref/cited (:seon.ref/citations (ref/parse nil ins)))))
                tx (task/create-tx db [(assoc (decl "") :seon.task/instructions ins)])]]
    (is (= (set kept) (set (keep :seon.cite/name tx))) (str kind ": every name it cites"))))

(deftest a-plan-and-its-instructions-are-cited-apart
  ;; md-scan bench-b17: the plan quotes a fenced block's ``` marks, and parsed as one text with the instructions that
  ;; run opened a code span, so the instructions' ```docs/agent.md#working-a-task``` was cited at summary (its heading
  ;; alone) and the agent was never sent how a task is worked
  (let [db (db-with-program)
        tx (task/create-tx db [(assoc (decl "Drop a fenced block's ``` marks from `demo/f`.")
                                      :seon.task/instructions "How: ```docs/agent.md#working-a-task```.")])
        cite (first (filter #(= "docs/agent.md#working-a-task" (:seon.cite/address %)) tx))]
    (is (= :code (:seon.cite/depth cite)) (str "the instructions' citation at the depth its own delimiter asks: " (pr-str cite)))
    (is (some #(= 'demo/f (:seon.cite/name %)) tx) "and the plan's own")))

(deftest unresolved-citations-are-answered-not-refused
  (let [db (db-with-program)
        ds [(decl "Make `demo/f` and `demo/a` pass.")]]
    (is (= [{:task "demo/t" :name 'demo/a :problem :unresolved}] (task/citation-problems db ds)))
    (is (vector? (task/create-tx db ds)) "the write still happens")))

(deftest the-reader-answers-every-stored-member
  ;; research row 20: the reader pulls what main stores (Datahike's wildcard), so a task
  ;; attribute is written in one place; references were stored and never read back
  (let [db (d/db-with (db-with-program) (task/create-tx (db-with-program) [(decl "Make `demo/f` pass.")]))
        t (task/task db "demo/t")]
    (is (= ['demo/f] (mapv :seon.cite/name (task/cites-of db (pdb/eid db :seon.task/id "demo/t")))) "its cites, entities of their own")
    (is (not-any? #{:db/id :seon.task/declared} (keys t)) "the stored declaration is load-tx's, not the reader's")
    (is (= t (get (task/tasks db) "demo/t")))))

(deftest a-resumed-branch-takes-its-needs-from-main
  ;; a kept branch resumed for a run may have been forked before main held the tasks its
  ;; task needs (the step E dry run read its task on its branch as nil and threw).
  ;; onto-tx puts the task and what it needs there, as main holds them, and leaves another
  ;; task the branch already holds as the branch has it
  (let [db0 (ts/test-db)
        branch (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Branch's own a."}]))
        main (d/db-with branch (task/create-tx branch [{:seon.task/ns 'demo :seon.task/name "schema" :seon.task/plan "Register the schema." :seon.task/needs #{"demo/a"}}
                                                       {:seon.task/ns 'demo :seon.task/name "store" :seon.task/plan "" :seon.task/needs #{"demo/schema"}}]))
        main (d/db-with main [{:seon.task/id "demo/a" :seon.task/plan "Main's a."}])
        tx (task/onto-tx main branch "demo/store")
        after (d/db-with branch tx)]
    (is (= #{"demo/schema" "demo/store"} (set (map :seon.task/id tx))) "the task and what it needs that the branch lacks")
    (is (= "Register the schema." (:seon.task/plan (task/task after "demo/schema"))))
    (is (= #{"demo/schema"} (:seon.task/needs (task/task after "demo/store"))) "its needs resolve on the branch")
    (is (= "Branch's own a." (:seon.task/plan (task/task after "demo/a"))) "a task the branch holds is left as the branch has it")
    (is (= [] (task/onto-tx main after "demo/store")) "nothing when the branch holds them")))

(deftest a-released-branch-takes-its-task-from-main
  ;; owner, 2026-10-23: a refused end holds its task for root, and root's release starts it
  ;; again on the same, kept branch; root's judge wrote the failure and the evidence on main
  ;; after the branch last moved, so the resumed agent takes its task as main holds it
  (let [db0 (ts/test-db)
        branch (d/db-with db0 (task/create-tx db0 [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "Make a true."}]))
        ev {:from "demo/a" :conflicts [] :unmerged [] :red ['demo-test/a-test] :problem "proof-red demo-test/a-test"}
        main (d/db-with branch [{:seon.task/id "demo/a" :seon.task/evidence ev :seon.task/failure {:reason "proof-red demo-test/a-test" :agent "boot-1/a1"}}])
        after (d/db-with branch (task/onto-tx main branch "demo/a"))]
    (is (= ev (:seon.task/evidence (task/task after "demo/a"))) "the evidence")
    (is (= "boot-1/a1" (:agent (:seon.task/failure (task/task after "demo/a")))) "and the failure it answers")
    (is (= [] (task/onto-tx main after "demo/a")) "nothing when the branch holds it as main does")))

(deftest a-tasks-lifecycle-is-read-from-its-schema
  ;; task seon.task/a-runs-lifecycle-is-read-once: the sets are the schema's, and the two reads
  ;; every view and judge shares answer from the tasks' own outcomes
  (let [db0 (d/db-with (ts/test-db) [{:seon.task/id "demo/a" :seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b :seon.task/kind :work}
                                     {:seon.task/id "demo/b" :seon.task/ns 'demo :seon.task/name "b" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :b2 :seon.task/kind :work}])
        start (fn [db tid ag] (d/db-with db (task/start-tx db tid "boot-1" ag {:branch :b :model nil :max-turns 4} 1000)))
        db (-> db0 (start "demo/a" :a1) (start "demo/b" :a2)
               (d/db-with [{:seon.task/id "demo/b" :seon.task/outcome :merged}]))
        te #(:db/id (d/entity db [:seon.task/id %]))]
    (is (= #{:done :merged} task/finished) "the schema's finished set")
    (is (= ["demo/a"] (mapv :task (task/running db))) "only the running task, one seek of the outcome")
    (is (= [:a1 "a1"] ((juxt :agent :key) (first (task/running db)))) "with its agent's id and key")
    (is (false? (task/done? db (te "demo/a"))) "a running task is not done")
    (is (true? (task/done? db (te "demo/b"))) "a merged one is")))

;; ---- the links between root, an agent, its task, turns and messages are refs: a pull follows them ----------------------------

(deftest an-agents-links-pull-from-its-root
  ;; root records its agent on main; the reconciler records the task's start, naming the agent by its key;
  ;; the agent's branch records a turn of the agent, its evaluations and the message it took: each
  ;; written by its producer's own function, each link a ref a pull follows from root down
  (ts/test-branch!
   (fn [cfg conn]
     (let [main (:branch cfg) b (keyword (str (name main) "-a3"))
           k "a3"]
       (d/transact conn [{:seon.root/id :root :seon.root/boot "b1" :seon.config.agent/max-running 2}])
       (d/transact conn [{:seon.task/id "demo/f" :seon.task/ns 'demo :seon.task/name "f" :seon.task/plan "p" :seon.task/execution :running :seon.task/branch :task-demo-f}])
       (d/transact conn (task/agent-tx :a3 {:status :idle :pid 77 :running {:form "(f 2)"}}))
       (d/branch! conn main b)
       (let [bc (d/connect (assoc cfg :branch b))]
         (try
           (d/transact conn (task/start-tx @conn "demo/f" "b1" :a3 {:branch b :model "m" :max-turns 8} 0))
           (d/transact conn (task/message-tx "m-1" "owner" [k] "check g" 1000))
           ;; on the branch, which forked before main held the task's start or the message
           (d/transact bc (ts/turn-tx k 1 {}))
           (d/transact bc (task/message-tx "m-1" "owner" [k] "check g" 1000))
           (d/transact bc (repl/record-tx {:seon.agent/key k} "e1" "a3" {:seon.eval/code "(f 2)" :seon.eval/response {:handle 'result/e1 :value 4}}))
           (testing "from root on main: its agents, each agent's task"
             (is (= [{:seon.agent/key "a3" :seon.agent/status :idle :seon.process/_agent [{:seon.process/pid 77 :seon.process/running "(f 2)"}]
                      :seon.task/_agent [{:seon.task/id "demo/f"}]}]
                    (:seon.agent/_root (d/pull @conn '[{:seon.agent/_root [:seon.agent/key :seon.agent/status
                                                                           {:seon.process/_agent [:seon.process/pid :seon.process/running]}
                                                                           {:seon.task/_agent [:seon.task/id]}]}]
                                               [:seon.root/id :root])))))
           (testing "the message is to the agent by its key, on main"
             (is (= ["check g"] (d/q (quote [:find [?b ...] :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.message/to ?a] [?m :seon.message/body ?b]]) @conn k))))
           (testing "on the branch: the agent's model calls and the message it took, the agent's evaluations"
             (let [agent (d/pull @bc '[{:seon.model-call/_agent [:seon.model-call/id]} ] [:seon.agent/key k])]
               (is (= [{:seon.model-call/id (seon.lib/call-id k 1)}] (:seon.model-call/_agent agent)))
               (is (= ["m-1"] (d/q (quote [:find [?id ...] :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.message/to ?a] [?m :seon.message/id ?id]]) @bc k))))
             (is (= ["e1"] (mapv :seon.eval/id (:seon.eval/_agent (d/pull @bc '[{:seon.eval/_agent [:seon.eval/id]}] [:seon.agent/key k]))))))
           (testing "the message and the agent are one entity on the branch and on main (content ids)"
             (doseq [ref [[:seon.message/id "m-1"] [:seon.agent/key k]]]
               (is (= (:db/id (d/entity @conn ref)) (:db/id (d/entity @bc ref))) (pr-str ref))))
           (testing "the task's reader names its agent by key"
             (is (= k (:seon.task/agent (task/task @conn "demo/f")))))
           (finally (d/release bc) (d/delete-branch! conn b))))))))

(deftest an-agents-live-state-is-facts-on-its-entity
  ;; what root observes of an agent is recorded on the agent's entity: a member no longer true
  ;; is retracted, and an agent is named by its key, whatever boot
  (let [db0 (d/db-with (ts/test-db) [{:seon.root/id :root :seon.root/boot "b1" :seon.config.agent/max-running 2}])
        db1 (d/db-with db0 (task/agent-tx :a3 {:status :running :pid 77 :running {:form "(f 2)" :since-ms 5} :mem-mb 120.5}))
        db2 (d/db-with db1 (task/agent-tx :a3 {:status :idle :pid 77 :running nil}))
        db3 (d/db-with db2 (task/agent-tx :a3 {:status :spawning}))
        pull (fn [db] (d/pull db [:seon.agent/status {:seon.agent/root [:seon.root/id]}
                                   {:seon.process/_agent [:seon.process/pid :seon.process/running]}]
                                   [:seon.agent/key "a3"]))
        proc (fn [m] {:seon.agent/status (:seon.agent/status m) :seon.agent/root (:seon.agent/root m) :p (first (:seon.process/_agent m))})]
    (is (= {:seon.agent/status :running :seon.agent/root {:seon.root/id :root} :p {:seon.process/pid 77 :seon.process/running "(f 2)"}} (proc (pull db1))))
    (is (= {:seon.agent/status :idle :seon.agent/root {:seon.root/id :root} :p {:seon.process/pid 77}} (proc (pull db2)))
        "the form returned: no longer running")
    (is (= :spawning (:seon.agent/status (pull db3))) "a later fact of the same agent")))

(deftest a-new-boot-leaves-the-earlier-boots-agents
  ;; root's agents are this boot's: a boot retracts the earlier boots' agents' link to root
  (let [db (d/db-with (ts/test-db) (into [{:seon.root/id :root :seon.root/boot "b1" :seon.config.agent/max-running 2}] (task/agent-tx :a1 {:status :idle})))
        db2 (d/db-with db (into (task/boot-tx "b2" 4 "sean") (task/earlier-agents-tx db)))
        db3 (d/db-with db2 (task/agent-tx :a1 {:status :spawning}))
        agents (fn [db] (set (map :seon.agent/key (:seon.agent/_root (d/pull db '[{:seon.agent/_root [:seon.agent/key]}] [:seon.root/id :root]))))) ]
    (is (= #{"a1"} (agents db)))
    (is (= #{"sean"} (agents db2)) "the new boot holds none of the earlier boot's: only its user, an agent with no process")
    (is (= #{"sean" "a1"} (agents db3)) "and its own as they start")
    (is (= #{"sean"} (agents (d/db-with db3 (into (task/boot-tx "b3" 4 "sean") (task/earlier-agents-tx db3))))) "a later boot keeps the one user, and unlinks the rest")
    (is (some? (d/entity db3 [:seon.agent/key "a1"])) "the earlier agent is kept, not deleted")))

(deftest a-design-adds-a-proof-with-update
  ;; owner, 2026-10-25 (Dogfood rulings): my.task/update! takes :seon.task/proofs, so a design
  ;; registers the red proofs it writes; its write is seon.task/member-tx, a set member's adds and
  ;; retracts, a single member's new value
  (let [t {:seon.task/id "demo/t" :seon.task/plan "p" :seon.task/proofs #{'demo-test/a-test}}]
    (is (= [[:db/add [:seon.task/id "demo/t"] :seon.task/proofs 'demo-test/b-test]]
           (task/member-tx t :seon.task/proofs conj ['demo-test/b-test])))
    (is (= [[:db/retract [:seon.task/id "demo/t"] :seon.task/proofs 'demo-test/a-test]]
           (task/member-tx t :seon.task/proofs disj ['demo-test/a-test])))
    (is (= [[:db/add [:seon.task/id "demo/t"] :seon.task/proofs 'demo-test/b-test]]
           (task/member-tx (dissoc t :seon.task/proofs) :seon.task/proofs conj ['demo-test/b-test])) "a task with no proofs yet")
    (is (= [[:db/add [:seon.task/id "demo/t"] :seon.task/plan "p, better"]]
           (task/member-tx t :seon.task/plan str [", better"])))
    (is (= :seon.task/invalid-member (:seon.error/type (task/member-tx t :seon.task/proofs (constantly nil) []))) "proofs that are no set of names: the error, never #{}")
    (is (= :seon.task/invalid-member (:seon.error/type (task/member-tx t :seon.task/proofs conj ["not-a-symbol"]))))
    (is (= :seon.task/invalid-member (:seon.error/type (task/member-tx t :seon.task/plan (constantly 3) []))) "a plan that is no string")
    (is (= 3 (:value (task/member-tx t :seon.task/plan (constantly 3) []))) "what it would be is the error's data, printed by its render, not text in its message")
    (let [db (d/db-with (ts/test-db) (task/create-tx (ts/test-db) [(decl "Make t true.")]))
          after (d/db-with db (task/member-tx (task/task db "demo/t") :seon.task/proofs conj ['demo-test/t-test]))]
      (is (= #{'demo-test/t-test} (set (:seon.task/proofs (task/task after "demo/t")))) "written, the task holds it"))))

(deftest a-root-before-its-first-boot-is-no-entity-value
  ;; a store an earlier build left (seon.root-test/a-root-resumes-a-store-an-earlier-build-wrote):
  ;; root's entity holds a quiescent fact and no boot, so (task/root db) is nil, and
  ;; entity-value answered [:root nil] against its own contract: the reconciler's step read it as
  ;; a :platform defect on every resume. Before its boot root is no value the index holds.
  (let [db (d/db-with (ts/test-db) [{:seon.root/id :root :seon.root/quiescent {:t 536870914 :root-causes []}}])
        e (:db/id (d/entity db [:seon.root/id :root]))]
    (is (nil? (task/root db)))
    (is (nil? (task/entity-value db e)))
    (let [booted (d/db-with db [{:seon.root/id :root :seon.root/boot "b1" :seon.config.agent/max-running 2}])]
      (is (= :root (first (task/entity-value booted e))))
      (is (= "b1" (:boot (second (task/entity-value booted e))))))))

(deftest a-message-a-fork-inherited-is-still-delivered
  ;; docs/users.md#messages: a message sent before its agent's branch forked is on the branch with no turn holding it: it still arrives; one a turn holds, removed or not, doesn't again
  (let [main (d/db-with (ts/test-db) (into [{:seon.agent/key "a"}] (task/message-tx "m1" "sean" ["a"] "first" 1)))
        forked main
        placed (d/db-with forked [{:seon.message/id "m1" :seon.message/call {:seon.model-call/id (seon.lib/call-id "a" 1)}}])
        removed (d/db-with placed [[:db/retract (:db/id (d/entity placed [:seon.message/id "m1"])) :seon.message/to (:db/id (d/entity placed [:seon.agent/key "a"]))]])]
    (is (= ["m1"] (map :id (task/undelivered main forked "a"))) "inherited, and no turn has it")
    (is (= [] (task/undelivered main placed "a")) "a turn has it")
    (is (= [] (task/undelivered main removed "a")) "removed after, it stays gone")))

(deftest a-started-agent-is-linked-to-who-started-it
  ;; docs/users.md#what-each-thing-is: a task names who started it; its agent, when it starts, is in that one's :seon.agent/agents, a user or an agent alike
  (let [db (d/db-with (ts/test-db) (into [{:seon.root/id :root :seon.root/boot "b1"} {:seon.agent/key "sean" :seon.user/name "sean"}]
                                         (task/create-tx (ts/test-db) [{:seon.task/ns 'demo :seon.task/name "a" :seon.task/plan "A." :seon.task/kind :work}])))
        db (d/db-with db [{:seon.task/id "demo/a" :seon.task/started-by [:seon.agent/key "sean"]}])
        started (d/db-with db (task/start-tx db "demo/a" "b1" :a1 {:branch :task-demo-a :model nil :max-turns 40} 1000))
        agents (fn [db] (set (map :seon.agent/key (:seon.agent/agents (d/entity db [:seon.user/name "sean"])))))]
    (is (= #{} (agents db)) "no agent yet")
    (is (= #{"a1"} (agents started)) "its agent, once it starts")
    (let [none (d/db-with db [[:db/retract [:seon.task/id "demo/a"] :seon.task/started-by [:seon.agent/key "sean"]]])]
      (is (= #{} (agents (d/db-with none (task/start-tx none "demo/a" "b1" :a1 {:branch :task-demo-a :model nil :max-turns 40} 1000)))) "a task nobody started links nothing"))))


;; task seon.root/an-agent-is-named-by-its-key (docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08)
(deftest an-agent-is-named-by-a-unique-id-and-a-call-by-its-content
  (let [ids (repeatedly 100 seon.lib/unique-id)
        db (d/db-with (db-with-program) (task/create-tx (db-with-program) [(decl "Do.")]))
        a (keyword (first ids))
        tx (task/start-tx db "demo/t" "b1" a {:branch :b :model nil :max-turns 4} 0)]
    (is (= 100 (count (set ids))) "every spawn mints its own: two spawns of one task are two agents")
    (is (every? #(re-matches #"[0-9a-f]{16}" %) ids) "in the one id form (a digest)")
    (is (some #(= (name a) (:seon.agent/key %)) tx) "the agent's key is its id's name")
    (is (not-any? :seon.agent/id (tree-seq coll? seq tx)) "and :seon.agent/id is not written")
    (is (= (seon.lib/call-id "k" 3) (seon.lib/call-id "k" 3) (:seon.model-call/id (task/call-ref "k" 3))) "the same agent and turn, the same call id")
    (is (= (seon.lib/digest "k/3") (seon.lib/call-id "k" 3)) "a model call's id is its agent and turn digested")
    (is (= 3 (count (distinct [(seon.lib/call-id "k" 3) (seon.lib/call-id "k" 4) (seon.lib/call-id "j" 3)]))) "another turn or agent, another id")))

(deftest an-agents-id-is-no-stored-attribute
  ;; docs/decisions.md#an-id-is-the-content-of-the-facts-that-make-it-owner-2026-11-08: an agent is named by its key alone; its id in
  ;; root's process is the keyword of that key and is never stored, and nothing indexes the boot (the list is by who started an agent)
  (let [schema (d/schema (ts/test-db))]
    (is (contains? schema :seon.agent/key) "the key is the stored name")
    (is (not (contains? schema :seon.agent/id)) "no attribute for the id")
    (is (:db/index (get schema :seon.agent/boot)) "the boot is indexed: a boot's sessions are read by it")
    (is (= "b9" (:seon.agent/boot (first (task/agent-tx :c0ffee00c0ffee00 {:boot "b9" :status :spawning}))))
        "every agent root starts records its boot (its view's :boot), a bare spawn! too, so the agents list holds it")))
