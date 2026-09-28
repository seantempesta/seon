(ns one-path-proof
  "The one-path proof suite: every proof runs the implementation in
  tmp/one-path/src against default's live database, in an agent world on a
  disposable branch (its writes land there through the branch's own writer),
  or reads the scratch-JVM results of the SCI fork proofs.

  (load-file \"/Users/sean/src/seon/tmp/one-path/load.clj\")
  (load-file \"/Users/sean/src/seon/tmp/one-path/one_path_proof.clj\")
  (one-path-proof/prove!)   ; the map of named proofs -> proof-results.edn, summary -> proof-summary.md"
  (:require [cheshire.core]
            [clj-kondo.core]
            [clojure.walk]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]
            [sci.core :as sci]
            [seon.batch :as batch]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env]
            [seon.schema]
            [seon.sci.batch :as sci.batch]
            [seon.sci.eval :as eval]
            [seon.sci.forgive :as forgive]
            [seon.fn :as sfn]
            [seon.sci.observe :as observe]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as world]))

(def dir "/Users/sean/src/seon/tmp/one-path/")

;;; ---------------------------------------------------------------------------
;;; Worlds
;;; ---------------------------------------------------------------------------

(defn acquire!
  "An isolated root execution on a disposable branch of default's head."
  []
  (let [instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")
        handle (assoc (:seon.turn.loop/cluster instance) :seon.store/store (:seon.store/store instance))
        head (db/db (:seon.db/connection handle))]
    (agent/acquire-context! handle "root" {:seon.agent/isolate? true
                                            :seon.cluster.registry/from (db/commit-id head)})))

(defn release! [exec]
  ((:release @@#'eval/agent-entrance) exec))

(def ^:dynamic *exec* nil)

(declare gate-root! landing-files)

(defn- without-native-guard
  "The landing deletes native-mutation (S9: removals are observed writes); its
  guard reads this flag, so a proof world runs without it."
  [world]
  (update world :seon.sci.eval/ctx assoc :seon.sci.eval/native-operation? true))

(defn live-process
  "The live default root's process identity (a world's fault context)."
  []
  (:seon.db.process/id (:seon.turn.loop/cluster (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default"))))

(defn world
  "A fresh agent world (`seon.world/agent-world`) on the suite's
  disposable branch, reading that branch's head now. Writes land there
  through `seon.db/transact!`."
  ([] (world {}))
  ([opts]
   (without-native-guard
    (world/agent-world *exec* (merge {:seon.agent/id "root"
                                           :seon.turn/id (str "op3-" (subs (str (random-uuid)) 0 8))
                                           :seon.db.process/id (live-process)}
                                          opts)))))

(defn on-fresh-branch
  "Call `f` with *exec* bound to a new disposable execution (a branch no other
  proof has written), released after."
  [f]
  (let [exec (acquire!)]
    (try (binding [*exec* exec] (f)) (finally (release! exec)))))

(defn wdb [w] (:seon.db/db w))
(defn wctx [w] (:seon.sci.eval/ctx w))

(def turn-stages
  "What seon.turn supplies to the program stages (it owns these writers)."
  {:seon.turn.batch/row-tx @(requiring-resolve 'seon.turn/row-tx)
   :seon.turn.batch/record-call (requiring-resolve 'seon.turn/record-evaluated-call)})

(defn request
  "A request over `text` in world `w` (`:writer` replaces the world's writer)."
  [w text opts]
  (merge turn-stages
         {:seon.batch/text text
          :seon.batch/world (cond-> (assoc w :seon.turn/id (str "op3-" (subs (str (random-uuid)) 0 8)))
                              (:writer opts) (assoc :seon.db/writer (:writer opts)))
          :seon.batch/observe observe/observe}
         (dissoc opts :writer :mode)))

(defn run
  "Evaluate `text` as an MCP code batch, or a product turn with :mode :reply."
  ([w text] (run w text {}))
  ([w text opts]
   (let [req (request w text opts)]
     (batch/evaluate-batch (if (= :reply (:mode opts))
                             (turn.batch/turn-request req)
                             (turn.batch/code-request req))))))

(defn values [b] (mapv #(if (contains? % :seon.batch/value) (:seon.batch/value %) [:error (ex-message (:seon.batch/error %))])
                       (:seon.batch/entries b)))
(defn shown [b] (mapv :seon.batch/shown (:seon.batch/entries b)))
(defn facts-errors [b] (vec (keep #(some-> % :seon.batch/facts-error :seon.error/message) (:seon.batch/entries b))))
(defn diags [b] (mapv :seon.error/message (:seon.batch/diagnostics b)))
(defn after [b] (get-in b [:seon.batch/commit :seon.batch/after]))
(defn applied? [b] (true? (get-in b [:seon.batch/commit :seon.batch/applied])))
(defn stored? [database sym] (boolean (and database (:db/id (db/pull database [:db/id] [:seon.fn/sym sym])))))
(defn ns-stored? [database n] (boolean (and database (:db/id (db/pull database [:db/id] [:seon.ns/name n])))))
(defn private-reasons [b] (into {} (mapcat #(map (juxt :seon.batch/sym (fn [p] (or (:seon.batch/reason p) (:seon.batch/join p))))
                                                 (:seon.batch/private %))
                                           (:seon.batch/entries b))))
(defn commit-of [b] (select-keys (:seon.batch/commit b) [:seon.batch/applied :seon.batch/tx-count :seon.batch/storage-error]))
(defn- ms-since [t0] (Math/round (/ (- (System/nanoTime) t0) 1e6)))
(defn- brief [x] (let [s (pr-str x)] (if (< 160 (count s)) (str (subs s 0 157) "...") s)))

;;; ---------------------------------------------------------------------------
;;; Rows: {:id :class :claim :run}; run answers {:pass? ...evidence}
;;; ---------------------------------------------------------------------------

(def proofs
  "The named proofs, in definition order: id -> {:class :claim :run}."
  (atom []))

(defmacro defproof [id klass claim & body]
  `(swap! proofs (fn [ps#] (conj (vec (remove #(= ~id (:id %)) ps#))
                                 {:id ~id :class ~klass :claim ~claim :run (fn [] ~@body)}))))

;;; --- The SCI fork (scratch clone, its own suite) ---------------------------

(defn scratch [] (edn/read-string (slurp (str dir "scratch-results.edn"))))

(defproof :sci-suite :sci-fork
  "SCI's own clj suite on the patched clone: 398 tests, the same 5 failures as the pinned fork's 393, 0 new"
  (let [{:keys [patched base]} (:suite (scratch))]
    {:pass? (and (= (:failures base) (:failures patched)) (= 0 (:errors patched)) (> (:tests patched) (:tests base)))
     :patched (dissoc patched :failures) :base (dissoc base :failures) :failures (:failures patched)}))

(doseq [[id claim] [[:v2-1-inherited "V2-1: an inherited redefinition is observed once, on the Var the fork resolves; arming it leaves the base unchanged"]
                    [:v2-1-nested-callback "V2-1: a callback that defines does not repeat the outer write (no swap retry)"]
                    [:v2-1-throwing-watch "V2-1: a failing watch cannot hide a completed write"]
                    [:r1-every-write "R1: def, intern and alter-var-root of a retained Var, intern of a new Var, defonce twice, declare, nested eval, unmap, remove-ns, a fork's write"]
                    [:v2-2-single-def-macro "V2-2: a macro's single def is the macro call's occurrence (expanded-by), not authored"]
                    [:v2-2-generated-in-let "V2-2: defs an agent macro generates inside a let carry the call's span"]
                    [:extensions-marked "Extension writes are :extend, the protocol is one definition, the record helper names its member"]
                    [:expansion-observer "The expansion observer sees the single-def macro's expansion"]
                    [:future-conveys "future: a def inside a future is observed by the batch's observer (Clojure's binding conveyance)"]
                    [:two-threads-one-context "Two threads in one context: each thread's observer sees only its own thread's writes"]
                    [:q1-single-def-kondo "Q1: kondo, given SCI's recorded expansion of the single-def macro, defines `made` at the call"]]]
  (swap! proofs conj {:id id :class :sci-fork :claim claim
                    :run (fn [] (let [r (get-in (scratch) [:proofs id])] (assoc r :pass? (true? (:pass? r)))))}))

;;; --- Owner behaviors ------------------------------------------------------

(defproof :oq1-bad-form-logged :owner
  "OQ1: every form runs; a form whose facts are invalid logs its error as its result; the rest save in one transaction"
  (let [b (run (world) "(defn oq1a {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(defn oq1bad {:malli/schema [:=> [:cat :no.such/key] :int]} [x] 1)\n(defn oq1c {:malli/schema [:=> [:cat :int] :int]} [x] (oq1a x))\n(oq1c 5)")
        a (after b)]
    {:pass? (and (= 5 (last (values b))) (= 1 (count (facts-errors b))) (applied? b)
                 (stored? a 'user/oq1a) (not (stored? a 'user/oq1bad)) (stored? a 'user/oq1c) (empty? (diags b)))
     :facts-errors (facts-errors b) :commit (commit-of b)}))

(defproof :oq1-head-moved :owner
  "OQ1 writer failure: a concurrent write changed the checked basis; forms re-derive on the new head; only the form it now refuses loses its facts (logged); the rest commit"
  (let [_ (run (world) "(defn hm1 [x] x)")
        w (world)                                   ; its basis holds hm1
        _ (run (world) "(defn hmref [x] (hm1 x))")  ; a concurrent write moves the head
        b (run w "(defn hm3 [x] x)\n(ns-unmap *ns* 'hm1)\n(hm3 9)")
        a (after b)]
    {:pass? (and (applied? b) (= 9 (last (values b))) (stored? a 'user/hm3) (stored? a 'user/hm1) (stored? a 'user/hmref)
                 (= 1 (count (facts-errors b))) (some? (get-in b [:seon.batch/commit :seon.batch/first-refusal])))
     :facts-errors (facts-errors b) :first-refusal (get-in b [:seon.batch/commit :seon.batch/first-refusal :seon.error/message])}))

(defproof :oq1-storage-failure :owner
  "OQ1 writer failure: storage fails at commit; every value is kept and the batch answers the storage error"
  (let [b (run (world) "(defn sf1 [x] x)\n(sf1 7)" {:writer (fn [_ _] (throw (ex-info "storage unavailable" {})))})]
    {:pass? (and (= 7 (last (values b))) (= "storage unavailable" (get-in b [:seon.batch/commit :seon.batch/storage-error])))
     :commit (:seon.batch/commit b)}))

(defproof :q2-live-reply :owner
  "Q2 (product turn entrant): prose becomes comments, a prompt marker is stripped, an unbalanced defn is repaired, ::alias resolves live; the run is recorded"
  (let [text "Let me require it first.\n(require '[clojure.string :as str])\n::str/k\nuser=> (+ 1 2)\n(defn bad [x]\n  (inc x)\nThat should do it."
        b (run (world) text {:mode :reply})
        a (after b)]
    {:pass? (and (= [nil :clojure.string/k 3] (vec (take 3 (values b))))
                 (= "#'user/bad" (str (nth (values b) 3)))
                 (= [:prose :prompt-marker :delimiters :prose] (mapv :seon.batch/repair-kind (:seon.batch/repairs b)))
                 (applied? b) (stored? a 'user/bad))
     :repairs (mapv :seon.batch/repair-kind (:seon.batch/repairs b)) :text (:seon.batch/text b)}))

(defproof :q2-recorded-replies :owner
  "Q2: the 52 recorded replies read through the forgiving stage exactly as revision 2 recorded them; no read fails to advance"
  (let [inputs (edn/read-string (slurp (str dir "forgive-results.edn")))
        ctx (wctx (world))
        opts (sci.batch/parse-options ctx)
        t0 (System/nanoTime)
        outs (mapv (fn [{:keys [text ours]}]
                     (let [r (forgive/forms ctx opts :reply text)]
                       {:same? (= (:forms ours) (:forms r)) :forms (:forms r) :was (:forms ours)}))
                   inputs)
        ;; revision 2 read the prose "inside a `(do ...)`." as the forms (do ...) and `. ;
        ;; the structural suffix trial now reads that line as prose (designed)
        designed {6 (fn [was] (vec (remove #{"(do ...)" "`."} was)))}
        diffs (keep-indexed (fn [i o] (when-not (if-let [f (designed i)] (= (f (:was o)) (:forms o)) (:same? o))
                                        (assoc o :i i)))
                            outs)]
    {:pass? (empty? diffs) :count (count inputs) :designed (vec (keys designed)) :ms (ms-since t0) :diffs (vec (take 3 diffs))}))

(defproof :q3-threaded-facts :owner
  "Q3: each form reads earlier forms' facts: doc after defn, then after its redefinition; one transaction"
  (let [b (run (world) "(defn q3f \"First doc.\" [x] x)\n(:seon.fn/doc (seon.db/pull [:seon.fn/doc] [:seon.fn/sym 'user/q3f]))\n(defn q3f \"Second doc.\" [x] x)\n(:seon.fn/doc (seon.db/pull [:seon.fn/doc] [:seon.fn/sym 'user/q3f]))")]
    {:pass? (and (= ["First doc." "Second doc."] [(nth (values b) 1) (nth (values b) 3)]) (applied? b))
     :values (mapv str (values b))}))

(defproof :q4-lone-key :owner
  "Q4 (register! entrant): a lone storable key becomes an attribute; an unstorable form is noted with the bridge's reason; a bare entity of only that key is still refused (unowned-entity)"
  (let [b (run (world) "(seon.schema/register! :one.path.q4/n :int)\n(seon.schema/register! :one.path.q4/f [:=> [:cat :int] :int])")
        a (after b)
        attr (when a (get (d/schema a) :one.path.q4/n))
        bare (when a (db/with a [{:one.path.q4/n 3}]))]
    {:pass? (boolean (and (applied? b) (some? attr)
                          (= [:one.path.q4/f] (mapv :seon.schema/key (mapcat :seon.batch/notes (:seon.batch/entries b))))
                          (str/includes? (str (:seon.error/message bare)) "unowned-entity")))
     :attribute (select-keys attr [:db/valueType :db/cardinality])
     :notes (vec (mapcat :seon.batch/notes (:seon.batch/entries b)))}))

(defproof :q5-def-edges :owner
  "Q5: def/defonce are program entities with dependency edges, acquired in edge order into a fresh context (acquisition entrant)"
  (let [fresh (world)                ; acquired before these definitions existed
        b (run (world) "(def base-v 41)\n(def dep-v {:k base-v})\n(defonce once-v (inc base-v))")
        a (after b)
        rs (for [s '[user/base-v user/dep-v user/once-v]] (db/pull a '[:seon.fn/sym :seon.fn/source :seon.fn/calls] [:seon.fn/sym s]))
        acq (batch/evaluate-batch (sci.batch/acquire-request
                                   {:seon.batch/text (str/join "\n" (map :seon.fn/source rs))
                                    :seon.batch/world fresh}))
        got (values (batch/evaluate-batch (sci.batch/acquire-request
                                           {:seon.batch/text "[once-v dep-v]" :seon.batch/world fresh})))]
    {:pass? (and (applied? b) (every? :seon.fn/source rs)
                 (contains? (set (:seon.fn/calls (second rs))) 'user/base-v)
                 (= [[42 {:k 41}]] got) (empty? (diags acq)))
     :calls (mapv (comp vec :seon.fn/calls) rs) :acquired got}))

(defn- branch-of [conn] (:seon.agent/branch (meta conn) (get-in @conn [:config :branch])))

(defn- world-members
  "What the world says of itself, checked against its own members."
  [w]
  (let [conn (:seon.db/connection w)
        ctx (:seon.sci.eval/ctx w)
        env-conn (some-> ctx seon.env/of :seon.db/connection)]
    {:db-is-connections (if conn (= (db/commit-id (:seon.db/db w)) (db/commit-id (db/db conn))) :no-connection)
     :ctx-custody-is-connection (cond (nil? ctx) :no-context
                                      (nil? conn) :read-only
                                      :else (identical? env-conn conn))
     :writer-takes-connection (if conn (fn? (:seon.db/writer w)) (nil? (:seon.db/writer w)))}))

(defn world-errors
  "`w` validated against :seon.batch/world (the landing schema resource over
  the live projection): {:schema-errors [...]}, empty when valid (B5: no
  member installed as nil)."
  [w]
  (let [forms (update-keys (edn/read-string (slurp "/Users/sean/src/seon/resources/seon/schemas/seon.batch.edn"))
                           #(keyword "seon.batch" (name %)))
        registry (mr/composite-registry forms (:seon.schema.projection/registry (db/carried-projection (wdb w))))]
    {:schema-errors (mapv #(select-keys % [:in :value]) (:errors (m/explain :seon.batch/world w {:registry registry})))}))

(defproof :world-members-agree :world
  "For every entrant's world (agent turn, MCP SCI, MCP JVM, test agent, render, install gate, register!, merge), the world validates against :seon.batch/world with no nil member, and every member agrees: its value is its connection's, its SCI fork's custody is that same connection, and a write through its writer lands on its branch (a test agent's on its requester's); read-only worlds have no connection and refuse a write"
  (let [conn (:seon.db/connection *exec*)
        agent (world {:seon.agent/id "root"})
        mcp (world {:seon.agent/id "mcp/op3"})
        requester conn
        texec (acquire!)
        results
        (try
          (let [test-agent (without-native-guard (world/test-agent-world texec requester {:seon.agent/id "root"}))
                worlds {:agent-turn agent :mcp-sci mcp
                        :mcp-jvm (world/jvm-world "default" conn {:seon.agent/id "root"})
                        :test-agent test-agent
                        :render (world/read-only-world agent (wdb agent))
                        :gate (world/read-only-world agent (wdb agent))
                        :register (world {:seon.agent/id "root"})
                        :merge (without-native-guard (world/merge-world *exec* {}))}
                members (update-vals worlds world-members)
                ;; a write through each writable world: a fresh namespace entity, found only where it should land
                wrote (into {} (for [[k w] worlds
                                     :let [n (symbol (str "op3.world." (name k) "." (subs (str (random-uuid)) 0 6)))
                                           target (world/settlement-connection w)]]
                                 [k (if-let [writer (:seon.db/writer w)]
                                      (let [r (writer target [{:seon.ns/name n}])]
                                        {:landed (boolean (db/pull (db/db target) [:db/id] [:seon.ns/name n]))
                                         :own-branch? (identical? target (:seon.db/connection w))
                                         :elsewhere (boolean (when-not (identical? target conn)
                                                               (db/pull (db/db conn) [:db/id] [:seon.ns/name n])))})
                                      {:refused (let [b (batch/evaluate-batch (turn.batch/render-request
                                                                               (request w (str "(seon.db/transact! [{:seon.ns/name '" n "}])") {})))]
                                                  (= 'seon.db/missing-connection-error (:seon.error/operation (first (values b)))))})]))]
            (into {} (for [[k w] worlds] [k (merge (get members k) (get wrote k) (world-errors w))])))
          (finally (release! texec)))
        ok? (fn [[k m]]
              (and (empty? (:schema-errors m))
                   (#{true :no-connection} (:db-is-connections m))
                   (#{true :no-context :read-only} (:ctx-custody-is-connection m))
                   (:writer-takes-connection m)
                   (or (:refused m) (and (:landed m) (not (:elsewhere m))))
                   (if (= k :test-agent) (false? (:own-branch? m)) true)))]
    {:pass? (every? ok? results) :worlds results}))

(defproof :worlds-never-cross :world
  "Two agent worlds on two branches (two executions) and two concurrent test agents: databases, writes and SCI state stay separate"
  (let [ea (acquire!) eb (acquire!)]
    (try
      (let [wa (without-native-guard (world/agent-world ea {:seon.agent/id "root"}))
            wb (without-native-guard (world/agent-world eb {:seon.agent/id "root"}))
            ba (future (batch/evaluate-batch (turn.batch/code-request (request wa "(def op3-cross :a)\nop3-cross" {}))))
            bb (future (batch/evaluate-batch (turn.batch/code-request (request wb "(def op3-cross :b)\nop3-cross" {}))))
            [ra rb] [@ba @bb]
            src (fn [e] (:seon.fn/source (db/pull (db/db (:seon.db/connection e)) [:seon.fn/source] [:seon.fn/sym 'user/op3-cross])))]
        {:pass? (and (= [:a] (rest (values ra))) (= [:b] (rest (values rb)))
                     (= "(def op3-cross :a)" (src ea)) (= "(def op3-cross :b)" (src eb))
                     (= :a (sci/eval-string* (wctx wa) "op3-cross")) (= :b (sci/eval-string* (wctx wb) "op3-cross"))
                     (not (identical? (:seon.db/connection ea) (:seon.db/connection eb))))
         :sources [(src ea) (src eb)]})
      (finally (release! ea) (release! eb)))))

(defproof :world-survives-compute-hop :world
  "A form evaluated on another thread (a compute-pool hop) loses the submitting thread's bindings; carrying the world as a value and re-establishing them from it (seon.world/with-world) gives the same custody, read basis and context"
  (let [w (world)
        pool (java.util.concurrent.Executors/newFixedThreadPool 1)]
    (try
      (let [probe #(db/pull [:seon.agent/id] [:seon.agent/id "root"])
            ;; on the submitting thread, under the world
            here (world/with-world w probe)
            bare (.get (.submit pool ^Callable (fn [] (try (probe) (catch Throwable t [:refused (ex-message t)])))))
            carried (.get (.submit pool ^Callable (fn [] (world/with-world w probe))))
            ;; a whole batch whose evaluate stage hops to the pool, carrying the
            ;; world as a value; the observation scope is the evaluation's own
            ;; dynamic binding, conveyed as a future conveys it (bound-fn): a
            ;; write on the pool thread is observed and stored
            hop-req (assoc (turn.batch/code-request (request w "(defn hopf [x] x)\n(:seon.fn/sym (seon.db/pull [:seon.fn/sym] [:seon.fn/sym 'user/hopf]))" {}))
                           :seon.batch/evaluate (fn [req form working]
                                                  (let [^Callable task (bound-fn [] (world/with-world (:seon.batch/world req)
                                                                                      #(seon.sci.batch/evaluate req form working)))]
                                                    (.get (.submit pool task)))))
            b (batch/evaluate-batch hop-req)]
        {:pass? (and (= "root" (:seon.agent/id here)) (= here carried)
                     (not= here bare)
                     (= 'user/hopf (last (values b))) (applied? b))
         :here here :bare bare :hop-values (mapv str (values b))})
      (finally (.shutdown pool)))))

(defproof :speculative-batch-keeps-committed-projection :schema
  "Ruling B: a speculative value's projection derived from its own basis's projection (never through the committed projection cache) costs the same after a 22-registration batch as before; the landed seon.db path at HEAD does not, which is what S-B changes"
 (on-fresh-branch
   (fn [] (let [w (world)
        conn (:seon.db/connection *exec*)
        tx-of (fn [nm] (let [b (run (world) (str "(defn " nm " [x] x)") {:seon.batch/settle nil})]
                         (turn.batch/form-facts (turn.batch/code-request (request (world) "" {}))
                                                (:seon.db/db (world))
                                                (get-in b [:seon.batch/entries 0 :seon.batch/change]))))
        t (fn [f] (let [t0 (System/nanoTime)] (f) (ms-since t0)))
        speculative (fn [tx] (let [basis (db/db conn)
                                   prepared (#'db/prepared-write (db/carried-projection basis) basis tx)]
                               [basis (db/carry-projection-state (:db-after (d/with basis (update prepared :tx-meta dissoc :datahike/validate-report)))
                                                                 (:seon.sci.eval/projection-state (meta basis)))]))
        from-basis (fn [[basis v]] (t #(seon.schema/load-projection v (db/carried-projection basis))))
        through-cache (fn [[_ v]] (t #(db/carried-projection v)))
        before-b (from-basis (speculative (tx-of "spb1")))
        before-c (through-cache (speculative (tx-of "spc1")))
        _ (run (world) (slurp "/Users/sean/src/seon/tmp/valid-clojure-corpus/items/h36-register-22.clj"))
        after-b (from-basis (speculative (tx-of "spb2")))
        after-c (through-cache (speculative (tx-of "spc2")))]
    {:pass? (< after-b 100)
     :ruling-b-ms {:before before-b :after after-b}
     :head-path-ms {:before before-c :after after-c}}))))

;;; --- Both entrants: an MCP eval and a Seon agent turn ---------------------------

(defn mcp-eval
  "The MCP tool as a thin wrapper: transport (JSON, as script/seon/dev/mcp.clj
  encodes with cheshire) around the one path's reply for a code batch."
  [w text]
  (-> (batch/evaluate-batch (turn.batch/code-request (request w text {})))
      turn.batch/reply
      (cheshire.core/generate-string)
      (cheshire.core/parse-string true)))

(defn agent-turn
  "A Seon agent turn: a scripted local provider stub (no paid call) answers
  the turn's context with `scripted`; the reply is evaluated through the one
  path and its reply is what the agent's next context reads."
  [w scripted]
  (let [provider (fn [_context] scripted)
        text (provider {:seon.agent/id "root"})]
    (turn.batch/reply (batch/evaluate-batch (turn.batch/turn-request (request w text {}))))))

(defn- transport-free
  "A reply as the MCP transport leaves it (JSON keys and symbols as strings),
  without its nondeterministic timings and fault occurrence identities."
  [r]
  (-> (cheshire.core/parse-string (cheshire.core/generate-string r) true)
      (update :seon.batch/faults (fn [fs] (mapv #(dissoc % :seon.error.occurrence/id) fs)))
      ;; a diagnostic's printed error names the instant it occurred
      (update :seon.batch/diagnostics (fn [ds] (mapv #(str/replace % #"#inst [^,}]*" "#inst") ds)))
      (update :seon.batch/evaluations (fn [es] (mapv #(dissoc % :seon.batch/duration-ms :seon.batch/total-ms) es)))))

(def owner-behaviors
  "Each owner behavior's input, run through both entrants."
  {:oq1 "(defn be1 {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(defn be-bad {:malli/schema [:=> [:cat :no.such/key] :int]} [x] 1)\n(be1 5)"
   :q3 "(defn be3 \"First.\" [x] x)\n(:seon.fn/doc (seon.db/pull [:seon.fn/doc] [:seon.fn/sym 'user/be3]))"
   :q4 "(seon.schema/register! :be.q4/n :int)\n(seon.schema/register! :be.q4/f [:=> [:cat :int] :int])"
   :q5 "(def be-base 41)\n(def be-dep {:k be-base})"
   :contract "(defn be-nc1 [x] x)\n(defn be-nc2 [x] x)"
   :gate "(defn be-gt {:malli/schema [:=> [:cat :int] :int]} [x] (str x))\n(be-gt 1)"
   :feedback "(require '[clojure.test :refer [deftest is]])\n(defn be-f [x] x)\n(deftest be-t (is (= 1 (be-f 1))))"
   :removal "(defn be-r [x] x)\n(ns-unmap *ns* 'be-r)"
   ;; dogfood 6: a render that refuses is a diagnostic beside the value; the run still commits
   :render-refusal "{:seon.render/ai 'no.such/renderer :x 1}\n(defn be-after-render [x] x)"})

(defproof :mcp-equals-agent-turn :entrant
  "MCP is a thin wrapper: every owner behavior run as an MCP eval and as a Seon agent turn (scripted local provider stub) answers the same reply, transport aside"
  (let [pairs (for [[k text] owner-behaviors]
                ;; each side on its own fresh branch: the same world, built twice
                [k (on-fresh-branch #(transport-free (mcp-eval (world {:seon.agent/id "root"}) text)))
                   (on-fresh-branch #(transport-free (agent-turn (world {:seon.agent/id "root"}) text)))])
        differ (vec (for [[k a b] pairs :when (not= a b)] [k (brief a) (brief b)]))]
    {:pass? (empty? differ) :behaviors (count pairs) :differ differ}))

;;; --- Feedback from facts ---------------------------------------------------

(defproof :ns-and-defn-in-one-form :effects
  "(do (ns …) (defn …)) in one form: the namespace and its function are stored in one transaction (found refused at HEAD by the feedback lane)"
  (let [b (run (world) "(do (ns op3.onef (:require [clojure.string :as s])) (defn up [x] (s/upper-case x)))\n(op3.onef/up \"a\")")
        a (after b)]
    {:pass? (and (= "A" (last (values b))) (applied? b) (stored? a 'op3.onef/up) (empty? (facts-errors b)))
     :facts-errors (facts-errors b)}))

(defproof :agent-starts-in-another-namespace :effects
  "An agent created in user runs a later batch starting in another namespace (found refused at HEAD by the feedback lane): the start namespace is a stage value"
  (let [w (world)
        _ (run w "(def home 1)")
        _ (run w "(ns op3.elsewhere)")
        b (run w "(defn there [] :here)\n(there)" {:seon.batch/start-ns 'op3.elsewhere})
        a (after b)]
    {:pass? (and (= :here (last (values b))) (applied? b) (stored? a 'op3.elsewhere/there))
     :values (mapv str (values b))}))

;;; --- The install gate judges the change (REPL dogfood items 2-4) -------------------

(defn tree-expansion-hooks
  "seon.fn.analyzer/expansion-hooks as the working tree has it, evaluated in a
  scratch namespace (the installed analyzer predates it until the restart)."
  [expansions first-row]
  (let [src (slurp "/Users/sean/src/seon/src/seon/fn/analyzer.clj")
        form (read-string (subs src (str/index-of src "(defn expansion-hooks")))]
    (binding [*ns* (create-ns 'one-path-proof.analyzer)]
      (refer-clojure)
      (eval form))
    ((ns-resolve 'one-path-proof.analyzer 'expansion-hooks) expansions first-row)))

;;; --- Astra revision-3 execution blockers (review f3c1db347) -----------------
;; Each imported from tmp/one-path-review/proof.clj, stated against these
;; namespaces; each failed on the reviewed prototype.

(defn- throw-stage [& _] (throw (ex-info "review injected failure" {})))

(defn- tiny-request
  "One form read, whose evaluation answers `value`."
  [value]
  {:seon.batch/text "nil"
   :seon.batch/read-state (fn [text] {:seon.batch/version {:seon.batch/text text :seon.batch/pos 0}})
   :seon.batch/read-next (fn [_ {v :seon.batch/version}]
                           (if (zero? (:seon.batch/pos v))
                             {:seon.batch/form nil :seon.batch/start 0 :seon.batch/end 3
                              :seon.batch/version (assoc v :seon.batch/pos 3)}
                             {:seon.batch/eof true :seon.batch/version v}))
   :seon.batch/ns-name (constantly 'user)
   :seon.batch/evaluate (fn [& _] value)})

(defn- kept? [b v] (let [e (first (:seon.batch/entries b))]
                     (and e (contains? e :seon.batch/value) (= v (:seon.batch/value e)))))

(defproof :b1-nil-print-failure :totality
  "B1: a nil value followed by a throwing show keeps the entry and its value (contains? :seon.batch/value), with one show diagnostic"
  (let [b (batch/evaluate-batch (assoc (tiny-request nil) :seon.batch/show throw-stage))]
    {:pass? (and (kept? b nil) (= ["show: review injected failure"] (diags b))
                 (= "#<unprintable nil>" (:seon.batch/shown (first (:seon.batch/entries b)))))
     :entries (mapv #(dissoc % :seon.batch/duration-ms :seon.batch/total-ms :seon.batch/allocated-bytes) (:seon.batch/entries b)) :diagnostics (diags b)}))

(defproof :b1-value-equal-to-sentinel :totality
  "B1: an evaluated value equal to any keyword the fold might use as a sentinel is kept when the observer throws after the evaluation"
  (let [vs [:seon.batch/none :seon.batch/nil-write (keyword "seon.batch" "none")]
        bs (mapv #(batch/evaluate-batch (assoc (tiny-request %) :seon.batch/observe (fn [_ _ _ thunk] (thunk) (throw-stage)))) vs)]
    {:pass? (every? true? (map kept? bs vs)) :diagnostics (mapv diags bs)}))

(defproof :b1-malformed-observation :totality
  "B1: an observer handing a write that is not a map keeps the value, with one observe diagnostic"
  (let [b (batch/evaluate-batch (assoc (tiny-request 42) :seon.batch/observe (fn [_ _ on-write thunk] (on-write 42) (on-write nil) (thunk))))]
    {:pass? (and (kept? b 42) (= 1 (count (diags b))) (str/starts-with? (first (diags b)) "observe: 2 observed writes"))
     :diagnostics (diags b)}))

(defproof :b7-concurrent-observation :effects
  "B7: eight futures each handing 1,000 writes to one evaluation's observer, all awaited before it returns: all 8,000 are observed; a write after the evaluation completed is refused to its writer"
  (let [late (promise)
        b (batch/evaluate-batch
           (assoc (tiny-request 42) :seon.batch/observe
                  (fn [_ _ on-write thunk]
                    (let [ready (java.util.concurrent.CountDownLatch. 8)
                          start (java.util.concurrent.CountDownLatch. 1)
                          jobs (mapv (fn [i] (future (.countDown ready) (.await start)
                                                    (dotimes [j 1000]
                                                      (on-write {:seon.batch/op :namespace :seon.batch/ns (symbol (str "review." i "." j))}))))
                                     (range 8))]
                      (.await ready) (.countDown start) (run! deref jobs)
                      (deliver late on-write)
                      (thunk)))))
        refused (try (@late {:seon.batch/op :namespace :seon.batch/ns 'review.late}) :accepted
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    {:pass? (and (= 8000 (count (:seon.batch/writes b))) (kept? b 42)
                 (= "The evaluation that owned this observation has completed." refused))
     :observed (count (:seon.batch/writes b)) :late refused}))

(defproof :b7-lazy-definition :effects
  "B7: a definition made while the form's value is realized by its print is observed and stored: (lazy-seq (def late-root 42) (list late-root))"
  (on-fresh-branch
   #(let [b (run (world) "(lazy-seq (def late-root 42) (list late-root))")]
      {:pass? (and (= ["(42)"] (shown b)) (applied? b) (stored? (after b) 'user/late-root)
                   (not (turn.batch/read-only? b (first (:seon.batch/entries b)))))
       :shown (shown b) :diagnostics (diags b)})))

(defproof :b4-writer-refusal-reply :totality
  "B4: a writer refusing the recording keeps 42; the reply carries the refusal as data and the settle diagnostic, recorded as a core fault by seon.fault (its receipt in the reply), for both entrants"
  (on-fresh-branch
   #(let [refusing (constantly {:seon.error/message "review writer refusal"})
          code (run (world) "42" {:writer refusing})
          agent (run (world) "(+ 40 2)" {:writer refusing :mode :reply})
          ok? (fn [b] (let [r (turn.batch/reply b)]
                        (and (= [42] (values b))
                             (= "review writer refusal" (get-in r [:seon.batch/commit :seon.batch/writer-refusal :seon.error/message]))
                             (some (fn [d] (str/includes? d "review writer refusal")) (:seon.batch/diagnostics r))
                             (some (fn [f] (and (:seon.fault/committed? f) (= :seon.batch/settle (:seon.error/layer f)))) (:seon.batch/faults r)))))]
      {:pass? (boolean (and (ok? code) (ok? agent)))
       :reply (select-keys (turn.batch/reply code) [:seon.batch/commit :seon.batch/diagnostics :seon.batch/faults])})))

(defproof :b4-moved-head-feedback :effects
  "B4: settlement on a moved head that refuses a deletion answers the committed state: the refused removal leaves its definition stored and the other change committed"
  (on-fresh-branch
   #(let [w (world)
          _ (run w "(defn review-base [x] x)")
          old (wdb (world))
          _ (run (world) "(defn review-dep [x] (review-base x))")
          b (run (assoc w :seon.db/db old) "(defn review-new [x] x)\n(ns-unmap *ns* 'review-base)\n(review-new 9)")
]
      {:pass? (and (applied? b) (stored? (after b) 'user/review-base) (stored? (after b) 'user/review-new)
                   (= 9 (last (values b))))
       :errors (facts-errors b)})))

(defn- jvm-run [text]
  (let [w (world)]
    (batch/evaluate-batch (turn.batch/jvm-request (request w text {:seon.batch/start-ns 'one-path-proof.host})))))

(defproof :b6-jvm-floor :entrant
  "B6: MCP JVM mode shows through the same floor as SCI: a 12,000-character string is cut by the floor's budget (with its requery), identically bounded in both entrants"
  (let [text "(apply str (repeat 12000 \"x\"))"
        j (jvm-run text)
        c (run (world) text)
        js (first (shown j)) cs (first (shown c))]
    {:pass? (and (= 12000 (count (first (values j)))) (< (count js) 4000) (= (count js) (count cs)))
     :jvm-shown-chars (count js) :sci-shown-chars (count cs) :jvm-tail (subs js (max 0 (- (count js) 160)))}))

(defproof :b6-jvm-throwing-print-and-error :entrant
  "B6: in JVM mode a value whose print throws and a thrown error both show through the floor (the error as the one error value), never a second printer"
  (let [j (jvm-run "(reify Object (toString [_] (throw (ex-info \"print failed\" {}))))\n(throw (ex-info \"jvm boom\" {:k 1}))")
        [a e] (shown j)]
    {:pass? (and (= 2 (count (:seon.batch/entries j))) (string? a) (str/includes? e "jvm boom") (not (str/starts-with? e "ERR ")))
     :shown (shown j) :diagnostics (diags j)}))

(defproof :b3-repair-reads-tag-once :reader
  "B3: a delimiter repair is decided structurally before the one semantic read: \"[#review/tag 1\" invokes its tag reader once and yields the one repaired form"
  (let [calls (atom 0)
        ctx (sci/init {})
        opts {:readers {'review/tag (fn [x] (swap! calls inc) x)}}
        r (forgive/forms ctx opts :code "[#review/tag 1")]
    {:pass? (and (= 1 @calls) (= 1 (count (:forms r))) (= [:delimiters] (mapv :seon.batch/repair-kind (:repairs r))))
     :reader-calls @calls :result r}))

(defproof :b3-malformed-tag-read-once :reader
  "B3: a structurally whole form whose tag reader throws is read once and answers its read error, never retried"
  (let [calls (atom 0)
        ctx (sci/init {})
        opts {:readers {'review/tag (fn [_] (swap! calls inc) (throw (ex-info "bad tag" {})))}}
        r (forgive/forms ctx opts :code "[#review/tag 1]")]
    {:pass? (and (= 1 @calls) (= 0 (:unread-from r)) (str/includes? (str (:reason r)) "bad tag"))
     :reader-calls @calls :reason (:reason r)}))

(defproof :b2-exact-span-no-fallback :join
  "B2: an authored definition whose producer span [1 1 1 12] differs from the analyzed definition's [1 1 1 30] joins nothing (no start-position or containment fallback)"
  (let [j (turn.batch/join-write
           {:seon.fn/analysis
            {:seon.fn.analyzer/var-definitions
             [{:seon.fn.analyzer/name 'f :seon.fn.analyzer/ns 'user
               :seon.fn.analyzer/row 1 :seon.fn.analyzer/col 1
               :seon.fn.analyzer/end-row 1 :seon.fn.analyzer/end-col 30}]}}
           {:first-row 1 :line 1 :column 1}
           {:seon.batch/sym 'user/f :seon.batch/origin {:line 1 :column 1 :end-line 1 :end-column 12}})]
    {:pass? (= :unanalyzed (:seon.batch/join j)) :join j}))

(defproof :b2-generated-definition-hook :join
  "B2 (mechanism; stored entity and reacquisition wait on root's analyzer patch): the expansion observed at the call occurrence (single) at [2 1 2 9], carried into clj-kondo as an :analyze-call hook keyed by that occurrence, makes the analysis define user/made at the call, and a second call elsewhere is left as written"
  (let [src "(defmacro single [] (list 'def 'made 7))\n(single)\n(comment (single))"
        events [{:seon.batch/op :expansion :seon.batch/macro 'user/single
                 :seon.batch/call-span [2 1 2 9] :seon.batch/expansion '(def made 7)}]
        hooks (tree-expansion-hooks events 1)
        a (:analysis (with-in-str src (clj-kondo.core/run! {:lint ["-"] :cache false
                                                            :config {:output {:format :none} :hooks hooks :analysis true}})))
        made (filterv #(= 'made (:name %)) (:var-definitions a))]
    {:pass? (and (= 1 (count made)) (= [2 1] ((juxt :row :col) (first made))))
     :made (mapv #(select-keys % [:name :row :col :end-row :end-col]) made)}))

;;; --- red-triage r6 (docs/research/agent-platform/red-triage-r6-2026-09-27.md) ---

(defproof :r6-shown-by-the-one-print-owner :print
  "r6: every shown text EQUALS the one print owner's rendering (seon.sci.eval/shown-result, shown-failure, through seon.turn.batch/show) of the value that settles: an error, a value naming its own renderer, a map; never pr-str"
  (let [w (world)
        b (run w "(throw (ex-info \"owner\" {:k 1}))\n{:seon.render/ai \"(+ 1 2)\" :x 1}\n{:a (range 3)}")
        req (request w "" {})
        owner (mapv #(:seon.batch/shown (turn.batch/show (assoc-in req [:seon.batch/world :seon.db/db] (after b)) %)) (:seon.batch/entries b))]
    {:pass? (and (= 3 (count owner)) (= owner (shown b))
                 (not= (pr-str (second (values b))) (second (shown b))))
     :shown (shown b)}))

(defproof :r6-unresolved-names-kept-as-values :effects
  "r6: a definition's call into a namespace with no program entity is kept in :seon.fn/calls as a symbol value, and a required namespace with no program entity is kept in :seon.ns/requires as a symbol; neither mints an identity"
  (on-fresh-branch
   #(let [b (run (world) "(require 'clojure.set)\n(defn set-caller [] (clojure.set/union #{} #{}))")
          a (after b)
          calls (set (:seon.fn/calls (db/pull a [:seon.fn/calls] [:seon.fn/sym 'user/set-caller])))
          requires (set (:seon.ns/requires (db/pull a [:seon.ns/requires] [:seon.ns/name 'user])))]
      {:pass? (and (applied? b) (contains? calls 'clojure.set/union) (not (stored? a 'clojure.set/union))
                   (contains? requires 'clojure.set) (not (ns-stored? a 'clojure.set)))
       :calls calls :requires requires})))

(defproof :r6-e1-opening-reads-the-inbox :parity
  "r6 E1: the agent's opening records its inbox read ((my.message/inbox ...), unread only, 5d95c4913), so a changed message is a changed read refreshed into the next context; MCP runs of that agent read the same opening. Needs the JVM to run 5d95c4913 or later"
  (let [instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")
        h (:seon.turn.loop/cluster instance)
        forms (mapv :seon.cluster.eval/source (:seon.turn/forms (#'seon.turn/declared-sources h (db/db (:seon.db/connection h)) "root" 'user)))
        inbox (filterv #(str/includes? % "my.message/inbox") forms)
        duplicates (into {} (filter (fn [[_ n]] (< 1 n))) (frequencies forms))]
    ;; two walk units render the same runtime pull; the system plan keeps one per source
    {:pass? (= 1 (count inbox))
     :inbox-reads inbox :walk-duplicates (count duplicates) :opening-forms (count forms)}))

;;; --- Dogfood 6: parity -------------------------------------------------------

(defproof :parity-faults-in-both-replies :parity
  "Dogfood 6: a later stage's failure is a diagnostic recorded as a core fault, and its receipt is in the reply of an MCP eval and of a Seon agent turn alike (the agent's next context reads the same saved reply)"
  (let [text (:render-refusal owner-behaviors)
        m (on-fresh-branch #(mcp-eval (world) text))
        a (on-fresh-branch #(agent-turn (world) text))
        faulted? (fn [r] (and (seq (:seon.batch/faults r)) (seq (:seon.batch/diagnostics r))))]
    {:pass? (and (faulted? m) (faulted? a) (= (count (:seon.batch/faults m)) (count (:seon.batch/faults a))))
     :mcp-faults (count (:seon.batch/faults m)) :agent-faults (count (:seon.batch/faults a))
     :diagnostics (:seon.batch/diagnostics a)}))

(defproof :parity-render-refusal-never-parks :parity
  "Dogfood 6: a value whose renderer refuses is shown by the floor with one diagnostic; its run still commits and the next form runs, for both entrants (it parked the agent's turn loop before)"
  (let [text (:render-refusal owner-behaviors)
        go (fn [mode] (on-fresh-branch #(let [b (run (world) text (if (= mode :agent) {:mode :reply} {}))]
                                          {:applied? (applied? b) :stored? (stored? (after b) 'user/be-after-render)
                                           :values (count (:seon.batch/entries b)) :shown (first (shown b))})))
        m (go :mcp) a (go :agent)]
    {:pass? (and (= m a) (:applied? m) (:stored? m) (= 2 (:values m)) (string? (:shown m))) :mcp m :agent a}))

;;; --- Per-world call sink (root: test ownership in the world) -----------------

(defn- patched-sink-macro!
  "The count seon.profile's armed wrapper makes at each call, loaded from
  src/seon/profile.clj into its own scratch namespace (no existing Var is
  redefined)."
  []
  (let [src (slurp "/Users/sean/src/seon/src/seon/profile.clj")
        form-at (fn [start] (let [i (str/index-of src start)] (read-string (subs src i))))
        adder (form-at "(def ^java.util.function.Function new-adder")
        counter (form-at "(defmacro count-into-world!")]
    (binding [*ns* (create-ns 'one-path-proof.profile-sink)]
      (refer-clojure)
      (import '[java.util.concurrent.atomic LongAdder])
      (eval adder)
      (eval (clojure.walk/postwalk-replace {'seon.profile/new-adder 'one-path-proof.profile-sink/new-adder} counter)))
    (eval '(fn [sym] (one-path-proof.profile-sink/count-into-world! sym)))))

(defproof :sink-per-world :world
  "Two concurrent test agents in two worlds call the same counted function; each world's sink counts only its own calls (1,000 and 1,500), where the JVM-wide armed counter counts both (seon.profile's count, loaded from its source)"
  (let [count! (patched-sink-macro!)
        ea (acquire!) eb (acquire!)]
    (try
      (let [requester (:seon.db/connection *exec*)
            wa (world/test-agent-world ea requester {:seon.agent/id "root"})
            wb (world/test-agent-world eb requester {:seon.agent/id "root"})
            jvm-wide (java.util.concurrent.atomic.LongAdder.)
            f (fn [] (.increment jvm-wide) (count! 'user/counted))
            go (fn [w n] (future (world/with-world w #(dotimes [_ n] (f)))))
            [a b] [(go wa 1000) (go wb 1500)]]
        @a @b
        (let [ca (batch/calls (:seon.batch/sink wa)) cb (batch/calls (:seon.batch/sink wb))]
          {:pass? (and (= {'user/counted 1000} ca) (= {'user/counted 1500} cb) (= 2500 (.sum jvm-wide)))
           :a ca :b cb :jvm-wide (.sum jvm-wide)}))
      (finally (release! ea) (release! eb)))))

(defproof :s3-schema-rehearsal :landing
  "S3 rehearsal: every :seon.batch/* declaration the landing publishes registers through the one path on a disposable branch of the live store, applied and stored, with no refusal"
  (on-fresh-branch
   (fn []
     (let [forms (edn/read-string (slurp "/Users/sean/src/seon/resources/seon/schemas/seon.batch.edn"))
           refs (fn [f] (into #{} (filter #(and (keyword? %) (contains? forms %))) (tree-seq coll? seq f)))
           ;; declarations before their users, as one resource publishes them together
           ordered (loop [done [] seen #{} left (set (keys forms))]
                     (if (empty? left) done
                         (let [ready (sort (filter #(every? (fn [r] (or (= r %) (seen r))) (refs (forms %))) left))
                               ready (if (seq ready) ready [(first (sort left))])]
                           (recur (into done ready) (into seen ready) (reduce disj left ready)))))
           text (str/join "\n" (for [k ordered] (str "(seon.schema/register! " (pr-str k) " '" (pr-str (forms k)) ")")))
           t0 (System/nanoTime)
           b (run (world) text)
           a (after b)
           errors (vec (keep (fn [e] (when (contains? e :seon.batch/error) (ex-message (:seon.batch/error e)))) (:seon.batch/entries b)))
           stored (count (db/q '[:find [?k ...] :where [_ :seon.schema/key ?k] [(namespace ?k) ?n] [(= ?n "seon.batch")]] a))]
       ;; register! (the REPL's path) admits a [:fn] predicate only once the
       ;; predicate is a proven program entity: seon.batch/sink? becomes one when
       ;; its file publishes, so the sink and the world that names it are the
       ;; publication's (`s3-population-admits`), not the REPL's
       {:pass? (let [reaches-sink? (fn reaches? [k seen] (or (= k :seon.batch/sink)
                                                              (some #(and (not (seen %)) (reaches? % (conj seen %))) (refs (forms k)))))
                     sink-bound (set (filter #(reaches-sink? % #{%}) (keys forms)))]
                 (and (applied? b) (empty? (facts-errors b))
                      (every? (fn [e] (some #(str/includes? e (str %)) sink-bound)) errors)
                      ;; every declared key is stored (keys an earlier landing stored stay)
                      (<= (- (count forms) (count sink-bound)) stored)))
        :keys (count forms) :stored stored :ms (ms-since t0) :errors (vec (take 3 errors)) :facts-errors (vec (take 3 (facts-errors b)))
        :notes (vec (take 3 (mapcat :seon.batch/notes (:seon.batch/entries b))))}))))

(defproof :s3-population-admits :landing
  "S3 rehearsal of publication: the packaged population of git archive HEAD with seon.batch.edn added passes schema admission (seon.schema.edn/admit) exactly as far as HEAD's own population does in this JVM: the new file adds no refusal"
  (let [root (gate-root!)]
    (try
      (let [files (->> (.listFiles (io/file root "resources/seon/schemas"))
                       (filter #(.endsWith (.getName ^java.io.File %) ".edn")) (sort-by #(.getName ^java.io.File %)))
            all (apply merge (map #(edn/read-string (slurp %)) files))
            mine (set (keys (edn/read-string (slurp "/Users/sean/src/seon/resources/seon/schemas/seon.batch.edn"))))
            admit (fn [forms] (try {:admitted (count (seon.schema.edn/admit {:seon.schema/forms (seon.schema.edn/derive-config-forms forms)}))}
                                   (catch Throwable t {:refused (ex-message t)})))
            head (admit (apply dissoc all mine))
            with (admit all)]
        {:pass? (and (= head with) (not (some #(str/includes? (str (:refused with)) (str %)) mine)))
         :head head :with-batch with :keys (count mine)})
      (finally (sh/sh "rm" "-rf" root)))))

;;; --- The branch entrance (dogfood 6): my.agent's, shared with MCP's tool -------

(defn- scratch-my-agent
  "src/my/agent.clj loaded as its own scratch namespace (no live Var is
  redefined): the entrance as it lands."
  []
  (let [text (slurp "/Users/sean/src/seon/src/my/agent.clj")
        text (str/replace-first text "(ns ^{:seon.ns/context-relevant? true} my.agent" "(ns one-path-proof.my-agent")]
    (load-string text)
    (fn [sym] @(ns-resolve 'one-path-proof.my-agent sym))))

(defproof :branch-entrance :parity
  "Dogfood 6: one my.agent entrance creates, lists and retires a lane branch: a created branch forks the cluster head's commit; an existing name and the cluster's own branch refuse as data; retiring a branch whose hosted agent ran retires the host first, then the roster entry; retiring an unheld branch (the state the MCP tool's NPE left tfp-sel in) retires it"
  (let [f (scratch-my-agent)
        instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")
        handle (:seon.turn.loop/cluster instance)
        context {:seon.store/store (:seon.store/store instance) :seon.db/connection (:seon.db/connection handle)
                 :seon.agent/routing (:seon.agent/routing handle)}
        a (keyword (str "op-branch-" (subs (str (random-uuid)) 0 6)))
        b (keyword (str "op-branch-" (subs (str (random-uuid)) 0 6)))
        created ((f 'create-branch!) {:seon.store/branch a} context)
        again ((f 'create-branch!) {:seon.store/branch a} context)
        own ((f 'create-branch!) {:seon.store/branch (:my.agent/own-branch ((f 'branches) context))} context)
        _ ((f 'create-branch!) {:seon.store/branch b} context)
        host ((requiring-resolve 'seon.cluster.host/host!)
              {:seon.turn.loop/cluster (assoc handle :seon.store/store (:seon.store/store instance))
               :seon.agent/branch a :seon.agent/id (str "mcp/" (name a)) :seon.ns/name 'user})
        hosted-before (some? ((requiring-resolve 'seon.cluster.host/hosted) handle a))
        retired ((f 'retire-branch!) {:seon.store/branch a} context)
        retired-unheld ((f 'retire-branch!) {:seon.store/branch b} context)
        roster (:my.agent/roster ((f 'branches) context))]
    {:pass? (and (:seon.cluster/created? created) (uuid? (:seon.cluster.registry/from created))
                 (str/includes? (str (:seon.error/message again)) "already exists")
                 (str/includes? (str (:seon.error/message own)) "shared")
                 (some? host) hosted-before
                 (= {:seon.store/branch a :my.agent/retired? true} retired)
                 (= {:seon.store/branch b :my.agent/retired? true} retired-unheld)
                 (not (contains? roster a)) (not (contains? roster b))
                 (nil? ((requiring-resolve 'seon.cluster.host/hosted) handle a)))
     :created (dissoc created :seon.cluster.registry/from) :refusals [(:seon.error/message again) (:seon.error/message own)]
     :retired [retired retired-unheld]}))

;;; --- The supply stage (S3a): world members filled from the calling world -------

(defn- scratch-call-preparation
  "src/seon/call_preparation.clj as it lands, loaded as its own scratch
  namespace (no live Var is redefined): its plan and prepare over a contract."
  []
  (let [text (-> (slurp "/Users/sean/src/seon/src/seon/call_preparation.clj")
                 (str/replace-first "(ns seon.call-preparation" "(ns one-path-proof.call-preparation")
                 (str/replace "'seon.call-preparation/state?" "'one-path-proof.call-preparation/state?")
                 (str/replace "[:fn seon.call-preparation/state?]" "[:fn one-path-proof.call-preparation/state?]"))]
    (load-string text)
    (fn [sym] @(ns-resolve 'one-path-proof.call-preparation sym))))

(defn- scratch-plan
  "The invocation plan the landing arms `contract` with, under `registry`,
  from the world members alone (no supplied-default rows)."
  [cp contract registry]
  (let [arities ((cp 'arity-facts) (m/schema contract {:registry registry}))
        index @(ns-resolve 'one-path-proof.call-preparation 'world-candidates)
        plans (mapv #((cp 'arity-plan) index %) arities)]
    ((cp 'compiled-plan) 'user/target plans)))

(defproof :supply-from-the-world :supply
  "S3a: an omitted world member (positional or a missing map key, required or optional) is filled from the calling evaluation's world before validation; a caller's own value (another branch's connection) is never replaced; a non-member optional key stays absent; no argument is prefixed when an arity already matches; a member the world lacks refuses by name; a render request's and a custody request's connection (renamed) are never filled"
  (let [cp (scratch-call-preparation)
        prepare (cp 'prepare)
        live (db/carried-projection (wdb (world)))
        mine (merge (edn/read-string (slurp "/Users/sean/src/seon/resources/seon/schemas/seon.db.edn"))
                    (edn/read-string (slurp "/Users/sean/src/seon/resources/seon/schemas/seon.render.walk.edn")))
        registry (mr/composite-registry mine (:seon.schema.projection/registry live))
        w (world)
        conn (:seon.db/connection w)
        other (:seon.db/connection *exec*)
        blob-get [:=> [:cat :seon.db/connection :string] :any]
        indexed [:=> [:cat :seon.fn/index-request] :any]
        custody [:=> [:cat :seon.db/custody-request :int] :any]
        walk [:=> [:cat :seon.render.walk/continue-request] :any]
        run (fn [world contract args]
              (binding [seon.batch/*world* world]
                (prepare nil (:seon.env/environment world) (scratch-plan cp contract registry) args)))
        positional (run w blob-get ["d"])
        passed (run w blob-get [::other-connection "d"])
        map-filled (run w indexed [{:seon.fn/changed-paths #{"a"}}])
        map-kept (run w indexed [{:seon.db/connection other}])
        custody-args (run w custody [{} 1])
        walk-args (run w walk [{:seon.render.walk/continuation-node 'a/b :seon.render.data/cursor {}}])
        lacking (run (dissoc w :seon.db/connection) blob-get ["d"])]
    {:pass? (and (identical? conn (first positional)) (= "d" (second positional))
                 (= [::other-connection "d"] passed)
                 (identical? conn (:seon.db/connection (first map-filled)))
                 (not (contains? (first map-filled) :seon.fn/root))
                 (identical? other (:seon.db/connection (first map-kept)))
                 (= [{} 1] custody-args)
                 (not (contains? (first walk-args) :seon.db/connection))
                 (not (contains? (first walk-args) :seon.sci.eval/ctx))
                 (= :seon.db/connection (:seon.call-preparation/unavailable-key lacking))
                 (str/includes? (str (:seon.error/message lacking)) ":seon.db/connection"))
     :lacking (:seon.error/message lacking)
     :walk (keys (first walk-args)) :custody custody-args}))

(defproof :supply-per-world :supply
  "S3a: two concurrent test agents in two worlds each receive their own world's connection, and a body moved to another thread receives the world it carries (with-world), never the submitting thread's"
  (let [cp (scratch-call-preparation)
        prepare (cp 'prepare)
        registry (:seon.schema.projection/registry (db/carried-projection (wdb (world))))
        plan (scratch-plan cp [:=> [:cat :seon.db/connection :string] :any] registry)
        ea (acquire!) eb (acquire!)]
    (try
      (let [requester (:seon.db/connection *exec*)
            wa (world/test-agent-world ea requester {:seon.agent/id "root"})
            wb (world/test-agent-world eb requester {:seon.agent/id "root"})
            call (fn [] (first (prepare nil (:seon.env/environment seon.batch/*world*) plan ["d"])))
            [a b] [(future (world/with-world wa call)) (future (world/with-world wb call))]
            hop (binding [seon.batch/*world* wa] @(future (world/with-world wb call)))]
        {:pass? (and (identical? @a (:seon.db/connection wa)) (identical? @b (:seon.db/connection wb))
                     (not (identical? @a @b)) (identical? hop (:seon.db/connection wb)))})
      (finally (release! ea) (release! eb)))))

(defproof :supply-reads-the-working-value :supply
  "S3a: the world the fold binds around each evaluation holds the batch's working value (every earlier form's validated facts), the database a supplied :seon.db/database-value reads"
  (on-fresh-branch
   (fn []
     (let [seen (atom [])
           w (world)
           req (turn.batch/code-request (request w "(defn supply-probe [x] x)\n(+ 1 2)" {}))
           req (update req :seon.batch/evaluate
                       (fn [ev] (fn [r form working]
                                  (swap! seen conj [(identical? working (:seon.db/db seon.batch/*world*))
                                                    (stored? (:seon.db/db seon.batch/*world*) 'user/supply-probe)])
                                  (ev r form working))))
           b (batch/evaluate-batch req)]
       {:pass? (and (applied? b) (= [[true false] [true true]] @seen)) :seen @seen}))))

(defproof :blob-get-without-connection :supply
  "A digest is data; (seon.blob/get digest) with no connection returns the stored content through the one path"
  (on-fresh-branch
   (fn []
     (let [w (world)
           digest ((requiring-resolve 'seon.blob/put!) (:seon.db/connection w) "the whole value")
           b (run w (str "(seon.blob/get " (pr-str digest) ")"))]
       {:pass? (= ["the whole value"] (values b)) :values (values b) :digest digest}))))

;;; --- Astra rev-4 execution blockers (52b8cefc2) --------------------------------

(defproof :c1-malformed-map-observation :totality
  "C1: an observation that is a map but no write (a sorted map of other keys, whose own lookup throws) keeps the evaluated 42, with one observe diagnostic"
  (let [b (batch/evaluate-batch (assoc (tiny-request 42) :seon.batch/observe
                                       (fn [_ _ write! run] (run) (write! (sorted-map 1 :bad)))))]
    {:pass? (and (kept? b 42) (= 1 (count (diags b))) (str/starts-with? (first (diags b)) "observe:"))
     :diagnostics (diags b)}))

(defproof :c2-watch-rebinding-origin :sci-fork
  "C2 (needs the JVM on SCI ead49957): a watch's alter-var-root during (def watched 2) is a rebinding with no origin: the live value 3 is not stored as the def's source, the definition stays private"
  (on-fresh-branch
   (fn []
     (let [b (run (world) "(def watched 0)\n(add-watch #'watched :once (fn [_ v _ _] (remove-watch v :once) (alter-var-root v inc)))\n(def watched 2)\nwatched")
           source (:seon.fn/source (db/pull (after b) [:seon.fn/source] [:seon.fn/sym 'user/watched]))]
       {:pass? (and (= 3 (last (values b))) (not= "(def watched 2)" source)
                    (= :rebound (get (private-reasons b) 'user/watched)))
        :source source :private (private-reasons b)}))))

(defproof :c3-producer-spans :join
  "C3: the one reader reads with end locations: a literal definition's producer origin carries all four coordinates, and it joins its analyzed definition by that exact span (not a start position)"
  (on-fresh-branch
   (fn []
     (let [w (world)
           seen (atom [])
           req (update (turn.batch/code-request (request w "(def spanned 1)\n  (defn indented [x] x)" {}))
                       :seon.batch/observe (fn [obs] (fn [r working on-write thunk]
                                                       (obs r working #(do (swap! seen conj %) (on-write %)) thunk))))
           b (batch/evaluate-batch req)
           origins (into {} (keep (fn [w] (when (= :bind (:seon.batch/op w)) [(:seon.batch/sym w) (:seon.batch/origin w)]))) @seen)]
       {:pass? (and (applied? b) (stored? (after b) 'user/spanned) (stored? (after b) 'user/indented)
                    (= {:line 1 :column 1 :end-line 1 :end-column 16} (select-keys (origins 'user/spanned) [:line :column :end-line :end-column]))
                    (= {:line 2 :column 3 :end-line 2 :end-column 24} (select-keys (origins 'user/indented) [:line :column :end-line :end-column])))
        :origins origins}))))

(defproof :c3-generated-full-span :join
  "C3: a generated definition joins only when its owning macro call's COMPLETE span is the producer's; a call analyzed at [1 1 1 30] refuses an origin [1 1 1 12] expanded-by defn"
  (let [analysis {:seon.fn/analysis
                  {:seon.fn.analyzer/var-definitions
                   [{:seon.fn.analyzer/name 'f :seon.fn.analyzer/ns 'user :seon.fn.analyzer/defined-by 'clojure.core/defn
                     :seon.fn.analyzer/row 1 :seon.fn.analyzer/col 1 :seon.fn.analyzer/end-row 1 :seon.fn.analyzer/end-col 30}]
                   :seon.fn.analyzer/var-usages
                   [{:seon.fn.analyzer/name 'defn :seon.fn.analyzer/to 'clojure.core
                     :seon.fn.analyzer/row 1 :seon.fn.analyzer/col 1 :seon.fn.analyzer/end-row 1 :seon.fn.analyzer/end-col 30}]}}
        j (fn [end] (turn.batch/join-write analysis {:first-row 1 :line 1 :column 1}
                                           {:seon.batch/sym 'user/f :seon.batch/origin {:line 1 :column 1 :end-line 1 :end-column end :expanded-by 'defn}}))]
    {:pass? (and (= :unanalyzed (:seon.batch/join (j 12))) (= :matched (:seon.batch/join (j 30))))
     :short (j 12) :exact (:seon.batch/join (j 30))}))

(defproof :c3-hook-spans :join
  "C3: the observer emits each macro call's expansion with its complete span (SCI's :end-row/:end-col), moved to the form's own coordinates: a call on a later, indented line; the analyzer's hooks key by that complete span"
  (on-fresh-branch
   (fn []
     (let [w (world)
           seen (atom [])
           text "(defmacro pair [] (list 'do (list 'def 'pa 1) (list 'def 'pb 2)))\n(do\n    (pair))"
           req (update (turn.batch/code-request (request w text {}))
                       :seon.batch/observe (fn [obs] (fn [r working on-write thunk]
                                                       (obs r working #(do (swap! seen conj %) (on-write %)) thunk))))
           b (batch/evaluate-batch req)
           e (first (filter #(= 'user/pair (:seon.batch/macro %)) @seen))
           entry (second (:seon.batch/entries b))
           relative (#'turn.batch/form-relative-expansions entry (vec @seen))
           hooks (tree-expansion-hooks relative 7)]
       {:pass? (and (= [3 5 3 11] (:seon.batch/call-span e))
                    (= [2 5 2 11] (:seon.batch/call-span (first relative)))
                    (str/includes? (get-in hooks [:analyze-call 'user/pair]) "[8 5 8 11]"))
        :observed (:seon.batch/call-span e) :relative (mapv :seon.batch/call-span relative)}))))

(defproof :c3-generated-stored-and-reacquired :join
  "C3 (needs the JVM on the analyzer hooks, after the restart): a macro call on a later, indented line generating two definitions stores both as entities at the call, and reacquiring the stored sources in a fresh world reproduces them"
  (on-fresh-branch
   (fn []
     (let [text "(defmacro pair [] (list 'do (list 'def 'pa 1) (list 'def 'pb 2)))\n(do\n    (pair))"
           b (run (world) text)
           a (after b)
           srcs (mapv #(:seon.fn/source (db/pull a [:seon.fn/source] [:seon.fn/sym %])) '[user/pair user/pa user/pb])
           fresh (world)
           _ (batch/evaluate-batch (seon.sci.batch/acquire-request {:seon.batch/text (str/join "\n" (distinct srcs)) :seon.batch/world fresh}))
           got (values (batch/evaluate-batch (seon.sci.batch/acquire-request {:seon.batch/text "[pa pb]" :seon.batch/world fresh})))]
       {:pass? (and (applied? b) (every? some? srcs) (= [[1 2]] got))
        :sources srcs :private (private-reasons b) :reacquired got}))))

(defproof :c5-constructor-absence :world
  "C5: a world built without an agent holds no :seon.agent/id (never nil); a read-only world holds no call sink; both validate"
  (let [aw (world/agent-world *exec* {})
        jw (world/jvm-world "default" (:seon.db/connection *exec*) {})
        ro (world/read-only-world (world) (wdb (world)))]
    {:pass? (and (not (contains? aw :seon.agent/id)) (not (contains? jw :seon.agent/id))
                 (not (contains? ro :seon.batch/sink))
                 (every? #(empty? (:schema-errors (world-errors %))) [aw jw ro]))
     :errors (mapv #(:schema-errors (world-errors %)) [aw jw ro])}))

(defproof :c5-error-print-world :world
  "C5: a throwing evaluation's error is printed inside the evaluation's world (seon.batch/*world* bound at print), as a value is"
  (let [seen (atom [])
        w (world)
        req (-> (turn.batch/code-request (request w "(throw (ex-info \"x\" {}))\n42" {}))
                (update :seon.batch/show (fn [show] (fn [r e] (swap! seen conj (some? seon.batch/*world*)) (show r e)))))
        b (batch/evaluate-batch req)]
    {:pass? (and (= [true true] @seen) (= 42 (last (values b)))) :world-at-print @seen}))

(defproof :c5-jvm-custody :entrant
  "C5: JVM mode evaluates inside its world: an elided seon.db read reaches the world's branch, as in SCI"
  (let [b (batch/evaluate-batch (turn.batch/jvm-request (request (world) "(seon.db/pull [:seon.agent/id] [:seon.agent/id \"root\"])" {:seon.batch/start-ns 'one-path-proof.host})))]
    {:pass? (= [{:seon.agent/id "root"}] (values b)) :values (values b)}))

(defproof :c6-nested-world-sink :world
  "C6: the armed wrapper as it lands (seon.profile's with-cell and timed, loaded from its source into a scratch namespace) counts a call made in a nested world into that world's sink, keeps same-world re-entry uncounted, restores the outer entry, and counts a throwing call"
  (let [src (slurp "/Users/sean/src/seon/src/seon/profile.clj")
        scratch (-> src
                    (str/replace-first #"\(ns seon\.profile" "(ns one-path-proof.profile")
                    (str/replace "seon.profile/new-adder" "one-path-proof.profile/new-adder")
                    (str/replace "seon.profile/host-entry" "one-path-proof.profile/host-entry"))
        _ (load-string scratch)
        wa {:seon.batch/sink (batch/sink)} wb {:seon.batch/sink (batch/sink)}
        f (eval '(let [cell (one-path-proof.profile/cell {:seon.profile/sym (quote review/f)})]
                   (one-path-proof.profile/with-cell cell
                     (fn f [mode wb]
                       (one-path-proof.profile/timed
                        (case mode
                          :nested (do (binding [seon.batch/*world* wb] (f :inner wb)) (f :same wb))
                          :same :same
                          :inner :inner
                          :throw (throw (ex-info "boom" {}))))))))
        _ (binding [batch/*world* wa] (f :nested wb))
        _ (binding [batch/*world* wa] (try (f :throw wb) (catch Exception _ nil)))]
    {:pass? (and (= {'review/f 2} (batch/calls (:seon.batch/sink wa)))
                 (= {'review/f 1} (batch/calls (:seon.batch/sink wb))))
     :a (batch/calls (:seon.batch/sink wa)) :b (batch/calls (:seon.batch/sink wb))}))

(defproof :hop-plain-executor :world
  "A form that hops to a plain executor (no binding conveyance) and re-establishes only its world value (seon.turn.world/with-world) is observed: its definition is stored, from the world's observation scope, never an inherited binding"
  (on-fresh-branch
   (fn []
     (let [pool (java.util.concurrent.Executors/newFixedThreadPool 1)]
       (try
         (let [req (assoc (turn.batch/code-request (request (world) "(defn hopped [x] x)\n(hopped 7)" {}))
                          :seon.batch/evaluate
                          (fn [req form working]
                            (let [w seon.batch/*world*
                                  ^Callable task (fn [] (world/with-world w #(seon.sci.batch/evaluate req form working)))]
                              (.get (.submit pool task)))))
               b (batch/evaluate-batch req)]
           {:pass? (and (applied? b) (stored? (after b) 'user/hopped) (= 7 (last (values b))))
            :values (mapv str (values b))})
         (finally (.shutdown pool)))))))

;;; --- V1: the Var render (owner, rev 5) -------------------------------------------

(defn- var-render-load! []
  (load-file "/Users/sean/src/seon/src/seon/render/var.clj"))

(defn- run-tests!
  "The reaching tests run and recorded on the world's branch: a test agent's
  recording, until S12 makes the defn's batch schedule them."
  [w tests]
  (let [r ((requiring-resolve 'seon.test/run) {:seon.test/execution *exec* :seon.test/recording-connection (:seon.db/connection w)
                                               :seon.test/policy :named :seon.test/identities (set tests)})]
    (if (:seon.error/at r) r (mapv #(select-keys % [:seon.test/sym :seon.test/fail-count]) (:seon.test/results r)))))

(defproof :v1-uncontracted-admitted :render
  "V1: an uncontracted defn is admitted and stored through both entrants (MCP code and an agent turn), and its Var render shows the missing contract in the AI face (data for the value printer: its symbol, status and example form, nothing evaluated) and the HTML face (the same status as hiccup)"
  (var-render-load!)
  (let [go (fn [mode] (on-fresh-branch
                       #(let [b (run (world) "(defn v1-open [x] x)" (if (= mode :agent) {:mode :reply} {}))
                              a (after b)
                              v (first (values b))
                              unit {:seon.render/value v :seon.db/db a}]
                          {:stored? (stored? a 'user/v1-open) :errors (facts-errors b)
                           :status ((requiring-resolve 'seon.render.var/status) a 'user/v1-open)
                           :ai ((requiring-resolve 'seon.render.var/render-ai) unit)
                           :html ((requiring-resolve 'seon.render.var/render-html) unit)})))
        m (go :mcp) g (go :agent)
        ok? (fn [r] (and (:stored? r) (empty? (:errors r))
                         (= :missing (:seon.render.var/contract (:status r)))
                         ;; data for the value printer: nothing in it is evaluated
                         (= 'user/v1-open (:seon.render.var/symbol (:ai r)))
                         (= :missing (:seon.render.var/contract (:ai r)))
                         (= 'defn (first (:seon.render.var/example (:ai r))))
                         (str/includes? (pr-str (:html r)) "contract missing")))]
    {:pass? (and (ok? m) (ok? g) (= (dissoc m :html) (dissoc g :html))) :mcp (dissoc m :html) :html (:html m)}))

(defproof :v1-red-delta :render
  "V1: a contracted function whose reaching test passes at its digest says nothing; a change that turns the test red is pending, then named newly red once its result at the new digest exists; changing it back is named newly green"
  (var-render-load!)
  (on-fresh-branch
   (fn []
     (let [status (requiring-resolve 'seon.render.var/status)
           w (world) conn (:seon.db/connection w)
           _ (run w "(require '[clojure.test :refer [deftest is]])\n(defn v1-g {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(deftest v1-t (is (= 1 (v1-g 1))))")
           s0 (status (db/db conn) 'user/v1-g)
           r0 (run-tests! w '[user/v1-t])
           s1 (status (db/db conn) 'user/v1-g)
           _ (run (world) "(defn v1-g {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
           s2 (status (db/db conn) 'user/v1-g)
           r2 (run-tests! w '[user/v1-t])
           s3 (status (db/db conn) 'user/v1-g)
           _ (run (world) "(defn v1-g {:malli/schema [:=> [:cat :int] :int]} [x] x)")
           _ (run-tests! w '[user/v1-t])
           s4 (status (db/db conn) 'user/v1-g)]
       {:pass? (and (= {:seon.render.var/pending '[user/v1-t]} s0) (= {} s1)
                    (= {:seon.render.var/pending '[user/v1-t]} s2)
                    (= {:seon.render.var/newly-red '[user/v1-t]} s3)
                    (= {:seon.render.var/newly-green '[user/v1-t]} s4))
        :statuses [s0 s1 s2 s3 s4] :runs [r0 r2]}))))

(defproof :v1-pending-refreshes :render
  "V1: the Var render's read of the test results is ordinary read evidence: when the results are recorded, that evidence is no longer current, so the changed-read refresh re-renders it into the next context"
  (var-render-load!)
  (on-fresh-branch
   (fn []
     (let [status (requiring-resolve 'seon.render.var/status)
           w (world) conn (:seon.db/connection w)
           _ (run w "(require '[clojure.test :refer [deftest is]])\n(defn v1-p {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(deftest v1-pt (is (= 1 (v1-p 1))))")
           before (db/db conn)
           sink (atom [])
           pending (binding [db/*read-evidence-sink* sink] (status before 'user/v1-p))
           evidence (db/read-evidence @sink)
           current-before (db/read-evidence-current? before evidence)
           _ (run-tests! w '[user/v1-pt])
           current-after (db/read-evidence-current? (db/db conn) evidence)]
       {:pass? (and (= {:seon.render.var/pending '[user/v1-pt]} pending) (seq evidence)
                    (true? current-before) (false? current-after)
                    (= {} (status (db/db conn) 'user/v1-p)))
        :evidence (count evidence) :current [current-before current-after]}))))

(defproof :r7-ai-data-face-prints-as-data :render
  "rev 6: an AI face that returns data is printed by the value printer as data (its keys shown, the value's own keys not), and a :seon.render/ai inside that data is never called (no nested re-dispatch). Green once render-selection lands render-ai-data-face.patch"
  (let [b (run (world) (str "{:x 1 :seon.render/ai (fn [_] {:outer 1"
                            " :nested {:seon.render/ai (fn [_] (throw (ex-info \"nested render called\" {})))}})}"))
        text (first (shown b))]
    {:pass? (and (string? text) (str/includes? text ":outer 1")
                 (not (str/includes? text "nested render called"))
                 (not (str/includes? text ":x 1")))
     :shown text}))

(defproof :p1-opening-calls-supplied :supply
  "P1: the opening's calls that elide their world, (help), (seon.plan/plan {}) and (seon.agent/settings), are supplied their database and agent by declared schema name through an agent's evaluation: no arity or missing-database refusal"
  (on-fresh-branch
   #(let [b (run (world) "(help)\n(seon.plan/plan {})\n(seon.agent/settings)" {:mode :reply})
          shown (shown b)]
      {:pass? (and (= 3 (count shown)) (not-any? (fn [t] (re-find #"refused (argument|:request)" (str t))) shown)
                   (every? (fn [e] (not (contains? e :seon.batch/error))) (:seon.batch/entries b)))
       :shown (mapv (fn [t] (subs (str t) 0 (min 120 (count (str t))))) shown)})))

(defn- tree-fork-for-turn
  "seon.sci.eval/fork-for-turn and its handle binding as the working tree has
  them, evaluated in a scratch namespace over the installed privates (no live
  Var redefined)."
  []
  (let [src (slurp "/Users/sean/src/seon/src/seon/sci/eval.clj")
        form-at (fn [start] (-> (subs src (str/index-of src start))
                                (str/replace "::turn-fork?" ":seon.sci.eval/turn-fork?")
                                (str/replace "::base-bindings" ":seon.sci.eval/base-bindings")
                                (str/replace "::result-objects" ":seon.sci.eval/result-objects")
                                (str/replace "::kernel/installed-functions" ":seon.sci.kernel/installed-functions")
                                (str/replace "::kernel/program-snapshot" ":seon.sci.kernel/program-snapshot")
                                (str/replace "::print-session" ":seon.sci.eval/print-session")
                                (str/replace "::bind-result!" ":seon.sci.eval/bind-result!")
                                (str/replace "::custody" ":seon.sci.eval/custody")
                                (str/replace "::base-ctx" ":seon.sci.eval/base-ctx")
                                (str/replace "::unavailable-execution" ":seon.sci.eval/unavailable-execution")
                                read-string))
        forms (mapv form-at ["(defn- settled-results" "(declare bind-handles!)" "(def ^:private unstored-notes"
                             "(defn- bind-settled-results!" "(defn- bind-handles!" "(defn fork-for-turn"])]
    (binding [*ns* (create-ns 'one-path-proof.fork)]
      (refer-clojure)
      (require '[sci.core :as sci] '[seon.sci.admit :as admit] '[seon.render.value :as render.value]
               '[seon.db :as db] '[seon.blob :as blob] '[seon.env :as env] '[seon.error.refusal] '[seon.sci.kernel :as kernel])
      (doseq [p '[regenerate-agent-context! base-bindings session-print-options projection-state context-projection
                  advance-context-projection! agent-namespace bind-result!]]
        (eval (list 'def p (list 'deref (list 'var (symbol "seon.sci.eval" (name p)))))))
      (run! eval forms))
    @(ns-resolve 'one-path-proof.fork 'fork-for-turn)))

(defproof :fork-unbound-results-surface :totality
  "A fresh fork whose result handles cannot be bound says so as data (:seon.sci.eval/unbound-results), for its entrant to record as a fault; the fork itself stands"
  (let [fork-for-turn (tree-fork-for-turn)
        d (wdb (world))
        ctx (eval/build-base-ctx (db/carried-projection d))
        r (fork-for-turn {:seon.sci.eval/ctx ctx :seon.db/db d :seon.agent/id "root"})]
    {:pass? (and (some? (:seon.sci.eval/ctx r)) (string? (get-in r [:seon.sci.eval/unbound-results :seon.error/message])))
     :unbound (select-keys (:seon.sci.eval/unbound-results r) [:seon.error/message :seon.error/operation])}))

(defproof :refusal-builds-for-data-against-a-union :totality
  "A refusal always builds: a data value returned against a union output (render-call's shape) is refused with its problems, never the minimal 'could not be built' fallback (a map entry in the evidence broke seon.error.refusal/referenced)"
  (let [d (wdb (world))
        proj (db/carried-projection d)
        caps (:seon.sci.admit/caps (#'eval/instrumentation-config d))
        schema [:=> [:cat :any] [:or :nil :string :seon.render/hiccup :seon.render/error-result]]
        w (#'seon.instrument/compiled-wrapper proj 'user/probe-union schema
            (fn [_] {:seon.agent/id "root" :seon.agent/namespace {:seon.ns/name 'my.agents.root}}) caps)
        r (try (w 1) nil (catch clojure.lang.ExceptionInfo t (ex-data t)))]
    {:pass? (and (map? r) (seq (:seon.error/problems r))
                 (not (str/includes? (str (:seon.error/message r)) "could not be built")))
     :message (:seon.error/message r) :problems (count (:seon.error/problems r))}))

(defproof :p1-system-turn-records-data-units :parity
  "P1: a written system turn on a disposable branch records the opening's data units (each printed as data, never evaluated) and answers them in its forms; a recording its contract refuses answers a refusal value, never a throw"
  (on-fresh-branch
   (fn []
     (let [instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")
           handle (merge (:seon.turn.loop/cluster instance) (select-keys *exec* [:seon.db/connection :seon.sci.eval/ctx]))
           r (try (seon.turn/system-turn {:seon.turn.loop/cluster handle :seon.agent/id "root" :seon.turn/write? true})
                  (catch Throwable t {:threw (ex-message t)}))
           rendered (filter :seon.turn/rendered (:seon.turn/forms r))]
       {:pass? (and (not (:threw r)) (not (:seon.error/message r)) (some? (:seon.turn/id r))
                    (seq rendered) (every? :seon.turn/text rendered))
        :turn (:seon.turn/id r) :rendered (count rendered)
        :failure (or (:threw r) (:seon.error/message r))}))))

(defproof :union-refusal-leads-with-closest :totality
  "A union's refusal leads with its closest alternative (the branch the value's type matched, then the fewest problems), carries only that branch's problems, and counts the others (:seon.instrument/other-alternatives)"
  (let [d (wdb (world))
        proj (db/carried-projection d)
        caps (:seon.sci.admit/caps (#'eval/instrumentation-config d))
        w (#'seon.instrument/compiled-wrapper proj 'user/probe-closest
            [:=> [:cat :any] [:or :nil :string [:map [:a :int] [:b :int]]]]
            (fn [_] {:a 1}) caps)
        r (try (w 1) nil (catch clojure.lang.ExceptionInfo t (ex-data t)))
        problems (:seon.error/problems r)]
    {:pass? (and (= 1 (count problems)) (= 2 (:seon.instrument/other-alternatives r))
                 (str/includes? (str (:seon.error/message r)) ":b"))
     :message (:seon.error/message r) :problems (count problems) :others (:seon.instrument/other-alternatives r)}))

(def landing-files
  "The six namespaces and the schema resource step S3 copies into the tree."
  ["src/seon/batch.clj" "src/seon/sci/forgive.clj" "src/seon/sci/batch.clj" "src/seon/sci/arm.clj"
   "src/seon/sci/observe.clj" "src/seon/turn/world.clj" "src/seon/turn/batch.clj"
   "resources/seon/schemas/seon.batch.edn"])

(defn gate-root!
  "A disposable root: `git archive HEAD` with the landing files copied in."
  []
  (let [root (str (System/getProperty "java.io.tmpdir") "one-path-gate-" (System/nanoTime))
        sh (fn [& args] (let [p (.start (doto (ProcessBuilder. ^java.util.List (vec args)) (.redirectErrorStream true)))]
                          (when-not (zero? (.waitFor p)) (throw (ex-info (slurp (.getInputStream p)) {:args args})))))]
    (sh "sh" "-c" (str "mkdir -p " root " && cd /Users/sean/src/seon && git archive HEAD | tar -x -C " root))
    (doseq [f landing-files]
      (io/make-parents (io/file root f))
      (io/copy (io/file "/Users/sean/src/seon" f) (io/file root f)))
    root))

(defproof :b5-contracts-gate :landing
  "B5: the installed landing gate seon.contracts-compile-test/check, over git archive HEAD plus the six namespaces and the schema, with empty baselines for the new files, finds nothing"
  (let [root (gate-root!)
        paths (mapv #(str root "/" %) (butlast landing-files))
        check (requiring-resolve 'seon.contracts-compile-test/check)
        t0 (System/nanoTime)
        r (try (check {:seon.contracts-compile-test/root root :seon.contracts-compile-test/paths paths
                       :seon.contracts-compile-test/baselines (zipmap paths (repeat ""))})
               (finally (sh/sh "rm" "-rf" root)))]
    {:pass? (empty? r) :ms (ms-since t0) :findings (count r)
     :each (mapv (fn [f] (into {} (keep (fn [[k v]] (when-not (= "chain" (name k)) [(keyword (name k)) (if (string? v) (subs v 0 (min 160 (count v))) v)]))) f)) r)}))

;;; --- Astra revision-2 counterexamples ---------------------------------------

(defn- with-read-stage [req read-next] (assoc req :seon.batch/read-next read-next))

(defproof :v2-3-span-invalid :totality
  "V2-3: a read stage answering a span outside its text: the evaluated 42 is kept, one diagnostic"
  (let [w (world)
        req (with-read-stage (turn.batch/code-request (request w "42" {}))
              (fn [_ {v :seon.batch/version}]
                (if (zero? (:seon.batch/pos v))
                  {:seon.batch/form 42 :seon.batch/start 0 :seon.batch/end 999
                   :seon.batch/version (assoc v :seon.batch/pos 2) :seon.batch/repairs []}
                  {:seon.batch/eof true :seon.batch/version v :seon.batch/repairs []})))
        b (batch/evaluate-batch req)]
    {:pass? (and (= [42] (values b)) (= 1 (count (diags b)))) :diagnostics (diags b)}))

(defproof :v2-3-observer-fails :totality
  "V2-3: the observer failing after the form returned keeps the value, with a diagnostic"
  (let [w (world)
        req (assoc (turn.batch/code-request (request w "42" {}))
                   :seon.batch/observe (fn [r working on-write thunk]
                                         (observe/observe r working on-write thunk)
                                         (throw (ex-info "stop failed" {}))))
        b (batch/evaluate-batch req)]
    {:pass? (and (= [42] (values b)) (some #(str/includes? % "stop failed") (diags b)) true)
     :diagnostics (diags b)}))

(defproof :v2-3-show-fails :totality
  "V2-3: a value whose print throws: every entry is kept and shown; the reply is present"
  (let [b (run (world) "(lazy-seq (throw (ex-info \"print failed\" {})))\n42")]
    {:pass? (and (= 2 (count (:seon.batch/entries b))) (= "42" (second (shown b))) (string? (first (shown b))))
     :shown (shown b) :diagnostics (diags b)}))

(defproof :v2-3-ns-name-shadow :totality
  "R3/V2-3: shadowing ns-name in the batch cannot lose the prefix: 42, the Var, 43"
  (let [b (run (world) "42\n(def ns-name (fn [_] (throw (ex-info \"ns-after failed\" {}))))\n43")]
    {:pass? (and (= 42 (first (values b))) (= 43 (last (values b))) (= 3 (count (values b))))
     :values (mapv str (values b))}))

(defproof :v2-3-later-stages-fail :totality
  "V2-3: settle, conclude, show and fault stages that throw each add a diagnostic; every value is kept"
  (let [w (world)
        boom (fn [& _] (throw (ex-info "stage failed" {})))
        req (assoc (turn.batch/code-request (request w "(defn lsf [x] x)\n(lsf 3)" {}))
                   :seon.batch/settle boom :seon.batch/conclude boom :seon.batch/show boom :seon.batch/fault boom)
        b (batch/evaluate-batch req)]
    {:pass? (and (= 3 (last (values b))) (<= 4 (count (diags b)))) :diagnostics (diags b)}))

(defproof :v2-4-schema-reflush :schema
  "V2-4: a key registered, read, registered again differently, read: each read sees the version before it"
  (let [b (run (world) "(seon.schema/register! :review.v2/n :int)\n(:seon.schema/form (seon.db/pull [:seon.schema/form] [:seon.schema/key :review.v2/n]))\n(seon.schema/register! :review.v2/n [:int {:min 3}])\n(:seon.schema/form (seon.db/pull [:seon.schema/form] [:seon.schema/key :review.v2/n]))")]
    {:pass? (and (= [":int" "[:int {:min 3}]"] [(nth (values b) 1) (nth (values b) 3)]) (applied? b))
     :values (mapv str (values b)) :timings (:seon.batch/timings b)}))

(defproof :v2-4-register-then-use :schema
  "V2-4/h16: a contract naming a key registered earlier in the batch arms, is gated and is stored; its attribute is installed before the next form"
  (let [b (run (world) "(seon.schema/register! :op3.h16/n [:int {:min 0}])\n(defn h16f {:malli/schema [:=> [:cat :op3.h16/n] :op3.h16/n]} [x] x)\n(h16f 4)\n(h16f -1)")
        a (after b)]
    {:pass? (and (= 4 (nth (values b) 2)) (vector? (nth (values b) 3)) (applied? b) (stored? a 'user/h16f))
     :values (mapv str (values b)) :facts-errors (facts-errors b)}))

(defproof :v2-5-remove-rebind :effects
  "V2-5: def, unmap, def again of one name folds in execution order: the second definition is stored"
  (let [b (run (world) "(do (def rr-v2 1) (ns-unmap *ns* 'rr-v2) (def rr-v2 2))\nrr-v2")
        a (after b)]
    {:pass? (and (= 2 (last (values b))) (applied? b) (stored? a 'user/rr-v2)
                 (= "(def rr-v2 2)" (:seon.fn/source (db/pull a [:seon.fn/source] [:seon.fn/sym 'user/rr-v2]))))
     :source (:seon.fn/source (db/pull a [:seon.fn/source] [:seon.fn/sym 'user/rr-v2]))}))

(defproof :v2-5-remove-ns :effects
  "V2-5: a namespace created, defined in and removed in one batch leaves no row and no program namespace facts (its name stays only as where two recorded evaluations ran)"
  (let [b (run (world) "(ns gone.v2)\n(def x 1)\n(in-ns 'user)\n(remove-ns 'gone.v2)")
        a (after b)
        ns-facts (when a (db/pull a [:seon.ns/source :seon.ns/aliases] [:seon.ns/name 'gone.v2]))]
    {:pass? (and (applied? b) (not (stored? a 'gone.v2/x)) (empty? (dissoc ns-facts :db/id)))
     :namespace-facts ns-facts :commit (commit-of b)}))

(defproof :r4-new-then-removed :effects
  "R4: a definition removed in the same batch proposes nothing; a stored definition removed is retracted"
  (let [b (run (world) "(defn rr1 [x] x)\n(ns-unmap *ns* 'rr1)")
        a0 (after (run (world) "(defn rr2 [x] x)"))
        b2 (run (world) "(ns-unmap *ns* 'rr2)")]
    {:pass? (and (applied? b) (not (stored? (after b) 'user/rr1)) (stored? a0 'user/rr2) (not (stored? (after b2) 'user/rr2)))}))

(defproof :v2-5-read-only-evidence :effects
  "V2-5: an evaluation is read-only only with no observed write and no schema change: unmap-only and register-only forms are writes"
  (let [b (run (world) "(def ro1 1)\n(ns-unmap *ns* 'ro1)\n(seon.schema/register! :ro.v2/k :int)\n(+ 1 2)")]
    {:pass? (= [false false false true] (mapv #(turn.batch/read-only? b %) (:seon.batch/entries b)))
     :read-only (mapv #(turn.batch/read-only? b %) (:seon.batch/entries b))}))

(defproof :v2-6-tag-once :input
  "V2-6: accepting a structured suffix after prose invokes its tag reader once"
  (let [calls (atom 0)
        ctx (wctx (world))
        opts (assoc (sci.batch/parse-options ctx) :readers {'review/tag (fn [x] (swap! calls inc) x)})
        r (forgive/forms ctx opts :reply "Answer: [#review/tag 1]")]
    {:pass? (and (= 1 @calls) (= ["[#review/tag 1]"] (:forms r))) :reader-calls @calls}))

(defproof :v2-6-repair-before-require :input
  "V2-6: a missing close in a require is repaired without resolving the alias ahead of it; the alias then resolves live"
  (let [b (run (world) "(require '[clojure.string :as rv2s]\n(+ 1 2)\n::rv2s/k" {:mode :reply})]
    {:pass? (and (= [nil 3 :clojure.string/k] (values b)) (= [:delimiters] (mapv :seon.batch/repair-kind (:seon.batch/repairs b))))
     :values (values b)}))

(defproof :r2-rebound-intern :observer
  "R2 (intern arming live): an intern over a contracted defn is observed as a rebinding: armed at once, never persisted as the defn's source"
  (let [b (run (world) "(defn review-rebound {:malli/schema [:=> [:cat :int] :int]} [x] 1)\n(intern 'user 'review-rebound (fn [x] 2))\n(review-rebound \"bad\")\n(review-rebound 5)")
        a (after b)]
    {:pass? (and (vector? (nth (values b) 2)) (= 2 (nth (values b) 3)) (not (stored? a 'user/review-rebound))
                 (= :rebound (get (private-reasons b) 'user/review-rebound)))
     :values (mapv str (values b)) :private (private-reasons b)}))

(defproof :r5-namespace-facts :effects
  "R5: a namespace with an alias and a function using it commits in one transaction"
  (let [b (run (world) "(ns review.alias (:require [clojure.string :as a]))\n(defn f {:malli/schema [:=> [:cat :string] :string]} [x] (a/upper-case x))\n(f \"ok\")")
        a (after b)]
    {:pass? (and (= "OK" (last (values b))) (applied? b) (stored? a 'review.alias/f) (empty? (facts-errors b)))
     :facts-errors (facts-errors b)}))

(defproof :captured-locals :observer
  "V2-2: a definition reading a local of its enclosing form, even one shadowing a global, stays private (captures-locals)"
  (let [b (run (world) "(def capture-k 10)\n(let [capture-k 1] (defn capture-f [] capture-k))\n(capture-f)")
        a (after b)]
    {:pass? (and (= 1 (last (values b))) (stored? a 'user/capture-k) (not (stored? a 'user/capture-f))
                 (= :captures-locals (get (private-reasons b) 'user/capture-f)))
     :private (private-reasons b)}))

(defproof :record-acquires-once :observer
  "V2-2: a record's entities acquire into a fresh context by evaluating its declaration once; the constructor works"
  (let [fresh (world)                ; acquired before the record existed
        b (run (world) "(defrecord OpR [a])\n(:a (->OpR 1))")
        a (after b)
        srcs (distinct (keep #(:seon.fn/source (db/pull a [:seon.fn/source] [:seon.fn/sym %])) '[user/->OpR user/map->OpR]))
        acq (batch/evaluate-batch (sci.batch/acquire-request
                                   {:seon.batch/text (str/join "\n" srcs) :seon.batch/world fresh}))
        got (values (batch/evaluate-batch (sci.batch/acquire-request
                                           {:seon.batch/text "(:a (->OpR 7))" :seon.batch/world fresh})))]
    {:pass? (and (= 1 (last (values b))) (= 1 (count srcs)) (= [7] got) (empty? (diags acq)))
     :sources srcs :acquired got}))

(defproof :r8-render-read-only :entrant
  "R8 (render/context-assembly entrant): a render batch's def is discarded with its fork and its write refuses (no connection); a shared atom it mutates is the stated limit"
  (let [w (world)
        _ (sci/eval-string* (wctx w) "(def shared (atom 0))")
        b (batch/evaluate-batch (turn.batch/render-request
                                 (request (world/read-only-world w (wdb w))
                                          "(def leaked 1)\n(seon.db/transact! [{:db/id \"x\" :seon.ns/name 'render.leak}])\n(swap! shared inc)" {})))]
    {:pass? (and (nil? (sci/resolve (wctx w) 'user/leaked))
                 (= 'seon.db/missing-connection-error (:seon.error/operation (second (values b))))
                 (= 1 (last (values b))))
     :values (mapv str (values b)) :viewer-shared (sci/eval-string* (wctx w) "@shared")}))

(defproof :v2-8-nested-render-one-tx :entrant
  "V2-8: a form that renders (a nested read-only batch) contributes to its run's one transaction: one commit for the batch"
  (let [w (world)
        commits (atom 0)
        render! (fn [text] (shown (batch/evaluate-batch (turn.batch/render-request (request (world/read-only-world w (wdb w)) text {})))))
        _ (sci/intern (wctx w) (sci/find-ns (wctx w) 'user) 'render! render!)
        b (run w "(defn nr [x] x)\n(render! \"(+ 1 2)\")" {:writer (fn [conn tx] (swap! commits inc) (db/transact! conn tx))})]
    {:pass? (and (= ["3"] (second (values b))) (= 1 @commits) (applied? b)) :commits @commits :values (mapv str (values b))}))

(defproof :v2-8-jvm-recorded :entrant
  "V2-8 (MCP JVM entrant): host reader and eval, several forms with an alias, an error kept beside later values, the run recorded in one transaction"
  (let [conn (:seon.db/connection *exec*)
        commits (atom 0)
        w (world/jvm-world "default" conn {:seon.agent/id "root"
                                               :seon.db/writer (fn [c tx] (swap! commits inc) (db/transact! c tx))})
        b (batch/evaluate-batch
           (turn.batch/jvm-request (request w "(ns op3.jvm (:require [clojure.string :as s]))\n(s/upper-case \"a\")\n(/ 1 0)\n(+ 1 2)" {})))
        a (after b)]
    {:pass? (and (= [nil "A"] (take 2 (values b))) (vector? (nth (values b) 2)) (= 3 (last (values b)))
                 (applied? b) (= 1 @commits))
     :values (mapv str (values b)) :commit (commit-of b)}))

(defn- test-agent-run
  "One test agent: an ordinary agent execution on its own branch (the agent
  entrance, isolated), its world forked there, its body forms through the one
  path. Its run is recorded on the requesting agent's branch (`requester`, a
  connection), where that agent's feedback reads it."
  [requester body]
  (let [exec (acquire!)]
    (try
      (binding [*exec* exec]
        (let [w (without-native-guard
                 (world/test-agent-world exec requester {:seon.agent/id "root" :seon.sci.eval/time-limit-ms 5000}))
              req (request w body {})
              b (batch/evaluate-batch (turn.batch/code-request req))
              recorded (count (d/q '[:find [?e ...] :in $ ?id :where [?r :seon.turn/id ?id] [?e :seon.cluster.eval/run ?r]]
                                   (db/db requester) (get-in req [:seon.batch/world :seon.turn/id])))]
          {:values (values b) :recorded recorded :applied? (applied? b) :branch (str (:seon.agent/branch exec))}))
      (finally (release! exec)))))

(defproof :test-agent :entrant
  "Test entrant = a test agent: an ordinary agent on its own branch whose body forms (definitions, fixtures, assertions) go through read, evaluate, observe, one transaction; its evaluations are its test evidence, recorded by the same settle stage through the requesting agent's branch writer (where that agent's feedback reads them). No test-side runner or recorder"
  (let [r (test-agent-run (:seon.db/connection *exec*) "(defn op3-add [a b] (+ a b))\n(= 3 (op3-add 1 2))\n(= 4 (op3-add 1 2))")]
    {:pass? (and (= [true false] (vec (rest (:values r)))) (:applied? r) (= 3 (:recorded r)))
     :values (mapv str (:values r)) :recorded (:recorded r)}))

(defproof :test-agents-concurrent :entrant
  "Two test agents run at the same time in this JVM, each on its own branch and world: both record their runs on the requester's branch, neither sees the other's definitions"
  (let [requester (:seon.db/connection *exec*)
        a (future (test-agent-run requester "(def op3-which :a)\n(= :a op3-which)"))
        b (future (test-agent-run requester "(def op3-which :b)\n(= :b op3-which)"))
        [ra rb] [@a @b]]
    {:pass? (and (:applied? ra) (:applied? rb) (= true (last (:values ra))) (= true (last (:values rb)))
                 (not= (:branch ra) (:branch rb)))
     :branches [(:branch ra) (:branch rb)]}))

(defproof :entrant-mcp-sci-recorded :entrant
  "MCP SCI entrant: a code batch commits its run (turn + evaluations) and program in one transaction through the recording writer"
  (let [w (world)
        req (request w "(defn mcp1 [x] x)\n(mcp1 1)" {})
        b (batch/evaluate-batch (turn.batch/code-request req))
        a (after b)
        evals (d/q '[:find [?e ...] :in $ ?id :where [?r :seon.turn/id ?id] [?e :seon.cluster.eval/run ?r]] a (get-in req [:seon.batch/world :seon.turn/id]))]
    {:pass? (and (applied? b) (stored? a 'user/mcp1) (= 2 (count evals)))
     :evaluations (count evals) :commit (commit-of b)}))

(defproof :entrant-merge-write-back :entrant
  "Merge/write-back entrant: neither evaluates (merge moves entities by identity through my.program, unchanged by this cut). A committed row re-analyzed from its stored source, write-back's reindex, derives the same definition digest"
  (let [w (world)
        b (run w "(defn wb1 {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n(defn wb2 [y] (wb1 y))")
        a (after b)
        stored (fn [database s] (db/pull database [:seon.fn/source :seon.program/definition-digest] [:seon.fn/sym s]))
        reindexed (for [s '[wb1 wb2]
                        :let [{src :seon.fn/source dg :seon.program/definition-digest} (stored a (symbol "user" (str s)))
                              {:keys [entities]} (#'turn.batch/analyze-form (wctx w) a 'user src '[user/wb1 user/wb2] [])]]
                    (= dg (:seon.program/definition-digest (first (vals entities)))))
        ]
    {:pass? (and (applied? b) (= [true true] (vec reindexed)))
     :reindexed (vec reindexed)}))

;;; --- Static proofs: the cut's names and requires --------------------------------

(defn- new-contracts []
  (for [n '[seon.batch seon.sci.batch seon.sci.arm seon.sci.forgive seon.sci.observe seon.turn.world seon.turn.batch]
        [s v] (ns-interns n) :let [c (:malli/schema (meta v))] :when c]
    [(symbol (str n) (str s)) c]))

(defproof :v2-7-schema-names :static
  "V2-7: every :seon.batch name is this landing's own (no other owner's key in the live registry is redefined: a live key outside the seon.batch namespace is taken); every form and every contract compiles against the live registry plus the tree's forms (an undeclared reference fails to compile)"
  (let [forms (edn/read-string (slurp "/Users/sean/src/seon/resources/seon/schemas/seon.batch.edn"))
        proj (db/carried-projection (wdb (world)))
        taken (filter #(and (contains? (:seon.schema.projection/forms proj) %) (not= "seon.batch" (namespace %))) (keys forms))
        ;; :seon.db/writer is declared at HEAD (6eadf4b62) but not in this JVM's older registry
        head-db-forms (edn/read-string (:out (clojure.java.shell/sh "git" "-C" "/Users/sean/src/seon" "show" "HEAD:resources/seon/schemas/seon.db.edn")))
        registry (mr/composite-registry forms (select-keys head-db-forms [:seon.db/writer]) (:seon.schema.projection/registry proj))
        compile (fn [[s c]] (try (m/schema c {:registry registry}) nil (catch Throwable t [s (ex-message t) (:data (ex-data t))])))
        form-failures (keep compile forms)
        contract-failures (keep compile (new-contracts))]
    {:pass? (and (empty? taken) (empty? form-failures) (empty? contract-failures))
     :declared (count forms) :contracts (count (new-contracts)) :taken (vec taken)
     :form-failures (vec (take 5 form-failures)) :contract-failures (vec (take 5 contract-failures))}))

(defn- require-graph
  "Namespace -> required namespaces, from clj-kondo's analysis of `paths`."
  [paths]
  (let [{{:keys [namespace-usages]} :analysis}
        (clj-kondo.core/run! {:lint paths :cache false :config {:output {:format :none} :analysis true}})]
    (reduce (fn [g {:keys [from to]}] (update g from (fnil conj #{}) to)) {} namespace-usages)))

(defn- cycles-through
  "Cycles of `graph` through any namespace of `roots`."
  [graph roots]
  (for [r roots
        :let [reach (loop [seen #{} frontier (get graph r)]
                      (if (empty? frontier) seen
                          (let [n (first frontier)]
                            (if (seen n) (recur seen (disj frontier n))
                                (recur (conj seen n) (into (disj frontier n) (get graph n)))))))]
        :when (contains? reach r)]
    r))

(defproof :v2-7-no-reverse-requires :static
  "V2-7: with the landing's new edges (seon.turn -> seon.turn.batch; seon.sci.eval -> seon.batch, seon.sci.batch, seon.sci.forgive), the require graph of src + the new namespaces has no cycle through them"
  (let [graph (merge-with into
                          (require-graph ["/Users/sean/src/seon/src"])
                          '{seon.turn #{seon.turn.batch} seon.sci.eval #{seon.batch seon.sci.batch seon.sci.forgive}})
        cyc (vec (cycles-through graph '[seon.batch seon.sci.batch seon.sci.arm seon.sci.forgive seon.turn.batch seon.turn seon.sci.eval]))]
    {:pass? (empty? cyc) :cycles cyc}))

;;; --- The corpus -------------------------------------------------------------

(defn- plain-outcome [r]
  (cond (contains? r :value) [:value (:value r)] (:error r) [:error (:error r)] :else [:unread]))

(defn- our-outcome [show e]
  (if (contains? e :seon.batch/value) [:value (show (:seon.batch/value e))] [:error (ex-message (:seon.batch/error e))]))

(def designed-differences
  "Items whose outcome differs from plain SCI by design, with the reason."
  {"h14-register-top" "Seon supplies seon.schema/register! (plain SCI has no seon.schema)"
   "h15-register-nested" "Seon supplies seon.schema/register!"
   "h16-register-then-use" "Seon supplies seon.schema/register!"
   "h20-unbalanced" "the forgiving input stage repairs the unbalanced form (Q2); plain SCI stops reading"
   "h25-deftest" "Seon supplies clojure.test"
   "h27-contract-refusal" "the contract refuses the bad argument by design (armed at the def)"
   "h30-require-import" "Seon supplies java.util.UUID to import"
   "h36-register-22" "Seon supplies seon.schema/register!"
   "h37-register-22-in-do" "Seon supplies seon.schema/register!"
   "h13-program-ns-unmap" "plain SCI has no my.program; at HEAD my.program/ns-unmap! answers its refusal for a name no stored entity holds. S9 makes it SCI's unmap, observed (proofs v2-5-remove-rebind and r4-new-then-removed prove the removal)"})

(def designed-facts-errors
  "Items one of whose forms' facts are refused by design, with the reason."
  {"h32-multi-arity" "its contract admits every int while its :pre refuses non-positive ones: the install gate's seeded check refuses it (today's path refuses it too); the value is kept"})

(defn- object-free [x] (clojure.walk/postwalk #(if (string? %) (str/replace % #"(0x|@)[0-9a-f]+" "$1_") %) x))

(defn corpus-item [{:keys [id source]}]
  (let [show (ns-resolve 'runner 'show)
        plain (mapv plain-outcome ((ns-resolve 'runner 'sci-run) source))
        ;; each item is its own program on its own fresh branch
        [b ms] (on-fresh-branch #(let [w (world) t0 (System/nanoTime) b (run w source)] [b (ms-since t0)]))
        ours (mapv #(our-outcome @show %) (:seon.batch/entries b))
        unread (:seon.batch/unread b)]
    {:id id :ms ms
     :same? (= (object-free (remove #(= [:unread] %) plain)) (object-free ours))
     :plain plain :ours ours :unread unread
     :applied? (or (nil? (:seon.batch/commit b)) (applied? b))
     :facts-errors (facts-errors b)
     :fold-diagnostics (vec (keep #(when (#{:fold :place :derive :read :around :settle} (:seon.batch/stage %)) (:seon.error/message %))
                                  (:seon.batch/diagnostics b)))
     :private (private-reasons b)
     :entities (count (get-in b [:seon.batch/program :entities]))}))

(defn corpus! []
  (load-file "/Users/sean/src/seon/tmp/valid-clojure-corpus/runner.clj")
  (let [items (or (ns-resolve 'runner 'corpus)
                  (throw (ex-info "runner/corpus is not defined after loading the corpus runner" {})))]
    (mapv corpus-item (items))))

(def ^:private corpus-result (atom nil))

(defproof :corpus :corpus
  "The 94-item valid-Clojure corpus: every value or error equals plain SCI's or is a designed difference; every commit applies; no fold, read, derive or settle diagnostic; no ambiguous join"
  (let [items (or @corpus-result (reset! corpus-result (corpus!)))
        differ (remove #(or (:same? %) (contains? designed-differences (:id %))) items)
        bad (filter #(or (not (:applied? %)) (seq (:fold-diagnostics %)) (some #{:ambiguous} (vals (:private %)))
                         (and (seq (:facts-errors %)) (not (contains? designed-facts-errors (:id %)))))
                    items)
        mss (sort (map :ms items))]
    {:pass? (and (= 94 (count items)) (empty? differ) (empty? bad))
     :items (count items) :same (count (filter :same? items)) :designed (count (filter #(contains? designed-differences (:id %)) items))
     :differ (mapv #(select-keys % [:id :plain :ours :unread]) (take 12 differ))
     :bad (mapv #(select-keys % [:id :applied? :fold-diagnostics :private :facts-errors]) (take 6 bad))
     :facts-errors (vec (keep #(when (seq (:facts-errors %)) [(:id %) (:facts-errors %)]) items))
     :entities (reduce + (map :entities items))
     :over-1s (vec (for [i items :when (> (:ms i) 1000)] [(:id i) (:ms i)]))
     :p50 (nth mss (quot (count mss) 2)) :p90 (nth mss (int (* 0.9 (count mss)))) :max (last mss)}))

;;; --- The exact join over the corpus, producer origins from the SCI fork -----

(defn- form-text [source {:keys [line column end-row end-col]}]
  (let [ls (vec (str/split-lines source))
        off (fn [l c] (+ (reduce + (map #(inc (count %)) (take (dec l) ls))) (dec c)))]
    (subs source (off line column) (off end-row end-col))))

(defproof :corpus-join :observer
  "Every corpus definition write the SCI fork observed (origins.edn, scratch JVM) joins exactly one analyzed definition through seon.turn.batch/join-write, is its declaration's member, or is generated by an agent macro whose expansion only Q1 supplies (q1-single-def-kondo); none is ambiguous"
  (let [origins (edn/read-string (slurp (str dir "origins.edn")))
        db (wdb (world))
        results
        (for [[id {:keys [writes forms source]}] origins
              [form-ix ws] (group-by :form writes)
              :let [f (get forms form-ix)
                    text (form-text source f)
                    analysis (#'sfn/runtime-analysis-batch db [{:namespace-name 'user :form-source text
                                                                  :seon.fn/declared (vec (distinct (map :sym writes)))}])
                    coords {:first-row (ffirst (:seon.fn/source-spans analysis)) :line (:line f) :column (:column f)}
                    finals (vals (reduce #(assoc %1 (:sym %2) %2) {} (filter #(= :root (:kind %)) ws)))]
              w finals
              :let [j (turn.batch/join-write analysis coords {:seon.batch/sym (:sym w) :seon.batch/origin (:origin w)
                                                               :seon.batch/member-of (:member-of w)})]]
          {:id id :sym (:sym w) :join (:seon.batch/join j) :reason (:seon.batch/reason j)
           :agent-macro? (some-> w :origin :expanded-by namespace nil?)
           :expanded-by (some-> w :origin :expanded-by)})
        agent-generated (fn [r] (and (= :unanalyzed (:join r)) (= :no-definition-at-origin (:reason r))
                                     (not (#{'defn 'defmacro 'defrecord 'deftype 'defprotocol 'defmulti 'defonce 'declare 'ns} (:expanded-by r)))
                                     (some? (:expanded-by r))))
        bad (remove #(or (#{:matched :member} (:join %)) (agent-generated %)) results)]
    {:pass? (and (seq results) (empty? bad))
     :writes (count results) :by-join (frequencies (map :join results))
     :agent-macro (vec (distinct (keep #(when (agent-generated %) [(:id %) (:sym %)]) results)))
     :bad (vec (take 8 bad))}))

;;; --- Formerly unproven, now proofs or recorded decisions ------------------------

(defproof :extension-replay :decision
  "Extension entities (defmethod, extend-*): DEFERRED (S16 not in this cut). No extension is persisted, so none is claimed; a defmethod in a false branch is not reported as present"
  (let [b (run (world) "(defmulti area :k)\n(defmethod area :sq [x] 1)\n(when false (defmethod area :never [x] 2))\n(contains? (methods area) :never)")]
    {:pass? (and (false? (last (values b))) (applied? b)
                 (empty? (filter #(re-find #"defmethod" (str (:seon.fn/source %))) (vals (get-in b [:seon.batch/program :entities])))))
     :decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"}))

;;; --- Timings (prototype on default's JVM, warm, writes through `with`) --------

(defn- timed [n f] (let [xs (vec (repeatedly n #(let [t0 (System/nanoTime) r (f)] [(ms-since t0) r])))] xs))

;; Dogfood 5: a Seon agent turn's overhead, profiled on default (read only).
(defn- live-db []
  (let [instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")]
    (db/db (:seon.db/connection (:seon.turn.loop/cluster instance)))))

(defn- latest-by-kept-evidence
  "The turn prelude's history read as the one path does it: every evaluation
  pulled with only its read-evidence ids, the latest per source kept by
  seon.turn's own fold, then the kept evaluations' evidence pulled whole."
  [database agent-id]
  (let [q '[:find ?t ?ordinal
            (pull ?evaluation [* {:seon.cluster.eval/ns [:seon.ns/name]}
                               {:seon.cluster.eval/run [:db/id :seon.turn.work/situation]}
                               {:seon.cluster.eval/read-evidence [:db/id]}])
            :in $ ?id
            :where [?agent :seon.agent/id ?id] [?agent :seon.agent/runtime ?runtime]
            [?runtime :seon.runtime/turns ?turn] [?turn :seon.turn/id _ ?t]
            [?evaluation :seon.cluster.eval/run ?turn] [?evaluation :seon.cluster.eval/ordinal ?ordinal]
            [?evaluation :seon.cluster.eval/source]]
        rows (sort-by (juxt first second) (db/q q database agent-id))
        source-key #'seon.turn/source-key
        latest (reduce (fn [m [basis _ e]]
                         (let [s (assoc e :seon.ns/name (get-in e [:seon.cluster.eval/ns :seon.ns/name]) :seon.turn/basis-t basis)
                               shown (seon.repl/shown-value (:seon.eval/shown e ""))
                               previous (get m (source-key s))
                               shown (if (and previous (map? shown) (:seon.repl/changes shown)
                                              (= :system (:seon.cluster.eval/author e))
                                              (not= :call (get-in e [:seon.cluster.eval/run :seon.turn.work/situation])))
                                       (db/apply-diff (:seon.repl/shown-value previous) (:seon.repl/changes shown))
                                       shown)]
                           (assoc m (source-key s) (assoc s :seon.repl/shown-value shown))))
                       {} rows)
        ids (vec (distinct (for [e (vals latest) r (:seon.cluster.eval/read-evidence e)] (:db/id r))))
        whole (zipmap ids (db/pull-many database '[*] ids))]
    (update-vals latest (fn [e] (cond-> e (contains? e :seon.cluster.eval/read-evidence)
                                  (update :seon.cluster.eval/read-evidence #(mapv (comp whole :db/id) %)))))))

(defproof :turn-overhead :timing
  "Dogfood 5: the agent turn prelude on default, profiled. Wake-to-open is seon.turn/system-turn, dominated by seon.turn/latest-evaluations decoding every stored read plan of the agent's history; the one path reads the evidence of the kept evaluations only, with the same result (installed since a3c1bcf81: 3.5 s became under 1 s; the rest is one producer's 4.5 M-char plan). The remainder is named (the largest stored plan, seon.eval/of-agent and per-render call reach: root-owned patches); the 300 ms target is judged after they land"
  (let [database (live-db)
        tm (fn [f] (let [t (System/nanoTime) r (f)] [(ms-since t) r]))
        [old-ms old] (tm #(#'seon.turn/latest-evaluations database "root"))
        [new-ms new] (tm #(latest-by-kept-evidence database "root"))
        strip identity
        plans (for [e (vals new) r (:seon.cluster.eval/read-evidence e)] (count (pr-str (:datahike.read/dependency-plan r))))
        [sys-ms _] (tm #(seon.turn/system-turn {:seon.turn.loop/cluster (:seon.turn.loop/cluster (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default"))
                                               :seon.agent/id "root" :seon.turn/write? false}))]
    ;; since a3c1bcf81 the installed latest-evaluations reads the kept
    ;; evidence only: it equals the one path's and is as fast
    {:pass? (and (= (set (keys old)) (set (keys new))) (= (strip old) (strip new)) (< old-ms 1000) (< new-ms 1000))
     :equal? (= (strip old) (strip new))
     :system-turn-preview-ms sys-ms
     :latest-evaluations-ms {:current old-ms :one-path new-ms}
     :kept (count new) :kept-plan-chars (vec (sort > plans))
     :remaining {:largest-plan "seon.error/for-agent's read stores one whole vector in :datahike.query.dependency/sources (4.5 M chars, ~400 ms to decode)"
                 :prompt-cold "seon.cluster.prompt/prompt 7.6 s: seon.eval/of-agent 3.7 s (the same decode), seon.db/call-reach x143 3.1 s"
                 :tasks ["a-trivial-agent-turn-takes-eleven-seconds" "history-acquisition-decodes-stored-read-plans" "call-reach-is-recomputed-per-render"]}}))

(defn- used-heap []
  (System/gc) (System/gc)
  (.getUsed (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))))

(defn retained-mb
  "[value retained-MB]: the heap still used, after full collections, while
  `f`'s value (a batch) is held, over the heap used before it ran. Other
  threads' allocation is included, so it is an upper bound."
  [f]
  (let [before (used-heap)
        v (f)
        held (used-heap)]
    [v (/ (Math/round (/ (- held before) 1e4)) 100.0) (identity v)]))

(defproof :time-one-defn :timing
  "One contracted defn, warm: read, evaluate, derive, validated apply, gate, recorded commit"
 (on-fresh-branch
   (fn [] (let [w (world)
        n (atom 0)
        runs (timed 5 #(run w (str "(defn t1f" (swap! n inc) " {:malli/schema [:=> [:cat :int] :int]} [x] x)")))
        [ms b] (last runs)
        [kept mb] (retained-mb #(run w "(defn t1f-held {:malli/schema [:=> [:cat :int] :int]} [x] x)"))]
    {:pass? (and (applied? b) (< ms 1000) (= 1 (count (get-in b [:seon.batch/program :entities]))) (applied? kept))
     :ms (mapv first runs) :timings (:seon.batch/timings b) :batch-retained-mb mb}))))

(defproof :time-chained-40 :timing
  "Astra's chained input: 40 contracted defns with 39 pulls of the preceding definition's facts (79 forms)"
 (on-fresh-branch
   (fn [] (let [text (:text (edn/read-string (slurp "/Users/sean/src/seon/tmp/review-one-repl-v2-20260927/chained.edn")))
        w (world)
        _ (run w "(+ 1 1)")
        t0 (System/nanoTime)
        b (run w text)
        ms (ms-since t0)
        [held mb] (retained-mb #(run (world) text))]
    {:pass? (and (applied? b) (every? some? (keep-indexed (fn [i v] (when (odd? i) v)) (values b))))
     :ms ms :forms (count (:seon.batch/entries b)) :timings (:seon.batch/timings b)
     :batch-retained-mb mb :retained-forms (count (:seon.batch/entries held))}))))

(defproof :time-h36 :timing
  "h36: 22 top-level register! calls, each version applied to the working value before the next form"
 (on-fresh-branch
   (fn [] (let [text (slurp "/Users/sean/src/seon/tmp/valid-clojure-corpus/items/h36-register-22.clj")
        w (world)
        t0 (System/nanoTime)
        b (run w text)
        ms (ms-since t0)]
    {:pass? (applied? b) :ms ms :timings (:seon.batch/timings b)}))))

(defproof :time-acquisition :timing
  "Acquisition through the one function: 100 stored definitions' sources evaluated into a fresh world (code reader, no observation, derivation or recording)"
 (on-fresh-branch
   (fn [] (let [w (world)
        fresh (world)                       ; its context was acquired before these definitions existed
        text (str/join "\n" (for [i (range 100)] (str "(defn acq" i " [x] (+ x " i "))")))
        a (after (run w text))
        srcs (vec (for [i (range 100)] (:seon.fn/source (db/pull a [:seon.fn/source] [:seon.fn/sym (symbol "user" (str "acq" i))]))))
        t0 (System/nanoTime)
        b (batch/evaluate-batch (sci.batch/acquire-request {:seon.batch/text (str/join "\n" srcs)
                                                             :seon.batch/world fresh}))
        ms (ms-since t0)
        got (values (batch/evaluate-batch (sci.batch/acquire-request {:seon.batch/text "(acq99 1)" :seon.batch/world fresh})))]
    {:pass? (and (every? some? srcs) (= 100 (count (:seon.batch/entries b))) (= [100] got))
     :entities 100 :ms ms :us-per-entity (Math/round (/ (* 1000.0 ms) 100))}))))

(defproof :write-after-schema-batch :timing
  "After a 22-registration batch has run anywhere in the JVM, an ordinary contracted defn in a fresh world stays under 1 s (it was 214 ms before)"
 (on-fresh-branch
   (fn [] (let [_ (run (world) (slurp "/Users/sean/src/seon/tmp/valid-clojure-corpus/items/h36-register-22.clj"))
        w (world)
        runs (vec (for [i (range 3)] (let [t0 (System/nanoTime)
                                           b (run w (str "(defn wasb" i " {:malli/schema [:=> [:cat :int] :int]} [x] x)"))]
                                       [(ms-since t0) (:seon.batch/timings b)])))]
    {:pass? (< (first (last runs)) 1000) :ms (mapv first runs) :timings (second (last runs))
     :cause "seon.db/nearest-base: the 24-entry projection-cache's committed revision entries are evicted by the batch's 22 speculative schema values; each later speculative value then derives its projection from a far base (642-659 ms against 30-31 ms)"}))))

;;; ---------------------------------------------------------------------------
;;; The table
;;; ---------------------------------------------------------------------------

(defn- relieve-pressure!
  "The shared JVM refuses new evaluations above its old-generation bound; this
  suite's own allocation must not push it there. A collection when the old
  generation retained after the last one is above 5 GB."
  []
  (let [old (some #(when (re-find #"Old Gen" (.getName ^java.lang.management.MemoryPoolMXBean %)) %)
                  (java.lang.management.ManagementFactory/getMemoryPoolMXBeans))]
    (when (some-> old .getCollectionUsage .getUsed (> (* 5 1024 1024 1024)))
      (System/gc))))

(defn run-proofs!
  "Run every proof (or those whose id is in `only`) in one disposable
  execution; answer the map of named proofs, id -> result."
  ([] (run-proofs! nil))
  ([only]
   (let [exec (acquire!)]
     (try
       (binding [*exec* exec]
         (reduce (fn [m {:keys [id claim run] klass :class}]
                   (if (or (nil? only) (contains? only id))
                     (let [_ (relieve-pressure!)
                           t0 (System/nanoTime)
                           r (try (run) (catch Throwable t {:pass? false :threw (str (.getName (class t)) ": " (ex-message t))}))]
                       (assoc m id (assoc r :class klass :claim claim :ms-total (ms-since t0))))
                     m))
                 (array-map)
                 ;; the corpus and the timings last, the 22-registration batch very last:
                 ;; their cost is measured, not inherited by the proofs before them
                 ;; ordinary timings first, on a JVM no schema batch has run in; the
                 ;; corpus, the 22-registration batch and what follows it last
                 (sort-by #(case (:id %) (:time-one-defn :time-acquisition :time-chained-40) -1
                                 :corpus 1 :time-h36 2 :write-after-schema-batch 3 0)
                          @proofs)))
       (finally (release! exec))))))

(defn- jvm-state
  "Heap in use and the G1 old generation after its last collection."
  []
  (let [old (some #(when (re-find #"Old Gen" (.getName ^java.lang.management.MemoryPoolMXBean %)) %)
                  (java.lang.management.ManagementFactory/getMemoryPoolMXBeans))]
    {:heap-used-mb (quot (- (.totalMemory (Runtime/getRuntime)) (.freeMemory (Runtime/getRuntime))) 1048576)
     :old-gen-after-gc-mb (some-> old .getCollectionUsage .getUsed (quot 1048576))
     :processors (.availableProcessors (Runtime/getRuntime))}))

(defn prove!
  "Run the suite; write proof-results.edn (the map of named proofs) and
  proof-summary.md; answer the summary."
  ([] (prove! nil))
  ([only]
   (let [before (jvm-state)
         results (assoc (run-proofs! only) :jvm {:pass? true :class :context :claim "JVM state around the run"
                                                  :before before :after (jvm-state) :ms-total 0})
         md (str "| proof | class | pass | ms | evidence |\n|---|---|---|---|---|\n"
                 (str/join "\n" (for [[id r] results]
                                   (format "| `%s` | %s | %s | %d | %s |" (name id) (name (:class r))
                                           (if (:pass? r) "PASS" "**FAIL**") (:ms-total r)
                                           (str/replace (brief (dissoc r :class :claim :pass? :ms-total)) "|" "\\|"))))
                 "\n\n" (count (filter (comp :pass? val) results)) "/" (count results) " proofs pass\n")]
     (when (nil? only)
       (spit (str dir "proof-results.edn") (with-out-str (pprint/pprint results)))
       (spit (str dir "proof-summary.md") md))
     {:pass (count (filter (comp :pass? val) results)) :of (count results)
      :failing (vec (keep (fn [[id r]] (when-not (:pass? r) [id (brief (dissoc r :claim))])) results))})))

(defn check!
  "Run proofs (all when `only` is nil); answer the count and the failing ones."
  [only]
  (let [rs (run-proofs! only)]
    {:pass (count (filter (comp :pass? val) rs)) :of (count rs)
     :failing (vec (keep (fn [[id r]] (when-not (:pass? r) [id (brief (dissoc r :claim))])) rs))}))

(defproof :attribute-change-equivalence :schema
  "The change-proportional attribute derivation equals seon.turn's whole-projection one on: a lone key, an entity map listing two new keys, and a redefinition of a key already stored"
  (let [w (world)
        full @(requiring-resolve 'seon.turn/schema-attribute-change-tx)
        cases [["(seon.schema/register! :ace.v3/n :int)" #{:ace.v3/n}]
               ["(seon.schema/register! :ace.v3/a :string) (seon.schema/register! :ace.v3/b :int) (seon.schema/register! :ace.v3/e [:map {:seon.db/attributes true} [:ace.v3/a :ace.v3/a] [:ace.v3/b :ace.v3/b]])"
                #{:ace.v3/a :ace.v3/b :ace.v3/e}]]
        stored (after (run w "(seon.schema/register! :ace.v3/r :int)"))
        results (vec (concat
                      (for [[text ks] cases]
                        (let [req (turn.batch/code-request (request w text {}))
                              b (batch/evaluate-batch (assoc req :seon.batch/derive nil :seon.batch/settle nil))
                              db (wdb w) current (db/carried-projection db)
                              candidate ((requiring-resolve 'seon.sci.arm/arming-projection) req db)]
                          (= (full db current candidate ks) (turn.batch/attribute-change-tx db current candidate ks))))
                      (let [req (turn.batch/code-request (request (world) "(seon.schema/register! :ace.v3/r :string)" {}))
                            _ (batch/evaluate-batch (assoc req :seon.batch/derive nil :seon.batch/settle nil))
                            current (db/carried-projection stored)
                            candidate ((requiring-resolve 'seon.sci.arm/arming-projection) req stored)]
                        [(= (full stored current candidate #{:ace.v3/r}) (turn.batch/attribute-change-tx stored current candidate #{:ace.v3/r}))])))]
    {:pass? (= [true true true] results) :results results}))
