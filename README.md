# SectorRTP

High-performance `/rtp` plugin for Paper 1.21.4 designed for the
**[EndSectors](https://github.com/maksiosmf/EndSectors-NoToLowerCase)** multi-sector
(Redis/NATS) setup.

> Built on top of [`maksiosmf/EndSectors-NoToLowerCase`](https://github.com/maksiosmf/EndSectors-NoToLowerCase),
> which is itself a fork of the original
> [Endixon/EndSectors](https://github.com/Endixon/EndSectors) project.
> SectorRTP reuses the public `SectorsAPI` of that plugin – it does not modify
> or redistribute its source.

## Features

- Picks an **online** sector first (1-4), then random coordinates inside that
  sector's bounds — guaranteed-valid targets only.
- Async safe-location search (chunks loaded via Paper `getChunkAtAsync`).
- Cross-sector teleport via the public `SectorsAPI`, with the well-known
  **fallback bug** protected against:
  - destination saved to `UserProfile.setLocationAndSave` *before* the NATS
    transfer packet,
  - `UserProfile.activateTransferOffset` to nudge the player away from spawn
    on arrival,
  - configurable `pending-lock-ms` to block double `/rtp` while the transfer
    round-trip is in flight,
  - `preserveCoordinates=true` so the destination spawns at the random
    location rather than the previous one.
- Per-sector safe-location cache (TTL).
- Cooldown, BossBar+actionbar countdown, movement-cancel, particles, sounds,
  titles — all configurable.
- MiniMessage everywhere, PlaceholderAPI integration.
- Custom `SectorRandomTeleportEvent` + cancellable `SectorRandomTeleportPreEvent`.
- Bukkit-service-registered public `SectorRTPAPI`.
- bStats metrics + GitHub Releases update checker.
- Graceful standalone fallback when EndSectors is missing.

## Requirements

| Software       | Version            |
|----------------|--------------------|
| Paper          | **1.21.4** or later |
| Java           | **21+**            |
| EndSectors     | optional (recommended) |
| PlaceholderAPI | optional           |
| ProtocolLib    | optional (pulled in by EndSectors) |

## Build

The project uses Gradle Kotlin DSL.

```bash
git clone https://github.com/maksiosmf/SectorRTP.git
cd SectorRTP

# (optional) drop the compiled EndSectors-paper.jar into ./libs so the
# compile-only classpath has access to its types. The plugin uses reflection
# either way, so this is only needed for the IDE.
mkdir -p libs
cp /path/to/EndSectors-paper-1.7.x.jar libs/

# Build the fat jar (output: build/libs/SectorRTP-1.0.0.jar)
./gradlew build
```

## Deployment

SectorRTP is a **Paper** plugin – install it on every Paper server in the
EndSectors network where `/rtp` should be runnable, **not** on the proxy.

| Server                                | Install? | Why                                                       |
|---------------------------------------|----------|-----------------------------------------------------------|
| Proxy (Velocity / BungeeCord)         | ❌       | Wrong runtime – it's a Paper plugin.                      |
| Spawn (`SectorType.SPAWN`)            | ✅       | So players on spawn can `/rtp` into a survival sector.    |
| Survival sectors (`sector_1`…N)       | ✅       | So players can `/rtp` between sectors mid-game.           |
| Queue / AFK servers                   | ❌       | Players there can't teleport anyway.                      |

### How it works across servers

Every Paper instance running EndSectors keeps an in-memory snapshot of **all**
sectors in the network – synchronised via the NATS heartbeat. SectorRTP reads
that snapshot, so each server independently knows about `sector_1..sector_N`
and the pool selection works identically everywhere.

| Scenario                                          | Path taken                                                                                                       |
|---------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| Picked sector == current sector                   | Local `teleportAsync` – no network traffic.                                                                      |
| Picked sector lives on another physical server    | `UserProfile.setLocationAndSave` → `activateTransferOffset` → NATS `PacketRequestTeleportSector` → cross-server. |

### Configuration consistency

`config.yml` should be **identical** on every Paper server – especially
`sectors.pool`, `cooldown` and `cross-sector.pending-lock-ms`. If servers
disagree about the pool, players see different candidate lists depending on
which sector they happen to be on. The easiest pattern is one config file
deployed via your usual tooling (Git, Ansible, rsync, …).

### Example layout

```text
proxy/                        # Velocity – only EndSectors-Proxy
spawn/plugins/                # Paper – EndSectors + SectorRTP + PlaceholderAPI
sector_1/plugins/             # Paper – EndSectors + SectorRTP + PlaceholderAPI
sector_2/plugins/             # Paper – EndSectors + SectorRTP + PlaceholderAPI
sector_3/plugins/             # Paper – EndSectors + SectorRTP + PlaceholderAPI
sector_4/plugins/             # Paper – EndSectors + SectorRTP + PlaceholderAPI
queue/plugins/                # Paper – EndSectors only (no SectorRTP)
afk/plugins/                  # Paper – EndSectors only (no SectorRTP)
```

## Configuration

### Sector pool (the 1-4 setup)

```yaml
sectors:
  mode: "POOL"
  pool:
    - "sector_1"
    - "sector_2"
    - "sector_3"
    - "sector_4"
  require-online: true
```

### Cross-sector / Redis hardening

```yaml
cross-sector:
  activate-transfer-offset: true   # UserProfile.activateTransferOffset()
  pending-lock-ms: 7000            # block re-/rtp during NATS round trip
  preserve-coordinates: true       # destination uses random location
  force-transfer: true             # bypass spawn-to-spawn block
```

### Cooldown / safety

```yaml
cooldown: 60
max-attempts: 30
sector-margin: 32
countdown-seconds: 3
safety:
  min-y: 40
  max-y: 200
  required-air-above: 2
  unsafe-blocks: [ LAVA, WATER, FIRE, CACTUS, MAGMA_BLOCK, ... ]
```

## Commands

| Command                | Permission                | Description                  |
|------------------------|--------------------------|------------------------------|
| `/rtp`                 | `sectorrtp.use`          | Teleport the caller.         |
| `/rtp reload`          | `sectorrtp.admin`        | Reload `config.yml` + `messages.yml`. |
| `/rtp force <player>`  | `sectorrtp.admin`        | Force-RTP another player.    |

Aliases: `/randomtp`, `/randomteleport`, `/srtp`.

## Permissions

- `sectorrtp.use` — base permission for `/rtp`.
- `sectorrtp.admin` — implies all bypasses below.
- `sectorrtp.bypass.cooldown`
- `sectorrtp.bypass.movement`

## API

The plugin exposes a `SectorRTPAPI` via the Bukkit ServicesManager and via
the `SectorRTPProvider` static accessor. See
[`example/ExampleSectorRTPConsumer.java`](example/ExampleSectorRTPConsumer.java)
for a complete example.

```java
SectorRTPAPI api = SectorRTPProvider.get();
api.requestRtp(player).thenAccept(result -> {
    if (result.success()) {
        getLogger().info(player.getName() + " RTP'd to " + result.sector());
    }
});
```

## License

MIT — see `LICENSE`.
