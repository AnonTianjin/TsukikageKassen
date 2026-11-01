# 当前验证过的构建环境：JDK 25 + Node（假人冒烟用）+ curl。
# 本地实测：Debian/Ubuntu + OpenJDK 25.0.5 + Node 24 + Paper 26.1.2 build 74。
FROM eclipse-temurin:25-jdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      nodejs \
      npm \
      curl \
      ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && node -v && npm -v && java -version

# Gradle 与 npm 依赖落在同一个卷里：同一次构建的多个 stage 复用，避免重复下载
ENV GRADLE_USER_HOME=/cache/gradle
ENV npm_config_cache=/cache/npm
RUN mkdir -p /cache/gradle /cache/npm
VOLUME /cache
