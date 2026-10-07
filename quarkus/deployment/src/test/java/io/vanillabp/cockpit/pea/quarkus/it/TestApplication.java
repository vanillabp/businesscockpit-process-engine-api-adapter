package io.vanillabp.cockpit.pea.quarkus.it;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;

/**
 * The application every test class of this module boots, with a database of its own.
 * <p>
 * VanillaBP writes the user tasks it delivered into the table <code>VANILLABP_TASK_DELIVERY</code>
 * of the application's database, and nothing drops it when the next test class boots. The
 * workflow aggregates of this application live in memory, so their ids start at 1 again with every
 * boot. In one shared database, an open record another class left behind for the same aggregate
 * id names a workflow of a case the next class thinks has no open user task. That made the tests
 * red depending on the order they ran in.
 * <p>
 * So the database is named after the test class. <code>business-cockpit.yaml</code> builds the URL
 * from {@link #DATABASE_NAME_KEY} and has no default for it, so a test class which boots the
 * application some other way does not start, instead of sharing a database.
 * <p>
 * The Spring Boot tests of this repository name their databases the same way. Their class
 * <code>ADatabaseOfItsOwn</code> says why the Camunda 8 half of the cockpit does it differently,
 * and why both ways are fine.
 */
public final class TestApplication {

  /** The key <code>business-cockpit.yaml</code> reads the name of the database from. */
  public static final String DATABASE_NAME_KEY = "pea-cockpit.test.database-name";

  private TestApplication() {
  }

  /**
   * @param testClass The test class which boots the application
   * @return The application, talking to the cockpit server of the test and to a database named
   *         after the test class. A test adds what is special about it.
   */
  public static QuarkusExtensionTest forTestClass(
      final Class<?> testClass) {

    return new QuarkusExtensionTest()
        .withApplicationRoot(
            jar -> jar
                .addAsResource("business-cockpit.yaml", "application.yaml")
                .addAsResource("pea-cockpit/processes/taxi-ride.bpmn")
                .addAsResource(
                    "workflow-module-descriptor/workflow-module", "META-INF/workflow-module")
                .addClass(TestAggregate.class)
                .addClass(TestAggregatePersistence.class)
                .addClass(TestWorkflowService.class)
                // the test class is loaded inside the application, and its static field calls
                // this class
                .addClass(TestApplication.class))
        .overrideConfigKey(DATABASE_NAME_KEY, testClass.getSimpleName())
        .overrideRuntimeConfigKey("vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

  }

}
