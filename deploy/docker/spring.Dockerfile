# Общий образ для Spring-сервисов. Jar собирается заранее (mvn package), контекст сборки — корень репозитория:
#   docker build -f deploy/docker/spring.Dockerfile --build-arg MODULE=services/assistant -t mytpu/assistant .
# Флаги JVM — документ 22, раздел 3. Лимит памяти задаёт compose (mem_limit, memswap_limit).

FROM eclipse-temurin:25-jre AS build
ARG MODULE
# Переменные только для тренировочного прогона (сервису нужны значения, чтобы поднять контекст)
ARG TRAINING_ENV=""
WORKDIR /app
ENV JVM_FLAGS="-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=15 \
-XX:+UseSerialGC -XX:+UseCompactObjectHeaders \
-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=32m \
-XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -Xss512k \
-XX:+PerfDisableSharedMem -XX:+ExitOnOutOfMemoryError \
-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
COPY ${MODULE}/target/*-SNAPSHOT.jar /tmp/app.jar
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app && rm /tmp/app.jar
# Тренировочный прогон для AOT-кэша (JDK 25): контекст поднимается и сразу закрывается.
# Сервисам, которым на старте нужна БД, прогон может не удаться — тогда образ собирается без кэша.
RUN env ${TRAINING_ENV} java ${JVM_FLAGS} -XX:AOTCacheOutput=/app/app.aot \
        -Dspring.context.exit=onRefresh -jar /app/app.jar \
    || (echo "AOT-кэш не собран: сервис стартует без него" && rm -f /app/app.aot)

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /app /app
ENV MALLOC_ARENA_MAX=2
ENV JVM_FLAGS="-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=15 \
-XX:+UseSerialGC -XX:+UseCompactObjectHeaders \
-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=32m \
-XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -Xss512k \
-XX:+PerfDisableSharedMem -XX:+ExitOnOutOfMemoryError \
-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"
ENV JAVA_TOOL_OPTIONS="${JVM_FLAGS}"
COPY --chmod=0755 deploy/docker/entrypoint.sh /app/entrypoint.sh
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
