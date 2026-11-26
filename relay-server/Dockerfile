FROM gradle:8.11-jdk17 AS build
WORKDIR /app
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S phonebridge && adduser -S phonebridge -G phonebridge
COPY --from=build /app/build/install/phonebridge-relay ./
USER phonebridge
EXPOSE 8443
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s CMD wget -qO- http://localhost:8443/v1/health || exit 1
ENTRYPOINT ["./bin/phonebridge-relay"]
