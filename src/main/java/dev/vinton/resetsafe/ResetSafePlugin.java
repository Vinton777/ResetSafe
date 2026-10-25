package dev.vinton.resetsafe;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ResetSafePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private record WorldState(UUID id, long generation) { }
    private record PlayerState(String world, long generation) { }

    private final Object stateLock = new Object();
    private final Map<String, WorldState> worlds = new HashMap<>();
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Set<UUID> notifyOnJoin = ConcurrentHashMap.newKeySet();
    private volatile Set<String> protectedWorlds = Set.of();
    private volatile Location safeLocation;
    private Path stateFile;
    private boolean stateReadable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = getDataFolder().toPath().resolve("state.properties");
        if (!readState()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!reloadSettings()) {
            getLogger().severe("Invalid safe destination. Set safe-world to a loaded, non-resource world.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        try {
            Class.forName("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent", false, getClassLoader());
            getServer().getPluginManager().registerEvents(new ModernSpawnListener(this), this);
            getLogger().info("Using Paper's asynchronous spawn event.");
        } catch (ClassNotFoundException missing) {
            getServer().getPluginManager().registerEvents(new LegacySpawnListener(this), this);
            getLogger().info("Using the Paper 1.21 legacy spawn event.");
        }
        PluginCommand command = getCommand("resetsafe");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getLogger().info("Watching " + protectedWorlds.size() + " resource world(s).");
    }

    @Override
    public void onDisable() {
        if (stateReadable) synchronized (stateLock) { writeState(); }
    }

    private boolean reloadSettings() {
        reloadConfig();
        Set<String> names = new HashSet<>(getConfig().getStringList("resource-worlds"));
        String destination = getConfig().getString("safe-world", "world");
        World safeWorld = Bukkit.getWorld(destination);
        if (safeWorld == null || names.contains(destination)) return false;

        Location location;
        if (getConfig().getBoolean("safe-location.use-world-spawn", true)) {
            location = safeWorld.getSpawnLocation().add(0.5, 0, 0.5);
        } else {
            location = new Location(safeWorld,
                    getConfig().getDouble("safe-location.x"),
                    getConfig().getDouble("safe-location.y"),
                    getConfig().getDouble("safe-location.z"),
                    (float) getConfig().getDouble("safe-location.yaw"),
                    (float) getConfig().getDouble("safe-location.pitch"));
        }
        protectedWorlds = Set.copyOf(names);
        safeLocation = location;
        for (World world : Bukkit.getWorlds()) observeWorld(world);
        return true;
    }

    private void observeWorld(World world) {
        String name = world.getName();
        if (!protectedWorlds.contains(name)) return;
        synchronized (stateLock) {
            WorldState previous = worlds.get(name);
            UUID id = world.getUID();
            if (previous == null) {
                worlds.put(name, new WorldState(id, 0));
                writeState();
            } else if (!previous.id().equals(id)) {
                long generation = previous.generation() + 1;
                worlds.put(name, new WorldState(id, generation));
                writeState();
                getLogger().warning("World " + name + " has a new UUID; reset generation " + generation + ".");
            }
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        observeWorld(event.getWorld());
    }

    private Location destinationFor(UUID id, Location original) {
        World originalWorld = original.getWorld();
        if (originalWorld == null) return null;
        boolean relocate;
        synchronized (stateLock) {
            PlayerState player = players.get(id);
            WorldState world = worlds.get(originalWorld.getName());
            relocate = player != null && world != null
                    && player.world().equals(originalWorld.getName())
                    && player.generation() < world.generation();
        }
        Location destination = safeLocation;
        if (!relocate || destination == null) return null;
        notifyOnJoin.add(id);
        return destination.clone();
    }

    private static final class ModernSpawnListener implements Listener {
        private final ResetSafePlugin plugin;
        private ModernSpawnListener(ResetSafePlugin plugin) { this.plugin = plugin; }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawn(AsyncPlayerSpawnLocationEvent event) {
            UUID id = event.getConnection().getProfile().getId();
            if (id == null) return;
            Location destination = plugin.destinationFor(id, event.getSpawnLocation());
            if (destination != null) event.setSpawnLocation(destination);
        }
    }

    private static final class LegacySpawnListener implements Listener {
        private final ResetSafePlugin plugin;
        private LegacySpawnListener(ResetSafePlugin plugin) { this.plugin = plugin; }

        @SuppressWarnings("removal")
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSpawn(PlayerSpawnLocationEvent event) {
            Location destination = plugin.destinationFor(event.getPlayer().getUniqueId(), event.getSpawnLocation());
            if (destination != null) event.setSpawnLocation(destination);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        remember(player);
        if (notifyOnJoin.remove(player.getUniqueId())) {
            player.sendMessage(ChatColor.YELLOW + getConfig().getString("message",
                    "Your resource world was reset. You were moved to safety."));
        }
    }

    @EventHandler
    public void onChangeWorld(PlayerChangedWorldEvent event) {
        remember(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remember(event.getPlayer());
        notifyOnJoin.remove(event.getPlayer().getUniqueId());
    }

    private void remember(Player player) {
        String worldName = player.getWorld().getName();
        UUID id = player.getUniqueId();
        synchronized (stateLock) {
            WorldState world = worlds.get(worldName);
            if (protectedWorlds.contains(worldName) && world != null) {
                PlayerState next = new PlayerState(worldName, world.generation());
                if (!next.equals(players.put(id, next))) writeState();
            } else if (players.remove(id) != null) {
                writeState();
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GOLD + "ResetSafe 1.0.0");
            synchronized (stateLock) {
                for (String name : protectedWorlds) {
                    WorldState state = worlds.get(name);
                    sender.sendMessage(ChatColor.GRAY + name + ": " +
                            (state == null ? "not loaded" : "generation " + state.generation()));
                }
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("mark") && args.length == 2) {
            String name = args[1];
            if (!protectedWorlds.contains(name)) {
                sender.sendMessage(ChatColor.RED + "World is not listed in resource-worlds.");
                return true;
            }
            synchronized (stateLock) {
                WorldState old = worlds.get(name);
                if (old == null) {
                    sender.sendMessage(ChatColor.RED + "World has not been loaded yet.");
                    return true;
                }
                long generation = old.generation() + 1;
                worlds.put(name, new WorldState(old.id(), generation));
                writeState();
                sender.sendMessage(ChatColor.GREEN + "Marked " + name + " reset generation " + generation + ".");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("setspawn")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Run this command in game.");
                return true;
            }
            Location location = player.getLocation();
            if (protectedWorlds.contains(location.getWorld().getName())) {
                sender.sendMessage(ChatColor.RED + "Safe spawn cannot be in a resource world.");
                return true;
            }
            getConfig().set("safe-world", location.getWorld().getName());
            getConfig().set("safe-location.use-world-spawn", false);
            getConfig().set("safe-location.x", location.getX());
            getConfig().set("safe-location.y", location.getY());
            getConfig().set("safe-location.z", location.getZ());
            getConfig().set("safe-location.yaw", location.getYaw());
            getConfig().set("safe-location.pitch", location.getPitch());
            saveConfig();
            safeLocation = location.clone();
            sender.sendMessage(ChatColor.GREEN + "Safe spawn saved.");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (reloadSettings()) sender.sendMessage(ChatColor.GREEN + "ResetSafe configuration reloaded.");
            else sender.sendMessage(ChatColor.RED + "Invalid safe destination; previous settings remain active.");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "Usage: /resetsafe <status|mark <world>|setspawn|reload>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("status", "mark", "setspawn", "reload").stream()
                .filter(value -> value.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("mark")) return protectedWorlds.stream()
                .filter(value -> value.startsWith(args[1])).sorted().toList();
        return new ArrayList<>();
    }

    private boolean readState() {
        if (!Files.exists(stateFile)) {
            stateReadable = true;
            return true;
        }
        Properties data = new Properties();
        try (InputStream input = Files.newInputStream(stateFile)) {
            data.load(input);
            for (String key : data.stringPropertyNames()) {
                if (key.startsWith("w.") && key.endsWith(".id")) {
                    String encoded = key.substring(2, key.length() - 3);
                    String name = new String(Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
                    UUID id = UUID.fromString(data.getProperty(key));
                    long generation = Long.parseLong(data.getProperty("w." + encoded + ".gen", "0"));
                    worlds.put(name, new WorldState(id, generation));
                } else if (key.startsWith("p.") && key.endsWith(".world")) {
                    String uuid = key.substring(2, key.length() - 6);
                    long generation = Long.parseLong(data.getProperty("p." + uuid + ".gen", "0"));
                    players.put(UUID.fromString(uuid), new PlayerState(data.getProperty(key), generation));
                }
            }
            stateReadable = true;
            return true;
        } catch (Exception error) {
            getLogger().severe("Could not read state.properties: " + error.getMessage());
            return false;
        }
    }

    private void writeState() {
        Properties data = new Properties();
        for (Map.Entry<String, WorldState> entry : worlds.entrySet()) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            data.setProperty("w." + encoded + ".id", entry.getValue().id().toString());
            data.setProperty("w." + encoded + ".gen", Long.toString(entry.getValue().generation()));
        }
        for (Map.Entry<UUID, PlayerState> entry : players.entrySet()) {
            String prefix = "p." + entry.getKey();
            data.setProperty(prefix + ".world", entry.getValue().world());
            data.setProperty(prefix + ".gen", Long.toString(entry.getValue().generation()));
        }
        Path temporary = stateFile.resolveSibling("state.properties.tmp");
        try {
            Files.createDirectories(stateFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                data.store(output, "ResetSafe state - managed automatically");
            }
            try {
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            getLogger().severe("Could not save state.properties: " + error.getMessage());
        }
    }
}
