package io.portable.identity.events;

import jakarta.persistence.EntityManager;
import java.util.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

final class IdentityEventListenerProvider implements EventListenerProvider {
    private static final Logger LOG = Logger.getLogger(IdentityEventListenerProvider.class.getName());
    private final KeycloakSession session;
    private final ProviderConfig config;
    private final OutboxRepository repository;
    private final IdentityEventMapper mapper;
    private final EventMetrics metrics;

    IdentityEventListenerProvider(KeycloakSession session, ProviderConfig config,
                                  OutboxRepository repository, EventMetrics metrics) {
        this.session = session;
        this.config = config;
        this.repository = repository;
        this.mapper = new IdentityEventMapper(config);
        this.metrics = metrics;
    }

    @Override
    public void onEvent(Event event) {
        if (!config.enabled) return;
        capture(mapper.user(event));
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (!config.enabled) return;
        capture(mapper.admin(event));
    }

    private void capture(OutboxRecord record) {
        try {
            EntityManager entityManager = session.getProvider(JpaConnectionProvider.class).getEntityManager();
            repository.capture(entityManager, record, config.backlogMaxRows);
            if (mapper.securityRelevant(record)) {
                repository.capture(entityManager, mapper.securityCopy(record), config.backlogMaxRows);
            }
            metrics.captured(record.kind());
        } catch (RuntimeException failure) {
            metrics.captureFailed();
            LOG.severe("identity-event capture failed kind=" + record.kind() + " class=" + OutboxPublisher.safeClass(failure));
            if (config.required) throw failure;
        }
    }

    @Override
    public void close() { }
}
