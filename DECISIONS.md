# Decision log

Decisions this repository's code points at. A number is handed out once. It is never reused and
never renumbered, so a citation stays resolvable. A decision which is overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it means an entry
of THIS repository. A decision the platform shares has its own entry in
`adapter-platform-integration`, written from that side, and one the Business Cockpit shares has
its entry in `business-cockpit`. A pointer into another repository is the fragile kind this log
exists to avoid.

## 1. What the adapter says about a delivery is taken as said, and it is the adapter which says it

The Process-Engine-API adapter works out six things while it routes a user task to a subscription:
which of its engines delivered it, which workflow module and which BPMN process it belongs to,
which subscription key it arrived under, which workflow aggregate the payload names, and what the
engine says about the task. All six travel in the observation, and this extension reads them
rather than working any of them out again.

That includes the identifiers. A workflow module deployed with `use-prefix` runs under identifiers
the application never wrote. The BPMN process id and the external form reference carry the module's
prefix, because the adapter rewrote the file before it deployed it. So a user task reaches the
adapter under the prefixed names, while the cockpit reports the plain ones. The adapter translates
them back through VanillaBP's own name-clash avoidance before it builds an observation. That is not a
string operation. Cutting a known prefix off a string would work until the adapter changes how it
builds one, and it would mis-read a process id which happens to contain the separator. The BPMN
element id of a user task is the one identifier which is never rewritten. That is why the extension
prefers it wherever the engine reports it.

Two of the six may be missing, and that is the adapter saying so rather than guessing. A delivery
whose BPMN process cannot be told is passed over with a line naming the task, because there is no
workflow to report it under: the engine named no process and the subscription serves several. A
termination names no process either, and that costs nothing: what a terminated task was is what its
delivery said, and that is remembered here.

A termination carries the reason the engine named. The adapter registers the `TaskTerminationHandler`
overload which carries it, and decision 4 says what the reason means for the report. What is left
open is what the API means by "finished".

The names a modeller wrote are answers of the same kind. A process name and a user task name are
read out of the model the adapter deployed, not out of the file a second time. `PeaDeployedProcesses`
answers both: a process by its name, and a user task as a `BpmnTaskSpec` which carries the name
beside its element id and its form reference. Reading the file twice cost more than the work. The two
passes could disagree about what a file holds, and a file this extension could not read left a
workflow module running with its titles missing, a half state nobody could see from the outside. Now a
file the adapter cannot read fails the deployment, which is where that belongs. So this extension has
no place in VanillaBP's deployment pipeline in this repository. It registers no
`ExtensionWiringService`. What it needs to know is recorded by the adapter while it deploys, and read
when a delivery or a cockpit question arrives.

The extension does not subscribe for user tasks itself. The Process-Engine-API hands a delivered task
to exactly one subscription. The engine picks the first subscription which matches a task and
remembers it as the one active for that task. An extension which subscribes next to the VanillaBP
adapter would therefore either see nothing or take the task away from the workflow application, and
which of the two happens depends on the order the two subscriptions were registered in.
`PeaSubscriptionProbeTest` holds that against the in-memory engine the adapter ships, and the API's
own reference engine adapter behaves the same way. So the adapter calls the cockpit's observer from
the subscriptions it already opens, and decision 20 says how a details provider gets its values from
there.

## 2. The entry of an observed event gets a transaction of its own

The Process-Engine-API delivers a task on a thread of the engine's own. There is no transaction of
the application to join, and nothing the delivery does is undone by anything the application rolls
back. So the entry which reports the event opens a transaction, writes and commits.

That is the honest answer rather than a complete one. An engine which delivers a task twice
reports it twice, and the outbox spots the repetition by the task id and the reason the engine
named. What it cannot spot is a report about a task the engine took back a moment later, and there
is nothing on this BPMS to ask about that. Decision 3 is about the same hole.

## 3. What a delivery said is remembered in memory, per node, and never persisted - what the memory is for amended by decision 10

Every other BPMS half of the cockpit can read the state of a task from its engine whenever it is
asked. On the Process-Engine-API there is nothing to read from: no task query, no single-task get,
no history. So what arrived with the delivery is kept in a bounded map per node, and every
question about a task is answered from that map. A task the engine has taken away stays in it as
well, marked as ended, because this map is the only source which saw that happen. A task leaves
the map when a newer one needs the room.

It is deliberately not persisted. An extension which writes the engine's state into the
application's database keeps a second copy of a state nobody can reconcile it with, and the
application's own data is the copy which already exists. The price is said out loud rather than
hidden. A node which restarts knows nothing about the tasks it was given before, and a task
delivered to one node is unknown to the others.
`vanillabp.cockpit.process-engine-api.remembered-user-tasks` sizes the map, and the repository's
`GAPS.md` says what the Process-Engine-API would have to offer for this to become unnecessary.

Decision 10 narrows what the map has to carry. The report of a delivery no longer reads it later,
because it is built while the delivery is handled.

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

*Merged into decision 1 on 2026-10-12, before the 1.0 release.* Decision 1 now says why the
extension opens no subscription of its own, and that the adapter calls the cockpit's observer from
its own subscriptions.

## 6. A business case appears with its first user task, under the aggregate where nothing else identifies it - a task of a called step belongs to the caller's case since decision 21

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

*Merged into decision 1 on 2026-10-12, before the 1.0 release.* Decision 1 now says which facts of a
delivery the adapter states, and that this extension takes them as said.

## 8. The names a modeller wrote are read out of what the adapter deployed, not out of the file a second time

*Merged into decision 1 on 2026-10-12, before the 1.0 release.* Decision 1 now says that the names a
modeller wrote come from what the adapter deployed.

## 9. That a delivery happened is read out of VanillaBP's log, what it said stays in the memory

Two questions look alike, and they have different answers. What does the cockpit show about this
user task, and which user tasks of this business case did this application report? The first one
asks for content, the second one for bookkeeping.

The content stays where decision 3 put it, and that entry stands. `TaskDelivery`, the record
VanillaBP writes about a delivery, carries not one field of that memory: no task name, no
assignee, no candidate users or groups, no dates, no variables. Taking those fields into the
record would make the platform write the engine's state into the application's database. That is
the second copy decision 3 argues against. So a report which needs the content still needs the
node the engine delivered to.

The bookkeeping is read out of the platform's delivery log. VanillaBP writes one record per
delivery it processed, in the transaction which saves the workflow aggregate. It stamps the
record once the completion of that task reaches the BPMS. That record sits in the application's
own database, so it outlives a restart and every node reads the same one. This extension only
reads it. A second writer would put work into the log which never ran, and the core would then
skip a delivery nobody processed.

Where both can answer, the memory answers. It carries more, and it heard what the engine said, while
a record says that a delivery happened and how it ended. The log adds what the memory never saw or
has forgotten. The rule is applied per task and not per business case. For a task the memory holds,
the memory answers, even where it says the task is over and the record is still open. The memory saw
the engine take that task away, and the log only learns of an end which the application asked for.

Two reads are not part of this. `prefilledUserTaskDetails` answers content, and the log holds
none, so it stays on the memory alone. The end of a task stays there too. When the
Process-Engine-API says a task is gone, it names only the task, but the log has to be asked
with the workflow module, the BPMN process, the workflow aggregate and the task.

A user task which no `@WorkflowTask` method of the application claims is never recorded, because a
record carries the outcome of a delivery and nobody processed that one. The repository's `GAPS.md`
says what that costs.

#### What a record names

The adapter fills the workflow id and the BPMN element of a record as far as it knows them. The
workflow id is what the engine names as the process instance in the meta map of a delivery. An
engine which names none leaves the field empty. The BPMN element is what the engine names as the
activity in the meta map. Where it names none, the adapter reads the element out of the model it
deployed. That works where the model has one user task with that form reference. Where several user
tasks of one process share the form, the model cannot tell them apart, and the field stays empty. A
record written before the adapter filled these fields names neither.

The reader of the log takes both fields out of the record first and answers only an empty one
itself. For an empty element it reads what the adapter deployed, by the same rule as for a delivery.
For an empty workflow it takes the aggregate's id, as decision 6 says. The observer answers a
delivery by the same two rules. So a task read back out of the log on a fresh node has the same
element and the same workflow as the report of its delivery.

Every row of the log also carries the version of its process (decision 110 of
`adapter-platform-integration`). The Process-Engine-API adapter names the version tag the engine
wrote into the meta map of the task, and VanillaBP writes that into the row. The reference engine
adapter fills no tag today, so its rows carry none. The reader takes the version out of the record.
So a task read back on a fresh node names the version the report of its delivery named.

#### The version of a workflow found through a task

`workflowsOfAggregate` reports a workflow under the version of the open user task it was found
through. Where the memory knows the task, the memory answers first, and its version stands even
where it is none. That is what the report of the delivery said, so an update under the same version
changes nothing the cockpit shows.

A task only the log knows is answered in this order. First the memory, where it holds another task of
the same workflow, finished or not, with the version tag that task carried. Then the record. Then
VanillaBP's note of the start (decision 14), where the note names the same workflow or the record
named no workflow. A note is about the one workflow VanillaBP started last for the case. The memory
answers with the tag here, not with what it shows. Where the engine filled no tag, the memory shows
what this application deployed, and a deployment key picks no details provider which names a
version.

Where none of the three names a version, the workflow is left out, and a warning says why, once per
case. That is the rule of decision 14, for the same reason. The version picks the
`@WorkflowDetailsProvider` method. A report without one skips every method which names a version, and
the cockpit would replace the details it shows with an empty map. A record without a version cannot
tell an engine which fills no tag from a record which lost it: one written before the platform kept
the version, or one in an application's own store which keeps none. Nothing here can tell whether the
application has a details provider which names a version.

What that costs: on an engine which fills no tag, a fresh node does not report the change of a case
whose open task it knows only from the log. An application without any versioned details provider
loses that update. The cockpit keeps what it shows until the engine delivers the next user task of
that workflow to this node.

User tasks are not part of this rule, and they need none. A report of a user task needs what the
engine said about it, and the log holds none of that. So a task only the log knows is not reported at
all, whatever its version (entry 10 in the repository's `GAPS.md`). The version of its reference
reaches no `@UserTaskDetailsProvider` method today. It is correct all the same, so that a later
source of the content finds it.

## 10. The report is built when the task is delivered, not when its entry is sent

The Business Cockpit used to put a report together while it sent the outbox entry, seconds after
the event. Since decision 26 of `business-cockpit` it builds the report at the event and lets the
report travel inside the entry. `prefilledUserTaskDetails` and `prefilledWorkflowDetails` of this
half therefore run on the engine's delivery thread, in the transaction decision 2 opens for the
entry.

Nothing in this half had to change for it. `PeaCockpitObserver` already fills the whole prefill
out of the delivery and remembers it BEFORE it hands the event over, and the bridge reads it back
from there. The end of a task works the same way round: the report is built first, and the task is
marked as ended after it. Both places used to be tidy and are now the reason this works, so both
of them say why.

This narrows decision 3. The memory no longer has to outlive the sending of an entry. A report which
was written is in the entry, so it costs nothing if the node restarts, if another node sends the
entry, or if newer tasks push the task out. The memory is still the only source for three things:
how a task ended, which tasks of a business case are open, and what `getUserTask` shows. Entry 10 of
`GAPS.md` says what each of those costs on a fresh node. An ended task therefore keeps the age it
had instead of moving to the young end of the map. Nothing waits for it any more, so it must not
push out a task somebody is still working on.

#### Two ends: a completion and a cancelation

Every text of this repository uses these two words. A completion is a user task which ends because
it was finished, seen from the engine. What it meant for the business may well be a rejection, and a
BPMN error event may pick that up. A cancelation is the engine taking the task away without
finishing it, say through a boundary event, and the task becomes pointless. Both arrive at
`PeaCockpitObserver#userTaskTerminated`, and only `PeaUserTaskObservation#reason()` tells them
apart.

The reason `complete` only reaches this extension for a completion which went through the API. A
task somebody finished in a task list arrives as `delete`, so decision 4 reports it as cancelled.
What the cockpit shows then is work somebody stopped, for work which was done. Entry 2 in `GAPS.md`
carries that one, and it is the reason the words of this entry are not the words a cockpit user reads
off a task.

#### A details provider which fails

A details provider which throws takes the delivery with it. It runs while the entry is built, in the
transaction decision 2 opens for that entry, so a provider which throws rolls that transaction back,
and no entry is written. Since decision 12 of the `process-engine-api-adapter` an observer which
throws fails the delivery it was told about. What follows was read off the code of both sides and
measured on 2026-09-18 against the API's own reference implementation for an embedded Camunda 7
(`process-engine-adapter-camunda-platform-c7-embedded-spring-boot-starter` 2025.11.1 on Camunda 7.24
and H2, user tasks pulled once a second) and against this repository's own Spring Boot test
application.

A delivery:

- The exception leaves `PeaCockpitObserver` as it is. The adapter wraps it and names this observer,
  the task and the workflow module in the message. It writes the whole failure to its own log and
  hands it to the engine as a failed delivery.
- The application keeps its notification. The adapter runs the `@WorkflowTask` method of the
  delivery before it lets the failure out. VanillaBP knows a repeated delivery by its task id, so
  that method runs once per task, however often the engine delivers it.
- The task is not lost. The reference implementation keeps the task in the engine, raises no
  incident, and offers the task again at the next pull. The API itself promises nothing about a
  handler which throws, which is entry 25 of that repository's `GAPS.md`.

So a broken provider costs the report of a delivery while it is broken, and the report arrives with
the next delivery once somebody fixed it. That is what this half wants. An error which nobody has to
answer is an error nobody fixes.

A completion reaches the engine through `UserTaskCompletionApi#completeTask`, and the reference
implementation calls the termination handler inside that very call. It runs on the thread which
asked for the completion, inside that caller's transaction, and it names the reason `complete`. So
the observer and everything it does belong to the caller, and a failure travels back to it. That
caller is not the application's thread. Completing a user task is a phase-two operation of the
Process-Engine-API adapter. It is scheduled while the application's transaction commits, and
VanillaBP's phase-two outbox dispatches it afterwards. In the test application no details provider
had run while the application's transaction was still open, nor right after it committed. It ran a
moment later on an outbox worker thread. An application which calls
`ProcessService#completeUserTask` therefore never sees what a broken provider throws, and the
failure does not roll back its own transaction.

What a broken provider costs a completion is decided by the outbox and by the engine together. The
failed dispatch is a failed outbox entry, and the outbox brings that entry back. An embedded engine
takes part in the transaction the dispatch runs in, so there the rollback takes the completion with
it. The user task is still there afterwards, and the next pull offers it again as a new delivery. The
next attempt then completes the task again and reports the end once the provider works. A remote
engine and the in-memory engine of the tests keep the completion. There the next attempt finds no
task. VanillaBP reads that as a stale entry, writes one WARN line and consumes the entry, and the
report of that end is gone. That is what the test application showed: one retry, one warning, and
nothing at the cockpit server after it.

A cancelation is the quiet end. The reference implementation notices at its next pull that a task it
had delivered is gone. It calls the handler on one of its own worker threads, outside any transaction
of the application, and names the reason `delete`. The subscription of that task is forgotten before
the handler runs, so the same end is never offered a second time. The failure travels out of the pull
cycle into the scheduler's error log. The task is not delivered again, and the terminations which were
queued behind it in that cycle are skipped with it. `PeaCockpitObserver#userTaskTerminated` marks a
task as ended only after its report was written, which was meant to let a second termination report
the task again. Nothing on this BPMS sends a second one. So a report lost on a cancelation is lost for
good, and the task stays open in the memory of the node and open in the cockpit, the way it does for
a node which never saw the delivery (entry 11 in `GAPS.md`).

A repeated delivery does not become a second outbox entry. The attempt which failed left none, and
the attempt which works writes one, under the idempotency key of decision 4 of `business-cockpit`:
the operation, the adapter id, the task and the kind of event. A repeat carries the same kind, so
it carries the same key. A waiting entry with that key is replaced, not joined. The business case
is reported once as well. Its entry is written first, in a transaction of its own. The node marks
the case as reported once that entry is in. So a repeat of the same task finds the case reported
and skips it.

`FailingDetailsProviderTest` on both platforms holds all of this against the adapter's own
delivery, with one test per end.

## 11. A details provider which fails takes the delivery with it - what the end of a task costs corrected by decision 12

*Merged into decision 10 on 2026-10-12, before the 1.0 release.* Decision 10 now says that a details
provider which fails takes the delivery with it, and what that costs.

## 12. A completion and a cancelation are two different ends

*Merged into decision 10 on 2026-10-12, before the 1.0 release.* Decision 10 now says what a
completion and a cancelation are, and what a failing details provider costs each of them.

## 13. Every wait for a report names the report

Decided on 2026-10-03.

No test of this repository waits for the next report on a collecting path any more. A wait on
`/usertask/created` or `/workflow/created` names the user task or the business case it is about,
through `CockpitServer.awaitRequest` when it reads the report afterwards and through
`awaitRequestOf` when only the arrival matters. The 17 waits which took whatever arrived are gone,
and the cockpit server of the test refuses such a wait from now on.

Why:

Every test of a module reports into one server, and the dispatch of an outbox entry outlives the
test which caused it. A report of an earlier test therefore arrives on a collecting path at any
moment. A wait which takes the next report of its kind is satisfied by it. The test walks on
although nothing it provoked has happened yet.

Eight of the 17 made that costly, because they stood one line above a `forgetRequests()`:

```java
aDeliveredUserTask(aggregate, "task-6");
CockpitServer.awaitAnyRequest("/usertask/created");
CockpitServer.forgetRequests();
```

The wait takes the stale report, the forget throws away the report of `task-6`, and the wait
further down then waits for a report nobody owes it any more. It falls 30 seconds later, in
another part of the test than the one which was wrong.

The `forgetRequests()` stays. It is what tells two reports about the same case apart: the test
waits for the first report, forgets it, provokes the second and waits again. `task-6` is reported
twice in that test and the identifier alone cannot separate the two, so the order of those steps
has to. The wait in front of the forget was the only broken part.

The other nine waits have the same cause and a quieter effect: they take a stale report, assert
nothing straight after it, and only make the test shorter than it reads.

What a wait names here:

| what is waited for |        path         |          what the wait names           |
|--------------------|---------------------|----------------------------------------|
| a user task        | `/usertask/created` | `"userTaskId":"<task id>"`             |
| a business case    | `/workflow/created` | `"workflowId":"<id of the aggregate>"` |

That is the form `RestartedNodeTest` and `PeaCockpitTest` already used, so the 17 places are now
written the same way as the 14 which story `1373` moved.

`quarkus/deployment/.../PeaCockpitTest` keeps its `forgetRequests()` calls in the test bodies and
gets no `@BeforeEach`. Each of its tests now waits for its own report, so what an earlier test
left in the server reaches none of them. A `@BeforeEach` would only hide which test depends on
what.

Where this is referred to, each in its own words rather than by number:

- `spring-boot/.../PeaCockpitTest.java`, `spring-boot/.../FailingDetailsProviderTest.java`,
  `spring-boot/.../UnservedUserTaskTest.java`: 9 waits
- `quarkus/deployment/.../PeaCockpitTest.java`,
  `quarkus/deployment/.../FailingDetailsProviderTest.java`,
  `quarkus/deployment/.../UnservedUserTaskTest.java`: 8 waits
- the six `awaitAnyRequest` calls left in this repository all carry the id of their case, so they
  stay
- the decision log of `business-cockpit`: why the cockpit server refuses the wait instead
  of trusting the next test class to get it right
- `FailingDetailsProviderTest` holds the behaviour of decision 10 and asserts the same things as
  before. It only waits for the right report now

## 14. A business case without an open user task is found by the workflow VanillaBP started

`BusinessCockpitService.aggregateChanged` names a business case by its aggregate id. The bridge has
to answer which workflows belong to it. The open user tasks it knows, from the memory of this node
and from VanillaBP's delivery log, answer that only while the case has one. A workflow busy with a
service task, or one waiting for a message, has none. So the change of such a case would reach the
cockpit as nothing.

VanillaBP writes down the engine's id of a workflow when it starts it. The Process-Engine-API adapter
hands over the `instanceId` the engine answered the start with. The engine answers a start with no
version, so the note gets one from a delivery row of the same workflow (decision 110 of
`adapter-platform-integration`). That row carries the version tag where the engine fills it. It may
come from another node, or from before a restart, where the memory of this node holds nothing.
`WorkflowElection#workflowStartOf` reads id, version and adapter together. It asks no engine, waits
for nothing and throws nothing.

**The promise.** The Process-Engine-API promises that the `instanceId` a start answers with is the
`processInstanceId` the tasks of that instance name in their meta map. Its maintainers confirmed this
on 2026-10-05 (bpm-crafters/process-engine-api issue 318): an engine which names another id has a
bug. The promise is about the id where one is named. It does not make an engine name one.

**The open tasks answer first.** `workflowsOfAggregate` asks the open user tasks first. Only where
none is known does it ask `workflowStartOf`, once. A case is created in the cockpit with its first
user task, under the workflow id that delivery named (decision 6). On an engine which keeps the
promise, the task and the note give the same id, so the order changes nothing there. It still matters
for an engine which names no instance in its tasks. Such an engine shows its cases under the
aggregate's id (decision 6), while the note names the engine's instance. The order keeps a change of
such a case under the id the cockpit knows. It also protects against an engine which breaks the
promise. We tried the other order. With the note asked first, `PeaCockpitTest` of the Spring Boot
module failed only in the test with an engine which names no instance. The update went out under the
id of the start, and the cockpit does not know that id. So the order stays. A case without any open
task is still reported under the id of the start, and on an engine which names no instance in its
tasks that is an id the cockpit does not show the case under. Nothing here can tell such an engine
apart.

**The version.** A workflow found by its note takes the version of the note. Where the note names
none, the memory of this node answers: a delivery of that workflow which it still remembers,
finished or not. Where neither names one, the change is not reported, and a warning says why, once
per case. Only a delivery names a version on this BPMS. There is no repository to ask, and what this
application deployed is not the version a running workflow started on. The version picks the
`@WorkflowDetailsProvider` method. A report without one skips every method which names a version, so
it arrives with empty details. The cockpit server takes the details of an update as they come, so
that report would replace what the cockpit shows with nothing. No report keeps it as it is.

`WorkflowStart#versionsAreReported` tells "not yet" (true) from "never" (false). This adapter says
that its deliveries carry the version tag, so its notes say "not yet". The platform answers "never"
in two more cases: for a note which names no adapter, and for an adapter which said nothing about
versions at all. Neither proves that the application has no details provider which names a tag. So
an empty version is read the same way whatever the flag says: the memory answers, or nothing is
reported.

`prefilledWorkflowDetails` reads what the adapter deployed by the workflow module and the BPMN
process, not by the workflow id, so it finds a workflow known only by its start as well. It shows the
version of the memory first. Where the memory has none, it shows the version the reference names,
which is the one that picked the details provider. Only then does it fall back to what this
application deployed.

A note which names another adapter is left out, and the case is treated as one VanillaBP knows nothing
about. Its id belongs to another engine. A note which names no adapter is still taken.

No note covers every case where VanillaBP does not know. Nobody started the workflow, it started
before VanillaBP wrote such notes, the note is older than
`vanillabp.delivery.workflow-start-retention`, or the engine answered the start with no id. These
cannot be told apart, so the bridge reads nothing into it. It answers out of the open tasks alone and
says once per case that nothing is known.

An id says what was true at the start. It does not say that the workflow still runs. The bridge uses
it to name the workflow in a report and never sends it to the engine. Since decision 5 of
`adapter-platform-integration`, `adapterIdOfWorkflow` reads the note of the start first and no longer
throws after a workflow ended, for as long as the note lives. So `aggregateChanged` reaches this bridge
after the end as well. It answers as it does for a case between two user tasks: the note names the
workflow, the note or the memory names the version, and the cockpit gets an update. This bridge holds
no client of the engine, so nothing can reach the engine from here. That is what an application asks
for when it changes a case after its end.

`userTasksOfAggregate` and `userTaskOfAggregate` do not use the note, because the id of a workflow
names no task. But `userTasksOfAggregate` does not warn about a case whose workflow VanillaBP started.
A workflow between two user tasks is a normal state.

This leaves decision 6 as it stands. A case still appears in the cockpit with its first user task,
and a workflow without any user task still never appears, because nothing reports it as created.
What changes is that a case which once had a user task keeps receiving its updates after its tasks
are done.

**A task which names another instance is said out loud.** `PeaBusinessCases` compares the instance
a task names with the note VanillaBP wrote down when it started the task's own process. Where both
are known and they differ, it logs a warning. It does so once per instance, with the first task of
it, because the answer for an instance is remembered. A step of a called process is never compared:
its instance is another one by design (decision 21). A called process VanillaBP never started has
no note, and a task which names no instance has nothing to compare. Both stay silent. So a task
which is no step costs one read of a note, even where its process shares its aggregate with
nobody. That is one read per instance, the same as a step costs. The warning also comes where
VanillaBP started a second workflow for the same aggregate while a task of the first is still open.
The note names the last start. The text says so.

**The in-memory engine of the tests keeps the promise.** It names the instance whose start variables
a task carries, with the same value and the same type. A task which matches no start names none. The
tests of this repository hand over the aggregate's id with its real type, so their cases stand under
the id of the start. A few tests hand it over as text on purpose. That plays an engine which names no
instance.

## 15. A delivery record names the workflow and the BPMN element where the adapter can - the in-memory engine corrected by decision 22

*Merged into decision 9 on 2026-10-12, before the 1.0 release.* Decision 9 now says which workflow
and which BPMN element a record of the delivery log names, and how the reader answers an empty one.

## 16. A business case without an open user task takes its version from VanillaBP's note of the start

*Merged into decision 14 on 2026-10-12, before the 1.0 release.* Decision 14 now says that a case
found by its note of the start takes the version of that note.

## 17. A user task known only from the delivery log takes its version from its record, and without one its case is not reported

*Merged into decision 9 on 2026-10-12, before the 1.0 release.* Decision 9 now says which version a
task known only from the delivery log names, and that its case is not reported without one.

## 18. A details provider does not serve a user task, so a task only a task list works on is marked

VanillaBP now ends the start when a task of a claimed BPMN process has no `@WorkflowTask` method
and is not marked with `implemented-externally=true`. This holds for a user task as well. The rule
lives in the core, in `adapter-platform-integration`. Before it, a user task without a method
passed, and the Process-Engine-API adapter only named it in the log.

The question for this extension was whether a `@UserTaskDetailsProvider` method counts as serving
a user task. It does not. The provider says what the cockpit shows about a task. Nothing in it does
the work of the task, and a task with only a provider is still a task a task list works on. So an
application marks such a task like any other task which something else serves.

The test applications on both platforms follow this. The taxi ride has two user tasks without a
method, `Inspect` and `Pay`. Each is marked on its own, at the task, by its element id. A line for
the whole workflow or the whole module would cover them too. It would also cover a third user task
which somebody adds to the model and forgets the method for, and that is the mistake the start
should catch.

`UnservedUserTaskTest` still measures what the cockpit hears about a task no method claims, now
with the task marked. `UnmarkedUserTaskTest` on both platforms takes the line away for one task and
shows that the start ends and names the line to add.

Nothing in the extension's code changed. No entry of this log says otherwise: the memory still
answers for a task no method claims, and the delivery log still holds only the tasks a handler was
called for, as decision 3 and decision 9 say.

## 19. A process no `@WorkflowService` claims is not reported

This extension follows the rule which `adapter-platform-integration` decided for processes nobody
claims.

The platform sorts the BPMN processes an engine runs into three kinds. A process is claimed when a
`@WorkflowService` class of the application names it, in `bpmnProcess` or in `secondaryBpmnProcesses`.
A process called by a call activity counts as claimed when the service of the caller names it there. A
process can also travel in the file of a claimed one without being claimed. The core ends the start for
it unless the application marks it with
`vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`. And a process
can belong to somebody else and run on the same engine. For the last two, VanillaBP changes nothing in
the BPMN, opens no subscription and reports nothing.

This extension follows the rule. The observer reports a delivered user task only when
`WorkflowTaskWiring#isClaimedByAWorkflowService` says yes for its workflow module and BPMN process.
It gets `WorkflowTaskWiring` as a bean, on Spring Boot and on Quarkus. It does not ask the election,
and it does not catch the exception of `resolveWorkflowAggregateIdName` itself.

Before, the outcome was already mostly the same, but by accident. The Process-Engine-API adapter
opens no subscription for a process nobody claims. A task of such a process can still arrive, because a
subscription matches a task by its form reference and two processes may show the same form. A process
of somebody else was then skipped because this application never deployed it. A process which came
with the application's own file was skipped only because the adapter could not name its aggregate.
Now the observer asks the question itself, and both cases stop at the same check.

This extension adds nothing to a BPMN file and registers no listener, on any process. This BPMS has
no listener to install, so there is nothing to leave out for a process nobody claims.

`PeaCockpitObserverTest` holds the process nobody claims and the secondary process at the observer.
`OnlyClaimedProcessesTest` on both platforms boots an application whose file carries a claimed
process, a process the claimed one calls, and a process nobody claims, and delivers a task of somebody
else's process as well.

## 20. A details provider gets its `@TaskParam` values from the adapter's one subscription

This extension opens no subscription of its own and asks the engine for no variable. A
`@TaskParam` of a details provider is filled from the payload of the adapter's delivery, which the
observer hands to the cockpit as it came.

That payload now carries what the details providers read. The platform collects the `@TaskParam`
names of every method an extension registered a contract for, and answers them through
`WorkflowTaskWiring#extensionTaskParameterNames`. The Process-Engine-API adapter adds those names to
what its subscription asks for, with the element id and the task definition as keys. Both
repositories wrote that down in their own logs: decision 122 of `adapter-platform-integration` and
decision 20 of `process-engine-api-adapter`.

One thing has to hold for this. The cockpit registers its contract for details providers before the
adapter deploys and subscribes. It does so while the application starts, on Spring Boot and on
Quarkus alike. A contract registered later would not change a
subscription which is already open.

A second subscription was never a way out. The engine hands a task to one subscription only (entry
1 in the repository's `GAPS.md`), so a subscription of this extension would take the task away
from the application.

`PeaCockpitTest` on both platforms holds this. The details provider of the first user task reads a
variable which no `@WorkflowTask` method reads. The test asserts that the subscription asks for it
and that the report the cockpit receives carries its value. This closes entry 4 in `GAPS.md`.

## 21. A task of a called process belongs to the caller's case where both share the workflow aggregate - the note of the task's own process read by decision 22

Decision 6 reports a business case with its first user task, under the process instance the engine
names. Until now that was the instance the task sits in, so every called process became a case of
its own, under its own process. Where a call activity came first, the case appeared under the called
process. Camunda 7 and Camunda 8 show a called process as a step of the caller's case. Decision 61
of `business-cockpit` (story 1472) says the same for all BPMS: notifications form one group per
case, and only a process with its own aggregate forms a group of its own.

**The rule is the one of Camunda 7 and Camunda 8.** A task of a called process which shares the
workflow aggregate of its caller is a task of the caller's case. A called process with an aggregate
of its own is a case of its own.

**How the caller is found.** The Process-Engine-API has no call hierarchy. A delivery names the
instance the task sits in and nothing above it. So the extension uses two answers of the core:

- Which declared processes share the task's aggregate. It asks
  `WorkflowTaskWiring#workflowsShareTheWorkflowAggregate` for every process
  `ExtensionHandlers#bpmnProcessesOf` lists, the same answer the Camunda 7 and Camunda 8 adapters use
  for their call activities. The extension builds no check of its own. A process which shares its
  aggregate with no other process is a case of its own, and nothing else is read. A process the core
  does not know shares nothing.
- Which instance VanillaBP started for the aggregate. That is its note of the start, read with
  `WorkflowElection#workflowStartOf` under the first process which shares the aggregate, in the
  order the application declared them. The primary process of a workflow service comes first, and
  that is the process the start row is written under.

Where the task sits in the instance of the note, it belongs to that case. Where it sits in another
instance, it sits in a called process. Its `workflowId` is then the instance of the note. The case
is reported under the caller's process, with the version VanillaBP wrote down, or else with the one
this adapter deployed. The task itself keeps naming its own process (decision 29 of the Camunda 8
cockpit adapter has the same rule).

**Where nothing can tell the two apart,** the task stays under the instance it sits in, as before.
That is a delivery without an instance id, where decision 6 files the task under the aggregate's id,
and an aggregate whose start VanillaBP did not write down or whose note is older than
`vanillabp.delivery.workflow-start-retention`.

The answer is remembered per instance. Otherwise every delivery of a task would read the note again,
and an instance does not change its caller. The class is `PeaBusinessCases`.

**Not changed:** the bridge still answers the questions of the cockpit about an aggregate with the
tasks of its primary process. A task of a called process reaches the cockpit when the engine
delivers it, and `aggregateChanged` does not report it again. That is the gap story 1472 closed for Camunda 8, and it stays open here.

`PeaCockpitObserverTest` shows the three cases: a step, a called process with an aggregate of its
own, and a task of the instance VanillaBP started. `PeaCockpitTest` shows the step on a booted
application.

## 22. The id of a start is the id its tasks name, and a task which names another one is said out loud

*Merged into decision 14 on 2026-10-12, before the 1.0 release.* Decision 14 now says what the
Process-Engine-API promises about the id of a start, and that a task which names another instance
is warned about.

## 23. The IDE learns the key of this half from the Spring Boot jar, and a test keeps it complete

Built by story 994 on 2026-10-28.

An IDE offers the keys of `application.yaml` and marks a key it does not know. It reads them from
`META-INF/spring-configuration-metadata.json` in the jars of the application. Until this story
the Spring Boot jar of this half shipped no such file.

This half binds no class of its own. Its one key, `process-engine-api.remembered-user-tasks`,
stands below `vanillabp.cockpit`, in the tree of the extension's platform-neutral half, and that
half binds it. So the configuration processor finds nothing to describe here. It still runs, and
it copies the hand-written `META-INF/additional-spring-configuration-metadata.json` into the jar.
That file describes the key with its type, its default and what it does. A key which a workflow
module must not write cannot get an entry of its own, because the format has no name for "any
module". The description says instead that such a key ends the start.

The keys this half reads are a list in the code, `PeaCockpitSettings.KEYS_READ`. The check which
ends the start when a workflow module writes one of them walks that list. So does
`EveryKeyIsKnownToTheIdeTest`. It fails when a key of the list has no entry, when an entry
describes a key the list does not name, and when a default or a type in the file differs from the
code. It also binds each key of the list onto the class the application binds and reads it back,
so the list cannot name a key nobody reads. We chose a list over searching the sources for key
names, because a list is what the code itself uses.

The key is described here and not in the jar of the extension's neutral half. Only an application
with this half reads it, and its default lives here. That jar ships no metadata today. When it
gets some, it should leave this key out, or two jars describe the same name.
