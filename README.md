# Cobblemon Duo

A **server-side** cooperative trainer partnership mod for **Minecraft 1.21.1 / Fabric**, targeting **Cobblemon 1.7.3**.

> Development status: early prototype. Not yet compiled or tested in a running server. Do not distribute as a stable release.

## Current features
- `/duo invite <player>` — invite another online player (60-second expiry)
- `/duo accept`, `/duo deny`, `/duo info`
- Only operators (permission level 2+) can disband a duo with `/duo admin disband <online-player>`; players cannot leave or delete their duo.
- One partnership per player, stored by UUID
- Server-world persistent partnerships and exploration progress
- Shared bond XP and 50 bond levels
- Exploration checks every minute: both players online, same dimension, within 64 blocks, and both moved at least four blocks since last sample
- Every 15 eligible minutes completes a cooperative exploration milestone
- No client-side installation, assets or custom networking

## Build
Requires Java 21 and Gradle compatible with Fabric Loom. The project currently does not include the Gradle wrapper. Run `gradle build` after installing Gradle and validating the configured dependencies. A successful build has **not** yet been verified.

## Design notes
- Cobblemon is currently a required mod in metadata, but Pokémon capture and battle events are **not yet integrated**.
- Movement sampling is intended to discourage simple stationary AFK farming; it is not a comprehensive anti-cheat solution.
- Leaving a duo clears its adventure progression.
- Current storage uses JSON in the world save directory.
- Progression values are currently hardcoded; configuration and admin tools are planned.
- Do not assume compatibility with all Fabric/Cobblemon builds until tested.

## Shared Pokédex specification (not implemented yet)
- Track the union of species registered by either partner while the duo exists.
- Each species counts only once for the duo, regardless of which member registered it.
- Store shared discoveries per duo persistently by stable species identifiers.
- Provide `/duo pokedex` progress and contribution counts.
- Do not modify or overwrite either player's personal Pokédex.
- Use Cobblemon 1.7.3 server-side capture/discovery events after API verification.
- Administrator disbanding should clear or archive duo data according to a future configurable retention policy.

## Planned gameplay
- Co-op Pokédex discovery challenges
- Partner Pokémon and type-themed adventure objectives
- Cooperative trainer battle achievements
- Configurable progression and admin recovery commands
- Anti-exploit testing and automated builds

## License
No license has been selected. All rights reserved until the repository owner adds one.
