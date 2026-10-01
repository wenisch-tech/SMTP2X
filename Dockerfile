# syntax=docker/dockerfile:1.27
# Chainguard JRE is the runtime image. The small scaffold stage creates the
# writable data directory used by the application.
FROM cgr.dev/chainguard/wolfi-base:latest AS scaffold
RUN mkdir -p /app/data && chmod 0777 /app/data
FROM cgr.dev/chainguard/jre:latest
WORKDIR /app
ARG BUILD_DATE=unknown
ARG BUILD_VERSION=dev
ARG BUILD_REVISION=unknown
LABEL org.opencontainers.image.title="SMTP2X" \
      org.opencontainers.image.description="SMTP-to-GitLab and webhook gateway" \
      org.opencontainers.image.source="https://github.com/wenisch-tech/SMTP2X" \
      org.opencontainers.image.licenses="AGPL-3.0" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      org.opencontainers.image.created="${BUILD_DATE}"
COPY --from=scaffold /app/data /app/data
COPY target/smtp2x-*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"
VOLUME ["/app/data"]
EXPOSE 8080 2525
ENTRYPOINT ["java","-jar","/app/app.jar"]
