# What the Business Cockpit needs and the Process-Engine-API does not offer

This adapter was built directly against the bpm-crafters
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api). There was no Version 1
of it, so nothing here is a regression: every entry below is a question the cockpit asks a
workflow engine and this API has no answer for yet, written down as the basis of a conversation
with the Process-Engine-API team.

Each entry says what the cockpit needs, what the API offers today, where that can be read, what
it costs somebody looking at the cockpit, and what would close it. A number is handed out once and
never reused: an entry which is closed keeps its number and says so, because a conversation about
"gap 7" outlives the file. The first two need no change to the Process-Engine-API at all: they are
work in
[vanillabp/process-engine-api-adapter](https://github.com/vanillabp/process-engine-api-adapter),
and their headings say so.

The file which says the same thing from the adapter's side is that repository's own `GAPS.md`;
this one adds what a cockpit needs on top of what a workflow application needs.

## 1. Nothing hands a delivered user task to a second observer (VanillaBP adapter work)

**The cockpit needs** to learn that a user task appeared, changed and ended. It has no engine of
its own: it watches the one the workflow application runs on.

**The API offers** one channel, `TaskSubscriptionApi`, and it delivers a task to exactly ONE
subscription. The reference engine adapter for an embedded Camunda 7 picks
`subscriptions.firstOrNull { it.matches(task) }` and records it with
`activateSubscriptionForTask`
(`dev.bpm-crafters.process-engine-adapters:process-engine-adapter-camunda-platform-c7-embedded-core`,
`EmbeddedPullUserTaskDelivery.refresh:59` and `:87`), and the in-memory engine of the VanillaBP
adapter does the same
(`process-engine-api-adapter/mock/src/main/java/io/vanillabp/pea/mock/InMemoryProcessEngine.java:462-472`).
A second subscription for the same task definition therefore either sees nothing or takes the
task away from the workflow application, decided by the order the two were registered in.
`businesscockpit-process-engine-api-adapter/core/src/test/java/io/vanillabp/cockpit/pea/test/PeaSubscriptionProbeTest.java`
holds the in-memory engine to it.

**What it costs:** without a seam in the VanillaBP adapter, this extension registers workflow
modules at the cockpit server and reports nothing else. Every user task is invisible.

**What would close it:** an observer the adapter hands its deliveries to, applied per adapter id
the way the Camunda 7 adapter applies its `Camunda7EngineCustomizer`. Concretely, in
`io.vanillabp:process-engine-api-adapter`:

```java
package io.vanillabp.pea.observation;

public interface PeaUserTaskObserver {
    void userTaskDelivered(PeaUserTaskObservation observation);
    void userTaskTerminated(PeaUserTaskObservation observation);
}

public record PeaUserTaskObservation(
        String adapterId, String workflowModuleId, String bpmnProcessId, String taskDefinition,
        String workflowAggregateId, TaskInformation taskInformation, Map<String, Object> payload) { }
```

`PeaDeploymentService` collects the observers as a hook bean list and hands them to the handlers
it builds at
`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java:807-816`.
`PeaUserTaskHandler.accept`
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/wiring/PeaUserTaskHandler.java:88`)
calls `userTaskDelivered` for every delivery - before its own routing check at `:96-105`, because
the cockpit shows a user task whether or not the application declared a `@WorkflowTask` method for
it - and the termination handler registered at
`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java:821`
calls `userTaskTerminated`.

The workflow module and the BPMN process are resolved before that check, but the aggregate id is
not: the handler asks for the name of the aggregate's id variable and reads it out of the payload
at `PeaUserTaskHandler.java:106-108`, after the check. So the seam means moving that lookup in
front of the observer call - it needs nothing the check produces, only the workflow module and the
BPMN process. Where it answers nothing, because the application declares no workflow aggregate for
that process or the subscription did not ask for the variable, the observation carries no aggregate
id, and this extension passes the task over with a DEBUG line naming it: a business case the
cockpit cannot address is a report it cannot place.

An observer which throws must not break the task: the adapter's user-task handler already treats
a failing notification that way, and an observer is one more of them.

This extension is written against exactly that interface, under
`businesscockpit-process-engine-api-adapter/core/src/main/java/io/vanillabp/cockpit/pea/PeaUserTaskObserver.java`,
so the adapter's seam replaces the port and nothing else changes. Once it exists, that port and
the bean of it are withdrawn here, together with the wiki's instruction to call it from an
application: the adapter's seam sees every delivery, the port only what an application noticed
itself.

## 2. A terminated task carries neither its outcome nor its identifiers (adapter first, then the API)

**The cockpit needs** to know whether a task which is gone was finished or withdrawn, and which
business case it belonged to.

**The API offers** `TaskTerminationHandler`, which has carried a full `TaskInformation` since
version 1.5. The VanillaBP adapter registers the older `Consumer<String>` overload instead
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java:821`
and `:869`), so even the meta map is discarded before the adapter sees it. What the reason says is only half an outcome: the reference C7
adapter names `complete` when a task was finished through `UserTaskCompletionApi`
(`C7UserTaskCompletionApiImpl.completeTask`) and `delete` for everything else it notices, so a
task somebody finished in a task list arrives as `delete` like a cancelled one
(`EmbeddedPullUserTaskDelivery.refresh`, the deactivation branch).

**What it costs:** no reason reaches this extension at all today, so every user task which ends is
reported as completed - see decision 4 in this repository's `DECISIONS.md`. With the overload
registered, `delete` becomes cancelled and a task finished outside the API is then the case which
is reported wrongly. And the termination names no aggregate, so a task the reporting node no
longer holds cannot be reported at all.

**What would close it:** the adapter registering the `TaskTerminationHandler` overload, and the
API defining an outcome its engine adapters fill - finished against withdrawn, decided by what
happened to the task rather than by which API noticed it.

## 3. The meta map of a delivered task has no vocabulary

**The cockpit needs** what a task list shows: the assignee, the candidate users and groups, the
due and follow-up date, the name of the task and of the process.

**The API offers** `TaskInformation.meta`, a `Map<String, String>` whose keys are defined nowhere.
`CommonRestrictions` names the keys a subscription may be RESTRICTED by, and `TaskInformation`
itself defines only `reason` and `retries`. What an engine actually fills is its own business:
the reference C7 adapter fills `assignee`, `candidateUsers`, `candidateGroups`, `dueDate`,
`followUpDate`, `taskName`, `taskDescription`, `formKey`, `creationDate` and `lastUpdatedDate`
next to the `CommonRestrictions` keys (`TaskInformationExtensions.kt`), and `TaskInformation`
even offers `getMetaValueAsOffsetDate` and `getMetaValueAsStringSet` for reading exactly those.

**What it costs:** this extension reads that same set
(`businesscockpit-process-engine-api-adapter/core/src/main/java/io/vanillabp/cockpit/pea/PeaTaskMeta.java`)
and shows one detail less
per key an engine leaves out. On an engine which fills none of them, a cockpit user sees a task
with a name from the BPMN and nothing else.

**What would close it:** the same list of keys in `TaskInformation`'s companion, as constants
next to `REASON` and `RETRIES`, so that an engine adapter fills what a task list needs by
contract rather than by imitation.

## 4. The payload of a delivery is narrowed to what the workflow tasks asked for

**The cockpit needs** the process variables a `@UserTaskDetailsProvider` method reads through its
`@TaskParam` parameters.

**The API offers** `SubscribeForTaskCmd.payloadDescription`, which the VanillaBP adapter fills
from what the application's `@WorkflowTask` methods need
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java:191`,
called at `:806`). The cockpit's own annotations are not part of that derivation, and a second
subscription cannot ask for more (entry 1).

**What it costs:** a `@TaskParam` of a details provider receives `null` unless the same variable
is read by a workflow task of the same module.

**What would close it:** on the adapter's side, letting an observer contribute to the derived
payload description; today the way out is `vanillabp.adapters.<id>.fetch-variables: all`, which
asks the engine for everything.

## 5. No process definitions, no versions

**The cockpit needs** the version a workflow runs on, to show a case of an older release
correctly and to serve the right BPMN diagram for it.

**The API offers** no repository API at all. The VanillaBP adapter therefore composes a
definition id of its own
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeployedProcesses.java`,
`definitionId`, `<workflowModuleId>|<bpmnProcessId>`)
and keeps what the current application version deployed, with the deployment key as the only
version-like value. `TaskInformation.meta` may carry a `processDefinitionVersionTag` where the
engine fills it.

**What it costs:** the cockpit shows the version tag where an engine sets one and the deployment
key otherwise, and a workflow still running on what an earlier release deployed is shown with what
this release deployed.

**What would close it:** a repository API - process definitions by key with their version, and
their resources.

## 6. No workflow lifecycle

**The cockpit needs** to know that a business case started, ended or was terminated. The workflow
list of a cockpit is a list of cases, not of tasks.

**The API offers** nothing about a process instance: no start or end notification, no instance
query, no history. The VanillaBP adapter answers `WorkflowAwareness.ACTIVE` unconditionally
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/processservice/PeaProcessService.java:726`),
reports `canLocateWorkflows()` as `false` (`:743`) and serves a workflow history without any
elements (`:337`).

**What it costs:** this extension reports a case CREATED with the first user task delivered for it
(decision 6 in `DECISIONS.md`). A workflow without user tasks never appears in the cockpit at all,
a case appears later than it started, and no case is ever reported as completed or cancelled - the
cockpit's list of open cases keeps them forever.

**What would close it:** a subscription for process-instance events, in the shape the task
subscription already has, or an instance query the adapter can ask after the fact.

## 7. No way to find the workflows or the user tasks of a business case

**The cockpit needs** to answer `BusinessCockpitService.aggregateChanged(aggregate)` and
`getUserTask(aggregate, id)`: which workflows and which user tasks belong to this business case
right now.

**The API offers** neither a task query nor an instance query. What a subscriber knows is what was
delivered to it, and `UserTaskSupport`, the helper the API ships for keeping exactly that, holds
it in memory too.

**What it costs:** this extension answers from the deliveries the node it runs on has seen
(decision 3 in `DECISIONS.md`). A change reported for a business case whose tasks this node never
saw - after a restart, or on the node which did not get the delivery - is not reported to the
cockpit, and the log says so once per case. `getUserTask` answers empty in the same situation.

**What would close it:** a query for user tasks by a restriction the API already knows
(`businessKey`, `processInstanceId`), which is the cheapest read a task list needs anyway.

## 8. The task description key means different things in different engines

**The cockpit needs** the task definition to be what the modeller wrote as the form reference: it
is one of the two keys a `@UserTaskDetailsProvider` is matched by, and it is what the cockpit
shows a form for.

**The API offers** `SubscribeForTaskCmd.taskDescriptionKey` with a javadoc which leaves it to the
engine ("may refer to BPMN 2.0 attribute `implementation` or `operation[@implementationRef]` or
any engine-specific attribute"; a task's `id` as a fallback). The VanillaBP adapter subscribes
user tasks by the `zeebe:formDefinition` external reference
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java:819-821`,
read out of the BPMN at `:433-434`), while the reference C7 adapter matches a subscription against
`task.taskDefinitionKey` - the BPMN element id - or the task id.

**What it costs:** on an engine which matches by element id, the adapter's user-task subscriptions
match nothing, so neither the application's notifications nor the cockpit's reports happen. Where
they do match, the cockpit reads the element id out of the meta key `activityId` and falls back to
the subscription key.

**What would close it:** one defined meaning per task type, or a subscription which may name the
attribute it matches on.

## 9. One Process-Engine-API adapter id, and no isolation of its own

**The cockpit needs** nothing special here, but it shows a migration between two engines by the
adapter id holding each workflow, and it addresses a BPMS by that id.

**The API offers** no tenant or namespace concept, so the VanillaBP adapter rejects the
`by-adapter` name-clash avoidance at startup
(`process-engine-api-adapter/core/src/main/java/io/vanillabp/pea/deployment/PeaDeploymentService.java`,
`deployResources`, through `NameClashAvoidanceSupport.validateNativeIsolationSupported`) and
refuses a second adapter id of type `process-engine-api` (same file,
`validateDistinctAdapterInstances`).

**What it costs:** a workflow module runs its Process-Engine-API workflows on one engine, and a
migration from one Process-Engine-API engine to another cannot be shown in the cockpit. A
migration between this BPMS and another one is unaffected. This extension registers one bridge per
configured adapter id anyway, so nothing here has to change when the adapter allows a second.

**What would close it:** tenant support in the API, which is what the adapter's own `GAPS.md`
entry 15 asks for.

## 10. A restart between a delivery and its dispatch loses the details

**The cockpit needs** to read the current state of a task while the report is dispatched, which is
after the transaction the event was observed in committed.

**The API offers** nothing to read it from (entries 6 and 7), so this extension answers the
dispatch from what the delivery said, in memory.

**What it costs:** a node which restarts between the delivery and the dispatch of the entry
reports the user task without its details - the report is dropped and the log says so - and the
cockpit then misses that task until the engine delivers it again. The same holds for a report
whose entry is dispatched on another node, and for a task which so many newer ones have pushed
out of the memory that nothing is left of it. Sizing the memory
(`vanillabp.cockpit.process-engine-api.remembered-user-tasks`) does not change
that, it only decides how many tasks a node holds at once.

**What would close it:** the single-task read of entry 7. With it, this extension answers a
dispatch the way every other BPMS half does and the memory is a cache rather than the only source.
