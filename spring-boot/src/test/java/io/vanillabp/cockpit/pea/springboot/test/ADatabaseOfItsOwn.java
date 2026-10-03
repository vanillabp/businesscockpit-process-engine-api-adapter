package io.vanillabp.cockpit.pea.springboot.test;

import java.util.List;
import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Gives every test class which boots a context a database of its own, named after the class.
 * <p>
 * VanillaBP writes the user tasks it delivered into the table <code>VANILLABP_TASK_DELIVERY</code>
 * of the application's database. That table is not a JPA entity, so nothing drops it when the
 * next test class boots. The table of the workflow aggregates is created anew with every context,
 * so the ids start at 1 again. In one shared database, an open record another class left behind
 * for the same aggregate id names a workflow of a case the next class thinks has no open user
 * task. That made the tests red depending on the order they ran in.
 * <p>
 * Spring finds this factory in the test's <code>META-INF/spring.factories</code>, so a new test
 * class gets its own database without asking for it. The test's <code>application.yaml</code>
 * builds the URL from {@link #DATABASE_NAME_KEY} and has no default for it, so a context booted
 * some other way and without a URL of its own does not start, instead of sharing a database.
 */
public class ADatabaseOfItsOwn implements ContextCustomizerFactory {

  /** The key <code>application.yaml</code> reads the name of the database from. */
  public static final String DATABASE_NAME_KEY = "pea-cockpit.test.database-name";

  /**
   * @param testClass The test class which boots a context
   * @return The URL of the database this factory gives that class
   */
  public static String urlOf(
      final Class<?> testClass) {

    return "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(testClass.getSimpleName());

  }

  @Override
  public ContextCustomizer createContextCustomizer(
      final Class<?> testClass,
      final List<ContextConfigurationAttributes> configAttributes) {

    return new NamedDatabase(testClass.getSimpleName());

  }

  /**
   * Puts the name of the database into the environment of the context. It is added last, so a
   * test which sets <code>spring.datasource.url</code> itself still wins.
   *
   * @param databaseName The name of the in-memory database
   */
  private record NamedDatabase(String databaseName) implements ContextCustomizer {

    @Override
    public void customizeContext(
        final ConfigurableApplicationContext context,
        final MergedContextConfiguration mergedConfig) {

      context
          .getEnvironment()
          .getPropertySources()
          .addLast(
              new MapPropertySource(
                  ADatabaseOfItsOwn.class.getName(), Map.of(DATABASE_NAME_KEY, databaseName)));

    }

  }

}
