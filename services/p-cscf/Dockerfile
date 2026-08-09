# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY platform/shared-telecom-lib/pom.xml platform/shared-telecom-lib/pom.xml
COPY services/p-cscf/pom.xml services/p-cscf/pom.xml
RUN mvn -q -pl platform/shared-telecom-lib,services/p-cscf -am -DskipTests dependency:go-offline || true
COPY platform/shared-telecom-lib platform/shared-telecom-lib
COPY services/p-cscf services/p-cscf
RUN mvn -q -pl services/p-cscf -am -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# ZGC to keep signaling latency off the JVM GC critical path.
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"
COPY --from=build /workspace/services/p-cscf/target/p-cscf-*.jar app.jar
EXPOSE 5060/udp 5060/tcp 8080/tcp
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
