(ns seon.cluster
  "The entry: one JVM process hosting cluster instances, REPL-first.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — the B0 rung). The
  schemas and function contracts are SEALED: the implementation lane
  fills the stub bodies until test/seon/cluster/boot_test.clj is green
  and may not loosen a schema or a test. Friction is reported, never
  resolved by weakening.

  The boot tower (plan README, rulings 2026-07-27): each layer reads
  only the one below it and publishes its own readiness —

  0. PROCESS. `start!` consumes one complete bootstrap configuration —
     the closed, deliberately tiny key set the process needs before any
     store exists. Everything else lives in the database (B2). The REPL
     (io-prepl) opens FIRST, before anything else, and the instance
     advertises its coordinate; the ten-second ruling is this layer's
     bound.
  1. STORE (B1, next rung — not in this namespace).
  2. FACTS and 3. FLOW (B2/N3): the flow graph definition is data
     derived from database facts at a basis; graph transforms are
     referenced as VARS so re-evaluating a defn updates a running proc
     with no restart, and topology changes rebuild the graph (measured
     ~0.3 ms) — nothing in a flow channel is durable
     (research/flow-dynamic-update-2026-07-27.md).

  Multi-instance from day 0: the process identity is
  (cluster-name, pid, start-instant); every path derives from
  (root, cluster-name) by convention; each instance advertises its own
  REPL coordinate under its cluster directory; a JVM may host several
  instances and NOTHING here is an ambient one-cluster singleton — no
  process-global connection, cache, or session keyed by \"the\"
  cluster.

  The root of the process owns exactly two shared executors — one
  bounded `:compute` (default parallelism = available processors, a
  computed hardware fact, never a literal) and one `:io` — created once
  per JVM and shared by every cluster's flow graph
  (research/flow-per-cluster-2026-07-27.md).

  Crash walk: `start!` performs no database writes and owns no durable
  state; a kill at any instant leaves at most an orphan advertisement
  file, and the advertisement carries (pid, start-instant) so a reader
  detects staleness against the live process table rather than trusting
  the file. `stop!` is idempotent; a killed process's next boot simply
  re-advertises."
  (:require [clojure.core.server]
            [seon.flow :as flow]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Bootstrap configuration — the CLOSED pre-store key set.
;;; A key that the database could own does not belong here; the closed
;;; map makes that a review-time refusal, not a convention.
;;; ---------------------------------------------------------------------------

(schema/register! :seon.boot/cluster-name [:string {:min 1}])
; the parent directory holding every cluster's directory
(schema/register! :seon.boot/root [:string {:min 1}])
(schema/register! :seon.boot/prepl-host [:string {:min 1}])
; 0 = ephemeral; the advertisement carries the real bound port
(schema/register! :seon.boot/prepl-port [:int {:min 0 :max 65535}])
(schema/register! :seon.boot/log-dir [:string {:min 1}])

(schema/register!
 :seon.boot/config
 [:map {:closed true}
  [:seon.boot/cluster-name :seon.boot/cluster-name]
  [:seon.boot/root :seon.boot/root]
  [:seon.boot/prepl-host :seon.boot/prepl-host]
  [:seon.boot/prepl-port :seon.boot/prepl-port]
  [:seon.boot/log-dir :seon.boot/log-dir]])

; overrides: any subset of the complete config's keys, still closed
(schema/register!
 :seon.boot/overrides
 [:map {:closed true}
  [:seon.boot/cluster-name {:optional true} :seon.boot/cluster-name]
  [:seon.boot/root {:optional true} :seon.boot/root]
  [:seon.boot/prepl-host {:optional true} :seon.boot/prepl-host]
  [:seon.boot/prepl-port {:optional true} :seon.boot/prepl-port]
  [:seon.boot/log-dir {:optional true} :seon.boot/log-dir]])

;;; The advertisement — one EDN file under the cluster directory that
;;; makes every instance's REPL discoverable. (pid, start-instant) is
;;; the staleness fence: a reader validates the pid is alive AND
;;; started at that instant before trusting the port.

(schema/register! :seon.boot/pid [:int {:min 1}])
(schema/register! :seon.boot/start-instant :inst)

(schema/register!
 :seon.boot/advertisement
 [:map {:closed true}
  [:seon.boot/cluster-name :seon.boot/cluster-name]
  [:seon.boot/prepl-host :seon.boot/prepl-host]
  [:seon.boot/prepl-port [:int {:min 1 :max 65535}]]
  [:seon.boot/pid :seon.boot/pid]
  [:seon.boot/start-instant :seon.boot/start-instant]])

;;; The running instance value returned by start!. Named predicates for
;;; the genuinely opaque platform objects; everything else is ordinary
;;; data.

(defn socket-server?
  "True for the java.net.ServerSocket an io-prepl listens on."
  [value]
  (instance? java.net.ServerSocket value))

(schema/register-core-predicate! 'seon.cluster/socket-server?
                                 socket-server?)

(schema/register!
 :seon.boot/instance
 [:map {:closed true}
  [:seon.boot/config :seon.boot/config]
  [:seon.boot/advertisement :seon.boot/advertisement]
  [:seon.boot/prepl-server [:fn 'seon.cluster/socket-server?]]
  [:seon.boot/executors
   [:map {:closed true}
    [:compute [:fn 'seon.flow/executor?]]
    [:io [:fn 'seon.flow/executor?]]]]])

;;; ---------------------------------------------------------------------------
;;; Pure resolution — defaults are THE defaults document for this layer
;;; ---------------------------------------------------------------------------

(defn resolve-bootstrap
  "Resolve overrides into one complete bootstrap configuration.
  Every key optional; absent = default. Defaults: cluster-name
  \"default\" (just a name, nothing special), root \"data/clusters\",
  prepl-host \"127.0.0.1\", prepl-port 0 (ephemeral — the advertisement
  carries the real port), log-dir derived as <root>/<name>/logs.
  Refuses (throws ex-info {:seon.error/kind :seon.boot/refused ...}) on
  any unknown key or invalid value — the closed schema is the gate, not
  a convention."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/config]}
  [overrides]
  (throw (ex-info "awaits implementation" {::fn `resolve-bootstrap
                                           ::overrides overrides})))

(defn cluster-paths
  "Derive every per-cluster path from (root, cluster-name).
  Convention owns the layout: the cluster directory, its store
  directory, its log directory, and its advertisement file. One
  derivation — no other code builds these paths."
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:map {:closed true}
                   [:seon.boot/cluster-dir :string]
                   [:seon.boot/store-dir :string]
                   [:seon.boot/advertisement-file :string]
                   [:seon.boot/log-dir :string]]]}
  [root cluster-name]
  (throw (ex-info "awaits implementation" {::fn `cluster-paths})))

;;; ---------------------------------------------------------------------------
;;; The shared root executors — created once per JVM, never per cluster
;;; ---------------------------------------------------------------------------

(defn root-executors
  "The process root's two shared executors.
  One bounded `:compute` platform-thread executor (parallelism =
  available processors — a computed hardware fact) and one `:io`
  executor for blocking transport. Idempotent per JVM: repeated calls return the SAME
  executor objects (the root owns them; cluster graphs share them).
  This is deliberately process-global state — the one sanctioned kind:
  a genuinely process-local artifact, like a compiler state or a
  connection."
  {:malli/schema [:=> [:cat]
                  [:map {:closed true}
                   [:compute [:fn 'seon.flow/executor?]]
                   [:io [:fn 'seon.flow/executor?]]]]}
  []
  (throw (ex-info "awaits implementation" {::fn `root-executors})))

;;; ---------------------------------------------------------------------------
;;; The instance lifecycle
;;; ---------------------------------------------------------------------------

(defn start!
  "Start one cluster instance in this JVM, REPL FIRST.
  Order: resolve paths and create directories → open the io-prepl
  socket server (clojure.core.server, `:accept
  clojure.core.server/io-prepl`) → write the advertisement (real bound
  port, this process's pid and start-instant from
  java.lang.ProcessHandle) → return the instance value. No store, no
  database, no flow graph — those are later rungs stacked ON this
  value. Two instances in one JVM are fully independent except the
  shared root executors. Refuses a second start! for a cluster this
  JVM already has running (one instance per cluster per process)."
  {:malli/schema [:=> [:cat :seon.boot/overrides] :seon.boot/instance]}
  [overrides]
  (throw (ex-info "awaits implementation" {::fn `start!})))

(defn stop!
  "Stop one instance: close the prepl server, delete the advertisement.
  Idempotent — stopping a stopped instance is a no-op returning nil.
  Never touches the shared root executors (other instances ride them)."
  {:malli/schema [:=> [:cat :seon.boot/instance] :nil]}
  [instance]
  (throw (ex-info "awaits implementation" {::fn `stop!})))

(defn read-advertisement
  "Read and validate one cluster's advertisement, or nil.
  Returns the advertisement map only when the file exists, parses,
  validates against :seon.boot/advertisement, AND its (pid,
  start-instant) matches a live process — a stale file from a killed
  instance reads as nil, never as a coordinate. (ProcessHandle/of pid →
  startInstant comparison; tolerate the platform's millisecond
  truncation.)"
  {:malli/schema [:=> [:cat :seon.boot/root :seon.boot/cluster-name]
                  [:maybe :seon.boot/advertisement]]}
  [root cluster-name]
  (throw (ex-info "awaits implementation" {::fn `read-advertisement})))
