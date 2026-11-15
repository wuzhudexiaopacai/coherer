# syntax=docker/dockerfile:1
# 运行镜像：jar 由 CI 或本地 `mvn -DskipTests package` 预先构建。
# 这样镜像构建不需要访问 Maven 仓库，部署环境更可控。
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 coherer \
    && mkdir -p /app/firmware \
    && chown coherer:coherer /app/firmware
COPY target/coherer-backend-*.jar app.jar
USER coherer
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
