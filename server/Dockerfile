# Сборка фат-джара
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY shared ./shared
COPY server ./server
RUN gradle --no-daemon :server:buildFatJar

# Рантайм: JRE + ffmpeg (ingest: ffprobe, loudnorm, кодирование opus)
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl \
 && rm -rf /var/lib/apt/lists/*

RUN useradd --system --uid 10001 --create-home vitz
WORKDIR /app
COPY --from=build /src/server/build/libs/*-all.jar /app/server.jar
RUN mkdir -p /media && chown -R vitz:vitz /media /app
USER vitz

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/server.jar"]
