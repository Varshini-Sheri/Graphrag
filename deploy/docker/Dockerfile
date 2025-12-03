FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy both JARs
COPY modules/job/target/scala-2.12/graphrag-job-assembly-0.1.0-SNAPSHOT.jar /app/graphrag-job.jar
COPY modules/api/target/scala-2.12/graphrag-api-assembly-0.1.0-SNAPSHOT.jar /app/graphrag-api.jar

# Copy data and config
COPY data/index/chunks.jsonl /app/data/index/chunks.jsonl
COPY deploy/docker/application.conf /app/application.conf

# Entry point that checks MODE environment variable
ENTRYPOINT ["sh", "-c", "\
  if [ \"$MODE\" = \"api\" ]; then \
    echo 'Starting GraphRAG API server...'; \
    java -Dconfig.file=/app/application.conf \
      --add-opens=java.base/java.util=ALL-UNNAMED \
      --add-opens=java.base/java.lang=ALL-UNNAMED \
      -jar /app/graphrag-api.jar; \
  else \
    echo 'Starting GraphRAG job...'; \
    java -Dconfig.file=/app/application.conf \
      --add-opens=java.base/java.util=ALL-UNNAMED \
      --add-opens=java.base/java.lang=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
      --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
      --add-opens=java.base/java.net=ALL-UNNAMED \
      --add-opens=java.base/java.io=ALL-UNNAMED \
      -jar /app/graphrag-job.jar; \
  fi"]