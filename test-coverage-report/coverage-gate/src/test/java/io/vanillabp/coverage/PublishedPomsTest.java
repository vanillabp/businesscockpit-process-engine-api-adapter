package io.vanillabp.coverage;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Tests that a tool which only compiles our source stays out of the classpath of the
 * applications which use our artifacts.
 * <p>
 * Lombok and the annotation processors of Quarkus and Spring Boot are used while javac runs
 * and have nothing left to do once the class file exists. Somebody added one dependency to see
 * their user tasks in the cockpit. Every jar they did not ask for is one more thing to ship and
 * to answer a CVE report about. The scope for such a tool is {@code provided}: it puts the jar
 * on our own compile path and hands it to nobody. The section "What a POM hands an
 * application" of this repository's AGENTS.md says the same in words, and this test turns it
 * into a gate.
 * <p>
 * The check itself is {@link PublishedPoms} in the platform's module 'test-utils'. Every
 * repository of VanillaBP can make this mistake, and they all make it in the same way. So the
 * rule lives in one place and each repository calls it. A copy per repository drifts apart, and
 * this check is only worth something if it still runs in two years. This test calls it with the
 * file this repository publishes and the tools of this build.
 * <p>
 * This repository publishes its POMs as they are, so an application reads the same file a
 * reviewer reads, and the check reads it too. It lives in the module which already gates this
 * repository as a whole, because it asks about the repository and not about one module.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools of this build, each as {@code groupId:artifactId}. When this repository starts to
   * use a new tool, add it to this list, because nothing else knows that it is a tool.
   */
  private static final Set<String> TOOLS_OF_THIS_BUILD = Set
      .of(
          "org.projectlombok:lombok",
          "io.quarkus:quarkus-extension-processor");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoToolOfTheBuild(TOOLS_OF_THIS_BUILD);

  }

}
