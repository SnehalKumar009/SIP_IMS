# syntax=docker/dockerfile:1

# Runtime-only image: copies the jar built by `mvn -pl services/cdf-billing -am package`.
# Build context = services/cdf-billing
FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"
COPY target/cdf-billing-*.jar app.jar
EXPOSE 8080/tcp
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
