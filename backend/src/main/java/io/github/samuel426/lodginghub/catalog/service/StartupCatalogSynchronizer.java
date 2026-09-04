package io.github.samuel426.lodginghub.catalog.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "catalog.sync-on-startup",
    havingValue = "true",
    matchIfMissing = true)
public class StartupCatalogSynchronizer implements ApplicationRunner {
  private final CatalogSyncService sync;

  public StartupCatalogSynchronizer(CatalogSyncService sync) {
    this.sync = sync;
  }

  @Override
  public void run(ApplicationArguments args) {
    sync.synchronizeAll();
  }
}
