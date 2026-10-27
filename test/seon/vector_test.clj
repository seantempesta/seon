(ns seon.vector-test
  "Semantic search (seon.vector): a stored vector keeps what it was made from, the pyramid ranks
  as an exact search does, `near` is one more relation in a query, a text is embedded once, and
  the proc that keeps main's vectors current asks only for what is due, and a branch embeds
  what it writes there. The model itself runs
  only in root (it starts threads), so these use made vectors; bin/check vectors proves the
  pyramid's recall on the checkout's own forms, embedded by the model."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.test-store :as ts]
            [seon.vector :as vector] [seon.contract :as contract] [seon.agent :as agent] [seon.problems]))

(defn- unit [v] (let [n (Math/sqrt (reduce + (map #(* % %) v)))] (mapv #(/ % n) v)))

(defn- vectors
  "n unit vectors of dims around k centres, made from seed by a linear congruential generator
  (the same on every machine): clustered, as embeddings of a program's forms are, and shaped as a
  Matryoshka model's are, dim i weighted by e^(-i/96), so a prefix carries most of a vector."
  [seed n k dims]
  (let [rs (iterate #(mod (+ (* % 6364136223846793005) 1442695040888963407) 9223372036854775807) seed)
        xs (map #(- (/ (double (mod % 1000003)) 1000003.0) 0.5) (rest rs))
        w (mapv #(Math/exp (/ (- %) 96.0)) (range dims))
        centres (vec (map vec (partition dims (take (* k dims) xs))))]
    (vec (map (fn [i noise] (unit (mapv * w (mapv + (centres (mod i k)) (map #(* 0.6 %) noise)))))
              (range n) (partition dims (drop (* k dims) xs))))))

(defn- db-with-vectors
  "The test store's seed with a form per vector, each carrying its packed vector."
  [vs]
  (d/db-with (ts/test-db) (vec (map-indexed (fn [i v] (assoc (vector/pack v) :db/id (str "f" i) :form/id (str "f" i) :seon.vector/model "m")) vs))))

(defn- embedded
  "The facts of due texts [[e text t] ...] embedded as vs, each text of 10 tokens, by model."
  [due vs model] (vector/tx-data (map first due) (map peek due) vs (repeat (count due) 10) model))

(defn- exact
  "The k entities nearest q by the full cosine of each stored vector, computed here without the
  kernel."
  [db q k]
  (->> (d/q '[:find ?e :where [?e :form/id] [?e :seon.vector/f16]] db)
       (map (fn [[e]] (let [v (vector/vector-of db e)]
                        [(- (/ (reduce + (map * q v)) (Math/sqrt (reduce + (map * v v))))) e])))
       sort (take k) (mapv second)))

(deftest a-stored-vector-keeps-what-it-was-made-from
  (let [v (first (vectors 7 1 1 768))
        {:seon.vector/keys [f16 coarse]} (vector/pack v)
        db (db-with-vectors [v])
        e (ffirst (d/q '[:find ?e :where [?e :form/id "f0"]] db))]
    (is (= 1536 (count f16)) "the whole vector, f16")
    (is (= (+ 128 4) (count coarse)) "the coarse level: its first dims as int8, and their scale")
    (is (< (apply max (map #(Math/abs (- %1 %2)) v (vector/vector-of db e))) 0.001) "f16 keeps each dim to its precision")))

(deftest the-pyramid-ranks-as-an-exact-search
  (let [vs (vectors 11 600 24 768)
        db (db-with-vectors vs)
        queries (take 40 (vectors 13 40 24 768))
        hits (fn [opts] (for [q queries]
                          (count (filter (set (exact db q 10)) (map first (vector/near db q 10 opts))))))]
    (is (= (* 10 (count queries)) (reduce + (hits {:shortlist 600}))) "a shortlist of every vector ranks them all exactly")
    (is (thrown? Exception (vector/near db (vec (repeat 64 0.125)) 10)) "a query of another width is refused, never read past")
    (is (<= 0.99 (/ (reduce + (hits {})) (* 10.0 (count queries)))) "the default levels find the exact top 10")
    (is (> 0.99 (/ (reduce + (hits {:shortlist 10 :fine-dims 64})) (* 10.0 (count queries))))
        "a shortlist of k ranked on a short prefix misses some: the levels are what keeps the recall")))

(deftest near-is-a-relation-in-a-query
  (let [[a b c] (vectors 5 3 3 768)
        close (unit (mapv + a (map #(* 0.05 %) b)))
        db (d/db-with (db-with-vectors [a b c close]) [{:db/id "s" :seon.section/address "x.md#y" :seon.section/heading "y"}])
        db (d/db-with db [(assoc (vector/pack close) :db/id [:seon.section/address "x.md#y"] :seon.vector/model "m")])
        f0 (ffirst (d/q '[:find ?e :where [?e :form/id "f0"]] db))]
    (is (= (rest (exact db (vector/vector-of db f0) 3))
           (mapv first (sort-by second > (d/q '[:find ?e ?s :in $ ?q :where [(seon.vector/near $ ?q 2 {:of :form/id}) [[?e ?s]]] [?e :form/id]] db f0))))
        "an entity's nearest others, as rows a query joins; :of keeps the forms")
    (is (= "f3" (:form/id (d/entity db (first (first (vector/near db f0 1 {:of :form/id})))))) "the form made beside it is first")
    (is (= #{"f0" "f3" "x.md#y"} (set (map first (d/q '[:find ?n :in $ ?q :where [(seon.vector/near $ ?q 3) [[?e _]]] (or [?e :form/id ?n] [?e :seon.section/address ?n])] db a))))
        "a vector asks too, over every kind: itself, and the form and section beside it")
    (is (= [] (vector/near (ts/test-db) a 3)) "no vectors: no rows")))

(deftest a-text-is-embedded-once
  (let [db (d/db-with (ts/test-db) [{:db/id "f" :form/id "x/1" :form/src "(defn g [])"}
                                     {:db/id "s" :seon.section/address "p.md#a" :seon.section/heading "a" :seon.section/text "old"}])
        due (vector/due db "m")
        mine (filterv #(#{"title: none | text: (defn g [])" "title: none | text: old"} (second %)) due)
        [f s] (map first mine)
        db (d/db-with db (embedded mine (vectors 3 2 2 768) "m"))]
    (is (and f s) "a form and a section with no vector are due, as the model reads them")
    (is (not-any? (comp #{f s} first) (vector/due db "m")) "embedded, they are not due again")
    (let [db (d/db-with db [[:db/add s :seon.section/text "new"]])]
      (is (= [[s "title: none | text: new"]] (mapv pop (filterv (comp #{f s} first) (vector/due db "m")))) "a changed text is due again, and only it"))
    (let [[_ _ t] (first (filter (comp #{s} first) mine))
          db (d/db-with db [[:db/add s :seon.section/text "changed while its round was out"]])
          db (d/db-with db (vector/tx-data [s] [t] (vectors 4 1 1 768) [10] "m"))]
      (is (= [s] (map first (filter (comp #{s} first) (vector/due db "m")))) "a vector of the text before is no vector of the text now"))
    (is (= #{f s} (set (filter #{f s} (map first (vector/due db "m2"))))) "another model's vectors are due again")))

(defn- db-due-only
  "The test store's seed with every text but form x/1's already embedded: x/1 alone is due."
  []
  (let [db (ts/test-db)
        seeded (vector/due db "m")
        db (d/db-with db (embedded seeded (vectors 2 (count seeded) 8 768) "m"))]
    (d/db-with db [{:db/id "f" :form/id "x/1" :form/src "(defn g [])"}])))

(deftest the-proc-asks-for-what-is-due-and-writes-it
  (let [db (db-due-only)
        st (vector/vectors-proc {:model "m" :texts 64})
        [st o] (vector/vectors-proc st :main {:t 10 :db db})
        ask (first (:to-api o))]
    (is (= :embed (get-in ask [:req :kind])) "main's notice asks the :embed effect")
    (is (empty? (:to-api (second (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 0}) :main {:t 10 :db db}))))
        "a proc of 0 texts a round asks nothing: the model never loads")
    (is (= ["title: none | text: (defn g [])"] (get-in ask [:req :req :texts])) "for the texts due, as the model reads them")
    (let [[st o2] (vector/vectors-proc st :main {:t 11 :db db})
          _ (is (empty? (:to-api o2)) "one round at a time: a notice during one is kept")
          [st o3] (vector/vectors-proc st :answers {:tag (:tag ask) :r {:value {:model "m" :vectors (vectors 1 1 1 768) :tokens [5]}}})
          w (first (:to-api o3))]
      (is (= {:op :tx :branch :db} (select-keys (get-in w [:req :req]) [:op :branch])) "the vectors go to main through the writer")
      (let [db2 (d/db-with db (get-in w [:req :req :tx-data]))
            [st o4] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t 12}})]
        (is (empty? (:to-api o4)) "the kept notice is older than the write: the write's own notice starts the next round")
        (let [[st o5] (vector/vectors-proc st :main {:t 12 :db db2})]
          (is (empty? (:to-api o5)) "nothing due: nothing asked, the proc idles")
          (is (= 1 (:embedded st))))))))

(deftest a-caught-up-proc-looks-only-at-a-commits-texts
  ;; task one-index step 2: with everything due asked for, the next notice reads the texts that commit
  ;; changed (seon.vector/written), not every text of the store
  (let [db (db-due-only)
        [st o] (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 64}) :main {:t 10 :db db})
        _ (is (seq (:to-api o)) "the first round reads the store: x/1 is due")
        [st _] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api o))) :r {:value {:model "m" :vectors (vectors 1 1 1 768) :tokens [5]}}})
        [st _] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api (second (vector/vectors-proc st :main {:t 10 :db db}))))) :r {:ok true :t 11}})
        db2 (d/db-with db [{:db/id "g" :form/id "x/2" :form/src "(defn h [])"}])
        [_ o2] (vector/vectors-proc (assoc st :running false :written-t 10) :main {:t 12 :db db2})]
    (is (= ["title: none | text: (defn h [])"] (get-in (first (:to-api o2)) [:req :req :texts])) "the commit's new text alone is asked")))

(deftest a-failed-embedding-waits-for-the-next-notice
  (let [db (db-due-only)
        [st o] (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 64}) :main {:t 10 :db db})
        e (contract/error :seon.vector/model-unopened 'seon.vector/open "no model file" {:path "m.gguf"})
        [st o2] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api o))) :r e})]
    (is (empty? (:to-api o2)) "nothing is written")
    (let [[st o4] (vector/vectors-proc st :main {:t 11 :db db})
          _ (is (seq (:to-api o4)) "the next notice tries again")
          e (contract/error :seon.vector/encode-failed 'seon.vector/embed "llama.cpp's encode answered -2" {:code -2})
          [st _] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api o4))) :r e})
          [_ o5] (vector/vectors-proc st :main {:t 12 :db db})]
      (is (empty? (:to-api o5)) "texts the model could not encode are set aside: nothing else is due, so nothing is asked"))))

(deftest a-failed-embedding-is-recorded-and-its-own-commit-asks-nothing
  ;; root code records its own failed effect (seon.error/asked-tx): the :effect row names the vectors'
  ;; :seon.vector/model, closed by the next vectors written; the row's own commit starts no round,
  ;; or an idle main would ask, fail and write for ever
  (let [db (db-due-only)
        [st o] (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 64}) :main {:t 10 :db db})
        e (assoc (contract/error :seon.vector/model-unopened 'seon.vector/open "no model file" {:path "m.gguf"}) :seon.fx/at-ms 1000)
        [st o2] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api o))) :r e})
        w (first (:to-api o2))
        rows (fn [db] (get-in (seon.problems/report db) [:classes :effect :rows]))
        db2 (d/db-with db (get-in w [:req :req :tx-data]))]
    (is (= {:op :tx :branch :db} (select-keys (get-in w [:req :req]) [:op :branch])) "the failure is written to main")
    (is (= [[:seon.vector/model-unopened :seon.vector/model]] (map (juxt :type #(get-in % [:example :seon.error/subject])) (rows db2)))
        "as one :effect row naming the vectors' attribute")
    (let [[st o3] (vector/vectors-proc st :main {:t 11 :db db2})
          _ (is (empty? (:to-api o3)) "a notice while the write is out is kept")
          [st o4] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t 11}})
          _ (is (empty? (:to-api o4)) "the kept notice is the row's own commit: no round")
          [st o5] (vector/vectors-proc st :main {:t 11 :db db2})
          _ (is (empty? (:to-api o5)) "nor when it arrives after")
          [_ o6] (vector/vectors-proc st :main {:t 12 :db db2})]
      (is (= :embed (get-in (first (:to-api o6)) [:req :kind])) "a later commit tries again"))))

(deftest rrf-fuses-rankings-by-reciprocal-rank
  ;; kept for exploration (owner, 2026-10-24): an item in both rankings beats one high in only one
  (is (= '[b a c] (vector/rrf '[[a b] [b c]]))))

(defn- on-branch
  "f called in a test-mode agent (its writes apply to its own db) working task \"t/x\" on a
  branch that forked main db base and holds db, each :embed it asks answered by answer (a fn of
  the texts) and counted; answers [f's value, the branch's db after, the texts asked]."
  [base db answer f]
  (let [old @agent/state asked (atom [])]
    (reset! agent/state {:n 0 :mode :test :db db :base base :task "t/x"})
    (try (let [v (binding [agent/*fx-stub* (fn [kind {:keys [texts]}] (swap! asked conj [kind texts]) (answer texts))]
                   (f))]
           [v (:db @agent/state) @asked])
         (finally (reset! agent/state old)))))

(defn- answered [texts] {:value {:model "m" :vectors (vectors (count texts) (count texts) 1 768) :tokens (vec (repeat (count texts) 7))}})

(defn- plan-of [db] (ffirst (d/q '[:find ?e :where [?e :seon.task/id "t/x"]] db)))

(deftest a-plan-rewritten-on-a-branch-gets-its-vector-there
  (let [main (d/db-with (ts/test-db) [{:seon.task/id "t/x" :seon.task/plan "first plan"}])
        e (plan-of main)
        main (d/db-with main (vector/tx-data [e] [(:tx (first (d/datoms main :eavt e :seon.task/plan)))] (vectors 1 1 1 768) [4] "m"))
        branch (d/db-with main [[:db/add e :seon.task/plan "the plan rewritten"] {:form/id "y/1" :form/src "(defn h [])"}])
        [n db asked] (on-branch main branch answered #(agent/embed-written! 64 "m"))]
    (is (= [[:embed ["task: code retrieval | query: the plan rewritten" "title: none | text: (defn h [])"]]] asked)
        "one request for what the branch wrote: the rewritten plan, as a search asks, and its new form")
    (is (= 2 n))
    (is (= (:tx (first (d/datoms db :eavt e :seon.task/plan))) (:v (first (d/datoms db :eavt e :seon.vector/text-t))))
        "the plan's vector on the branch is made from the plan as it is now")
    (is (empty? (vector/due db "m" (vector/written main db))) "nothing the branch wrote is due any more")
    (let [f (ffirst (d/q '[:find ?e :where [?e :form/id "y/1"]] db))]
      (is (= [f] (mapv first (vector/near db e 1 {:of :form/id}))) "near on the branch finds the form only the branch holds"))
    (let [e2 (contract/error :seon.vector/model-unopened 'seon.vector/open "no model file" {:path "m.gguf"})
          [r db2 asked2] (on-branch main branch (constantly e2) #(agent/embed-written! 64 "m"))]
      (is (= :seon.vector/model-unopened (:seon.error/type r)) "a failed embedding answers the effect's error")
      (is (= 1 (count asked2)))
      (is (= 2 (count (vector/due db2 "m" (vector/written main db2)))) "and the texts stay due"))))

(deftest a-turn-with-nothing-due-asks-nothing
  (let [main (d/db-with (ts/test-db) [{:seon.task/id "t/x" :seon.task/plan "a plan"}])
        e (plan-of main)
        main (d/db-with main (vector/tx-data [e] [(:tx (first (d/datoms main :eavt e :seon.task/plan)))] (vectors 1 1 1 768) [4] "m"))
        ;; the branch wrote facts, none of them a text: a turn's and an eval's records
        branch (d/db-with main [{:seon.run/id "r1"}])
        [n _ asked] (on-branch main branch answered #(agent/embed-written! 64 "m"))]
    (is (= 0 n))
    (is (empty? asked) "nothing written is due and the plan's vector is current: no request")
    (let [[n _ asked] (on-branch main (d/db-with main [{:form/id "z/1" :form/src "(defn z [])"}]) answered #(agent/embed-written! 0 "m"))]
      (is (= [0 []] [n asked]) "0 texts a request asks nothing, as on bin/test's roots"))))

(deftest more-due-than-one-request-takes-waits-for-the-next
  (let [main (d/db-with (ts/test-db) [{:seon.task/id "t/x" :seon.task/plan "a plan"}])
        e (plan-of main)
        main (d/db-with main (vector/tx-data [e] [(:tx (first (d/datoms main :eavt e :seon.task/plan)))] (vectors 1 1 1 768) [4] "m"))
        branch (d/db-with main [{:form/id "y/1" :form/src "(defn a [])"} {:form/id "y/2" :form/src "(defn b [])"} {:form/id "y/3" :form/src "(defn c [])"}])
        old @agent/state asked (atom [])]
    (reset! agent/state {:n 0 :mode :test :db branch :base main :task "t/x"})
    (try
      (binding [agent/*fx-stub* (fn [_ {:keys [texts]}] (swap! asked conj texts)
                                  (if (some #(clojure.string/includes? % "(defn b") texts)
                                    (contract/error :seon.vector/encode-failed 'seon.vector/embed "llama.cpp's encode answered -2" {:code -2})
                                    (answered texts)))]
        (is (= 1 (agent/embed-written! 1 "m")) "one of three, as one request takes")
        (is (= main (:embedded-from @agent/state (:base @agent/state))) "the rest still due: the next call reads from the same db")
        (is (= :seon.vector/encode-failed (:seon.error/type (agent/embed-written! 1 "m"))) "a text the model cannot encode fails its request")
        (is (= 1 (agent/embed-written! 1 "m")) "and is kept out of the next, so the text after it is embedded")
        (is (= 0 (agent/embed-written! 1 "m")) "nothing else due: nothing asked")
        (is (= [["title: none | text: (defn a [])"] ["title: none | text: (defn b [])"] ["title: none | text: (defn c [])"]] @asked)))
      (finally (reset! agent/state old)))))
