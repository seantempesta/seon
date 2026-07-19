(ns seon.agent.ctx.menu
  "Render optional typeahead menus from current database facts.

   This namespace derives compact, glyph-addressed function suggestions and
   owns the database-backed typeahead policy schemas. Menus are advisory and
   vanish when empty; plan presentation and provider execution live elsewhere."
  (:require
    [clojure.string :as str]
    [seon.agent.home :as home]
    [seon.agent.ctx.namespaces :as ns-cards]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.eval :as seval]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]))

;; ============================================================
;; Glyphs — the model→driver selection vocabulary (①–⑩, all single
;; tokens, measured — typeahead-design "The glyph vocabulary").
;; ============================================================

(def glyphs
  "The ten menu-entry selection glyphs ①–⑩, in menu order.

   Single tokens each (measured). Every glyph-numbered menu this family
   renders indexes into THIS vector — the hard ceiling on any menu is
   its length, whatever the policy `menu-cap` says."
  ["①" "②" "③" "④" "⑤" "⑥" "⑦" "⑧" "⑨" "⑩"])

;; ============================================================
;; The `:seon.typeahead/policy` row — auto-offer margin, worst-token
;; gate, probe budget, menu cap. One singleton entity, identity
;; `[:seon.typeahead/id "policy"]` (the `:seon.ai/id "config"`
;; precedent). The two confidence knobs + the probe budget have NO
;; consumer in this ns — they are the DRIVER's dials (P2/P3b),
;; registered now so the row shape is stable (the PARKED
;; `::capabilities` precedent in seon.agent.ctx).
;; ============================================================

(schema/register! :seon.typeahead/id [:string {:seon.db/identity true}])
;; Calibrated glyph-posterior margin an auto-offer must clear (logits;
;; measured first-slot inflation −0.0 vs −6.4 — the design's calibration
;; rule subtracts the null-intent baseline BEFORE this threshold).
(schema/register! :seon.typeahead/auto-offer-margin :double)
;; Auto-accept gates use WORST-token confidence over a span, never mean
;; (probability dilution) — the minimum per-token probability accepted.
(schema/register! :seon.typeahead/worst-token-gate :double)
;; How many hole lengths the CAL confidence probe tries per hole.
(schema/register! :seon.typeahead/probe-budget :int)
;; Glyph page size — max entries any menu section renders (further
;; bounded by (count glyphs)).
(schema/register! :seon.typeahead/menu-cap :int)
;; Max TOOLKIT-group entries in the function menu (P6 — the second offer
;; group; the recency group keeps menu-cap; the combined menu is bounded
;; by the glyph vocabulary).
(schema/register! :seon.typeahead/toolkit-cap :int)
;; Step-loop round budget — how many mode=step calls one provider turn
;; may make before it stops with whatever locked (the P3b driver's cap;
;; mirrors the worker Policy's max_rounds default).
(schema/register! :seon.typeahead/max-rounds :int)
;; The per-step PLAN PASS scheduling (planner-worker-design W2): the step
;; loop's edit-with-prefill plan-document pass runs at every call open
;; (:every-step), only after an observed stuck round (:on-stuck), or
;; never (:off). LOOP-side knob — never rides the worker Policy wire.
(schema/register! :seon.typeahead/plan-pass [:enum :every-step :on-stuck :off])

;; The stored row shape — every knob optional (absent = code default;
;; optional = absent, never a stored nil).
(schema/register! :seon.typeahead/policy
  [:map {:seon.db/entity true}
   [:seon.typeahead/id :seon.typeahead/id]
   [:seon.typeahead/auto-offer-margin {:optional true} :seon.typeahead/auto-offer-margin]
   [:seon.typeahead/worst-token-gate  {:optional true} :seon.typeahead/worst-token-gate]
   [:seon.typeahead/probe-budget      {:optional true} :seon.typeahead/probe-budget]
   [:seon.typeahead/menu-cap          {:optional true} :seon.typeahead/menu-cap]
   [:seon.typeahead/toolkit-cap       {:optional true} :seon.typeahead/toolkit-cap]
   [:seon.typeahead/max-rounds        {:optional true} :seon.typeahead/max-rounds]
   [:seon.typeahead/plan-pass         {:optional true} :seon.typeahead/plan-pass]])

;; The EFFECTIVE policy view [[policy]] returns — every knob present.
(schema/register! ::policy-view
  [:map
   [:seon.typeahead/auto-offer-margin :seon.typeahead/auto-offer-margin]
   [:seon.typeahead/worst-token-gate  :seon.typeahead/worst-token-gate]
   [:seon.typeahead/probe-budget      :seon.typeahead/probe-budget]
   [:seon.typeahead/menu-cap          :seon.typeahead/menu-cap]
   [:seon.typeahead/toolkit-cap       :seon.typeahead/toolkit-cap]
   [:seon.typeahead/max-rounds        :seon.typeahead/max-rounds]
   [:seon.typeahead/plan-pass         :seon.typeahead/plan-pass]])

(def policy-row-id
  "The `:seon.typeahead/id` of the ONE policy singleton row."
  "policy")

(def default-policy
  "The code-default typeahead policy — the row overrides per knob."
  {:seon.typeahead/auto-offer-margin 3.0
   :seon.typeahead/worst-token-gate  0.9
   :seon.typeahead/probe-budget      3
   :seon.typeahead/menu-cap          8
   :seon.typeahead/toolkit-cap       4
   :seon.typeahead/max-rounds        8
   :seon.typeahead/plan-pass         :every-step})

;; ============================================================
;; :function-menu — most recently/frequently eval'd public fns, derived
;; from the eval log + the program graph. Nothing new is stored: the
;; `:seon.eval` rows are the log the turn loop already writes, the
;; `:seon.fn` rows are the boot/tee-indexed program graph, and alias
;; resolution reads persisted `:seon.ns/require-edges` for each eval's
;; own ns (the one requires store, C36).
;; ============================================================

(def ^:private eval-scan-window
  "How many of the agent's most recent successful evals feed the
   ranking. A window (not all history) keeps the menu tracking what the
   agent is doing NOW and bounds the per-render read."
  30)

(defn- call-syms
  "Every symbol in call position anywhere in eval `source`, in order.

   Reads the whole source structurally (`repl-internal/read-forms` — the
   ONE whole-source read; nil on a broken source → []), then walks each
   form collecting the head symbol of every list. Strings/comments can't
   false-positive; a quoted list's head is accepted noise."
  [source]
  (into []
        (comp (mapcat #(tree-seq coll? seq %))
              (keep (fn [f]
                      (when (and (seq? f) (symbol? (first f)))
                        (first f)))))
        (or (repl-internal/read-forms source) [])))

(defn- resolve-call-sym
  "Resolve call symbol `sym` to a full `\"ns/name\"` string via the
   eval ns's require `info`, or nil when unresolvable (a local, a core
   fn, an unaliased bare symbol)."
  [{aliases :seon.eval/aliases refers :seon.eval/refers} sym]
  (if-let [ns-part (namespace sym)]
    (str (get aliases (symbol ns-part) (symbol ns-part)) "/" (name sym))
    (some (fn [[target syms]]
            (when (contains? syms sym)
              (str target "/" (name sym))))
          refers)))

;; ============================================================
;; Toolkit group (P6) — task-relevant offers from the program graph. The
;; P5 measurement: all 13 auto-offer fires were argmax-correct ON the
;; menu, but 0/13 selected a task-required function — the recency-only
;; menu structurally cannot contain a function the agent has not called
;; yet. This group puts the agent's core function surface on the menu
;; regardless of its own usage:
;;
;;   - the TOOLKIT NSES are the nses the agent's CURRENT ns requires
;;     (the stored `:seon.ns/require-edges` — the SAME set whose compact
;;     cards the `:namespaces` section renders, mirroring its
;;     `required-ns-set`; for a home ns that is the canonical
;;     `seon.agent.home/home-ns-require-specs` wiring). No hand list: the
;;     require edges are data, `included-ns?` is the structural filter.
;;   - the CANDIDATES are those nses' public SPECCED `:seon.fn` rows
;;     (the program graph; a specced public fn is the agent-facing
;;     contract surface).
;;   - the RANK is cross-agent global call frequency over the newest
;;     [[global-eval-scan-window]] successful evals (any agent — the
;;     cross-agent reactive-context precedent), admitted ROUND-ROBIN per
;;     ns (every toolkit ns gets its top function before any ns gets its
;;     second) so one chatty ns cannot crowd the rest out. Zero-usage
;;     stores without eval history fall back to (ns-name, fn-name) order.
;;
;; ONE numbering across both groups (decided here, P6): the rendered
;; section numbers recent + toolkit entries through the SAME glyph
;; vector, and [[function-offers]] mirrors the concatenation — glyph N on
;; the wire is glyph N in the prompt for EVERY offer. (The retired
;; `:plan-ledger` section used to number its own lines from ① too;
;; that render-side ambiguity is gone — this menu is the only
;; glyph-numbered surface.)
;; ============================================================

(def ^:private global-eval-scan-window
  "How many of the CLUSTER's most recent successful evals (any agent)
   feed the toolkit-group ranking. Bounds the per-render read."
  200)

(def ^:private menu-teaching
  ;; The measured P5 teaching, byte-stable (the bench's teaching overlay
  ;; keys on these lines).
  (str "; A MENU, never a mandate: select an entry by outputting its glyph\n"
       "; alone (e.g. ①), or ignore this and write any Clojure — both work.\n"
       "; Example: to select entry ①, output the single character ① and\n"
       "; nothing else — its call template is expanded for you to fill."))

(def ^:private recent-functions-header
  (str "; recent functions — the fns you have been calling, most-used first.\n"
       menu-teaching))

(def ^:private toolkit-only-header
  (str "; toolkit functions — your required namespaces' public fns.\n"
       menu-teaching))

(def ^:private toolkit-group-header
  "; toolkit — more functions from your required namespaces:")

(defn- compact-row
  "Materialize the callable fields of a Datahike entity as an ordinary map."
  [sym-str row]
  (cond-> {:seon.fn/sym sym-str}
    (:seon.fn/arglists row) (assoc :seon.fn/arglists (:seon.fn/arglists row))
    (:seon.fn/doc row)      (assoc :seon.fn/doc (:seon.fn/doc row))
    (:seon.fn/spec row)     (assoc :seon.fn/spec (:seon.fn/spec row))))

(defn- function-line
  "One rendered menu entry: glyph plus the canonical compact fn record."
  [glyph sym-str row]
  (str "; " glyph " "
       (ns-cards/compact-fn-head (compact-row sym-str row))))

(def ^:private prompt-eval-query
  {:find '[?at ?eval-tx ?source ?ns]
   :in '[$ ?agent-id]
   :where '[[?agent :seon.agent/id ?agent-id]
            [?eval :seon.eval/agent ?agent]
            [?eval :seon.eval/ok? true]
            [?eval :seon.eval/at ?at ?eval-tx]
            [?eval :seon.eval/source ?source]
            [?eval :seon.eval/ns ?ns]]
   :order-by '[?at :desc ?eval-tx :desc]
   :limit eval-scan-window})

(def ^:private cluster-eval-query
  {:find '[?at ?eval-tx ?source ?ns]
   :where '[[?eval :seon.eval/ok? true]
            [?eval :seon.eval/at ?at ?eval-tx]
            [?eval :seon.eval/source ?source]
            [?eval :seon.eval/ns ?ns]]
   :order-by '[?at :desc ?eval-tx :desc]
   :limit global-eval-scan-window})

(def ^:private prompt-fn-selector
  '[:seon.fn/sym :seon.fn/fn-var? :seon.fn/agent-facing?
    :seon.fn/private? :seon.fn/spec :seon.fn/arglists :seon.fn/doc])

(def ^:private prompt-ns-selector
  '[:seon.ns/name {:seon.ns/require-edges
                   [:seon.ns.require/target :seon.ns.require/alias
                    :seon.ns.require/refers :seon.ns.require/refer-all?
                    :seon.ns.require/as-alias?]}])

(defn- query-member [query arguments max-results max-weight]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form query
   ::protocol/arguments (vec arguments)
   :datahike.resource/max-work 1000000
   :datahike.resource/max-results max-results
   :datahike.resource/max-result-weight max-weight})

(defn- member-result [member]
  (when (true? (::protocol/success? member))
    (or (::protocol/result member)
        (:datahike.query/result member))))

(defn- eval-row [[at eval-tx source ns-name]]
  {:seon.eval/at at :seon.eval/source source :seon.eval/ns ns-name
   :seon.eval/ok? true ::eval-tx eval-tx})

(defn- namespace-info [rows]
  (into {}
        (map (fn [row]
               [(:seon.ns/name row)
                (seval/edges->require-info
                  (set (:seon.ns/require-edges row)))]))
        rows))

(defn- acquired-called-symbols [rows infos]
  (vec
    (mapcat (fn [{src :seon.eval/source ns-name :seon.eval/ns}]
              (keep #(or (resolve-call-sym (get infos ns-name) %)
                         (when (and ns-name (nil? (namespace %)))
                           (str (name ns-name) "/" (name %))))
                    (call-syms src)))
            rows)))

(defn- directly-called-symbols [rows]
  (->> rows
       (mapcat (fn [{src :seon.eval/source ns-name :seon.eval/ns}]
                 (keep (fn [sym]
                         (cond
                           (namespace sym) (str sym)
                           ns-name (str (name ns-name) "/" (name sym))))
                       (call-syms src))))
       distinct
       vec))

(defn- rank-acquired [rows infos functions]
  (let [occ (acquired-called-symbols rows infos)
        freq (frequencies occ)
        seen (reduce (fn [m [i s]] (if (contains? m s) m (assoc m s i)))
                     {} (map-indexed vector occ))]
    (->> (keys freq)
         (sort-by (juxt #(- (freq %)) seen))
         (keep (fn [s] (when-let [row (get functions s)] [s row])))
         vec)))

(defn- acquired-functions
  [{:keys [agent-rows cluster-rows current-ns namespace-rows function-rows
           policy-row]}]
  (let [effective-policy (merge default-policy
                                (select-keys policy-row (keys default-policy)))
        infos (namespace-info namespace-rows)
        functions (into {}
                        (keep (fn [row]
                                (when (and (:seon.fn/sym row)
                                           (:seon.fn/fn-var? row)
                                           (:seon.fn/agent-facing? row)
                                           (not (:seon.fn/private? row)))
                                  [(:seon.fn/sym row) row])))
                        function-rows)
        recent (->> (rank-acquired agent-rows infos functions)
                    (take (-> (:seon.typeahead/menu-cap effective-policy)
                              (min (count glyphs)) (max 0)))
                    vec)
        freq (frequencies (acquired-called-symbols cluster-rows infos))
        targets (->> namespace-rows
                     (some #(when (= current-ns (:seon.ns/name %)) %))
                     :seon.ns/require-edges
                     (map :seon.ns.require/target)
                     (filter ns-cards/included-ns?) set)
        exclude (into #{} (map first) recent)
        per-ns (->> targets
                    (map (fn [ns-name]
                           (->> functions
                                (keep (fn [[s row]]
                                        (when (and (:seon.fn/spec row)
                                                   (str/starts-with? s (str (name ns-name) "/"))
                                                   (not (contains? exclude s)))
                                          [s row])))
                                (sort-by (fn [[s _]] [(- (freq s 0)) s]))
                                vec)))
                    (remove empty?)
                    (sort-by (fn [entries]
                               [(- (freq (ffirst entries) 0)) (ffirst entries)])))
        max-len (apply max 0 (map count per-ns))
        toolkit-cap (-> (:seon.typeahead/toolkit-cap effective-policy)
                        (min (- (count glyphs) (count recent))) (max 0))
        toolkit (->> (for [i (range max-len), entries per-ns
                           :when (< i (count entries))]
                       (nth entries i))
                     (take toolkit-cap) vec)]
    {::policy effective-policy ::recent recent ::toolkit toolkit}))

(defn- format-function-menu [{::keys [recent toolkit]}]
  (let [lines (fn [offset entries]
                (str/join "\n"
                          (map-indexed
                            (fn [i [s row]]
                              (function-line (glyphs (+ offset i)) s row))
                            entries)))]
    (cond
      (and (seq recent) (seq toolkit))
      (str recent-functions-header "\n" (lines 0 recent) "\n"
           toolkit-group-header "\n" (lines (count recent) toolkit))
      (seq recent) (str recent-functions-header "\n" (lines 0 recent))
      (seq toolkit) (str toolkit-only-header "\n" (lines 0 toolkit))
      :else "")))

(defn ^:async ^:private acquire-prompt-menu
  [{agent-id :seon.agent/id :as input}]
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [initial (await (db/execute-many
                           {::db/db database
                            ::db/members
                            [{::protocol/operation protocol/pull-operation
                              ::protocol/selector (vec (cons :seon.typeahead/id
                                                             (keys default-policy)))
                              ::protocol/entity-id [:seon.typeahead/id policy-row-id]
                              :datahike.resource/max-work 10000
                              :datahike.resource/max-results 32
                              :datahike.resource/max-result-weight 4096}
                             (query-member prompt-eval-query [agent-id] 32768 262144)
                             (query-member cluster-eval-query [] 131072 1048576)
                             (query-member home/namespace-assignment-query
                                           [agent-id] 64 4096)]
                            ::db/max-result-weight 1314816}))]
        (if-not (and (not (:seon.error/message initial))
                     (every? #(true? (::protocol/success? %))
                             (::db/results initial)))
          {:seon.error/message "Function menu acquisition failed."
           :seon.error/kind :core-bug :seon.error/data initial}
          (let [[policy-member agent-member cluster-member assignment-member]
                (::db/results initial)
            agent-rows (mapv eval-row (member-result agent-member))
            cluster-rows (mapv eval-row (member-result cluster-member))
            latest-eval (first agent-rows)
            current-ns
            (home/current-ns
             agent-id (:seon.agent/entity input)
             (when latest-eval
               [(:seon.eval/ns latest-eval)
                (:seon.eval/at latest-eval)
                (::eval-tx latest-eval)])
             (some-> (member-result assignment-member) first))
            source-nses (->> (concat [current-ns]
                                     (map :seon.eval/ns agent-rows)
                                     (map :seon.eval/ns cluster-rows))
                             (remove nil?) distinct vec)
            direct-syms (directly-called-symbols
                          (concat agent-rows cluster-rows))
            selected (await (db/execute-many
                              {::db/db database
                               ::db/members
                               [{::protocol/operation protocol/pull-many-operation
                                 ::protocol/selector prompt-ns-selector
                                 ::protocol/entity-ids
                                 (mapv (fn [n] [:seon.ns/name n]) source-nses)
                                 :datahike.resource/max-work 1000000
                                 :datahike.resource/max-results 16384
                                 :datahike.resource/max-result-weight 1048576}
                                (query-member
                                  {:find [(list 'pull '?fn prompt-fn-selector)]
                                   :in '[$ [?source-name ...]]
                                   :where '[[?source :seon.ns/name ?source-name]
                                            [?source :seon.ns/require-edges ?edge]
                                            [?edge :seon.ns.require/target ?target-name]
                                            [?target :seon.ns/name ?target-name]
                                            [?fn :seon.fn/ns ?target]
                                            [?fn :seon.fn/fn-var? true]
                                            [?fn :seon.fn/agent-facing? true]]}
                                  [source-nses] 65536 2097152)
                                {::protocol/operation protocol/pull-many-operation
                                 ::protocol/selector prompt-fn-selector
                                 ::protocol/entity-ids
                                 (mapv (fn [sym] [:seon.fn/sym sym]) direct-syms)
                                 :datahike.resource/max-work 1000000
                                 :datahike.resource/max-results 32768
                                 :datahike.resource/max-result-weight 1048576}]
                               ::db/max-result-weight 3211264}))]
            (if-not (and (not (:seon.error/message selected))
                         (every? #(true? (::protocol/success? %))
                                 (::db/results selected)))
              {:seon.error/message "Function menu selected acquisition failed."
               :seon.error/kind :core-bug :seon.error/data selected}
              (let [[ns-member fn-member direct-member] (::db/results selected)]
                {:policy-row (member-result policy-member)
                 :agent-rows agent-rows :cluster-rows cluster-rows
                 :current-ns current-ns
                 :namespace-rows (remove nil? (member-result ns-member))
                 :function-rows (into (mapv first (member-result fn-member))
                                      (remove nil?)
                                      (member-result direct-member))}))))))))

(declare acquire-function-menu)

(defn ^:async function-menu-block
  "The `:function-menu` section — recent + toolkit functions, glyph-listed.

   ONE menu, TWO derived groups under ONE glyph numbering (P6):

     - recent — every fn called in YOUR last [[eval-scan-window]]
       successful evals that is a PUBLIC program-graph fn, most-called
       first, most-recent first on ties, policy `menu-cap`.
     - toolkit — your current ns's required nses' public SPECCED fns
       ([[toolkit-functions]]: cross-agent frequency rank, per-ns
       round-robin, policy `toolkit-cap`) — the task-relevant surface a
       recency menu structurally misses (P5: 0/13 fires could select a
       required function).

   Each entry is a glyph plus the canonical inert callable contract (explicit
   map-in versus positional arguments, input types, return type, doc line 1).
   Aliased calls resolve through each eval ns's persisted require edges; nothing
   new is stored.
   Selection is strictly optional (the header teaches it); REACTIVE:
   both groups empty → \"\" and the composer drops the section. The
   section is named `:function-menu` (renamed from `:recent-verbs`,
   owner 2026-07-12 — ctx rows seed-copied into agents BEFORE the rename
   keep the old name + fn symbol and are orphaned; a cluster reset
   re-seeds from the manifest)."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input _invoke-selected!]
  (let [menu (await (acquire-function-menu input))]
    (if (:seon.error/message menu)
      (str "[function-menu] render failed: "
           (:seon.error/message menu))
      (::text menu))))

;; ============================================================
;; Driver offers — the SAME capped function list as the rendered menu,
;; in the step-driver's offer shape (typeahead-design "Wire modes":
;; glyph + label + a clamp/free template). The P3b provider
;; (`seon.ai.typeahead`) converts these to the worker's string-keyed
;; wire maps; glyph N here is glyph N in the rendered `:function-menu`
;; section by construction (both read [[capped-functions]]).
;; ============================================================

(schema/register! :seon.typeahead/glyph :string)
(schema/register! :seon.typeahead/label :string)
(schema/register! :seon.typeahead/template-segment
  [:or [:tuple [:= "clamp"] :string] [:tuple [:= "free"] :int]])
(schema/register! :seon.typeahead/template
  [:vector :seon.typeahead/template-segment])
(schema/register! :seon.typeahead/offer
  [:map
   [:seon.typeahead/glyph    :seon.typeahead/glyph]
   [:seon.typeahead/label    :seon.typeahead/label]
   [:seon.typeahead/template :seon.typeahead/template]])
(schema/register! ::offers-view [:vector :seon.typeahead/offer])

(schema/register! ::text :string)
(schema/register! ::menu-value
  [:map
   [::policy ::policy-view]
   [::offers ::offers-view]
   [::text ::text]])
(schema/register! ::direct-error
  [:map [:seon.error/message :string]])

(def ^:private offer-args-free-tokens
  "Free tokens a function template grants for the call's arguments."
  24)

(defn- functions->offers
  [{::keys [recent toolkit]}]
  (vec
    (map-indexed
      (fn [i [sym row]]
        {:seon.typeahead/glyph (glyphs i)
         :seon.typeahead/label (ns-cards/compact-fn-head
                                 (compact-row sym row))
         :seon.typeahead/template
         [["clamp" (str "(" sym " ")]
          ["free" offer-args-free-tokens]
          ["clamp" ")"]]})
      (concat recent toolkit))))

(defn ^:async ^:no-doc acquire-function-menu
  {:malli/schema
   [:=> [:cat :seon.render/section-request]
    [:or ::menu-value ::direct-error]]}
  [input]
  (let [acquired (await (acquire-prompt-menu input))]
    (if (:seon.error/message acquired)
      acquired
      (let [functions (acquired-functions acquired)
            policy (::policy functions)]
        {::policy policy
         ::offers (functions->offers functions)
         ::text (format-function-menu functions)}))))

;; (The `:plan-ledger` section that used to live here retired 2026-07-11
;; — see the ns docstring; `my.plan.internal/plan-block` carries the
;; ▶/☐/done-dropped contract now.)
