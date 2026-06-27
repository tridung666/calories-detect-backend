FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY settings.gradle build.gradle gradlew ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x gradlew
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown app:app app.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
