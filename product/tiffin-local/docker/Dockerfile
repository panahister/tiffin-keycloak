ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.7.3
ARG JDK_IMAGE=eclipse-temurin:21-jdk-alpine

FROM ${KEYCLOAK_IMAGE} AS keycloak-source

FROM ${JDK_IMAGE} AS provider-builder
COPY --from=keycloak-source /opt/keycloak/lib/lib/main /opt/keycloak-libs
ADD --checksum=sha256:52501b7b47510c66f898871adaf6d2968ab7246561d44ced43643a8a587f0b36 \
    https://repo1.maven.org/maven2/org/apache/kafka/kafka-clients/4.3.1/kafka-clients-4.3.1.jar \
    /opt/provider-libs/kafka-clients-4.3.1.jar
COPY provider/src/main/java /build/provider/src/main/java
COPY provider/src/main/resources /build/provider/src/main/resources
COPY provider/src/test/java /build/provider/src/test/java
RUN mkdir -p /build/provider/classes /build/provider/test-classes /build/provider/out \
    && find /build/provider/src/main/java -name '*.java' -print0 \
      | xargs -0 javac --release 21 -encoding UTF-8 \
          -cp '/opt/keycloak-libs/*:/opt/provider-libs/*' \
          -d /build/provider/classes \
    && find /build/provider/src/test/java -name '*.java' -print0 \
      | xargs -0 javac --release 21 -encoding UTF-8 \
          -cp '/opt/keycloak-libs/*:/opt/provider-libs/*:/build/provider/classes' \
          -d /build/provider/test-classes \
    && java -ea -cp '/opt/keycloak-libs/*:/opt/provider-libs/*:/build/provider/classes:/build/provider/test-classes' \
         io.portable.identity.events.ProviderSelfTest \
    && cp -R /build/provider/src/main/resources/. /build/provider/classes/ \
    && find /build/provider/classes -exec touch -d '2026-09-01T00:00:00Z' {} + \
    && jar --create --date=2026-09-01T00:00:00Z \
         --file /build/provider/out/identity-kafka-outbox-provider.jar \
         -C /build/provider/classes .

FROM ${KEYCLOAK_IMAGE} AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_FEATURES_DISABLED=opentelemetry,opentelemetry-logs,opentelemetry-metrics
COPY --from=provider-builder --chown=1000:0 /build/provider/out/identity-kafka-outbox-provider.jar /opt/keycloak/providers/
COPY --from=provider-builder --chown=1000:0 /opt/provider-libs/kafka-clients-4.3.1.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

FROM ${KEYCLOAK_IMAGE}
COPY --from=builder --chown=1000:0 /opt/keycloak /opt/keycloak
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_FEATURES_DISABLED=opentelemetry,opentelemetry-logs,opentelemetry-metrics
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
