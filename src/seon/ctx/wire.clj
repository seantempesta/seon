(ns seon.ctx.wire
  "The model's request read from the agent's one render tree (seon.render/wire), its system text, and a past turn's request made again at its basis (docs/render.md#the-models-context-a-cached-prefix-then-the-turn)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render :as render]
            [clojure.data.json :as json]
            [seon.db :as pdb]
            [seon.print.select :as select]
            [seon.doc :as doc]
            [seon.config :as cfg]
            [seon.model :as model]
            [seon.agent :as agent]
            [seon.ctx :as ctx]))

(declare render)

(def help-page
  "The help page, docs/agent.md: what `doc` answers of an anchor, each section on demand. Main holds
  it as the wiki's sections from root's boot on (seon.root/start!)."
  "docs/agent.md")

(defn help-text
  "The help page as db holds it (seon.doc/page-text). A db without it (no root booted on it) is
  refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (doc/page-text db help-page))

(def system-page
  "The system prompt, docs/prompt.md: what every agent reads first (its system message, the same
  bytes for every agent and turn, so a provider caches it): the standard format's short prompt
  (the owner's ruling: the model's context is the standard format; the help page is read on
  demand, doc). Main holds it as the wiki's sections, as the help page."
  "docs/prompt.md")

(defn system-text
  "The system prompt as db holds it (seon.doc/page-text): a turn's, at the commit it read. A db
  without it is refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (doc/page-text db system-page))

(defn agent-system-text
  "The system message of agent record c on db: the doc sections its :system join names, each its heading at its level and its text, in the order the wiki was written; with none, the
  system prompt (`system-text`). See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :seon.host/db :map] :string]}
  [db c]
  (if-let [es (seq (keys (:system c)))]
    (->> (map #(d/pull db [:seon.doc/address :seon.doc/heading :seon.doc/level :seon.doc/text :seon.doc/position] %) es)
         ;; page by page, each as the wiki wrote it: a position is its page's
         (sort-by (juxt #(first (str/split (:seon.doc/address %) #"#")) :seon.doc/position))
         (map (fn [{:seon.doc/keys [heading level text]}] (cond-> (str (apply str (repeat level "#")) " " heading) text (str "\n\n" text))))
         (str/join "\n\n"))
    (system-text db)))

(defn basis-value
  "The value eval e names by its basis (:seon.eval/basis, @ctx's: the record of e's agent on branch db as of t,
  seon.ctx/build), drawn by the schema e kept: what the eval answered, rebuilt from what exists, since no copy of the
  session is stored at each @ctx. A value as of t never changes: its reads are not the reader's (no later transaction
  touches them), so it is built when asked and never because the branch moved. See docs/render.md#a-render-is-a-value-printed-once."
  {:malli/schema [:=> [:cat :seon.host/db :map] :map]}
  [db e]
  (let [{:keys [t branch]} (:seon.eval/basis e)
        ;; the agent of the eval's record, else (an eval not yet recorded, whose text is made first) the one this process is
        a (or (d/q '[:find ?a . :in $ ?id :where [?e :seon.eval/id ?id] [?e :seon.eval/agent ?a]] db (:seon.eval/id e))
              (pdb/eid db :seon.agent/key (:key @agent/state)))]
    (with-meta (assoc (:tree (binding [datahike.index.interface/*reads* nil] (ctx/build (d/as-of db t) a branch))) :t t)
      {:seon.render/schema (:seon.eval/schema e)})))

(defn wire-id
  "The id eval id goes by on the wire: made from its own id, so two evals never share one, whatever a provider called them (a provider
  may reuse its ids across replies, which reads as one call answered twice). See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :string] :string]}
  [id] (str "eval-" (str/replace id "/" "-")))

(defn result-mark
  "Eval e's mark as the tree carries it (:wire/result): the record itself, printed only when the model's reading reaches it (seon.render/result-text), and, for an eval that names its basis, how its value is rebuilt (`basis-value`). See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] :map]}
  [db e] (cond-> {:record (-> e (assoc :seon.eval/response (pdb/response-of db e)) (dissoc :seon.eval/result))} (:seon.eval/basis e) (assoc :value-of #'basis-value)))

(defn tool-text
  "What came of eval e as the model reads it, the same printing a request's reading does (seon.render/result-text): for a reader of its text outside the tree. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] :string]}
  [db e] (render/result-text db (result-mark db e)))

(def render-calls
  "What Seon runs to show an agent its page, each [intent code]: :whole, the page whole (an agent's first eval, and an outside
  read's last), and :changes, the parts that moved since it was last shown. The intent reads true for the model and for a person
  on the page, so it speaks of the agent. See docs/render.md#the-render-is-an-eval."
  {:whole ["The agent's page: its task and how its proofs stand."
           "(my.render/render-ctx)"]
   :changes ["What changed on the agent's page."
             "(my.render/render-changes)"]})

(defn- render-eval?
  "Is eval e (an eval record) one of the page's renders, whole or what changed (`render-calls`)?"
  {:malli/schema [:=> [:cat :map] :boolean]}
  [e] (boolean (some #(= (:seon.eval/code e) (second %)) (vals render-calls))))

(defn at-t
  "Branch db as of t, as a page is drawn: the earliest commit of its line that holds t, a real db value, found by walking the line back from db at the cost of the commits since; past what collection kept, the same datoms as of t, which is all a status can't do a diff of (docs/storage.md#collection). See docs/storage.md#collection."
  {:malli/schema [:=> [:cat :seon.host/db :int] :seon.host/db]}
  [db t]
  (loop [at db]
    (let [id (first (filter uuid? (get-in at [:meta :datahike/parents])))]
      (if (or (nil? id) (= t (:max-tx at)))
        at
        (let [p (d/commit-as-db (:store db) id)]
          (cond
            (nil? p) (d/as-of db t)
            (>= (:max-tx p) t) (recur p)
            :else at))))))

(defn request
  "The agent's request, a reading of walk w, the one tree its page draws (docs/render.md#the-models-context-a-cached-prefix-then-the-turn): its conversation's list through the one generic conversion
  (seon.render/wire), so the request is what the tree holds, a re-eval in its own place. A request whose records hold no render of the page (an observer's context read, the first move a judge scores) ends
  with the page whole as a call of its own and its result, when the tree holds a page to show. Answers {:messages :parts}: provider-neutral messages (seon.model/wire-messages makes the wire shape), and :parts, each
  [ref messages turn] in order (what the debug page labels its messages by): ref [:section address] the system message, [:example key t], [:session id], [:eval id], [:message id], [:reply id] or [:render]. A tree that draws no system message is refused, :seon.ctx/no-system-message, never sent without one."
  {:malli/schema [:=> [:cat :map] [:map [:messages [:vector :seon.model/message]] [:parts [:vector :any]]]]}
  [w]
  (let [ps (render/wire w)
        ;; a conversation's other defects reach the model as what threw, in their place; a request with no system message is no request
        _ (when-not (= "system" (:role (first (second (first ps)))))
            (throw (ex-info "the request has no system message"
                            (contract/error :seon.ctx/no-system-message #'request "the request has no system message: its component drew none (a db without the prompt, or a section it links gone)"
                                            {:first (some-> (first ps) second first (select-keys [:role :content]))}))))
        ;; its own: an example session's render of its page shows the agent nothing of its own
        shown? (some (fn [[r ms]] (when-not (= :example (first r)) (some (fn [m] (some #(render-eval? {:seon.eval/code (:code %)}) (:tool-calls m))) ms))) ps)
        lists (render/page-lists w)
        parts (when (and (not shown?) (seq lists)) (not-empty (render/placed-hiccup w lists)))
        ps (cond-> ps
             parts (into (let [[_ code] (:whole render-calls) id (wire-id "render")
                               record {:seon.eval/schema :seon.render/parts :seon.eval/response {:value (render/parts-value parts)}}]
                           (render/hiccup-wire [:li {:wire/role "assistant" :wire/call-id id :wire/ref [:render] :wire/result {:record record}}
                                                [:pre {:wire/field "code"} code]]))))]
    {:messages (into [] (mapcat second) ps) :parts ps}))

(defn messages-tokens
  "Messages ms in tokens, by the printer's sizer over their contents and their calls' code, sized
  together (the sizer's estimate is not additive across pieces): how sent-split and a turn's
  cache check (seon.turn/cache-miss) apportion a request. It reads each part's length and copies
  no text, so it costs its parts, not their characters."
  {:malli/schema [:=> [:cat [:sequential :map]] nat-int?]}
  [ms] (select/chars-tokens (transduce (comp (mapcat (fn [m] (cons (:content m) (map :code (:tool-calls m))))) (map #(count (str %)))) + 0 ms)))

(defn render
  "What a turn's reader got, from its basis b (seon.turn records it on the turn's model call, :seon.model-call/basis) on db,
  the commit b names: the request (`request`) of b's :agent, how its work stands among it (the page's evals, `status` in the first). The one maker of a context: the model's request is this, and so is every
  later reading of it. opts :walk, the agent's live walk, drawn to db (else the tree drawn cold, of opts :ctx, its @ctx at db, or of its record there). {:messages :parts} (`request`)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.model-call/basis] [:map [:messages [:vector :seon.model/message]] [:parts [:vector :any]]]]
                  [:=> [:cat :seon.host/db :seon.model-call/basis :map] [:map [:messages [:vector :seon.model/message]] [:parts [:vector :any]]]]]}
  ([db basis] (render db basis {}))
  ([db {:keys [agent]} {:keys [walk ctx]}]
   (if walk
     (request walk)
     (let [t (or ctx (ctx/agent-ctx db (pdb/eid db :seon.agent/key agent)))
           w (render/draw t ctx/changes db (render/declared-var :seon.render/render-ctx) {:model-only true :once true})]
       (try (request w) (finally (render/dispose-walk! w)))))))

(defn provider-of
  "The model provider a turn with basis b used, from db at b's commit: main as the agent last heard it
  (its :seon.agent/main-commit; the branch itself before it heard any), the task's own settings and the
  run's model (seon.config/provider-at). The turn calls it at its basis and a replay at the same commit, so
  both name one provider. See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:=> [:cat :seon.host/db :seon.model-call/basis] :seon.model/provider]}
  [db {:keys [agent task model]}]
  (let [main (some->> (:seon.agent/main-commit (d/entity db [:seon.agent/key agent])) (d/commit-as-db (pdb/store-of db)))]
    (cfg/provider-at (or main db) db task model)))

(defn wire-turn
  "Turn basis b's request as it went on the wire, made again from the records at b's commit (one `render`):
  {:body :provider :messages :sources :new}: the body seon.model/request-body made of its messages under its provider
  (`provider-of`), :sources, for each of the body's messages in order the refs of the records that made it (seon.model/wire-groups
  folds a reply's calls into one message), and :new, whether each message is of a record the call before it did not hold: one of
  a turn after since, that call's number (nil: none is new), or the page it ended with. Drawn with today's renders: the debug page
  compares :messages with what the turn hashed (:seon.model-call/sent-hash). See docs/render.md#the-debug-page-is-the-wire-request."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.model-call/basis] [:map [:body :map] [:provider :seon.model/provider] [:messages [:vector :seon.model/message]] [:sources [:vector [:vector :any]]] [:new [:vector :boolean]]]]
                  [:=> [:cat :seon.host/db :seon.model-call/basis [:maybe nat-int?]] [:map [:body :map] [:provider :seon.model/provider] [:messages [:vector :seon.model/message]] [:sources [:vector [:vector :any]]] [:new [:vector :boolean]]]]]}
  ([db basis] (wire-turn db basis nil))
  ([db basis since]
   (let [{:keys [messages parts]} (render db basis)
         p (provider-of db basis)
         ;; of a turn after since, or since's own but for the messages it took, which that request read
         fresh? (fn [[ref _ n]] (boolean (and since (or (= [:render] ref) (and n (or (> n since) (and (= n since) (not= :message (first ref)))))))))
         ;; each neutral message's ref and whether it is new, in order, then each wire message's by the one fold
         per (into [] (mapcat (fn [[ref ms :as part]] (repeat (count ms) [ref (fresh? part)]))) parts)
         groups (model/wire-groups messages)]
     {:body (model/request-body p {:messages messages :task (:task basis) :turn (:turn basis)})
      :provider p
      :messages messages
      :sources (mapv (fn [is] (vec (distinct (map #(first (nth per %)) is)))) groups)
      :new (mapv (fn [is] (boolean (some #(second (nth per %)) is))) groups)})))

(defn read-text
  "A read of messages, the request basis b makes on db, as an outside agent is answered it: the system prompt, then each record as the call made and its result; wire?: the request body as JSON, the bytes a provider is sent (`wire-turn`). See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.host/db :seon.model-call/basis [:vector :seon.model/message] :boolean] :string]}
  [db basis messages wire?]
  (if wire?
    (json/write-str (:body (wire-turn db basis)))
    (str/join "\n\n" (remove str/blank? (mapcat (fn [{:keys [content tool-calls]}] (concat (map #(str (:intent %) "\n" (:code %)) tool-calls) [content])) messages)))))
