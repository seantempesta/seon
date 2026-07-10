(ns seon.agent.ctx.menu
  "The typeahead MENU block family — glyph-numbered, strictly-OPTIONAL
   offers derived from the db at render time (diffusion-typeahead P3a;
   see docs/prds/diffusion-dynamic-context/typeahead-design.md). Two
   sections, both pure fns of the db (reactive-context — the query
   returns nothing → the section vanishes, nothing is stored):

     - `:recent-verbs` ([[recent-verbs-block]]) — the agent's most-used
       public fns, derived from its OWN eval log (the `:seon.eval` rows
       the turn loop already persists — no new storage), each rendered
       as a glyph-numbered entry: glyph, `(fn-sym [args] …)` in the same
       arity grammar as the compact ns cards, docstring line 1.
     - `:plan-ledger` ([[plan-ledger-block]]) — the open/current
       `my.plan` steps as `▶`/`☐` glyph lines. DONE steps are DROPPED
       from the render (derive-don't-store; the full tree stays
       queryable via `my.plan/tree`).

   The one law (settled by measurement — see the design doc): selection
   is STRICTLY OPTIONAL, forever. Each section header teaches it once,
   colocated with the block: select by outputting the glyph alone, or
   ignore the menu and write any Clojure — both work.

   This ns also owns the `:seon.typeahead/*` driver-policy row — the ONE
   policy surface the typeahead design allows (no new config system).
   Defaults live in code ([[default-policy]]); the
   `[:seon.typeahead/id \"policy\"]` singleton row, when present,
   OVERRIDES per knob ([[policy]] — read at render, so a `db/transact!`
   changes the next render). The config→DB migration is in flight on
   another lane, so the defaults deliberately stay code-side; the row is
   the override. (The keyword ns `seon.typeahead` names the DRIVER
   surface later phases add; the design doc pins these keyword names.)"
  (:require
    [clojure.string :as str]
    [my.plan.internal :as plan-internal]
    [seon.agent.ctx.namespaces :as ns-cards]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.repl.internal :as repl-internal]
    [seon.schema :as schema]))

;; ============================================================
;; Glyphs — the model→driver selection vocabulary (①–⑩, all single
;; tokens, measured — typeahead-design "The glyph vocabulary"). The
;; ledger's status glyphs ▶/☐ are driver→model render chrome only (☑ is
;; never rendered here: done items are dropped, not marked).
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
;; Step-loop round budget — how many mode=step calls one provider turn
;; may make before it stops with whatever locked (the P3b driver's cap;
;; mirrors the worker Policy's max_rounds default).
(schema/register! :seon.typeahead/max-rounds :int)

;; The stored row shape — every knob optional (absent = code default;
;; optional = absent, never a stored nil).
(schema/register! :seon.typeahead/policy
  [:map {:seon.db/entity true}
   [:seon.typeahead/id :seon.typeahead/id]
   [:seon.typeahead/auto-offer-margin {:optional true} :seon.typeahead/auto-offer-margin]
   [:seon.typeahead/worst-token-gate  {:optional true} :seon.typeahead/worst-token-gate]
   [:seon.typeahead/probe-budget      {:optional true} :seon.typeahead/probe-budget]
   [:seon.typeahead/menu-cap          {:optional true} :seon.typeahead/menu-cap]
   [:seon.typeahead/max-rounds        {:optional true} :seon.typeahead/max-rounds]])

;; The EFFECTIVE policy view [[policy]] returns — every knob present.
(schema/register! ::policy-view
  [:map
   [:seon.typeahead/auto-offer-margin :seon.typeahead/auto-offer-margin]
   [:seon.typeahead/worst-token-gate  :seon.typeahead/worst-token-gate]
   [:seon.typeahead/probe-budget      :seon.typeahead/probe-budget]
   [:seon.typeahead/menu-cap          :seon.typeahead/menu-cap]
   [:seon.typeahead/max-rounds        :seon.typeahead/max-rounds]])

(def policy-row-id
  "The `:seon.typeahead/id` of the ONE policy singleton row."
  "policy")

(def default-policy
  "The code-default typeahead policy — the row overrides per knob."
  {:seon.typeahead/auto-offer-margin 3.0
   :seon.typeahead/worst-token-gate  0.9
   :seon.typeahead/probe-budget      3
   :seon.typeahead/menu-cap          8
   :seon.typeahead/max-rounds        8})

(defn policy
  "The effective typeahead driver policy in db value `db`.

   [[default-policy]] with the `[:seon.typeahead/id \"policy\"]`
   singleton row's knobs merged over it (per-knob override; an absent
   row/attr keeps the code default). Read at render time — transact the
   row and the next render behaves differently. Never throws: a db
   without the attr installed answers the defaults."
  {:malli/schema [:=> [:catn [::db :seon.db/db]] ::policy-view]}
  [db]
  (let [row (when (and db (contains? (db/installed-schema db) :seon.typeahead/id))
              (db/entity-lazy {:seon.db/db db
                               :seon.db/ref [:seon.typeahead/id policy-row-id]}))]
    (merge default-policy
           (when row (select-keys row (keys default-policy))))))

(defn- menu-cap
  "The effective entry cap for one rendered menu: the policy's
   `:seon.typeahead/menu-cap` bounded by the glyph vocabulary size."
  [db]
  (-> (:seon.typeahead/menu-cap (policy db))
      (min (count glyphs))
      (max 0)))

;; ============================================================
;; :recent-verbs — most recently/frequently eval'd public fns, derived
;; from the eval log + the program graph. Nothing new is stored: the
;; `:seon.eval` rows are the log the turn loop already writes, the
;; `:seon.fn` rows are the boot/tee-indexed program graph, and alias
;; resolution reads the STORED `:seon.ns/require-edges` of each eval's
;; own ns (the one requires store, C36).
;; ============================================================

(def ^:private eval-scan-window
  "How many of the agent's most recent successful evals feed the
   ranking. A window (not all history) keeps the menu tracking what the
   agent is doing NOW and bounds the per-render read."
  30)

(defn- eval-rows
  "The agent's most recent successful eval rows in `db`, newest first.

   `{:seon.eval/at … :seon.eval/source … :seon.eval/ns …}` maps, capped
   at [[eval-scan-window]]. [] when the agent/attrs are absent."
  [db agent-id]
  (->> (db/query {:seon.db/db db
                  :seon.db/query '[:find [?e ...]
                                   :in $ ?aid
                                   :where
                                   [?a :seon.agent/id ?aid]
                                   [?e :seon.eval/agent ?a]
                                   [?e :seon.eval/ok? true]]
                  :seon.db/args [agent-id]})
       (map #(db/pull db [:seon.eval/at :seon.eval/source :seon.eval/ns] %))
       (filter :seon.eval/at)
       (sort-by #(.getTime ^js (:seon.eval/at %)) >)
       (take eval-scan-window)
       vec))

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

(defn- require-info-for
  "The `::seval/require-info` lexical env of ns `ns-kw` in `db` — the
   alias→ns + refers maps its STORED require-edges fold to. The empty
   info for a nil/unstored ns (fully-qualified calls still resolve)."
  [db ns-kw]
  (seval/edges->require-info
    (if ns-kw (seval/stored-require-edges db ns-kw) #{})))

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

(defn- public-fn-row
  "The `:seon.fn` program-graph row for full-sym string `s` when it is a
   PUBLIC fn var (indexed, `:seon.fn/fn-var?`, not private) — else nil."
  [db s]
  (let [e (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.fn/sym s]})]
    (when (and e (:seon.fn/fn-var? e) (not (:seon.fn/private? e)))
      e)))

(defn- ranked-verbs
  "`[full-sym-str fn-row]` pairs for the public fns called across eval
   `rows` (newest first), ranked most-CALLED first, most-RECENT first on
   ties. Uncapped — the section applies the policy menu-cap."
  [db rows]
  (let [infos (into {}
                    (map (fn [k] [k (require-info-for db k)]))
                    (distinct (map :seon.eval/ns rows)))
        occ   (vec (mapcat (fn [{src :seon.eval/source ns-kw :seon.eval/ns}]
                             (keep #(resolve-call-sym (get infos ns-kw) %)
                                   (call-syms src)))
                           rows))
        freq  (frequencies occ)
        seen  (reduce (fn [m [i s]] (if (contains? m s) m (assoc m s i)))
                      {}
                      (map-indexed vector occ))]
    (->> (keys freq)
         (sort-by (juxt #(- (freq %)) seen))
         (keep (fn [s] (when-let [row (public-fn-row db s)] [s row])))
         vec)))

(defn- capped-verbs
  "The rendered/offered `[full-sym-str fn-row]` pairs for agent `id` in
   `db` — [[ranked-verbs]] over the eval window, policy menu-capped. []
   when the db/agent/attrs are absent, so callers share ONE guard. The
   SAME list drives the rendered `:recent-verbs` menu AND the driver's
   wire offers — glyph N always means the same verb on both sides."
  [db id]
  (if (and db id
           (contains? (db/installed-schema db) :seon.eval/agent)
           (contains? (db/installed-schema db) :seon.fn/sym))
    (vec (take (menu-cap db) (ranked-verbs db (eval-rows db id))))
    []))

(def ^:private recent-verbs-header
  (str "; recent verbs — the fns you have been calling, most-used first.\n"
       "; A MENU, never a mandate: select an entry by outputting its glyph\n"
       "; alone (e.g. ①), or ignore this and write any Clojure — both work."))

(defn- verb-line
  "One rendered menu entry: `; <glyph> (<sym> [args] …) — <doc line 1>`.
   The arity grammar is the compact-card one ([[ns-cards/compact-arities]]);
   a fn with no docstring renders without the ` — …` tail."
  [glyph sym-str row]
  (let [doc1 (some->> (:seon.fn/doc row) str/split-lines first str/trim
                      not-empty)
        doc1 (when doc1
               (if (> (count doc1) 78) (str (subs doc1 0 77) "…") doc1))]
    (str "; " glyph " (" sym-str " "
         (ns-cards/compact-arities (:seon.fn/arglists row)) ")"
         (when doc1 (str " — " doc1)))))

(defn recent-verbs-block
  "The `:recent-verbs` menu section — your most-used fns, glyph-listed.

   Derived at render from YOUR eval log (the last [[eval-scan-window]]
   successful evals): every fn called there that is a PUBLIC program-graph
   fn renders as one glyph-numbered entry — glyph, `(fn-sym [args] …)`
   (compact-card arity grammar), docstring line 1 — most-called first,
   most-recent first on ties, capped by the policy `menu-cap`. Aliased
   calls resolve through each eval ns's STORED require-edges; nothing new
   is stored. Selection is strictly optional (the header teaches it);
   REACTIVE: no eval history / no resolvable public fns → \"\" and the
   composer drops the section."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [db    (or db (some-> db/*conn* deref))
        verbs (capped-verbs db id)]
    (if (seq verbs)
      (str recent-verbs-header "\n"
           (str/join "\n"
                     (map-indexed
                       (fn [i [s row]] (verb-line (glyphs i) s row))
                       verbs)))
      "")))

;; ============================================================
;; Driver offers — the SAME capped verb list as the rendered menu, in
;; the step-driver's offer shape (typeahead-design "Wire modes":
;; glyph + label + a clamp/free template). The P3b provider
;; (`seon.ai.typeahead`) converts these to the worker's string-keyed
;; wire maps; glyph N here is glyph N in the rendered `:recent-verbs`
;; section by construction (both read [[capped-verbs]]).
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

(def ^:private offer-args-free-tokens
  "Free tokens a verb template grants for the call's arguments."
  24)

(defn verb-offers
  "Driver offers mirroring the agent's rendered `:recent-verbs` menu.

   One offer per [[capped-verbs]] entry — the selection glyph, a
   `sym [args] …` label, and a `(sym ` + free-args-hole + `)` clamp
   template the driver expands on selection. [] when the agent has no
   menu (same guard as the rendered section), so the wire carries offers
   exactly when the prompt shows the menu."
  {:malli/schema [:=> [:catn [::db :seon.db/db] [:seon.agent/id :string]]
                  ::offers-view]}
  [db id]
  (vec
    (map-indexed
      (fn [i [s row]]
        {:seon.typeahead/glyph    (glyphs i)
         :seon.typeahead/label    (str s " "
                                       (ns-cards/compact-arities
                                         (:seon.fn/arglists row)))
         :seon.typeahead/template [["clamp" (str "(" s " ")]
                                   ["free" offer-args-free-tokens]
                                   ["clamp" ")"]]})
      (capped-verbs db id))))

;; ============================================================
;; :plan-ledger — the open/current plan steps as ▶/☐ glyph lines. The
;; plan IS the todo tree (`my.plan` datoms); this is a second VIEW of it
;; for the glyph-selection channel — done steps dropped from the render
;; entirely (hermes precedent = our derive-don't-store), the `:active`
;; step marked ▶, open steps ☐. Blocked steps are not selectable work
;; and stay off the menu (the `:plan` section's frontier still shows
;; everything with ids).
;; ============================================================

(def ^:private plan-ledger-header
  (str "; plan ledger — your plan's open steps (done steps are dropped;\n"
       "; the full tree stays queryable via my.plan/tree). ▶ = the step\n"
       "; you are on, ☐ = open. Select one by outputting its glyph alone,\n"
       "; or ignore this and write any Clojure — both work."))

(defn plan-ledger-block
  "The `:plan-ledger` menu section — open plan steps as ▶/☐ glyph lines.

   Derived at render from YOUR `my.plan` steps: the `:active` step
   renders first as `▶ ① <title>`, then open steps oldest-first as
   `☐ ② <title>`, capped by the policy `menu-cap` (an overflow renders
   one `… and N more` line). DONE steps are NOT rendered — done-ness is
   derived and the completed interior stays queryable, never in the
   prompt. Selection is strictly optional (the header teaches it);
   REACTIVE: no open/active steps → \"\" and the composer drops the
   section."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [db (or db (some-> db/*conn* deref))]
    (if (and db id (contains? (db/installed-schema db) :my.plan/status))
      (if-let [oe (plan-internal/agent-eid db [:seon.agent/id id])]
        (let [steps (->> (plan-internal/open-steps db oe)
                         (filter #(contains? #{:active :open}
                                             (:my.plan/status %)))
                         (sort-by #(if (= :active (:my.plan/status %)) 0 1)))
              shown (take (menu-cap db) steps)
              more  (- (count steps) (count shown))]
          (if (seq shown)
            (str plan-ledger-header "\n"
                 (str/join "\n"
                           (map-indexed
                             (fn [i {:my.plan/keys [status title]}]
                               (str "; " (if (= :active status) "▶" "☐")
                                    " " (glyphs i) " " title))
                             shown))
                 (when (pos? more)
                   (str "\n; … and " more
                        " more open — (my.plan/next {}) lists them.")))
            ""))
        "")
      "")))
