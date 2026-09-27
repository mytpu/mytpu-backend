FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ai-backend-*.jar ai-backend.jar
ENTRYPOINT ["java", "-jar", "ai-backend.jar"]