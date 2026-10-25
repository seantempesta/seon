(ns seon.vector-test
  "Semantic search (seon.vector): a stored vector keeps what it was made from, the pyramid ranks
  as an exact search does, `near` is one more relation in a query, a text is embedded once, and
  the proc that keeps main's vectors current asks only for what is due. The model itself runs
  only in root (it starts threads), so these use made vectors; bin/check vectors proves the
  pyramid's recall on the checkout's own forms, embedded by the model."
  (:require [clojure.test :refer [deftest is testing]] [datahike.api :as d] [seon.test-store :as ts]
            [seon.vector :as vector] [seon.contract :as contract]))

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

(deftest rrf-fuses-rankings-by-reciprocal-rank
  ;; kept for exploration (owner, 2026-10-24): an item in both rankings beats one high in only one
  (is (= '[b a c] (vector/rrf '[[a b] [b c]]))))
