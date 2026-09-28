;; Feedback renders proof (lane feedback-renders-opus, 2026-09-27).
;;
;; Evaluated into default over MCP JVM mode, in new namespaces only (`proof`,
;; and while the landed code is not yet installed, the scratch copies
;; `fr.error` and `fr.render.schema` of the landed source): never load-file,
;; intern or alter-var-root over default's program.
;;
;; What this proves, and what it does not (Astra e9e15d610 §4):
;; - Each case runs on a disposable isolated branch of default's head through
;;   two batch adapters: `turn.batch/code-request` (what the MCP tool encodes)
;;   and `turn.batch/turn-request` over text a local function returns. The
;;   second is NOT the provider/Flow turn entrance; call it the turn-request
;;   adapter, not an agent turn.
;; - `:before` is the installed code's shown text. `:after` is the landed
;;   render function called directly on the extracted value, printed here by
;;   the value printer; it is not a saved reply of an installed render.
;; - `:fix-works?` evaluates a fix an agent writes from the reply (`:fix`,
;;   authored per case) and checks no thrown error; a returned error map would
;;   pass it. Replies no longer offer a synthesized fix form.
;; - A registration's stored-data check is `seon.db/registration-check` under
;;   the world's database, as `register!` records it; the render only reads it.

(ns proof
  (:require [clojure.pprint :as pprint]
            [clojure.walk]
            [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.batch :as batch]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.repl :as repl]
            [seon.sci.eval :as eval]
            [seon.sci.observe :as observe]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as world]))

;;; --- Worlds: an isolated root execution on a disposable branch ------------

(defn acquire!
  "An isolated root execution on a new branch of default's head."
  []
  (let [instance (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")
        handle (assoc (:seon.turn.loop/cluster instance) :seon.store/store (:seon.store/store instance))
        head (db/db (:seon.db/connection handle))]
    (agent/acquire-context! handle "root" {:seon.agent/isolate? true
                                            :seon.cluster.registry/from (db/commit-id head)})))

(defn release!
  "Release an execution and unlink its branch."
  [exec]
  ((:release @@#'eval/agent-entrance) exec))

(defn- live-process []
  (:seon.db.process/id (:seon.turn.loop/cluster (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default"))))

(def ^:private turn-stages
  {:seon.turn.batch/row-tx @(requiring-resolve 'seon.turn/row-tx)
   :seon.turn.batch/record-call (requiring-resolve 'seon.turn/record-evaluated-call)})

(defn world
  "A fresh agent world on the execution's branch, reading its head now."
  [exec]
  (update (world/agent-world exec {:seon.agent/id "root"
                                   :seon.turn/id (str "fr-" (subs (str (random-uuid)) 0 8))
                                   :seon.db.process/id (live-process)})
          :seon.sci.eval/ctx assoc :seon.sci.eval/native-operation? true))

(defn- request [w text]
  (merge turn-stages {:seon.batch/text text
                      :seon.batch/world (assoc w :seon.turn/id (str "fr-" (subs (str (random-uuid)) 0 8)))
                      :seon.batch/observe observe/observe}))

(defn mcp-batch
  "The MCP entrant: one code batch through the one path."
  [w text]
  (batch/evaluate-batch (turn.batch/code-request (request w text))))

(defn agent-batch
  "A Seon agent turn: a scripted local stub provider answers with `text`."
  [w text]
  (let [provider (fn [_context] text)]
    (batch/evaluate-batch (turn.batch/turn-request (request w (provider {:seon.agent/id "root"}))))))

(defn on-branch
  "Call `f` with a fresh execution, released after."
  [f]
  (let [exec (acquire!)] (try (f exec) (finally (release! exec)))))

(defn- last-entry [b] (peek (:seon.batch/entries b)))

(defn- error-of
  "The last entry's error value: a thrown one, else a returned error map."
  [b]
  (let [entry (last-entry b)
        thrown (or (:seon.batch/error-value (ex-data (:seon.batch/error entry))) (:seon.batch/error entry))
        value (:seon.batch/value entry)]
    (cond (map? thrown) thrown
          (and (map? value) (:seon.error/message value)) value)))

(defn toks "Estimated tokens of a shown text." [s] (tokens/estimate (str s)))

(defn- ms-of [f]
  (let [t0 (System/nanoTime) v (f)] [v (Math/round (/ (- (System/nanoTime) t0) 1e6))]))

;;; --- The landed code: installed, or its scratch copy ----------------------

(defn- read-forms [text]
  (let [reader (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))]
    (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof :read-cond :allow} reader)))))

(defn- eval-in! [ns-form text]
  (binding [*ns* *ns*]
    (eval ns-form)
    (doseq [form (read-forms text)] (eval form))))

(def ^:private root "/Users/sean/src/seon/")

(defn installed?
  "True once default runs the landed renders (after root's restart)."
  []
  (some? (resolve 'seon.db/registration-check)))

(defn copy-landed!
  "Evaluate the landed source's new render code into scratch namespaces:
  error.clj's reply section into `fr.error` (the rest of seon.error it uses
  is referred from the installed namespace), and render/schema.clj whole as
  `fr.render.schema`."
  []
  (let [error-text (slurp (str root "src/seon/error.clj"))
        start (str/index-of error-text ";;; THE REPLY")
        end (str/index-of error-text ";;; SCHEMA PAIRS AS REPL ENTRIES")]
    (eval-in! '(ns fr.error
                 (:require [clojure.string :as str] [malli.core :as m] [malli.generator :as mg] [malli.registry :as mr]
                           [malli.util :as mu]
                           [seon.db :as db] [seon.render.value :as render.value] [seon.repl :as repl]))
              (str "(def ^:private rendered-error-value @#'seon.error/rendered-error-value)\n"
                   "(def ^:private fact-source @#'seon.error/fact-source)\n"

                   (subs error-text start end))))
  (eval-in! '(ns fr.render.schema)
            ;; seon.id/printed is public from f3b05eb31; an older installed
            ;; archive prints the link identity with pr-str
            (-> (slurp (str root "src/seon/render/schema.clj"))
                (str/replace-first "(ns seon.render.schema" "(ns fr.render.schema-copy")
                (cond-> (:private (meta (resolve 'seon.id/printed)))
                  (str/replace "(id/printed " "(pr-str "))))
  ;; seon.db's evaluation-time stored-data check, its private helpers referred
  (let [src (slurp (str root "src/seon/db.clj"))
        grab (fn [head] (let [i (str/index-of src head)]
                          (read (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (subs src i))))))]
    (when (find-ns 'fr.db) (remove-ns 'fr.db))
    (binding [*ns* (create-ns 'fr.db)]
      (refer-clojure)
      (require '[malli.core :as m] '[datahike.api :as d] '[datahike.db.interface :as dbi])
      (doseq [v '[diagnostic write-entity-value carried-projection db read-refusal? database-value?]]
        (intern *ns* v @(ns-resolve 'seon.db v)))
      (doseq [head ["(def ^:private stored-check-limit" "(defn stored-value-check" "(defn registration-check"]]
        (eval (clojure.walk/postwalk-replace {'*read-database* 'seon.db/*read-database* '*conn* 'seon.db/*conn*}
                                             (grab head))))))
  (eval-in! '(ns fr.render.schema) "(def usage fr.render.schema-copy/usage) (def render-ai fr.render.schema-copy/render-ai) (def render-html fr.render.schema-copy/render-html)")
  :copied)

(defn- impl [sym]
  (let [installed {'reply-value 'seon.error/reply-value 'render-ai 'seon.error/render-ai
                   'reply-html 'seon.error/reply-html 'rendered-reply 'seon.error/rendered-reply
                   'usage 'seon.render.schema/usage 'schema-ai 'seon.render.schema/render-ai
                   'schema-html 'seon.render.schema/render-html
                   'registration-check 'seon.db/registration-check}
        copied {'reply-value 'fr.error/reply-value 'render-ai 'fr.error/render-ai
                'reply-html 'fr.error/reply-html 'rendered-reply 'fr.error/rendered-reply
                'usage 'fr.render.schema/usage 'schema-ai 'fr.render.schema/render-ai
                'schema-html 'fr.render.schema/render-html
                'registration-check 'fr.db/registration-check}]
    @(resolve (get (if (installed?) installed copied) sym))))

(defn- floor-text
  "`value` printed by the floor under the world's profile (what the reply shows)."
  [w value]
  (let [projection ((requiring-resolve 'seon.render.value/prepare)
                    {:seon.render/value value :seon.db/db (:seon.db/db w)
                     :seon.render/profile (:seon.render/profile w)
                     :seon.render.value/options {:seon.render.value/structural? true}
                     :seon.render.call/id [:proof]}
                    :seon.render/ai)]
    (:seon.render.value/text projection)))

;;; --- Error cases -------------------------------------------------------------

(def setup
  "The agent's program for the error cases: contracted functions in scratch.fbr."
  (str "(ns scratch.fbr)\n"
       "(defn area \"Area of a rectangle.\" {:malli/schema [:=> [:cat [:map [:scratch.fbr/w [:int {:min 0}]] [:scratch.fbr/h [:int {:min 0}]]]] :int]} [{:scratch.fbr/keys [w h]}] (* w h))\n"
       "(defn total-area \"Sum of the rectangles' areas.\" {:malli/schema [:=> [:cat [:vector [:map [:scratch.fbr/w [:int {:min 0}]] [:scratch.fbr/h [:int {:min 0}]]]]] :int]} [rects] (reduce + (map area rects)))\n"
       "(defn ratio \"Ratio of two counts.\" {:malli/schema [:=> [:cat :int :int] :any]} [a b] (/ a b))\n"))

(def error-cases
  "Each case: the agent's form, and the form an agent writes from the reply
  alone (nil: the reply offers `:seon.error/fix-form`, which is evaluated)."
  {:contract-nested {:form "(total-area [{:scratch.fbr/w 2 :scratch.fbr/h 3} {:scratch.fbr/w 1 :scratch.fbr/h \"4\"}])"}
   :contract-missing-key {:form "(area {:scratch.fbr/w 2})"}
   :first-party-contract {:form "(my.program/callers {:seon.program/subject \"seon.error/reply-value\"})"}
   :exception {:form "(ratio 3 0)" :fix "(ratio 3 1)"}
   :unresolved {:form "(aera {:scratch.fbr/w 2 :scratch.fbr/h 3})" :fix "(area {:scratch.fbr/w 2 :scratch.fbr/h 3})"}
   :missing-entity {:form "(my.program/callers {:seon.program/subject 'seon.error/replyvalue})"
                    :fix "(my.program/callers {:seon.program/subject 'seon.error/reply-value})"}
   :writer-refusal {:form "(seon.db/transact! [{:seon.task/id \"fbr-task\" :seon.task/intent 5}])"}
   :removal-refusal {:form "(my.program/ns-unmap! 'seon.error/evidence-path)"}
   :schema-admission {:form "(seon.schema/register! :scratch.fbr/rect2 [:map [:scratch.fbr/w :scratch.fbr/nope]])"}
   :schema-nilable {:form "(seon.schema/register! :scratch.fbr/maybe-n [:maybe :int])"
                    :fix "(seon.schema/register! :scratch.fbr/maybe-n :int)"}})

(def ^:private example-fixes
  "The fix an agent writes from the reply's path, expectation and example."
  {:contract-nested "(total-area [{:scratch.fbr/w 2 :scratch.fbr/h 3} {:scratch.fbr/w 1 :scratch.fbr/h 4}])"
   :contract-missing-key "(area {:scratch.fbr/w 2 :scratch.fbr/h 3})"
   :first-party-contract "(my.program/callers {:seon.program/subject 'seon.error/reply-value})"})

(defn- fix-text [_reply case k]
  (or (:fix case) (get example-fixes k)))

(defn error-case
  "One error case through both entrants: today's shown reply, the landed
  reply (AI text and HTML), and the fix evaluated from the reply alone."
  [exec k]
  (let [case (get error-cases k)
        text (str setup (:form case))
        mcp (mcp-batch (world exec) text)
        turn (agent-batch (world exec) text)
        w (world exec)
        error (error-of mcp)
        [reply ms] (ms-of #((impl 'reply-value) (:seon.db/db w) error))
        after (floor-text w reply)
        after-turn (floor-text w ((impl 'reply-value) (:seon.db/db w) (error-of turn)))
        face ((impl 'render-ai) {:seon.render/value error :seon.db/db (:seon.db/db w)})
        html ((impl 'reply-html) reply)
        fix (fix-text reply case k)
        fixed (when fix (mcp-batch (world exec) (str setup fix)))]
    {:before (:seon.batch/shown (last-entry mcp))
     :before-tokens (toks (:seon.batch/shown (last-entry mcp)))
     :entrants-agree? (= (:seon.batch/shown (last-entry mcp)) (:seon.batch/shown (last-entry turn)))
     :after after
     :after-tokens (toks after)
     :after-ms ms
     :after-entrants-agree? (= after after-turn)
     :face-is-data? (or (map? face) (string? face))
     :html-nodes (count (filter vector? (tree-seq vector? seq html)))
     :html html
     :fix fix
     :fix-works? (when fixed (not (:seon.batch/error (last-entry fixed))))
     :fix-shown (when fixed (:seon.batch/shown (last-entry fixed)))}))

;;; --- Schema registrations -----------------------------------------------------

(def schema-cases
  "Registrations the schema render judges: new, changed, and one that stored
  entities fail."
  {:new "(seon.schema/register! :scratch.fbr/width [:int {:min 0}])"
   :changed-compatible "(seon.schema/register! :seon.task/intent [:string {:min 1 :max 20000}])"
   :changed-refusing-stored "(seon.schema/register! :seon.task/intent [:string {:min 400}])"
   :unstorable "(seon.schema/register! :scratch.fbr/rect3 [:map [:w :int]])"})

(defn schema-case
  "One registration through both entrants: today's shown reply and the
  landed render of the registration both ways."
  [exec k]
  (let [text (str "(ns scratch.fbr)\n" (get schema-cases k))
        ;; the render reads what the evaluation's print reads: the basis,
        ;; before the batch's own settlement
        w (world exec)
        mcp (mcp-batch (world exec) text)
        turn (agent-batch (world exec) text)
        registration (let [v (:seon.batch/value (last-entry mcp))]
                       (if (map? v) v
                           ;; today register! returns the key: rebuild what the landed one returns
                           (let [form (read-string (get schema-cases k))]
                             {:seon.schema/key (second form) :seon.schema/definition (nth form 2)})))
        unit {:seon.render/value registration :seon.db/db (:seon.db/db w)
              :seon.render/profile (:seon.render/profile w)}
        check (binding [db/*read-database* (:seon.db/db w)]
                ((impl 'registration-check)
                 (:seon.schema/key registration) (:seon.schema/definition registration)))
        unit (cond-> unit (some? check) (assoc-in [:seon.render/value :seon.schema.usage/check] check))
        [selected ms] (ms-of #((impl 'schema-ai) unit))
        after (floor-text w selected)
        html ((impl 'schema-html) unit)]
    {:before (:seon.batch/shown (last-entry mcp))
     :before-tokens (toks (:seon.batch/shown (last-entry mcp)))
     :entrants-agree? (= (:seon.batch/shown (last-entry mcp)) (:seon.batch/shown (last-entry turn)))
     :notes (:seon.batch/notes (last-entry mcp))
     :after after
     :after-tokens (toks after)
     :after-ms ms
     :html-nodes (count (filter vector? (tree-seq vector? seq html)))
     :html html}))

(defn proofs
  "The map of named proofs, each case on its own disposable branch."
  []
  (when-not (installed?) (copy-landed!))
  {:installed? (installed?)
   :errors (into (sorted-map) (map (fn [k] [k (on-branch #(error-case % k))])) (keys error-cases))
   :schemas (into (sorted-map) (map (fn [k] [k (on-branch #(schema-case % k))])) (keys schema-cases))})

(defn summary
  "Tokens, ms and verdicts per case, without the texts."
  [p]
  (into (sorted-map)
        (for [[group cases] (select-keys p [:errors :schemas]) [k v] cases]
          [[group k] (select-keys v [:before-tokens :after-tokens :after-ms :entrants-agree? :after-entrants-agree? :fix-works? :html-nodes])])))

(defn pp "Pretty-printed text." [x] (with-out-str (pprint/pprint x)))
