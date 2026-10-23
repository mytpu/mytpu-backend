# Общий образ для Spring-сервисов. Jar собирается заранее (mvn package), контекст сборки — корень репозитория:
#   docker build -f deploy/docker/spring.Dockerfile --build-arg MODULE=services/assistant -t mytpu/assistant .
# Флаги JVM — документ 22, раздел 3. Лимит памяти задаёт compose (mem_limit, memswap_limit).
# Базовый образ закреплён по digest: тот же образ при каждой сборке (обновлять осознанно, вместе с проверкой trivy).

ARG BASE_IMAGE=eclipse-temurin:25-jre@sha256:dbb43d81099eec91e0800226ed2296d03146ee7857165b95112372ad0d67e87f
# Флаги JVM — в одном месте: ими идёт и тренировочный прогон AOT-кэша, и сервис (разные флаги испортили бы кэш)
ARG JVM_FLAGS="-XX:MaxRAMPercentage=55 -XX:InitialRAMPercentage=15 \
-XX:+UseSerialGC -XX:+UseCompactObjectHeaders \
-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=32m \
-XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=32m -Xss512k \
-XX:+PerfDisableSharedMem -XX:+ExitOnOutOfMemoryError \
-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30"

FROM ${BASE_IMAGE} AS build
ARG MODULE
ARG JVM_FLAGS
# Переменные только для тренировочного прогона (сервису нужны значения, чтобы поднять контекст)
ARG TRAINING_ENV=""
WORKDIR /app
# Ровно один исполняемый jar модуля (не *-plain/*-sources); версия проекта в имени не важна
COPY ${MODULE}/target/*.jar /tmp/jars/
RUN set -e; jar=$(ls /tmp/jars/*.jar | grep -v -E -- '-(plain|sources|javadoc)\.jar$'); \
    [ "$(echo "$jar" | wc -l)" -eq 1 ] || { echo "Ожидался один jar модуля, найдено: $jar"; exit 1; }; \
    mv "$jar" /tmp/app.jar && java -Djarmode=tools -jar /tmp/app.jar extract --destination /app \
    && rm -rf /tmp/jars /tmp/app.jar
# Тренировочный прогон для AOT-кэша (JDK 25): контекст поднимается и сразу закрывается.
# Сервисам, которым на старте нужна БД, прогон может не удаться — тогда образ собирается без кэша.
RUN env ${TRAINING_ENV} java ${JVM_FLAGS} -XX:AOTCacheOutput=/app/app.aot \
        -Dspring.context.exit=onRefresh -jar /app/app.jar \
    || (echo "AOT-кэш не собран: сервис стартует без него" && rm -f /app/app.aot)

FROM ${BASE_IMAGE}
ARG JVM_FLAGS
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /app /app
ENV MALLOC_ARENA_MAX=2
ENV JAVA_TOOL_OPTIONS="${JVM_FLAGS}"
COPY --chmod=0755 deploy/docker/entrypoint.sh /app/entrypoint.sh
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
