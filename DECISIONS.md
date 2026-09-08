# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry of
THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side, and one the Business Cockpit shares has its
entry in `business-cockpit`; a pointer into another repository is the fragile kind this log exists
to avoid.

## 1. An engine's identifiers are translated back through VanillaBP's name-clash avoidance

A workflow module deployed with `use-prefix` runs under identifiers the application never wrote:
the BPMN process id and the external form reference carry the module's prefix, because the
adapter rewrote the file before deploying it. A user task the engine delivers therefore arrives
under the prefixed names, and the cockpit reports the plain ones.

Getting from one to the other is not a string operation. The extension asks VanillaBP's own
name-clash avoidance, the same object the adapter builds its prefixes with, and it asks it for
every delivery whichever mode the module uses - the helper returns its input unchanged where
nothing is prefixed. Cutting a known prefix off a string would work until the adapter changes how
it builds one, and it would mis-attribute a process id which happens to contain the separator.

The BPMN element id of a user task is the one identifier which is never rewritten, which is why
the extension prefers it wherever the engine reports it.

## 2. The entry of an observed event gets a transaction of its own

The Process-Engine-API delivers a task on a thread of the engine's own. There is no transaction
of the application to join, and nothing the delivery does is undone by anything the application
rolls back, so the entry reporting the event opens a transaction, writes and commits.

That is the honest answer rather than a complete one. An engine which delivers a task twice
reports it twice, and the outbox recognizes the repetition by the task id and the reason the
engine named. What it cannot recognize is a report about a task the engine took back a moment
later, and there is nothing on this BPMS to ask about it - which is what decision 3 is about.

## 3. What a delivery said is remembered in memory, per node, and never persisted

Every other BPMS half of the cockpit reads the current state of a task when the report is
dispatched, seconds after the event was observed. On the Process-Engine-API there is nothing to
read from: no task query, no single-task get, no history. So what arrived with the delivery is
kept, in a bounded map per node, and answered from when the dispatch asks. A task the engine has
taken away stays in that map as well, marked as ended: the report of its creation may still be
waiting, and it is dispatched after the task is gone. It leaves the map when a newer task needs
the room.

It is deliberately not persisted. An extension writing the engine's state into the application's
database keeps a second copy of a state nobody can reconcile it with, and the application's own
data is the copy which already exists. The price is stated rather than hidden: a node which
restarts between a delivery and its dispatch reports the task without its details, and a task
delivered to one node is unknown to the others. `vanillabp.cockpit.process-engine-api.remembered-user-tasks`
sizes the map, and the repository's `GAPS.md` says what the Process-Engine-API would have to
offer for this to become unnecessary.

## 4. A user task which is gone is reported as completed unless the engine says it was withdrawn

The Process-Engine-API tells a subscriber that a task it was given is gone, and the reason it
names is the only thing distinguishing the two ways that happens. Its reference engine adapter for
an embedded Camunda 7 names `complete` when the task was finished through
`UserTaskCompletionApi`, which is the way VanillaBP finishes one, and `delete` when the task
disappeared from the engine some other way - a cancelled process, or somebody finishing it in a
task list.

So `delete` is reported as cancelled and everything else as completed, including a termination
which names no reason at all. A termination without a reason is what an engine of an older
Process-Engine-API version sends and, today, what every engine sends: the VanillaBP adapter
registers the callback taking only a task id, so the reason never arrives here. Completed is the
better guess for that case: a task the cockpit shows as cancelled reads as work somebody stopped,
and most tasks are finished rather than withdrawn.

## 5. The extension does not subscribe for user tasks itself

The Process-Engine-API hands a delivered task to exactly one subscription: the engine picks the
first subscription matching a task and remembers it as the one active for that task. An extension
subscribing next to the VanillaBP adapter would therefore either see nothing or take the task
away from the workflow application, decided by the order the two subscriptions happened to be
registered in. `PeaSubscriptionProbeTest` holds that against the in-memory engine the adapter
ships, and the API's own reference engine adapter does the same thing.

The extension therefore offers a port, `PeaUserTaskObserver`, and waits for the Process-Engine-API
adapter to compose the cockpit's handler into the subscriptions it already opens. Until it does,
an application which observes user tasks itself can call the port, the tests of this repository
drive it the way the adapter would, and the startup says so rather than leaving somebody with an
empty cockpit and no explanation. The seam is described in this repository's `GAPS.md` and belongs
to `vanillabp/process-engine-api-adapter`.

## 6. A business case appears with its first user task, under the aggregate where nothing else identifies it

The Process-Engine-API reports neither the start nor the end of a workflow, and there is no
instance query to ask afterwards. The only moment this extension learns that a business case
exists is the first user task delivered for it, so that is when the case is reported to the
cockpit - with the first task of a case rather than with every task of it, because a case
reported again and again is a case whose creation date moves. The node remembers which cases it
has reported in the same bounded way it remembers tasks, so a case which many newer ones have
pushed out is reported as created a second time.

Which workflow the case is depends on what the engine fills in: its instance id where the meta
map carries one, and the workflow aggregate's id where it does not. The aggregate's id is what
the cockpit shows the case for either way, and an id which changes per event would arrive at the
cockpit server as a case per event. What this costs - a workflow without user tasks never
appears, and no case is ever reported as completed - is written down in the repository's
`GAPS.md`.
