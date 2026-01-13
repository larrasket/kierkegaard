# Build stage
FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y curl gnupg2 && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

WORKDIR /app

COPY build.sbt .
COPY project/build.properties project/

RUN sbt update

COPY src/ src/
COPY eitheror.epub papers_and_journals selections.txt ./

RUN sbt compile

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update && apt-get install -y curl gnupg2 && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /app /app
COPY --from=builder /root/.sbt /root/.sbt
COPY --from=builder /root/.cache /root/.cache

RUN mkdir -p /app/data

EXPOSE 8080

ENV PORT=8080
ENV FORCE_POST_PASSWORD=changeme

CMD ["sbt", "run --daemon"]
