# Imagem do backend Java. Contexto de build = esta pasta (backend-java/); nada fora dela é usado.
# ── Estágio 1: compila o jar (testes e portões de qualidade rodam no CI, não aqui)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -q -B -DskipTests package -pl app -am

# ── Estágio 2: imagem de execução enxuta
FROM eclipse-temurin:25-jre
# a imagem base não traz wget nem curl; o HEALTHCHECK precisa de um deles
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/app/target/raykookie-app.jar /app/raykookie.jar
ENV DOWNLOADS_DIR=/app/downloads
EXPOSE 10005
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:10005/api/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/raykookie.jar"]
