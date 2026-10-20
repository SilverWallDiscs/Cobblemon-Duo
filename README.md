# Cobblemon Duo

**Cooperative trainer partnerships with shared native Pokédex progression**, built as a **server-side** Fabric mod.

**Target:** Minecraft **1.21.1**, Fabric, Cobblemon **1.7.3**, Java **21**.

> **Development preview:** Compilation with Java 21 and a **production dedicated-server startup** using Minecraft 1.21.1 / Fabric 0.18.4 / Cobblemon 1.7.3 passed in [GitHub Actions #24](https://github.com/SilverWallDiscs/Cobblemon-Duo/actions/runs/39504515050). Two-player gameplay tests are still pending. **Not yet a stable release.**

## Why this exists

A duo is a permanent trainer partnership, not another party system. Trainers can explore together, combine Pokédex discoveries, level their bond, coordinate catches and take on a timed challenge.

Players with standard Cobblemon clients do **not** install Cobblemon Duo separately. No custom screens, asset downloads, custom packets or resource packs are required.

## Commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/duo invite <player>` | Everyone | Send an invitation to an online player |
| `/duo accept` | Everyone | Accept pending partnership |
| `/duo deny` | Everyone | Decline pending partnership |
| `/duo info` | Everyone | View partner, bond level and adventure statistics |
| `/duo challenge start` | Everyone in a duo | Invite partner to a Catch Sprint |
| `/duo challenge accept` | Everyone in a duo | Start an invited Catch Sprint |
| `/duo challenge deny` | Everyone in a duo | Decline a Catch Sprint |
| `/duo challenge status` | Everyone in a duo | View challenge progress |
| `/duo admin disband <name-or-uuid>` | OP level 2+ | End a duo, including when its members are offline |

**Important:** There is deliberately **no** `/duo leave` command. Only operators can disband partnerships.

## Native Cobblemon Pokédex sharing

The existing Cobblemon Pokédex is synchronized between duo partners. There is no additional /duo pokedex command or alternate Pokédex.

- Records from both original Pokédexes are **merged when a duo forms and when either member reconnects**.
- New encounter and catch updates are replicated through Cobblemon's server-side Pokédex API, **including to the offline partner's data store**.
- Merge preserves the strongest knowledge (seen / caught) and combines known forms, genders, shiny states and species aspects.
- Cobblemon's own UI is updated using its normal client sync mechanisms.
- Learned Pokédex entries stay with each player after an administrator disbands the duo.
- There is no rollback of already shared discoveries.

These behaviors have been implemented in source code, but still require server testing with Cobblemon 1.7.3. **Back up your world and Cobblemon player data before testing.**

## Cooperative gameplay

### Bond levels
- 50 bond levels with progressively higher XP requirements.
- Earn XP each eligible minute adventuring near your partner.
- Expedition milestone XP every 15 eligible minutes.
- No proximity XP if either partner stayed still during the last sampling interval.

### Catch synergy
- Catch different Pokémon species while the partner is nearby for bond XP.
- A second trainer contributing another species within the teamwork window creates a **Catch Combo** bonus.
- Daily species limits stop repeated captures of the same species from farming bond XP.

### Catch Sprint
- One member starts a challenge and the other accepts.
- Both must remain nearby when contributing captures.
- Each must catch **2 distinct species**, with **4 unique species across the duo**, within 10 minutes.
- Completing the goal grants **300 bonus bond XP** by default.
- A default 2-hour cooldown applies once the challenge starts.
- Active challenge progress and cooldowns survive server restarts.

## Server configuration

Generated automatically on first server initialization:

`config/cobblemon-duo.json`

Edit it while the server is offline, then restart. Options include proximity distance, invitation expiry, bond rewards, daily catch limits, challenge duration/reward/cooldown, and whether to share encounters, captures and old Pokédex records.

Persistent duo data is saved inside the world:
- `<world>/cobblemon-duo.json` — relationships and cached player names
- `<world>/cobblemon-duo-adventures.json` — bond XP and synergy counters
- `<world>/cobblemon-duo-challenges.json` — catch sprint progress/cooldowns

**Deleting these files will lose mod data.** Do not delete them while the server is running.

## Build (development)

Requires Java 21, Fabric Loader **0.17.2+** (tested with **0.18.4**), and Gradle. The repository currently has no Gradle wrapper; use:

```shell
gradle build
```

A GitHub Actions build workflow compiles the mod, uploads the runtime JAR, then starts a **real production dedicated Fabric server** with Cobblemon 1.7.3 to verify that the server boots. [Verified successful run #24](https://github.com/SilverWallDiscs/Cobblemon-Duo/actions/runs/39504515050). A boot test is not a multiplayer functional test.\n\nInstall the built **non-sources** JAR in the server's mods folder beside Cobblemon 1.7.3 and Fabric API. Clients still need their normal Cobblemon installation, but **do not need Cobblemon Duo**.

## Compatibility and test checklist

- [x] Gradle compilation successful against Cobblemon 1.7.3 Fabric
- [x] Clean dedicated-server startup using only Fabric, Cobblemon, dependencies and Cobblemon Duo on the server ([CI #24](https://github.com/SilverWallDiscs/Cobblemon-Duo/actions/runs/39504515050))
- [ ] Create duo, test permission-gated disband and offline disband
- [ ] Merge existing Pokédex records, including encountered/caught/forms/shiny
- [ ] Catch a Pokémon with partner offline, reconnect partner, verify native Pokédex sync
- [ ] Verify no recursion / duplicate update loops
- [ ] Test Catch Sprint rewards, expiration and restart persistence
- [ ] Test corrupted data recovery and large player Pokédexes

## License

No project license has been selected. All rights reserved unless the repository owner chooses a license.
