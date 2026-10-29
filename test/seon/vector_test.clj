(ns seon.vector-test
  "Semantic search (seon.vector): a stored vector keeps what it was made from, the pyramid ranks
  as an exact search does, `near` is one more relation in a query, a text is embedded once, and
  the proc that keeps main's vectors current asks only for what is due, and a branch embeds
  what it writes there. The model runs only in an exec'd helper (docs/vector.md#the-model), so
  most of these use made vectors; the helper's own tests start the real one, and bin/check vectors
  proves the pyramid's recall on the checkout's own forms, embedded by the model."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.test-store :as ts]
            [seon.vector :as vector] [seon.contract :as contract] [seon.agent :as agent] [seon.problems] [seon.lib :as lib]
            [clojure.java.shell :as sh] [clojure.string :as str]))

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
  (d/db-with (ts/test-db) (vec (mapcat (fn [i v] [{:db/id (str "f" i) :form/id (str "f" i)} (vector/fact (str "f" i) 0 v 8 "m")]) (range) vs))))

(defn- embedded
  "The facts of due texts [[e text t] ...] embedded as vs, each text of 10 tokens, by model."
  [due vs model] (vector/tx-data (map first due) (map peek due) vs (repeat (count due) 10) model))

(defn- exact
  "The k entities nearest q by the full cosine of each stored vector, computed here without the
  kernel."
  [db q k]
  (->> (d/q '[:find ?e :where [?e :form/id] [?v :seon.vector/of ?e] [?v :seon.vector/f16]] db)
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
        db (d/db-with db [(vector/fact [:seon.section/address "x.md#y"] 0 close 8 "m")])
        f0 (ffirst (d/q '[:find ?e :where [?e :form/id "f0"]] db))]
    (is (= (rest (exact db (vector/vector-of db f0) 3))
           (mapv first (sort-by second > (d/q '[:find ?e ?s :in $ ?q :where [(seon.vector/near $ ?q 2 {:of :form/id}) [[?e ?s]]] [?e :form/id]] db f0))))
        "an entity's nearest others, as rows a query joins; :of keeps the forms")
    (is (= "f3" (:form/id (d/entity db (first (first (vector/near db f0 1 {:of :form/id})))))) "the form made beside it is first")
    (is (= #{"f0" "f3" "x.md#y"} (set (map first (d/q '[:find ?n :in $ ?q :where [(seon.vector/near $ ?q 3) [[?e _]]] (or [?e :form/id ?n] [?e :seon.section/address ?n])] db a))))
        "a vector asks too, over every kind: itself, and the form and section beside it")
    (is (= [] (vector/near (ts/test-db) a 3)) "no vectors: no rows")))

(deftest a-text-is-embedded-once
  ;; each document titled as the model was trained (docs/vector.md#relevance): a form by its var's
  ;; name, a section by its heading
  (let [db (d/db-with (ts/test-db) [{:db/id "f" :form/id "x/1" :form/src "(defn g [])"} {:var/name 'x/g :var/form "f"}
                                     {:db/id "s" :seon.section/address "p.md#a" :seon.section/heading "a" :seon.section/text "old"}])
        due (vector/due db "m")
        mine (filterv #(#{"title: x/g | text: (defn g [])" "title: a | text: old"} (second %)) due)
        ;; each by its text: due answers in entity order, which says nothing of which is which
        f (some #(when (= "title: x/g | text: (defn g [])" (second %)) (first %)) mine)
        s (some #(when (= "title: a | text: old" (second %)) (first %)) mine)
        db (d/db-with db (embedded mine (vectors 3 2 2 768) "m"))]
    (is (and f s) "a form and a section with no vector are due, as the model reads them")
    (is (not-any? (comp #{f s} first) (vector/due db "m")) "embedded, they are not due again")
    (let [db (d/db-with db [[:db/add s :seon.section/text "new"]])]
      (is (= [[s "title: a | text: new"]] (mapv pop (filterv (comp #{f s} first) (vector/due db "m"))))
          "a changed text is due again, and only it, titled by its heading"))
    (let [[_ _ t] (first (filter (comp #{s} first) mine))
          db (d/db-with db [[:db/add s :seon.section/text "changed while its round was out"]])
          db (d/db-with db (vector/tx-data [s] [t] (vectors 4 1 1 768) [10] "m"))]
      (is (= [s] (map first (filter (comp #{s} first) (vector/due db "m")))) "a vector of the text before is no vector of the text now"))
    (is (= #{f s} (set (filter #{f s} (map first (vector/due db "m2"))))) "another model's vectors are due again")
    (let [db (d/db-with db [{:db/id "a" :seon.agent/key "demo/a1" :seon.agent/id :a1}
                            {:seon.eval/id "e1" :seon.eval/agent "a" :seon.eval/by "a1" :seon.eval/record {:code "(f)" :response {} :line "1"}}])
          ev (:e (first (d/datoms db :avet :seon.eval/id "e1")))]
      (is (= [[ev "task: search result | query: (f)\n1"]] (mapv pop (filterv (comp #{ev} first) (vector/due db "m"))))
          "an eval is embedded as a search asks: its code and its result's line"))))

(defn- with!
  "conn's head after tx-data is committed on it (db, the head before, is the one the test holds):
  main's dbs here are commits, as the writer's notices name them."
  [conn _db tx-data]
  (:db-after (d/transact conn {:tx-data tx-data})))

(defn- note
  "The writer's notice for the commit db (a head of a test branch): {:t :commit}."
  [db] {:t (:max-tx db) :commit (d/commit-id db)})

(defn- db-due-only
  "The test branch conn's seed with every text but form x/1's already embedded: x/1 alone is due."
  [conn]
  (let [db @conn
        seeded (vector/due db "m")
        db (with! conn db (embedded seeded (vectors 2 (count seeded) 8 768) "m"))]
    (with! conn db [{:db/id "f" :form/id "x/1" :form/src "(defn g [])"}])))

(deftest the-proc-asks-for-what-is-due-and-writes-it
  (ts/test-branch! (fn [_ conn]
  (let [db (db-due-only conn)
        st (vector/vectors-proc {:model "m" :texts 64 :conn conn})
        [st o] (vector/vectors-proc st :main (assoc (note db) :t 10))
        ask (first (:to-api o))]
    (is (= :embed (get-in ask [:req :kind])) "main's notice asks the :embed effect")
    (is (empty? (:to-api (second (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 0 :conn conn}) :main (assoc (note db) :t 10)))))
        "a proc of 0 texts a round asks nothing: the model never loads")
    (is (= ["title: none | text: (defn g [])"] (get-in ask [:req :req :texts])) "for the texts due, as the model reads them")
    (let [[st o2] (vector/vectors-proc st :main (assoc (note db) :t 11))
          _ (is (empty? (:to-api o2)) "one round at a time: a notice during one is kept")
          [st o3] (vector/vectors-proc st :answers {:tag (:tag ask) :r {:value {:model "m" :vectors (vectors 1 1 1 768) :tokens [5]}}})
          w (first (:to-api o3))]
      (is (= {:op :tx :branch :db} (select-keys (get-in w [:req :req]) [:op :branch])) "the vectors go to main through the writer")
      (let [db2 (with! conn db (get-in w [:req :req :tx-data]))
            [st o4] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t 12}})]
        (is (empty? (:to-api o4)) "the kept notice is older than the write: the write's own notice starts the next round")
        (let [[st o5] (vector/vectors-proc st :main (assoc (note db2) :t 12))]
          (is (empty? (:to-api o5)) "nothing due: nothing asked, the proc idles")
          (is (= 1 (:embedded st)))))))))
)

(defn- round
  "One whole round of the proc st at db, notice t: the notice (committed on conn), the :embed answered with vectors, the
  write answered. [st' db' the texts asked] (db' holds the vectors written), or [st db []] when
  nothing was asked."
  [conn st db t]
  (let [[st o] (vector/vectors-proc st :main (assoc (note db) :t t))
        ask (first (:to-api o))
        texts (get-in ask [:req :req :texts])]
    (if-not ask
      [st db []]
      (let [[st o2] (vector/vectors-proc st :answers {:tag (:tag ask) :r {:value {:model "m" :vectors (vectors 1 (count texts) 1 768) :tokens (vec (repeat (count texts) 5))}}})
            w (first (:to-api o2))
            [st _] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t (inc t)}})]
        [st (with! conn db (get-in w [:req :req :tx-data])) texts]))))

(defn- caught-up
  "A proc caught up at the first round on conn's seed (x/1 alone due): [st db1 asked]."
  [conn]
  (round conn (vector/vectors-proc {:model "m" :texts 64 :conn conn}) (db-due-only conn) 10))

;; once everything due is asked for the proc looks only at the texts a commit changed
;; (seon.vector/written): what it asks must still be what is due, whatever happened between;
;; each case on its own branch of the test store, as main's commits are linear
(deftest a-caught-up-proc-asks-for-exactly-what-a-full-scan-would
  (ts/test-branch! (fn [_ conn]
    (let [[_ _ asked] (caught-up conn)]
      (is (= ["title: none | text: (defn g [])"] asked) "the first round reads the store"))))
  (testing "a commit's new text alone"
    (ts/test-branch! (fn [_ conn]
      (let [[st db1 _] (caught-up conn)
            db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/src "(defn h [])"}])
            [_ db3 asked] (round conn st db2 12)]
        (is (= ["title: none | text: (defn h [])"] asked))
        (is (= [] (map first (vector/due db3 "m"))) "and nothing is due after it")))))
  (testing "a backlog larger than a round leaves the proc not caught up: the rest is asked next"
    (ts/test-branch! (fn [_ conn]
      (let [[st db1 _] (caught-up conn)
            db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/src "(defn h [])"} {:db/id "k" :form/id "x/3" :form/src "(defn k [])"}])
            [st2 db3 asked1] (round conn (assoc st :texts 1) db2 12)
            [_ _ asked2] (round conn st2 db3 14)]
        (is (= 1 (count asked1)))
        (is (= 1 (count asked2)) "the other text, from a full scan")
        (is (not= asked1 asked2))))))
  (testing "a text changed while its round was out is asked again"
    (ts/test-branch! (fn [_ conn]
      (let [[st db1 _] (caught-up conn)
            db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/src "(defn h [])"}])
            [st o] (vector/vectors-proc st :main (assoc (note db2) :t 12))
            ask (first (:to-api o))
            x2 (:e (first (d/datoms db2 :avet :form/id "x/2")))
            changed (with! conn db2 [[:db/add x2 :form/src "(defn h [] 1)"]])
            [st o2] (vector/vectors-proc st :answers {:tag (:tag ask) :r {:value {:model "m" :vectors (vectors 1 1 1 768) :tokens [5]}}})
            w (first (:to-api o2))
            [st _] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t 13}})
            written (with! conn changed (get-in w [:req :req :tx-data]))
            [_ o3] (vector/vectors-proc st :main (assoc (note written) :t 14))]
        (is (= ["title: none | text: (defn h [] 1)"] (get-in (first (:to-api o3)) [:req :req :texts]))))))))

(deftest a-more-round-followed-by-nothing-due-releases-the-helper
  ;; a request saying :more keeps the :embed kind's helper up; where the proc stops asking, an empty request without :more
  ;; is the event that lets it close (docs/vector.md#the-model)
  (letfn [(more-round [conn]
            (let [[st db1 _] (caught-up conn)
                  db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/src "(defn h [])"} {:db/id "k" :form/id "x/3" :form/src "(defn k [])"}])
                  [st o] (vector/vectors-proc (assoc st :texts 1) :main (assoc (note db2) :t 12))
                  ask (first (:to-api o))]
              (is (true? (get-in ask [:req :req :more])) "a round that leaves texts due says more are coming")
              [st ask db2]))]
    (testing "nothing due at the next notice"
      (ts/test-branch! (fn [_ conn]
        (let [[st ask db2] (more-round conn)
              [st o2] (vector/vectors-proc st :answers {:tag (:tag ask) :r {:value {:model "m" :vectors (vectors 1 1 1 768) :tokens [5]}}})
              w (first (:to-api o2))
              db3 (with! conn db2 (get-in w [:req :req :tx-data]))
              [st _] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t 13}})
              due (vector/due db3 "m")
              db4 (with! conn db3 (embedded due (vectors 5 (count due) 1 768) "m"))
              [st o3] (vector/vectors-proc st :main (assoc (note db4) :t 14))
              rel (first (:to-api o3))]
          (is (= [] (get-in rel [:req :req :texts])) "an empty request")
          (is (nil? (get-in rel [:req :req :more])) "without :more: its answer lets the helper close")
          (is (= :embed (get-in rel [:req :kind])))
          (is (empty? (:to-api (second (vector/vectors-proc st :answers {:tag (:tag rel) :r {:value {:vectors [] :tokens []}}})))) "and its answer asks nothing"))))
    (testing "a failed write starts no next round"
      (ts/test-branch! (fn [_ conn]
        (let [[st ask _] (more-round conn)
              [st o2] (vector/vectors-proc st :answers {:tag (:tag ask) :r {:value {:model "m" :vectors (vectors 1 1 1 768) :tokens [5]}}})
              w (first (:to-api o2))
              [_ o3] (vector/vectors-proc st :answers {:tag (:tag w) :r {:seon.error/type :seon.writer/refused :seon.error/message "no"}})]
          (is (= [[] nil] [(get-in (first (:to-api o3)) [:req :req :texts]) (get-in (first (:to-api o3)) [:req :req :more])]) "released"))))))))

(deftest a-failed-embedding-waits-for-the-next-notice
  (ts/test-branch! (fn [_ conn]
  (let [db (db-due-only conn)
        [st o] (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 64 :conn conn}) :main (assoc (note db) :t 10))
        e (contract/error :seon.vector/model-unopened 'seon.vector/open "no model file" {:path "m.gguf"})
        [st o2] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api o))) :r e})]
    (is (empty? (:to-api o2)) "nothing is written")
    (let [[st o4] (vector/vectors-proc st :main (assoc (note db) :t 11))
          _ (is (seq (:to-api o4)) "the next notice tries again")
          e (contract/error :seon.vector/encode-failed 'seon.vector/embed "llama.cpp's encode answered -2" {:code -2})
          [st _] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api o4))) :r e})
          [_ o5] (vector/vectors-proc st :main (assoc (note db) :t 12))]
      (is (empty? (:to-api o5)) "texts the model could not encode are set aside: nothing else is due, so nothing is asked")))))
)

(deftest a-failed-embedding-is-recorded-and-its-own-commit-asks-nothing
  (ts/test-branch! (fn [_ conn]
  ;; root code records its own failed effect (seon.error/asked-tx): the :effect row names the vectors'
  ;; :seon.vector/model, closed by the next vectors written; the row's own commit starts no round,
  ;; or an idle main would ask, fail and write for ever
  (let [db (db-due-only conn)
        [st o] (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 64 :conn conn}) :main (assoc (note db) :t 10))
        e (assoc (contract/error :seon.vector/model-unopened 'seon.vector/open "no model file" {:path "m.gguf"}) :seon.fx/at-ms 1000)
        [st o2] (vector/vectors-proc st :answers {:tag (:tag (first (:to-api o))) :r e})
        w (first (:to-api o2))
        rows (fn [db] (get-in (seon.problems/report db) [:classes :effect :rows]))
        db2 (with! conn db (get-in w [:req :req :tx-data]))]
    (is (= {:op :tx :branch :db} (select-keys (get-in w [:req :req]) [:op :branch])) "the failure is written to main")
    (is (= [[:seon.vector/model-unopened :seon.vector/model]] (map (juxt :type #(get-in % [:example :seon.error/subject])) (rows db2)))
        "as one :effect row naming the vectors' attribute")
    (let [[st o3] (vector/vectors-proc st :main (assoc (note db2) :t 11))
          _ (is (empty? (:to-api o3)) "a notice while the write is out is kept")
          [st o4] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t 11}})
          _ (is (empty? (:to-api o4)) "the kept notice is the row's own commit: no round")
          [st o5] (vector/vectors-proc st :main (assoc (note db2) :t 11))
          _ (is (empty? (:to-api o5)) "nor when it arrives after")
          [_ o6] (vector/vectors-proc st :main (assoc (note db2) :t 12))]
      (is (= :embed (get-in (first (:to-api o6)) [:req :kind])) "a later commit tries again")))))
)

(defn- db-value? "Is x a db value (it carries a :max-tx)?" [x] (some? (:max-tx x)))

;; task seon.vector/vectors-clean-is-a-commit: the proc keeps references to main, never db values
(deftest a-caught-up-round-diffs-from-its-clean-commit
  (ts/test-branch! (fn [_ conn]
    (let [[st db1 asked] (caught-up conn)]
      (is (= 1 (count asked)) "the first round reads the store")
      (is (= #{:t :commit} (set (keys (:clean st)))) "caught up: :clean is a commit reference, not a db")
      (is (not-any? db-value? (vals st)) "the state holds no db value")
      (let [db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/src "(defn h [])"}])
            [st2 o] (vector/vectors-proc st :main (assoc (note db2) :t 12))
            ask (first (:to-api o))
            db3 (with! conn db2 [{:db/id "k" :form/id "x/3" :form/src "(defn k [])"}])
            [st3 o3] (vector/vectors-proc st2 :main (assoc (note db3) :t 13))]
        (is (= ["title: none | text: (defn h [])"] (get-in ask [:req :req :texts]))
            "the round diffs from the clean commit: only the new text is asked")
        (is (empty? (:to-api o3)) "a notice during the round is kept")
        (is (= #{:t :commit} (set (keys (:latest st3)))) "as a reference")
        (is (not-any? db-value? (vals (:latest st3))))
        (is (not-any? db-value? (vals st3))))))))

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
    (is (= [[:embed ["task: search result | query: the plan rewritten" "title: none | text: (defn h [])"]]] asked)
        "one request for what the branch wrote: the rewritten plan, as a search asks, and its new form")
    (is (= 2 n))
    (is (= (:tx (first (d/datoms db :eavt e :seon.task/plan))) (:v (first (d/datoms db :eavt (:e (first (d/datoms db :avet :seon.vector/of e))) :seon.vector/text-t))))
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

;; docs/vector.md#a-vector-is-its-own-entity: the ruling of 2026-10-29, an entity's record is facts about
;; its work; its embedding is a vector entity pointing at it (:seon.vector/of)
(deftest a-vector-is-its-own-entity
  (let [db (d/db-with (ts/test-db) [{:seon.task/id "t/x" :seon.task/plan "a plan"}
                                    {:db/id "e" :seon.eval/id "e1" :seon.eval/by "a1" :seon.eval/record {:code "(f)" :response {} :line "x"}}])
        t (plan-of db)
        ev (ffirst (d/q '[:find ?e :where [?e :seon.eval/id "e1"]] db))
        [v1 v2] (vectors 3 2 2 768)
        db (d/db-with db (vector/tx-data [t ev] [1 1] [v1 v2] [4 4] "m"))
        vector-keys #(filter (comp #{"seon.vector"} namespace) (keys %))]
    (is (empty? (vector-keys (d/pull db '[*] t))) "an embedded task's own pull holds none of its vector")
    (is (empty? (vector-keys (d/pull db '[*] ev))) "nor an embedded eval's")
    (is (< (apply max (map #(Math/abs (- %1 %2)) v2 (vector/vector-of db ev))) 0.001) "its vector is found through :seon.vector/of")
    (is (= [ev t] (sort-by #(- %) (map :v (d/datoms db :aevt :seon.vector/of)))) "one vector entity for each entity embedded")
    (is (= #{"m"} (set (map :v (d/datoms db :aevt :seon.vector/model)))))
    (let [db2 (d/db-with db (vector/tx-data [ev] [2] [v1] [5] "m2"))]
      (is (= 2 (count (d/datoms db2 :aevt :seon.vector/of))) "a vector written again replaces the entity's, in place")
      (is (< (apply max (map #(Math/abs (- %1 %2)) v1 (vector/vector-of db2 ev))) 0.001))
      (is (= #{[t "m"] [ev "m2"]} (set (for [e [t ev]] [e (:v (first (d/datoms db2 :eavt (:e (first (d/datoms db2 :avet :seon.vector/of e))) :seon.vector/model)))]))))
      (is (= [t] (map first (vector/due db2 "m2" (d/datoms db2 :aevt :seon.task/plan)))) "another model's vector is due again, the entity's own"))))

;; docs/vector.md#a-vector-is-its-own-entity: retracting an embedded entity drops the datom that pointed at its
;; vector; the round retracts the vector too, in the write of the embeddings or alone
(deftest a-retracted-entitys-vector-is-retracted-by-the-round
  (ts/test-branch! (fn [_ conn]
    (let [[st db1 _] (caught-up conn)
          x1 (:e (first (d/datoms db1 :avet :form/id "x/1")))
          ve (:e (first (d/datoms db1 :avet :seon.vector/of x1)))
          _ (is (some? ve) "the form is embedded")
          db2 (with! conn db1 [[:db/retractEntity x1]])
          _ (is (and (empty? (d/datoms db2 :avet :seon.vector/of x1)) (seq (d/datoms db2 :eavt ve :seon.vector/f16)))
                "retracting the form leaves its vector behind")
          _ (is (not-any? #{x1} (map first (vector/near db2 (first (vectors 1 1 1 768)) 50))) "and no search finds it")
          ;; the write's own commit is noticed, as in a root, before the retraction
          [st _] (vector/vectors-proc st :main (assoc (note db1) :t 11))
          [st3 o] (vector/vectors-proc st :main (assoc (note db2) :t 12))
          w (first (:to-api o))
          db3 (with! conn db2 (get-in w [:req :req :tx-data]))]
      (is (= :db (get-in w [:req :req :branch])) "nothing due: the retraction alone is written to main")
      (is (empty? (d/datoms db3 :eavt ve)) "the vector entity is gone")
      (is (= (dec (count (d/datoms db2 :aevt :seon.vector/coarse))) (count (d/datoms db3 :aevt :seon.vector/coarse))) "the others stay")
      (let [[_ o2] (vector/vectors-proc st3 :answers {:tag (:tag w) :r {:ok true :t 13}})]
        (is (empty? (:to-api o2)) "and nothing more is asked"))
      (is (empty? (vector/orphaned db3)) "a full scan finds none left")
      (is (= [ve] (distinct (map second (vector/orphaned db2)))) "a full scan finds it")))))

;; ---- the model in its own process (docs/vector.md#the-model) ------------------------------------
;; These start the built binary's `embed-worker` (seon.vector/start! is a :seon.config.source/binary-vars
;; var, so bin/test builds target/seon first) and the real model, bin/build embed.

(defn- maps-model?
  "Does process pid hold the model file open or mapped (lsof lists a mapped file)?"
  [pid]
  (str/includes? (:out (sh/sh "lsof" "-p" (str pid))) ".gguf"))

(defn- gone? [pid] (not (zero? (lib/c-kill pid 0))))

(deftest root-holds-no-model-after-an-embed
  ;; the helper holds the model while work is queued, this process never; its end is the close, and
  ;; the next embed starts another
  (lib/ignore-sigpipe!)
  (let [handle (vector/open nil)
        embed #(vector/embed {:batch [{:req {:texts %}}] :handle handle})
        [r] (embed ["title: none | text: (defn a [])" "title: none | text: (defn b [])"])
        pid (:pid @(:facts handle))]
    (testing "a batch is embedded: one unit vector of the model's dims per text"
      (is (= 2 (count (get-in r [:value :vectors]))) (pr-str (keys r)))
      (is (= 768 (count (first (get-in r [:value :vectors])))))
      (is (< 0.999 (reduce + (map * (first (get-in r [:value :vectors])) (first (get-in r [:value :vectors])))) 1.001) "normalised")
      (is (= (vector/model-id (seon.config/value :seon.config.vector/model)) (get-in r [:value :model]))))
    (testing "the model is the helper's, never this process's"
      (is (pos? pid))
      (is (maps-model? pid) "the helper maps the model")
      (is (not (maps-model? (lib/c-getpid))) "this process, root's place, does not"))
    (testing "the queue empty, the helper is closed and gone"
      (let [f (vector/close handle)]
        (is (= pid (:pid f)))
        (is (= 0 (:exit f)) "it exits 0 at the end of its input")
        (is (gone? pid))
        (is (nil? (vector/close handle)) "closing nothing is nothing")))
    (testing "a search after it ended starts a new helper and answers"
      (let [[r2] (embed ["task: search result | query: a"])
            pid2 (:pid @(:facts handle))]
        (is (= 1 (count (get-in r2 [:value :vectors]))) (pr-str r2))
        (is (not= pid pid2) "another process")
        (is (maps-model? pid2))
        (is (not (maps-model? (lib/c-getpid))))
        (vector/close handle)
        (is (gone? pid2))))))

(deftest a-helper-that-dies-mid-batch-answers-an-error-and-the-next-batch-works
  ;; a scripted helper (a shell) that reads the request's first byte and exits without answering: the write
  ;; succeeds and the answer never comes, deterministically (a real helper killed at a guessed moment
  ;; may die before the write, which embed retries)
  (lib/ignore-sigpipe!)
  (let [handle (vector/open nil)
        _ (.mkdirs (java.io.File. (.getParent (java.io.File. ^String (:err handle)))))
        p (.start (doto (ProcessBuilder. ^java.util.List ["sh" "-c" "head -c 1 > /dev/null; echo oops >&2; exit 3"])
                    (.redirectError (java.io.File. ^String (:err handle)))))
        _ (vreset! (:helper handle) {:process p :pid (.pid p) :in (.getOutputStream p) :out (.getInputStream p)})
        embed #(vector/embed {:batch [{:req {:texts %}} {:req {:texts ["x"]}}] :handle handle})
        rs (embed ["hello"])]
    (is (= 2 (count rs)) "every request of the batch is answered")
    (is (every? #(= :seon.vector/helper-died (:seon.error/type %)) rs) (pr-str (map #(select-keys % [:seon.error/type :seon.error/message]) rs)))
    (is (every? #(contract/valid? :seon.vector/helper-died %) rs))
    (is (integer? (:exit (first rs))) "its exit status is in the error")
    (is (str/includes? (:stderr (first rs)) "oops") "and its stderr, read from the file it was sent to")
    (is (nil? @(:helper handle)) "nothing is held")
    (testing "the next batch starts a real helper"
      (let [rs (embed ["again"])]
        (is (every? #(= 1 (count (get-in % [:value :vectors]))) rs) (pr-str rs))
        (is (some? (:pid @(:facts handle))))
        (vector/close handle)))))

(deftest a-helper-killed-while-idle-is-replaced-and-the-request-answered
  (lib/ignore-sigpipe!)
  (let [handle (vector/open nil)
        embed #(vector/embed {:batch [{:req {:texts %}}] :handle handle})
        _ (embed ["warm"])
        h @(:helper handle)]
    (lib/c-kill (:pid h) 9)
    (.waitFor (:process h))
    (let [[r] (embed ["again"])]
      (is (= 1 (count (get-in r [:value :vectors]))) (pr-str (select-keys r [:seon.error/type :seon.error/message])))
      (is (not= (:pid h) (:pid @(:facts handle))) "another helper answered it"))
    (vector/close handle)))
