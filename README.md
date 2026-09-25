![VanillaBP](./readme/vanillabp-headline.png)

# VanillaBP Business Cockpit adapter for the Process-Engine-API

[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

Spring Boot [![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fbusinesscockpit-process-engine-api-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/businesscockpit-process-engine-api-adapter/spring-boot-report)<br>
Quarkus [![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fbusinesscockpit-process-engine-api-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/businesscockpit-process-engine-api-adapter/quarkus-report)

This repository holds the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit)
integration for the BPMS-agnostic
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api). It is built as an
extension of [VanillaBP](https://www.vanillabp.io) Version 2. The cockpit shows user tasks and
business cases to business staff, and for that it has to learn what happens inside the workflow
engine. This adapter is the half which runs in the workflow application. It watches the user tasks
and the workflows through the Process-Engine-API, asks the application what they are about, and
hands the answer to the cockpit server.

## Status

The extension is here, on both platforms, and it works as far as the Process-Engine-API lets it.
It registers a workflow module at the cockpit server. It turns a delivered user task into the
report the cockpit shows, and it adds what the application's `@UserTaskDetailsProvider` returns. It
also answers what `BusinessCockpitService` reads back.

The VanillaBP Process-Engine-API adapter is what hands it a delivered user task. That API gives a
task to exactly one subscription, so this extension must not subscribe next to the adapter: it
would take the task away from the workflow application. The adapter therefore calls
`io.vanillabp.pea.observation.PeaUserTaskObserver` from its own subscription. This repository
contributes a bean of that type on both platforms, and the adapter finds it by its type.

This adapter has no Version 1 predecessor. The Business Cockpit supported Camunda 7 and Camunda 8
in Version 1, and the Process-Engine-API integration is new with Version 2. So there is nothing to
port and no configuration key to translate.

## What is here

The module layout every VanillaBP adapter repository uses:

- `core` - everything which needs neither Spring nor Quarkus. That is the observer which turns a
  delivered user task into a cockpit event, the memory of what a delivery said, the reader of
  VanillaBP's own delivery log, and the bridge which answers what the cockpit reads about a task or
  a business case. What was deployed is read from the Process-Engine-API adapter's own record of
  it, so nothing here takes a place in VanillaBP's deployment pipeline.
- `spring-boot` and `quarkus/runtime` plus `quarkus/deployment` - the glue which registers those
  beans with each platform, and one bridge per configured `process-engine-api` adapter id.
- `test-coverage-report` - the coverage measurement per platform and the gate which breaks the
  build below it.

The artifacts keep the repository name as their prefix, so
`businesscockpit-process-engine-api-adapter` is the core and
`businesscockpit-process-engine-api-adapter-spring-boot` is what a Spring Boot application depends
on. The prefix is what keeps a jar of this repository apart from the jar of the VanillaBP
Process-Engine-API adapter it plugs into.

[`DECISIONS.md`](./DECISIONS.md) holds the decisions the code points at.
[`GAPS.md`](./GAPS.md) holds the questions the cockpit asks a workflow engine which this one cannot
answer yet. The wiki says the same in the words of somebody using the cockpit.

## How the probe shaped the design

The first question was whether this extension can watch user tasks at all, and it was answered
before anything was designed. Two ways were open: subscribing for the same task definitions the
VanillaBP adapter subscribes for, or a seam in that adapter.

The first one does not work, and `PeaSubscriptionProbeTest` in `core` is that answer written as a
test. A task the engine delivers reaches one subscription, so a second subscriber sees nothing, or
takes the task, depending on which of the two was registered first. The Process-Engine-API's own
reference adapter for an embedded Camunda 7 picks the first matching subscription and records it as
the active one for that task. So this is a property of the API rather than of the in-memory engine
the test uses.

The design is therefore the second way, and the seam exists:
`io.vanillabp.pea.observation.PeaUserTaskObserver` in the VanillaBP Process-Engine-API adapter,
called from the subscription the adapter already opens. Everything behind it is implemented and
tested through `PeaCockpitObserver`, which implements that interface: the memory of a delivery, the
kinds of event, and the reads the cockpit does.

## Two sources, and what each of them answers

This BPMS cannot be asked anything about a user task, so the extension answers out of what it was
told. The memory of this node holds what the engine said about a delivery, which is what the
cockpit shows. VanillaBP's delivery log holds that a delivery happened and how it ended, in the
application's own database, which is what survives a restart and what every node reads. The memory
answers first because it carries more, and the log adds the tasks the memory never saw or has
forgotten. Decision 9 in [`DECISIONS.md`](./DECISIONS.md) writes the border down, and entries 10
and 11 of [`GAPS.md`](./GAPS.md) say what falls between the two.

The report of a delivered task is built while that delivery is handled, out of the memory it was
just written to, and it travels inside the outbox entry. So a restart costs no report which was
already written. What it costs is every later question about that task, and decision 10 says what
that leaves the memory for. A details provider which throws while a report is built fails the
delivery. The engine then offers the task again, so the report is not simply lost. Decision 11 says
that.

The end of a user task is two ends. A completion is a task the engine finished because somebody
asked it to, and a cancelation is the engine taking the task away, say through a boundary event. A
broken provider costs them different things: a completion comes back with the outbox entry which
carries it, while a cancelation is reported once and the report is gone if it fails. Decision 12
holds what was measured on both.

While the seam was missing, this repository carried a port of the same shape and asked an
application to call it. Both are gone. The adapter is told about every delivery, while an
application which called a port of the cockpit only ever passed on what it noticed itself. The
identifiers arrive plain, too, because the adapter translates what an engine reports back through
name-clash avoidance before it builds an observation.

## Building

```bash
mvn install
```

Snapshots go to GitHub Packages through the pipeline described below. Releases go to Maven Central
under the groupId `io.vanillabp.businesscockpit`, like the rest of the Business Cockpit.

## Test coverage

`mvn install` builds one aggregated JaCoCo report per platform. The Spring Boot report sums up
`core` and `spring-boot`, and it lands in `test-coverage-report/spring-boot/report`. The Quarkus one
sums up `core` together with `quarkus/runtime` and `quarkus/deployment`, and it lands in
`test-coverage-report/quarkus/report`. The badges at the top of this page link to the copies CI
publishes.

Coverage is measured per platform because `core` is platform-neutral. A line of the core counts on
the platform whose tests ran it, so a core line Quarkus never reaches is a feature Quarkus never
runs. Both platforms are held to the same number, and each one has to earn it with its own tests.

`test-coverage-report/coverage-gate` is the last module of the build. It reads both reports and
fails whenever a platform is below its threshold in the root POM
(`coverage.threshold.spring-boot` and `coverage.threshold.quarkus`, in percent of covered
instructions, which is the number the badges show). Both hold 85, the number every VanillaBP
repository gates on, and that number is not the target. The target is `coverage.rule`, which holds
90, so a report between the two passes the build and still names a gap somebody owes a test for.
The gate is therefore never edited to make a build pass. It also compares every module writing a
`jacoco.exec` with the two aggregates, so a module added to the build but to no report cannot stay
unnoticed.

`CoverageGateTest` is where both measurements happen, and it prints what it measured on every run,
green ones included. That makes it the one test class of this repository which says something while
it passes. The angle brackets stand for the numbers of the run:

```
coverage gate | Spring Boot: <percent> % instructions (<missed> of <total> missed) | at the rule of 90 %
coverage gate | Quarkus: <percent> % instructions (<missed> of <total> missed) | <gap> points below the rule of 90 %, build breaks below 85 %
```

`mvn package` does not check the threshold. JaCoCo writes the aggregated reports in the `verify`
phase, and a build which stops at `package` never gets that far. The gate then prints a line per
platform saying that the coverage was not checked and naming the command which does check it, and
those two tests are reported as skipped, instead of failing over a file the run could not have
written.

`TestClassConventionsTest` next to the gate keeps every test class on the output suppression. It
also reads the main sources of this repository, for a guiding message whose sentence fell apart: a
run of spaces between two words, or two words a line continuation glued into one.

## What CI runs

`build.yaml` builds and tests a pull request. `deploy-to-github-packages.yaml` publishes the
snapshot, and only for a push to `main`: the snapshot coordinates are shared, so what the other
repositories compile against has to be what `main` holds rather than whichever branch was pushed
last. The build runs in a group per pull request and the publish in a group of its own. That way a
publish never waits for a build, and a publish which is already running is never cancelled, because
two runs which publish at the same time would overwrite each other. `release.yaml` is started by
hand and publishes to Maven Central from a release branch. It deploys no snapshot, so it can run
beside a publish.

`deploy-to-github-packages.yaml` also publishes the two coverage reports to GitHub Pages, which is
what the badges at the top of this page link to. `deploy` runs every phase the pull-request build
runs, so the number covers the whole test suite.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by
[Phactum](https://www.phactum.at) with the intention of giving back to the community as it has
benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
