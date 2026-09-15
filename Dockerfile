FROM eclipse-temurin:25-jdk-jammy AS builder

RUN apt-get update && \
    apt-get install -y curl ca-certificates gnupg && \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /usr/share/keyrings/postgresql-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/postgresql-keyring.gpg] http://apt.postgresql.org/pub/repos/apt jammy-pgdg main" > /etc/apt/sources.list.d/pgdg.list && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y postgresql-17 && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

COPY src src
COPY .env.example .env

RUN chmod +x gradlew

RUN service postgresql start && \
    su - postgres -c "psql -c \"ALTER USER postgres WITH PASSWORD 'change-me';\"" && \
    su - postgres -c "createdb -O postgres pegasus_tcg" && \
    ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S spring && adduser -S spring -G spring

WORKDIR /app

COPY --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar

RUN chown spring:spring app.jar

USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
