![VanillaBP](./readme/vanillabp-headline.png)

# VanillaBP Business Cockpit adapter for the Process-Engine-API

[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This repository holds the [VanillaBP Business Cockpit](https://github.com/vanillabp/business-cockpit)
integration for the BPMS-agnostic
[Process-Engine-API](https://github.com/bpm-crafters/process-engine-api), built as an extension of
[VanillaBP](https://www.vanillabp.io) Version 2. The cockpit shows user tasks and business cases to
business staff, and to do that it has to learn what happens inside the workflow engine. This
adapter is the half that runs in the workflow application: it observes the user-task and workflow
lifecycle through the Process-Engine-API, asks the application for the business details of what it
saw, and hands the result to the cockpit server.

## Status

The extension is here, on both platforms, and it works as far as the Process-Engine-API lets it.
It registers a workflow module at the cockpit server, it turns a delivered user task into the
report the cockpit shows, it enriches that report with what the application's
`@UserTaskDetailsProvider` returns, and it answers what `BusinessCockpitService` reads back.

One thing is missing, and it is not in this repository: nothing hands it a delivered user task
yet. The Process-Engine-API gives a task to exactly one subscription, so this extension must not
subscribe next to the VanillaBP Process-Engine-API adapter - it would take the task away from the
workflow application - and that adapter does not pass its deliveries on. Entry 1 of
[`GAPS.md`](./GAPS.md) describes the seam it would need, this repository is written against
exactly that interface, and the application says so at startup rather than leaving somebody with
an empty cockpit and no explanation.

This adapter has no Version 1 predecessor. The Business Cockpit supported Camunda 7 and Camunda 8
in Version 1, and the Process-Engine-API integration is new with Version 2, so there is nothing to
port and no configuration key to translate.

## What is here

The module layout every VanillaBP adapter repository uses:

- `core` - everything which needs neither Spring nor Quarkus. The wiring service which joins
  VanillaBP's deployment pipeline and remembers what was deployed, the observer which turns a
  delivered user task into a cockpit event, the memory of what a delivery said, and the bridge
  which answers what the cockpit reads about a task or a business case.
- `spring-boot` and `quarkus/runtime` plus `quarkus/deployment` - the glue which registers those
  beans with each platform, and one bridge per configured `process-engine-api` adapter id.
- `test-coverage-report` - the per-platform coverage measurement and the gate which breaks the
  build below it.

The artifacts keep the repository name as their prefix, so
`businesscockpit-process-engine-api-adapter` is the core and
`businesscockpit-process-engine-api-adapter-spring-boot` is what a Spring Boot application depends
on. The prefix is what keeps a jar of this repository apart from the jar of the VanillaBP
Process-Engine-API adapter it plugs into.

[`DECISIONS.md`](./DECISIONS.md) holds the decisions the code points at, and
[`GAPS.md`](./GAPS.md) the ten questions the cockpit asks a workflow engine which this one cannot
answer yet. The wiki says the same in the words of somebody using the cockpit.

## How the probe shaped the design

The first question was whether this extension can watch user tasks at all, and it was answered
before anything was designed. Two ways were open: subscribing for the same task definitions the
VanillaBP adapter subscribes for, or a seam in that adapter.

The first one does not work, and `PeaSubscriptionProbeTest` in `core` is that answer as a test: a
task the engine delivers reaches one subscription, so a second subscriber sees nothing - or takes
the task, depending on which of the two was registered first. The Process-Engine-API's own
reference adapter for an embedded Camunda 7 picks the first matching subscription and records it
as the active one for that task, which makes this a property of the API rather than of the
in-memory engine the test uses.

So the design is the second way, with the seam described rather than written: the extension owns a
port, `PeaUserTaskObserver`, produced as a bean on both platforms. Everything behind it - the
translation of an engine's identifiers, the memory of a delivery, the event kinds, the reads the
cockpit does - is implemented and tested through that port, and the adapter's future seam replaces
it without touching anything else. What that costs today is said in `GAPS.md` and at every
startup.

## Building

```bash
mvn install
```

Snapshots are published to GitHub Packages by the pipeline described below, and releases go to
Maven Central under the groupId `io.vanillabp.businesscockpit`, like the rest of the Business
Cockpit.

## What CI runs

`build.yaml` builds and tests a pull request. `deploy-to-github-packages.yaml` publishes the
snapshot when a branch is pushed. Both run under one concurrency group, queued and never
cancelled, because the snapshot artifacts share their coordinates: two runs publishing at the same
time would overwrite each other, and whoever finished last would decide what the other repositories
compile against. `release.yaml` is started by hand and publishes to Maven Central from a release
branch.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by
[Phactum](https://www.phactum.at) with the intention of giving back to the community as it has
benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
