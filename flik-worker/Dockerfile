FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml ./
COPY flik-common/pom.xml flik-common/
COPY flik-api-gateway/pom.xml flik-api-gateway/
COPY flik-worker/pom.xml flik-worker/
COPY flik-autoscaler/pom.xml flik-autoscaler/
COPY flik-load-test/pom.xml flik-load-test/

RUN apk add --no-cache maven && \
    mvn dependency:go-offline -pl flik-common,flik-worker -am -B || true

COPY flik-common/src flik-common/src
COPY flik-worker/src flik-worker/src

RUN mvn clean package -pl flik-common,flik-worker -am -Dmaven.test.skip=true -B

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/flik-worker/target/*.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --retries=3 CMD curl -f http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
