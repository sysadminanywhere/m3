FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/m3-community-2.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
