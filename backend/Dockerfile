# 后端镜像：多阶段构建。构建阶段有 Maven 和 JDK，运行阶段只带 JRE，镜像更小、攻击面更小。
#
#   docker build -t paiagent-backend ./backend
#
# 构建阶段需要联网下载 Maven 依赖（默认走 Maven Central）；依赖下载有缓存，第二次构建很快。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷 pom.xml 拉依赖：源码改动不会让这一层缓存失效
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package \
    && cp target/paiagent-backend-*.jar /build/app.jar

FROM eclipse-temurin:21-jre-alpine
# 不用 root 运行：就算应用被攻破，也不能随便改容器里的系统文件
RUN addgroup -S paiagent && adduser -S paiagent -G paiagent
WORKDIR /app
COPY --from=build /build/app.jar app.jar
# 可写目录：Agent 记忆（SQLite）和产物 / 执行结果；compose 里把它们挂成卷，容器重建不丢数据
RUN mkdir -p /app/data /app/outputs && chown -R paiagent:paiagent /app
USER paiagent

ENV PAIAGENT_OUTPUT_DIR=/app/outputs \
    AGENT_MEMORY_DB_PATH=/app/data/agent-memory.db \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8084

# 就绪探针：数据库能连、执行线程池没满才算 UP。start-period 给 Spring 启动留时间，这段时间内的失败不计数
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8084/actuator/health/readiness | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
