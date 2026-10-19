#!/bin/sh
# Запуск Spring-сервиса: AOT-кэш подключается, только если он собран при сборке образа
# (сервисам с БД тренировочный прогон без базы не удаётся — тогда старт без кэша, без ошибок в логе).
if [ -f /app/app.aot ]; then
    exec java -XX:AOTCache=/app/app.aot -jar /app/app.jar "$@"
fi
exec java -jar /app/app.jar "$@"
