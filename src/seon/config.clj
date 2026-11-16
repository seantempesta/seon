(ns seon.config
  "The one config system (DECISIONS, Only documented config). Every
  limit, bound and default of the platform is a key of the `:seon.config/config` map
  (resources/seon/schemas/seon.config*.edn): its entry states its default as `:default`, with
  its unit, provenance and what happens at the bound (`defaults`, every config's bottom layer;
  owner). The aero file resources/seon/config/default.edn states only what a schema
  can't: values read from the process (`#env`, `#seon/checkout`, `#seon/cores`), `#profile`
  values, `#ref`/`#join` paths, and the keys bin/env and bin/tree read from it by text. This
  namespace is its loader and the only place the process environment is read:

  - the checkout (`SEON_CHECKOUT`, which bin/env exports for the binary, else `JOLT_PWD`,
    which `jolt run` callers set), where the file and the checkout-relative paths live;
  - overrides: aero's `#env` in the file itself (leaving: seon.config/per-run-keys-ride-the-command-line),
    and, all on the command line (every mode of the binary; taken out of the mode's
    args): `--profile NAME` (default `:default`; aero's `#profile` reads the file under it, and
    `:seon.config.root/profile` records it), then `--config FILE` and `--set :ns/key=edn`.
    Later layers win, key by key; `sources` names the layer of each key. A checkout's `config/local.edn`
    (gitignored; its ports, and the standing root's source) is no implicit layer: bin/system
    and bin/ports pass it with --config, so a check's or a test's process reads only what its
    command line gives it.

  The shipped file's text is baked when this namespace compiles (`built-text`), so what a process
  loads with moves only at a rebuild and no file on disk can stop it starting. A process then holds
  the config of its own branch's db (`hold!`, docs/config.md#a-process-holds-its-branchs-config):
  every read takes that map, and a fork inherits it. A value resolving to nil (an unset `#env`) is
  absent; each required key's schema :default fills it (malli's default-value-transformer), then the result must satisfy
  `:seon.config/config` (an invalid value, or a required key with no default and no value, refuses with `:seon.config/refused`;
  an unknown key is carried, read by nothing). Root
  writes the resolved config as facts on main (`seon.task/config-tx`: an entity of its own, named by its content, which root's entity refs), platform records a
  merge never carries, where agents, the dashboard and the context read it.

  A foundational namespace, below seon.contract (which reads the instrument config key here): its
  functions carry contracts in their metadata and are armed at boot."
  (:refer-clojure :exclude [resolve])
  (:require [seon.edn :as sedn] [aero.core :as aero] [malli.core :as m] [malli.registry :as mr] [malli.util :as mu] [malli.transform :as mt] [clojure.edn :as edn]
            [clojure.java.io :as io] [clojure.string :as str] [datahike.api :as d] [datahike.db.utils :as dbu]))

;; ---- where: the checkout (the loader's bootstrap environment read) ----------

(defn checkout
  "The checkout this process runs from: SEON_CHECKOUT (bin/env exports it; the binary runs
  from target/), else JOLT_PWD (a `jolt run` from the checkout), or the error naming both.
  Not JOLT_PWD for the binary: Jolt resolves a child process's relative path against it."
  {:malli/schema [:=> [:cat] :string]}
  []
  (let [d (first (remove str/blank? [(System/getenv "SEON_CHECKOUT") (System/getenv "JOLT_PWD")]))]
    (if d
      d
      (let [msg "neither SEON_CHECKOUT nor JOLT_PWD is set: the checkout the config file and its paths are read from (bin/env exports SEON_CHECKOUT)"]
        (throw (ex-info msg {:seon.error/type :seon.config/refused :seon.error/operation (symbol #'seon.config/checkout)
                             :seon.error/message msg}))))))

(defn path
  "The config file in checkout dir."
  {:malli/schema [:=> [:cat :string] :string]}
  [dir] (str dir "/resources/seon/config/default.edn"))

;; ---- machine-derived values: resolved at load, never shipped as a literal --------------------

(defmethod aero/reader 'seon/checkout
  ;; #seon/checkout "rel/path": rel/path under the checkout this process runs from
  [opts _tag value]
  (str (::checkout opts) "/" value))

(defmethod aero/reader 'seon/cores
  ;; #seon/cores nil: the machine's core count (Runtime's availableProcessors), read once when the config is read; the
  ;; value given is not read. What :seon.config.proofs/max-cores, a share, is a share of (docs/config.md#the-file)
  [_opts _tag _value]
  (.availableProcessors (Runtime/getRuntime)))

(defmethod aero/reader 'seon/own-branch
  ;; #seon/own-branch "jolt/seon-merges": the branch as named in the main tree, and in a git
  ;; worktree (its .git a file, not a directory) the branch with -<worktree> appended: worktrees
  ;; share refs, so each commits on a branch of its own (task seon.system/root-repairs-a-failed-merge-from-its-repl, gap 1)
  [opts _tag value]
  (let [co (java.io.File. ^String (::checkout opts))]
    (if (.isFile (java.io.File. co ".git")) (str value "-" (.getName co)) value)))

;; ---- the schemas: read once, when this namespace compiles --------------------------------

(def schemas-dir
  "Where the platform's schema resources live, relative to the checkout: the files the
  build compiles in, and the schema resources seon.source imports and writes back."
  "resources/seon/schemas")

(defmacro ^:private baked-schema-forms
  "Every named schema form of the platform: the maps in resources/seon/schemas/*.edn, read
  when this namespace compiles (the build host has the directory; a built binary carries the
  forms, and bakes no resource files of this project's own paths)."
  []
  (let [dir (io/file (checkout) schemas-dir)]
    (list 'quote
          (reduce merge {} (for [f (sort-by #(.getName %) (file-seq dir))
                                 :when (str/ends-with? (.getName f) ".edn")]
                             (edn/read-string (slurp f)))))))

(def schema-forms
  "Every named schema form of the platform, by name: the config keys' among them, and the
  one baked map seon.contract/forms is."
  (baked-schema-forms))

(def ^:private registry
  (mr/composite-registry (m/default-schemas) (mr/simple-registry schema-forms)))

(def ^:private with-defaults
  "Malli's default-value transformer (malli.transform/default-value-transformer): a decode of a config map, held to config-schema
  (`as-read`: every top-level key required unless its own schema says optional, whatever the branch's entity map says), adds each
  required key it lacks from its :default. Optional keys are never filled, so a model's or provider's entry that leaves a key unset
  still falls through to the next level (resolve-in). See docs/config.md#the-file."
  (mt/default-value-transformer))

(defn- optional?
  "Whether config key k's own schema in map s says it may be absent (:seon.config/optional: its readers take nil as none)."
  {:malli/schema [:=> [:cat :any :qualified-keyword] :boolean]}
  [s k] (boolean (some (fn [[ek _ child]] (and (= ek k) (:seon.config/optional (m/properties (m/deref child))))) (m/children s))))

(defn- as-read
  "The config entity map s as a process reads a config: every key required but those whose own schema says
  :seon.config/optional (malli.util/required-keys), open at its top. The entity schema itself keeps every key optional, as
  every entity schema does (an entity holds the keys it holds). See docs/config.md#the-file."
  {:malli/schema [:=> [:cat :any] :any]}
  [s] (mu/required-keys s (remove (partial optional? s) (map first (m/entries s)))))

(def config-schema "The compiled `:seon.config/config` as a config is read (`as-read`)." (as-read (m/deref (m/schema :seon.config/config {:registry registry}))))

(declare held)

(defn keys-declared
  "Every key the config may hold, in declaration order, as the schema this process holds states them (its branch's: held's
  :schema), never the binary's compiled one: a key the branch deleted is not read back off main's stored config entity."
  {:malli/schema [:=> [:cat] [:vector :qualified-keyword]]}
  [] (mapv first (m/entries (:schema @held))))


;; ---- resolving -------------------------------------------------------------------------------

(defn invalid
  "The thrown error for a config value m that does not satisfy :seon.config/config: one
  problem per refused key (invalid or missing), each naming the key. schema is the
  compiled config map it is held to (default this process's branch's)."
  {:malli/schema [:function [:=> [:cat :map] :seon.config/refused] [:=> [:cat :map :any] :seon.config/refused]]}
  ([m] (invalid m (:schema @held)))
  ([m schema]
  (let [ps (vec (for [{:keys [path in type value]} (:errors (m/explain schema m))]
                  {:key (or (first in) (first path))
                   :problem (case type :malli.core/missing-key :missing-key :invalid)
                   :value (sedn/encode value)}))]
    {:seon.error/type :seon.config/refused :seon.error/operation (symbol #'seon.config/invalid)
     :seon.error/message (str "the config does not satisfy :seon.config/config: "
                              (str/join ", " (for [{:keys [key problem]} ps] (str key " " (name problem)))))
     :seon.config/problems ps})))

(defn- refuse!
  "Throw what raiser op (by its var: the compile resolves it) refuses, with msg."
  {:malli/schema [:=> [:cat :seon.host/var :string] :nil]}
  [op msg] (throw (ex-info msg {:seon.error/type :seon.config/refused :seon.error/operation (symbol op) :seon.error/message msg})))

(defn resolve-in
  "Setting k in config c (its layer sources srcs) for ctx {:task m :model id}: the task's or
  agent's own settings (a :seon.task/config map), then the model's entry (default
  :seon.config.ai/model), then that model's provider's, then c's own key. Answers {:value
  :source}, :source one of [:task], [:model id], [:provider id], [:config layer], or [:absent] with value nil for a key
  whose own schema says :seon.config/optional, which its readers take as none. Any other key set nowhere is a defect of the caller:
  the error names it."
  {:malli/schema [:=> [:cat :map :map :qualified-keyword :seon.config/context] :seon.config/resolved]}
  [c srcs k {:keys [task model]}]
  (let [m (or model (:seon.config.ai/model c))
        me (get-in c [:seon.config.ai/models m])
        p (:seon.ai.model/provider me)
        pe (get-in c [:seon.config.ai/providers p])]
    (cond
      (contains? task k) {:value (get task k) :source [:task]}
      (contains? me k) {:value (get me k) :source [:model m]}
      (contains? pe k) {:value (get pe k) :source [:provider p]}
      (contains? c k) {:value (get c k) :source [:config (get srcs k "resources/seon/config/default.edn")]}
      ;; a key the config may lack reads nil, which its readers take as none (my.root's no-source-root errors)
      (optional? (:schema @held) k) {:value nil :source [:absent]}
      :else (refuse! #'seon.config/resolve-in (str k " is set nowhere: not by the task, the model " m ", its provider "
                                               p " or the config (declared keys: seon.config/keys-declared)")))))

(def ^:private reasoning-pair (m/schema :seon.config.ai/reasoning-pair {:registry registry}))

(defn- invalid-pairs
  "Each model of config m whose thinking and reasoning replay (as the resolver reads them for
  it, task -> model -> provider -> config) do not satisfy :seon.config.ai/reasoning-pair: its
  key and m/explain's errors."
  {:malli/schema [:=> [:cat :map] [:vector :seon.config/problem]]}
  [m]
  (vec (for [id (sort (keys (:seon.config.ai/models m)))
             :let [me (get-in m [:seon.config.ai/models id])
                   layers [me (get-in m [:seon.config.ai/providers (:seon.ai.model/provider me)]) m]
                   ;; a key no layer sets is absent (thinking: the provider's default)
                   pair (into {} (for [k [:seon.config.ai/thinking :seon.config.ai/replay-reasoning]
                                       :when (some #(contains? % k) layers)]
                                   [k (:value (resolve-in m {} k {:model id}))]))
                   e (m/explain reasoning-pair pair)]
             :when e]
         {:key [:seon.config.ai/models id] :problem :invalid
          :value (str (sedn/encode pair) ": " (:description (m/properties reasoning-pair)) " " (sedn/encode (mapv #(select-keys % [:path :value]) (:errors e))))})))

(defn resolved
  "Raw (what aero read) as the config: nil values dropped (absent), each required key it lacks filled from its schema's :default
  (with-defaults), then validated against
  :seon.config/config, and each model's thinking with its reasoning replay against
  :seon.config.ai/reasoning-pair (invalid-pairs). schema is the compiled config map it is held
  to (default this process's branch's). Answers the map, or throws the error."
  {:malli/schema [:function [:=> [:cat :map] :map] [:=> [:cat :map :any] :map]]}
  ([raw] (resolved raw (:schema @held)))
  ([raw schema]
   (let [m (m/decode schema (into {} (remove (comp nil? val)) raw) with-defaults)]
     (if-not (m/validate schema m)
       (let [r (invalid m schema)] (throw (ex-info (:seon.error/message r) r)))
       (let [ps (invalid-pairs m)]
         (if (empty? ps)
           m
           (let [msg (str "the config does not satisfy :seon.config.ai/reasoning-pair: " (str/join ", " (map (comp pr-str :key) ps)))]
             (throw (ex-info msg {:seon.error/type :seon.config/refused :seon.error/operation (symbol #'seon.config/resolved)
                                  :seon.error/message msg :seon.config/problems ps})))))))))

;; ---- the layers: the shipped file, the checkout's own file, the command line ------------------
;; Owner: "Hand out different edn config files that each agent can load and control
;; separately or do command line overrides." Later layers win, key by key; the merged map is
;; validated once, and every key remembers the layer it came from (`sources`).

(defn argv
  "This process's command line, as *command-line-args* holds it while namespaces load (a
  `jolt run`, and a built binary since Jolt's jolt/argv): the one place the loader reads it,
  so --config and --set apply to every mode."
  {:malli/schema [:=> [:cat] [:vector :string]]}
  [] (vec *command-line-args*))

(defn parse-set
  "--set's argument `:ns/key=edn` as [key value], or the error naming it."
  {:malli/schema [:=> [:cat :string] [:tuple :qualified-keyword :any]]}
  [s]
  (let [i (str/index-of s "=")
        k (when i (try (edn/read-string (subs s 0 i)) (catch Exception _ nil)))
        v (when i (try [(edn/read-string (subs s (inc i)))] (catch Exception _ nil)))]
    (if (and (qualified-keyword? k) v)
      [k (first v)]
      (refuse! #'seon.config/parse-set (str "--set " s ": expected :ns/key=<edn value>")))))

(defn overrides
  "The command line's config overrides, in order, and the rest of it:
  {:layers [[source map-or-path] ...] :rest [arg ...] :profile NAME-or-nil}. `--config FILE` is
  a layer read from FILE; `--set :k=v` one key; `--profile NAME` the aero profile the files
  are read under (not a layer: the last one given wins)."
  {:malli/schema [:=> [:cat [:sequential :string]] [:map [:layers vector?] [:rest [:vector :string]] [:profile [:maybe :string]]]]}
  [args]
  (loop [[a b & more :as as] (seq args) acc {:layers [] :rest [] :profile nil}]
    (cond
      (empty? as) acc
      (and (= "--profile" a) b) (recur more (assoc acc :profile b))
      (and (= "--config" a) b) (recur more (update acc :layers conj [(str "--config " b) b]))
      (and (= "--set" a) b) (recur more (update acc :layers conj ["--set" (into {} [(parse-set b)])]))
      (#{"--config" "--set" "--profile"} a) (refuse! #'seon.config/overrides (str a " needs an argument"))
      :else (recur (rest as) (update acc :rest conj a)))))

(defn without-overrides
  "args with --profile, --config and --set (and their arguments) taken out: what a mode reads."
  {:malli/schema [:=> [:cat [:maybe [:sequential :string]]] [:vector :string]]}
  [args] (:rest (overrides args)))

(declare config)

(defn profile
  "The aero profile the config file is read under: --profile NAME on args as a keyword, else
  :default; with no args, the profile of the config in force (:seon.config.root/profile, read
  once from this process's command line when the config loaded: *command-line-args* is unbound
  in an eval's thread). See docs/config.md#layers."
  {:malli/schema [:function [:=> [:cat] :keyword] [:=> [:cat [:sequential :string]] :keyword]]}
  ([] (or (get config :seon.config.root/profile) (profile (argv))))
  ([args] (if-let [p (:profile (overrides args))] (keyword p) :default)))

(defn- read-layer
  "One file layer read with aero under prof, #seon/checkout resolving under dir; nil values
  are not stated (absent at this layer)."
  {:malli/schema [:=> [:cat :any :keyword :string] :map]}
  [p prof dir]
  (into {} (remove (comp nil? val)) (aero/read-config p {:profile prof ::checkout dir})))

(defn layered
  "The config of the layers [[source map] ...] (later wins, key by key), resolved once against schema (`resolved`: a key no
  layer states takes its schema's :default, source \"schema\") (default this process's branch's): {:config :sources}, :sources naming the layer
  each key came from. See docs/config.md#layers."
  {:malli/schema [:function [:=> [:cat [:sequential [:tuple :string :map]]] [:map [:config :map] [:sources :seon.config/sources]]]
                  [:=> [:cat [:sequential [:tuple :string :map]] :any] [:map [:config :map] [:sources :seon.config/sources]]]]}
  ([layers] (layered layers (:schema @held)))
  ([layers schema]
   (let [m (reduce (fn [m [_ l]] (merge m l)) {} layers)
         srcs (into {} (for [[src l] layers [k v] l :when (some? v)] [k src]))
         c (resolved m schema)]
     {:config c
      ;; a key no layer stated came from its schema's :default
      :sources (into srcs (for [k (keys c) :when (not (contains? srcs k))] [k "schema"]))})))

(def shipped-file "The shipped config, relative to the checkout." "resources/seon/config/default.edn")

(defn- layers-over
  "The layers this process reads over base, read by base-of under the command line's --profile: the shipped layer, the
  profile's own record (:seon.config.root/profile), then its --config files and --set keys, in order."
  {:malli/schema [:=> [:cat ifn? :string [:sequential :string]] [:vector [:tuple :string :map]]]}
  [base-of dir args]
  (let [{:keys [layers profile]} (overrides args)
        prof (if profile (keyword profile) :default)]
    (into [[shipped-file (base-of prof)]
           [(if profile (str "--profile " profile) "no --profile") {:seon.config.root/profile prof}]]
          (for [[src x] layers]
            [src (if (string? x) (read-layer x prof dir) x)]))))

(defn load-layers
  "The layers a process of checkout dir would read, args its command line: dir's shipped file, as this process's branch states it when dir is this checkout. The checkout's config/local.edn is a layer only when a
  --config names it (bin/system, bin/ports): read implicitly, it gave a check's root in the
  main tree the standing root's source tree (task seon.check/a-checks-roots-take-explicit-config)."
  {:malli/schema [:=> [:cat :string [:sequential :string]] [:vector [:tuple :string :map]]]}
  [dir args]
  (layers-over #(read-layer (if (= dir (checkout)) (java.io.StringReader. (:text @held)) (path dir)) % dir) dir args))

(defmacro ^:private baked-shipped-text
  "The shipped config file's text, read when this namespace compiles, as baked-schema-forms reads the schemas. See docs/config.md#a-process-holds-its-branchs-config."
  [] (slurp (path (checkout))))

(def built-text
  "The shipped config file as this binary was built: what a process loads with, whatever the file on disk says now. See docs/config.md#a-process-holds-its-branchs-config."
  (baked-shipped-text))

(def ^:private built-layers
  "This process's layers as it loaded: built-text, then its command line's, each read once. See docs/config.md#layers."
  (let [dir (checkout)]
    (layers-over #(read-layer (java.io.StringReader. built-text) % dir) dir (argv))))

(def ^:private loaded (layered built-layers config-schema))

(def config
  "This process's config as it loaded, built-text under its layers: what a read at load takes, and the value a branch's differs from. A read of a setting takes the held map (in-force)."
  (:config loaded))

(def sources
  "Which layer each key of the config as it loaded came from."
  (:sources loaded))

(defn process-of
  "A process's own facts (:seon.config/process) from what it started with: layers (its command line's, as layers-over reads
  them: the --config files and --set keys after the shipped file), getenv (the environment variable named, or nil), its checkout dir,
  and the machine's cores. A fact a later layer states wins over an earlier one, any layer over the environment, and the environment
  over its default: the store under the runs directory, the runs directory under the checkout, main \"main\", no port (0), no
  source tree. Pure. The shipped file states none of them: a branch never states a process's own facts (task
  seon.config/config-is-schema-defaults-and-datoms, B1). See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat [:sequential [:tuple :string :map]] ifn? :string :int] :seon.config/process]}
  [layers getenv dir cores]
  (let [stated (reduce (fn [m [_ x]] (merge m x)) {} (rest layers))
        long-env #(some-> (getenv %) parse-long)
        of (fn [k env default] (if (contains? stated k) (get stated k) (or (getenv env) default)))
        runs (of :seon.config.root/runs-dir "SEON_RUNS" (str dir "/runs"))]
    {:ports {:server (get stated :seon.config.root/server-port (or (long-env "SEON_SERVER_PORT") 0))
             :web (get stated :seon.config.web/port (or (long-env "SEON_WEB_PORT") 0))}
     :store (of :seon.config.root/store-path "SEON_STORE" (str runs "/store")) :runs runs
     :main (of :seon.config.root/main "SEON_MAIN" "main") :source-root (of :seon.config.source/root "SEON_SOURCE_ROOT" nil)
     :checkout dir :cores cores :end-with (get stated :seon.config.root/end-with (or (long-env "SEON_END_WITH") 0))
     ;; read from the config until B3 moves it: a test's digest takes it (seon.fx/config-input)
     :embed-model (:seon.config.vector/model config)}))

(defn process
  "This process's own facts (process-of over the layers it started with, its environment, checkout and cores). The seam until flow's
  start map carries them as :process (task seon.step/roots-procs-are-the-first-forks-graph (vii)); a test child's layers are its own
  (seon.agent/hold-env!). See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat] :seon.config/process]}
  [] (process-of built-layers #(System/getenv %) (checkout) (:seon.config.machine/cores config)))

(def ^:private held
  "The config this process reads: :config and :sources, its branch's (hold!) or as it loaded; :schema, the config map compiled as its branch states it; :text, the shipped file as its branch states it; :entries and :changes, what they were built from; :waiting, the keys it does not act on (waiting). See docs/config.md#a-process-holds-its-branchs-config."
  (atom (assoc loaded :schema config-schema :text built-text :waiting {})))

(defn in-force
  "This process's config as its branch states it, {:config}: built once at its start, refreshed only when a transaction touches an entry. Not which layer each key came
  from: that differs by process while a digest takes no place, so a reader of it could be green in one process and red in another at one digest. Root's boot writes them on
  main (boot-sources), and every reader reads them there. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat] [:map [:config :map]]]}
  [] (select-keys @held [:config]))

(defn boot-sources
  "The layer each config key came from in this process, for root's boot alone, to write on main (:seon.config/sources). See in-force."
  {:malli/schema [:=> [:cat] :seon.config/sources]}
  [] (:sources @held))

(defn shipped-text
  "The shipped file's text as this process's branch states it: built-text, or its entries, one a line. What a reader of the file as the program holds it reads, never the disk. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat] :string]}
  [] (:text @held))

(defn waiting
  "The keys whose value this process's branch states and this process does not act on, {k {:running :stated}}: a place, read before any db, and a key read at load (:seon.config/at-load), until a rebuild. See docs/config.md#what-waits-for-a-rebuild."
  {:malli/schema [:=> [:cat] [:map-of :qualified-keyword :map]]}
  [] (:waiting @held))

(defn- schema-of
  "The config map compiled with changes ({k form}, the schema entries a branch holds beyond the binary's) over the binary's forms: the binary's own when there are none."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :any]] :any]}
  [changes]
  (if (empty? changes)
    config-schema
    (as-read (m/deref (m/schema :seon.config/config {:registry (mr/composite-registry (m/default-schemas) (mr/simple-registry (merge schema-forms changes)))})))))

(defn- entries-text
  "The file's text of entries {k form}: a map, one entry a line, each form as stated. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :string]] :string]}
  [entries]
  (str "{\n " (str/join "\n " (for [[k form] (sort-by (comp str key) entries)] (str k " " form))) "}\n"))

(defn- of-key
  "Whether a key's schema form among forms says prop."
  {:malli/schema [:=> [:cat :map :any :keyword] :any]}
  [forms k prop]
  (let [f (get forms k)] (and (vector? f) (map? (second f)) (get (second f) prop))))

(defn of-entries*
  "Under layers (a process's, as layers-over makes them) and its profile prof, the config entries ({k form}, each form its text, read here as aero reads the file: its tags resolved for this process) state, as this process's layers over them: {:config :sources :schema :text :waiting}, or the :seon.config/refused error. A place keeps the value this process loaded with, since it is read before any db. changes: the branch's schema entries beyond the binary's. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :string] [:map-of :qualified-keyword :any] [:vector [:tuple :string :map]] :keyword] :map]}
  [entries changes layers prof]
  (let [forms (merge schema-forms changes)
        text (entries-text entries)
        ;; what this process loaded: its binary's schema defaults under its shipped file, as it loaded them (`loaded`), so a place
        ;; whose value is a schema :default has it on every branch, whatever that branch's own schema entries say (a branch made
        ;; before the defaults moved into the schema holds entries with no :default: its agents lost cwd, dirs and main, 2026-11-16)
        built (m/decode config-schema (second (first layers)) with-defaults)
        place? #(of-key forms % :seon.config/place)
        schema (schema-of changes)]
    ;; docs/failure-points.md: an entry's text that aero can't read, or a map that doesn't validate, is this answer
    (try
      (let [stated (read-layer (java.io.StringReader. text) prof (checkout))
            base (merge (into {} (remove (comp place? key)) stated) (into {} (filter (comp place? key)) built))
            r (layered (assoc-in layers [0 1] base) schema)]
        ;; what waits compares the values in force on both sides, each over its schema's defaults: a key the file no longer states
        ;; resolves to its default, so a branch that changes an at-load key's :default waits for a rebuild as a changed entry does
        (let [stated (m/decode schema stated with-defaults)]
          (assoc r :schema schema :text text
                 :waiting (into {} (for [k (distinct (concat (keys stated) (keys built)))
                                         :when (and (or (place? k) (of-key forms k :seon.config/at-load))
                                                    (not= (get stated k) (get built k)))]
                                     [k {:running (get built k) :stated (get stated k)}])))))
      (catch Throwable e
        (or (when (= :seon.config/refused (:seon.error/type (ex-data e))) (ex-data e))
            {:seon.error/type :seon.config/refused :seon.error/operation (symbol #'seon.config/of-entries*)
             :seon.error/message (str "the config's entries do not read: " (ex-message e))})))))

(defn of-entries
  "of-entries* under this process's own layers and profile: what its branch's entries state, as it holds them. See
  docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat [:map-of :qualified-keyword :string] [:map-of :qualified-keyword :any]] :map]}
  [entries changes] (of-entries* entries changes built-layers (get config :seon.config.root/profile)))

(defn entries
  "The config entries db holds, {k form}, or nil when it holds none (a store imported before them, a test's world). See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe [:map-of :qualified-keyword :string]]]}
  [db]
  (not-empty (into {} (for [dt (d/datoms db :aevt :config/form)] [(:config/key (d/entity db (:e dt))) (:v dt)]))))

(def test-system-config
  "The config a process test's root runs with: the shipped file under the scripted profile (every test starts its roots
  under --profile scripted, with no config/local.edn and none of the planning process's layers), one value a process. What
  a process test's digest takes for what root runs (seon.program/roots-of) and for the config values its reach reads
  (the planners' :test-system-config), so that digest is one whichever process plans it: an agent of the standing system, under
  its own profile and local.edn, gave 103 of 180 process digests apart from the shell's (task
  seon.prove/one-digest-whichever-runner-plans-it). See docs/source.md#tests-run-by-reach."
  (m/decode config-schema (read-layer (str (checkout) "/" shipped-file) :scripted (checkout)) with-defaults))

(defn hold!
  "Make this process's config what es and changes state (of-entries), when they differ from what it holds. Its :schema follows changes even when the entries are refused, so a branch can name a key before it states it; what it reads stays as it was. Answers nil, or the refusal. es nil (nothing stated) holds nothing. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat [:maybe [:map-of :qualified-keyword :string]] [:map-of :qualified-keyword :any]] [:maybe :seon.config/refused]]}
  [es changes]
  (when (and es (not (and (= es (:entries @held)) (= changes (:changes @held)))))
    (let [r (of-entries es changes)]
      (if (:seon.error/type r)
        (do (swap! held assoc :schema (schema-of changes)) r)
        (do (reset! held (assoc r :entries es :changes changes)) nil)))))

(defn held-for
  "What a process of role args (its command line's layers: a test process's [\"--profile\" \"scripted\"]) holds over es, its
  branch's config entries (none: the binary's shipped text), and schema changes, as a value: {:layers :value}, :value what `held`
  holds, and :refused the refusal of es when they do not read (the layers' own config held then). Pure: no atom, no var. A
  process's layers come from its role, never its parent's (test-system-config's rule). See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat [:sequential :string] [:maybe [:map-of :qualified-keyword :string]] [:map-of :qualified-keyword :any]] :map]}
  [args es changes]
  (let [dir (checkout)
        layers (layers-over #(read-layer (java.io.StringReader. built-text) % dir) dir args)
        ;; the binary's shipped text is held to the binary's schema, the branch's entries to the branch's (of-entries*): a key the
        ;; branch deleted, which the binary still ships, never meets the branch's schema (every test child died so)
        r0 (layered layers config-schema)
        v0 (assoc r0 :schema (schema-of changes) :text built-text :waiting {})
        ;; the entries are read under this process's own profile, never its parent's
        r (when es (of-entries* es changes layers (get-in r0 [:config :seon.config.root/profile])))]
    (cond (nil? es) {:layers layers :value v0}
          (:seon.error/type r) {:layers layers :value v0 :refused r}
          :else {:layers layers :value (assoc r :entries es :changes changes)})))

(defn install!
  "Make this process hold h, what held-for answered: its layers as loaded, its config held and as loaded (config and sources,
  which readers at load take). Answers h's refusal, or nil. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat :map] [:maybe :seon.config/refused]]}
  [{:keys [layers value refused]}]
  (alter-var-root #'built-layers (constantly layers))
  (reset! held value)
  (alter-var-root #'config (constantly (:config value)))
  (alter-var-root #'sources (constantly (:sources value)))
  refused)

(defn hold-args!
  "Make this process hold what held-for answers for args, es and changes (install!). seon.agent/hold-env! calls it as a test
  child's first act when its agent handed it no test config (test-held!). Answers nil, or the refusal of es. See
  docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat [:sequential :string] [:maybe [:map-of :qualified-keyword :string]] [:map-of :qualified-keyword :any]] [:maybe :seon.config/refused]]}
  [args es changes] (install! (held-for args es changes)))

(defn test-held!
  "What a test child of this process holds (held-for args over this branch's entries and schema changes, read by es-f and ch-f),
  derived once for the db point k (seon.db/point) and kept beside what this process holds, so every test child takes it whole
  (install!) and parses and validates nothing: 8.1M ticks a child before (runtime, result/d10c5747). A move of this process's
  config (hold!) drops it. See docs/config.md#a-process-holds-its-branchs-config."
  {:malli/schema [:=> [:cat :any [:sequential :string] ifn? ifn?] :map]}
  [k args es-f ch-f]
  (or (get-in @held [::test k])
      (let [h (held-for args (es-f) (ch-f))]
        (swap! held assoc ::test {k h})
        h)))

(defn text-refusal
  "Why text can't be the shipped config on this process's branch, as a boot reads it with no profile, or nil: its message names each key. What a write of the file checks first (seon.fx/unreadable). See docs/source.md#a-program-file."
  {:malli/schema [:function [:=> [:cat :string] [:maybe :string]] [:=> [:cat :string [:maybe [:map-of :qualified-keyword :any]]] [:maybe :string]]]}
  ([text] (text-refusal text nil))
  ;; changes: schema entries the same change states ({k form}, a branch's schema files read with it), over the entries this process's
  ;; branch already holds beyond the binary's (held's :changes), so a config key and its schema changed together are checked against
  ;; each other and the branch, never against the binary's schema with the change's alone (a key the branch deleted stays deleted)
  ([text changes]
  ;; docs/failure-points.md: text that aero can't read, or a map that doesn't validate, is this answer
  (try
    (layered [[shipped-file (read-layer (java.io.StringReader. text) :default (checkout))]
              ["no --profile" {:seon.config.root/profile :default}]]
             (if (seq changes) (schema-of (merge (:changes @held) changes)) (:schema @held)))
    nil
    (catch Throwable e (ex-message e)))))

;; ---- reading a setting: the one resolver ------------------------------------------------------
;; Owner: settings are per model, per provider and per task; "a principle and not just
;; a hack": every reader resolves through `resolve-in` (task -> model -> its provider -> the
;; config's own key), and the server shows each value with where it came from.

(defn resolve
  "Setting k for ctx ({:task m :model id}; default {}) in the config this process holds (in-force): {:value
  :source} (resolve-in)."
  {:malli/schema [:function [:=> [:cat :qualified-keyword] :seon.config/resolved]
                  [:=> [:cat :qualified-keyword :seon.config/context] :seon.config/resolved]]}
  ([k] (resolve k {}))
  ([k ctx] (let [{c :config srcs :sources} @held] (resolve-in c srcs k ctx))))

(defn value
  "Setting k's value for ctx (resolve), the read every reader makes."
  {:malli/schema [:function [:=> [:cat :qualified-keyword] :any] [:=> [:cat :qualified-keyword :seon.config/context] :any]]}
  ([k] (value k {}))
  ([k ctx] (:value (resolve k ctx))))

(defn file-kind
  "The kind :seon.config.source/things gives the file at relative path, or nil: a pattern is a path whose one * stands for any name in that directory. See docs/source.md#a-program-file."
  {:malli/schema [:=> [:cat :string] [:maybe :keyword]]}
  [path]
  (some (fn [[pattern kind]]
          (let [[before after] (str/split pattern #"\*" 2)]
            (when (and (str/starts-with? path before) (str/ends-with? path (or after ""))
                       (<= (+ (count before) (count after)) (count path))
                       (not (str/includes? (subs path (count before) (- (count path) (count after))) "/")))
              kind)))
        (value :seon.config.source/things)))

(defn pin-class
  "The :seon.pin class of submodule path p: its path under reference-code/ with / as .
  (reference-code/datahike is :seon.pin/datahike). seon.fx/inputs digests each one, and a test's reach
  takes the classes of the namespaces it references (seon.program/config-reads)."
  {:malli/schema [:=> [:cat :string] :qualified-keyword]}
  [p]
  (keyword "seon.pin" (str/replace (str/replace-first p #"^reference-code/" "") "/" ".")))

;; ---- the model provider, as the config states it --------------------------------------------

(defn provider
  "The model provider config c names (:seon.config.ai/*, resolved for its model: thinking too, so a model's or a task's wins over the file's, and a task's reasoning effort; docs/model.md#thinking-is-on-at-low-effort): none, the
  scripted provider (a test fixture), or the OpenAI-compatible endpoint with the NAME of its key's
  environment variable (never the key)."
  {:malli/schema [:=> [:cat :seon.config/config] :seon.model/provider]}
  [c]
  (let [v #(:value (resolve-in c {} % {}))]
    (case (:seon.config.ai/provider c)
      :none {:kind :none}
      :scripted {:kind :scripted :script (:seon.config.ai/script c)}
      :openai-compat (cond-> {:kind :openai-compat :base-url (v :seon.config.ai/base-url) :model (:seon.config.ai/model c)
                              :api-key-env (v :seon.config.ai/api-key-variable) :max-tokens (v :seon.config.ai/max-tokens)
                              :replay-reasoning (v :seon.config.ai/replay-reasoning)}
                       ;; thinking is shipped (default.edn), so the resolver reads it, a model's or a task's over the file's; temperature
                       ;; is left out of the file on purpose (the provider's own default), and effort may be, so each is read where a
                       ;; layer states it, the task's merged into c by provider-at (docs/model.md#thinking-is-on-at-low-effort)
                       true (assoc :thinking (v :seon.config.ai/thinking))
                       (:seon.config.ai/temperature c) (assoc :temperature (:seon.config.ai/temperature c))
                       (:seon.config.ai/reasoning-effort c) (assoc :reasoning-effort (:seon.config.ai/reasoning-effort c))))))

(defn with-provider
  "Config c stating provider p (a provider handed to root's start! in place of the file's):
  the facts root writes say what the run really used (an endpoint p names is its model's
  provider's)."
  {:malli/schema [:=> [:cat :seon.config/config :seon.model/provider] :seon.config/config]}
  [c p]
  (let [top {:seon.config.ai/provider (:kind p) :seon.config.ai/script (:script p) :seon.config.ai/model (:model p)
             :seon.config.ai/max-tokens (:max-tokens p)
             :seon.config.ai/temperature (:temperature p) :seon.config.ai/thinking (:thinking p) :seon.config.ai/reasoning-effort (:reasoning-effort p)}
        c (into c (filter (comp some? val)) top)
        prov (get-in c [:seon.config.ai/models (:seon.config.ai/model c) :seon.ai.model/provider])
        wire (into {} (filter (comp some? val)) {:seon.config.ai/base-url (:base-url p) :seon.config.ai/api-key-variable (:api-key-env p)})]
    (if (and prov (seq wire)) (update-in c [:seon.config.ai/providers prov] merge wire) c)))

;; ---- the facts on main ------------------------------------------------------------------------

(defn moved
  "This process's config and sources as {:config :sources} when ops (resolved tx-data, read against db after them) touch config entries, else nil: what main's config entity is written again from (seon.task/config-tx), a new entity when its content moved, which root's ref then names. A place, which this process doesn't act on, stays. See docs/config.md#facts-on-main."
  {:malli/schema [:=> [:cat :seon.host/db [:sequential :any]] [:maybe [:map [:config :map] [:sources :seon.config/sources]]]]}
  [db ops]
  (when (some (fn [[_ e a]] (and (= a :config/form) (:config/key (d/entity db e)))) ops)
    (select-keys @held [:config :sources])))

(defn secret
  "The value of the environment variable named var-name (a credential, e.g. the model's key,
  named by :seon.config.ai/api-key-variable): read at its one use, never stored, never
  printed. nil when unset."
  {:malli/schema [:=> [:cat :string] [:maybe :string]]}
  [var-name] (System/getenv var-name))

(defn from-db
  "The config root wrote on db (main, or any branch made from it since): every declared key
  the entity root's :seon.system/config names holds, each as its schema declares it. What agents, the dashboard and the
  context read. A [:set T] key is a cardinality-many attribute (seon.schema.datahike/
  declaration), which a pull answers as a vector: it is read back as the set it was (the
  first set-valued key, :seon.config.source/process-vars, came back a vector, so the config
  in force and main's copy of it differed)."
  {:malli/schema [:=> [:cat :seon.host/db] [:maybe :map]]}
  [db]
  (not-empty (into {} (for [[k v] (:seon.system/config (d/pull db [{:seon.system/config (keys-declared)}] [:seon.system/id :root]))]
                        [k (if (dbu/multival? db k) (set v) v)]))))

(defn provider-at
  "The model provider as the config stands now: main's config entity (from-db: what root wrote
  at boot and any change since), over this process's own config for a key it does not hold, and the
  task's own settings over both (the declared :seon.config.* keys its entity holds on task-db, the
  flattened :seon.task/config): the one resolver (resolve-in: task, model, provider, config) read at
  the call, never a copy taken at boot. A task naming :seon.config.ai/provider or a model uses it
  from its next turn; a change to main's config applies at the next turn with no restart. model, a
  run's own model, names an :openai-compat provider's model."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db [:maybe :seon.task/id]] :seon.model/provider]
                  [:=> [:cat :seon.host/db :seon.host/db [:maybe :seon.task/id] [:maybe :string]] :seon.model/provider]]}
  ([main task-db id] (provider-at main task-db id nil))
  ([main task-db id model]
   (let [p (provider (merge (:config @held) (from-db main)
                            (when id (d/pull task-db (keys-declared) [:seon.task/id id]))))]
     (cond-> p (and model (= :openai-compat (:kind p))) (assoc :model model)))))
