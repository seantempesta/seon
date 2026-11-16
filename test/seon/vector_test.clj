(ns seon.vector-test
  "Semantic search (seon.vector): a stored vector keeps what it was made from, the pyramid ranks
  as an exact search does, `near` is one more relation in a query, a text is embedded once, and
  the proc that keeps main's vectors current asks only for what is due, and a branch embeds
  what it writes there. The model runs only in an exec'd helper (docs/vector.md#the-model), so
  most of these use made vectors; the helper's own tests start the real one, and bin/check vectors
  proves the pyramid's recall on the checkout's own forms, embedded by the model."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.test-store :as ts]
            [seon.vector :as vector] [seon.contract :as contract] [seon.agent :as agent] [seon.problems] [seon.lib :as lib]
            [clojure.java.shell :as sh] [clojure.string :as str] [seon.fx :as fx] [clojure.core.async :as a]))

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
  (d/db-with (ts/test-db) (vec (mapcat (fn [i v] [{:db/id (str "f" i) :form/id (str "f" i)} (vector/fact (str "f" i) "0" v "m")]) (range) vs))))

(defn- embedded
  "The facts of due texts [[e text t] ...] embedded as vs, each text of 10 tokens, by model."
  [due vs model] (vector/tx-data (map first due) (map peek due) vs model))

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
  (let [vs (vectors 11 60 6 768)
        db (db-with-vectors vs)
        queries (take 4 (vectors 13 4 6 768))
        ;; each query's exact top 10 is the same for every option set: computed once (each stored vector unpacked once)
        truth (let [vecs (vec (for [[e] (d/q '[:find ?e :where [?e :form/id] [?v :seon.vector/of ?e] [?v :seon.vector/f16]] db)] [e (vector/vector-of db e)]))]
                (mapv (fn [q] (->> vecs (map (fn [[e v]] [(- (/ (reduce + (map * q v)) (Math/sqrt (reduce + (map * v v))))) e])) sort (take 10) (map second) set))
                      queries))
        hits (fn [opts] (for [[q t] (map vector queries truth)]
                          (count (filter t (map first (vector/near db q 10 opts))))))]
    (is (= (* 10 (count queries)) (reduce + (hits {:shortlist 60}))) "a shortlist of every vector ranks them all exactly")
    (is (thrown? Exception (vector/near db (vec (repeat 64 0.125)) 10)) "a query of another width is refused, never read past")
    (is (<= 0.99 (/ (reduce + (hits {:shortlist 40})) (* 10.0 (count queries)))) "the levels, with a shortlist of two thirds of the vectors as the default is of 600, find the exact top 10")
    (is (> 0.99 (/ (reduce + (hits {:shortlist 10 :fine-dims 64})) (* 10.0 (count queries))))
        "a shortlist of k ranked on a short prefix misses some: the levels are what keeps the recall")))

(deftest near-is-a-relation-in-a-query
  (let [[a b c] (vectors 5 3 3 768)
        close (unit (mapv + a (map #(* 0.05 %) b)))
        db (d/db-with (db-with-vectors [a b c close]) [{:db/id "s" :seon.doc/address "x.md#y" :seon.doc/heading "y"}])
        db (d/db-with db [(vector/fact [:seon.doc/address "x.md#y"] "0" close "m")])
        f0 (ffirst (d/q '[:find ?e :where [?e :form/id "f0"]] db))]
    (is (= (rest (exact db (vector/vector-of db f0) 3))
           (mapv first (sort-by second > (d/q '[:find ?e ?s :in $ ?q :where [(seon.vector/near $ ?q 2 {:of :form/id}) [[?e ?s]]] [?e :form/id]] db f0))))
        "an entity's nearest others, as rows a query joins; :of keeps the forms")
    (is (= "f3" (:form/id (d/entity db (first (first (vector/near db f0 1 {:of :form/id})))))) "the form made beside it is first")
    (is (= #{"f0" "f3" "x.md#y"} (set (map first (d/q '[:find ?n :in $ ?q :where [(seon.vector/near $ ?q 3) [[?e _]]] (or [?e :form/id ?n] [?e :seon.doc/address ?n])] db a))))
        "a vector asks too, over every kind: itself, and the form and section beside it")
    (is (= [] (vector/near (ts/test-db) a 3)) "no vectors: no rows")))

(deftest a-text-is-embedded-once
  ;; each document titled as the model was trained (docs/vector.md#relevance): a form by its var's
  ;; name, a section by its heading
  (let [db (d/db-with (ts/test-db) [{:db/id "f" :form/id "x/1" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn g [])"} {:var/name 'x/g :var/form "f"}
                                     {:db/id "s" :seon.doc/address "p.md#a" :seon.doc/heading "a" :seon.doc/text "old"}])
        due (vector/due db "m")
        mine (filterv #(#{"title: x/g | text: (defn g [])" "title: a | text: old"} (second %)) due)
        ;; each by its text: due answers in entity order, which says nothing of which is which
        f (some #(when (= "title: x/g | text: (defn g [])" (second %)) (first %)) mine)
        s (some #(when (= "title: a | text: old" (second %)) (first %)) mine)
        db (d/db-with db (embedded mine (vectors 3 2 2 768) "m"))]
    (is (and f s) "a form and a section with no vector are due, as the model reads them")
    (is (not-any? (comp #{f s} first) (vector/due db "m")) "embedded, they are not due again")
    (let [db (d/db-with db [[:db/add s :seon.doc/text "new"]])]
      (is (= [[s "title: a | text: new"]] (mapv pop (filterv (comp #{f s} first) (vector/due db "m"))))
          "a changed text is due again, and only it, titled by its heading"))
    (let [[_ _ t] (first (filter (comp #{s} first) mine))
          db (d/db-with db [[:db/add s :seon.doc/text "changed while its round was out"]])
          db (d/db-with db (vector/tx-data [s] [t] (vectors 4 1 1 768) "m"))]
      (is (= [s] (map first (filter (comp #{s} first) (vector/due db "m")))) "a vector of the text before is no vector of the text now"))
    (is (= #{f s} (set (filter #{f s} (map first (vector/due db "m2"))))) "another model's vectors are due again")
    (let [db (d/db-with db [{:db/id "a" :seon.agent/key "demo/a1"}
                            {:seon.eval/id "e1" :seon.eval/agent "a" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(f)" :seon.eval/response {}}])
          ev (:e (first (d/datoms db :avet :seon.eval/id "e1")))]
      (is (empty? (filterv (comp #{ev} first) (vector/due db "m")))
          "an eval is no text to embed: nothing reads its vector, and a turn would wait on the model for it"))))

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
        db (with! conn db (embedded seeded (take (count seeded) (cycle (vectors 2 8 8 768))) "m"))]
    (with! conn db [{:db/id "f" :form/id "x/1" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn g [])"}])))

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

(deftest ^{:seon.test/estimate {:ticks 20000000 :bytes 80000000}} a-search-with-a-vector-and-no-hits-gets-them-from-the-proc
  ;; docs/vector.md#search: a search's hits are written with its query's vector (seon.ctx.search/embedded-tx), so a store written before
  ;; that (main on 2026-11-16: 1082 searches with a vector, 2 with hits, result/a9c21e93) would draw no search on any page: the vectors
  ;; proc's first round owes those searches their hits and writes them from their stored vectors, :texts a round, with no embedding.
  ;; Estimate: a test branch with every text embedded (~5M), one search over a small store (~3M), two rounds (~2M): ~10M; 20000000 ticks, 80 MB
  (ts/test-branch! (fn [_ conn]
    (let [db @conn
          seeded (vector/due db "m")
          db (with! conn db (embedded seeded (take (count seeded) (cycle (vectors 2 8 8 768))) "m"))
          db (with! conn db [{:seon.search/id "t/x/request" :seon.search/query "messages sent while it ran are lost"}])
          s (:db/id (d/entity db [:seon.search/id "t/x/request"]))
          db (with! conn db (vector/tx-data [s] [(peek (first (filter (comp #{s} first) (vector/due db "m"))))] (vectors 3 1 1 768) "m"))
          st (vector/vectors-proc {:model "m" :texts 64 :conn conn})
          [st o] (vector/vectors-proc st :main (assoc (note db) :t 10))
          w (first (:to-api o))
          tx (get-in w [:req :req :tx-data])]
      (is (= :write (get-in w [:req :op])) (str "nothing to embed: the round writes the owed search's hits: " (pr-str (:to-api o))))
      (is (= "messages sent while it ran are lost" (:query (:seon.search/hits (some #(when (= s (:db/id %)) %) tx))))
          "its hits, for its query, from its stored vector")
      (let [db2 (with! conn db tx)
            [st _] (vector/vectors-proc st :answers {:tag (:tag w) :r {:ok true :t (:max-tx db2)}})
            [_ o2] (vector/vectors-proc st :main (assoc (note db2) :t (:max-tx db2)))]
        (is (not-any? #(some :seon.search/hits (get-in % [:req :req :tx-data])) (:to-api o2)) "written once: the next round owes it nothing"))
      ;; a proc whose state was made before this code arrived in place: past its first round (:clean), holding no :hits-owed
      (let [old (-> (vector/vectors-proc {:model "m" :texts 64 :conn conn}) (dissoc :hits-owed) (assoc :clean (note db) :seen (:commit (note db))))
            [_ o3] (vector/vectors-proc old :main (assoc (note db) :t 10))]
        (is (some #(some :seon.search/hits (get-in % [:req :req :tx-data])) (:to-api o3))
            "a proc started before the code arrived owes it at its next round"))))))

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
            db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn h [])"}])
            [_ db3 asked] (round conn st db2 12)]
        (is (= ["title: none | text: (defn h [])"] asked))
        (is (= [] (map first (vector/due db3 "m"))) "and nothing is due after it")))))
  (testing "a backlog larger than a round leaves the proc not caught up: the rest is asked next"
    (ts/test-branch! (fn [_ conn]
      (let [[st db1 _] (caught-up conn)
            db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn h [])"} {:db/id "k" :form/id "x/3" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn k [])"}])
            [st2 db3 asked1] (round conn (assoc st :texts 1) db2 12)
            [_ _ asked2] (round conn st2 db3 14)]
        (is (= 1 (count asked1)))
        (is (= 1 (count asked2)) "the other text, from a full scan")
        (is (not= asked1 asked2))))))
  (testing "a text changed while its round was out is asked again"
    (ts/test-branch! (fn [_ conn]
      (let [[st db1 _] (caught-up conn)
            db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn h [])"}])
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
                  db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn h [])"} {:db/id "k" :form/id "x/3" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn k [])"}])
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
      (let [db2 (with! conn db1 [{:db/id "g" :form/id "x/2" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn h [])"}])
            [st2 o] (vector/vectors-proc st :main (assoc (note db2) :t 12))
            ask (first (:to-api o))
            db3 (with! conn db2 [{:db/id "k" :form/id "x/3" :form/ns {:ns/name (quote app.t)} :form/order 0 :form/src "(defn k [])"}])
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

(defn- answered [texts] {:value {:model "m" :vectors (vectors (count texts) (count texts) 1 768) :tokens (vec (repeat (count texts) 7))}})

(defn- plan-of [db] (ffirst (d/q '[:find ?e :where [?e :seon.task/id "t/x"]] db)))

;; docs/vector.md#a-vector-is-its-own-entity: the ruling of 2026-10-29, an entity's record is facts about
;; its work; its embedding is a vector entity pointing at it (:seon.vector/of)
(deftest a-vector-is-its-own-entity
  (let [db (d/db-with (ts/test-db) [{:seon.task/id "t/x" :seon.task/plan "a plan"}
                                    {:db/id "e" :seon.eval/id "e1" :seon.eval/by {:seon.agent/key "a1"} :seon.eval/code "(f)" :seon.eval/response {}}])
        t (plan-of db)
        ev (ffirst (d/q '[:find ?e :where [?e :seon.eval/id "e1"]] db))
        [v1 v2] (vectors 3 2 2 768)
        db (d/db-with db (vector/tx-data [t ev] ["1" "1"] [v1 v2] "m"))
        vector-keys #(filter (comp #{"seon.vector"} namespace) (keys %))]
    (is (empty? (vector-keys (d/pull db '[*] t))) "an embedded task's own pull holds none of its vector")
    (is (empty? (vector-keys (d/pull db '[*] ev))) "nor an embedded eval's")
    (is (< (apply max (map #(Math/abs (- %1 %2)) v2 (vector/vector-of db ev))) 0.001) "its vector is found through :seon.vector/of")
    (is (= #{ev t} (set (map :v (d/datoms db :aevt :seon.vector/of)))) "one vector entity for each entity embedded")
    (is (= 2 (count (d/datoms db :aevt :seon.vector/of))) "and no more")
    (is (= #{"m"} (set (map :v (d/datoms db :aevt :seon.vector/model)))))
    (let [db2 (d/db-with db (vector/tx-data [ev] ["2"] [v1] "m2"))]
      (is (= 2 (count (d/datoms db2 :aevt :seon.vector/of))) "a vector written again replaces the entity's, in place")
      (is (< (apply max (map #(Math/abs (- %1 %2)) v1 (vector/vector-of db2 ev))) 0.001))
      (is (= #{[t "m"] [ev "m2"]} (set (for [e [t ev]] [e (:v (first (d/datoms db2 :eavt (:e (first (d/datoms db2 :avet :seon.vector/of e))) :seon.vector/model)))]))))
      (is (= [t] (map first (vector/due db2 "m2" (d/datoms db2 :aevt :seon.task/plan)))) "another model's vector is due again, the entity's own"))))

;; docs/vector.md#a-vector-is-its-own-entity: retracting an embedded entity drops the datom that pointed at its
;; vector; the round retracts the vector too, in the write of the embeddings or alone
(deftest a-retracted-entitys-vector-ends-with-it
  ;; docs/storage.md#the-write-checks-every-entity-it-touches: a vector's identity is a ref to the entity it embeds, so the
  ;; one transaction path retracts it with that entity; the round once swept it later, an orphan between
  (ts/test-branch! (fn [_ conn]
    (let [[st db1 _] (caught-up conn)
          x1 (:e (first (d/datoms db1 :avet :form/id "x/1")))
          ve (:e (first (d/datoms db1 :avet :seon.vector/of x1)))
          _ (is (some? ve) "the form is embedded")
          db2 (with! conn db1 [[:db/retractEntity x1]])]
      (is (empty? (d/datoms db2 :eavt ve)) "retracting the form retracts its vector in the same write")
      (is (= (dec (count (d/datoms db1 :aevt :seon.vector/coarse))) (count (d/datoms db2 :aevt :seon.vector/coarse))) "the others stay")
      (let [[st _] (vector/vectors-proc st :main (assoc (note db1) :t 11))
            [_ o] (vector/vectors-proc st :main (assoc (note db2) :t 12))]
        (is (empty? (:to-api o)) "and the round has nothing to sweep"))))))

;; ---- the model in its own process (docs/vector.md#the-model) ------------------------------------
;; These start the built binary's `embed-worker` (seon.vector/start! is a :seon.config.source/binary-vars
;; var, so bin/test builds target/seon first) and the real model, bin/build embed.

(defn- maps-model?
  "Does process pid hold the model file open or mapped (lsof lists a mapped file)?"
  [pid]
  (str/includes? (:out (sh/sh "lsof" "-p" (str pid))) ".gguf"))

(defn- gone? [pid] (not (zero? (lib/c-kill pid 0))))

;; embed's outcomes for each request's texts, its :frames stream run as the :embed worker runs it (seon.fx/worker-proc), each outcome
;; without the worker's timing: the one path a batch takes (docs/flow.md#effects-one-owner-and-its-workers-per-kind)
(defn- embed-now
  "Each request's outcome, one request a texts vector, of embed's batch run on handle as the :embed worker runs it."
  {:malli/schema [:=> [:cat :map [:* [:vector :string]]] [:vector :map]]}
  [handle & texts]
  (let [st (assoc (fx/worker-proc {:kind :embed :owner :fx/embed :perform vector/embed}) :handle handle)
        item {:batch (mapv #(hash-map :req {:texts %} :in-ns 0 :taken-ns 0) texts)}]
    (loop [[st o] (fx/worker-proc st :work item)]
      (if-let [d (first (get o [:fx/embed :done]))]
        (mapv #(dissoc (:outcome %) :seon.fx/queue-us :seon.fx/us :seon.fx/at-ms) (:batch d))
        (recur (fx/worker-proc st :lines (a/<!! (:lines st))))))))

(deftest root-holds-no-model-after-an-embed
  ;; the helper holds the model while work is queued, this process never; its end is the close, and
  ;; the next embed starts another
  (lib/ignore-sigpipe!)
  (let [handle (vector/open nil)
        embed #(embed-now handle %)
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

;; Estimate: one helper spawned and killed (a fork-exec and a reap, ~1M ticks) and two worker steps; 3,000,000
(deftest ^{:seon.test/estimate {:ticks 3000000}} a-stop-ends-a-helper-still-loading-its-model
  ;; the switch-over's L2 (seon.system/an-update-loses-nothing; docs/vector.md#the-model): the :embed worker's step only starts the
  ;; helper, whose model load (1.5-1.7 s [M result/c0a59861]) is read on the stream, so the worker's stop ends a helper still
  ;; loading and holds nothing. Red before: the step waited out the load (the helper start read its ready frame in the step), so every root's stop did
  ;; (:fx.embed/0 held :flow-stop 1,674 ms [M result/da72e7d0])
  (lib/ignore-sigpipe!)
  (let [handle (vector/open nil)
        st (assoc (fx/worker-proc {:kind :embed :owner :fx/embed :perform vector/embed}) :handle handle)
        [st o] (fx/worker-proc st :work {:batch [{:req {:texts ["x"]} :in-ns 0 :taken-ns 0}]})
        pid (:pid @(:facts handle))]
    (is (and (:call st) (nil? o)) "the step returns with the helper's call in flight")
    (fx/worker-proc st :clojure.core.async.flow/stop)
    (is (gone? pid) "the stop ends the helper, its model load unfinished")
    (is (nil? @(:helper handle)) "and the kind holds no helper")))

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
        embed #(embed-now handle % ["x"])
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
  ;; the idle helper is a scripted one (a shell reading its requests and answering none), held as start! holds one: what
  ;; embed meets is a held helper whose process is gone, the same whatever ran in it, and the real helper's model load is
  ;; paid once, by the replacement the claim is about (a real warm helper paid it twice: 2.75 s of the test's wall time)
  (lib/ignore-sigpipe!)
  (let [handle (vector/open nil)
        embed #(embed-now handle %)
        p (.start (ProcessBuilder. ^java.util.List ["sh" "-c" "cat > /dev/null"]))
        _ (vreset! (:helper handle) {:process p :pid (.pid p) :in (.getOutputStream p) :out (.getInputStream p)})
        h @(:helper handle)]
    (lib/c-kill (:pid h) 9)
    (.waitFor (:process h))
    (let [[r] (embed ["again"])]
      (is (= 1 (count (get-in r [:value :vectors]))) (pr-str (select-keys r [:seon.error/type :seon.error/message])))
      (is (not= (:pid h) (:pid @(:facts handle))) "another helper answered it"))
    (vector/close handle)))

;; docs/vector.md#the-pyramid: the coarse rows a search ranks are read once a db value, kept by its value as the text index is,
;; and the filter of an :of attribute with them: a page's search asks near three times on one value (one attribute each), and
;; each call scanned every coarse row and built the set of every form again (main, 2026-11-12: 9,127 vectors, 2.5M ticks a scan,
;; 15M with :of :form/id). A call on a kept value is the coarse kernel and the fine shortlist: the first's cost less its scan
(deftest ^{:seon.test/estimate {:ticks 20000000}} near-reads-the-coarse-rows-once-a-db-value
  (let [vs (vectors 11 600 12 768)
        db (db-with-vectors vs)
        q (nth vs 3)
        ticks (fn [] (:ticks (seon.profile/measure! #(vector/near db q 10 {:of :form/id}))))
        scan (:ticks (seon.profile/measure! #(#'seon.vector/coarse-rows db (into #{} (map :e) (d/datoms db :aevt :form/id)) nil)))
        first-ticks (ticks) again (ticks)]
    (is (= (vector/near db q 10 {:of :form/id}) (vector/near db q 10 {:of :form/id})) "the same answer")
    (is (< again (- first-ticks (* 0.8 scan))) (str "the second call on the value pays no scan (" scan " ticks): " first-ticks " then " again))))

;; docs/vector.md#search: the dependency source is derived data kept current by main's notice, as the vectors are: a round with
;; nothing to embed asks the :library effect at the lowest :prio when the pins or deps moved from those it was read at, and
;; nothing when they stand, or when the store depends on nothing (a test's). Estimate: the seed and two rounds: ~5M; 20,000,000
(deftest ^{:seon.test/estimate {:ticks 20000000 :bytes 40000000}} an-idle-round-reads-the-dependency-source-when-its-pins-move
  (ts/test-branch! (fn [_ conn]
  (let [db (db-due-only conn)
        db (with! conn db (embedded (vector/due db "m") (take (count (vector/due db "m")) (cycle (vectors 2 8 8 768))) "m"))
        st (vector/vectors-proc {:model "m" :texts 64 :conn conn})
        [_ o0] (vector/vectors-proc st :main (assoc (note db) :t 10))
        db (with! conn db [{:seon.pin/path "reference-code/lib" :seon.pin/commit "c1"}])
        [st o1] (vector/vectors-proc st :main (assoc (note db) :t 11))
        ask (first (:to-api o1))]
    (is (empty? (:to-api o0)) "a store that depends on nothing asks nothing")
    (is (= :library (get-in ask [:req :kind])) (str "a pin with no library read asks the :library effect: " (pr-str o1)))
    (is (= -1 (get-in ask [:req :prio])) "at the lowest priority")
    (is (= {"reference-code/lib" "c1"} (get-in ask [:req :req :pins])) "with the pins it reads at")
    (let [in (@#'vector/library-inputs db)
          db (with! conn db [{:seon.system/id :root :seon.library/inputs in}])
          [_ o2] (vector/vectors-proc (vector/vectors-proc {:model "m" :texts 64 :conn conn}) :main (assoc (note db) :t 12))]
      (is (empty? (:to-api o2)) "pins that stand ask nothing"))))))

