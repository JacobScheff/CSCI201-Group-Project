FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd -m rally
WORKDIR /app
RUN mkdir images && chown rally:rally images
COPY --from=build /build/target/rally-1.0.0.jar app.jar
COPY docker-entrypoint.sh /usr/local/bin/rally-entrypoint
RUN chmod 0755 /usr/local/bin/rally-entrypoint
USER rally
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/rally-entrypoint"]
