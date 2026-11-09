#!/bin/sh
# Builds NoEndermen-patcher.jar (needs a JDK and curl).
set -e
ASM=9.7.1
mkdir -p lib build/classes
for a in asm asm-tree; do
  [ -f lib/$a-$ASM.jar ] || curl -fsSL -o lib/$a-$ASM.jar https://repo1.maven.org/maven2/org/ow2/asm/$a/$ASM/$a-$ASM.jar
done
javac --release 8 -Xlint:-options -cp lib/asm-$ASM.jar:lib/asm-tree-$ASM.jar -d build/classes src/noendermen/NoEndermen.java
(cd build/classes && unzip -qo ../../lib/asm-$ASM.jar 'org/*' && unzip -qo ../../lib/asm-tree-$ASM.jar 'org/*')
printf 'Main-Class: noendermen.NoEndermen\n' > build/manifest.txt
jar cfm build/NoEndermen-patcher.jar build/manifest.txt -C build/classes .
echo "Built build/NoEndermen-patcher.jar"
