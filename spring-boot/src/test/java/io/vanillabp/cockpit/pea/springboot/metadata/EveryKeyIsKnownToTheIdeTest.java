package io.vanillabp.cockpit.pea.springboot.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import org.springframework.boot.configurationmetadata.Deprecation;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.ApplicationConversionService;

import io.vanillabp.cockpit.extension.config.ConfigurationKeys;
import io.vanillabp.cockpit.extension.springboot.CockpitOverlayProperties;
import io.vanillabp.cockpit.pea.PeaCockpitSettings;
import io.vanillabp.cockpit.pea.springboot.PeaCockpitAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Holds the configuration metadata of this module against the keys this half reads. An IDE
 * offers and checks the keys in <code>application.yaml</code> from that metadata. So a key which
 * the code reads but the metadata does not describe is shown as unknown, and a key described but
 * not read is offered for nothing.
 * <p>
 * This half binds no class of its own. Its keys stand in the tree of the extension's
 * platform-neutral half, below <code>vanillabp.cockpit</code>, and that half binds them. So the
 * keys are not found by walking a class. They are the list {@link PeaCockpitSettings#KEYS_READ},
 * which the check for a key below a workflow module walks as well. One test here binds each key
 * of that list the way the application does, so the list cannot name a key nobody reads.
 * <p>
 * The metadata has one part, see decision 23 in the repository's DECISIONS.md. The configuration
 * processor finds no class to describe, and it copies the hand-written file into the jar.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EveryKeyIsKnownToTheIdeTest {

  private static final String METADATA = "META-INF/spring-configuration-metadata.json";

  private static final String SCOPE_LEVELS = "vanillabp.workflow-modules.";

  private static Map<String, ConfigurationMetadataProperty> metadata;

  private static List<String> keysRead;

  @BeforeAll
  public static void readBothSides() throws IOException {

    metadata = metadataOfThisModule();
    keysRead = PeaCockpitSettings.KEYS_READ
        .stream()
        .map(ConfigurationKeys::globalKey)
        .toList();

  }

  @Test
  @DisplayName("every key read is described with a type and a description")
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
        "add these keys to META-INF/additional-spring-configuration-metadata.json, with a type and a description: "
            + missing);

  }

  @Test
  @DisplayName("every key described is read by this half")
  public void everyDescribedKeyIsRead() {

    final var notRead = new TreeSet<String>();
    metadata
        .keySet()
        .stream()
        // a key which no longer works is described so the IDE marks it, and no code reads it
        .filter(name -> !isNoLongerSupported(metadata.get(name)))
        .filter(name -> !keysRead.contains(name))
        .forEach(notRead::add);
    assertTrue(
        notRead.isEmpty(),
        "the metadata describes keys this half does not read: "
            + notRead);

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
  @DisplayName("each key of the list reaches the reading, with the type the metadata names")
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
    // a key without an entry is reported by everyKeyReadIsDescribed
    if (metadata.containsKey(key)) {
      assertEquals(
          typeName(Integer.class),
          typeName(metadata
              .get(key)
              .getType()),
          "the type of '%s' in the metadata".formatted(key));
    }

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
  @DisplayName("a default in the metadata is the value this half starts with")
  public void everyDefaultIsTheRealOne() {

    final var key = ConfigurationKeys.globalKey(PeaCockpitSettings.REMEMBERED_USER_TASKS);
    final var entry = metadata.get(key);
    if ((entry == null) || (entry.getDefaultValue() == null)) {
      // a key without an entry is reported by everyKeyReadIsDescribed
      return;
    }
    final var nothingConfigured = new CockpitOverlayProperties().toSettings();
    assertEquals(
        PeaCockpitSettings.rememberedUserTasks(nothingConfigured),
        ApplicationConversionService
            .getSharedInstance()
            .convert(entry.getDefaultValue(), Integer.class),
        "the default of '%s' in the metadata".formatted(key));

  }

  private static boolean isNoLongerSupported(
      final ConfigurationMetadataProperty entry) {

    return (entry.getDeprecation() != null) && (entry
        .getDeprecation()
        .getLevel() == Deprecation.Level.ERROR);

  }

  /**
   * The processor writes a nested class with a dollar sign and a type argument list without
   * blanks. Java's own type names have a dot and a blank there.
   */
  private static String typeName(
      final Object type) {

    final var name = type instanceof Class<?> javaType
        ? javaType.getTypeName()
        : String.valueOf(type);
    return name
        .replace('$', '.')
        .replace(" ", "");

  }

  private static boolean isBlank(
      final Object text) {

    return (text == null) || text
        .toString()
        .isBlank();

  }

  /**
   * The metadata of THIS module, which is the file next to the auto-configuration. The class
   * path of the test holds the files of other jars as well. It is read with Spring Boot's own
   * reader of the format, which tools use as well.
   */
  private static Map<String, ConfigurationMetadataProperty> metadataOfThisModule() throws IOException {

    final var location = PeaCockpitAutoConfiguration.class
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .toString();
    final var files = EveryKeyIsKnownToTheIdeTest.class
        .getClassLoader()
        .getResources(METADATA);
    while (files.hasMoreElements()) {
      final var file = files.nextElement();
      if (!file
          .toString()
          .startsWith(location)) {
        continue;
      }
      try (InputStream in = file.openStream()) {
        return new TreeMap<>(ConfigurationMetadataRepositoryJsonBuilder
            .create(StandardCharsets.UTF_8)
            .withJsonResource(in)
            .build()
            .getAllProperties());
      }
    }
    throw new AssertionError("no %s next to %s, is the configuration processor configured?"
        .formatted(METADATA, location));

  }

}
