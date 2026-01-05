#!/bin/bash

# 定义变量
APP_NAME="ClientProxy"
APP_VERSION="1.0.1"
MAIN_JAR="client-${APP_VERSION}-jar-with-dependencies.jar"
MAIN_CLASS="com.itjiang.Socks5ProxyClient"

mvn clean package

# 1. 准备构建目录 (为了防止把 target 下的其他垃圾文件打进去)
echo "正在准备构建目录..."
rm -rf client/target/staging
mkdir -p client/target/staging
# 只复制那个 fat-jar 进去
cp client/target/${MAIN_JAR} client/target/staging/

# 2. (可选) 如果你有图标，把图标也放进去或者指定路径
# ICON_PATH="client/src/main/resources/icon.icns"

echo "开始打包..."
jpackage \
  --type app-image \
  --name ${APP_NAME} \
  --app-version ${APP_VERSION} \
  --input client/target/staging/ \
  --main-jar ${MAIN_JAR} \
  --main-class ${MAIN_CLASS} \
  --dest client/target/dist \
  --java-options "-Xmx512m" \
  --java-options "-Dfile.encoding=UTF-8" \
  --verbose

# 如果有图标，加上这一行参数：
# --icon ${ICON_PATH} \

echo "打包完成！文件位于: client/target/dist/${APP_NAME}.app"