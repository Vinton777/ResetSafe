# ResetSafe

ResetSafe moves returning players to a safe location when the resource world they left in has been reset. The move happens while Paper selects the join location, before the player appears in the old world.

![ResetSafe icon](assets/icon.png)

## Supported servers

Paper and Folia, Minecraft **1.21 through 26.3** (including all stable 1.21.x and 26.x releases up to 26.3). Java 21 is required for Minecraft 1.21; newer server versions may require newer Java. Purpur should work because it is based on Paper, but is not separately tested. Spigot/Bukkit are not supported.

## Setup

1. Put the JAR in `plugins/` and start the server once.
2. Edit `plugins/ResetSafe/config.yml`: list the exact names of every resettable world in `resource-worlds`, and choose a permanent `safe-world` (default: `world`).
3. Restart or run `/resetsafe reload`.
4. Optionally stand at a safe location and run `/resetsafe setspawn`. This saves precise coordinates instead of using the safe world's spawn.

```yaml
resource-worlds:
  - resource
  - resource_nether
safe-world: world
safe-location:
  use-world-spawn: true
```

The safe world must be loaded and must not appear in `resource-worlds`. ResetSafe disables itself at startup if this is invalid. Players begin being protected after the plugin has observed them in a listed world. Existing offline player positions from before installation cannot be identified retroactively.

## How reset detection works

ResetSafe records each listed world's UUID and the last listed world visited by each player. When a world is recreated with a new UUID, it increments that world's reset generation. On the player's next login, if their saved location is still in that world and their saved generation is older, it changes their spawn location to the configured safe location. Their inventory and other player data are untouched.

Some reset tools preserve the world's UUID. In that case, run `/resetsafe mark <world>` **after each reset**, before players rejoin. You can put that command in your reset script or console automation. `mark` increments the generation even if the UUID did not change. If the UUID changed, auto detection is already sufficient; running `mark` once more is harmless.

If you reset a world while players are still online in it, evacuate them first. This plugin protects returning players at login; it does not manage online resets.

## Commands

All commands require `resetsafe.admin` (given to operators by default).

| Command | Purpose |
| --- | --- |
| `/resetsafe status` | Show watched worlds and their generations. |
| `/resetsafe mark <world>` | Explicitly mark a reset. |
| `/resetsafe setspawn` | Save your current location as the safe destination. |
| `/resetsafe reload` | Reload configuration. |

State is saved atomically in `plugins/ResetSafe/state.properties`. Keep this file when restarting or upgrading, otherwise prior world/player generations are lost. Back it up with other plugin data.

## Build

Run `mvn package` with JDK 21 or newer. The JAR is produced in `target/resetsafe-1.0.0.jar`.

## License

MIT.
