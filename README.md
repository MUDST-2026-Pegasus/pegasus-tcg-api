# Pegasus TCG API

This is the backend API for Pegasus TCG.

## Build and Run with Docker

You can easily build and run the entire backend infrastructure, including the database (PostgreSQL), object storage (MinIO), and the backend application itself using Docker Compose.

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) installed.
- [Docker Compose](https://docs.docker.com/compose/install/) installed.

### Setup

1. Copy the example environment file:
   ```bash
   cp .env.example .env
   ```
2. Open `.env` and fill in any required secrets such as `JWT_SECRET`, `MINIO_ACCESS_KEY`, and `MINIO_SECRET_KEY`.

### Running the Application

To build the Spring Boot backend image and start all services (Backend, PostgreSQL, MinIO) in the background, run:

```bash
docker compose up -d --build
```

The Dockerfile is configured to perform a multi-stage build, which runs Gradle inside an isolated build container, eliminating the need to install Java or Gradle on your host machine.

### Accessing the Services

- **Backend API:** `http://localhost:8080` (or the port you defined in `.env` as `BACKEND_PORT`)
- **MinIO Console:** `http://localhost:9001` (Login with your `MINIO_ACCESS_KEY` and `MINIO_SECRET_KEY`)
- **PostgreSQL Database:** `localhost:5432`

### Checking Logs

To check the logs of the backend service to verify it started successfully:

```bash
docker compose logs -f backend
```

### Stopping the Application

To stop the running services without removing the data volumes:

```bash
docker compose stop
```

To stop and completely remove the containers (your database data will persist in the Docker volume):

```bash
docker compose down
```
