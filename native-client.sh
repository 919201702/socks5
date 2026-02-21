#!/bin/bash

set -euo pipefail

echo "请确保在 GraalVM 环境下执行"

# 仅构建 client 及其依赖模块，跳过测试以缩短 native 编译前阶段耗时
mvn -pl client -am clean package -Pnative -DskipTests
