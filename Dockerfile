# ─────────────────── Stage 1: build ───────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copiamos solo el pom primero: si no cambian las dependencias,
# Docker reusa esta capa y el build es muchísimo más rápido.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ─────────────────── Stage 2: runtime ───────────────────
# JRE (no JDK) sobre Alpine: imagen final chica, sin compilador ni herramientas.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# ── Flags de memoria ──────────────────────────────────────────────────
# Sin flags, la JVM mira la RAM que le reporta el contenedor y aplica su
# MaxRAMPercentage default de 25%. En Railway el replica limit es de 24GB,
# o sea 6GB de heap máximo: la JVM crece y nunca lo devuelve al SO.
#
# Xmx320m              → ABSOLUTO, a propósito. NO usar MaxRAMPercentage acá:
#                        es un % del límite del contenedor, y ese límite en
#                        Railway son 24GB (no los 512MB de un docker run local).
#                        Un 75% ahí serían 18GB de heap: TRIPLE del default.
#                        Un valor absoluto no depende de ningún límite externo
#                        ni de que nadie toque los replica limits.
# UseSerialGC          → G1 reserva mucha memoria nativa (remembered sets,
#                        card tables) y no le aporta nada a un heap de 320MB.
#                        Explícito porque con 24GB visibles la JVM se considera
#                        "server class" y elegiría G1 sola.
#                        Si el tráfico crece de verdad, revisá esta línea.
# MaxMetaspaceSize     → techo a las clases cargadas (Hibernate, Security,
#                        POI, AWS SDK). Si ves "OutOfMemoryError: Metaspace",
#                        subilo a 256m. No es un número mágico.
# Xss512k              → stack por thread (default 1MB) × 20 threads Tomcat.
# ExitOnOutOfMemoryError → morir rápido y que Railway reinicie, en vez de
#                        quedar zombie degradado.
ENV JAVA_OPTS="-Xmx320m \
-XX:+UseSerialGC \
-XX:MaxMetaspaceSize=192m \
-XX:ReservedCodeCacheSize=64m \
-Xss512k \
-XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
