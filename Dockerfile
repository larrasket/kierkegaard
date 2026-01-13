# Build stage
FROM eclipse-temurin:21-jdk AS builder

RUN apt-get update && apt-get install -y curl gnupg2 && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

WORKDIR /app

COPY build.sbt .
COPY project/ project/

RUN sbt update

COPY src/ src/
COPY eitheror.epub papers_and_journals selections.txt ./

# Build fat JAR
RUN sbt assembly

# Build quotes database
RUN java -jar target/scala-2.13/kierkegaard-bot.jar --build-quotes

# Runtime stage - minimal image
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the JAR, initial data (to a staging location), and config
COPY --from=builder /app/target/scala-2.13/kierkegaard-bot.jar /app/
COPY --from=builder /app/data /app/data-init
COPY --from=builder /app/src/main/resources/application.conf /app/

# Create startup script that initializes data volume if empty
RUN echo '#!/bin/sh' > /app/start.sh && \
    echo 'if [ ! -f /app/data/quotes.json ]; then' >> /app/start.sh && \
    echo '  echo "Initializing data volume..."' >> /app/start.sh && \
    echo '  cp -r /app/data-init/* /app/data/' >> /app/start.sh && \
    echo 'fi' >> /app/start.sh && \
    echo 'exec java -Xmx256m -Dconfig.file=/app/application.conf -jar /app/kierkegaard-bot.jar --daemon' >> /app/start.sh && \
    chmod +x /app/start.sh

EXPOSE 8080

ENV PORT=8080

CMD ["/app/start.sh"]
