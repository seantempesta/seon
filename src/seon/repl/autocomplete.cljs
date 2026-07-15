(ns seon.repl.autocomplete
  "REPL autocomplete — byte-exact situation context + turn-mining export.

   The repl-autosuggest lane (docs/prds/repl-autosuggest/design.md): a tiny
   encoder-decoder learns the mapping from a compact SITUATION projection to
   the next REPL forms. This ns owns the seon side of that contract:

     - [[context]] — the encoder input. NOT a new renderer: the ONE prompt
       producer (`seon.agent.ctx/render-context`, the same fn behind
       `render-prompt` and the web UI) invoked with the `:autocomplete`
       render PROFILE (db-stored `:seon.config/context-profiles`, code
       default [[context-blocks]]) — the existing :plan / :transcript
       section fns under tight per-block token caps. Pure over a db VALUE:
       the live db at inference, an exact resolved coordinate at export — same
       function, same bytes, by construction.
     - [[rate!]] + the `::rating`/`::tag` curation attrs — mark turns
       gold/good/excluded for the training corpus.
     - [[export!]] — walk every agent's turns (`seon.agent.ctx/agent-turns`),
       render [[context]] at each turn's complete rendered coordinate, pair it
       with the turn's ok `:seon.eval/source` forms, and write one canonical,
       content-addressed manifest under `data/tune/`.

   If this projection (or any block it renders) changes, previously exported
   datasets are STALE — re-export, never patch (source identity is manifest
   content and therefore changes its digest)."
  (:require
    ["node:child_process" :as child-process]
    ["node:crypto" :as node-crypto]
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.reader :as reader]
    [clojure.string :as str]
    ;; The profile's section fns resolve LATE (symbol slots via
    ;; seon.eval/lookup-value) — required here so every bundle that carries
    ;; this ns also carries the blocks it renders.
    [my.plan.internal]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as ns-cards]
    [seon.agent.ctx.transcript]
    [seon.agent.home :as home]
    [seon.agent.turn :as turn]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.schema :as schema]))

;; ============================================================
;; Curation attrs — datoms ON the turn entity (`:seon.agent.turn/id` is
;; the transactable handle). Genuine judgments, stored with tx-meta
;; provenance like every other write; training weights :gold higher and
;; [[export!]] drops :excluded rows.
;; ============================================================

(schema/register! ::rating [:enum :gold :good :excluded])
(schema/register! ::tag    [:vector :keyword])

(schema/register! ::ok?    :boolean)
(schema/register! ::error  :string)

(schema/register! ::rate-request
  [:map
   [:seon.agent.turn/id :seon.agent.turn/id]
   [::rating ::rating]
   [::tag {:optional true} ::tag]])

(schema/register! ::rate-response
  [:map
   [::ok? ::ok?]
   [:seon.agent.turn/id {:optional true} :seon.agent.turn/id]
   [::error {:optional true} ::error]])

(defn ^:async rate!
  "Rate a turn for the autocomplete corpus (:gold | :good | :excluded).

   Upserts `::rating` (+ optional `::tag` keywords) onto the turn addressed
   by `:seon.agent.turn/id`. An unknown id is refused as a value (no blind
   upsert minting a hollow turn). → `{::ok? true …}` or a fail envelope."
  {:malli/schema [:=> [:cat ::rate-request] ::rate-response]}
  [{turn-id :seon.agent.turn/id rating ::rating tags ::tag}]
  (let [turn (db/entity-lazy {:seon.db/ref [:seon.agent.turn/id turn-id]})]
    (if (nil? (:seon.agent.turn/id turn))
      {::ok? false ::error (str "rate!: no turn " (pr-str turn-id)
                                " — pass a real :seon.agent.turn/id")}
      (let [res (await (db/transact!
                         {:seon.db/tx-data
                          [(cond-> {:seon.agent.turn/id turn-id
                                    ::rating            rating}
                             (seq tags) (assoc ::tag (vec tags)))]}))]
        (if (:seon.db/ok? res)
          {::ok? true :seon.agent.turn/id turn-id}
          {::ok? false ::error (str (:seon.db/error res))})))))

;; ============================================================
;; The projection PROFILE — a block list in the exact `:seon.agent/ctx`
;; manifest shape, rendered by the ONE producer. Total budget ≈ 700 BPE
;; tokens (the needle encoder envelope is 1024; retrieved cards ride
;; separately in the JSONL row).
;; ============================================================

(def context-blocks
  "The default `:autocomplete` render profile — existing blocks, tight caps.

   Reuses the registered section fns verbatim (never a second renderer):
   `:plan` (current position — the windowed plan bands) and `:transcript`
   configured as the RECENT TAIL: the masthead (current-ns line), the last
   turn's events with result heads decayed to stubs, the newest unanswered
   inbound message. Every process/wall-clock byte is OFF: no readline (the
   one live-`now` line), no `result/<id>` handles or resume markers (the
   process-identity bytes) — the render is a pure function of the db VALUE.
   The `:warnings` derivation is deliberately EXCLUDED (its slow-evals check
   reads the wall clock and canvas checks read live vars — impure over a db
   value). Per-block `:seon.agent.ctx/token-cap` bounds each band. The
   CODE default for the config-through-DB `:seon.config/context-profiles`
   `:autocomplete` entry — a db-stored profile (the one in force at an
   as-of t) always wins ([[context]])."
  [{:seon.agent.ctx/name      :plan
    :seon.agent.ctx/priority  45
    :seon.agent.ctx/token-cap 200
    :seon.render/ai           'my.plan.internal/plan-block}
   {:seon.agent.ctx/name      :transcript
    :seon.agent.ctx/priority  100
    :seon.agent.ctx/token-cap 440
    :seon.agent.ctx/cap-keep  :tail
    :seon.render/ai           'seon.agent.ctx.transcript/transcript-block
    ;; recent tail only: last turn verbatim, older evals evicted (a
    ;; zero-budget tier covers every older offset); messages always render
    ;; but the block token-cap bounds the total, keeping the newest tail.
    :seon.agent.ctx.transcript/turns-retained 1
    :seon.agent.ctx.transcript/tiers
    [{:seon.agent.ctx.transcript/from-turn 1
      :seon.agent.ctx.transcript/token-cap 0}]
    ;; result HEADS: every result body clips to a 48-token stub.
    :seon.agent.ctx.transcript/result-decay
    [{:seon.agent.ctx.transcript/from-turn-offset 0
      :seon.agent.ctx.transcript/token-cap 48}]
    :seon.agent.ctx.transcript/readline? false
    :seon.agent.ctx.transcript/result-handles? false
    ;; PINNED as a block constant (the schema default's value): the
    ;; converters then never fall back to their live-conn read — the
    ;; render stays a pure fn of the db value.
    :seon.agent.ctx/escape-clipping? true}])

(defn- profile-from-db
  "The `:autocomplete` entry of the db-stored context profiles, or nil.

   Reads `:seon.config/context-profiles` off the `:seon.config` singleton
   IN the passed db VALUE (never the live conn / config-view) — an as-of
   render regenerates under the profile in force at that t. nil when the
   store predates the attr or carries no `:autocomplete` profile."
  [db]
  (when (and db (contains? (db/installed-schema db)
                           :seon.config/context-profiles))
    (some->> (:seon.config/context-profiles
               (db/entity {:seon.db/db db
                           :seon.db/ref [:seon.config/id
                                         config/cluster-config-id]}))
             (db/decode-edn-value :seon.config/context-profiles)
             :autocomplete
             seq
             vec)))

(schema/register! ::context-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db {:optional true} :seon.db/db]])

(defn context
  "The autocomplete situation projection for one agent, as a bare String.

   THE encoder input (byte-exact contract): the ONE producer
   (`seon.agent.ctx/render-context` — the same fn behind the real prompt
   and the web UI) invoked with the `:autocomplete` render profile — the
   db-stored `:seon.config/context-profiles` entry when the passed db
   carries one (the profile in force at that t), else [[context-blocks]].
   Pure over the db VALUE: pass the live db at inference, or the immutable
   value returned by `db/at-coordinate` at export — identical bytes for
   identical inputs (readline, `result/<id>` handles, and resume markers —
   the wall-clock / process-identity bytes — are off in this profile).
   Deterministic: no wall clock, no randomness."
  {:malli/schema [:=> [:cat ::context-request] :string]}
  [{id :seon.agent/id db :seon.db/db}]
  (let [db (or db @db/*conn*)]
    (ctx/render-context
      {:seon.agent/id id
       :seon.db/db    db
       :seon.agent.ctx/profile (or (profile-from-db db) context-blocks)})))

;; ============================================================
;; Export internals — mining turns into canonical manifest rows.
;; ============================================================

(def ^:private qualified-token-re
  "Qualified-symbol tokens in Clojure source — `alias/name` or
   `full.ns/name`. The lookbehind drops keywords (`:ns/kw`) and
   mid-token tails; quoted symbols stay in (a reference is a use)."
  #"(?<![:A-Za-z0-9*+!?<>=._'-])[A-Za-z][A-Za-z0-9*+!?<>=._'-]*/[A-Za-z*+!?<>=_-][A-Za-z0-9*+!?<>=._'-]*")

(defn- home-alias-maps
  "[aliases refers] for `agent-id`'s home requires.

   `aliases` maps alias-string → ns-string (`\"db\"` → `\"seon.db\"`);
   `refers` maps bare-name → full-sym (`\"complete\"` →
   `\"seon.agent.lifecycle/complete\"`). Derived from
   `seon.agent.home/home-requires-for` — the SAME list that wires the agent's ns."
  [agent-id]
  (reduce (fn [[aliases refers] spec]
            (let [ns-sym (first spec)]
              (case (second spec)
                :as    [(assoc aliases (name (nth spec 2)) (name ns-sym)) refers]
                :refer [aliases
                        (reduce (fn [m r]
                                  (assoc m (name r) (str (name ns-sym) "/" (name r))))
                                refers (nth spec 2))]
                [aliases refers])))
          [{} {}]
          (home/home-requires-for agent-id)))

(defn- indexed-fn-syms
  "The set of every `:seon.fn/sym` string in db value `db`."
  [db]
  (into #{} (map first)
        (db/query {:seon.db/db db
                   :seon.db/query '[:find ?s :where [_ :seon.fn/sym ?s]]})))

(defn- called-syms
  "Full `:seon.fn/sym` strings referenced by `target` source text.

   Best-effort, deterministic: qualified tokens matched directly against
   the indexed fn set, then through the agent's home ALIASES; bare refers
   (`complete`, `wait`) matched by word boundary. Sorted vector."
  [target fn-syms aliases refers]
  (let [qualified (->> (re-seq qualified-token-re target)
                       (keep (fn [tok]
                               (if (contains? fn-syms tok)
                                 tok
                                 (let [[a n] (str/split tok #"/" 2)
                                       full  (some-> (get aliases a) (str "/" n))]
                                   (when (and full (contains? fn-syms full))
                                     full))))))
        referred  (keep (fn [[bare full]]
                          (when (and (contains? fn-syms full)
                                     (re-find (js/RegExp.
                                                (str "(^|[\\s(\\[{,'])"
                                                     (str/replace bare #"[.*+?^${}()|\[\]\\]" "\\$&")
                                                     "($|[\\s)\\]},])"))
                                              target))
                            full))
                        refers)]
    (->> (concat qualified referred) distinct sort vec)))

(defn- fn-record
  "The indexed row and compact card for fn `sym-str`, or nil.

   ONE mechanism with the `:namespaces` compact cards —
   `seon.agent.ctx.namespaces/compact-fn-head` over the indexed
   `:seon.fn` row."
  [db sym-str]
  (when (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.fn/sym sym-str]})
    (let [row (db/pull {:seon.db/db db
                        :seon.db/ref [:seon.fn/sym sym-str]
                        :seon.db/pull-pattern '[:seon.fn/sym :seon.fn/arglists
                                                :seon.fn/doc :seon.fn/spec]})]
      {::fn-row row ::card (ns-cards/compact-fn-head row)})))

(def ^:private keyword-token-re
  "Keyword tokens in Clojure source — `:kw`, `:ns/kw`, `::kw` (the `::`
   form matched literally; its expansion is ns-dependent, so coverage
   checks it as written)."
  #"(?<![:A-Za-z0-9*+!?<>=._'-]):{1,2}[A-Za-z][A-Za-z0-9*+!?<>=._'-]*(?:/[A-Za-z][A-Za-z0-9*+!?<>=._'-]*)?")

(defn- target-identifiers
  "The coverage-checkable identifiers in `target` source text.

   Qualified fn/var symbol tokens, keyword tokens, and short
   whitespace-free string literals (opaque ids — plan ids, agent ids,
   hashes). Deterministic regex extraction, best-effort by design."
  [target]
  (vec (distinct
         (concat (re-seq qualified-token-re target)
                 (re-seq keyword-token-re target)
                 (keep second (re-seq #"\"([^\"\s]{2,40})\"" target))))))

(defn- ingredients-coverage
  "Fraction (0–1, 2 decimals) of `target`'s identifiers present in the
   rendered context+cards `haystack` — the context-GAP measure: a low
   value means the model would have to INVENT identifiers the situation
   never showed it. 1 when the target has no identifiers."
  [target haystack]
  (let [ids (target-identifiers target)]
    (if (empty? ids)
      1
      (-> (count (filter #(str/includes? haystack %) ids))
          (* 100)
          (quot (count ids))
          (/ 100)))))

(defn- distractor-syms
  "`k` deterministic distractor fn syms for a turn (needle-style noise).

   Seeded by the turn id — `(hash (str turn-id \"|\" sym))` orders the
   non-called candidates — so re-export reproduces the same rows byte-for-
   byte. No randomness."
  [fn-syms called turn-id k]
  (->> (remove (set called) (sort fn-syms))
       (sort-by #(hash (str turn-id "|" %)))
       (take k)
       vec))

(defn- git-head-sha
  "The repo's current git HEAD sha (the projection version stamp), or
   \"unknown\" outside a git checkout."
  []
  (try (str/trim (.toString (.execSync child-process "git rev-parse HEAD"
                                       #js {:stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ "unknown")))

(defn- database-name
  "This cluster's database name — the basename of `SEON_CLUSTER_DIR`
   (\"default\" when unset)."
  []
  (let [dir (config/env-string "SEON_CLUSTER_DIR")]
    (if (str/blank? dir) "default" (.basename npath dir))))

(def ^:private export-format "seon.autocomplete.export/v1")
(def ^:private split-policy
  {"id" "sha256-row-id-mod-100/v1"
   "seed" "seon-autocomplete-v1"
   "ranges" {"development" [0 80]
             "milestone" [80 90]
             "test" [90 100]}})

(defn- json-key [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (symbol? k) (str k)
    :else (str k)))

(declare canonical-json)

(defn- canonical-json
  "Stable JSON for content identities. Map keys and set members are sorted;
   Clojure keywords/symbols are represented by their full reader names."
  [x]
  (cond
    (map? x) (str "{" (str/join "," (map (fn [[k v]]
                                            (str (js/JSON.stringify (json-key k))
                                                 ":" (canonical-json v)))
                                          (sort-by (comp json-key key) x))) "}")
    (set? x) (canonical-json (sort-by canonical-json x))
    (sequential? x) (str "[" (str/join "," (map canonical-json x)) "]")
    (keyword? x) (js/JSON.stringify (json-key x))
    (symbol? x) (js/JSON.stringify (str x))
    (uuid? x) (js/JSON.stringify (str x))
    (nil? x) "null"
    :else (js/JSON.stringify x)))

(defn- sha256 [s]
  (-> (.createHash node-crypto "sha256") (.update s "utf8") (.digest "hex")))

(defn- coordinate-data [point]
  {"database_id" (str (::coordinate/database-id point))
   "branch" (name (::coordinate/branch point))
   "commit_id" (str (::coordinate/commit-id point))
   "t" (::coordinate/t point)})

(defn- split-for [row-id]
  (let [bucket (mod (js/parseInt (subs row-id 0 8) 16) 100)]
    (cond (< bucket 80) "development"
          (< bucket 90) "milestone"
          :else "test")))

(defn- source-identity [projection-sha]
  (let [head (git-head-sha)
        diff (try
               (.toString
                 (.execFileSync child-process "git"
                   #js ["diff" "--binary" "HEAD" "--"
                        "src" "config" "deps.edn" "shadow-cljs.edn"
                        "package.json" "package-lock.json"]
                   #js {:stdio #js ["ignore" "pipe" "ignore"]}))
               (catch :default _ "source-diff-unavailable"))]
    {"revision" head
     "projection_sha" projection-sha
     ;; Diagnostic source-world identity. The runtime artifact's application
     ;; digest is the authoritative transitive binding for the ACTUAL compiled
     ;; renderer; manifest content separately binds exact rendered/card/schema
     ;; bytes. Do not mislabel a source-path list as a dependency closure.
     "runtime_root_diff_sha256" (sha256 diff)}))

(defn- runtime-artifact-path []
  (let [flavor (or (config/env-string "SEON_ARTIFACT_FLAVOR") "default")
        proc-dir (or (config/env-string "SEON_PROC_DIR") "tmp/seon-operator")
        file (if (= flavor "acme") "artifact-acme.edn" "artifact.edn")]
    (.resolve npath proc-dir file)))

(defn- runtime-artifact-identity []
  (let [path (runtime-artifact-path)]
    (when-not (.existsSync nfs path)
      (throw (ex-info "autocomplete export requires the canonical runtime artifact manifest"
                      {:path path})))
    (let [manifest (reader/read-string (.readFileSync nfs path "utf8"))
          identity (select-keys manifest
                     [:seon.dev.artifact/version :seon.dev.artifact/flavor
                      :seon.dev.artifact/client-build-id
                      :seon.dev.artifact/writer-digest
                      :seon.dev.artifact/client-digest
                      :seon.dev.artifact/bootstrap-digest
                      :seon.dev.artifact/css-digest
                      :seon.dev.artifact/application-digest])]
      {"identity_sha256" (sha256 (canonical-json identity))
       "manifest" identity})))

(defn- config-identity [db]
  ;; Query attr presence rather than using a lookup ref: historical/test
  ;; databases can carry the singleton before `:seon.config/id` was unique.
  (let [eid (ffirst (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?e :in $ ?id :where [?e :seon.config/id ?id]]
                       :seon.db/args [config/cluster-config-id]}))
        row (some-> (when eid
                      (db/entity-lazy {:seon.db/db db :seon.db/ref eid}))
                    (dissoc :db/id))]
    (sha256 (canonical-json (or row {})))))

(defn- profile-identity [db]
  (sha256 (canonical-json (or (profile-from-db db) context-blocks))))

(defn- rejection-record
  [{::keys [agent-id turn point reason target]}]
  (let [base (cond-> {"agent" agent-id
                      "turn_id" (:seon.agent.turn/id turn)
                      "projection_mode" "observed"
                      "attempted_target" (or target "")
                      "reason" reason}
               (and point (not (:seon.error/message point)))
               (assoc "coordinate" (coordinate-data point)))
        id (sha256 (canonical-json base))]
    (assoc base "rejection_id" id)))

;; ============================================================
;; The exporter.
;; ============================================================

(schema/register! ::out-path       :string)
(schema/register! ::manifest-id    :string)
(schema/register! ::projection-sha :string)
(schema/register! ::distractors    [:int {:min 0}])
(schema/register! ::count          [:int {:min 0}])
;; [min p50 max] token summary of a row column (Token Reporting rule —
;; sizes are ALWAYS tokens; the full distribution is derivable from the
;; manifest rows themselves).
(schema/register! ::token-summary  [:vector :int])

(schema/register! ::export-request
  [:map
   [::out-path       {:optional true} ::out-path]
   [::projection-sha {:optional true} ::projection-sha]
   [::distractors    {:optional true} ::distractors]
   [:seon.db/db      {:optional true} :seon.db/db]])

(schema/register! ::export-response
  [:map
   [::ok?              ::ok?]
   [::out-path         {:optional true} ::out-path]
   [::manifest-id      {:optional true} ::manifest-id]
   [::projection-sha   {:optional true} ::projection-sha]
   [::agents           {:optional true} ::count]
   [::turns-walked     {:optional true} ::count]
   [::rows             {:optional true} ::count]
   [::skipped-no-evals {:optional true} ::count]
   [::skipped-no-coordinate {:optional true} ::count]
   [::skipped-excluded {:optional true} ::count]
   [::cards-missed     {:optional true} ::count]
   ;; Byte-exactness self-check: every row's context is rendered TWICE
   ;; over the same as-of db value; a mismatch is a determinism bug.
   [::determinism-mismatches {:optional true} ::count]
   [::context-tokens   {:optional true} ::token-summary]
   [::target-tokens    {:optional true} ::token-summary]
   [::error            {:optional true} ::error]])

(defn ^:async export!
  "Export every agent's turns as one content-addressed autocomplete manifest.

   Walks agent → runs → turns (`seon.agent.ctx/agent-turns`) over db value
   `:seon.db/db` (default: the live db). A turn contributes one row when it
   has ok `:seon.eval/source` forms AND a complete rendered database
   coordinate; turns rated `:excluded` are dropped. Per row:

     context — [[context]] rendered against the exact retained coordinate
               (the pre-turn database snapshot the model actually saw)
     target  — the turn's ok eval sources, in order, newline-joined
     cards   — compact fn cards for the fns the target CALLS (direct +
               home-alias + refer resolution) plus `::distractors`
               (default 3) deterministic distractor cards
     meta    — turn-id, agent, complete coordinate, database, projection-sha
               (git HEAD unless passed), and rating when present

   Rows, referenced-schema closures, configurations, profiles, deterministic
   split assignments, and addressable rejection records live in one canonical
   content object. The envelope's `manifest_id` is SHA-256 of that object and
   the default path is `data/tune/<manifest-id>.manifest.json`. Repeating an
   export from the same database/source/runtime world is byte-identical.

   Never throws; a failure comes back as `{::ok? false ::error …}`."
  {:malli/schema [:=> [:cat ::export-request] ::export-response]}
  [{db :seon.db/db ::keys [out-path projection-sha distractors]}]
  (try
    (let [db    (or db @db/*conn*)
          sha   (or projection-sha (git-head-sha))
          k     (or distractors 3)
          database (database-name)
          runtime-artifact (runtime-artifact-identity)
          source (source-identity sha)
          agent-ids (->> (db/query {:seon.db/db db
                                    :seon.db/query
                                    '[:find ?id :where [_ :seon.agent/id ?id]]})
                         (map first)
                         sort)
          ;; Attr-presence guard: an old database may predate rating. Complete
          ;; turn-coordinate absence is handled by `turn/rendered-coordinate`.
          installed  (db/installed-schema db)
          rating-ok? (contains? installed ::rating)
          candidates
          (mapcat (fn [agent-id]
                    (let [[aliases refers] (home-alias-maps agent-id)]
                      (map (fn [turn]
                             {::agent-id agent-id
                              ::aliases aliases
                              ::refers refers
                              ::turn turn})
                           (ctx/agent-turns agent-id db))))
                  agent-ids)
          contributions
          (await
            (js/Promise.all
              (into-array
                (map
                  (fn ^:async export-turn [{::keys [agent-id aliases refers turn]}]
                    (let [evals   (sort-by :seon.eval/at
                                           (:seon.agent.turn/evals turn))
                          sources (->> evals
                                       (filter #(true? (:seon.eval/ok? %)))
                                       (map :seon.eval/source)
                                       (remove str/blank?)
                                       vec)
                          point   (turn/rendered-coordinate turn)
                          rating  (when rating-ok? (::rating turn))]
                      (cond
                        (= :excluded rating)
                        {::turns-walked 1 ::skipped-excluded 1
                         ::rejections
                         [(rejection-record
                            {::agent-id agent-id ::turn turn ::point point
                             ::target (str/join "\n" sources)
                             ::reason "excluded-rating"})]}

                        (empty? sources)
                        {::turns-walked 1 ::skipped-no-evals 1
                         ::rejections
                         [(rejection-record
                            {::agent-id agent-id ::turn turn ::point point
                             ::target "" ::reason "no-successful-evals"})]}

                        (:seon.error/message point)
                        {::turns-walked 1 ::skipped-no-coordinate 1
                         ::rejections
                         [(rejection-record
                            {::agent-id agent-id ::turn turn
                             ::target (str/join "\n" sources)
                             ::reason "incomplete-rendered-coordinate"})]}

                        :else
                        (let [aodb (await (db/at-coordinate db/*conn* point))]
                          (if (:seon.error/message aodb)
                            {::turns-walked 1 ::skipped-no-coordinate 1
                             ::rejections
                             [(rejection-record
                                {::agent-id agent-id ::turn turn ::point point
                                 ::target (str/join "\n" sources)
                                 ::reason "unresolvable-rendered-coordinate"})]}
                            (let [ctext   (context {:seon.agent/id agent-id
                                                      :seon.db/db aodb})
                                    ;; determinism self-check: the SAME as-of
                                    ;; db value must render the SAME bytes.
                                    ctext2  (context {:seon.agent/id agent-id
                                                      :seon.db/db aodb})
                                    target  (str/join "\n" sources)
                                    fn-syms (indexed-fn-syms aodb)
                                    called  (called-syms target fn-syms
                                                         aliases refers)
                                    turn-id (:seon.agent.turn/id turn)
                                    syms    (into called
                                                  (distractor-syms fn-syms called
                                                                   turn-id k))
                                    records (vec (keep #(fn-record aodb %) syms))
                                    cards   (mapv ::card records)
                                    specs   (vec (keep (comp :seon.fn/spec ::fn-row)
                                                       records))
                                    schema-text (or (ctx/referenced-schema-block
                                                      {:seon.db/db aodb
                                                       :seon.agent.ctx/seed-specs specs
                                                       :seon.agent.ctx/own-keys #{}})
                                                    "")
                                    schema-id (sha256 schema-text)
                                    config-id (config-identity aodb)
                                    profile-id (profile-identity aodb)
                                    missed  (- (count syms) (count cards))
                                    ;; context-GAP evidence (never "fixed"
                                    ;; here): how much of the target the
                                    ;; situation actually showed the model.
                                  coverage (ingredients-coverage
                                             target
                                             (str ctext "\n"
                                                  (str/join "\n" cards)))]
                              (if (not= ctext ctext2)
                                {::turns-walked 1
                                 ::determinism-mismatches 1
                                 ::rejections
                                 [(rejection-record
                                    {::agent-id agent-id ::turn turn ::point point
                                     ::target target
                                     ::reason "context-determinism-mismatch"})]}
                                (let [row-base
                                      (cond->
                                        {"agent" agent-id
                                         "turn_id" turn-id
                                         "projection_mode" "observed"
                                         "coordinate" (coordinate-data point)
                                         "context" ctext
                                         "cards" cards
                                         "target" target
                                         "coverage" coverage
                                         "schema_closure_id" schema-id
                                         "config_id" config-id
                                         "profile_id" profile-id}
                                        rating (assoc "rating" (name rating)))
                                      row-id (sha256
                                               (canonical-json
                                                 {"seed" (get split-policy "seed")
                                                  "row" row-base}))
                                      row (assoc row-base
                                                 "row_id" row-id
                                                 "split" (split-for row-id))]
                                  {::turns-walked 1
                                   ::rows 1
                                   ::cards-missed missed
                                   ::determinism-mismatches 0
                                   ::ctx-tokens [(tokens/estimate ctext)]
                                   ::target-tokens [(tokens/estimate target)]
                                   ::row-data [row]
                                   ::schema-data [{"id" schema-id
                                                   "definitions" schema-text}]
                                   ::config-data [{"id" config-id}]
                                   ::profile-data [{"id" profile-id}]}))))))))
                  candidates))))
          acc (reduce
                (fn [acc contribution]
                  (reduce-kv
                    (fn [acc k v]
                      (if (vector? v)
                        (update acc k into v)
                        (update acc k + v)))
                    acc
                    contribution))
                {::turns-walked 0 ::rows 0 ::skipped-no-evals 0
                 ::skipped-no-coordinate 0 ::skipped-excluded 0
                 ::cards-missed 0 ::determinism-mismatches 0
                 ::ctx-tokens [] ::target-tokens [] ::row-data []
                 ::rejections [] ::schema-data [] ::config-data []
                 ::profile-data []}
                (js->clj contributions))
          summary (fn [xs]
                    (if (empty? xs)
                      [0 0 0]
                      (let [s (vec (sort xs))]
                        [(first s) (nth s (quot (count s) 2)) (peek s)])))
          dedupe-by-id (fn [xs]
                         (->> xs
                              (reduce (fn [m x] (assoc m (get x "id") x)) {})
                              vals
                              (sort-by #(get % "id"))
                              vec))
          rows (vec (sort-by #(get % "row_id") (::row-data acc)))
          rejections (vec (sort-by #(get % "rejection_id") (::rejections acc)))
          content
          {"format" export-format
           "database" database
           "source" source
           "runtime_artifact" runtime-artifact
           "renderer" {"symbol" "seon.repl.autocomplete/context"
                       "profile" "autocomplete"}
           "split_policy" split-policy
           "schema_closures" (dedupe-by-id (::schema-data acc))
           "configurations" (dedupe-by-id (::config-data acc))
           "profiles" (dedupe-by-id (::profile-data acc))
           "rows" rows
           "rejections" rejections}
          manifest-id (sha256 (canonical-json content))
          manifest {"manifest_id" manifest-id "content" content}
          out (or out-path (str "data/tune/" manifest-id ".manifest.json"))]
      (.mkdirSync nfs (.dirname npath out) #js {:recursive true})
      (.writeFileSync nfs out (str (canonical-json manifest) "\n"))
      {::ok?              true
       ::out-path         out
       ::manifest-id      manifest-id
       ::projection-sha   sha
       ::agents           (count agent-ids)
       ::turns-walked     (::turns-walked acc)
       ::rows             (::rows acc)
       ::skipped-no-evals (::skipped-no-evals acc)
       ::skipped-no-coordinate (::skipped-no-coordinate acc)
       ::skipped-excluded (::skipped-excluded acc)
       ::cards-missed     (::cards-missed acc)
       ::determinism-mismatches (::determinism-mismatches acc)
       ::context-tokens   (summary (::ctx-tokens acc))
       ::target-tokens    (summary (::target-tokens acc))})
    (catch :default e
      {::ok? false
       ::error (str (ex-message e)
                    (when-let [data (ex-data e)] (str " " (pr-str data)))
                    (when-let [stack (.-stack e)] (str "\n" stack)))})))
