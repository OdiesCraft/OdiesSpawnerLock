package org.example.OdiesSpawnerLock;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.EntityType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.UUID;
import java.util.HashSet;

public class OdiesSpawnerLock extends JavaPlugin implements Listener {

    private final HashMap<String, UUID> spawnerOwners = new HashMap<>();
    private Connection connection;
    private final String DB_URL = "jdbc:sqlite:spawnerlock.db";
    private final HashSet<UUID> bypassPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupDatabase();
        loadSpawnerOwners();
        Bukkit.getPluginManager().registerEvents(this, this);
        String logMsg = "==============================\n" +
                "   \u2728\u2728 \u001B[32mOdiesSpawnerLock AKTİF!\u001B[0m \u2728\u2728\n" +
                "==============================";
        getLogger().info(logMsg);
    }

    private String getDatabasePath() {
        return getDataFolder().getAbsolutePath() + "/spawnerlock.db";
    }

    private void setupDatabase() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDatabasePath());
            Statement stmt = connection.createStatement();
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS spawner_owners (loc_key TEXT PRIMARY KEY, uuid TEXT NOT NULL)");
            stmt.close();
        } catch (SQLException e) {
            getLogger().severe("Veritabanı bağlantısı kurulamadı: " + e.getMessage());
        }
    }

    private void loadSpawnerOwners() {
        try {
            if (connection == null) return;
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT loc_key, uuid FROM spawner_owners");
            while (rs.next()) {
                String locKey = rs.getString("loc_key");
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                spawnerOwners.put(locKey, uuid);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            getLogger().severe("Spawner sahipleri yüklenemedi: " + e.getMessage());
        }
    }

    @EventHandler
    public void onSpawnerPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();

        if (block.getType() == Material.SPAWNER) {
            String locKey = serializeLocation(block);
            spawnerOwners.put(locKey, player.getUniqueId());
            saveSpawnerOwnerToDB(locKey, player.getUniqueId());
            String msg = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.spawner_locked", "&aSpawner koyuldu ve kilitlendi. Sadece sen kırabilirsin!"));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        }
    }

    @EventHandler
    public void onSpawnerBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER) return;

        String locKey = serializeLocation(block);
        UUID ownerUUID = spawnerOwners.get(locKey);
        Player player = event.getPlayer();

        boolean isBypass = player.isOp() && bypassPlayers.contains(player.getUniqueId());

        if (!isBypass && (ownerUUID == null || !ownerUUID.equals(player.getUniqueId()))) {
            Player owner = Bukkit.getPlayer(ownerUUID);
            event.setCancelled(true);

            String notOwnerMsg = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.not_owner", "&cBu spawner sana ait değil!"));
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(notOwnerMsg));

            if (owner != null && owner.isOnline()) {
                String ownerMsg = getConfig().getString("messages.spawner_owner", "&eBu spawner &b%player%&e'a ait.");
                if (ownerMsg != null) {
                    ownerMsg = ChatColor.translateAlternateColorCodes('&', ownerMsg.replace("%player%", owner.getName()));
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(ownerMsg));
                }
            }
            return;
        }

        event.setDropItems(false); // Sadece vanilla drop'u engelle, başka bir şey yapma
        spawnerOwners.remove(locKey);
        removeSpawnerOwnerFromDB(locKey);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.isOp()) {
            String msg = ChatColor.translateAlternateColorCodes('&', "&a&l\u2728 OdiesSpawnerLock AKTİF! \u2728");
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("odiesspawnerlock")) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                String msg = ChatColor.translateAlternateColorCodes('&', "&a&l\u2728 OdiesSpawnerLock AKTİF! \u2728");
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
            } else {
                sender.sendMessage(ChatColor.GREEN + "OdiesSpawnerLock aktif!");
            }
            return true;
        }
        // /oslock bypass komutu
        if (command.getName().equalsIgnoreCase("oslock")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("bypass") && sender instanceof Player) {
                Player player = (Player) sender;
                if (!player.isOp()) {
                    player.sendMessage(ChatColor.RED + "Bu komutu sadece OP'ler kullanabilir!");
                    return true;
                }
                if (bypassPlayers.contains(player.getUniqueId())) {
                    bypassPlayers.remove(player.getUniqueId());
                    player.sendMessage(ChatColor.YELLOW + "Bypass modu kapatıldı. Artık sadece kendi spawnerlarını kırabilirsin.");
                } else {
                    bypassPlayers.add(player.getUniqueId());
                    player.sendMessage(ChatColor.GREEN + "Bypass modu aktif! Artık tüm spawnerları kırabilirsin.");
                }
                return true;
            }
        }
        return false;
    }

    private String serializeLocation(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            getLogger().severe("Veritabanı bağlantısı kapatılamadı: " + e.getMessage());
        }
    }

    private void saveSpawnerOwnerToDB(String locKey, UUID uuid) {
        try {
            if (connection == null) return;
            PreparedStatement ps = connection.prepareStatement("REPLACE INTO spawner_owners (loc_key, uuid) VALUES (?, ?)");
            ps.setString(1, locKey);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            getLogger().severe("Spawner kaydedilemedi: " + e.getMessage());
        }
    }

    private void removeSpawnerOwnerFromDB(String locKey) {
        try {
            if (connection == null) return;
            PreparedStatement ps = connection.prepareStatement("DELETE FROM spawner_owners WHERE loc_key = ?");
            ps.setString(1, locKey);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            getLogger().severe("Spawner silinemedi: " + e.getMessage());
        }
    }
}
