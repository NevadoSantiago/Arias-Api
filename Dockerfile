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
# Sin estos flags la JVM mira la RAM del contenedor (8GB en Railway),
# aplica su MaxRAMPercentage default de 25% y se queda con ~2GB de heap
# que nunca devuelve al SO. De ahí el 1GB constante en idle.
#
# MaxRAMPercentage=75  → el heap se ata al límite REAL del contenedor.
#                        Con 512MB en Railway → ~384MB de heap.
# UseSerialGC          → G1 reserva mucha memoria nativa (remembered sets,
#                        card tables). Con este límite la JVM ya elegiría
#                        Serial sola, pero lo hacemos explícito para que no
#                        vuelva a G1 en silencio si algún día subís el límite.
#                        Si el tráfico crece de verdad, sacá esta línea.
# MaxMetaspaceSize     → techo a las clases cargadas (Hibernate, Security,
#                        POI, AWS SDK). Si ves "OutOfMemoryError: Metaspace",
#                        subilo a 256m. No es un número mágico.
# Xss512k              → stack por thread (default 1MB) × 20 threads Tomcat.
# ExitOnOutOfMemoryError → morir rápido y que Railway reinicie, en vez de
#                        quedar zombie degradado.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 \
-XX:+UseSerialGC \
-XX:MaxMetaspaceSize=192m \
-XX:ReservedCodeCacheSize=64m \
-Xss512k \
-XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
