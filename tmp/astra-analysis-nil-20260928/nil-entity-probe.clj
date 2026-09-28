(require '[seon.db :as db] '[seon.cluster.boot :as boot])
(declare q-after)
(let [database (db/db (boot/connection "default")) q '[:find (count ?sym) . :in $ ?e :where [?e :seon.fn/sym ?sym]]] (mapv (fn [[label f input]] (let [start (System/nanoTime) value (f q database input)] {:label label :value value :ms (/ (- (System/nanoTime) start) 1e6)})) [[:parent-nil db/q nil] [:candidate-nil q-after nil] [:parent-valid db/q [:seon.fn/sym 'seon.db/q]] [:candidate-valid q-after [:seon.fn/sym 'seon.db/q]]]))
