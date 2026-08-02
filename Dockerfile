FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew settings.gradle build.gradle ./
COPY src ./src
# Several schema and deployment contract tests deliberately verify reviewed repository artifacts.
# Keep those artifacts in the build stage only; they are not copied into the runtime image.
COPY docs ./docs
COPY Dockerfile ./Dockerfile

RUN chmod +x ./gradlew

RUN test ! -f src/main/resources/env.properties && \
    ! find src/main/resources -type f \( \
      -iname '*firebase-adminsdk*.json' -o \
      -iname '*firebase-admin*.json' -o \
      -iname '*service-account*.json' -o \
      -iname '*serviceAccount*.json' \
    \) | grep -q .

# Testcontainers cannot access the host Docker Engine from a BuildKit build step.
# MySQL integration tests remain mandatory in the host/CI gate and are excluded only here.
RUN ./gradlew --no-daemon clean test bootWar -PexcludeMysqlTests=true

RUN WAR_FILE=$(find build/libs -name "*.war" ! -name "*plain*" | head -n 1) && \
    cp "$WAR_FILE" /workspace/app.war

RUN jar --create \
    --file /workspace/readiness-probe.jar \
    --main-class com.noLate.global.health.ContainerReadinessProbe \
    -C build/classes/java/main com/noLate/global/health/ContainerReadinessProbe.class

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 --user-group --no-create-home nolate

COPY --from=build --chown=nolate:nolate /workspace/app.war /app/app.war
COPY --from=build --chown=nolate:nolate /workspace/readiness-probe.jar /app/readiness-probe.jar

USER 10001

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 5522

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["java", "-jar", "/app/readiness-probe.jar"]

ENTRYPOINT ["java", "-jar", "/app/app.war"]
