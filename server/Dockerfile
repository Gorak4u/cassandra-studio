# Studio Server image (docs/guide/server.md).
# Build from the repository root after `cd engine && ./gradlew installDist`:
#   docker build -f server/Dockerfile -t cassandra-studio-server .
# Runs as an unprivileged user; data in /var/lib/cassandra-studio; settings from
# /etc/cassandra-studio/server.conf and/or STUDIO_* environment variables.
FROM eclipse-temurin:21-jre-noble

RUN groupadd --system --gid 10001 studio \
 && useradd --system --uid 10001 --gid studio --home-dir /var/lib/cassandra-studio --shell /usr/sbin/nologin studio \
 && mkdir -p /var/lib/cassandra-studio /etc/cassandra-studio \
 && chown studio:studio /var/lib/cassandra-studio

COPY engine/build/install/cassandra-studio-engine/ /opt/cassandra-studio/
COPY server/server.conf.example /etc/cassandra-studio/server.conf.example

ENV PATH=/opt/cassandra-studio/bin:$PATH \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    STUDIO_SERVER_HOST=0.0.0.0 \
    STUDIO_SERVER_PORT=8443 \
    STUDIO_SERVER_DATADIR=/var/lib/cassandra-studio

USER studio:studio
WORKDIR /var/lib/cassandra-studio
VOLUME ["/var/lib/cassandra-studio"]
EXPOSE 8443

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 CMD ["studio-server", "health"]
ENTRYPOINT ["studio-server"]
CMD ["run"]
