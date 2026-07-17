FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew settings.gradle build.gradle ./
COPY src ./src

RUN chmod +x ./gradlew

RUN test ! -f src/main/resources/env.properties && \
    ! find src/main/resources -type f \( \
      -iname '*firebase-adminsdk*.json' -o \
      -iname '*firebase-admin*.json' -o \
      -iname '*service-account*.json' -o \
      -iname '*serviceAccount*.json' \
    \) | grep -q .

RUN ./gradlew --no-daemon clean test bootWar

RUN WAR_FILE=$(find build/libs -name "*.war" ! -name "*plain*" | head -n 1) && \
    cp "$WAR_FILE" /workspace/app.war

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 --user-group --no-create-home nolate

COPY --from=build --chown=nolate:nolate /workspace/app.war /app/app.war

USER 10001

ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 5522

ENTRYPOINT ["java", "-jar", "/app/app.war"]
