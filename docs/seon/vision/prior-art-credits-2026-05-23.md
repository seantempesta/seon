---
type: research
status: draft
tags: [research, vision, prior-art]
---

# Prior Art Credits for Seon

**STATUS:** Draft complete (2026-05-23). All 22 sections filled. Some lineages have unresolved gaps — see final section.

## Statement of purpose

None of the foundational ideas in Seon are original to Sean Tempesta. What is his own is the **specific synthesis**: a personal substrate where AI agents own and evolve code, built on immutable Datalog over LMDB, schema-as-contract, REPL-driven Clojure, capability-typed WASM containment, and a narrative inspired by bonded luminous helpers from speculative fiction. This document credits the earliest credible sources for each load-bearing idea so that the public README and vision documents can give honest attribution. The goal is intellectual honesty, not exhaustive citation. Where lineage is murky, that is noted.

---

## 1. Bonded personal AI assistant (narrative inspiration)

**Earliest source**
- Brandon Sanderson — *Elantris* (Tor Books, 2005). Seons are autonomous, sentient, luminous beings that have given themselves to the service of mankind, each bearing an Aon (the magic glyph) at its center. The name "Seon" is from an archaic verb "to see"; Sanderson notes the visual inspiration was Michael Whelan's *Passage* paintings (floating candle-bubbles).

**Lineage**
- Neal Stephenson — *The Diamond Age: Or, a Young Lady's Illustrated Primer* (Bantam Spectra, 1995). The Primer is an AI book that acts as a teacher and mentor, adapting to the child it is bonded with — perhaps the most direct fictional precedent for a personal AI substrate.
- HAL 9000 (Clarke / Kubrick, *2001*, 1968), KITT (*Knight Rider*, 1982), Bicentennial Man (Asimov, 1976), Cortana, JARVIS — predecessors in the "bonded helpful machine intelligence" archetype.

**How Seon uses it**
- The project name and visual/affective register come directly from Sanderson's Seons (luminous, bonded, voluntarily in service). The Primer-as-substrate gives the deeper architectural intuition: a personal device that grows with its user.

---

## 2. Augmenting human intellect

**Earliest source**
- Vannevar Bush — "As We May Think," *The Atlantic Monthly*, July 1945. Introduces the Memex: "A memex is a device in which an individual stores all his books, records, and communications, and which is mechanized so that it may be consulted with exceeding speed and flexibility. It is an enlarged intimate supplement to his memory."

**Lineage**
- J.C.R. Licklider — "Man-Computer Symbiosis," *IRE Transactions on Human Factors in Electronics* HFE-1, March 1960. "The hope is that, in not too many years, human brains and computing machines will be coupled together very tightly, and that the resulting partnership will think as no human brain has ever thought."
- Douglas Engelbart — "Augmenting Human Intellect: A Conceptual Framework," SRI report AFOSR-3223, October 1962. Followed by the 1968 "Mother of All Demos."
- Alan Kay — "A Personal Computer for Children of All Ages," 1972 ACM National Conference (the Dynabook proposal).

**How Seon uses it**
- Seon is explicitly framed as cognitive infrastructure, not an automation tool. The vision document echoes Engelbart's "trained human together with his artifacts, language, and methodology."

---

## 3. Knowledge Navigator — AI assistant building tools on demand

**Earliest source**
- Apple — "Knowledge Navigator" concept video, premiered by John Sculley at Educom, 1987 (production directed by Bud Colligan; concept incorporated input from Alan Kay). Depicted a tablet computer with a bow-tied software agent that could schedule, research, and synthesize on natural-language request.

**Lineage**
- Pattie Maes — "Agents that reduce work and information overload," *Communications of the ACM* 37(7), July 1994; founded the MIT Media Lab Software Agents Group in 1991.
- Modern realizations: Siri (2011), Google Now, Alexa, and most recently the LLM-agent generation (Claude Code, Cursor Composer, Devin).

**How Seon uses it**
- The "agents build their own tools" ethos is Knowledge Navigator made literal: the agent doesn't choose from a fixed menu — it writes the function it needs.

---

## 4. Code as data / homoiconic agent surface

**Earliest source**
- John McCarthy — "Recursive Functions of Symbolic Expressions and Their Computation by Machine, Part I," *Communications of the ACM*, April 1960 (Lisp implemented 1958 at MIT). S-expressions mirror the internal representation of code and data; almost all Lisps today use S-expressions to manipulate both.

**Lineage**
- Gerald Jay Sussman & Guy L. Steele Jr. — "Scheme: An Interpreter for Extended Lambda Calculus," MIT AI Memo 349, 1975 (first of the Lambda Papers).
- Clojure — Rich Hickey, 2007 — Lisp-on-the-JVM with persistent immutable data structures.

**How Seon uses it**
- Seon is written in Clojure, and the entire agent-as-editor pattern rests on code being manipulable as data (`clojure_replace` operates structurally, schemas are EDN, etc.).

---

## 5. REPL as the primary interface

**Earliest source**
- Lisp itself (McCarthy, 1958–60) — the read-eval-print loop is intrinsic to Lisp from its earliest implementation on the IBM 704.
- Lisp Machines (MIT AI Lab, 1970s; Symbolics, LMI, Texas Instruments Explorer, 1980s) — single-user workstations where the running image *is* the development environment.

**Lineage**
- Smalltalk-72 (Alan Kay, Dan Ingalls, Adele Goldberg et al., Xerox PARC, October 1972 — implemented in ~700 lines of BASIC by Ingalls); Smalltalk-80 — first publicly available version, established "image-based development" as a discipline.
- Self (David Ungar & Randall B. Smith, "SELF: The Power of Simplicity," OOPSLA '87) — prototype-based, deeply live.

**How Seon uses it**
- The REPL is treated as the oracle: "Tests passing is necessary but not sufficient. Query the live system and confirm the actual state matches your intent." (CLAUDE.md)

---

## 6. Live programming / dynamic interfaces / state-derived UI

**Earliest source**
- Smalltalk (1972 forward) — continuous edit-while-running was the original model.

**Lineage**
- Self (Ungar & Smith, 1987) — pushed liveness further with prototype-based objects and morphic interfaces.
- Subtext — Jonathan Edwards, "Subtext: Uncovering the Simplicity of Programming," OOPSLA 2005. Example-centric, schematic-table programming.
- Bret Victor — "Inventing on Principle," CUSEC, 20 January 2012. Principle: "creators need an immediate connection to what they create." Also "Learnable Programming" (2012) and "Up and Down the Ladder of Abstraction" (2011).
- Eve — Chris Granger (Light Table → Eve, 2014–2018). General-purpose relational programming with a live database and tabular UI.
- Elm — Evan Czaplicki, designed 2011; "Asynchronous Functional Reactive Programming for GUIs," PLDI 2013. The Elm Architecture (model–update–view) directly inspired Redux.

**How Seon uses it**
- Datastar SSE + render-from-DB-state means the UI is genuinely a projection of the live system. Bret Victor's "immediate connection" is the design tax Seon pays to make the substrate trustworthy.

---

## 7. Datalog / Datalog-on-EAV / triplestore queries

**Earliest source**
- E. F. Codd — "A Relational Model of Data for Large Shared Data Banks," *CACM* 13(6), 1970. The deep ancestor.
- David Maier & David S. Warren — coined the term "Datalog" in the early 1980s (the canonical historical mention; Ceri/Gottlob/Tanca attribute the name to Maier in various survey citations).
- Stefano Ceri, Georg Gottlob & Letizia Tanca — "What You Always Wanted to Know About Datalog (And Never Dared to Ask)," *IEEE Trans. Knowl. Data Eng.* 1(1), March 1989. (Note: I initially attributed this paper to Maier/Warren — that was wrong; Maier coined the *name* "Datalog," this 1989 paper is the canonical survey.)

**Lineage**
- RDF / SPARQL (W3C, 1999/2008) — the web's triplestore language.
- Datomic (Hickey, 2012) and XTDB (formerly Crux) — Datalog-on-EAV for production use.

**How Seon uses it**
- All persistent data is EAV datoms queryable in Datalog. Datahike is the embedded implementation (post-2026-04 migration from Datalevin).

---

## 8. Bitemporal databases (record + transaction time)

**Earliest source**
- Richard T. Snodgrass — *The TSQL2 Temporal Query Language* (Kluwer, 1995). Established the bitemporal conceptual data model with explicit valid-time and transaction-time dimensions ("VALID" and "TRANSACTION" specifiers).

**Lineage**
- SQL:2011 standardized application-time and system-time period tables.
- Snodgrass's earlier "Temporal Databases" (1986 onward) and the TimeCenter consortium output.
- XTDB / Crux (JUXT, ~2018) and Datomic (2012) brought bitemporality to general programming.

**How Seon uses it**
- The Datalog substrate gives transaction-time for free (append-only datoms). Application-time is modeled per domain (trading positions, health records).

---

## 9. Datomic — immutable, time-aware, queryable graph database

**Earliest source**
- Rich Hickey — Datomic publicly announced March 29, 2012. First public release (0.8.3335) July 24, 2012. Developed at Relevance / Cognitect.

**Lineage**
- The "facts are accreted" model traces back to event sourcing and ultimately to the immutable-data philosophy of Clojure itself (Hickey, "Are We There Yet?" QCon 2009).
- Subsequent open-source Datalog-on-EAV systems: Datascript (Tonsky, 2014), Datahike (replikativ, 2018+), XTDB, DataLevin.

**How Seon uses it**
- Datomic is the conceptual ancestor. Seon uses Datahike (open-source, embedded, LMDB-backed) but the data model, pull syntax, and Datalog dialect are direct descendants of Datomic.

---

## 10. Actor model / namespace-as-process / message-passing concurrency

**Earliest source**
- Carl Hewitt, Peter Bishop, Richard Steiger — "A Universal Modular ACTOR Formalism for Artificial Intelligence," IJCAI '73 (Stanford, August 1973).

**Lineage**
- Joe Armstrong, Robert Virding, Mike Williams — Erlang at Ericsson, 1986. OTP supervisor trees (Armstrong's PhD work; "A History of Erlang," HOPL III, 2007).
- Akka (Jonas Bonér, 2009), Elixir (José Valim, 2011), Pony (Sylvan Clebsch, 2014).
- Hewitt's later restatement: "Actor Model of Computation: Scalable Robust Information Systems" (arXiv, 2010).

**How Seon uses it**
- core.async/flow is the routing backbone — `topology/request!` is essentially Hewitt's "actor sends a message and registers a continuation." Erlang's supervisor-tree pattern shapes how agents are restarted on failure.

---

## 11. Capability-based security

**Earliest source**
- Jack B. Dennis & Earl C. Van Horn — "Programming Semantics for Multiprogrammed Computations," *CACM* 9(3), March 1966. Introduced the C-list: "each capability in a C-list locates by means of a pointer some computing object, and indicates the actions that the computation may perform."

**Lineage**
- KeyKOS (Norm Hardy, Charles Landau et al., Tymshare/Key Logic, 1980s).
- EROS (Jonathan Shapiro, 1990s) → Coyotos → seL4 (verified microkernel, 2009).
- Mark S. Miller — *Robust Composition* (PhD thesis, Johns Hopkins, 2006). Founding document of the modern Object Capability Model.

**How Seon uses it**
- The WASM-Tauri containment plan (Phase 3 active 2026-05) imports capabilities through WIT interfaces — `fs`, `http`, `mcp`, `capability-prompt`, `eval`. The Rust host decides what to grant. This is Dennis & Van Horn's C-list with sixty years of refinement.

---

## 12. Object-capability + sandboxed AI execution

**Earliest source**
- Miller's *Robust Composition* (2006) is the canonical literature.
- The E language (Miller, Tribble, et al., 1997 onward) is the founding implementation.

**Lineage**
- WASI Preview 2 + WebAssembly Component Model (Bytecode Alliance, 2024) — explicit capability-passing at the WIT interface boundary.
- Commercial sandboxed-AI products: OpenAI Code Interpreter (2023), Anthropic's tool-use API and code execution (2024), E2B and Modal sandboxes.

**How Seon uses it**
- The CLJS pod is the agent's body; the WIT-typed import surface is what the host grants it. Adversarial agent code can't escape what wasmtime didn't import.

---

## 13. Schema as machine-readable contract for discovery

**Earliest source**
- J. Roger Hindley — "The Principal Type-Scheme of an Object in Combinatory Logic," *Transactions of the AMS* 146, 1969.
- Robin Milner — "A Theory of Type Polymorphism in Programming," *J. Comp. Sys. Sci.* 17(3), 1978.

**Lineage**
- Hoogle (Neil Mitchell, 2004) — search Haskell APIs by type signature. The first widely-used tool that treats types as a *discovery* surface, not just a checking surface.
- clojure.spec — Rich Hickey, introduced May 23, 2016. "Specs are expressive and precise, and including spec in Clojure creates a lingua franca with which we can state how programs work and how to use them."
- Malli — Metosin / Tommi Reiman, ~2019. Schema as plain data, used in Seon.
- TypeScript (2012), Flow (2014), ReasonML (2016) — gradual typing for industrial languages.

**How Seon uses it**
- Every public function has `:malli/schema` metadata. Schemas are queryable from the database (the shape graph indexes 138 shapes, 333 entries). An agent finds a function by what shape it produces. This is Hoogle at the substrate level.

---

## 14. Function discovery / structural search

**Earliest source**
- Hoogle — Neil Mitchell, 2004 ("© Neil Mitchell, 2004-present"). The canonical "find a function by signature" tool.

**Lineage**
- Hayoo (Frank Steinmetz / Sebastian M. Schlatt et al., FH Wedel, ~2008) — Haskell function search complementary to Hoogle.
- Datomic's "function attribute" pattern (transaction functions as first-class data).
- The "AI as Hoogle" pattern recently re-invented inside LLM tool registries.

**How Seon uses it**
- The shape graph (`route-data!` in Seon) is recursive schema indexing — call any function by the data shape you have and it's discovered, not configured.

---

## 15. Generative testing / property-based testing

**Earliest source**
- Koen Claessen & John Hughes — "QuickCheck: a lightweight tool for random testing of Haskell programs," ICFP '00 (proceedings published 2000; presented late 1999 schedule per ICFP custom — the canonical date is 2000). 2010 ICFP Most Influential Paper Award.

**Lineage**
- test.check (Reid Draper / Clojure team, 2012+) — Clojure port.
- Hypothesis (David R. MacIver, Python, 2013+).
- Malli's built-in generators — Metosin, 2019+. Generative tests at the type boundary are a first-class concern in Seon.

**How Seon uses it**
- `user/test-gen` runs Malli-generator-driven property tests. The pipeline roundtrip test is generative by construction.

---

## 16. WebAssembly as portable, language-agnostic runtime

**Earliest source**
- Andreas Haas, Andreas Rossberg, Derek Schuff, Ben Titzer, Dan Gohman, Luke Wagner, Alon Zakai, JF Bastien, Michael Holman — "Bringing the Web Up to Speed with WebAssembly," PLDI 2017 (Distinguished Paper Award). Rossberg was editor of the WebAssembly 1.0 spec (released 2017, formalized 2019-07-20).

**Lineage**
- asm.js (Alon Zakai, Mozilla, 2013) — the typed-JavaScript precursor.
- WASI Preview 1 (2019) → Preview 2 (January 25, 2024) — system interface as capability surface.
- WIT + Component Model (Bytecode Alliance, 2022–2024).
- wasmtime — the reference runtime, where Seon's pod lives.

**How Seon uses it**
- The pod runs as a `wasm32-wasip2` Component inside wasmtime, embedded in a Tauri Rust process. Capability surface is WIT-typed.

---

## 17. Polyglot / language-portable runtimes

**Earliest source**
- Sun Microsystems — Java Virtual Machine (Gosling et al., 1995). First mainstream "compile once, run anywhere" runtime.

**Lineage**
- .NET CLR (Microsoft, ECMA-335, 2002).
- GraalVM (Oracle Labs, 2018) — polyglot via Truffle.
- WASM Component Model (2022–2024) — the most language-agnostic story to date because the interface (WIT) is independent of source language.

**How Seon uses it**
- The pod is CLJS today, but WIT interfaces mean any language that targets `wasm32-wasip2` can host an agent.

---

## 18. Self-modifying / self-improving programs

**Earliest source**
- Douglas Lenat — AM (Automated Mathematician), PhD dissertation (Stanford, 1976). Followed by Eurisko (CMU, 1976–1981 — first major successes 1981, including the *Traveller TCS* national championship win).

**Lineage**
- John Koza — *Genetic Programming: On the Programming of Computers by Means of Natural Selection* (MIT Press, 11 December 1992).
- Allen Newell, John Laird, Paul Rosenbloom — SOAR (CMU, 1983); "SOAR: An Architecture for General Intelligence," *AI Journal* 33(1), 1987.
- Jürgen Schmidhuber — "Gödel Machines: Self-Referential Universal Problem Solvers Making Provably Optimal Self-Improvements," IDSIA TR-19-03 / arXiv:cs.LO/0309048, September 2003.

**How Seon uses it**
- Seon's agents don't yet rewrite their own substrate, but the design intent — code agents can read, write, and evolve responsibly — is the practical descendant of Eurisko and the Gödel Machine, with "provably useful" replaced by "tested + schema-validated."

---

## 19. Long-lived autonomous agents that "own" code

**Earliest source**
- SOAR (1983) and ACT-R (John R. Anderson, CMU, 1976 onward) — cognitive architectures designed for long-lived problem-solving agents.

**Lineage**
- Richard Sutton & Andrew Barto — *Reinforcement Learning: An Introduction* (MIT Press, 1998; 2nd ed. 2018).
- Stuart Russell & Peter Norvig — *AI: A Modern Approach* (1995+) — codified the "rational agent" framing.
- Devin (Cognition AI, 2024), Claude Code (Anthropic, 2024), Cursor Composer (Cursor, 2024), OpenAI Codex/Operator — commercial instances of agents that own code.

**How Seon uses it**
- The orchestrator + sub-agent topology, the "agent owns a namespace" pattern, and the lane discipline (`.clj` JVM seat vs `.cljs` pod seat) all assume durable, code-owning agents.

---

## 20. Multi-domain personal operating system framing

**Earliest source**
- Vannevar Bush — Memex, 1945 (see §2).
- Ted Nelson — Project Xanadu, conceived 1960; "Complex Information Processing: A File Structure for the Complex, the Changing, and the Indeterminate," ACM National Conference, 1965 (coined the term *hypertext*).

**Lineage**
- HyperCard (Bill Atkinson, Apple, 1987).
- Roam Research (Conor White-Sullivan, 2019), Notion (Ivan Zhao, 2016), Obsidian (Erica Xu & Shida Li, 2020).
- WeChat (Tencent, 2011) — the "super-app" archetype that proved one app *can* hold every domain.

**How Seon uses it**
- Trading, health, finance, ingest — these aren't separate apps. They're domains inside one personal substrate, queryable across boundaries because they share the EAV datoms.

---

## 21. Smart defaults + progressive enhancement

**Earliest source**
- Jon Postel — RFC 760 (January 1980), restated in RFC 761 ("Robustness Principle" section): "be conservative in what you do, be liberal in what you accept from others."

**Lineage**
- Håkon Wium Lie & Bert Bos — graceful degradation principle in CSS (W3C, 1996 onward).
- Steve Champeon & Nick Finck — "Inclusive Web Design for the Future," SXSW 2003 — coined "progressive enhancement" as a term of art.
- David Heinemeier Hansson — "convention over configuration" in Ruby on Rails (2004 release; Rails 1.0 December 13, 2005). "Don't write configuration for things the framework can figure out."

**How Seon uses it**
- The Malli `:default/fn` pattern (every schema can declare how to materialize a default) is Postel/DHH applied at the schema layer: caller provides what's specific, the system fills the rest.

---

## 22. Reactive / push-based UI

**Earliest source**
- VisiCalc — Dan Bricklin & Bob Frankston (Software Arts, released for Apple II 17 October 1979). The first mass-market reactive computation: "a magic sheet of paper that can perform calculations and recalculations." Change any cell, the entire sheet recalculates.

**Lineage**
- Conal Elliott & Paul Hudak — "Functional Reactive Animation" (Fran), ICFP 1997. The paper that gave birth to FRP — behaviors (continuous time-varying values) and events (discrete occurrences).
- React — Jordan Walke at Facebook (deployed internally 2011 in the news feed); open-sourced May 2013 at JSConf US.
- Elm — Evan Czaplicki, 2011/2013.
- HTMX — Carson Gross, 2020 (descended from his earlier intercooler.js, 2014).
- Datastar — successor to HTMX with SSE-native architecture, ~2024–2025.

**How Seon uses it**
- The web UI is Datastar+SSE: every UI change is the server pushing a fragment derived from current DB state. There is no client-held truth.

---

## Acknowledgments (draft for README)

> Seon stands on a long lineage of ideas. The bonded-helper image comes from Brandon Sanderson's Seons of *Elantris* (2005) and Neal Stephenson's *Diamond Age* (1995). The architectural genealogy traces through Vannevar Bush (Memex, 1945), Engelbart (Augmenting Human Intellect, 1962), Licklider (Man-Computer Symbiosis, 1960), and Kay (Dynabook, 1972). The runtime model is Lisp (McCarthy, 1958), the REPL is Smalltalk (Kay/Ingalls, 1972), the database model is Datalog (Codd 1970, Maier 1980s, Hickey's Datomic 2012). The containment story descends from Dennis & Van Horn's capabilities (1966) through Mark Miller's *Robust Composition* (2006) to the WebAssembly Component Model (2024). The schema-as-discovery surface owes to Hindley (1969), Milner (1978), Mitchell's Hoogle (2004), and Hickey's clojure.spec (2016). The live-programming aesthetic is Bret Victor's principle (2012), with respectful nods to Smalltalk, Self, Subtext, and Eve. The reactive UI lineage starts with VisiCalc (1979), runs through Fran (Elliott & Hudak, 1997), and lands in Datastar today. Property-based testing is Claessen & Hughes's QuickCheck (2000). What is Sean's own is the synthesis: a personal substrate where these pieces compose into infrastructure AI agents can use to write reliable software.

---

## Unresolved attributions

Honestly noted gaps where I couldn't reach a clean primary source within budget:

1. **"Datalog" as a coined term.** Widely attributed to David Maier (early 1980s); the canonical 1989 survey is Ceri/Gottlob/Tanca, *not* Maier/Warren. Primary text where the name first appears is plausibly Maier & Warren's *Computing with Logic* (Benjamin/Cummings, 1988) but I did not verify online. The original task brief mis-attributed the 1989 paper to Maier/Warren; the correct authorship is recorded above.
2. **Knight Rider's KITT** and other 1980s TV bonded-AI tropes — listed but not deeply researched. The Sanderson/Stephenson line is the cited inspiration; the TV examples are noise.
3. **"Image-based development"** as a coined term — used broadly in the Smalltalk and Lisp Machine communities since the early 1980s; I couldn't find a clean first-attestation paper. Treated as folkloric to the LISP/Smalltalk traditions.
4. **GraalVM polyglot story** — cited the 2018 release year, but the underlying Truffle framework (Würthinger et al., "One VM to Rule Them All," Onward! 2013) is the better academic citation. Both are real, the 2013 paper is the deeper root.
5. **"Progressive enhancement"** — Steve Champeon's 2003 SXSW talk is widely cited but the slides/transcript are hard to locate authoritatively. The principle in spirit is older than the term.
6. **WeChat as super-app** — pointed to 2011 launch; the "super-app" descriptor is journalist-coined, not a single paper. Used here for the architectural pattern only.
7. **HTMX precise birth date** — Carson Gross's intercooler.js (~2014) is the lineage; HTMX is a 2020 rename/rewrite. Repeated dates around the web vary by source.
8. **The 1965 Nelson paper title** — confirmed via multiple secondary sources but I did not pull the primary ACM proceedings text.
