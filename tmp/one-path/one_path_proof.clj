(ns one-path-proof
  "The one-path proof suite: every row runs the implementation in
  tmp/one-path/src against default's live database, in an agent world on a
  disposable branch (commits through `seon.db/with`, the writer's own
  validation), or reads the scratch-JVM results of the SCI fork rows.

  (load-file \"/Users/sean/src/seon/tmp/one-path/load.clj\")
  (load-file \"/Users/sean/src/seon/tmp/one-path/one_path_proof.clj\")
  (one-path-proof/prove!)   ; the map of named proofs -> proof-results.edn, summary -> proof-summary.md"
  (:require [cheshire.core]
            [clj-kondo.core]
            [clojure.walk]
            [clojure.edn :as edn]
            [clojure.java.shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]
            [sci.core :as sci]
            [seon.batch :as batch]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.sci.batch :as sci.batch]
            [seon.sci.eval :as eval]
            [seon.sci.forgive :as forgive]
            [seon.fn :as sfn]
            [seon.sci.pinned :as pinned]
            [seon.turn.batch :as turn.batch]))

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

(defn world
  "A fresh agent world forked from the suite's execution, reading `db` (its
  branch head by default)."
  ([] (world (:seon.db/db *exec*)))
  ([db]
   {:db db :conn (:seon.db/connection *exec*)
    ;; the landing deletes native-mutation (S12: removals are observed writes);
    ;; its guard reads this flag, so the world runs without it
    :ctx (assoc (eval/fork-cluster-ctx (:seon.sci.eval/base-ctx *exec*) db (:seon.db/connection *exec*))
                :seon.sci.eval/native-operation? true)}))

(def turn-stages
  "What seon.turn supplies to the program stages (it owns these writers)."
  {:seon.turn.batch/row-tx @(requiring-resolve 'seon.turn/row-tx)
   :seon.turn.batch/record-call (requiring-resolve 'seon.turn/record-evaluated-call)})

(defn request
  "A program request over `text` in `w`, committing to a `with` value."
  [w text opts]
  (let [{:keys [db ctx]} w
        head (or (:head opts) db)]
    (merge turn-stages
           {:seon.batch/text text :seon.batch/database db :seon.sci.eval/ctx ctx
            :seon.batch/observe pinned/observe :seon.sci.pinned/roots (pinned/observer-state)
            :seon.agent/id "root" :seon.turn/id (str "op3-" (subs (str (random-uuid)) 0 8))
            :seon.cluster/name "default"
            :seon.batch/commit! (fn [tx] (db/with head tx))
            :seon.batch/head (fn [_] head)}
           (dissoc opts :head :mode))))

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
  "SCI's own clj suite on the patched clone: 397 tests, the same 5 failures as the pinned fork's 393, 0 new"
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
  (let [w (world)
        a0 (after (run w "(defn hm1 [x] x)"))
        head (after (run (assoc w :db a0) "(defn hmref [x] (hm1 x))"))
        b (run (assoc w :db a0) "(defn hm3 [x] x)\n(ns-unmap *ns* 'hm1)\n(hm3 9)" {:head head})
        a (after b)]
    {:pass? (and (applied? b) (= 9 (last (values b))) (stored? a 'user/hm3) (stored? a 'user/hm1) (stored? a 'user/hmref)
                 (= 1 (count (facts-errors b))) (some? (get-in b [:seon.batch/commit :seon.batch/first-refusal])))
     :facts-errors (facts-errors b) :first-refusal (get-in b [:seon.batch/commit :seon.batch/first-refusal :seon.error/message])}))

(defproof :oq1-storage-failure :owner
  "OQ1 writer failure: storage fails at commit; every value is kept and the batch answers the storage error"
  (let [b (run (world) "(defn sf1 [x] x)\n(sf1 7)" {:seon.batch/commit! (fn [_] (throw (ex-info "storage unavailable" {})))})]
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
        ctx (:ctx (world))
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
  "Q5: def/defonce are program rows with dependency edges, acquired in edge order into a fresh context (acquisition entrant)"
  (let [b (run (world) "(def base-v 41)\n(def dep-v {:k base-v})\n(defonce once-v (inc base-v))")
        a (after b)
        rs (for [s '[user/base-v user/dep-v user/once-v]] (db/pull a '[:seon.fn/sym :seon.fn/source :seon.fn/calls] [:seon.fn/sym s]))
        fresh (world)
        acq (batch/evaluate-batch (turn.batch/acquire-request
                                   {:seon.batch/text (str/join "\n" (map :seon.fn/source rs))
                                    :seon.batch/database a :seon.sci.eval/ctx (:ctx fresh)}))
        got (values (batch/evaluate-batch (turn.batch/acquire-request
                                           {:seon.batch/text "[once-v dep-v]" :seon.batch/database a :seon.sci.eval/ctx (:ctx fresh)})))]
    {:pass? (and (applied? b) (every? :seon.fn/source rs)
                 (contains? (set (:seon.fn/calls (second rs))) 'user/base-v)
                 (= [[42 {:k 41}]] got) (empty? (diags acq)))
     :calls (mapv (comp vec :seon.fn/calls) rs) :acquired got}))

(defproof :contract-warning :owner
  "Contract coverage: one uncontracted function gives the note; two give the growing warning; nothing is refused"
  (let [b1 (run (world) "(defn nc1 [x] x)")
        b2 (run (world) "(defn nc2 [x] x)\n(defn nc3 [x] x)")]
    {:pass? (and (str/includes? (str (:seon.eval/contract-note b1)) "user/nc1")
                 (every? (set (:seon.eval/contract-warning b2)) '[user/nc2 user/nc3])
                 (applied? b1) (applied? b2))
     :note (:seon.eval/contract-note b1) :warning (:seon.eval/contract-warning b2)}))

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
  without its nondeterministic timings."
  [r]
  (-> (cheshire.core/parse-string (cheshire.core/generate-string r) true)
      (update :seon.batch/evaluations (fn [es] (mapv #(dissoc % :seon.batch/duration-ms) es)))))

(def owner-behaviors
  "Each owner behavior's input, run through both entrants."
  {:oq1 "(defn be1 {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(defn be-bad {:malli/schema [:=> [:cat :no.such/key] :int]} [x] 1)\n(be1 5)"
   :q3 "(defn be3 \"First.\" [x] x)\n(:seon.fn/doc (seon.db/pull [:seon.fn/doc] [:seon.fn/sym 'user/be3]))"
   :q4 "(seon.schema/register! :be.q4/n :int)\n(seon.schema/register! :be.q4/f [:=> [:cat :int] :int])"
   :q5 "(def be-base 41)\n(def be-dep {:k be-base})"
   :contract "(defn be-nc1 [x] x)\n(defn be-nc2 [x] x)"
   :gate "(defn be-gt {:malli/schema [:=> [:cat :int] :int]} [x] (str x))\n(be-gt 1)"
   :feedback "(require '[clojure.test :refer [deftest is]])\n(defn be-f [x] x)\n(deftest be-t (is (= 1 (be-f 1))))"
   :removal "(defn be-r [x] x)\n(ns-unmap *ns* 'be-r)"})

(defproof :mcp-equals-agent-turn :entrant
  "MCP is a thin wrapper: every owner behavior run as an MCP eval and as a Seon agent turn (scripted local provider stub) answers the same reply, transport aside"
  (let [pairs (for [[k text] owner-behaviors]
                [k (transport-free (mcp-eval (world) text)) (transport-free (agent-turn (world) text))])
        differ (vec (for [[k a b] pairs :when (not= a b)] [k (brief a) (brief b)]))]
    {:pass? (empty? differ) :behaviors (count pairs) :differ differ}))

;;; --- Feedback from facts ---------------------------------------------------

(defproof :feedback-from-facts :owner
  "The reply's :seon.eval/feedback is queried from the batch's validated working value, scoped by the definitions it changed: those still missing a contract, and the tests reaching each (never run here); the growing warning rides beside it"
  (let [b (run (world) "(require '[clojure.test :refer [deftest is]])\n(defn fb-f [x] x)\n(defn fb-g {:malli/schema [:=> [:cat :int] :int]} [x] (fb-f x))\n(deftest fb-t (is (= 1 (fb-g 1))))")
        fb (:seon.eval/feedback b)]
    {:pass? (and (applied? b)
                 (= ['user/fb-f] (:seon.feedback/missing-contracts fb))
                 (= [{:seon.test/sym 'user/fb-t :seon.feedback/status :never-run}] (get-in fb [:seon.feedback/reaching-tests 'user/fb-g]))
                 (some? (or (:seon.eval/contract-note b) (:seon.eval/contract-warning b))))
     :feedback fb :contract (or (:seon.eval/contract-note b) (:seon.eval/contract-warning b))}))

(defproof :jvm-gets-feedback :entrant
  "MCP JVM mode receives the same feedback key as every other entrant (it changed no definition, so its feedback names none)"
  (let [w (world)
        b (batch/evaluate-batch (turn.batch/jvm-request (request w "(+ 1 2)" {})))]
    {:pass? (and (= [3] (values b)) (contains? (turn.batch/reply b) :seon.eval/feedback))
     :feedback (:seon.eval/feedback b)}))

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
                                         (pinned/observe r working on-write thunk)
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
        w (world)
        a0 (after (run w "(defn rr2 [x] x)"))
        b2 (run (assoc w :db a0) "(ns-unmap *ns* 'rr2)")]
    {:pass? (and (applied? b) (not (stored? (after b) 'user/rr1)) (stored? a0 'user/rr2) (not (stored? (after b2) 'user/rr2)))}))

(defproof :v2-5-read-only-evidence :effects
  "V2-5: an evaluation is read-only only with no observed write and no schema change: unmap-only and register-only forms are writes"
  (let [b (run (world) "(def ro1 1)\n(ns-unmap *ns* 'ro1)\n(seon.schema/register! :ro.v2/k :int)\n(+ 1 2)")]
    {:pass? (= [false false false true] (mapv #(turn.batch/read-only? b %) (:seon.batch/entries b)))
     :read-only (mapv #(turn.batch/read-only? b %) (:seon.batch/entries b))}))

(defproof :v2-6-tag-once :input
  "V2-6: accepting a structured suffix after prose invokes its tag reader once"
  (let [calls (atom 0)
        ctx (:ctx (world))
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
  "V2-2: a record's rows acquire into a fresh context by evaluating its declaration once; the constructor works"
  (let [b (run (world) "(defrecord OpR [a])\n(:a (->OpR 1))")
        a (after b)
        srcs (distinct (keep #(:seon.fn/source (db/pull a [:seon.fn/source] [:seon.fn/sym %])) '[user/->OpR user/map->OpR]))
        fresh (world)
        acq (batch/evaluate-batch (turn.batch/acquire-request
                                   {:seon.batch/text (str/join "\n" srcs) :seon.batch/database a :seon.sci.eval/ctx (:ctx fresh)}))
        got (values (batch/evaluate-batch (turn.batch/acquire-request
                                           {:seon.batch/text "(:a (->OpR 7))" :seon.batch/database a :seon.sci.eval/ctx (:ctx fresh)})))]
    {:pass? (and (= 1 (last (values b))) (= 1 (count srcs)) (= [7] got) (empty? (diags acq)))
     :sources srcs :acquired got}))

(defproof :r8-render-read-only :entrant
  "R8 (render/context-assembly entrant): a render batch's def is discarded with its fork and its write refuses (no connection); a shared atom it mutates is the stated limit"
  (let [w (world)
        _ (sci/eval-string* (:ctx w) "(def shared (atom 0))")
        b (batch/evaluate-batch (turn.batch/render-request
                                 (merge (request w "(def leaked 1)\n(seon.db/transact! [{:db/id \"x\" :seon.ns/name 'render.leak}])\n(swap! shared inc)" {})
                                        {:seon.sci.eval/ctx (:ctx w)})))]
    {:pass? (and (nil? (sci/resolve (:ctx w) 'user/leaked))
                 (= 'seon.db/missing-connection-error (:seon.error/operation (second (values b))))
                 (= 1 (last (values b))))
     :values (mapv str (values b)) :viewer-shared (sci/eval-string* (:ctx w) "@shared")}))

(defproof :v2-8-nested-render-one-tx :entrant
  "V2-8: a form that renders (a nested read-only batch) contributes to its run's one transaction: one commit for the batch"
  (let [w (world)
        commits (atom 0)
        render! (fn [text] (shown (batch/evaluate-batch (turn.batch/render-request (merge (request w text {}) {:seon.sci.eval/ctx (:ctx w)})))))
        _ (sci/intern (:ctx w) (sci/find-ns (:ctx w) 'user) 'render! render!)
        b (run w "(defn nr [x] x)\n(render! \"(+ 1 2)\")" {:seon.batch/commit! (fn [tx] (swap! commits inc) (db/with (:db w) tx))})]
    {:pass? (and (= ["3"] (second (values b))) (= 1 @commits) (applied? b)) :commits @commits :values (mapv str (values b))}))

(defproof :v2-8-jvm-recorded :entrant
  "V2-8 (MCP JVM entrant): host reader and eval, several forms with an alias, an error kept beside later values, the run recorded in one transaction"
  (let [w (world)
        commits (atom 0)
        b (batch/evaluate-batch
           (turn.batch/jvm-request (merge (request w "(ns op3.jvm (:require [clojure.string :as s]))\n(s/upper-case \"a\")\n(/ 1 0)\n(+ 1 2)" {})
                                          {:seon.batch/commit! (fn [tx] (swap! commits inc) (db/with (:db w) tx))})))
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
        (let [w (world)
              req (request w body {:seon.batch/commit! (fn [tx] (db/transact! requester tx))
                                   :seon.batch/head (fn [_] (db/db requester))
                                   :seon.sci.eval/time-limit-ms 5000})
              b (batch/evaluate-batch (turn.batch/code-request req))
              recorded (count (d/q '[:find [?e ...] :in $ ?id :where [?r :seon.turn/id ?id] [?e :seon.cluster.eval/run ?r]]
                                   (db/db requester) (:seon.turn/id req)))]
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
        evals (d/q '[:find [?e ...] :in $ ?id :where [?r :seon.turn/id ?id] [?e :seon.cluster.eval/run ?r]] a (:seon.turn/id req))]
    {:pass? (and (applied? b) (stored? a 'user/mcp1) (= 2 (count evals)))
     :evaluations (count evals) :commit (commit-of b)}))

(defproof :entrant-gate :entrant
  "Install gate: a contracted function whose seeded contract check fails keeps its value and live Var; its facts are refused and logged; a passing one is stored"
  (let [b (run (world) "(defn gt1 {:malli/schema [:=> [:cat :int] :int]} [x] (str x))\n(defn gt3 {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(gt3 4)")
        a (after b)]
    {:pass? (and (= 4 (last (values b))) (not (stored? a 'user/gt1)) (stored? a 'user/gt3) (= 1 (count (facts-errors b))))
     :facts-errors (facts-errors b)}))

(defproof :entrant-merge-write-back :entrant
  "Merge/write-back entrant: neither evaluates (merge moves entities by identity through my.program, unchanged by this cut). A committed row re-analyzed from its stored source, write-back's reindex, derives the same definition digest"
  (let [w (world)
        b (run w "(defn wb1 {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n(defn wb2 [y] (wb1 y))")
        a (after b)
        stored (fn [database s] (db/pull database [:seon.fn/source :seon.program/definition-digest] [:seon.fn/sym s]))
        reindexed (for [s '[wb1 wb2]
                        :let [{src :seon.fn/source dg :seon.program/definition-digest} (stored a (symbol "user" (str s)))
                              {:keys [entities]} (#'turn.batch/analyze-form (:ctx w) a 'user src '[user/wb1 user/wb2])]]
                    (= dg (:seon.program/definition-digest (first (vals entities)))))
        ]
    {:pass? (and (applied? b) (= [true true] (vec reindexed)))
     :reindexed (vec reindexed)}))

;;; --- Static rows: the cut's names and requires --------------------------------

(defn- new-contracts []
  (for [n '[seon.batch seon.sci.batch seon.sci.arm seon.sci.forgive seon.turn.batch]
        [s v] (ns-interns n) :let [c (:malli/schema (meta v))] :when c]
    [(symbol (str n) (str s)) c]))

(defproof :v2-7-schema-names :static
  "V2-7: every new :seon.batch name is free in the live registry; every new form and every new contract compiles against the live registry plus the new forms (an undeclared reference fails to compile)"
  (let [forms (edn/read-string (slurp (str dir "resources/seon/schemas/seon.batch.edn")))
        proj (db/carried-projection (:db (world)))
        taken (filter #(contains? (:seon.schema.projection/forms proj) %) (keys forms))
        registry (mr/composite-registry forms (:seon.schema.projection/registry proj))
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
                          (require-graph ["/Users/sean/src/seon/src" (str dir "src")])
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
   "h13-program-ns-unmap" "plain SCI has no my.program; at HEAD my.program/ns-unmap! answers its refusal for a name no stored row holds. S12 makes it SCI's unmap, observed (rows v2-5-remove-rebind and r4-new-then-removed prove the removal)"})

(def designed-facts-errors
  "Items one of whose forms' facts are refused by design, with the reason."
  {"h32-multi-arity" "its contract admits every int while its :pre refuses non-positive ones: the install gate's seeded check refuses it (today's path refuses it too); the value is kept"})

(defn- object-free [x] (clojure.walk/postwalk #(if (string? %) (str/replace % #"(0x|@)[0-9a-f]+" "$1_") %) x))

(defn corpus-item [{:keys [id source]}]
  (let [show (ns-resolve 'runner 'show)
        plain (mapv plain-outcome ((ns-resolve 'runner 'sci-run) source))
        t0 (System/nanoTime)
        b (run (world) source)
        ms (ms-since t0)
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
        {:keys [db ctx]} (world)
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

;;; --- Formerly unproven, now rows or recorded decisions ------------------------

(defproof :extension-replay :decision
  "Extension entities (defmethod, extend-*): DEFERRED (S16 not in this cut). No extension is persisted, so none is claimed; a defmethod in a false branch is not reported as present"
  (let [b (run (world) "(defmulti area :k)\n(defmethod area :sq [x] 1)\n(when false (defmethod area :never [x] 2))\n(contains? (methods area) :never)")]
    {:pass? (and (false? (last (values b))) (applied? b)
                 (empty? (filter #(re-find #"defmethod" (str (:seon.fn/source %))) (vals (get-in b [:seon.batch/program :entities])))))
     :decision "S16 needs a producer event for addMethod/removeMethod and extend in the SCI fork before any extension entity; deferred to its own spec"}))

;;; --- Timings (prototype on default's JVM, warm, writes through `with`) --------

(defn- timed [n f] (let [xs (vec (repeatedly n #(let [t0 (System/nanoTime) r (f)] [(ms-since t0) r])))] xs))

(defproof :time-one-defn :timing
  "One contracted defn, warm: read, evaluate, derive, validated apply, gate, recorded commit"
  (let [w (world)
        n (atom 0)
        runs (timed 5 #(run w (str "(defn t1f" (swap! n inc) " {:malli/schema [:=> [:cat :int] :int]} [x] x)")))
        [ms b] (last runs)]
    {:pass? (and (applied? b) (< ms 1000) (= 1 (count (get-in b [:seon.batch/program :entities]))))
     :ms (mapv first runs) :timings (:seon.batch/timings b)}))

(defproof :time-chained-40 :timing
  "Astra's chained input: 40 contracted defns with 39 pulls of the preceding definition's facts (79 forms)"
  (let [text (:text (edn/read-string (slurp "/Users/sean/src/seon/tmp/review-one-repl-v2-20260927/chained.edn")))
        w (world)
        _ (run w "(+ 1 1)")
        t0 (System/nanoTime)
        b (run w text)
        ms (ms-since t0)]
    {:pass? (and (applied? b) (every? some? (keep-indexed (fn [i v] (when (odd? i) v)) (values b))))
     :ms ms :forms (count (:seon.batch/entries b)) :timings (:seon.batch/timings b)}))

(defproof :time-h36 :timing
  "h36: 22 top-level register! calls, each version applied to the working value before the next form"
  (let [text (slurp "/Users/sean/src/seon/tmp/valid-clojure-corpus/items/h36-register-22.clj")
        w (world)
        t0 (System/nanoTime)
        b (run w text)
        ms (ms-since t0)]
    {:pass? (applied? b) :ms ms :timings (:seon.batch/timings b)}))

(defproof :time-acquisition :timing
  "Acquisition through the one function: 100 stored definitions' sources evaluated into a fresh world (code reader, no observation, derivation or recording)"
  (let [w (world)
        text (str/join "\n" (for [i (range 100)] (str "(defn acq" i " [x] (+ x " i "))")))
        a (after (run w text))
        srcs (vec (for [i (range 100)] (:seon.fn/source (db/pull a [:seon.fn/source] [:seon.fn/sym (symbol "user" (str "acq" i))]))))
        fresh (world)
        t0 (System/nanoTime)
        b (batch/evaluate-batch (turn.batch/acquire-request {:seon.batch/text (str/join "\n" srcs)
                                                             :seon.batch/database a :seon.sci.eval/ctx (:ctx fresh)}))
        ms (ms-since t0)
        got (values (batch/evaluate-batch (turn.batch/acquire-request {:seon.batch/text "(acq99 1)" :seon.batch/database a
                                                                       :seon.sci.eval/ctx (:ctx fresh)})))]
    {:pass? (and (every? some? srcs) (= 100 (count (:seon.batch/entries b))) (= [100] got))
     :entities 100 :ms ms :us-per-entity (Math/round (/ (* 1000.0 ms) 100))}))

(defproof :write-after-schema-batch :timing
  "After a 22-registration batch has run anywhere in the JVM, an ordinary contracted defn in a fresh world stays under 1 s (it was 214 ms before)"
  (let [_ (run (world) (slurp "/Users/sean/src/seon/tmp/valid-clojure-corpus/items/h36-register-22.clj"))
        w (world)
        runs (vec (for [i (range 3)] (let [t0 (System/nanoTime)
                                           b (run w (str "(defn wasb" i " {:malli/schema [:=> [:cat :int] :int]} [x] x)"))]
                                       [(ms-since t0) (:seon.batch/timings b)])))]
    {:pass? (< (first (last runs)) 1000) :ms (mapv first runs) :timings (second (last runs))
     :cause "seon.db/nearest-base: the 24-entry projection-cache's committed revision entries are evicted by the batch's 22 speculative schema values; each later speculative value then derives its projection from a far base (642-659 ms against 30-31 ms)"}))

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
                              db (:db w) current (db/carried-projection db)
                              candidate ((requiring-resolve 'seon.sci.arm/arming-projection) req db)]
                          (= (full db current candidate ks) (turn.batch/attribute-change-tx db current candidate ks))))
                      (let [req (turn.batch/code-request (request (assoc w :db stored) "(seon.schema/register! :ace.v3/r :string)" {}))
                            _ (batch/evaluate-batch (assoc req :seon.batch/derive nil :seon.batch/settle nil))
                            current (db/carried-projection stored)
                            candidate ((requiring-resolve 'seon.sci.arm/arming-projection) req stored)]
                        [(= (full stored current candidate #{:ace.v3/r}) (turn.batch/attribute-change-tx stored current candidate #{:ace.v3/r}))])))]
    {:pass? (= [true true true] results) :results results}))
