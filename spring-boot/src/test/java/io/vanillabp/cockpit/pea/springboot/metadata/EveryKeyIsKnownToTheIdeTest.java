package io.vanillabp.cockpit.pea.springboot.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataRepositoryJsonBuilder;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;

import io.vanillabp.cockpit.extension.config.ConfigurationKeys;
import io.vanillabp.cockpit.extension.springboot.CockpitOverlayProperties;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.cockpit.pea.springboot.PeaCockpitAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Holds the configuration metadata an IDE reads against the keys this half reads. An IDE offers
 * and checks the keys in <code>application.yaml</code> from that metadata. So a key which the
 * code reads but the metadata does not describe is shown as unknown.
 * <p>
 * This half binds no class of its own. Its keys stand below <code>vanillabp.cockpit</code>, and
 * the cockpit's <code>extensions-commons-spring-boot</code> binds them. That jar also describes
 * them, and this one describes nothing, see decision 23 in the repository's DECISIONS.md. So
 * the metadata read here is the one of that jar.
 * <p>
 * The keys this half reads are the list {@link PeaCockpitSettings#KEYS_READ}, which the check for
 * a key below a workflow module walks as well. One test here binds each key of that list the way
 * the application does, so the list cannot name a key nobody reads.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EveryKeyIsKnownToTheIdeTest {

  private static final String METADATA = "META-INF/spring-configuration-metadata.json";

  private static final String SCOPE_LEVELS = "vanillabp.workflow-modules.";

  private static Map<String, ConfigurationMetadataProperty> metadata;

  private static List<String> keysRead;

  @BeforeAll
  public static void readBothSides() throws IOException {

    metadata = metadataNextTo(CockpitOverlayProperties.class);
    keysRead = PeaCockpitSettings.KEYS_READ
        .stream()
        .map(ConfigurationKeys::globalKey)
        .toList();

  }

  @Test
  @DisplayName("every key read is described, with a description, by the jar which binds it")
  public void everyKeyReadIsDescribed() {

    final var missing = new TreeSet<String>();
    keysRead.forEach(key -> {
      final var entry = metadata.get(key);
      if ((entry == null) || isBlank(entry.getDescription()) || isBlank(entry.getType())) {
        missing.add(key);
      }
    });
    assertTrue(
        missing.isEmpty(),
        "the configuration metadata of extensions-commons-spring-boot does not describe these keys: "
            + missing);

  }

  @Test
  @DisplayName("this jar describes no key, so each key is described by exactly one jar")
  public void thisJarDescribesNothing() {

    final var location = PeaCockpitAutoConfiguration.class
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .toString();
    assertTrue(
        filesOf(location).isEmpty(),
        "%s has a %s, and the keys of this half are described by extensions-commons-spring-boot"
            .formatted(location, METADATA));

  }

  @Test
  @DisplayName("every key refused below a workflow module names that level in its description")
  public void everyRefusedLevelIsNamed() {

    final var notNamed = new TreeSet<String>();
    keysRead.forEach(key -> {
      final var entry = metadata.get(key);
      if ((entry == null) || !String
          .valueOf(entry.getDescription())
          .contains(SCOPE_LEVELS)) {
        notNamed.add(key);
      }
    });
    assertTrue(
        notNamed.isEmpty(),
        "these keys end the start below 'vanillabp.workflow-modules', and their description has to say so: "
            + notNamed);

  }

  @Test
  @DisplayName("each key of the list reaches the reading")
  public void everyListedKeyIsWhatTheReadingReads() {

    // the value the default is not, so a key which does not reach the reading shows
    final var configured = PeaCockpitSettings.DEFAULT_REMEMBERED_USER_TASKS + 1;
    final var key = ConfigurationKeys.globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS);
    assertTrue(keysRead.contains(key), "'%s' is read, and the list has to name it".formatted(key));
    // bound onto the class the application binds, so the key is what the application reads
    final var overlay = new Binder(new MapConfigurationPropertySource(Map.of(key, String.valueOf(configured))))
        .bind("vanillabp", CockpitOverlayProperties.class)
        .get();
    assertEquals(
        configured,
        PeaCockpitSettings.rememberedUserTasks(overlay.toSettings()),
        "what '%s' says reaches the reading".formatted(key));

    // the check for a workflow module walks the same list
    final var ofAWorkflowModule = SCOPE_LEVELS
        + "a-module."
        + ConfigurationKeys.COCKPIT_SECTION
        + "."
        + PeaCockpitSettings.REMEMBERED_USER_TASKS;
    assertThrows(
        IllegalStateException.class,
        () -> PeaCockpitSettings.refuseWhatAWorkflowModuleConfigured(List.of(ofAWorkflowModule)),
        "'%s' ends the start".formatted(ofAWorkflowModule));

  }

  @Test
  @DisplayName("the default in the metadata is the value this half starts with")
  public void everyDefaultIsTheRealOne() {

    final var key = ConfigurationKeys.globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS);
    final var entry = metadata.get(key);
    if (entry == null) {
      // a key without an entry is reported by everyKeyReadIsDescribed
      return;
    }
    final var nothingConfigured = new CockpitOverlayProperties().toSettings();
    assertEquals(
        PeaCockpitSettings.rememberedUserTasks(nothingConfigured),
        ApplicationConversionService
            .getSharedInstance()
            .convert(entry.getDefaultValue(), Integer.class),
        "the default of '%s' in the metadata of extensions-commons-spring-boot".formatted(key));

  }

  private static boolean isBlank(
      final Object text) {

    return (text == null) || text
        .toString()
        .isBlank();

  }

  private static List<URL> filesOf(
      final String location) {

    try {
      final var files = EveryKeyIsKnownToTheIdeTest.class
          .getClassLoader()
          .getResources(METADATA);
      final var result = new ArrayList<URL>();
      while (files.hasMoreElements()) {
        final var file = files.nextElement();
        // a directory of classes is named as it is, a jar as 'jar:<location>!/'
        final var name = file.toString();
        if (name.startsWith(location) || name.startsWith("jar:"
            + location
            + "!/")) {
          result.add(file);
        }
      }
      return result;
    } catch (final IOException e) {
      throw new AssertionError("cannot list %s".formatted(METADATA), e);
    }

  }

  /**
   * The metadata of the jar which holds the given class. The class path of the test holds the
   * files of other jars as well. It is read with Spring Boot's own reader of the format, which
   * tools use as well.
   */
  private static Map<String, ConfigurationMetadataProperty> metadataNextTo(
      final Class<?> type) throws IOException {

    final var location = type
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .toString();
    final var files = filesOf(location);
    if (files.isEmpty()) {
      throw new AssertionError("no %s next to %s, which version of extensions-commons-spring-boot is this?"
          .formatted(METADATA, location));
    }
    try (InputStream in = files
        .get(0)
        .openStream()) {
      return new TreeMap<>(ConfigurationMetadataRepositoryJsonBuilder
          .create(StandardCharsets.UTF_8)
          .withJsonResource(in)
          .build()
          .getAllProperties());
    }

  }

}
