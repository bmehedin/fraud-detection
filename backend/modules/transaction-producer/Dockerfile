FROM eclipse-temurin:25.0.5_8-jre
WORKDIR /app
COPY target/scala-2.13/transaction-producer-assembly-0.1.0-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
