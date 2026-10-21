(ns seon.config
  "Every dial of the platform, read once from the environment when this namespace loads
  (SEON_<NAME>; proto/flow f2-final's PROTO_<NAME> dials, collected in one place). A
  shipped default fits a 16 GB machine running many agents beside the user's work."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.contract]))

(defn- dial {:malli/schema [:=> [:cat :string :any] :int]}
  [k default] (Long/parseLong (or (System/getenv k) (str default))))

(def MAX-AGENTS
  "Live agents at most (root holds a socket per agent)."
  (dial "SEON_MAX_AGENTS" 1024))
(def WRITER-BATCH
  "Most requests one writer step drains and commits together (group commit)."
  (dial "SEON_WRITER_BATCH" 256))
(def WRITER-BUF
  "The writer's in-port. Each agent has at most one write in flight, so a buffer above the
  live agents never fills from agents; beyond it senders park (back-pressure)."
  (dial "SEON_WRITER_BUF" MAX-AGENTS))
(def RECONCILER-BUF
  "Main's commits queued for the reconciler. It reads every commit's datoms (an index kept by
  observing them), so the buffer blocks rather than drops: a full buffer parks the writer's
  send until the reconciler catches up (a pass costs what the commit changed)."
  (dial "SEON_RECONCILER_BUF" 1024))
(def AGENT-BUF
  "Each agent channel's buffer. Small on purpose: a full channel pushes back on its sender
  (the reader leaves frames in the socket)."
  (dial "SEON_AGENT_BUF" 64))
(def STOP-BOUND-MS
  "How long an agent may take to exit after :stop before it is SIGKILLed."
  (dial "SEON_STOP_BOUND_MS" 2000))
(def STANDBY-EVERY
  "Main commits after which the fork server forks a fresh standby (bounds the log of
  commits a promoted standby is sent)."
  (dial "SEON_STANDBY_EVERY" 256))
(def API-WRITES
  "Writer requests from the api in flight at most. At the bound the api stops reading its
  entrance (Flow's input filter) and reads only answers, so callers are pushed back and
  the writer's answers always have room: the api <-> writer cycle cannot wedge."
  (dial "SEON_API_WRITES" 512))
(def API-BATCH
  "Most messages one api step takes before it applies its graph change (spawns and ends
  become one update!)."
  (dial "SEON_API_BATCH" 256))
(def FLUSH-MS
  "How long a committed write may stay unflushed (:seon.config.store/flush-ms, owner
  2026-10-20): every write is answered when it is committed in memory, and the next group
  flush within this window makes it durable. Recovery restores to the last flush. Up to
  1,000 is acceptable. A write followed by an effect outside the database asks for
  :flush? and is answered after its flush."
  (dial "SEON_FLUSH_MS" 10))

(def STALL-MS
  "An agent with a request in flight that sends root nothing (no reply, commit or effect)
  for this long, and has no effect outstanding, is observed stalled once (lane D2: a
  recorded :seon.agent/stalled; root checks every half of it while an agent is busy). 0:
  no check."
  (dial "SEON_STALL_MS" 30000))

(def EID-LEASE
  "Entity ids each agent process may mint on its own branch (a block per agent, from the
  store's highest id at boot): ids stay unique store-wide with no shared counter (L1's lease)."
  (dial "SEON_EID_LEASE" 100000))

(def FX-BUF
  "Requests one effect owner holds queued (its in-port and its priority queue). Each agent
  has at most one synchronous effect in flight, so beyond the live agents only async
  sends queue; at the bound stand-ins park (back-pressure). Lane FX1."
  (dial "SEON_FX_BUF" 1024))
(def FX-DIRECT
  "Experiment (lane FX1): an effect owner answers straight to the asker's stand-in by id
  ([agent :in]) instead of through the api's :back. Off by default."
  (= "1" (System/getenv "SEON_FX_DIRECT")))
(def MODEL-RATE
  "Model tokens per second the provider admits, cluster-wide: the :model owner's token bucket
  (lane R3; AIMD on a 429). 0: no bucket."
  (dial "SEON_MODEL_RATE" 0))

(def MODEL-LANES
  "Model calls in flight at once, cluster-wide (the :model owner's lanes). Lane FX1."
  (dial "SEON_MODEL_LANES" 8))


(def GC-SWEEPERS
  "Unit: threads, Chez's parallel-collector sweepers in root, fixed at boot right after the
  fork server is forked (seon.lib/gc-sweepers!; our Chez's (cs)collect_sweepers). 0: root
  collects on the thread that triggers it. Provenance: lane THREADS, 2026-10-21. Unfixed,
  Chez kept one per thread a collection found waiting, so root's count drifted with load (0,
  5, 15 across identical runs). A/B at 0/2/4/8 (load 7-8, 3 runs of scale 100, 1 lifecycle
  10 each): GC real time the same at every n (scale 204-241 / 211-229 / 208-219 / 215-221 ms
  over ~140 collections of ~1.5 ms; lifecycle 360 / 369 / 376 / 366 ms over ~510), GC CPU
  rising with n (205-227 at 0, 238-248 at 8), wall time within noise, threads 37 / 39 / 41 /
  45. Root's collections are small young ones; sweepers only pay for heaps far bigger. At
  the bound: none. For lane CONFIG: key :seon.config.root/gc-sweepers."
  0)
