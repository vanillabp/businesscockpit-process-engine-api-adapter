package io.vanillabp.cockpit.pea.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.cockpit.pea.quarkus.PeaCockpitProducer;

/**
 * What the Process-Engine-API half of the Business Cockpit extension has to say at build time.
 * <p>
 * It produces no VanillaBP build item: an extension announces itself by the beans it produces,
 * unlike a BPMS adapter.
 */
class PeaCockpitProcessor {

  private static final String FEATURE = "vanillabp-business-cockpit-process-engine-api";

  /**
   * @param featureProducer Where the feature is announced, so that a booting application lists
   *          the extension
   * @return The producer class, as a bean nothing may remove
   */
  @BuildStep
  AdditionalBeanBuildItem registerProducer(
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));
    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(PeaCockpitProducer.class)
        .setUnremovable()
        .build();

  }

}
