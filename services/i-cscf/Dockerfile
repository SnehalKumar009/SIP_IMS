# syntax=docker/dockerfile:1

# Runtime-only image: copies the jar built by `mvn -pl services/i-cscf -am package`.
# Build context = services/i-cscf
FROM eclipse-temurin:21-jre
WORKDIR /app
# ZGC to keep signaling latency off the JVM GC critical path.
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"
COPY target/i-cscf-*.jar app.jar
EXPOSE 5060/udp 5060/tcp 8080/tcp
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
