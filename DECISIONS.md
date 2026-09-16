# Decision log

Decisions this repository's code points at. A number is handed out once. It is never reused and
never renumbered, so a citation stays resolvable. A decision which is overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it means an entry
of THIS repository. A decision the platform shares has its own entry in
`adapter-platform-integration`, written from that side, and one the Business Cockpit shares has
its entry in `business-cockpit`. A pointer into another repository is the fragile kind this log
exists to avoid.

## 1. An engine's identifiers are translated back through VanillaBP's name-clash avoidance - who does it superseded by decision 7

A workflow module deployed with `use-prefix` runs under identifiers the application never wrote.
The BPMN process id and the external form reference carry the module's prefix, because the adapter
rewrote the file before it deployed it. So a user task reaches us from the engine under the
prefixed names, while the cockpit reports the plain ones.

Getting from one to the other is not a string operation. The extension asks VanillaBP's own
name-clash avoidance, the same object the adapter builds its prefixes with. It asks for every
delivery, whichever mode the module uses, because the helper hands back what it was given where
nothing is prefixed. Cutting a known prefix off a string would work until the adapter changes how
it builds one, and it would mis-read a process id which happens to contain the separator.

The BPMN element id of a user task is the one identifier which is never rewritten. That is why the
extension prefers it wherever the engine reports it.

## 2. The entry of an observed event gets a transaction of its own

The Process-Engine-API delivers a task on a thread of the engine's own. There is no transaction of
the application to join, and nothing the delivery does is undone by anything the application rolls
back. So the entry which reports the event opens a transaction, writes and commits.

That is the honest answer rather than a complete one. An engine which delivers a task twice
reports it twice, and the outbox spots the repetition by the task id and the reason the engine
named. What it cannot spot is a report about a task the engine took back a moment later, and there
is nothing on this BPMS to ask about that. Decision 3 is about the same hole.

## 3. What a delivery said is remembered in memory, per node, and never persisted

Every other BPMS half of the cockpit reads the current state of a task when the report is
dispatched, seconds after the event was seen. On the Process-Engine-API there is nothing to read
from: no task query, no single-task get, no history. So what arrived with the delivery is kept in
a bounded map per node, and the dispatch is answered from that map. A task the engine has taken
away stays in it as well, marked as ended: the report of its creation may still be waiting, and it
is dispatched after the task is gone. The task leaves the map when a newer one needs the room.

It is deliberately not persisted. An extension which writes the engine's state into the
application's database keeps a second copy of a state nobody can reconcile it with, and the
application's own data is the copy which already exists. The price is said out loud rather than
hidden. A node which restarts between a delivery and its dispatch reports the task without its
details, and a task delivered to one node is unknown to the others.
`vanillabp.cockpit.process-engine-api.remembered-user-tasks` sizes the map, and the repository's
`GAPS.md` says what the Process-Engine-API would have to offer for this to become unnecessary.

## 4. A user task which is gone is reported as completed unless the engine says it was withdrawn - the missing reason answered by decision 7

The Process-Engine-API tells a subscriber that a task it was given is gone. The reason it names is
the only thing which tells the two ways that happens apart. Its reference engine adapter for an
embedded Camunda 7 names `complete` when the task was finished through `UserTaskCompletionApi`,
which is how VanillaBP finishes one. It names `delete` when the task left the engine some other
way, say a cancelled process or somebody finishing it in a task list.

So `delete` is reported as cancelled and everything else as completed, including a termination
which names no reason at all. A termination without a reason is what an engine of an older
Process-Engine-API version sends, and today it is what every engine sends: the VanillaBP adapter
registers the callback which takes only a task id, so the reason never arrives here. Completed is
the better guess in that case. A task the cockpit shows as cancelled reads as work somebody
stopped, and most tasks are finished rather than withdrawn.

## 5. The extension does not subscribe for user tasks itself - superseded by decision 7

The Process-Engine-API hands a delivered task to exactly one subscription. The engine picks the
first subscription which matches a task and remembers it as the one active for that task. An
extension which subscribes next to the VanillaBP adapter would therefore either see nothing or
take the task away from the workflow application, and which of the two happens depends on the
order the two subscriptions were registered in. `PeaSubscriptionProbeTest` holds that against the
in-memory engine the adapter ships, and the API's own reference engine adapter behaves the same
way.

The extension therefore offers a port, `PeaUserTaskObserver`, and waits for the
Process-Engine-API adapter to call the cockpit's handler from the subscriptions it already opens.
Until it does, an application which watches user tasks itself can call the port, and the tests of
this repository drive it the way the adapter would. The startup says all this, rather than leaving
somebody with an empty cockpit and no explanation. The seam is described in this repository's
`GAPS.md` and belongs to `vanillabp/process-engine-api-adapter`.

## 6. A business case appears with its first user task, under the aggregate where nothing else identifies it

The Process-Engine-API reports neither the start nor the end of a workflow, and there is no
instance query to ask afterwards. The only moment this extension learns that a business case
exists is the first user task delivered for it. So that is when the case is reported to the
cockpit: with the first task of a case rather than with every task of it, because a case reported
again and again is a case whose creation date moves. The node remembers which cases it has
reported, in the same bounded way it remembers tasks, so a case which many newer ones have pushed
out is reported as created a second time.

Which workflow the case is depends on what the engine fills in. It is the engine's instance id
where the meta map carries one, and the workflow aggregate's id where it does not. Either way the
cockpit shows the case under the aggregate's id, and an id which changed per event would arrive at
the cockpit server as one case per event. What this costs is written down in the repository's
`GAPS.md`: a workflow without user tasks never appears, and no case is ever reported as completed.

## 7. What the adapter says about a delivery is taken as said, and it is the adapter which says it - the BPMN names superseded by decision 8

The Process-Engine-API adapter works out six things while it routes a user task to a subscription:
which of its engines delivered it, which workflow module and which BPMN process it belongs to,
which subscription key it arrived under, which workflow aggregate the payload names, and what the
engine says about the task. All six travel in the observation, and this extension reads them
rather than working any of them out again.

Two of them may be missing, and that is the adapter saying so rather than guessing. A delivery
whose BPMN process cannot be told is passed over with a line naming the task, because there is no
workflow to report it under; the engine named no process and the subscription serves several. A
termination names no process either, and that costs nothing: what a terminated task was is what
its delivery said, and that is remembered here.

The name a modeller wrote on a user task is the same kind of answer. `ExtensionHandlers`
`#bpmnTaskNameOf` gives what the adapter read out of the model it deployed. The
Process-Engine-API adapter does not fill it yet, so this extension's own pass over the same bytes
still answers where VanillaBP has nothing. That pass exists anyway for the process name, which no
adapter hands over.

This entry replaces three earlier ones, which is why they keep their text and say so in their
headings. Decision 1 put the translation of an engine's prefixed identifiers here, and the adapter
now does it before it builds an observation. Decision 4 said no reason ever reaches this
extension, and the adapter now registers the `TaskTerminationHandler` overload which carries it;
what is left open is what the API means by "finished". Decision 5 described a port this extension
offered while the adapter had no seam. The seam exists, so the port is gone, and so is the startup
message which asked an application to feed it.

## 8. The names a modeller wrote are read out of what the adapter deployed, not out of the file a second time

A process name and a user task name are answers of the same kind as the rest of a delivery. The
adapter read them out of the model it deployed, so this extension asks rather than reading the
file again. `PeaDeployedProcesses` answers both: a process by its name, and a user task as a
`BpmnTaskSpec` which carries the name beside its element id and its form reference.

Decision 7 said the adapter did not fill the names yet, which is why this extension kept a pass of
its own over the same bytes. It fills them now, so the pass is gone. Gone with it is the reason
that pass had to read plain identifiers out of a file while the engine knew scoped ones.

Reading the file twice cost more than the work. The two passes could disagree about what a file
holds, and a file this extension could not read left a workflow module running with its titles
missing, a half state nobody could see from the outside. Now a file the adapter cannot read fails
the deployment, which is where that belongs.

With that pass this extension also gives up its place in VanillaBP's deployment pipeline in this
repository. It registers no `ExtensionWiringService` any more. What it needs to know is recorded
by the adapter while it deploys, and read when a delivery or a cockpit question arrives.

## 9. That a delivery happened is read out of VanillaBP's log, what it said stays in the memory

Two questions look alike, and they have different answers. What does the cockpit show about this
user task, and which user tasks of this business case did this application report? The first one
asks for content, the second one for bookkeeping.

The content stays where decision 3 put it, and that entry stands. `TaskDelivery`, the record
VanillaBP writes about a delivery, carries not one field of that memory: no task name, no
assignee, no candidate users or groups, no dates, no variables. Taking those fields into the
record would make the platform write the engine's state into the application's database, which is
the second copy decision 3 argues against. So a report which needs the content still needs the
node the engine delivered to.

The bookkeeping is read out of the platform's delivery log. VanillaBP writes one record per
delivery it processed, in the transaction which saves the workflow aggregate, and it stamps the
record once the completion of that task reaches the BPMS. That record sits in the application's
own database, so it outlives a restart and every node reads the same one. This extension only
reads it. A second writer would put work into the log which never ran, and the core would then
skip a delivery nobody processed.

Where both can answer, the memory answers. It carries more, and it heard what the engine said,
while a record says that a delivery happened and how it ended. The log adds what the memory never
saw or has forgotten. The rule is applied per task and not per business case: a task the memory
holds is the memory's answer even where the memory says the task is over while the record is
still open. The memory saw the engine take that task away, and the log only learns of an end
which the application asked for.

Two reads are not part of this. `prefilledUserTaskDetails` answers content, and the log holds
none, so it stays on the memory alone. The end of a task stays there too, because the
Process-Engine-API names only the task when it says one is gone, while the log has to be asked
with the workflow module, the BPMN process, the workflow aggregate and the task.

The log leaves two things out, and the repository's `GAPS.md` says what each of them costs. A user
task which no `@WorkflowTask` method of the application claims is never recorded, because a record
carries the outcome of a delivery and nobody processed that one. And a record written on this BPMS
names neither the BPMN element nor the engine's own id of the workflow, because the
Process-Engine-API adapter fills neither. Both are answered here the way a delivery answers them.
The element comes out of what the adapter deployed, and the workflow is the aggregate the case is
shown under.
