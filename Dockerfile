# Multi-stage: 저장소 전체로 이미지 빌드 (로컬/EC2에 소스 클론한 경우). JAR만 배포할 때는 Dockerfile.runtime 사용.
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
RUN groupadd -r app && useradd -r -g app -u 1000 app
WORKDIR /app
COPY --from=builder --chown=app:app /workspace/build/libs/ticketing-*-SNAPSHOT.jar app.jar
RUN mkdir -p /app/logs && chown app:app /app/logs
USER app
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
