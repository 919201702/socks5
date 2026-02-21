#!/bin/bash

set -euo pipefail

echo "请确保在 GraalVM 环境下执行"

os_name=$(uname)
config_dir=""

if [[ "$os_name" == "Darwin" ]]; then
    config_dir="macos-aarch_64"
elif [[ "$os_name" == "Linux" ]]; then
    config_dir="linux-x86_64"
elif [[ "$os_name" == MINGW* || "$os_name" == CYGWIN* || "$os_name" == "Windows_NT" ]]; then
    config_dir="windows-x86_64"
else
    echo "Unsupported OS: $os_name"
    exit 1
fi

echo "Detected OS: $os_name, using config directory: $config_dir"

config_output_path="client/src/main/resources/graalvm-config/${config_dir}/META-INF/native-image/com.itjiang/client"
app_jar="client/target/client-1.0.1-jar-with-dependencies.jar"

mkdir -p "${config_output_path}"

# 仅打包 client 及其依赖模块，避免重复 clean/package
mvn -pl client -am clean package -DskipTests

echo "Running agent to generate config into: ${config_output_path}"
java -agentlib:native-image-agent=config-merge-dir="${config_output_path}" -Dconfig="proxy-jp.properties" -jar "${app_jar}"
