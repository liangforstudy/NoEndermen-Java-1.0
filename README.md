# NoEndermen-Java-1.0

A bytecode patcher / jar mod that removes Endermen from Minecraft Java Edition 1.0.0. Yes, you heard that right: it's the version Notch released by pulling the lever on stage at MineCon 2011.

## Who is this for?
[This post on r/MinecraftMod.](https://www.reddit.com/r/MinecraftMod/s/pDMTJESJjN)

## What it changes
- **No Enderman spawns anywhere.** All 14 Enderman spawn entries are gone: 13 overworld biomes plus The End.
- **Existing Endermen disappear.** The `Enderman` entry (id 58) is removed from the entity registry. Any Endermen already saved in an old world are skipped when their chunk loads (the console logs `Skipping Entity with id Enderman`).
- Nothing else changes. The Ender Dragon, Eyes of Ender, pearls and so on are untouched.

> ⚠️ Heads-up: in 1.0, Endermen are the **only** source of Ender Pearls, because villager trading didn't exist yet. With this mod, survival players can't craft Eyes of Ender, so they can't reach The End. Use creative mode, `/give` on a server, or keep a world where you already have pearls.

## Why a patcher instead of a ready-made jar
In 1.0 there was no Forge or Fabric. Mods were "jar mods": you replaced class files inside `minecraft.jar`. Those class files are Mojang's code, so this mod is a **patcher**. It edits **your own** copy of the 1.0 jar on your machine.

It doesn't depend on obfuscated names. It finds the Enderman class by its sound string `mob.endermen.idle` and finds the code to patch by its bytecode shape. It checks the vanilla 1.0 client SHA-1 (`b679fea27f2284836202e9365e13a82552092e5d`) and refuses to run if it can't find exactly what it expects.

## Install (Prism Launcher — easiest)
1. Create a **1.0** instance in Prism and launch it once so Prism downloads the jar.
2. Find the vanilla jar in Prism's data folder: `libraries/com/mojang/minecraft/1.0/minecraft-1.0-client.jar`.
3. Run the patcher:
   ```
   java -jar NoEndermen-patcher.jar path/to/minecraft-1.0-client.jar
   ```
4. In Prism, go to **Edit instance → Version → Add to Minecraft.jar** and pick the generated `NoEndermen-jarmod.zip`.
5. Launch the game. Endermen are gone.

## Install (old-school, like 2011)
Run the patcher, then use `minecraft-1.0-noendermen.jar` in place of your `minecraft.jar` (the META-INF signature has already been removed).

## Uninstall
In Prism, remove the jar mod from the Version page. Endermen spawn again. Ones that were skipped while the mod was installed don't come back.

## Building from source
Requires a JDK and ASM 9.x (`asm` + `asm-tree`, BSD licensed, from Maven Central):
```
javac --release 8 -cp asm.jar:asm-tree.jar -d classes src/noendermen/NoEndermen.java
```

**Linux/macOS/Git Bash:** `./build.sh` downloads ASM from Maven Central and produces `build/NoEndermen-patcher.jar`.

## License
MIT — see [LICENSE](LICENSE). Minecraft is © Mojang/Microsoft; this repo contains no Mojang code.
