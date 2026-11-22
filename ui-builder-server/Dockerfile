# syntax=docker/dockerfile:1.29

# `ghcr.io/yschimke/compose-ui-builder-server`: the UI builder's own host, built by this repository on
# each release (`release.yml` `server-image`). Context is this directory after
# `./gradlew :ui-builder-server:installDist`.
#
# A JRE is enough while the host only answers the deployable endpoints and forwards to the legacy
# compose-preview-server `ui-builder` container. When export moves here it needs the daemons, and
# this becomes `FROM ghcr.io/yschimke/compose-preview-render-runtime:<daemon version>`
# (compose-preview-daemon `docs/daemon/RENDER_RUNTIME_IMAGE.md`).
FROM eclipse-temurin:25.0.4.1_1-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*
COPY build/install/compose-ui-builder-server /opt/compose-ui-builder-server
ENV PATH="/opt/compose-ui-builder-server/bin:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["compose-ui-builder-server"]
