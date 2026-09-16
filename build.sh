#!/usr/bin/env bash
# 一键构建 MineUNO 插件 + PackHost 插件 + 材质包，产物输出到 out/
set -e
cd "$(dirname "$0")"

mvn -q -B -f mineuno/pom.xml package
mvn -q -B -f packhost/pom.xml package
python3 pack/gen_pack.py

mkdir -p out
cp mineuno/target/MineUNO-*.jar out/
cp packhost/target/PackHost-*.jar out/
cp pack/out/mineuno.zip out/

echo "构建完成，产物："
ls -la out/
