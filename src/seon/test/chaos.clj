(ns seon.test.chaos
 "Deterministic `seed = 42` lifecycle chaos fuzzer with Zeller `ddmin` shrinking."
 (:require [clojure.core.async :as a] [clojure.core.async.flow :as flow]
  [clojure.java.io :as io] [clojure.string :as str] [datahike.api :as d]
  [seon.agent :as ag] [seon.code :as code] [seon.code.edit :as ce]
  [seon.code.io :as cio] [seon.code.overlay :as ov] [seon.fork.calls :as fc]
  [seon.fork.worker-support :as ws] [seon.reconciler :as rec]
  [seon.schema :as sc] [seon.store :as st] [seon.test :as ts]
  [seon.transactor :as tx]))

(def event-alphabet
 [:spawn-worker :user-chat :edit-fn :set-meta :fork-review :main-refactor-delete-fn
 :add-require-or-prune :eval-interrupted :main-register-schema :external-branch-move
 :review-compact-merge-back :main-merge :model-tcp-drop :model-429 :model-400-ctx
 :model-auth-err :gc-store :cold-reboot])

(def ^:private faults
 {:model-tcp-drop {:error :model/network :retryable? true :thought "TCP"}
  :model-429 {:error :model/rate-limit :retryable? true :retry-after-ms 10 :thought "429"}
  :model-400-ctx {:error :model/context-length :retryable? false :thought "400"}
  :model-auth-err {:error :model/auth :retryable? false :thought "[model/auth]"}})

(def ^:private flow-edges
 {:transactor [[:db :reconciler :db] [:db :test :db]]
 :reconciler [[:run :agents :run] [:batch :test :batch] [:tx :transactor :tx]]
 :agents [[:exits :reconciler :exits] [:tx :transactor :tx] [:request :model :request]]
 :test [[:tx :transactor :tx]]})

(defn synthetic-sources
 "Isolated `seon.chaos.s<seed>.*` namespaces."
 {:malli/schema [:=> [:cat :int] :map] :cost/ticks 500000}
 [seed]
 (let [s (Math/abs (long seed)) pfx (str "seon.chaos.s" s) p (str "src/seon/chaos/s" s)
  f #(symbol (str pfx "." %)) h (f "helper") u (f "ui") e (f "extra") t (f "ui-test")
  m #(str "{:malli/schema [:=> [:cat :int] " % "] :cost/ticks 10000}") ms (m ":string")
  uh (str "(ns " u " (:require [" h " :as h]))\n")]
  {:pfx pfx :h h :u u :e e :t t :up (str p "/ui.clj") :ep (str p "/extra.clj")
   :files [[(str p "/helper.clj") (str "(ns " h ")\n(defn compute " (m ":int") " [x] (+ x 1))\n")]
    [(str p "/ui.clj") (str uh "(defn- render-nav-bar [x] (str \"n\" x))\n(defn page " ms " [x] (str (render-nav-bar x) (h/compute x)))\n")]
    [(str "test/seon/chaos/s" s "/ui_test.clj") (str "(ns " t " (:require [clojure.test :refer [deftest is]] [" u " :as ui]))\n(deftest page-test (is (string? (ui/page 1))))\n")]]
   :es (str "(ns " e ")\n(defn badge " ms " [x] (str \"b:\" x))\n") :ur (str uh "(defn page " ms " [x] (str \"i\" x (h/compute x)))\n")}))

(defn generate-plan
 "Deterministic plan covering all 18 events when `n-steps >= 18`."
 {:malli/schema [:=> [:cat [:map [:seed {:optional true} :int] [:n-steps {:optional true} :int]]] [:vector :keyword]] :cost/ticks 1000000}
 [{:keys [seed n-steps] :or {seed 42 n-steps 24}}]
 (let [rng (java.util.Random. (long seed)) n (max 1 (min 64 (long n-steps))) m (count event-alphabet)
  full (into event-alphabet (repeatedly (max 0 (- n m)) #(nth event-alphabet (.nextInt rng m))))]
  (if (< n m) (subvec event-alphabet 0 n)
   (into (subvec full 0 11)
    (reduce (fn [v i] (let [j (+ i (.nextInt rng (- (count v) i)))] (assoc v i (v j) j (v i))))
     (subvec full 11) (range (dec (- (count full) 11))))))))

(defn format-reproducer
 "Copy-pasteable `replay!` call."
 {:malli/schema [:=> [:cat :int [:vector :keyword]] :string] :cost/ticks 200000}
 [seed steps]
 (str "(seon.test.chaos/replay! {:seed " seed " :steps " (pr-str (vec steps)) "})"))

(defn shrink-failing-sequence
 "Zeller `ddmin` shrinker."
 {:malli/schema [:=> [:cat [:vector :keyword] [:=> [:cat [:vector :keyword]] :boolean]] [:vector :keyword]] :cost/ticks 5000000}
 [steps fp]
 (let [v0 (vec steps)]
  (if (or (<= (count v0) 1) (not (fp v0))) v0
   (loop [cur v0 n 2]
    (let [l (count cur) g (min n l) sz (quot (+ l g -1) g)
     cs (for [i (range 0 l sz)] [i (min l (+ i sz))]) ok #(and (seq %) (< (count %) l) (fp %))
     p (some (fn [[s e]] (let [c (subvec cur s e)] (when (ok c) c))) cs)
     cm (when-not p (some (fn [[s e]] (let [c (into (subvec cur 0 s) (subvec cur e l))] (when (ok c) c))) cs))]
     (cond (<= l 1) cur p (recur p 2) cm (recur cm (max 2 (dec g))) (< g l) (recur cur (min l (* 2 g))) :else cur))))))

(defn route-outs
 "Pure Flow router mirroring `seon.main`."
 {:malli/schema [:=> [:cat :keyword [:maybe :map]] [:map [:msgs [:vector :any]] [:errors [:vector :any]]]] :cost/ticks 500000}
 [src out]
 {:errors (vec (get out ::flow/error))
 :msgs (vec (for [[ok dst dp] (flow-edges src) x (get out ok)] [dst dp x]))})

(defn- br-of [a] (keyword "branch" (name a)))

(defn- has? [db a v] (some? (first (d/datoms db :avet a v))))

(defn- p1 [db a e] (get (d/pull db [a] e) a))

(defn- q [w [d p m]] (update-in w [:q [d p]] (fnil conj []) m))

(defn- qtx [w m] (q w [:transactor :tx m]))

(defn- safe! [f] (try (f) (catch Throwable _)))

(defn- bdo! [cfg br f] (let [c (st/connect cfg br)] (try (f c) (finally (d/release c)))))

(defn- tx-br! [cfg br tx] (bdo! cfg br #(d/transact % {:tx-data tx})))

(defn- ectx! [c br a f] (binding [fc/*agent-ctx* {:conn c :branch br :agent-id a}] (f)))

(defn- restore-img! [c {:keys [h u t]} dels rel?]
 (doseq [k dels :when (re-find #"^[^/]+/[^#]+$" (str k))]
  (let [s (symbol k) ns-s (symbol (namespace s))] (when (find-ns ns-s) (ns-unmap ns-s (symbol (name s))))))
 (when rel? (let [db @c fs (vec (mapcat #(code/ns-forms db %) [h u t]))]
  (when (seq fs) (code/apply-overlay! db {:upserts (code/sort-forms-topologically fs) :retracted-keys #{}} true)))))

(defn- boot-procs! [w dir]
 (let [cfg (assoc (st/store-cfg dir {:cache-bytes 4194304}) :branch :main)
  _ (when-not (:conn w)
     (let [d0 (dissoc cfg :branch) c0 (do (d/create-database d0) (d/connect d0))]
      (try (d/transact c0 {:tx-data (:declarations @sc/base-registry)}) (d/branch! c0 :db :main) (finally (d/release c0)))))
  c (st/connect cfg :main) fo (a/chan 256) b {:conn c :cfg cfg :fork-out fo}]
  (when-not (:conn w)
   (d/transact c {:tx-data (into [(assoc st/default-config-values :seon.config/key :seon.config/root)]
    (mapcat (fn [[p s]] (code/file-import-tx @c p s false)) (get-in w [:ns :files])))}))
  (assoc w :conn c :cfg cfg :fo fo :ch {} :ph {} :q {[:transactor :boot] [{:op :boot-init}]}
   :ps {:transactor (tx/transactor-proc (assoc b :commit-git? false))
    :reconciler (rec/reconciler-proc {:conn c})
    :agents (ag/agents-proc (assoc b :fork-agent-in (a/chan 64) :max-os-procs 4))
    :test (ts/test-proc (assoc b :fork-test-in (a/chan 64) :max-workers 2))})))

(defn- handle-test! [w {bid :batch-id o :overlay ti :test-items gi :gen-items :keys [cfg branch]}]
 (let [p (:pid w) bc (when (and cfg branch) (st/connect cfg branch))
  tf #(vector :test :fork-ev (assoc % :type :test :batch-id bid :pid p))
  ev #(reduce q (assoc w :pid (inc p)) %)]
  (try
   (let [db (when bc @bc) _ (when o (ws/apply-form-overlay! db o))
    [r gf] (ws/call-with-candidate-overrides db (fn [_]
     (let [tr (mapv #(ws/run-single-test-var db (:var %) (:digest %)) ti)
      gr (vec (keep (fn [{v :var dg :digest}]
       (when-let [x (sc/stress-var db v {:samples 5 :seed 42})]
        #:seon.test{:digest dg :var v :kind :generative :pass? (true? (:ok? x))
         :ms 0.0 :ticks 0 :bytes 0 :report (str (or (:reason x) "OK"))})) gi))]
      [(into tr gr) (vec (remove :seon.test/pass? gr))])))]
    (ev [(tf {:ev :child-frame :frame {:ev :test-batch-done :batch-id bid :worker-pid p :results r :generative-failures gf}})
     (tf {:ev :child-exited :exit-code 0})]))
   (catch Throwable ex (ev [(tf {:ev :child-frame :frame {:ev :child-exception :batch-id bid :error (str ex)}})]))
   (finally (when bc (d/release bc)) (restore-img! (:conn w) (:ns w) (:del w) true)))))

(defn- handle-fork! [w {:keys [op pid msg agent-id branch cfg retracted-keys] :as cmd}]
 (let [af (fn [a p m] [:agents :fork-ev (assoc m :type :agent :agent-id a :pid p)])
  fr #(af %1 %2 {:ev :child-frame :frame %3})
  mreq (fn [w0 p {:keys [a br c]}]
   (let [ur (ws/unread-messages-for @(:conn w0) @c a) tn (inc (:max-turn (st/agent-eval-history-stats @c a)))]
    (q (assoc-in w0 [:ch p :pend] [tn ur])
     (fr a p {:ev :model-request :req-id (str (name a) "-" tn "-" p) :agent-id a :branch br :turn tn :unread ur}))))]
  (case op
   :advance-image (do (restore-img! (:conn w) (:ns w) retracted-keys false) w)
   :fork-agent (let [p (:pid w) bc (st/connect cfg branch)
    _ (when-not (has? @bc :seon.agent/id agent-id) (d/transact bc {:tx-data [#:seon.agent{:id agent-id :branch branch}]}))
    ch {:a agent-id :br branch :c bc :k (d/commit-id @bc) :m (d/commit-id @(:conn w))}]
    (mreq (q (assoc-in (assoc w :pid (inc p)) [:ch p] ch) (af agent-id p {:ev :forked})) p ch))
   :to-child (if-let [{:keys [c a br pend k m] :as ch} (get-in w [:ch pid])]
    (case (:op msg)
     :stop (do (safe! #(d/release c)) (q (update w :ch dissoc pid) (af a pid {:ev :child-exited :exit-code 0})))
     :run-turn (let [r (ws/sync-conn-to-latest-branch-head! c br k m) c2 (assoc ch :k (:branch-cid r) :m (:main-cid r))]
      (mreq (assoc-in w [:ch pid] c2) pid c2))
     :model-response (let [[tn ur] pend r (:response msg)
      es (when-let [ek (or (:seon.error/kind r) (:error r))] (or (not-empty (:thought r)) (name ek)))
      sids (when-not es (not-empty (set (map :seon.agent.message/id ur))))]
      (d/transact c {:tx-data
       [(cond-> #:seon.agent.eval{:id (random-uuid) :agent a :turn tn :code "" :thought (if es "" (:thought r ""))}
         es (assoc :seon.agent.eval/error es) sids (assoc :seon.agent.eval/seen-messages sids)
         (:prefix-hash msg) (assoc :seon.agent.eval/prefix-hash (:prefix-hash msg)))
        (cond-> #:seon.agent{:id a :waiting? (or (nil? es) (false? (:retryable? r)))} sids (assoc :seon.agent/ready? false))]})
      (q (assoc-in w [:ch pid :k] (d/commit-id @c))
       (fr a pid {:ev :turn-committed :agent-id a :branch br :turn tn :branch-tx (:max-tx @c) :ok? (nil? es)}))) w) w)
   :fork-test (handle-test! w cmd) w)))

(defn- drain! [w mt]
 (let [pf {:transactor tx/transactor-proc :reconciler rec/reconciler-proc :agents ag/agents-proc :test ts/test-proc}]
  (loop [cur (assoc w :q? true) t 0]
   (if (> t mt) (assoc cur :q? false)
    (if-let [fc (a/poll! (:fo cur))] (recur (handle-fork! cur fc) (inc t))
     (if (seq (:q cur))
      (let [ks (vec (sort (keys (:q cur)))) k (nth ks (.nextInt ^java.util.Random (:rng cur) (count ks)))
       [d p] k ms (get-in cur [:q k]) m (first ms)
       c1 (if (= 1 (count ms)) (update cur :q dissoc k) (assoc-in cur [:q k] (subvec ms 1)))]
       (recur (if (= d :model)
        (let [fk (first (:fq c1))]
         (q (if fk (update c1 :fq subvec 1) c1)
          [:agents :response {:agent-id (:agent-id m) :req-id (:req-id m) :response (get faults fk {:thought (str "ok" (:turn m))})}]))
        (let [[s out] ((pf d) (get-in c1 [:ps d]) p m) {:keys [msgs errors]} (route-outs d out)
         c2 (reduce q (-> c1 (assoc-in [:ps d] s) (update :ferrs into errors)) msgs)]
         (if-let [ph (and (= d :agents) (= p :fork-ev) (= :model-request (get-in m [:frame :ev]))
          (get-in s [:model-reqs (get-in m [:frame :req-id]) :prefix-hash]))]
          (update-in c2 [:ph (:agent-id m)] (fnil conj []) ph) c2))) (inc t))) cur))))))

(defn- kw [n & xs] (keyword n (apply str xs)))

(defn- wch [w wid] (some #(when (= wid (:a %)) %) (vals (:ch w))))

(defn- check-invs! [w ev]
 (let [{:keys [conn cfg ns]} w stp (:step w 0) vs (transient [])
  c! #(when-not %1 (conj! vs {:inv %2 :step stp :event ev :detail %3}))]
  (c! (:q? w) :inv-1 :q)
  (doseq [[k c] (assoc (update-vals (:ch w) :c) :main conn)]
   (c! (and (some? (:writer @(:wrapped-atom c))) (>= (:max-tx @c) (get-in w [:txs k] 0))) :inv-2 k))
  (when (#{:eval-interrupted :external-branch-move :gc-store :cold-reboot} ev)
   (try (d/transact conn {:tx-data [#:seon.config{:key :seon.config/root :eval-ticks 100000000}]})
    (catch Throwable ex (c! false :inv-2 (.getMessage ex)))))
  (doseq [[a phs] (:ph w)] (c! (<= (count (distinct phs)) 1) :inv-3 [a phs]))
  (when-let [wid (and (seq (:del w)) (#{:main-refactor-delete-fn :add-require-or-prune :edit-fn :set-meta :external-branch-move :review-compact-merge-back} ev) (last (:ws w)))]
   (let [wb (br-of wid) chk (fn [bc]
    (c! (not (str/includes? (str (cio/merged-ns-src bc wb (:u ns))) "render-nav-bar")) :inv-4 wb)
    (let [cid (d/commit-id @bc) b (d/merge-base bc :main wb)]
     (when (and b (not= b cid)) (c! (<= (count (d/changes bc b cid)) 500) :inv-4 wb))
     (when (= ev :add-require-or-prune)
      (try (c! (empty? (:errors (ov/load-delta! bc b cid false))) :inv-5 wb)
       (finally (restore-img! conn ns (:del w) true))))))]
    (if-let [c (:c (wch w wid))] (chk c) (bdo! cfg wb chk))))
  (let [t (get-in w [:ps :test])]
   (c! (and (empty? (:active-subs t)) (empty? (:parents t)) (empty? (:ferrs w)) (zero? (st/active-lmdb-write-txns))) :inv-6 (:ferrs w)))
  (when (#{:gc-store :cold-reboot} ev)
   (let [brs (d/branches conn) mdb @conn]
    (c! (and (every? #(contains? brs (br-of %)) (:ws w)) (has? mdb :seon.code.form/key (str (:u ns) "/page"))
     (every? #(has? mdb :seon.schema/key %) (:schs w))) :inv-8 ev)))
  (when-let [[id target tb z et] (when (= ev :review-compact-merge-back) (:lrev w))]
   (let [mdb @conn tdb (st/head conn tb) ek (str (:e ns) "/badge")]
    (c! (:ok? et) :inv-5 et)
    (c! (and (:ok? z) (zero? (long (or (:ran z) 0))) (not (p1 mdb :seon.agent/paused? [:seon.agent/id target]))
     (str/includes? (str (p1 tdb :seon.code.form/src [:seon.code.form/key ek])) "\"rev:\"")
     (not (has? mdb :seon.code.form/key ek)) (not (has? tdb :seon.agent/id id))) :inv-9 id)))
  (persistent! vs)))

(defn- exec-worker! [w ev]
 (if-let [wid (last (:ws w))]
  (let [{:keys [conn cfg ns]} w br (br-of wid) hf (symbol (name (:h ns)) "compute") stp (:step w 0)
   ch (wch w wid) c (or (:c ch) (st/connect cfg br)) ef {:eval? false}]
   (try (case ev
    :edit-fn (let [k (inc (:en w 0)) m (re-find #"\(\+ x \d+\)" (str (p1 @c :seon.code.form/src [:seon.code.form/key (str hf)])))]
     (when m (ectx! c br wid #(ce/edit-fn! hf m (str "(+ x " k ")") ef))) (assoc w :en k))
    :set-meta (do (ectx! c br wid #(ce/set-meta! hf {:cost/ticks (+ 10000 stp)} ef)) w)
    :add-require-or-prune (do (ectx! c br wid #(do (ce/add-require! (:u ns) (quote [clojure.set :as cset]) ef)
     (ce/prune-requires! (:u ns)))) w)
    :eval-interrupted (do (tx-br! cfg br [#:seon.agent{:id wid :summary (str "e" stp)}])
     (code/eval-tool-calls c {:agent-id wid :turn (+ 50 stp) :start-ns (:h ns)}
      [{:id "i1" :code "(throw (ex-info \"int\" {:jolt/interrupted :ticks}))"}]) (update w :ph dissoc wid))
    :external-branch-move (let [pc (d/commit-id @c) pm (d/commit-id @conn)]
     (tx-br! cfg br [#:seon.agent{:id wid :summary (str "m" stp)}])
     (ws/sync-conn-to-latest-branch-head! c br pc pm) (update w :ph dissoc wid)) w)
    (finally (when-not (:c ch) (d/release c))))) w))

(defn- close-st! [w]
 (doseq [[_ ch] (:ch w) :when (:c ch)] (safe! #(d/release (:c ch))))
 (when (:conn w) (safe! #(st/close! {:cfg (:cfg w) :conn (:conn w)}))))

(defn- exec-event! [w ev]
 (let [{:keys [conn cfg ns]} w wid (last (:ws w)) stp (:step w 0)
  chat #(qtx %1 {:op :send-message :from :user/default :to wid :type :chat :body %2})
  qtxd #(qtx %1 {:op :transact :tx-data %2}) btx #(:max-tx (st/head conn %))
  rb (fn [w0 b a] (let [ch (a/chan 1)] [(drain! (q w0 [:test :batch {:branch b :agent-id a :branch-tx (btx b) :reply-ch ch}]) 60) (a/poll! ch)]))
  w1 (if (faults ev) (if wid (chat (update w :fq conj ev) (name ev)) w)
   (case ev
    :spawn-worker (let [a (kw "agent" "cw" (inc (count (:ws w))))]
     (qtx (update w :ws conj a) {:op :spawn-agent :id a :branch (br-of a) :goal "g"}))
    :user-chat (if wid (chat w (str "p" stp)) w)
    :main-merge (let [mb (kw "branch" "cm-" stp)]
     (st/branch! conn :main mb) (tx-br! cfg mb [#:seon.task{:id (kw "task" "c" stp) :title "c" :done? true}]) (qtx w {:op :merge :branch mb}))
    :main-refactor-delete-fn (qtxd (update w :del conj (str (:u ns) "/render-nav-bar")) (code/file-import-tx @conn (:up ns) (:ur ns) false))
    :main-register-schema (let [k (kw "seon.chaos" "t" (inc (:sn w 0)))]
     (qtx (-> w (update :sn (fnil inc 0)) (update :schs conj k))
      {:op :register-schema :key k :form :string :doc "t" :db {:valueType :db.type/string :cardinality :db.cardinality/one}}))
    :fork-review (if wid (let [wb (br-of wid) rn (inc (:rn w 0)) rid (kw "agent" "cr" rn) rbr (br-of rid) ref [:seon.agent/id wid]]
     (bdo! cfg wb #(when-let [tx (seq (code/file-import-tx @% (:ep ns) (:es ns) false))] (d/transact % {:tx-data tx})))
     (st/branch! conn wb rbr) (tx-br! cfg rbr [#:seon.agent{:id rid :branch rbr :summary (str "r" rn)}])
     (qtxd (assoc w :rn rn :rev {:id rid :br rbr :target wid :tb wb})
      [#:seon.agent{:id wid :paused? true} #:seon.agent{:id rid :branch rbr :forked-from ref :target ref :goal "r"}])) w)
    :review-compact-merge-back (if-let [{:keys [id br target tb]} (:rev w)]
     (let [[w0 z] (rb w br id) _ (bdo! cfg br #(ectx! % br id (fn [] (ce/edit-fn! (symbol (name (:e ns)) "badge") "\"b:\"" "\"rev:\"" {:eval? false}))))
      [w1 et] (rb w0 br id) w2 (drain! (qtx w1 {:op :merge :branch br :agent-id id :branch-tx (btx br)}) 60)]
      (-> w2 (assoc :lrev [id target tb z et] :rev nil) (update :ph dissoc target))) w)
    :gc-store (qtx w {:op :gc-store :keep-branches (cond-> (into #{:main} (map br-of (:ws w))) (:rev w) (conj (:br (:rev w))))})
    :cold-reboot (do (close-st! w) (boot-procs! w (:dir w)))
    (exec-worker! w ev)))
  w2 (drain! w1 120) w3 (if-let [fh (:fh w2)] (or (fh w2 ev) w2) w2)]
  (-> w3 (update :step (fnil inc 0)) (update :seen conj ev))))

(defn ^:seon.flow/edge cleanup-chaos!
 "Closes store/conns, deletes temp dir & synthetic namespaces."
 {:malli/schema [:=> [:cat :any] :nil]}
 [w]
 (close-st! w)
 (when-let [d (:dir w)] (safe! #(st/rm-rf! (io/file d))))
 (when-let [pfx (get-in w [:ns :pfx])]
  (doseq [n (all-ns) :when (str/starts-with? (name (ns-name n)) pfx)] (safe! #(remove-ns (ns-name n)))))
 nil)

(defn ^:seon.flow/edge replay!
 "Replays an exact event sequence on an isolated LMDB store."
 {:malli/schema [:=> [:cat :map] :map] :seon.eval/budget-override {:ticks 300000000 :bytes 671088640}}
 [{:keys [seed steps fault-hook] :or {seed 42}}]
 (let [t0 (System/nanoTime) b0 (code/alloc-bytes) sv (vec steps) dir (str "/tmp/seon-chaos-" seed "-" t0)
  cur (volatile! (drain! (boot-procs! {:seed seed :dir dir :ns (synthetic-sources seed) :rng (java.util.Random. (long seed))
   :pid 100 :ws [] :del #{} :schs #{} :ferrs [] :fq [] :seen #{} :txs {} :fh fault-hook} dir) 80))
  mk #(array-map :ok? %1 :seed seed :dir dir :steps sv :ran %3 :events-covered (:seen %2)
   :violations %4 :ms (/ (- (System/nanoTime) t0) 1e6) :bytes (max 0 (- (code/alloc-bytes) b0)))]
  (try (loop [rem sv ran 0]
   (let [w @cur]
    (if (empty? rem) (mk true w ran [])
     (let [ev (first rem) w1 (exec-event! w ev) _ (vreset! cur w1) vs (check-invs! w1 ev)
      txs (assoc (update-vals (:ch w1) #(:max-tx @(:c %))) :main (:max-tx @(:conn w1)))]
      (if (seq vs) (mk false w1 (inc ran) vs) (do (vreset! cur (assoc w1 :txs txs)) (recur (subvec rem 1) (inc ran))))))))
   (finally (cleanup-chaos! @cur)))))

(defn ^:seon.flow/edge run-chaos!
 "Runs the `seed = 42` chaos fuzzer with Zeller `ddmin` shrinking."
 {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map]] :seon.eval/budget-override {:ticks 300000000 :bytes 671088640}}
 ([] (run-chaos! {}))
 ([{:keys [seed n-steps shrink? fault-hook] :or {seed 42 n-steps 24 shrink? true}}]
  (let [p (generate-plan {:seed seed :n-steps n-steps}) r (replay! {:seed seed :steps p :fault-hook fault-hook})]
   (if (or (:ok? r) (not shrink?)) r
    (let [s (shrink-failing-sequence p #(not (:ok? (replay! {:seed seed :steps % :fault-hook fault-hook}))))]
     (assoc r :shrunk-steps s :reproducer (format-reproducer seed s)))))))
