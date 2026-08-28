# syntax=docker/dockerfile:1

# Runtime-only image: copies the jar built by `mvn -pl services/hss -am package`.
# Build context = services/hss
FROM eclipse-temurin:21-jre
WORKDIR /app
# ZGC to keep Cx request latency off the JVM GC critical path.
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"
COPY target/hss-*.jar app.jar
EXPOSE 9090/tcp 8080/tcp
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
