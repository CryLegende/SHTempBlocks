package org.Cry_Legende.sHTempBlocks;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.block.Block;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Display;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.io.File;
import java.io.IOException;

public class SHTempBlocks extends JavaPlugin implements Listener {

    private int delay;
    private String hologramFormat;
    private List<String> regions;
    private List<String> blacklist;
    private List<TempBlockTask> activeTasks = new ArrayList<>();
    private File tempBlocksFile;
    private FileConfiguration tempBlocksConfig;

    @Override
    public void onEnable() {
        // Chargement de la configuration par défaut
        saveDefaultConfig();
        FileConfiguration config = getConfig();
        delay = config.getInt("Delay", 30); // Durée avant suppression (en secondes)
        hologramFormat = config.getString("hologramFormat", "&d⚠ %time%s");
        regions = config.getStringList("Regions"); // Format "monde:region"
        blacklist = config.getStringList("Blacklist");

        // Initialisation du fichier YAML pour persister les blocs temporaires
        tempBlocksFile = new File(getDataFolder(), "temp_blocks.yml");
        tempBlocksConfig = YamlConfiguration.loadConfiguration(tempBlocksFile);
        cleanUpTempBlocks();

        // Enregistrement des listeners
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (TempBlockTask task : activeTasks) {
            task.block.setType(Material.AIR);
            if (task.hologram != null && !task.hologram.isDead()) {
                task.hologram.remove();
            }
            task.cancel();
        }
        activeTasks.clear();
    }

    // Annule le drop des items lorsque le bloc est cassé par le plugin
    @EventHandler
    public void onBlockDrop(BlockDropItemEvent event) {
        Block eventBlock = event.getBlock();
        for (TempBlockTask task : activeTasks) {
            if (task.block.getLocation().equals(eventBlock.getLocation())) {
                event.setCancelled(true);
                break;
            }
        }
    }

    // Intercepte le placement de bloc dans les zones autorisées
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();
        Player player = event.getPlayer();

        // On n'intervient que si le bloc est posé dans une zone autorisée
        if (!isInAllowedRegion(loc)) {
            return;
        }

        // Récupérer le type du bloc placé
        Material placedBlockType = event.getBlockPlaced().getType();

        // Annuler l'événement pour empêcher la consommation du bloc
        event.setCancelled(true);

        // Si le bloc est blacklisté, ne rien faire
        if (blacklist.contains(event.getBlockPlaced().getType().toString())) {
            return;
        }

        // Utiliser un léger délai pour placer le bloc après l'annulation
        new BukkitRunnable() {
            @Override
            public void run() {
                block.getWorld().getBlockAt(block.getLocation()).setType(placedBlockType);
            }
        }.runTaskLater(this, 1L); // Délai de 1 tick (50 ms)


        // Créer un hologramme (optionnel) pour afficher le timer au-dessus du bloc
        Location holoLoc = loc.clone().add(0.5, 0.5, 0.5);
        TextDisplay hologram = block.getWorld().spawn(holoLoc, TextDisplay.class);
        hologram.setBillboard(Display.Billboard.CENTER);
        hologram.setShadowed(true);
        hologram.setSeeThrough(true);
        hologram.setBackgroundColor(Color.fromARGB(0x00000000)); // Fond transparent
        updateHologram(hologram, delay);

        // Sauvegarde des données du bloc dans le fichier YAML
        String key = loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
        tempBlocksConfig.set(key + ".world", loc.getWorld().getName());
        tempBlocksConfig.set(key + ".x", loc.getBlockX());
        tempBlocksConfig.set(key + ".y", loc.getBlockY());
        tempBlocksConfig.set(key + ".z", loc.getBlockZ());
        tempBlocksConfig.set(key + ".hologram", hologram.getUniqueId().toString());
        saveTempBlocksConfig();

        // Démarrage de la tâche qui supprimera le bloc après le délai
        TempBlockTask task = new TempBlockTask(block, hologram, delay);
        task.runTaskTimer(this, 0L, 20L); // Mise à jour chaque seconde
        activeTasks.add(task);
    }

    // Vérifie si une location se trouve dans une région autorisée (format "monde:region")
    private boolean isInAllowedRegion(Location loc) {
        for (String regionSpec : regions) {
            String[] parts = regionSpec.split(":");
            if (parts.length != 2) continue;
            String worldName = parts[0];
            String regionId = parts[1];
            if (!loc.getWorld().getName().equalsIgnoreCase(worldName)) continue;

            World weWorld = BukkitAdapter.adapt(loc.getWorld());
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager regionManager = container.get(weWorld);
            if (regionManager == null) continue;

            BlockVector3 blockVector = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            ApplicableRegionSet regionSet = regionManager.getApplicableRegions(blockVector);
            for (ProtectedRegion region : regionSet) {
                if (region.getId().equalsIgnoreCase(regionId)) {
                    return true;
                }
            }
        }
        return false;
    }

    // Met à jour le texte de l'hologramme affichant le temps restant
    private void updateHologram(TextDisplay hologram, int timeLeft) {
        String text = hologramFormat.replace("%time%", String.valueOf(timeLeft));
        String coloredText = ChatColor.translateAlternateColorCodes('&', text);
        hologram.setText(coloredText);
    }

    // Nettoie les blocs et hologrammes persistants lors du démarrage
    private void cleanUpTempBlocks() {
        for (String key : tempBlocksConfig.getKeys(false)) {
            String worldName = tempBlocksConfig.getString(key + ".world");
            int x = tempBlocksConfig.getInt(key + ".x");
            int y = tempBlocksConfig.getInt(key + ".y");
            int z = tempBlocksConfig.getInt(key + ".z");
            String hologramUUID = tempBlocksConfig.getString(key + ".hologram");

            org.bukkit.World world = getServer().getWorld(worldName);
            if (world != null) {
                Block block = world.getBlockAt(x, y, z);
                block.setType(Material.AIR);
                if (hologramUUID != null) {
                    UUID uuid = UUID.fromString(hologramUUID);
                    world.getEntities().stream()
                        .filter(e -> e.getUniqueId().equals(uuid))
                        .findFirst()
                        .ifPresent(Entity::remove);
                }
            }
        }
        tempBlocksConfig = new YamlConfiguration();
        saveTempBlocksConfig();
    }

    // Sauvegarde le fichier YAML contenant les blocs temporaires
    private void saveTempBlocksConfig() {
        try {
            tempBlocksConfig.save(tempBlocksFile);
        } catch (IOException e) {
            getLogger().warning("Erreur lors de la sauvegarde de temp_blocks.yml");
        }
    }

    // Tâche interne qui gère le compte à rebours et la suppression du bloc
    private class TempBlockTask extends BukkitRunnable {
        private Block block;
        private TextDisplay hologram;
        private int timeLeft;

        public TempBlockTask(Block block, TextDisplay hologram, int initialTime) {
            this.block = block;
            this.hologram = hologram;
            this.timeLeft = initialTime;
        }

        @Override
        public void run() {
            timeLeft--;
            if (timeLeft <= 0) {
                // Supprime le bloc avec animation de casse sans faire drop
                block.setType(Material.AIR);
                if (hologram != null && !hologram.isDead()) {
                    hologram.remove();
                }
                String key = block.getWorld().getName() + "_" + block.getX() + "_" + block.getY() + "_" + block.getZ();
                tempBlocksConfig.set(key, null);
                saveTempBlocksConfig();
                activeTasks.remove(this);
                cancel();
            } else {
                updateHologram(hologram, timeLeft);
            }
        }
    }
}
