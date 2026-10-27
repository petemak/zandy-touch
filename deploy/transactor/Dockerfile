# syntax=docker/dockerfile:1.7
# Datomic Pro transactor (dev storage) and the Datomic command line tools (backup-db, restore-db).
# The version MUST equal com.datomic/peer in the root deps.edn (1.0.7745). A peer and a transactor of
# different versions can refuse to talk to each other. scripts/ops/check-versions.sh compares them.
#
# Build context: the repository root (docker build -f deploy/transactor/Dockerfile .).
ARG JRE_IMAGE=eclipse-temurin:21-jre-noble

FROM ${JRE_IMAGE} AS fetch
ARG DATOMIC_VERSION=1.0.7745
# sha256 of datomic-pro-1.0.7745.zip, computed on 25 Oct 2026 from
# https://datomic-pro-downloads.s3.amazonaws.com/1.0.7745/datomic-pro-1.0.7745.zip (272751248 bytes).
# Change both lines together when the peer version changes.
ARG DATOMIC_SHA256=60ac6bc5f04fefb85a5aeef6a5e6c5369c5a880ca6b9f205e00a12719e7bd2dc
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL --retry 3 -o /tmp/datomic.zip \
      "https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip" \
 && echo "${DATOMIC_SHA256}  /tmp/datomic.zip" | sha256sum -c - \
 && unzip -q /tmp/datomic.zip -d /opt \
 && mv "/opt/datomic-pro-${DATOMIC_VERSION}" /opt/datomic \
 && rm -rf /opt/datomic/samples /opt/datomic/presto-server /opt/datomic/bin/*.cmd /tmp/datomic.zip

FROM ${JRE_IMAGE}
RUN apt-get update \
 && apt-get install -y --no-install-recommends gettext-base curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --gid 10001 zandy && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin zandy \
 && mkdir -p /var/lib/datomic/data /var/lib/datomic/log /var/lib/datomic/backup /etc/datomic \
 && chown -R zandy:zandy /var/lib/datomic
COPY --from=fetch /opt/datomic /opt/datomic
COPY deploy/transactor/transactor.properties.template /etc/datomic/transactor.properties.template
COPY deploy/transactor/logback.xml /etc/datomic/logback.xml
COPY --chmod=0755 deploy/transactor/entrypoint.sh /usr/local/bin/entrypoint.sh
USER zandy
WORKDIR /opt/datomic
VOLUME ["/var/lib/datomic/data", "/var/lib/datomic/backup"]
EXPOSE 4334 4335
# Heap is passed by compose.yaml as command arguments (-Xmx1024m, architecture D3).
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["transactor", "-Xmx1024m", "-Xms1024m"]
