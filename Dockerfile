# Build stage
FROM eclipse-temurin:21-jdk AS builder

# Install sbt
RUN apt-get update && apt-get install -y curl gnupg2 && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

WORKDIR /app

# Copy build files first for layer caching
COPY build.sbt .
COPY project/build.properties project/

# Download dependencies
RUN sbt update

# Copy source and resources
COPY src/ src/
COPY eitheror.epub papers_and_journals selections.txt ./

# Compile and stage
RUN sbt compile

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install sbt for running (needed for "sbt run" command)
RUN apt-get update && apt-get install -y curl gnupg2 && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Copy everything from builder
COPY --from=builder /app /app
COPY --from=builder /root/.sbt /root/.sbt
COPY --from=builder /root/.cache /root/.cache

# Create data directory
RUN mkdir -p /app/data

# Default command - post one quote
CMD ["sbt", "run --post-one"]
