# SimpleDeathFinder

[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Server](https://img.shields.io/badge/Paper%2FSpigot-1.20%E2%80%931.21-blue.svg)](https://papermc.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](#-license)
[![Release](https://img.shields.io/github/v/release/JobbeDeluxe/SimpleDeathFinder?sort=semver)](https://github.com/JobbeDeluxe/SimpleDeathFinder/releases)
[![Downloads](https://img.shields.io/github/downloads/JobbeDeluxe/SimpleDeathFinder/total.svg)](https://github.com/JobbeDeluxe/SimpleDeathFinder/releases)

A lightweight Paper/Spigot plugin that gives players a **Recovery Compass** on **respawn** and **removes** it once they get **within 5 blocks** of their death location. Minimal overhead, **no log spam**.

---

## ✨ Features

- Automatic **Recovery Compass** on respawn (toggle in config)
- **Auto-remove** the compass when the player reaches the death spot (radius configurable)
- Compasses are **tagged** via Persistent Data Container to avoid touching other compasses
- Option to **remove previous tagged compasses** when issuing a new one
- Friendly messages for **different dimension** cases (vanilla: compass spins)
- Lightweight **periodic check** (default: every 1 second)
- Tested with **Paper/Spigot 1.20–1.21**, **Java 17+**

---

## 📦 Installation

1. Build the JAR (see below) or download a release artifact.
2. Drop the file into your server’s `plugins/` folder.
3. Start the server → `plugins/SimpleDeathFinder/config.yml` is generated.

---

## ⚙️ Configuration (`plugins/SimpleDeathFinder/config.yml`)

```yml
give-on-respawn: true

remove-on-approach:
  enabled: true
  radius: 5.0                 # distance in blocks
  check-interval-ticks: 20    # 20 ticks = 1 second

remove-old-compasses: true     # remove previously tagged compasses when giving a new one

messages:
  given: "&aYou received a recovery compass. It points to your last death location."
  arrived: "&7You arrived at your death location. The compass crumbles."
  full-inventory: "&eInventory full — the compass was dropped at your feet."
  other-dimension: "&eYour death location is in another dimension — the compass spins."

item:
  name: "&bRecovery Compass"
  lore:
    - "&7Points to your last death location."
    - "&7Where: &f{dim} &7@ &f{x}&7,&f{y}&7,&f{z}"

sound:
  arrived: "entity.experience_orb.pickup"
```

**Placeholders** in `item.lore`: `{dim}`, `{x}`, `{y}`, `{z}`

---

## 🕹 Commands & Permissions

**Command**
```
/sdf [reload|give <player>]
```
- `/sdf reload` — reload configuration
- `/sdf give <player>` — give a compass manually

**Permissions**
```yaml
simpledeathfinder.use: true      # reserved for future features
simpledeathfinder.reload: op
simpledeathfinder.give: op
```

---

## 🔧 Build (Maven)

### Option A: Docker (recommended on servers)
```bash
docker run --rm -u $(id -u):$(id -g) -v "$PWD":/src -w /src   maven:3.9-eclipse-temurin-21 mvn -DskipTests package

# Result: target/SimpleDeathFinder-<version>.jar
```

### Option B: Local Maven
```bash
mvn -DskipTests package
```

**Dependencies**
- `spigot-api` (scope `provided`)
- Java 17 (`<release>17</release>` in POM)

---

## ✅ Behavior & Compatibility

- The item is a **vanilla RECOVERY_COMPASS** tagged with PDC.
- **Auto-removal**: at most **one** tagged compass is removed per check tick per player.
- **Dimensions**: In a different dimension the compass spins (vanilla); we send a hint message.
- **Geyser/Floodgate**: server‑side only; works fine for Bedrock players as well.
- No console spam; only short messages on load/reload.

---

## 🔖 GitHub Badges

Replace the placeholder `YOUR_GITHUB_USERNAME` (and repo name if different) at the top of this README:
```
https://github.com/YOUR_GITHUB_USERNAME/SimpleDeathFinder/actions/workflows/build.yml/badge.svg
https://img.shields.io/github/v/release/YOUR_GITHUB_USERNAME/SimpleDeathFinder?sort=semver
https://img.shields.io/github/downloads/YOUR_GITHUB_USERNAME/SimpleDeathFinder/total.svg
```

---

## 📜 Changelog

**1.0.0**
- Initial release: compass on respawn, auto‑remove within radius, configurable messages/sound

---

## 📄 License

This project is licensed under the **MIT License** — see [`LICENSE`](LICENSE).
