# syntax=docker/dockerfile:1

FROM eclipse-temurin:26-jdk AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY . .
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:26-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

COPY --from=build /workspace/target/agentic-trip-ai-mcp-server-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8090

USER 10001:10001

ENTRYPOINT ["java","-jar","/app/app.jar"]
