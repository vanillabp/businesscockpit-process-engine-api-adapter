# What the Business Cockpit needs and the Process-Engine-API does not offer

This adapter was built straight against the bpm-crafters
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api). There was no Version 1 of
it, so nothing here is a regression. Every entry below is a question the cockpit asks a workflow
engine and this API has no answer for yet, written down as the basis of a conversation with the
Process-Engine-API team.

Each entry says what the cockpit needs, what the API offers today, where that can be read, what it
costs somebody looking at the cockpit, and what would close it. A number is handed out once and
never reused. An entry which is closed keeps its number and says so, because a conversation about
"gap 7" outlives the file. The first two needed no change to the Process-Engine-API at all. They
were work in
[vanillabp/process-engine-api-adapter](https://github.com/vanillabp/process-engine-api-adapter), and
that work is done: entry 1 is closed, and entry 2 is closed as far as the adapter reaches.

The file which says the same thing from the adapter's side is that repository's own `GAPS.md`. This
one adds what a cockpit needs on top of what a workflow application needs.

## 1. Nothing hands a delivered user task to a second observer - CLOSED by the adapter

**Closed** in `io.vanillabp:process-engine-api-adapter`, package `io.vanillabp.pea.observation`.

**The cockpit needed** to learn that a user task appeared, changed and ended. It has no engine of
its own: it watches the one the workflow application runs on. The API offers one channel,
`TaskSubscriptionApi`, and that channel delivers a task to exactly ONE subscription. So a second
subscription for the same task definition either sees nothing or takes the task away from the
workflow application, which `PeaSubscriptionProbeTest` still holds the in-memory engine to.

**What closed it:** the adapter calls `PeaUserTaskObserver` from its own user-task subscription.
`PeaUserTaskObservation` carries the adapter id, the workflow module, the BPMN process, the task
definition, the workflow aggregate's id, the engine's `TaskInformation` and the payload the
subscription asked for. The identifiers are the PLAIN ones, which the adapter translated back
through name-clash avoidance. Every delivery reaches an observer, including one no `@WorkflowTask`
method of the application claims, and an observer which throws costs neither the task nor the
observers behind it.

This extension implements that interface in `PeaCockpitObserver` and contributes it as a bean on
both platforms; the adapter collects the beans by type. The port this repository carried in the
meantime is gone, and so is the startup message which asked an application to feed it.

## 2. A terminated task carries neither its outcome nor its identifiers - CLOSED for the outcome, open at the API for what "finished" means

**Closed in the adapter**, which now registers the `TaskTerminationHandler` overload carrying the
engine's full `TaskInformation`. So the reason reaches this extension: `delete` is reported as
cancelled and everything else as completed (decision 4 in this repository's `DECISIONS.md`). The
termination names no BPMN process and no workflow aggregate, and that costs nothing. What a
terminated task was is what its delivery said, and this extension remembers that from the delivery
until the end is reported.

**Still open at the Process-Engine-API.** What the reason says is only half an outcome. The
reference C7 adapter names `complete` when a task was finished through `UserTaskCompletionApi` and
`delete` for everything else it notices, so a task somebody finished in a task list arrives as
`delete` like a cancelled one.

**What it costs:** a task finished outside the API is reported as cancelled, which reads as work
somebody stopped.

**What would close it:** the API defining an outcome its engine adapters fill. Finished against
withdrawn, decided by what happened to the task rather than by which API noticed it.

## 3. The meta map of a delivered task has no vocabulary

**The cockpit needs** what a task list shows: the assignee, the candidate users and groups, the due
and follow-up date, the name of the task and of the process.

**The API offers** `TaskInformation.meta`, a `Map<String, String>` whose keys are defined nowhere.
`CommonRestrictions` names the keys a subscription may be RESTRICTED by, and `TaskInformation`
itself defines only `reason` and `retries`. What an engine actually fills is its own business. The
reference C7 adapter fills `assignee`, `candidateUsers`, `candidateGroups`, `dueDate`,
`followUpDate`, `taskName`, `taskDescription`, `formKey`, `creationDate` and `lastUpdatedDate` next
to the `CommonRestrictions` keys (`TaskInformationExtensions.kt`), and `TaskInformation` even
offers `getMetaValueAsOffsetDate` and `getMetaValueAsStringSet` for reading exactly those.

**What it costs:** this extension reads that same set, under the names the VanillaBP adapter writes
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/wiring/PeaTaskMeta.java`), and it
shows one detail less per key an engine leaves out. On an engine which fills none of them, a
cockpit user sees a task with a name from the BPMN and nothing else.

**What would close it:** the same list of keys in `TaskInformation`'s companion, as constants next
to `REASON` and `RETRIES`. Then an engine adapter fills what a task list needs by contract rather
than by imitation.

## 4. The payload of a delivery is narrowed to what the workflow tasks asked for

**The cockpit needs** the process variables a `@UserTaskDetailsProvider` method reads through its
`@TaskParam` parameters.

**The API offers** `SubscribeForTaskCmd.payloadDescription`, which the VanillaBP adapter fills from
what the application's `@WorkflowTask` methods need
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java:306`,
called at `:1223`). The cockpit's own annotations are not part of that derivation, and a second
subscription cannot ask for more (entry 1).

**What it costs:** a `@TaskParam` of a details provider receives `null` unless the same variable is
read by a workflow task of the same module.

**What would close it:** on the adapter's side, letting an observer add to the derived payload
description. Today the way out is `vanillabp.adapters.<id>.fetch-variables: all`, which asks the
engine for everything.

## 5. No process definitions, no versions

**The cockpit needs** the version a workflow runs on, to show a case of an older release correctly
and to serve the right BPMN diagram for it.

**The API offers** no repository API at all. The VanillaBP adapter therefore builds a definition id
of its own
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeployedProcesses.java`,
`definitionId`, `<workflowModuleId>|<bpmnProcessId>`)
and keeps what the current application version deployed, with the deployment key as the only
version-like value. `TaskInformation.meta` may carry a `processDefinitionVersionTag` where the
engine fills it.

**What it costs:** the cockpit shows the version tag where an engine sets one and the deployment
key otherwise. A workflow still running on what an earlier release deployed is shown with what this
release deployed.

**What would close it:** a repository API, which answers process definitions by key with their
version and their resources.

## 6. No workflow lifecycle

**The cockpit needs** to know that a business case started, ended or was terminated. The workflow
list of a cockpit is a list of cases, not of tasks.

**The API offers** nothing about a process instance: no start or end notification, no instance
query, no history. The VanillaBP adapter answers `WorkflowAwareness.ACTIVE` whatever it is asked
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/processservice/PeaProcessService.java:810`),
reports `canLocateWorkflows()` as `false` (`:827`) and serves a workflow history without any
elements (`:383`).

**What it costs:** this extension reports a case CREATED with the first user task delivered for it
(decision 6 in `DECISIONS.md`). A workflow without user tasks never appears in the cockpit at all,
a case appears later than it started, and no case is ever reported as completed or cancelled. The
cockpit's list of open cases keeps them forever.

**What would close it:** a subscription for process-instance events, in the shape the task
subscription already has, or an instance query the adapter can ask after the fact.

## 7. No way to find the workflows or the user tasks of a business case

**The cockpit needs** to answer `BusinessCockpitService.aggregateChanged(aggregate)` and
`getUserTask(aggregate, id)`: which workflows and which user tasks belong to this business case
right now.

**The API offers** neither a task query nor an instance query. What a subscriber knows is what was
delivered to it, and `UserTaskSupport`, the helper the API ships for keeping exactly that, holds it
in memory too.

**What it costs:** this extension answers out of two sources, and neither is a query. The first is
the deliveries the node it runs on has seen (decision 3 in `DECISIONS.md`). The second is
VanillaBP's own delivery log, which answers `TaskDeliveryLog#openTasksOfAggregate` with the open
deliveries of a business case. The log reads a table of the application's own database, so it
answers after a restart and on any node, and decision 9 in `DECISIONS.md` says which of the two
wins where both can answer.

What the log cannot do is what this entry asks for. It holds deliveries, so it names a user task
only where a `@WorkflowTask` method of the application ran for it, and a task nobody wrote a method
for was never recorded. It holds a record until the application's completion of that task reaches
the BPMS, so a task the engine withdrew some other way stays in it. And on this BPMS a record names
neither the BPMN element of the task nor the engine's own id of the workflow, because the
Process-Engine-API adapter fills neither; this extension answers both out of what the adapter
deployed.

So a business case whose tasks were all withdrawn, or served by nobody, is still a case this half
cannot find again. Nothing about it is reported to the cockpit then, and the log says so once per
case. `getUserTask` answers empty for a task neither source knows, and it answers empty for a task
only the delivery log knows, because a report of a running task without its details is no report
(entry 10).

**What would close it:** a query for user tasks by a restriction the API already knows
(`businessKey`, `processInstanceId`), which is the cheapest read a task list needs anyway.

## 8. The task description key means different things in different engines

**The cockpit needs** the task definition to be what the modeller wrote as the form reference. It
is one of the two keys a `@UserTaskDetailsProvider` is matched by, and it is what the cockpit shows
a form for.

**The API offers** `SubscribeForTaskCmd.taskDescriptionKey` with a javadoc which leaves it to the
engine ("may refer to BPMN 2.0 attribute `implementation` or `operation[@implementationRef]` or any
engine-specific attribute"; a task's `id` as a fallback). The VanillaBP adapter subscribes user
tasks by the `zeebe:formDefinition` external reference
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java:1238-1239`,
read out of the BPMN at `:601-605`), while the reference C7 adapter matches a subscription against
`task.taskDefinitionKey`, the BPMN element id, or against the task id.

**What it costs:** on an engine which matches by element id, the adapter's user-task subscriptions
match nothing. Then neither the application's notifications nor the cockpit's reports happen. Where
they do match, the cockpit reads the element id out of the meta key `activityId` and falls back to
the subscription key.

**What would close it:** one defined meaning per task type, or a subscription which may name the
attribute it matches on.

## 9. One Process-Engine-API adapter id, and no isolation of its own

**The cockpit needs** nothing special here. It does show a migration between two engines by the
adapter id holding each workflow, and it addresses a BPMS by that id.

**The API offers** no tenant or namespace concept. So the VanillaBP adapter refuses the
`by-adapter` name-clash avoidance at startup
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java`,
`deployResources`, through `NameClashAvoidanceSupport.validateNativeIsolationSupported`) and
refuses a second adapter id of type `process-engine-api` (same file,
`validateDistinctAdapterInstances`).

**What it costs:** a workflow module runs its Process-Engine-API workflows on one engine, and a
migration from one Process-Engine-API engine to another cannot be shown in the cockpit. A migration
between this BPMS and another one is unaffected. This extension registers one bridge per configured
adapter id anyway, so nothing here has to change when the adapter allows a second.

**What would close it:** tenant support in the API, which is what the adapter's own `GAPS.md` entry
15 asks for.

## 10. What a delivery said lives on one node, and a restart takes it

**The cockpit needs** to say what a user task looks like whenever something asks: while it builds
the report of an event, and later when an application reads the task back or reports that its
business case changed.

**The API offers** nothing to read it from (entries 6 and 7), so this extension answers out of
what the delivery said, in memory.

**What it costs:** the report of a delivered task is safe. It is built in the moment the task
arrives, out of the memory that same delivery was just written to, and it travels inside the
outbox entry (decision 10 in `DECISIONS.md`). A restart before that entry is sent takes nothing
from it, and neither does sending it on another node.

What a restart takes is every later question about that task. Its name, who it is assigned to,
who may claim it, its dates and its variables lived in the memory of the node the engine delivered
to and nowhere else. So a fresh node reports nothing when an application calls `aggregateChanged`
for a task only the delivery log knows, because a report of a running task without its details is
no report, and the log says so. `getUserTask` answers nothing for the same reason. And an end the
engine reports never becomes an entry at all: a termination names the task and nothing else, so
`PeaCockpitObserver#userTaskTerminated` asks the memory what the task was, finds nothing and says
so in a debug line. The cockpit keeps showing that task as open, and entry 11 says why the memory
cannot be rebuilt to answer it.

The same holds for a task delivered to another node, and for one which so many newer tasks have
pushed out of the memory. Sizing the memory
(`vanillabp.cockpit.process-engine-api.remembered-user-tasks`) does not change that. It only
decides how many tasks a node holds at once.

**What would close it:** the single-task read entry 11 asks for. With it, the memory is a cache
rather than the only source, and a fresh node answers those questions the way every other BPMS
half does.

## 11. Nothing lets a fresh node rebuild what it knew about a user task

**The cockpit needs** a node which has just started to be able to say what a user task of a running
business case is: its name, who it is assigned to, who may claim it, its dates and its variables.
Every other BPMS half of the cockpit reads exactly that from its engine, whenever it is asked.

**The API offers** nothing to read a task from. There is no task query, no single-task get and no
history. What a subscriber knows is what was delivered to it, and the API's own helper for keeping
that, `UserTaskSupport`, keeps it in memory as well. An engine repeats a delivery when something
about the task changes, but nobody can ask it to repeat one, so a node cannot fetch what it lost.

VanillaBP fills half of the hole and cannot fill the other half. Its delivery log says which user
tasks of a business case this application was handed and which of them are over, out of the
application's own database, so a fresh node knows the tasks again (decision 9 in `DECISIONS.md`).
The record holds no field of the content, and it must not: the platform would then write the
engine's state into the application's database.

**What it costs:** a fresh node has nothing to show about a task it did not see delivered. A
report an application asks for with `aggregateChanged` is dropped then, so the cockpit misses that
task until the engine delivers it again (entry 10). An end which the engine reports after the
restart is not even written, because a termination on this API names the task and nothing else, and
the delivery log has to be asked with the workflow module, the BPMN process, the workflow aggregate
and the task. That task stays open in
the cockpit until somebody looks at the engine. `getUserTask` answers empty for the same reason, so
an application cannot read the task back either.

**What would close it:** one read of one task by its id, answering what a delivery answers.
`TaskInformation` plus the payload the subscription asked for is the whole of it, and the API
already builds both when it delivers. With that read, this extension asks the engine wherever the
memory is empty, and the memory becomes a cache instead of the only source. A query for the
open user tasks of a business key (entry 7) would close it as well and answer more, including the
tasks no `@WorkflowTask` method of the application claims.

## 12. A details provider cut by version never runs here, and nothing says so

**The cockpit needs** to pick the `@UserTaskDetailsProvider` or `@WorkflowDetailsProvider` method which
serves the version of the deployed process an event came from. An application writes one method per
generation of a model, and VanillaBP chooses between them the way it chooses a `@WorkflowTask` method.

**The API offers** one version, and only sometimes. There is no repository, so there are no process
definitions to count and no version numbers (entry 5). What reaches this half is the
`processDefinitionVersionTag` an engine writes into the meta map of a delivered task, where it keeps one.
So the reference of a user task and of the business case it appears with carries that tag, or nothing.

One way of writing a version survives that. `version = "ride-2026-09"` is compared to the reported tag as
text, so a method written that way runs for the deliveries carrying that tag. Everything else needs the
catalogue this BPMS has none of. A specification made of numbers (`3`, `1-3`, `>2`) has no number to
compare against, and a range between two tags (`v1.0..v2.0`) has to place both ends in the deployment
order, which only a list of the deployed versions answers.

**What it costs:** a method which serves no version does nothing, and does it quietly. A `@WorkflowTask`
method in the same position is loud, because a delivery whose methods all name versions fails and the
message says why; the adapter's own `GAPS.md` writes that down as its entry 19. A details provider is the opposite, and
that is by design: no matching method is a LEGAL answer. The cockpit then sends the details it had
prefilled, so the task appears with the names out of the BPMN and with what the engine said about it, and
the enrichment the application wrote is not there.

Two checks could catch it and neither does. The platform warns about a method naming versions where the
extension reports no version with its calls, and this extension does report one, so that warning stays
silent. The check for a method which serves no deployed version needs the list of deployed versions, which
no adapter of this BPMS can answer.

What is left is the two lines the platform does write, and neither of them lands. At startup it says that a
version tag is known to no BPMS and that the method serves no workflow. That is the wrong way round here: a
tag is the one specification which does run. And a delivery whose tag no method names draws one line about a
BPMS nobody can ask for its versions. A delivery carrying NO version draws nothing at all, and that is the
normal state of this BPMS. An engine which fills no version tag leaves every version-named provider idle
without a word. So an application which cut its providers by version loses that enrichment here, and the
only sign of it is a cockpit showing less than the application wrote.

**What would close it:** what entry 5 asks for, both halves of it. A repository API answering the process
definitions of a key with their versions is the catalogue, and a version in the meta map of a delivery is
the version of the task at hand. With the two together a version specification means here what it means on
every other BPMS.
