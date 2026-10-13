FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S flashsale && adduser -S flashsale -G flashsale
COPY --from=build --chown=flashsale:flashsale /build/target/flash-sales-1.0.0-SNAPSHOT.jar app.jar
USER flashsale
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
