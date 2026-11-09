(ns seon.ctx.wire
  "The model's request read from the agent's one render tree (seon.render/wire), its system text, and a past turn's request made again at its basis (docs/render.md#the-models-context-a-cached-prefix-then-the-turn)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]]
            [clojure.string :as str] [clojure.walk]
            [datahike.api :as d]
            [seon.render :as render] [seon.program :as program]
            [clojure.data.json :as json]
            [seon.db :as pdb]
            [seon.print :as print]
            [seon.print.select :as select]
            [seon.doc :as doc]
            [seon.config :as cfg]
            [seon.model :as model]
            [seon.agent :as agent]
            [seon.ctx :as ctx]
            [seon.ctx.session :as session]
            [seon.ratom :as ratom]))

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

(defn system-text
  "The system prompt as db holds it (seon.doc/page-text): a turn's, at the commit it read. A db
  without it is refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (doc/page-text db doc/system-page))

(defn agent-system-text
  "The system message of agent record c on db: the doc sections its :seon.agent/system join names and every section below each, each its heading at its level and its text, in the order the wiki was written; with none, the
  system prompt (`system-text`). See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :seon.host/db :map] :string]}
  [db c]
  (if-let [es (seq (distinct (mapcat #(doc/subtree db %) (keys (:seon.agent/system c)))))]
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
  {:malli/schema [:=> [:cat :map :seon.host/db :map] :map]}
  [ix db e]
  (let [{:keys [t branch]} (:seon.eval/basis e)
        ;; the agent of the eval's record, else (an eval not yet recorded, whose text is made first) the one this process is
        a (or (d/q '[:find ?a . :in $ ?id :where [?e :seon.eval/id ?id] [?e :seon.eval/agent ?a]] db (:seon.eval/id e))
              (pdb/eid db :seon.agent/key (:key @agent/state)))]
    (with-meta (assoc (:tree (binding [datahike.index.interface/*reads* nil] (ctx/build ix (d/as-of db t) a branch))) :t t)
      {:seon.render/schema (:seon.eval/schema e)})))

(defn result-mark
  "Eval e's mark as the tree carries it (:wire/result): the record itself, printed only when the model's reading reaches it (seon.render/result-text), and, for an eval that names its basis, how its value is rebuilt (`basis-value`). See
  docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] :map]}
  [db e] (cond-> {:record (-> e (assoc :seon.eval/response (pdb/response-of db e)) (dissoc :seon.eval/result))} (:seon.eval/basis e) (assoc :value-of #'basis-value)))

(defn tool-text
  "What came of eval e as the model reads it, the same printing a request's reading does (seon.render/result-text): for a reader of its text outside the tree. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map [:maybe :seon.host/db] :map] :string]}
  [ix db e] (render/result-text ix db (result-mark db e)))

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

(defn- page-forms
  "The page walk w draws, {id form} in placed order (seon.render/placed-hiccup over its page-lists)."
  {:malli/schema [:=> [:cat :map] :map]}
  [w] (render/placed-hiccup w (render/page-lists w)))

(defn- db-at
  "The db of point p ({:branch :commit :t}) on db's store: its commit, else (a commit past what collection kept, docs/storage.md#collection) db as of its t."
  {:malli/schema [:=> [:cat :seon.host/db :map] :seon.host/db]}
  [db p] (or (d/commit-as-db (pdb/store-of db) (:commit p)) (d/as-of db (:t p))))

(defn diffs-through
  "The edits between each consecutive pair of chain, a vector of points ({:branch :commit :t}, in order on one branch; a first of nil: nothing before the second) of agent a, one per pair, [(p0 to p1) (p1 to p2) ...], of db's store, with today's renders (`seon.render/page-edits` over `seon.render/forms-moved` of the page at each). One walk across them: drawn once at the first point the chain names, then advanced through each later one (seon.ctx/moved, seon.ctx/patch, the moved components flushed, as a kept walk moves, docs/render.md#a-page-is-a-kept-walk), so the chain costs one draw and what moved between the points, never a draw a pair. Pure in its arguments, and the one place a page is drawn at a point. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :seon.host/db :int [:vector [:maybe :map]]] [:vector :any]]}
  [db a chain]
  (let [nothing? (nil? (first chain)) pts (vec (remove nil? chain))
        start (first pts) branch (:branch start)
        db0 (db-at db start)
        ix (program/index-of-db db0)
        c0 (assoc-in (ctx/build ix db0 a branch) [:ix :own-index?] true)
        layout (render/layout-of ix (assoc (:tree c0) :seon.agent/key (:seon.agent/key (d/entity db0 a))))
        at (volatile! db0) r (ratom/atom (:tree c0))
        w (assoc (render/walker {:ref r :changes ctx/changes} (fn [] @at) [layout {}] ix) :model-only true)]
    (try
      (render/flush! w nil)
      (let [f0 (page-forms w)]
        (loop [c c0 prev f0 ps (rest pts) out (if nothing? [(render/page-edits ix (render/forms-moved {} f0))] [])]
          (if-let [p (first ps)]
            (let [db1 (db-at db p)
                  moved (ctx/moved @at db1 nil)
                  c1 (ctx/patch c db1 moved (:branch p))]
              (vreset! at db1)
              (reset! r (:tree c1))
              (render/flush! w moved)
              (let [f1 (page-forms w)]
                (recur c1 f1 (rest ps) (conj out (render/page-edits (:index (:ix c1)) (render/forms-moved prev f1))))))
            out)))
      (finally (render/dispose-walk! w)))))

(defn points-of
  "The points of agent a's turns on db, [{:branch :commit :t}] in turn order: each model call's basis (:seon.model-call/basis, its :commit and :t, the t read from the commit when the basis holds none) with the branch. What `seon.render/changes-of` chains its pairs along. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :seon.host/db :int :any] [:vector :map]]}
  [db a branch]
  (->> (d/q '[:find ?n ?b :in $ ?a :where [?c :seon.model-call/agent ?a] [?c :seon.model-call/n ?n] [?c :seon.model-call/basis ?b]] db a)
       (sort-by first)
       (mapv (fn [[_ b]] {:branch branch :commit (:commit b) :t (or (:t b) (:max-tx (d/commit-as-db (pdb/store-of db) (:commit b))))}))))

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
             parts (into (let [[_ code] (:whole render-calls) id "render"
                               record {:seon.eval/schema :seon.render/parts :seon.eval/response {:value (render/parts-value parts)}}]
                           (render/hiccup-wire @(:index w) [:li {:wire/role "assistant" :wire/call-id id :wire/ref [:render] :wire/result {:record record}}
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
  later reading of it. opts :walk, the agent's live walk, drawn to db (else the tree drawn cold, of opts :ctx, its @ctx at db, or of its record there, its schemas read in opts :index, the agent's held branch index, else built from db). {:messages :parts} (`request`)."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.model-call/basis] [:map [:messages [:vector :seon.model/message]] [:parts [:vector :any]]]]
                  [:=> [:cat :seon.host/db :seon.model-call/basis :map] [:map [:messages [:vector :seon.model/message]] [:parts [:vector :any]]]]]}
  ([db basis] (render db basis {}))
  ([db {:keys [agent]} {:keys [walk ctx index]}]
   (if walk
     (request walk)
     (let [ix (or index (program/index-of-db db))
           t (or ctx (ctx/agent-ctx ix db (pdb/eid db :seon.agent/key agent)))
           w (render/draw t ctx/changes db (render/layout-of ix (merge {:seon.agent/key agent} t)) {:model-only true :once true :index ix})]
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

;; ---- model renders: an entity of each schema as the model reads it (stage 2b, docs/users.md#the-stages-agreed-2026-11-09) ----
;; Each is (f {:seon.db/db db} entity): a vector of :seon.model/message values and the entities it places, in its order; the one
;; walk runs it in the entity's component beside its hiccup render and puts each placed entity's own output where it stands.

(defn- reply-of
  "The reply number model eval e belongs to: its model call's :seon.model-call/n, read by its :seon.eval/call ref on db, when positive."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:maybe pos-int?]]}
  [db e] (some->> (:seon.eval/call e) :db/id (d/entity db) :seon.model-call/n (#(when (pos? %) %))))

(defn eval-wire
  "An eval (:seon.db/eval-fact) as the model reads it: the model's call (its code, and its intent when it said one) and the
  tool message its result is, printed by the one printer, within its budget, or whole once a person opened it (:seon.eval/opened); an eval of another hand, one user message of who ran what and what
  came of it. See docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db] [:seon.render/index :map]] :map] [:vector :seon.model/message]]}
  [{db :seon.db/db ix :seon.render/index} {:seon.eval/keys [id by code intent call-id opened] :as e}]
  (let [mark (cond-> (result-mark db e)
               ;; opened by a person: the model reads it whole, as the person does (owner, 2026-11-09: "Model reads it too")
               opened (update-in [:record :seon.eval/response] vary-meta assoc :seon.print/full true))
        said? (not (str/blank? intent))]
    (if (or call-id said?)
      [(cond-> {:role "assistant" :content "" :tool-calls [(cond-> {:id id :code code} said? (assoc :intent intent))]}
         (and call-id (reply-of db e)) (assoc :reply (reply-of db e)))
       {:role "tool" :tool-call-id id :content (render/result-text ix db mark)}]
      [{:role "user" :content (render/result-text ix db (assoc mark :with (array-map :by by :code code)))}])))

(defn message-wire
  "A message to the agent (:seon.message/fact) as the model reads it: one user message, who sent it and its body. See
  docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09."
  {:malli/schema [:=> [:cat :map :map] [:vector :seon.model/message]]}
  [_ {:seon.message/keys [from body]}]
  [{:role "user" :content (print/text (array-map :from (str from) :body (str body)) (:context print/budgets))}])


(defn- presentation-free
  "Hiccup h without what is the page's alone: each element's class, style and id (seon.render's part-form leaves them out the same way)."
  {:malli/schema [:=> [:cat :any] :any]}
  [h] (clojure.walk/postwalk (fn [x] (if (and (vector? x) (keyword? (first x)) (map? (second x)))
                                       (let [a (dissoc (second x) :class :style :id)] (if (seq a) (assoc x 1 a) (into [(first x)] (nnext x))))
                                       x))
                             h))

(defn- page-parts-at
  "Agent a's page parts at basis b on db's store, as the model reads them: the agent pulled at b's commit by what the parts
  render reads (its :seon.ctx/pattern, not the agent's whole pattern), rendered by its
  page-parts render (seon.render.ui/agent-parts, named here by symbol: seon.render.ui requires this namespace) in one pass
  (seon.render/render-pull, the hiccup output alone, so no model render nests) with the index of the point's own schemas,
  and stripped of presentation. The branch is the db's own (Datahike's config)."
  {:malli/schema [:=> [:cat :seon.host/db :int :map] :any]}
  [db a b]
  (let [d (db-at db {:commit (:commit b) :t (or (:t b) (:max-tx (d/commit-as-db (pdb/store-of db) (:commit b))))})
        branch (get-in (or (:origin-db d) d) [:config :branch])]
    (let [parts (requiring-resolve 'seon.render.ui/agent-parts)
          ix (program/index-of-db d)]
      (presentation-free (:hiccup (render/render-pull (:tree (ctx/build ix d a branch (:seon.ctx/pattern (meta parts)))) d parts ix #{:seon.render/hiccup}))))))

(defn page-message
  "The user message Seon writes the model before its turn n: `<page from=\"seon\" turn=\"n\">`, the edits that take the
  agent's page parts from its turn before to this one printed by the one printer, then `</page>`; nothing when nothing moved.
  Its bytes are a function of n and the edits: no point, commit or t is in it. See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :map :int :any] [:vector :seon.model/message]]}
  [ix n edits]
  (if (empty? edits)
    []
    [{:role "user" :content (str "<page from=\"seon\" turn=\"" n "\">\n"
                                 (render/result-text ix nil {:record {:seon.eval/schema :seon.render/parts
                                                                   :seon.eval/response {:value (with-meta {:edits edits} {:seon.render/schema :seon.render/parts :seon.print/full true})}}})
                                 "\n</page>")}]))

(defn page-diff-wire
  "What moved of agent a's page parts between basis p0 (nil: nothing before) and basis p1, as the page message of turn n
  (`page-message`): each point's parts rendered (`page-parts-at`) and compared by seon.render/page-edits over forms-moved,
  read from the db, never stored. Placed by the agent's model render before turn n's call, [#'page-diff-wire {} a p0 p1 n],
  so the walk keeps each pair as a component keyed by its points and a new turn renders only its own pair. See
  docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db] [:seon.render/index :map]] :int [:maybe :map] :map :int] [:vector :seon.model/message]]}
  [{db :seon.db/db ix :seon.render/index} a p0 p1 n]
  (page-message ix n (render/page-edits ix (render/forms-moved (if p0 {"page" (page-parts-at db a p0)} {}) {"page" (page-parts-at db a p1)}))))

(defn model-call-wire
  "A model call (:seon.model-call/fact) as the model reads it: what the model said and its reasoning. Whether those are sent is
  today's rule, reproduced: a model's reply only (origin :model), when it made calls or was not cut at the token limit, and
  only with words or reasoning to send. Its turn's page message (`page-diff-wire`) and its evals are their own. See
  docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db]] :map] [:vector :seon.model/message]]}
  [{db :seon.db/db} {:seon.model-call/keys [n origin reply reasoning truncated] :as c}]
  (let [called? (boolean (some #(:seon.eval/call-id (d/entity db %)) (keys (:seon.eval/_call c))))
        spoke? (not (str/blank? reply))
        sent? (and (= :model origin) (or called? (and (not truncated) spoke?)) (or spoke? (some? reasoning)))]
    (if sent?
      [(cond-> {:role "assistant" :content (if spoke? reply "")}
         (some? reasoning) (assoc :reasoning reasoning)
         (and n (pos? n)) (assoc :reply n))]
      [])))

(defn- turn-entities
  "Agent record c's turns as the entities the model reads, ordered from its pull with no seek a turn: its model calls by
  number, each call's messages as they were taken (seon.ctx/first-delivered), its page message between the basis of the call
  before that holds one and its own (`page-diff-wire`, a placed call), the call, the call's evals of the model's, then
  those of other hands; a member c no longer holds (unlinked by a swap!) is none. See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]]}
  [db c]
  (let [evals (:seon.eval/_agent c) msgs (:seon.message/_to c)]
    (vec (for [[prev call] (let [cs (map val (sort-by (comp :seon.model-call/n val) (filter (comp :seon.model-call/n val) (:seon.model-call/_agent c))))]
                             (map vector (reductions (fn [b x] (or (:seon.model-call/basis x) b)) nil cs) cs))
               :let [ms (filter #(contains? msgs %) (keys (:seon.message/_call call)))
                     ms (if (next ms) (sort-by #(ctx/first-delivered [db (:db/id c) %]) ms) ms)
                     es (sort (filter #(contains? evals %) (keys (:seon.eval/_call call))))
                     model? #(:seon.eval/call-id (get evals %))]
               x (concat (map msgs ms)
                         (when-let [b (:seon.model-call/basis call)] [[#'page-diff-wire {} (:db/id c) prev b (:seon.model-call/n call)]])
                         [call] (map evals (filter model? es)) (map evals (remove model? es)))]
           x))))

(defn agent-wire
  "An agent (:seon.db/agent-fact) as the model reads it: its system message, from the doc sections :seon.agent/system names
  (only the request's root agent pulls them, so only it has one: a placed example contributes its turns, never an agent
  message), each example session its task names between the messages that open and close it (`seon.ctx.session/example-open`;
  the example's turn entities placed here, as of the example's last t), what opens its own session, then its turns'
  entities (`turn-entities`). The task is read in the page parts alone, never here. See docs/users.md#one-system-of-agents-the-design-agreed-2026-11-09."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db]] :map] [:vector :any]]}
  [{db :seon.db/db} a]
  (let [k (:seon.agent/key a)]
    (-> (if (seq (:seon.agent/system a)) [{:role "system" :content (agent-system-text db a)}] [])
        (into (mapcat (fn [ek] (let [t (session/example-t db ek) dbt (d/as-of db t)
                                     c (ctx/agent-ctx (program/index-of-db dbt) dbt (pdb/eid dbt :seon.agent/key ek))]
                                 (concat [{:role "user" :content (session/example-open ek (:seon.agent/description c))}]
                                         (turn-entities dbt c)
                                         [{:role "user" :content "</session>"}]))))
              (session/session-examples db a))
        (into (when k [{:role "user" :content (session/session-open k)}]))
        (into (turn-entities db a)))))
