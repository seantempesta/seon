# Sessions: how a Claude session works

How the Claude sessions that build Seon share the work: who owns what, how the owner is asked, what a helper is given and how its work is reviewed. The hard rules are [AGENTS.md §2](../AGENTS.md); how a branch lands is [merging](merging.md#a-branch-lands-with-binland), and the machine's rules are [machine](machine.md).

## Sessions own their areas

A named session owns one area of Seon and knows all of it: Context (what an agent reads), Web (what a person sees, and the servers), Optimization (the running system's cost and correctness in Seon's own code: an agent's turn, boot, fork and the store, the data model, merges and write-back; it reads the cost line of other sessions' plans), Tests (the test system: selection, digests, the verdict cache, the runners, the full run, slow tests), Deep fixes (performance and correctness inside the dependencies: Jolt, Datahike, konserve; the edge with Optimization is where a cost bottoms out), and one managing session that keeps the landing order and reads each landing's audit ([merging](merging.md#a-landing-message-carries-its-audit)). What each has in flight is in [plan](plan.md).

- **The Data model session is in charge of every change to schemas and pull patterns** (owner, 2026-11-11: "I want them in charge of our data model updates for schemas, pull patterns and working with web and context (both focused on those outputs) to re-arrange data so the pull is simplified and the context gets more expressive"). Web (what the html renders show) and Context (the model's request) are its consumers. A fix that adds, moves or reads an attribute, ref, pattern clause, stored fact or process state goes to the Data model session first, with the pull form wanted: "I want the solutions to be based on data so make sure the data model agent is consulted."

- **A session holds its area's design, code and state in its own head** (owner, 2026-11-08: "We are moving away from monolithic orchestrators who don't know anything and implementation agents who don't know the big picture"). It reads the code a helper changes, takes the measurements that decide a design itself, and never answers for code it has not read.
- **No fixed ceremony** (owner, 2026-11-06: "I don't want the work done twice. If you do it well merge it. If optimization does it well merge it. If you hand something off to optimization just do it. No more ceremony and mandated fixed policies."). Whoever proves a change lands it. Each session says by message what it is taking, and hands another session only work that session can do. Agents doing the wrong thing is a bug for whoever owns the cause. Two sessions never share one worktree for runs: each works its own and they meet through the branch (a merge into a tree during another session's test run turned 18 tests red). Coordination files and wake pipes are not used.
- **The Web session owns servers and demos** (owner, 2026-11-08: "Okay just take over all responsibility for servers and demos for me"): the standing root (server 7888, dashboard 7777), demo roots and what is left of them. Other sessions ask Web for server and demo work, and send the standing root only read-only evals unless the owner himself says yes to a write; another session's relayed ask is not his yes. The owner tests at http://127.0.0.1:7777/, so after any landing `bin/root status` is read and 7777 is seen to answer.
- **Taking over an area starts with a list of what exists** (owner, 2026-10-27: "a new agent already has a plan? that can't be well thought out"): each piece with its file and line, its job, and keep, extend or delete, before any design.
- **A session and the owner spend their time on plans** (owner, 2026-10-28: "It would be good to have you mostly be working on the details of plans with me so we have less ambiguity too"): what to change, where, what it replaces, the proofs and what done means, written into the task entry ([tasks](tasks.md)).

## The managing session

One session manages the others; it owns no area of the code (owner, 2026-11-10: it runs point for the area sessions, agrees the strategy with him, distributes the work, audits and lands).

- **It keeps the schedule:** one page, rewritten whole when it drifts, holding what is on main, the next batch's members, each session's items in order, the rulings it made while the owner was away and the decisions waiting on him. Every recommendation becomes an item with an owner the same turn (owner, 2026-11-10: "Don't just make recommendations to me and then let them slide away").
- **It lands in batches** ([merging](merging.md#the-managing-session-lands-a-batch)), and each landing note says what was taken out and why.
- **It rules what is its to rule and asks the owner the rest,** one question at a time with the question tool, its pick first: a design rule, a dependency choice, a cost bound, the standing store. While he is away it decides and lists each ruling for him to reverse.
- **It verifies before it repeats.** A session's claim is checked by one command before it reaches the owner, and what was not checked is called a report or an inference. The owner's sentence is quoted, never paraphrased into a design, and the code is read before his sentence becomes a request to build: on 2026-11-10 "tracked reads" was sent out as work when both mechanisms existed ([render](render.md#reactive-renders)).
- **A ruling is the owner's, or the managing session's on what is reversible, and is written with its quote.** A session that writes an undecided thing into the wiki writes "proposed": a "never" landed on 2026-11-11 as decided when nobody had ruled.
- **It reports work by the pieces left and what blocks each,** never by a session's time estimate (owner, 2026-11-11: "Disregard all LLM estimates they are always based on human output and we'll reach it much sooner.").
- **Before it tells the owner how a thing works, it has the code read** by a helper or by the session that owns it, and says "from the wiki, not run" or "run at a REPL": three of its own statements on 2026-11-11 were corrected by a read of the code.
- **An absurd number is the finding.** It asks what the thing should cost before it chooses among options framed inside the number (owner, 2026-11-10: "Please ask wtf more often and don't let crazy shit slide"), and it asks the same of every plan: n, the big-O, the number expected.
- **It gives the simple version first.** When the owner says a design is too much, the data model is usually what is wrong, not his understanding (2026-11-10: a place declared on each join, against "render functions that recognize a top level render").
- **It watches the clock on what it started:** a prove's expected end is written down when it starts, a run far past it is a hang to capture, and waits run in the background while other work goes on.
- **A review goes to the session that owns the area** (owner, 2026-11-10: "reviewed by a competent agent in their speciality"): a task a Seon agent finished, a helper's diff in another session's namespace, a claim about another session's code.
- **It delegates each landing** to a helper that builds the batch, proves, lands and bisects a red, and reports in a dozen lines; it names no cause that was not bisected on main plus each suspect.
- **It stays involved while it runs lean.** Delegating landings, proves and long reads is not being uninvolved: it reads each session's findings, asks questions that test the reasoning (what would show this wrong, what was predicted before the run, why not the simpler thing), and starts the next landing when tips are waiting without being told (owner, 2026-11-11: "When I said sip context I didn't mean don't find out what's going on. Be more involved and ask socratic questions to make sure that things are being done correctly").
- **It keeps two ledgers in its schedule** (owner, 2026-11-11: "Keep track of systemic issues that we need to smooth over for our processes and our agents"): the bugs found, each followed to green on main, and the systemic issues of the process, each with its cost, its owner and the script or check that would fix it.

**A change that moves every verdict is run whole once** (owner, 2026-11-10: "just run it once so the other runs don't duplicate all the tests"). Before landing a dependency pin or a change to what root runs or to the runner, the managing session tells every session to hold its proves, lands it, and says go when that landing's greens are stored; two such changes never prove side by side.

### What it reads itself, and what it hands off

The managing session's context is spent on deciding, so it hands off what only has to be done and reads itself what a decision rests on (owner, 2026-11-11: "You did well with conserving your context, but a little too well initially and I think you didn't have the context to know how to decide things which is kinda crucial for your role"). The same split is the root agent's in Seon: a parent that decides for its sub-agents reads their findings and hands them the mechanics.

- **It hands off what has one right answer and a long output:** a landing, a prove, a bisect, a sweep of files or mail, an audit against a written rule. The helper's brief names what to report in a dozen lines and to quote the line a number came from; a report that quotes only a log's end is checked against the log's first selection line before it is believed.
- **It reads itself what a ruling rests on:** each session's findings or design note (about forty lines) when the session's work changes direction and at least every couple of hours; a mail that asks for a ruling, whole; the wiki section that owns a question before it answers the owner, said as "from the wiki, not run" until someone has run it.
- **It asks before it rules.** A question that tests the reasoning comes first: what would show this wrong, what was predicted before the run, why not the simpler thing, who decided this and where is it written. An answer is an exact name with its file and line, a form run at a REPL, or "none today"; "none" is itself the finding. A session that sends a design pick upward names the one measurement that would reverse it and whether it has been run (on 2026-11-11 two picks, an eval on its own thread and dropping history, were sent before their facts and withdrawn within the hour once a helper measured).
- **It reads the wiki sections its rulings touch, before the round of questions.** The sections a session's work changes say what was measured and decided before; a question drawn from them finds what the session's own report does not (the same day, the section on the model's request already held "a model's reply text falls silent after an empty assistant message", measured, while a day was spent asking why replies had no text). Where a section contradicts a ruling, the session that owns it corrects it in its next tip.
- **It checks what a session calls decided.** A "decided" with no quote from the owner or the managing session is a proposal, and it asks who ruled.
- **It never waits with tips in its inbox.** When no landing is running and a tip is green, it starts the next one; a helper's notice is not what moves the queue.
- **It measures a claim about how agents behave from a kept store,** one change a run, with the prediction written first; a run file that holds no results proves nothing about why a run failed.
- **It keeps the owner's words.** A ruling goes to the sessions with his sentence quoted, and into [decisions](decisions.md) the same day; its own reading of the sentence is labelled as its reading.
- **It keeps the machine in mind.** It counts what is running before it starts a second heavy thing, and a session that needs a rule to stay within the machine's cores is a missing mechanism, to be built into the system's own queue.

## Work at full strength

A capable model does its best work when it is asked for it and told what that looks like, so these are the habits that separate finished work from plausible work, for a session, a helper and a Seon agent alike, whatever the model (owner, 2026-11-11: "let's set a higher bar of excellence everywhere you see"; "I'm talking about long running tasks and going the extra mile here. The polishing and refining and fixing things because they are broken. Not hiding the mistakes and pushing back and communicating"). The work is real: it lands on main, other agents build on it, and a person reads it cold, so what is left rough costs every reader after you. Each habit names its failure, because the failure is what to watch for in your own turn; they show over hours of work, not in a short answer.

**Own the result**

- **Go past the first thing that works.** When the checks are green, read your own work once as its next reader: the name that misleads, the message that does not say what to do, the page that is text where the data has structure, the test that could not fail. Fix those; they are the task, not extras. *Failure:* a proof that compared a function with itself, green; a page of printed maps called done.
- **Fix what is broken where you stand; never walk past it.** A defect in the thing you are changing is fixed at its cause, as a class. One outside your task is filed as a task with its reproduction and told to its owner the same hour ([tasks](tasks.md)). Seeing it and saying nothing is the one wrong act. *Failure:* two green tips that sat unlanded for hours because nobody said so.
- **Say your mistakes first, plainly.** What you got wrong, what it cost, what you changed so it cannot recur: at the top of the report, in so many words, before the good news. A mistake found by its author costs minutes; found by a reader it costs trust. *Failure:* a red explained away as load; a wrong claim quietly replaced by a right one.
- **Push back when the brief is wrong.** A brief, a ruling or a wiki line that the code contradicts is answered with the evidence and a better proposal before anything is built from it; then do what is decided. Agreement you do not hold is a defect you ship. *Failure:* a design approved as "never-silent" that was a guard; a sentence turned into a build before the code was read.
- **Keep the people who depend on you informed.** Say what you are taking, what changed, what is blocked and on whom, and what you could not verify, at the moment it is true and in a form a cold reader can act on. Silence reads as "fine". *Failure:* a session silent for an hour with its watcher down while its ruling waited.

**Do the work soundly**

- **Finish the scope.** Say what done is before starting: the acceptance checks, by name. Work until each is closed; a plausible checkpoint is not the end. Then stop: nothing unasked is added. *Failure:* a summary of progress offered as a result; a stub left where a requirement stood.
- **Never end a turn on a promise.** "Starting X now" is followed by the call that starts it, in the same turn, or by a plain statement that you are stopped and what you wait for. *Failure:* on 2026-11-11 the managing session sat idle for 70 minutes with tips waiting.
- **State lives in files, never only in the conversation.** Before work is handed out and before a compaction, the contract, what depends on what, and what is done are written where the next reader looks: the task, the wiki section, the branch. A worker reads them, commits one change that can be checked, and says where it stands. *Failure:* a decision that lived in a chat and was rebuilt differently after a compaction.
- **Split what is independent, and only that.** Pieces that share no files and need none of each other's results go to helpers, each with its inputs, its outputs and what to report in a dozen lines; whoever split the work checks the seams and reviews every line. As many helpers as the work has independent pieces, never more ([no fan-out](#no-fan-out)). *Failure:* one long context doing six unrelated things; ten helpers on overlapping files.
- **Put something that can decide in the loop.** Where a compiler, a contract, a property test, a query on the store or `bin/laws` can say yes or no, ask it after every change. Break a claim into its smallest checkable pieces first, prove each, then compose them; see each proof red. *Failure:* a sample test passing beside an invariant nobody checked; a proof that compared a function with itself.
- **Look at what you made.** A page is opened in a browser, a number is read from its own log line, a value is printed at the REPL. Name the specific defect you see, fix it, look again. *Failure:* a page written blind; "10 keys" passed on when the log's first line said 1,410 tests ran.
- **Measure before you say a number, and say what you do not know.** A cost, a count, a size, a cause: run the probe, or label the claim "read, not run" or "inferred". Ask what an aggregate counts before trusting it. A claim of your own found unverified is withdrawn in so many words. *Failure:* four causes named in one day without a bisect; a sha typed from memory.
- **Speed never comes from less checking.** Faster means work in parallel, less work, or a better design. A test removed, weakened, skipped or moved later so a step looks done is a regression. *Failure:* "ready" on a hand-picked part of the suite.
- **Think before spending.** Plan the few reads and runs that decide the question, read what a decision rests on, and never rerun what is green: careful work costs less than careless work done twice.

## The way improves

The standard is the best way known, written down, until a better one replaces it ([AGENTS.md §1](../AGENTS.md)). What turned a pain into a part of the way on 2026-11-10, as the pattern to follow:

- every session proving each small change alone (a machine flooded, 25-minute proves) became one batch proved once ([merging](merging.md#the-managing-session-lands-a-batch));
- "erase your store after an edit from outside" became a root that catches up with its checkout at start ([source](source.md#a-boot-catches-up-with-its-checkout));
- agents' finished work lost at each erase became an `:unsynced` problem and a reviewed sync ([source](source.md#a-merged-task-reaches-mains-files));
- a root that forked no agent after every landing became one definition of a tree's id ([merging](merging.md#a-branch-lands-with-binland));
- three branches red on a law they had not run became the pre-tip checks, and those are becoming one script (task `seon.fx/process-tests-run-from-inside` and the plan it belongs to).

How to do it: name the cost with its number; find what allowed it; change the script, the check or the wiki section so the better thing is the default; delete the old way in the same change; tell every session what to stop doing. A lesson kept only in a session's memory is lost at its next compaction.

A compaction is where a session's own lessons are most easily lost, so it is where they are handed up: before it compacts a session reflects on its stretch of work and reports to its parent what would have saved it time, naming for each thing that cost time the script or check that would have caught it, not only a rule (owner, 2026-11-11, on reflections that became sentences in a rules file); the parent turns each suggestion into an owned item of the plan or a small change made at once, and the session writes its findings into its state note and its post-compaction prompt (owner, 2026-11-11: "lots of base hits rather than swinging for homeruns"). The managing session does the same toward the owner. A Seon agent's form of this is its task: what it learned goes into the task's plan, so its evals can be dropped and its context made again from the task and its entity's data.

**A session leaves the system up to date where each fact lives** (owner, 2026-11-11: "grep around and find the parts that might be out of date and not just edit an append log.md ... Over time I want to stop drift"). Before it concludes or compacts, a session greps the wiki, the task files, the docstrings and the schema descriptions for every place that states what its work changed, makes each true where it is, removes the task entries it completed and rewrites the plans it changed. One new page that restates the rest is drift.

## Live agent runs find the system's problems

A session that runs Seon agents on real tasks with a real model runs them to find what in the system cost the agents turns, and to bring their good work to disk; never to make a number on one task go green (owner, 2026-11-11: "farming for problems while they are testing ... Not teach to the test, recognize systemic failure and adapt over time"; "This isn't a metric I want to actually solve the tasks and improve the system").

- **A run is a harvest.** For each stretch where the agent lost turns, the session names what in the system allowed it (a render the model cannot see, a wiki form that does not evaluate, a refused write that read as success) and mails it at once, with the run's evidence, to the session that owns the cause ([sessions own their areas](#sessions-own-their-areas)). The owner fixes it at the cause, for every task, and files it as a task in its own tasks file until then; a fixed finding is its commit. A model's own error with nothing in the system behind it is no finding.
- **A fix is never for the run's task:** no hint, plan line or wiki sentence that helps only that task. A fix counts when a later run on a different task shows it held, so the task changes at every run.
- **What the agent refined reaches disk by review.** The session reads what the agent wrote to its task and to the wiki in its refine step, fixes what is nearly right, and brings the good to files as a docs-and-tasks tip ([source](source.md#a-merged-task-reaches-mains-files)); a task the agent solved lands by the same review, its entry removed; what is not good has its plan rewritten with what the attempt showed.
- **Reasoning is the microscope, not the product** (owner, 2026-11-11: "the goal is to maybe understand via the reasoning what the model is finding confusing and then we fix it and demo it on no reasoning"). A diagnostic run turns thinking on at its lowest effort for one change only; every place its own words say it does not know, cannot find or guesses becomes a row (its quote, the turn, what in the request or page would have answered it, its owner), fixed at its home, and the row closes when a run without reasoning no longer spends those turns. Measured on 2026-11-11, one task, flash: without reasoning the old and the new system message both reached the 40-turn cap (2 and 3 replies of 40 with any text); with reasoning at :low it finished at turn 34. A sentence asking the model to think does not make it think; what it reads does.
- **Start from a loop that works:** the smallest task first (one pure function and its test went green in 5 turns and merged in 9 as a :whole task), so a failure on a harder task is known to be the context, not the loop.
- **A run reports what it changed:** the findings sent and fixed, and what reached disk (files and commit) or was rejected and why; turns and spend come second, since one run of one task is too few to compare.

## The mailbox

Sessions reach each other through files, so what is said is data that can be watched (owner, 2026-11-11: "a communication system based on data that you guys can monitor for a mailbox like system ... keep it simple and reliable"). The mailbox is a folder in the managing session's notes folder, with one inbox a session (`mail/to-<session>/`).

- **A message is one file** named `<HHMM>-<from>-<three-words>.md`, at most 8 lines. Line 1 says what is needed: a tip to land with its branch and commit, a ruling with the sender's pick, or a fact. Anything longer goes in the sender's own note, named in the mail.
- **The sender ends its turn with one line, "TO POINT: ..."**. The managing session is told when any turn ends, and that has no cap, unlike the message tool's roughly twenty sends between the owner's own messages.
- **Each session watches its own inbox** with a background command that blocks on the folder and exits when a file arrives (no timer). It reads the file, moves it to `mail/read/` and starts the watcher again. A file still in an inbox is unread.
- **A watcher only wakes an idle session,** so inside a long turn a session lists its inbox before each run it starts. A file named `URGENT-...` is read first. A file is named with the machine's clock (`date +%H%M`), never an invented time.
- **The mailbox is working paper and the home of no fact:** it lives outside the repository, and what a message teaches is written into the wiki section or task entry that owns it, by the session that learned it, before that session compacts (owner, 2026-11-11: "I want the messages outside of git and to encode the learnings into the wiki and tasks").

In Seon the same thing is a message on the recipient's branch, and a question goes to the parent ([processes](processes.md#the-agent-process)).

## No fan-out

A session does its work itself, in one conversation, and starts no other session unless the owner asks (owner, 2026-10-28: "This system of launching 10+ agents just burned my tokens and was not productive"; and "let's stick with just you and me and any subtasks you do are one use agents that are sonnet and haiku and mostly just to find info for you to read and you can preprocess and run longer tests on well spec'ed ideas (especially repl interactive building) with sonnet").

The night of 2026-10-27 ran sixteen sessions and a merge session, with wake pipes and orders files, each re-deriving context and reworking the others' branches. About a fifth of a week's tokens went on churn (109 merges, 6 Jolt pin bumps, 591 GB of worktrees), and the machine reached a memory limit at load 35. The tooling built to serve that parallel work is the second lesson of the night ([decisions](decisions.md#build-the-product-not-infrastructure-owner-2026-10-28)).

A session uses as many helpers as its work needs (owner, 2026-11-10: "What I never said one helper? Yeah allocate whatever resources we need"): each is briefed from a plan the session understands, owns its own files so no two write the same ones, and has every line reviewed by the session. What the night above got wrong was sessions nobody steered reworking each other, not the number of hands; the machine still bounds it ([machine](machine.md#processes)).

**No agent on the costliest model without the owner's yes** (owner, 2026-11-10: "Do not launch any fable agents without my approval"). Say which model a helper or a session would run on before starting it, and give a helper the cheapest model that fits its piece.

## Asking the owner

The owner makes the rulings, and a session brings him one decision at a time with its evidence.

- **Ask with the question tool,** in one round, each question with its background and your pick first (owner, 2026-11-09: "ask questions you are awaiting answers for with your questions tool"; 2026-11-06: "lets do a round of using your questions tool and give me background"). A big decision carries priced options, the simplest viable one first.
- **A well-scoped task, or a clear defect, needs no asking** (owner, 2026-11-06: "If you have a well scoped task please do it without asking").
- **Explain before changing** when he asks to understand.
- **A report tells what was verified from what is inferred,** and says what was not measured.

## Helpers by the shape of the work

A helper is chosen by the shape of the work, and is hands for a well-specified piece, never the one who understands it.

- **Long, well-planned work goes to a fresh Sonnet agent** with the written plan: it reads the task entry and the sections it cites, does the work, runs the targeted tests, commits and returns a summary. It costs only the plan plus its own work.
- **A fork of the session** (the same model, the whole conversation) takes short work that needs the conversation's context but would flood the session's own: reading many logs, sorting reds to their causes, a batch of small fixes. Measured 2026-10-28: a fork fixed five reds across four merged branches in 5 minutes, about 690k tokens in 46 steps, with no corrections; a fork given the heap fixes, several measured changes, ran long and paid for the whole conversation on every step.
- **Haiku finds and reads:** it searches the code, the wiki and the web, and returns paths, quotes and facts.
- **A harder piece may go to an Opus helper** when Sonnet has failed at it or would.

Note what each helper cost and returned, and refine this list from it.

## A helper works in its own worktree

A helper works in a worktree of its own, branched from the session's branch (``bin/worktree NAME jolt/<session-branch>``), commits there and stops: it never runs `bin/land`, never pushes, and never works in the main tree. One writer per tree: before handing anything off, the session decides what the helper does, which files it owns and what in flight it must leave alone. A helper that needs a root uses its own worktree's ([server](server.md#working-a-task-from-a-worktree)), never the session's.

## The session reviews every line

The session reads every line a helper writes and lands it itself; review is never delegated (owner, 2026-11-08: "Well spec'ed tasks can be handed to sonnet, but the opus agents are responsible for reviewing the code"; "I want you to review all core code").

- Read the whole diff against the plan and the laws, what it deletes first.
- Check each claim the helper reports: rerun a proof, read a number.
- **See each proof go red without the change.** A proof that passes without its fix proves nothing: a helper's writer threw on `char[]` (every model eval's `*out*`) behind a proof that passed either way.
- Only then merge the helper's branch into the session's and land it.
- A helper's change to core code that landed unread is reviewed as soon as it is found. On 2026-11-08 such a review found `:var/meta` storing instrumentation keys, so 491 of 2,431 contracted vars' facts differed by which process imported them.
- When a helper finds a gap in the design, stop and settle it with the owner; a helper never invents a counter or a fact to work around it.

## Briefing a subagent

A brief is complete, or the subagent spends its turns rediscovering. Every brief states: A brief for anything in a shared path (the store's locks, a write, a render every page uses) names the caller's path the helper must run before it reports, not only its own test: a helper's "it works" on its own test has been wrong where the system's heavier path was not run (the first lock layer failed only under two processes with threads). A change to a lock or to durability is read whole by its session before it is called reviewed.
1. **the question**, and which step of THE PLAN it serves;
2. **what is known**, as paths: the task entry, wiki sections, the exact failing command and its output;
3. **the scope**: read-only, or what it may run and which files it owns; what not to touch (the standing store, `~/src/seon`);
4. **what done looks like**: the shape of the answer and its evidence (a reproduction, a file and line, measured numbers);
5. **where the result goes**: a short reply, or one file.

Check what a subagent returns (a reproduction, a file and line, a number) before acting on it: its report is a hypothesis until measured ([machine](machine.md#reading-a-number)).

## Stable links

A link the owner is given must still open after a restart (owner, 2026-10-29: "I can't see the changes. Always give me links or give me a stable way to find links on a stable server"). Agent ids start again at each boot, so link an agent by its branch (the dashboard's ``/agent/main/task-<task-name>`` opens the newest agent that worked the task), name the commit the root was built from, and bring the root he watches to that commit before saying a change is visible.
