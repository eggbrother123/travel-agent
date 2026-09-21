# E6 多阶段构建：maven 打包 → JRE 运行。镜像统一走 daocloud 镜像源（Hub 直连被墙）
# 注意：不要加 "# syntax=docker/dockerfile:1"——BuildKit 会去 Hub 拉 frontend 镜像，被墙必炸
FROM docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 依赖层单独缓存：pom 不变就跳过依赖下载（慢网环境下第二次 build 秒过）
COPY docker-settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

FROM docker.m.daocloud.io/library/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
