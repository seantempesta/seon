;; one-merge-base-probes.clj -- lane omb-c1, 2026-10-15. REPL probes behind "## C: REPL confirmation" in one-merge-base.md.
;;
;; Two environments (neither writes to default):
;;  (A) default's JVM, read-only, through the `eval_clj` entrance (jvm mode): the Seon pieces (`seon.program`,
;;      `seon.db`, `seon.cluster.registry`); forms marked ;; A.
;;  (B) a disposable JVM on the datahike fork, `cd reference-code/datahike && clojure -J-Dclojure.server.repl=...
;;      -M:test`, opened on an APFS clone (`cp -cR data/store <scratch>`) of the real store, or on in-memory stores;
;;      forms marked ;; B. Anything that writes is B only.

;; ---------------------------------------------------------------------------------------------------------------
;; B: open the clone. (store config copied from `(:config @(:seon.store/connection-object store))` on default)
(require '[datahike.api :as d] '[datahike.versioning :as v] '[konserve.core :as k])
(def clone "<scratch>/store-clone")
(defn cfg [branch]
  {:attribute-refs? false :branch branch :crypto-hash? false :fuse-index-roots? true
   :index :datahike.index/persistent-set :index-config {:branching-factor 128 :diff-buf-size 32}
   :keep-history? true :schema-flexibility :write :search-cache-size 10000
   :store-cache-bytes 512000000 :store-cache-size 1000
   :store {:backend :file :path clone :id #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7"
           :config {:defer-immutable-sync? true :compressor {:type :lz4}}}
   :writer {:backend :self}})
(def main (d/connect (cfg :cluster-default)))      ; main's branch is :cluster-default, not :db
(def chain (d/connect (cfg :chain-f1f6dc8e6652)))
(def kstore (:store @main))

;; measurement helper: wall ms and the calling thread's allocated MB (undercounts konserve reads, see
;; datahike-internals.md "Verification limits")
(defn measure* [f]
  (let [b (java.lang.management.ManagementFactory/getThreadMXBean)
        id (.getId (Thread/currentThread))
        a0 (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean b id)
        t0 (System/nanoTime)
        r (f)]
    {:ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0)
     :alloc-mb (/ (Math/round (/ (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean b id) a0) 1e4)) 100.0)
     :result r}))

;; ---------------------------------------------------------------------------------------------------------------
;; B, claim 1: the merge base by git's paint-down walk, over commit records read from the store.
;; `rec` decodes the WHOLE commit record (about 2.4 MB with fused index roots); `parents-of` keeps what the walk
;; needs. Priority: :datahike/updated-at (the commit instant; :datahike/created-at is the DB creation date, constant for every commit; phase 1 replaces both by a generation number).
(defn rec [ks cid] (k/get ks cid nil {:sync? true}))
(defn node-of [ks cid]
  (let [r (rec ks cid)]
    {:parents (get-in r [:meta :datahike/parents]) :at (get-in r [:meta :datahike/updated-at])
     :max-tx (:max-tx r) :branch (get-in r [:config :branch])}))

(defn paint-down
  "git's paint_down_to_common over `node` (cid -> {:parents :at [:gen]}). Flags: 1 reached from a, 2 from b, 4 stale,
  8 result. Answers {:bases (every result, redundant ones included) :pops n}. Stops when only stale entries remain."
  [node a b]
  (let [flags (volatile! {a 1, b 2}) result (volatile! #{}) pops (volatile! 0)
        prio (fn [c] (let [n (node c)] (or (:gen n) (:at n)))) ; generation number when the node has one, else the commit instant
        cmp (fn [x y] (let [c (compare (prio y) (prio x))] (if (zero? c) (compare x y) c)))
        q (volatile! (sorted-set-by cmp a b))]
    (while (some #(zero? (bit-and 4 (get @flags % 0))) @q)
      (let [c (first @q) _ (vswap! q disj c) _ (vswap! pops inc)
            f (get @flags c 0)
            fl (bit-and f 7)
            fl (if (= 3 fl)
                 (do (vswap! result conj c) (bit-or fl 4))
                 fl)]
        (doseq [p (:parents (node c))]
          (let [pf (get @flags p 0)]
            (when-not (= (bit-and pf fl) fl)
              (vswap! flags assoc p (bit-or pf fl))
              (vswap! q conj p))))))
    {:bases @result :pops @pops}))

(defn ancestor? "is x an ancestor of y (or y itself) by walking parents" [node x y]
  (loop [seen #{} todo [y]]
    (cond (empty? todo) false
          (= x (peek todo)) true
          :else (let [c (peek todo)]
                  (if (seen c) (recur seen (pop todo))
                    (recur (conj seen c) (into (pop todo) (:parents (node c)))))))))
(defn best-bases "remove bases that are ancestors of another base (git remove_redundant)" [node bases]
  (into #{} (remove (fn [x] (some #(and (not= x %) (ancestor? node x %)) bases))) bases))

;; ---------------------------------------------------------------------------------------------------------------
;; A and B, claims 2 and 3: the address-skipping diff of two persistent-sorted-set indexes, limited to a key range.
;; Pure datahike/PSS (no Seon code), so the same text runs in A (default's JVM) and B.
;;
;; A Branch holds, per child i: the child's max key (`.keys`), its address (`.address`), and a Slot (`.slots`) whose
;; `.diff` buffers pending edits ({child-index -> {:diff {:present [datoms] :absent [datoms]} ...}}). A child's
;; effective content is its anchor node (+ buffered edits). The walk therefore
;;   1. drops every child pair whose ADDRESS both sides hold (identical stored subtree),
;;   2. collects every datom named in any slot of an expanded Branch (extras),
;;   3. compares the anchors of the leaves that remain (candidates = symmetric difference + extras), and
;;   4. decides each candidate by membership in the two full sets, so the answer is exact.
(import '(org.replikativ.persistent_sorted_set PersistentSortedSet Branch Leaf ANode Slot)
        '(datahike.datom Datom))

(defn- dkey [^Datom d] [(.-e d) (.-a d) (.-v d) (.-tx d)])
(defn- slot-datoms [^Branch br i]
  (let [^Slot s (aget ^objects (.slots br) i)]
    (when s (into [] (filter #(instance? Datom %))
                  (tree-seq #(and (coll? %) (not (instance? Datom %))) seq (.-diff s))))))
(defn- child-in-range? "child i covers (key[i-1], key[i]]; the index is AEVT so a leads. lo/hi are attribute keywords."
  [^Branch br i lo hi]
  (let [ks (.keys br)]
    (and (not (neg? (compare (.-a ^Datom (.get ks i)) lo)))
         (or (zero? i) (not (pos? (compare (.-a ^Datom (.get ks (dec i))) hi)))))))

(defn pss-diff
  "Datoms only in `a` and only in `b` (PersistentSortedSets of datoms, AEVT order) whose attribute is in `attrs`.
  Answers {:only-a :only-b :nodes n :leaves n :extras n :candidates n}."
  [^PersistentSortedSet a ^PersistentSortedSet b attrs]
  (let [lo (first (sort attrs)) hi (last (sort attrs)) attrs (set attrs)
        sa (.-_storage a) sb (.-_storage b)
        ra ^ANode (.root a) rb ^ANode (.root b)
        visited (volatile! 0) extras (volatile! (transient #{}))
        leaves-a (volatile! (transient #{})) leaves-b (volatile! (transient #{}))
        entries (fn [nodes] ; -> [[addr branch i] ...] for the in-range children; every child's slot datoms are extras
                  (vec (for [[_ ^Branch br] nodes, i (range (.len br))
                             :let [_ (doseq [d (slot-datoms br i)] (vswap! extras conj! (dkey d)))]
                             :when (child-in-range? br i lo hi)]
                         [(.address br i) br i])))
        keep-unmatched (fn [ea eb] (let [common (clojure.set/intersection (set (keep first ea)) (set (keep first eb)))]
                                     [(remove #(common (first %)) ea) (remove #(common (first %)) eb)]))
        materialize (fn [st es] (mapv (fn [[addr ^Branch br i]] (vswap! visited inc) [addr (.child br st (int i))]) es))
        la (.level ra) lb (.level rb)
        same-root? (and (.-_address a) (= (.-_address a) (.-_address b)))]
    (when-not same-root?
      (loop [L (max la lb)
             A (if (== la L) [[(.-_address a) ra]] [])
             B (if (== lb L) [[(.-_address b) rb]] [])]
        (let [common (clojure.set/intersection (set (keep first A)) (set (keep first B)))
              A (remove #(common (first %)) A) B (remove #(common (first %)) B)]
          (if (zero? L)
            (do (doseq [[_ ^Leaf lf] A, k (.keys lf)] (vswap! leaves-a conj! (dkey k)))
                (doseq [[_ ^Leaf lf] B, k (.keys lf)] (vswap! leaves-b conj! (dkey k))))
            (let [[ea eb] (keep-unmatched (entries A) (entries B))
                  nxt (dec L)]
              (recur nxt
                     (into (if (== la nxt) [[(.-_address a) ra]] []) (materialize sa ea))
                     (into (if (== lb nxt) [[(.-_address b) rb]] []) (materialize sb eb))))))))
    (let [la (persistent! @leaves-a) lb (persistent! @leaves-b) ex (persistent! @extras)
          in-attrs (fn [[_ at _ _]] (attrs at))
          cands (into #{} (filter in-attrs) (concat (clojure.set/difference la lb) (clojure.set/difference lb la) ex))
          mk (fn [[e at v tx]] (datahike.datom/datom e at v tx))
          in? (fn [^PersistentSortedSet s k] (contains? s (mk k)))]
      {:only-a (into [] (filter #(and (in? a %) (not (in? b %)))) cands)
       :only-b (into [] (filter #(and (in? b %) (not (in? a %)))) cands)
       :nodes @visited :leaves (+ (count la) (count lb)) :extras (count ex) :candidates (count cands)})))

;; B, claim 3: the diff against a plain set difference of the datoms of the same attributes.
(def program-attrs #{:seon.program/definition-digest :seon.fn/sym :seon.test/sym :seon.ns/name :seon.schema/key})
(defn plain-diff [da db attrs]
  (let [ks (fn [d] (into #{} (mapcat (fn [at] (map (fn [x] [(:e x) (:a x) (:v x) (:tx x)]) (d/datoms d {:index :aevt :components [at]})))) attrs))
        a (ks da) b (ks db)]
    {:only-a (clojure.set/difference a b) :only-b (clojure.set/difference b a) :n-a (count a) :n-b (count b)}))
(defn check-diff [da db attrs]
  (let [f (measure* #(pss-diff (:aevt da) (:aevt db) attrs))
        p (measure* #(plain-diff da db attrs))
        fr (:result f) pr (:result p)]
    {:equal? (and (= (set (:only-a fr)) (:only-a pr)) (= (set (:only-b fr)) (:only-b pr)))
     :only-a (count (:only-a pr)) :only-b (count (:only-b pr)) :datoms-a (:n-a pr) :datoms-b (:n-b pr)
     :diff-ms (:ms f) :diff-mb (:alloc-mb f) :nodes (:nodes fr) :plain-ms (:ms p) :plain-mb (:alloc-mb p)}))

;; one contiguous AEVT range per attribute: `pss-diff`'s [lo..hi] over several attributes spans every attribute
;; between them alphabetically, so the range bound is applied per attribute and the answers are concatenated.
(defn pss-diff-by-attr [a b attrs]
  (reduce (fn [acc at] (let [r (pss-diff a b #{at})]
                         (-> acc (update :only-a into (:only-a r)) (update :only-b into (:only-b r))
                             (update :nodes + (or (:nodes r) 0)))))
          {:only-a [] :only-b [] :nodes 0} attrs))
(defn check-diff-by-attr [da db attrs]
  (let [f (measure* #(pss-diff-by-attr (:aevt da) (:aevt db) attrs)) p (measure* #(plain-diff da db attrs))
        fr (:result f) pr (:result p)]
    {:equal? (and (= (set (:only-a fr)) (:only-a pr)) (= (set (:only-b fr)) (:only-b pr)))
     :only-a (count (:only-a pr)) :only-b (count (:only-b pr))
     :diff-ms (:ms f) :diff-mb (:alloc-mb f) :nodes (:nodes fr) :plain-ms (:ms p) :plain-mb (:alloc-mb p)}))

;; B, claim 3, larger: every attribute of the schema (one range each) for main head vs chain head: the diff must equal the
;; plain set difference attribute by attribute; sums show how much of the store really differs.
(defn all-attrs-check [da db]
  (let [attrs (vec (sort (filter keyword? (keys (:schema da)))))
        rows (mapv (fn [at] (let [f (pss-diff (:aevt da) (:aevt db) #{at}) p (plain-diff da db #{at})]
                              {:attr at :equal? (and (= (set (:only-a f)) (:only-a p)) (= (set (:only-b f)) (:only-b p)))
                               :only-a (count (:only-a p)) :only-b (count (:only-b p)) :n (:n-a p) :nodes (:nodes f)}))
                   attrs)]
    {:attrs (count rows) :mismatches (mapv :attr (remove :equal? rows))
     :differing-attrs (count (filter #(pos? (+ (:only-a %) (:only-b %))) rows))
     :only-a (reduce + (map :only-a rows)) :only-b (reduce + (map :only-b rows)) :datoms (reduce + (map :n rows))
     :largest (take 3 (sort-by #(- (+ (:only-a %) (:only-b %))) rows))}))

;; ---------------------------------------------------------------------------------------------------------------
;; B, claims 4 and 5: the chain lifecycle on an in-memory store of the fork. Everything here writes, so B only.
;; `with-generation` adds git's generation number (1 + max parent generation; roots 1) to a node reader, memoized,
;; as phase 1 will stamp it in the commit meta.
(defn mem-cfg [] {:store {:backend :memory :id (random-uuid)} :keep-history? true :schema-flexibility :write
                  :index-config {:branching-factor 32 :diff-buf-size 8} :initial-tx [{:db/ident :k/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                                                                                     {:db/ident :k/who :db/valueType :db.type/string :db/cardinality :db.cardinality/one}]})
(defn mem-node [conn] (let [ks (:store @conn)] (memoize (fn [cid] (node-of ks cid)))))
(defn with-generation [node]
  (let [gen (memoize (fn gen [cid] (let [ps (:parents (node cid))] (if (empty? ps) 1 (inc (apply max (map gen ps)))))))]
    (fn [cid] (assoc (node cid) :gen (gen cid)))))
(defn tx! [conn tag n] (d/transact conn (vec (for [i (range n)] {:k/n (long i) :k/who (str tag "-" i)}))))
(defn merge-base [node a b] (best-bases node (:bases (paint-down node a b))))
(defn forward-merge!
  "What phase 2 asks of `merge-main!`: one transaction on `chain` carrying `:parents #{main-head}`, written exactly as
  `seon.db/transact!` does for a request with :parents: `@(d/merge-db! connection request)`."
  [chain main tx-data]
  @(d/merge-db! chain {:parents #{(d/commit-id @main)} :tx-data tx-data}))

;; B, claims 1/3/4 as a property on random histories: random transactions, branches and cross merges on an in-memory
;; store; (1) paint-down with generation numbers = brute-force lowest common ancestors for every pair of heads,
;; (2) the time-ordered walk's mismatches are counted (phase 1 needs the generation for this reason),
;; (3) pss-diff = plain datom set difference for random pairs of commits (small branching factor, so slots occur).
(defn all-ancestors [node c] (loop [seen #{} todo [c]] (if (empty? todo) seen (let [x (peek todo)] (if (seen x) (recur seen (pop todo)) (recur (conj seen x) (into (pop todo) (:parents (node x)))))))))
(defn brute-lca [node a b]
  (let [common (clojure.set/intersection (all-ancestors node a) (all-ancestors node b))]
    (into #{} (remove (fn [x] (some #(and (not= x %) (contains? (all-ancestors node %) x)) common))) common)))
(defn random-history! [seed n-ops]
  (let [rnd (java.util.Random. seed) pick (fn [v] (nth v (.nextInt rnd (count v))))
        cfg (mem-cfg) _ (d/create-database cfg)
        conns (atom {:db (d/connect cfg)}) commits (atom [])]
    (tx! (:db @conns) "m" 40)
    (dotimes [i n-ops]
      (let [names (vec (keys @conns))]
        (case (.nextInt rnd 5)
          (0 1 2) (let [b (pick names)] (d/transact (@conns b) (vec (for [j (range (inc (.nextInt rnd 12)))] {:k/n (long (.nextInt rnd 1000)) :k/who (str b i j)})))
                      (swap! commits conj (d/commit-id @(@conns b))))
          3 (let [from (pick names) nb (keyword (str "b" i))]
              (d/branch! (@conns from) from nb) (swap! conns assoc nb (d/connect (assoc cfg :branch nb))))
          4 (let [into-b (pick names) others (vec (remove #{into-b} names)) from (when (seq others) (pick others))]
              (when from
                @(d/merge-db! (@conns into-b) {:parents #{(d/commit-id @(@conns from))} :tx-data [{:k/who (str "merge" i) :k/n (long i)}]})
                (swap! commits conj (d/commit-id @(@conns into-b))))))))
    {:conns @conns :commits (vec (distinct @commits)) :cfg cfg}))
(defn property-run [seed n-ops]
  (let [{:keys [conns commits]} (random-history! seed n-ops)
        any (first (vals conns)) ks (:store @any) base-node (memoize (fn [c] (node-of ks c)))
        nodeG (with-generation base-node)
        heads (mapv #(d/commit-id @%) (vals conns))
        pairs (for [a heads b heads :when (neg? (compare a b))] [a b])
        gen-bad (remove (fn [[a b]] (= (merge-base nodeG a b) (brute-lca nodeG a b))) pairs)
        time-bad (remove (fn [[a b]] (= (merge-base base-node a b) (brute-lca nodeG a b))) pairs)
        multi (count (filter (fn [[a b]] (> (count (brute-lca nodeG a b)) 1)) pairs))
        rnd (java.util.Random. (inc seed))
        dbs (mapv (fn [_] (v/commit-as-db any (nth commits (.nextInt rnd (count commits))))) (range 12))
        attrs #{:k/n :k/who}
        diff-bad (remove (fn [[da db]] (let [f (pss-diff (:aevt da) (:aevt db) #{:k/n}) p (plain-diff da db #{:k/n})
                                             f2 (pss-diff (:aevt da) (:aevt db) #{:k/who}) p2 (plain-diff da db #{:k/who})]
                                         (and (= (set (:only-a f)) (:only-a p)) (= (set (:only-b f)) (:only-b p))
                                              (= (set (:only-a f2)) (:only-a p2)) (= (set (:only-b f2)) (:only-b p2)))))
                         (for [da dbs db dbs] [da db]))]
    {:seed seed :branches (count conns) :commits (count (all-ancestors nodeG (first heads))) :head-pairs (count pairs)
     :generation-walk-wrong (count gen-bad) :time-walk-wrong (count time-bad) :pairs-with-several-bases multi
     :diff-pairs (* 12 12) :diff-wrong (count diff-bad)
     :slot-datoms-seen (reduce + (for [da dbs db dbs] (or (:extras (pss-diff (:aevt da) (:aevt db) #{:k/n})) 0)))}))

;; B, claim 3, slots: a deeper tree (3000 rows, then many 1-2 row transactions on two branches) so that buffered
;; edits (Slots) exist; the diff must still equal the plain difference for every pair of commits.
(defn slot-run [seed]
  (let [rnd (java.util.Random. seed) cfg (mem-cfg) _ (d/create-database cfg) m (d/connect cfg)]
    (tx! m "base" 3000) (d/branch! m :db :chain)
    (let [c (d/connect (assoc cfg :branch :chain)) cids (atom [])]
      (dotimes [i 40]
        (let [conn (if (even? (.nextInt rnd 2)) m c)]
          (d/transact conn (vec (concat [{:k/n (long (.nextInt rnd 100000)) :k/who (str "n" i)}]
                                        (when (zero? (.nextInt rnd 3)) (let [x (first (d/datoms @conn {:index :aevt :components [:k/who]}))] [[:db/retractEntity (:e x)]])))))
          (swap! cids conj (d/commit-id @conn))))
      (let [dbs (mapv (fn [_] (v/commit-as-db m (nth @cids (.nextInt rnd (count @cids))))) (range 14))
            rows (for [da dbs db dbs, at [:k/n :k/who]]
                   (let [f (pss-diff (:aevt da) (:aevt db) #{at}) p (plain-diff da db #{at})]
                     {:ok (and (= (set (:only-a f)) (:only-a p)) (= (set (:only-b f)) (:only-b p))) :extras (:extras f) :nodes (:nodes f)}))]
        {:pairs (count rows) :wrong (count (remove :ok rows)) :slot-datoms-seen (reduce + (keep :extras rows)) :pairs-with-slots (count (filter #(pos? (or (:extras %) 0)) rows))}))))

;; ---------------------------------------------------------------------------------------------------------------
;; B, claim 1 driver: cold walk on the clone (fresh JVM), warm repeat. main's branch is :cluster-default.
(def head-m (d/commit-id @main)) (def head-c (d/commit-id @chain))
(def node-cache (atom {}))
(defn node [cid] (or (get @node-cache cid) (let [n (node-of kstore cid)] (swap! node-cache assoc cid n) n)))
(def cold (measure* #(paint-down node head-c head-m)))
(def warm (measure* #(paint-down node head-c head-m)))
{:cold (dissoc (update cold :result select-keys [:bases :pops]) :x) :warm (update warm :result select-keys [:bases :pops]) :nodes (count @node-cache)
 :head-m head-m :head-c head-c}

;; B, claim 1: where the base is and how it relates to history (chain-only / main-only commits, the S5 base ref).
(def base (first (:bases r2)))
(defn walk-down [from stop] ;; first-parent-agnostic: all commits reachable from `from` not reachable through `stop` (stop and ancestors excluded) 
  (loop [seen #{} todo [from] acc []]
    (if (empty? todo) acc
      (let [c (peek todo) todo (pop todo)]
        (if (or (seen c) (= c stop) (ancestor? node c stop)) (recur seen todo acc)
          (recur (conj seen c) (into todo (:parents (node c))) (conj acc c)))))))
(def chain-only (walk-down head-c base))
(def main-only (walk-down head-m base))
{:chain-only (count chain-only) :main-only (count main-only)
 :chain-merge-parents (count (filter #(> (count (:parents (node %))) 1) chain-only))
 :main-merge-parents (count (filter #(> (count (:parents (node %))) 1) main-only))
 :chain-branches (frequencies (map #(:branch (node %)) chain-only))
 :main-branches (frequencies (map #(:branch (node %)) main-only))}
(def first-chain (first (filter #(contains? (:parents (node %)) base) chain-only)))
(def base-db (v/commit-as-db main base))
(defn tx-inst [db tx] (-> (d/datoms db {:index :eavt :components [tx :db/txInstant]}) first :v))
{:base base :base-tx (:max-tx (node base)) :base-txInstant (str (tx-inst base-db (:max-tx (node base)))) :base-created (str (:at (node base)))
 :first-chain first-chain :first-chain-created (str (:at (node first-chain))) :first-chain-branch (:branch (node first-chain))
 :first-chain-parents (:parents (node first-chain))
 :chain-head-created (str (:at (node head-c)))}
(def base-ref #uuid "6ad046d3-04f9-50c7-98f3-f5238116fa1f")
(def bn (node base-ref))
{:base-ref-branch (:branch bn) :base-ref-parents (:parents bn) :base-ref-max-tx (:max-tx bn)
 :parents-known-on-main (into {} (map (fn [p] [p (boolean (ancestor? node p head-m))])) (:parents bn))
 :base-ref-ancestor-of-main (ancestor? node base-ref head-m)
 :base-ref-ancestor-of-chain (ancestor? node base-ref head-c)
 :merge-base-of-base-ref-and-main (let [r (paint-down node base-ref head-m)] (select-keys r [:bases :pops]))}

;; B, claim 1, sizing phase 1's small parents record: whole-record read vs get-in vs konserve get-meta vs a sidecar key.
(def heads (->> (k/get kstore :branches nil {:sync? true}) (map #(get-in (k/get kstore % nil {:sync? true}) [:meta :datahike/commit-id])) (remove nil?) distinct (remove @node-cache) vec))
(def sa (take 60 heads)) (def sb (take 60 (drop 60 heads))) (def sc (take 60 (drop 120 heads)))
{:heads (count heads)
 :full-record (dissoc (measure* #(count (map (fn [c] (get-in (k/get kstore c nil {:sync? true}) [:meta :datahike/parents])) sa))) :result)
 :get-in-meta (dissoc (measure* #(count (map (fn [c] (k/get-in kstore [c :meta :datahike/parents] nil {:sync? true})) sb))) :result)
 :get-meta (dissoc (measure* #(count (map (fn [c] (k/get-meta kstore c nil {:sync? true})) sc))) :result)
 :n 60}
(def cids (vec (keys @node-cache)))
(def w (measure* #(doseq [c cids] (k/assoc kstore [:omb/commit-graph c] {:parents (:parents (node c)) :at (:at (node c))} {:sync? true}))))
(def r1 (measure* #(count (map (fn [c] (k/get kstore [:omb/commit-graph c] nil {:sync? true})) cids))))
(def r2 (measure* #(count (map (fn [c] (k/get kstore [:omb/commit-graph c] nil {:sync? true})) cids))))
(def sidecar (into {} (map (fn [c] [c (k/get kstore [:omb/commit-graph c] nil {:sync? true})])) cids))
(def walk-sidecar (measure* #(paint-down (fn [c] (get sidecar c)) head-c head-m)))
{:n (count cids) :write (dissoc w :result) :read-1 (dissoc r1 :result) :read-2 (dissoc r2 :result)
 :walk-over-map (update walk-sidecar :result select-keys [:bases :pops])}

;; ---------------------------------------------------------------------------------------------------------------
;; A (default's JVM, jvm mode, read-only): claim 2. First define `pss-diff` and `pss-diff-by-attr` above in a scratch
;; ns (`omb.probe`; remove with (remove-ns 'omb.probe) when done). Then, per phase thread-allocation MB:
(comment
  (let [store (:seon.store/store (#'seon.cluster.boot/selected-instance "default"))
        kd (:seon.store/connection-object store)
        idattrs #{:seon.fn/sym :seon.test/sym :seon.ns/name :seon.schema/key}
        bean (java.lang.management.ManagementFactory/getThreadMXBean) tid (.getId (Thread/currentThread))
        mb (fn [] (/ (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid) 1048576.0))
        phase (fn [f] (let [t (System/nanoTime) m (mb) r (f)] [r {:ms (/ (Math/round (/ (- (System/nanoTime) t) 1e4)) 100.0) :mb (/ (Math/round (* 10 (- (mb) m))) 10.0)}]))
        ;; the identity of an entity is read from the entity (a digest edited in place leaves its :seon.fn/sym datom
        ;; out of the diff; reading identities from the diff's sym datoms alone answered 20, not 24)
        ids-of (fn [db es] (into #{} (for [e es, d (datahike.api/datoms db {:index :eavt :components [e]}) :when (idattrs (:a d))] [(:a d) (:v d)])))]
    (seon.cluster.registry/on-branch store :chain-f1f6dc8e6652
      (fn [bc]
        ;; the stand-in base: the S5 base ref's commit. For the graph's own base (today the fork point) use
        ;; #uuid "6acf6fed-6b55-5297-9f31-af2c339648cb": 701 changed identities, 677 of them carried from main.
        (let [base-db (datahike.api/commit-as-db kd (seon.cluster.registry/base-commit-id @bc :chain-f1f6dc8e6652)) head @bc]
          (try
            (let [[diff p-diff] (phase #(omb.probe/pss-diff (:aevt base-db) (:aevt head) #{:seon.program/definition-digest}))
                  [ids p-ids] (phase #(into (ids-of base-db (into #{} (map first) (:only-a diff))) (ids-of head (into #{} (map first) (:only-b diff)))))
                  bounds (assoc seon.cluster.source/merge-bound :seon.program/identities ids)
                  [dms p-dm] (phase #(mapv (fn [db] (seon.program/digest-map db bounds)) [base-db head]))
                  changed (into #{} (filter #(not= (get (first dms) %) (get (second dms) %))) ids)]
              {:changed-count (count changed) :candidates (count ids) :changed (sort changed)
               :phases {:diff p-diff :identities p-ids :digest-maps p-dm}})
            (finally (datahike.api/release-materialized-db base-db)))))))

  ;; claim 3 in A: the diff against the plain datom set difference of the five program attributes
  (let [store (:seon.store/store (#'seon.cluster.boot/selected-instance "default"))
        kd (:seon.store/connection-object store)
        attrs [:seon.program/definition-digest :seon.fn/sym :seon.test/sym :seon.ns/name :seon.schema/key]
        ks (fn [d] (into #{} (mapcat (fn [at] (map (fn [x] [(:e x) (:a x) (:v x) (:tx x)]) (datahike.api/datoms d {:index :aevt :components [at]})))) attrs))
        cmp (fn [da db] (let [r (omb.probe/pss-diff-by-attr (:aevt da) (:aevt db) attrs) a (ks da) b (ks db)]
                          {:equal? (and (= (set (:only-a r)) (clojure.set/difference a b)) (= (set (:only-b r)) (clojure.set/difference b a)))
                           :only-a (count (:only-a r)) :only-b (count (:only-b r))}))]
    (seon.cluster.registry/on-branch store :chain-f1f6dc8e6652
      (fn [bc] (let [base-db (datahike.api/commit-as-db kd (seon.cluster.registry/base-commit-id @bc :chain-f1f6dc8e6652))
                     main @(seon.cluster.boot/connection "default")]
                 (try {:base-vs-chain (cmp base-db @bc) :main-head-vs-chain (cmp main @bc)}
                      (finally (datahike.api/release-materialized-db base-db))))))))
