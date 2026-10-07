package io.portable.identity.events;

import java.util.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public final class IdentityEventListenerProviderFactory implements EventListenerProviderFactory {
    public static final String ID = "identity-kafka-outbox";
    private static final Logger LOG = Logger.getLogger(IdentityEventListenerProviderFactory.class.getName());
    private ProviderConfig config;
    private OutboxRepository repository;
    private EventMetrics metrics;
    private OutboxPublisher publisher;

    @Override
    public void init(Config.Scope scope) {
        config = ProviderConfig.load();
        repository = new OutboxRepository();
        metrics = new EventMetrics();
        LOG.info("identity-event provider configured enabled=" + config.enabled + " required=" +
            config.required + " mode=" + config.runtimeMode + " clientId=" + config.clientId);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (!config.enabled) return;
        RuntimeTopologyValidator.validate(config);
        publisher = new OutboxPublisher(config, factory, repository, metrics);
        publisher.start();
    }

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new IdentityEventListenerProvider(session, config, repository, metrics);
    }

    @Override
    public String getId() { return ID; }

    @Override
    public void close() {
        if (publisher != null) publisher.close();
    }
}
