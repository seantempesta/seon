(ns seon.ctx.wire
  "The model's request read from the agent's one render tree (seon.render/wire), its system text, and a past turn's request made again at its basis (docs/render.md#the-models-context-a-cached-prefix-then-the-turn)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]]
            [clojure.string :as str] [clojure.walk]
            [datahike.api :as d]
            [seon.render :as render] [seon.program :as program]
            [clojure.data.json :as json]
            [seon.db :as pdb] [seon.task :as task]
            [seon.print :as print]
            [seon.print.select :as select]
            [seon.doc :as doc]
            [seon.config :as cfg]
            [seon.model :as model]
            [seon.agent :as agent]
            [seon.ctx :as ctx]
            [seon.ctx.session :as session]
            [seon.ratom :as ratom] [seon.lib :as lib]))

(declare render)

(def help-page
  "The help page, docs/agent.md: what `doc` answers of an anchor, each section on demand. Main holds
  it as the wiki's sections from root's boot on (seon.system/start!)."
  "docs/agent.md")

(defn help-text
  "The help page as db holds it (seon.doc/page-text). A db without it (no root booted on it) is
  refused by this contract, never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] :string]}
  [db] (doc/page-text db help-page))

(defn system-text
  "The system prompt as db holds it (seon.doc/page-text): a turn's, at the commit it read; nil on a db without it, which the
  request refuses (`request`: :seon.ctx/no-system-message), never answered empty."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :string]]}
  [db] (doc/page-text db doc/system-page))

(defn agent-system-text
  "The system message of agent record c on db: the doc sections its :seon.agent/system join names and every section below each, each its heading at its level and its text, in the order the wiki was written; with none, the
  system prompt (`system-text`), nil when db holds neither. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:maybe :string]]}
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

(defn- db-at
  "The db of point p ({:branch :commit :t}) on db's store: its commit, else (a commit past what collection kept, docs/storage.md#collection) db as of its t."
  {:malli/schema [:=> [:cat :seon.host/db :map] :seon.host/db]}
  [db p] (or (d/commit-as-db (pdb/store-of db) (:commit p)) (d/as-of db (:t p))))

(declare page-diff-msgs example-msgs reply-of)

(defn- part-ref
  "The ref and turn of message m the agent's model output holds, from src, the component whose own answer held it
  (seon.render/answer-sources: {:f :args}): an eval [:eval id] in its reply's turn, a message [:message id] in its call's, a
  reply [:reply id] in its own, a turn's page message [:page n] in turn n, an example session [:example key], and the agent's
  own: its system message [:section addresses], what opens its session [:session key]; any other member the conversation placed
  [schema id] by its entity schema in the entity index ix (seon.render/entity-schema: an entity of a schema the agent registered is labelled as what it is, never as
  the session); turn-of, each placed member's entity id to the turn that read it (seon.ctx/placed-messages). What the debug page labels each message by. See docs/render.md#the-models-context-a-cached-prefix-then-the-turn."
  {:malli/schema [:=> [:cat :map :seon.host/db :map :map :map] [:tuple [:vector :any] [:maybe :int]]]}
  [ix db turn-of {:keys [f args]} m]
  (let [x (some-> (first args) ratom/realize)]
    (cond (= f #'page-diff-msgs) [[:page (nth args 3)] (nth args 3)]
          (= f #'example-msgs) [[:example (first args)] nil]
          (:seon.eval/id x) [[:eval (:seon.eval/id x)] (reply-of db x)]
          (:seon.message/id x) [[:message (:seon.message/id x)] (turn-of (:db/id x))]
          (:seon.model-call/id x) [[:reply (:seon.model-call/id x)] (:seon.model-call/n x)]
          ;; any other entity the output placed, not the agent itself (whose opening is :session): by what it is, its turn when one read it
          (and (:db/id x) (nil? (:seon.agent/key x))) [[(render/entity-schema ix x nil) (:db/id x)] (turn-of (:db/id x))]
          (= "system" (:role m)) [[:section (vec (sort (keys (:seon.agent/system x))))] nil]
          :else [[:session (:seon.agent/key x)] nil])))

(defn request
  "The agent's request, walk w's model output (seon.render/openai-compat: each entity rendered by its schema's model render, in
  the order the agent's render places them; docs/render.md#the-models-context-a-cached-prefix-then-the-turn). Answers
  {:messages :parts}: provider-neutral messages (seon.model/wire-messages makes the wire shape), and :parts, each [ref messages
  turn] in order, the ref of what answered them (`part-ref`), which the debug page labels its messages by. A request with no
  system message is refused, :seon.ctx/no-system-message, never sent without one."
  {:malli/schema [:=> [:cat :map] [:map [:messages [:vector :seon.model/message]] [:parts [:vector :any]]]]}
  [w]
  (let [db ((:db w))
        c @(:ref (:src w))
        turn-of (into {} (for [[n ps] (when (:db/id c) (ctx/placed-messages @(:index w) c)) :when (int? n) p ps] [(peek p) n]))
        ps (reduce (fn [acc [m src]]
                     (let [[ref n] (part-ref @(:index w) db turn-of src m) [r0 ms n0] (peek acc)]
                       (if (and (seq acc) (= ref r0)) (conj (pop acc) [r0 (conj ms m) n0]) (conj acc [ref [m] n]))))
                   [] (render/answer-sources w :seon.render/openai-compat))
        ms (into [] (mapcat second) ps)]
    (when-not (= "system" (:role (first ms)))
      (throw (ex-info "the request has no system message"
                      (contract/error :seon.ctx/no-system-message #'request "the request has no system message: the agent's render answered none (a db without the prompt, or a section it links gone)"
                                      {:first (some-> (first ms) (select-keys [:role :content]))}))))
    {:messages ms :parts ps}))

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
  ([db {:keys [agent commit] :as basis} {:keys [walk ctx index]}]
   (if walk
     (request walk)
     (let [ix (or index (program/index-of-db db))
           ;; the point this request is made at, which its newest call's basis will name (written after this commit): a db read
           ;; as of a t (a commit collection took) holds no commit id of its own
           t (cond-> (or ctx (ctx/agent-ctx ix db (pdb/eid db :seon.agent/key agent)))
               commit (assoc ::here {:commit commit :t (or (:t basis) (:max-tx db))}))
           w (render/draw t ctx/changes db (render/render-var-of ix (merge {:seon.agent/key agent} t)) {:model-only true :once true :index ix :outputs #{:seon.render/hiccup :seon.render/openai-compat}})]
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
         fresh? (fn [[ref _ n]] (boolean (and since n (or (> n since) (and (= n since) (not (#{:message :page} (first ref))))))))
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

(defn eval-msgs
  "An eval (:seon.db/eval-fact) as the model reads it: the model's call (its code, and its intent when it said one) and the
  tool message its result is, printed by the one printer, within its budget, or whole once a person opened it (:seon.eval/opened); an eval of another hand, one user message of who ran what and what
  came of it. See docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
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
      [{:role "user" :content (render/result-text ix db (assoc mark :with (array-map :by (task/sender-name (ratom/realize by)) :code code)))}])))

(defn message-msgs
  "A message to the agent (:seon.message/fact) as the model reads it: one user message, who sent it (by name: seon.task/sender-name of the
  sender its pull joins) and its body. See
  docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
  {:malli/schema [:=> [:cat :map :map] [:vector :seon.model/message]]}
  [_ {:seon.message/keys [from body]}]
  [{:role "user" :content (print/text (array-map :from (str (task/sender-name (ratom/realize from))) :body (str body)) (:context print/budgets))}])


(defn defect-msgs
  "A component whose model render threw (:seon.render/component-defect, seon.render/render!'s failure point) as the model reads it:
  one user message naming the render that threw and what it said, printed by the one printer, in the place its messages would
  have been, so the model is told its context lost a part, never left to read around a gap. See docs/errors.md#nothing-crashes-and-nothing-hangs."
  {:malli/schema [:=> [:cat :map :map] [:vector :seon.model/message]]}
  [_ {:seon.error/keys [in message]}]
  [{:role "user" :content (print/text (array-map :render-threw (some-> in str) :message (str message)) (:context print/budgets))}])

(defn- presentation-free
  "Hiccup h without what is the page's alone: every element's class and style, its :data-* and :aria-* attributes (what the page's script
  and a screen reader read; :href, :title and :alt stay) and its id, but for a component's root (an element the walk gave an id), which
  keeps its id (seon.render/key-id: the render and what it shows), by which a diff names it (seon.render/keyed), and :render, the
  render that drew it as data, its full symbol (seon.render's with-id), so the model can look up the function that drew a part
  (`(source seon.render.ui/task-view)`); nothing presentational reaches the model; each element's children vectors only (`children`).
  See docs/render.md#style."
  {:malli/schema [:=> [:cat :any] :any]}
  [h]
  ;; an element's children as the tree holds them: a seq a render returned (a `for`) spliced in its place, nils dropped, so the
  ;; model reads vectors only and a diff names each child (seon.render/keyed). A local fn: no contracted call an element
  (letfn [(children [xs] (into [] (comp (mapcat #(if (seq? %) (children %) [%])) (remove nil?)) xs))]
  (clojure.walk/postwalk (fn [x] (if (and (vector? x) (keyword? (first x)) (map? (second x)))
                                       (let [m (second x)
                                             ;; and the page's own marks (:wire/for \"page\"): what the page alone shows says nothing to the model
                                             ;; and what drives the page's script or a screen reader (:data-*, :aria-*): the page's alone too
                                             a (cond-> (into {} (remove (fn [[k _]] (or (#{:class :style :id} k) (= "wire" (namespace k))
                                                                                        (and (nil? (namespace k)) (or (str/starts-with? (name k) "data-") (str/starts-with? (name k) "aria-"))))))
                                                             m)
                                                 (and (:id m) (:render m)) (assoc :id (:id m)))]
                                         (into (if (seq a) [(first x) a] [(first x)]) (children (nnext x))))
                                       (if (and (vector? x) (keyword? (first x))) (into [(first x)] (children (next x))) x)))
                             h)))

(defonce ^:private kept-walk
  ;; the walk page-parts-through drew last, held at the point it reached, to advance from rather than draw again: nil, or
  ;; {:id [store branch agent] :t :commit :at :c :r :w :first}, :first the point its last chain began at with that point's page
  ;; ({:point [commit t] :hiccup}), which goes with the walk. One a process (an agent's process draws one agent's pages); derived, never relied on
  (atom nil))

(defn- descends?
  "Whether commit c, on db's store, is commit k or reaches it within :seon.config.ctx/kept-walk-steps parents (Datahike's commit
  graph records, d/commit-graph-record: a read a step, nil where the store holds none): a value the kept walk can be advanced to
  by what moved since, never a value of another lineage (a reset branch, another world on the same branch name). A nil commit
  (a value no commit names) descends from nothing."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :uuid] [:maybe :uuid]] :boolean]}
  [db k c]
  (let [store (pdb/store-of db) steps (cfg/value :seon.config.ctx/kept-walk-steps)]
    (boolean (and k c (loop [todo #{c} i 0]
                        (cond (contains? todo k) true
                              (or (empty? todo) (>= i steps)) false
                              :else (recur (into #{} (mapcat #(:datahike/parents (d/commit-graph-record store %))) todo) (inc i))))))))

(defn model-parts
  "The parts of entity c's page the model reads, as one component: the lists its page render places (seon.render/render-var-of: its own
  render, else its schema's), anywhere in its output (a layout places them in its columns: docs/render.md#a-layout-places-its-lists),
  that hold no messages and are not for the page alone, as seon.render/page-lists keeps them, in the order the
  render places them; none at a point before the entity was made. The root of the parts walk (`parts-walk`), so a list an entity's own render adds is read by the model as the
  task's parts are. See docs/render.md#an-entity-may-name-its-own-render."
  {:malli/schema [:=> [:cat [:and :seon.render/opts [:map [:seon.ctx/ctx :map] [:seon.db/db {:optional true} :seon.host/db]]] :any] :any]}
  [opts c]
  ;; at a point before the entity was made its pull holds nothing, and nothing draws its page: no parts
  (let [out (if (:db/id (ratom/untracked c)) ((render/render-var-of (:seon.render/index opts) c) opts c) [])
        model? (fn [x] (and (vector? x) (var? (first x))
                            (let [m (meta (first x))]
                              (and (:seon.render/list m) (not (:seon.render/messages m)) (not= "page" (:wire/for m)) (not= "page" (:wire/for (second x)))))))
        ;; the lists, through the layout's own elements, never into a component call's arguments
        lists (fn lists [x] (cond (model? x) [x]
                                  (and (vector? x) (keyword? (first x))) (mapcat lists (rest x))
                                  (seq? x) (mapcat lists x)
                                  :else nil))]
    (into [:div {:id "model-parts"}] (lists out))))

(defn- page-ctx
  "Agent a's record at db d0 on branch, pulled by what its page reads: its own pattern without the conversation's joins (seon.render/page-pattern:
  the model reads those as its messages), the ctx the page parts are drawn over (what the model is shown of @ctx is `ctx-at`, whole)."
  {:malli/schema [:=> [:cat :seon.host/db :int :any] :map]}
  [d0 a branch]
  (let [ix0 (program/index-of-db d0)]
    (assoc-in (ctx/build ix0 d0 a branch (render/page-pattern ix0 (or (render/entity-pattern ix0 (d/pull d0 '[*] a)) []))) [:ix :own-index?] true)))

(defn ctx-at
  "Agent a's record at point p of its branch, on db's store, as data: exactly what `@ctx` held there (seon.ctx/agent-ctx, its own pattern
  whole, its conversation's joins included: the same thing the agent reads, never a filtered copy). What the model is shown whole at
  its first point, before its page (`page-diff-msgs`), and what my.render/ctx answers. See
  docs/ctx.md#what-the-model-reads-and-what-the-page-renders."
  {:malli/schema [:=> [:cat :seon.host/db :int :map] :map]}
  [db a p]
  (let [d0 (db-at db p)] (ctx/agent-ctx (program/index-of-db d0) d0 a)))

(defn- parts-walk
  "Agent a's page-parts walk drawn at db d0 (its branch's own, at t), cold: {:id :t :at :c :r :w}, the walk the model reads (:model-only:
  what is the page's alone is not drawn) over the agent pulled by what the parts read (`page-ctx`)."
  {:malli/schema [:=> [:cat :seon.host/db :int :int [:tuple :any :any :int]] :map]}
  [d0 t a id]
  (let [parts #'model-parts
        c0 (page-ctx d0 a (nth id 1))
        at (volatile! d0) r (ratom/atom (:tree c0))
        w (assoc (render/walker {:ref r :changes ctx/changes} (fn [] @at) [parts {} (ratom/ref-view r ctx/changes)] (:index (:ix c0)))
                 :outputs #{:seon.render/hiccup} :model-only true)]
    (render/flush! w nil)
    {:id id :t t :at at :c c0 :r r :w w}))

(defn- advance
  "Parts walk k advanced to db d1, a later value of its branch at t1: the datoms between its value and d1 (seon.ctx/moved), its ctx
  patched by them and the components they reach flushed (docs/render.md#a-page-is-a-kept-walk). k with its :t and :c moved on."
  {:malli/schema [:=> [:cat :map :seon.host/db :int] :map]}
  [{:keys [at c r w id] :as k} d1 t1]
  ;; the same commit at the same t is the same value: nothing moved, and no diff is asked of the store (one in memory walks its index whole)
  (let [moved (if (and (some? (d/commit-id d1)) (= (d/commit-id @at) (d/commit-id d1)) (= (:max-tx @at) (:max-tx d1))) [] (ctx/moved @at d1 nil))]
    (if (empty? moved)
      (assoc k :t t1)
      (let [c1 (ctx/patch c d1 moved (nth id 1))]
        (vreset! at d1) (reset! r (:tree c1))
        (render/flush! w moved (:index (:ix c1)))
        (assoc k :c c1 :t t1)))))

(defn page-parts-through
  "Agent a's page parts at each point of chain, a vector of points ({:commit :t}, in order on one branch; a first of nil: nothing
  before the second, answered nil), on db's store, as the model reads them: one walk rooted on `model-parts` (the lists of the
  entity's own render, else its schema's, that hold no messages and are not for the page alone) over the agent pulled by what the
  parts read (`parts-walk`), advanced from point to point as a kept walk moves (`advance`: docs/render.md#a-page-is-a-kept-walk),
  each point's hiccup stripped of presentation (`presentation-free`). The walk is kept after the call at the chain's last point
  (`kept-walk`), so a later chain of the same agent on the same branch starting at or after it advances from it: a request at a
  new point (a turn's, a context read's) costs what moved since the last, never a draw of the page (a test's context read drew it
  cold each time, about 17M ticks of 25M). An earlier point, or another agent's, draws cold, and the walk drawn is the one kept.
  The branch is the db's own (Datahike's config). See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :seon.host/db :int [:vector [:maybe :map]]] [:vector :any]]}
  [db a chain]
  (if-let [p (some identity chain)]
    ;; a walk of its own, read by no reaction of the caller's: a render calling this (`turn-entities`) runs in its component's
    ;; reaction, which the walk's own atom and reactions would otherwise join, running it again for each
    (binding [ratom/*ratom-context* nil]
      (locking kept-walk
        (let [d0 (db-at db p)
              id [(get-in (or (:origin-db d0) d0) [:config :store :id]) (get-in (or (:origin-db d0) d0) [:config :branch]) a]
              k @kept-walk
              hiccup #(presentation-free (render/resolved (:w %) @(:out (:w %)) false))
              ;; the slot is empty while the walk moves: a walk left part-way (a render that threw past its own edge) is never advanced from
              _ (reset! kept-walk nil)
              ;; a point's t: its own, else its commit's (a db as of a t, a commit collection took, has no :max-tx of its own)
              t-of (fn [q d1] (or (:t q) (:max-tx d1)))
              t0 (t-of p d0)
              later (rest (drop-while nil? chain))
              ;; whether the kept walk can move on from where it is to point q (its t at or after the walk's, a later commit of its lineage)
              reaches? (fn [k q d1] (and (<= (:t k) (t-of q d1)) (descends? db (:commit k) (:commit q))))
              mine? (and k (= id (:id k)))
              ;; this point is the one the kept walk's last chain began at, which it has moved past (a flush between turns drew the
              ;; pending turn at the db's own point, one transaction past the last basis): its page is the one kept with the walk, and the
              ;; walk moves on from where it is to the next point, never drawn again (it drew the page cold at every such turn, ~7M ticks)
              held (when (and mine? (= [(:commit p) t0] (:point (:first k)))
                              (if-let [q (first later)] (reaches? k q (db-at db q)) true))
                     (:hiccup (:first k)))
              ;; the kept walk is advanced when this point is its own or a later commit of its lineage, else drawn again
              k0 (cond held k
                       (and mine? (reaches? k p d0)) (assoc (advance k d0 t0) :commit (:commit p))
                       :else (do (some-> k :w render/dispose-walk!) (assoc (parts-walk d0 t0 a id) :commit (:commit p))))
              h0 (or held (hiccup k0))
              ;; the first point, then each later one the walk advanced to
              [kn hs] (reduce (fn [[k hs] q] (let [d1 (db-at db q) k1 (assoc (advance k d1 (t-of q d1)) :commit (:commit q))] [k1 (conj hs (hiccup k1))]))
                              [k0 [h0]] later)]
          ;; kept with the walk, in its one slot: the page at this chain's first point, so a next chain that starts there again finds it
          (reset! kept-walk (assoc kn :first {:point [(:commit p) t0] :hiccup h0}))
          (into (mapv (constantly nil) (take-while nil? chain)) hs))))
    (mapv (constantly nil) chain)))

(defn page-parts-at
  "Agent a's page parts at basis b on db's store, as the model reads them: the chain of b alone drawn (`page-parts-through`).
  See docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
  {:malli/schema [:=> [:cat :seon.host/db :int :map] :any]}
  [db a b] (first (page-parts-through db a [b])))

(defn- point-text
  "Basis b as the point it names, written as the agent's code writes it: {:commit #uuid \"..\", :t n}, no branch, with the t the basis kept
  else the commit's own max-tx, printed by the one printer (seon.print/text), whole. See docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
  {:malli/schema [:=> [:cat :seon.host/db :map] :string]}
  [db b] (print/text (with-meta (array-map :commit (:commit b) :t (or (:t b) (:max-tx (d/commit-as-db (pdb/store-of db) (:commit b))))) {:seon.print/full true})))

(defn page-message
  "The page as the model reads it, a call Seon generated and its result, written as a reply the agent is asked to write: an assistant
  message whose text is the reply (`:seon.model-call/reply`) and whose one tool call is (f & args) with its intent (`:seon.eval/intent`),
  both read from the metadata of f's var (my.render/ctx, my.render/page, my.render/diff: the words are a fact about the function called,
  where the agent reads it, so constant per function and the prefix holds), and the tool message holding value v printed whole by the
  one printer, no wrapper. The call's id is a function of the points alone, so the bytes are the same at every render; no eval entity is
  written for it. A function whose var lacks either word is :seon.ctx/unworded, never an empty message
  (docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup)."
  {:malli/schema [:function [:=> [:cat :string :qualified-symbol [:sequential :string] :any] [:vector :seon.model/message]]
                  [:=> [:cat :string :qualified-symbol [:sequential :string] :any [:maybe :string]] [:vector :seon.model/message]]]}
  ([id f args v] (page-message id f args v nil))
  ([id f args v said]
  (let [{:seon.model-call/keys [reply] :seon.eval/keys [intent]} (meta (requiring-resolve f))
        reply (or said reply)]
    (when-not (and (string? reply) (seq reply) (string? intent) (seq intent))
      (throw (ex-info (str f " has no :seon.model-call/reply and :seon.eval/intent on its var: a call Seon writes for the model is a whole reply")
                      (contract/error :seon.ctx/unworded #'page-message (str "add both to " f "'s metadata") {:function f}))))
    [{:role "assistant" :content reply :tool-calls [{:id id :intent intent :code (str "(" (str/join " " (cons f args)) ")")}]}
     {:role "tool" :tool-call-id id :content (print/text (vary-meta v assoc :seon.print/full true))}])))

(defn diff-reply
  "What a diff's reply says, a function of its edits alone: the parts that moved, each by its name in the edit's path (the
  first name past the page and its lists, \"p-...\"), in the order they first move: \"My page changed: status, cite.\". Said
  in a few words that differ with what moved, since a model copies its history word for word and a fixed sentence becomes
  filler in its mouth (loop 2, call 16); what a diff is, is said once, at the first point (`my.render/page`'s reply). See
  docs/render.md#the-render-is-an-eval."
  {:malli/schema [:=> [:cat [:sequential :any]] :string]}
  [edits]
  (let [part (fn [[path]] (or (first (remove #(or (= "page" %) (and (string? %) (str/starts-with? % "p-"))) path)) "page"))]
    (str "My page changed: " (str/join ", " (distinct (map (comp str part) edits))) ".")))

(defn page-edits-between
  "The edits that take agent a's page parts at basis p0 (nil: nothing before, so the parts whole) to its parts at basis p1, on
  db's store, schemas read in index ix: the chain [p0 p1] drawn by one walk (`page-parts-through`) and its two pages compared by
  seon.render/page-edits over forms-moved, read from the db, never stored. What an agent asks at its REPL (my.render/diff), and
  what a turn's page message prints (`page-diff-msgs`). See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :seon.host/db :map :int [:maybe :map] :map] [:vector :any]]}
  [db ix a p0 p1]
  (let [[h0 h1] (page-parts-through db a [p0 p1])]
    (render/page-edits ix (render/forms-moved (if p0 {"page" h0} {}) {"page" h1}))))

(defn- pages-from
  "Agent a's page parts along chain (a first of nil: nothing before the second), drawn when asked: a function of a pair's index i
  answering [parts at point i, parts at point i+1]. The first pair asked draws the chain from its own point on by one walk
  (`page-parts-through`), whose later pairs read what that walk drew; a pair before it draws again from its own point. So a
  request whose pairs all run (a cold request) draws the chain once, and one whose earlier pairs the walk holds (a live walk, a
  new turn) draws its new pair alone. Asked by each turn's page message (`page-diff-msgs`). See docs/render.md#a-change-is-edits-by-name."
  {:malli/schema [:=> [:cat :seon.host/db :int [:vector [:maybe :map]]] :seon.host/fn]}
  [db a chain]
  (let [drawn (volatile! nil)]
    (fn [i]
      (let [[from hs] @drawn]
        (if (and from (<= from i))
          [(hs (- i from)) (hs (- (inc i) from))]
          (let [hs (page-parts-through db a (subvec chain i))]
            (vreset! drawn [i hs])
            [(hs 0) (hs 1)]))))))

(defn- describe-msgs
  "Agent a's record at point p described at path (what (describe @ctx path) answers there: seon.render/describe, printed through its
  schema's model render as an eval of it prints), as the generated eval `(my.agent/describe @ctx path)` and its result: the first
  point's (path []) and a task switch's ([:seon.agent/task], `task-msgs`). Reads a fixed past point only. See docs/agent.md#looking-at-what-you-pulled."
  {:malli/schema [:=> [:cat :seon.host/db :int :map [:vector :any]] [:vector :seon.model/message]]}
  [db a p path]
  (let [q (point-text db p) d0 (db-at db p) ix0 (program/index-of-db d0)
        es (with-meta (render/describe ix0 (ctx-at db a p) path (cfg/value :seon.config.agent/describe-depth)) {:seon.render/schema :my.agent/described})]
    (page-message (lib/id (lib/digest (str "ctx " q (when (seq path) (str " " (pr-str path))))))
                  'my.agent/describe (cond-> ["@ctx"] (seq path) (conj (pr-str path)))
                  (:value (render/model ix0 es {:ix ix0 :handle (symbol "@ctx")}) es))))

(defn page-diff-msgs
  "What agent a's page shows the model before its turn n, between basis p0 (nil: nothing before) and basis p1: generated evals,
  never written to the db. The first page (p0 nil) is the call `(my.render/page p1)` and its result the page parts whole; a later
  turn where something moved is `(my.render/diff p0 p1)` and its result the edits between the parts at the two points
  (seon.render/page-edits over forms-moved, as `page-edits-between` makes them); nothing moved, or a first page that shows nothing:
  no messages. Placed by the agent's model render before turn n's call, [#'page-diff-msgs opts a p0 p1 n], so the walk keeps each
  pair as a component keyed by its points and a new turn runs only its own pair. The parts come from the draw of all the turns'
  bases the placing render shares (`turn-entities`): opts' metadata :seon.ctx.wire/parts, a function answering this pair's two
  (`pages-from`), metadata so the component's opts compare by what they say, never by that function; without it, the pair's own
  chain is drawn (`page-parts-through`). See docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db] [:seon.render/index :map]] :int [:maybe :map] :map :int] [:vector :seon.model/message]]}
  [{db :seon.db/db ix :seon.render/index :as opts} a p0 p1 _n]
  ;; every read here is of a db value at a fixed past point (its two points: a commit, an immutable value, or a past t), which no
  ;; later write can change; it reads nothing of the current db (the placing render, `turn-entities`, does, and those reads stay
  ;; tracked: seon.ctx.wire-test/a-write-reruns-the-placing-render-and-no-earlier-page-pair). Only those are not recorded
  ;; (Datahike's recorder unbound, datahike.reads: with-reads would also turn the query planner and cache off), so the walk tracks none of them and runs this pair again only when its points change, never for each
  ;; later write (the read of a test's agent's context drew every earlier turn's page again, ~17M ticks a pair, at each read)
  (binding [datahike.index.interface/*reads* nil]
        (let [q0 (some->> p0 (point-text db)) q1 (point-text db p1)
              id (lib/id (lib/digest (str "[" q0 " " q1 "]")))
              [h0 h1] (if-let [f (::parts (meta opts))] (f) (page-parts-through db a [p0 p1]))
              edits (render/page-edits ix (render/forms-moved (if q0 {"page" h0} {}) {"page" h1}))]
          (cond (empty? edits) []
                ;; the page whole, unless it shows nothing (what moved from nothing to it is no edits: a pull holding no page)
                q0 (page-message id 'my.render/diff [q0 q1] edits (diff-reply edits))
                ;; the first point: the record whole as data (Sean, 2026-11-11: "the @ctx and the hiccup displayed in full"), then the page
                ;; the record described, never printed whole (owner, 2026-11-11: "Instead of dumping the whole @ctx value we are now doing
                ;; this more metadata approach"), then the page
                :else (into (describe-msgs db a p1 []) (page-message id 'my.render/page [q1] h1))))))

(defn model-call-msgs
  "A model call (:seon.model-call/fact) as the model reads it: what the model said and its reasoning. Whether those are sent is
  today's rule, reproduced: a model's reply only (origin :model), when it made calls or was not cut at the token limit, and
  only with words or reasoning to send. Its turn's page message (`page-diff-msgs`) and its evals are their own. See
  docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db]] :map] [:vector :seon.model/message]]}
  [_ {:seon.model-call/keys [n origin reply reasoning truncated] :as c}]
  ;; read from the pull: the call's evals are joined with the two attributes this asks (the agent's pattern), never looked up
  (let [called? (boolean (some (fn [e] (and (:seon.eval/call-id e) (:seon.eval/agent e))) (vals (:seon.eval/_call c))))
        spoke? (not (str/blank? reply))
        sent? (and (= :model origin) (or called? (and (not truncated) spoke?)) (or spoke? (some? reasoning)))]
    (if sent?
      [(cond-> {:role "assistant" :content (if spoke? reply "")}
         (some? reasoning) (assoc :reasoning reasoning)
         (and n (pos? n)) (assoc :reply n))]
      [])))

(defn- opening-page
  "The page of the turn now opening, whose call is not written yet (a read made as a model's request would be:
  seon.turn/context-block): the call that shows agent a's page from last-b, the last call's basis, to here, the request's own
  point, after what that turn took; none when here is that basis, or when the pull holds calls but no basis (a smaller pattern
  shows no page). newest: the last call's number. See docs/render.md#the-render-is-an-eval."
  {:malli/schema [:=> [:cat :int [:maybe :map] [:maybe :map] :boolean [:maybe :int]] [:maybe [:vector :any]]]}
  [a here last-b no-calls? newest]
  (when (and here (not= (:t here) (:t last-b)) (or no-calls? last-b))
    [[#'page-diff-msgs {} a last-b here (inc (or newest 0))]]))

(defn task-msgs
  "Where an agent's calls change task, what it reads at the first call of task id after task before's (seon.ctx.session/task-begins),
  then the new task described at the switching call's point p (`describe-msgs`, path [:seon.agent/task]); placed by the agent's model
  render as [#'task-msgs {} before id a p], so the walk keeps it as a component keyed by the two tasks and every later request holds it
  at the same place. See docs/tasks.md#a-refine-curates-its-own-task."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db]] :string :string :int [:maybe :map]] [:vector :seon.model/message]]}
  [{db :seon.db/db} before id a p]
  ;; and the new task described where the agent's record holds it (its cites, what each holds, the forms that act on them), read at the
  ;; switching call's point: a fixed past point, so every later request holds the same bytes
  (into [{:role "user" :content (session/task-begins before id)}]
        (when p (binding [datahike.index.interface/*reads* nil] (describe-msgs db a p [:seon.agent/task])))))

(defn- turn-entities
  "Agent record c's turns as the entities the model reads, ordered from its pull: its model calls by number, each turn's
  messages and the other members of its history as it read them (seon.ctx/placed-messages: those linked by its basis, so one linked
  mid-turn reads at the next turn, each by its path in c), its page message between the basis of the call before that holds one and its own (`page-diff-msgs`, a placed call
  whose parts come from one walk along the bases, drawn when a pair first asks: `pages-from`),
  the call, then its evals in the one order a turn's are read in (seon.ctx/turn-evals: the model's, then other hands'); a member c no longer holds (unlinked by a swap!) is
  none. The model reads of an eval no call holds by another hand than the agent's only one by the system with an intent (`model-reads?`).
  See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :seon.host/db :map :map] [:vector :any]]}
  [db ix c]
  (let [evals (:seon.eval/_agent c)
        placed (ctx/placed-messages ix c)
        ;; what the model reads of the placed members (the page shows them all): an eval by another than the agent itself only when it is the
        ;; system's (its :seon.eval/by the system entity, :seon.system/id) and holds an intent; a person's or another agent's eval, and the
        ;; system's own work (seon.turn/work!, no intent), stay out (Context and Data model, 2026-11-11)
        ;; read untracked, member by its path: who made an eval and its intent are written with it, once
        plain (ratom/untracked c)
        model-reads? (fn [p] (let [m (get-in plain p) by (:seon.eval/by m)] (or (nil? by) (= (:db/id by) (:db/id plain)) (and (:seon.system/id by) (not (str/blank? (:seon.eval/intent m)))))))
        ;; the newest call's basis is written after the commit it names (seon.turn/open-turn!), so read at that commit it holds none:
        ;; there the point is db's own (the basis it will name), so a request made again at its basis holds its page message; a pull
        ;; that leaves bases out (a smaller pattern) shows no page
        here (or (::here (ratom/untracked c)) (when-let [id (d/commit-id db)] {:commit id :t (:max-tx db)}))
        ;; its turns: the calls with a number, but an outside agent's context read (origin :outside, read-<k>), which is no turn
        ;; (seon.ctx/turns, placed-messages): numbered by reads, it sat among the turns by its own count, placed a turn's messages
        ;; a second time and moved the page pairs after it at every read
        cs (map val (sort-by (comp :seon.model-call/n val) (filter (fn [[_ x]] (and (:seon.model-call/n x) (not= :outside (:seon.model-call/origin x)))) (:seon.model-call/_agent c))))
        newest (:seon.model-call/n (last cs))
        ;; a call whose basis the db holds but the pull left out has none here: the pull says what the agent reads. A basis is
        ;; placed as its point alone ({:commit :t}): the opening page placed at here, once its call is written, is the same pair
        ;; at the same points, so the walk keeps the component it drew, never draws it again for the basis's other keys
        basis-of (fn [x] (some-> (or (:seon.model-call/basis x)
                                     (when (and (= newest (:seon.model-call/n x))
                                                (nil? (some->> (:seon.model-call/id x) (pdb/eid db :seon.model-call/id) (d/entity db) :seon.model-call/basis)))
                                       here))
                                 (select-keys [:commit :t])))
        ;; the page parts at the turns' bases, drawn by one walk along them when a pair first asks (`pages-from`): the i-th call
        ;; holding a basis reads the pair i, i+1 of them
        withb (filter basis-of cs)
        parts (pages-from db (:db/id c) (into [nil] (map basis-of) withb))
        pair (zipmap (map :seon.model-call/n withb) (range))]
    (into (vec (for [[prev call before] (map vector (reductions (fn [b x] (or (basis-of x) b)) nil cs) cs (cons nil cs))
               :let [n (:seon.model-call/n call)
                     ;; the task a call served, from its basis (the newest, written after its commit, serves the agent's task now)
                     task-of (fn [x] (or (:task (ratom/realize (:seon.model-call/basis x))) (when (= newest (:seon.model-call/n x)) (ratom/realize (get-in c [:seon.agent/task :seon.task/id])))))
                     t0 (some-> before task-of) t1 (task-of call)
                     ;; the members of its history it took, each by its path in c (a message, or any history join's member)
                     ms (keep #(get-in c %) (filter model-reads? (get placed n)))
                     ;; its evals in the one order a turn's are read in (seon.ctx/turn-evals: the model's, then other hands')
                     es (map first (ctx/turn-evals c (:db/id call)))
                     model? #(:seon.eval/call-id (get evals %))]
               x (concat (when (and t0 t1 (not= t0 t1)) [[#'task-msgs {} t0 t1 (:db/id c) (basis-of call)]])
                         ms
                         (when-let [b (basis-of call)] (let [i (pair n)] [[#'page-diff-msgs (with-meta {} {::parts #(parts i)}) (:db/id c) prev b n]]))
                         ;; a reply with no words whose calls c no longer holds (each unlinked by a swap!) says nothing: none of it stays
                         (when (or (some model? es) (not (str/blank? (:seon.model-call/reply call)))) [call])
                         (map evals es))]
           x))
          ;; those delivered after the last turn's edge, which the turn now opening takes (:pending): never lost before that turn's call exists
          (concat (keep #(get-in c %) (filter model-reads? (get placed :pending)))
                  (opening-page (:db/id c) here (last (keep basis-of cs)) (empty? cs) newest)))))

(defn example-session-msgs
  "An example session's agent (its root view in a render-pull of it) as the model reads it, a model output's call: the message
  that opens it (seon.ctx.session/example-open), its turns' entities placed (`turn-entities`), each rendered by its own
  :seon.render/openai-compat in the walk, and the message that closes it. A call's answer stands for the agent's own model render, so an
  example writes no session opening of its own. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db] [:seon.render/index :map]] :map] [:vector :any]]}
  [{db :seon.db/db ix :seon.render/index} a]
  (-> [{:role "user" :content (session/example-open (ratom/realize (:seon.agent/key a)) (ratom/realize (:seon.agent/description a)))}]
      (into (turn-entities db ix a))
      (conj {:role "user" :content "</session>"})))

(defn example-msgs
  "Example session ek as the model reads it, placed by the agent's model render as [#'example-msgs {} ek] (the walk keeps it as
  a component keyed by the example, a past point that never changes): its agent pulled as of its last t and rendered in one
  pass for the model (seon.render/render-pull, its root `example-session-msgs` as a model output's call), so each of its
  entities is rendered by its own :seon.render/openai-compat inside a walk. See docs/tasks.md#a-task-names-example-sessions."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db]] :string] [:vector :seon.model/message]]}
  [{db :seon.db/db} ek]
  (let [dbt (d/as-of db (session/example-t db ek))
        ix (program/index-of-db dbt)
        branch (get-in (or (:origin-db dbt) dbt) [:config :branch])
        pull (:tree (ctx/build ix dbt (pdb/eid dbt :seon.agent/key ek) branch))]
    (:messages (render/render-pull pull dbt #'example-session-msgs ix #{:seon.render/openai-compat} {:seon.render/as :seon.render/openai-compat :seon.render/changes ctx/changes}))))

(defn agent-msgs
  "An agent (:seon.db/agent-fact) as the model reads it: its system message (`agent-system-text`: the doc sections
  :seon.agent/system names, else the system prompt; a placed example is answered by its own call, so it has none), each example session its task names as a placed call of `example-msgs`, what opens its own session, then its turns'
  entities (`turn-entities`). The task is read in the page parts alone, never here. See docs/render.md#what-the-agent-reads-is-its-native-history-and-everything-else-through-the-hiccup."
  {:malli/schema [:=> [:cat [:map [:seon.db/db :seon.host/db] [:seon.render/index :map]] :map] [:vector :any]]}
  [{db :seon.db/db ix :seon.render/index} a]
  (let [k (:seon.agent/key a)]
    ;; no prompt on db: no system message, so the request is refused by its own check (a typed error), not thrown inside the render
    (-> (if-let [t (agent-system-text db a)] [{:role "system" :content t}] [])
        (into (map (fn [ek] [#'example-msgs {} ek])) (session/session-examples db a))
        (into (when k [{:role "user" :content (session/session-open k)}]))
        (into (turn-entities db ix a)))))
