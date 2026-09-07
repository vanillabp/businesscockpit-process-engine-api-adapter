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

There is no implementation here yet. The repository was created ahead of the work so that the
build and the pipelines are settled before the first line of code is written, and
what it contains today is that skeleton and nothing else.

This adapter has no Version 1 predecessor. The Business Cockpit supported Camunda 7 and Camunda 8
in Version 1, and the Process-Engine-API integration is new with Version 2, so there is nothing to
port and nothing here is carried over. It also has no story of its own yet: the extension work
currently planned covers Camunda 7 and Camunda 8, and this repository is prepared so that the
Process-Engine-API can follow the same shape when it is scheduled.

It builds on the extension SPI of `adapter-platform-integration`, on the VanillaBP
Process-Engine-API adapter, and on the cockpit's `extensions-commons` module. The last of those
does not exist yet, so no module here could hold a class that compiles.

## What is here today

The parent POM, which builds green and publishes itself as a snapshot, the three GitHub Actions
workflows, the formatting rules every VanillaBP repository shares, and the license
and notice files. The POM already manages the versions of everything the extension will depend on,
so adding the first module is adding a module rather than assembling a build.

Deliberately absent, and not as an empty placeholder:

- The `core`, `spring-boot`, `quarkus/runtime` and `quarkus/deployment` modules. Every class each of
  them would hold needs the cockpit's `extensions-commons` artifact, so an empty module would only
  publish an empty jar under coordinates somebody might resolve.
- The `test-coverage-report` module with its coverage gate. It measures modules, and there are
  none.
- A `DECISIONS.md` with decisions in it. The log exists and explains its own rules, and it stays
  empty until code points at an entry.

## What arrives with the extension

The module layout is the one every VanillaBP adapter repository uses: `core` for everything that
needs neither Spring nor Quarkus, `spring-boot` and `quarkus/runtime` plus `quarkus/deployment` for
the glue that registers the extension with each platform, and `test-coverage-report` for the
per-platform coverage measurement. The artifacts keep the repository name as their prefix, so
`businesscockpit-process-engine-api-adapter` is the core and
`businesscockpit-process-engine-api-adapter-spring-boot` is what a Spring Boot application depends
on. The prefix is what keeps a jar of this repository apart from the jar of the VanillaBP
Process-Engine-API adapter it plugs into.

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
