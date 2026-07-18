package net.avelium.aveliumbuild;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

public class AveliumBuild extends JavaPlugin implements Listener, TabCompleter {

    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<UUID, Deque<List<BlockSnapshot>>> undoHistory = new HashMap<>();
    private final Map<UUID, Deque<List<BlockSnapshot>>> redoHistory = new HashMap<>();
    private final Map<UUID, ClipboardData> clipboards = new HashMap<>();
    private final Map<String, Material> ruBlocks = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        registerRussianBlocks();
        getServer().getPluginManager().registerEvents(this, this);
        String[] cmds = {"wand","pos1","pos2","set","replace","walls","outline","sphere","cyl","pyramid","copy","paste","undo","redo","count","size","help"};
        for (String c : cmds) {
            if (getCommand(c) != null) getCommand(c).setTabCompleter(this);
        }
        getLogger().info("AveliumBuild запущен! Русских блоков: " + ruBlocks.size());
    }

    private boolean hasAccess(Player p) {
        return p.isOp() || p.hasPermission("aveliumbuild.use");
    }

    private String msg(String path, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "");
        String m = getConfig().getString("messages." + path, path);
        String full = prefix + m;
        for (int i = 0; i < replacements.length - 1; i += 2) {
            full = full.replace("%" + replacements[i] + "%", replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', full);
    }

    private Material parseBlock(String input) {
        if (input == null) return null;
        String lower = input.toLowerCase().replace(" ", "_");
        if (ruBlocks.containsKey(lower)) return ruBlocks.get(lower);
        Material m = Material.matchMaterial(lower);
        if (m != null && m.isBlock()) return m;
        return null;
    }

    // ============ EVENTS ============

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!hasAccess(p)) return;
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType() != Material.WOODEN_AXE) return;
        if (e.getClickedBlock() == null) return;

        e.setCancelled(true);
        Location loc = e.getClickedBlock().getLocation();

        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            pos1.put(p.getUniqueId(), loc);
            p.sendMessage(msg("pos1-set", "x", String.valueOf(loc.getBlockX()), "y", String.valueOf(loc.getBlockY()), "z", String.valueOf(loc.getBlockZ())));
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            pos2.put(p.getUniqueId(), loc);
            p.sendMessage(msg("pos2-set", "x", String.valueOf(loc.getBlockX()), "y", String.valueOf(loc.getBlockY()), "z", String.valueOf(loc.getBlockZ())));
        }
    }

    // ============ COMMANDS ============

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только для игроков!"); return true; }
        if (!hasAccess(p)) { p.sendMessage(msg("no-permission")); return true; }

        switch (cmd.getName().toLowerCase()) {
            case "wand" -> { p.getInventory().addItem(new ItemStack(Material.WOODEN_AXE)); p.sendMessage(msg("wand-given")); }
            case "pos1" -> {
                Location l = p.getLocation();
                pos1.put(p.getUniqueId(), l);
                p.sendMessage(msg("pos1-set", "x", String.valueOf(l.getBlockX()), "y", String.valueOf(l.getBlockY()), "z", String.valueOf(l.getBlockZ())));
            }
            case "pos2" -> {
                Location l = p.getLocation();
                pos2.put(p.getUniqueId(), l);
                p.sendMessage(msg("pos2-set", "x", String.valueOf(l.getBlockX()), "y", String.valueOf(l.getBlockY()), "z", String.valueOf(l.getBlockZ())));
            }
            case "set" -> cmdSet(p, args);
            case "replace" -> cmdReplace(p, args);
            case "walls" -> cmdWalls(p, args);
            case "outline" -> cmdOutline(p, args);
            case "sphere" -> cmdSphere(p, args);
            case "cyl" -> cmdCyl(p, args);
            case "pyramid" -> cmdPyramid(p, args);
            case "copy" -> cmdCopy(p);
            case "paste" -> cmdPaste(p);
            case "undo" -> cmdUndo(p);
            case "redo" -> cmdRedo(p);
            case "count" -> cmdCount(p, args);
            case "size" -> cmdSize(p);
            case "help" -> sendHelp(p);
        }
        return true;
    }

    private boolean checkRegion(Player p) {
        if (!pos1.containsKey(p.getUniqueId()) || !pos2.containsKey(p.getUniqueId())) {
            p.sendMessage(msg("no-selection"));
            return false;
        }
        return true;
    }

    // ============ SET ============
    private void cmdSet(Player p, String[] args) {
        if (args.length < 1) { p.sendMessage(msg("usage", "usage", "//set <блок>")); return; }
        if (!checkRegion(p)) return;
        Material mat = parseBlock(args[0]);
        if (mat == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }

        Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
        List<Location> locs = getCuboidLocations(l1, l2);
        performOperation(p, locs, loc -> mat);
    }

    // ============ REPLACE ============
    private void cmdReplace(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(msg("usage", "usage", "//replace <старый> <новый>")); return; }
        if (!checkRegion(p)) return;
        Material from = parseBlock(args[0]), to = parseBlock(args[1]);
        if (from == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }
        if (to == null) { p.sendMessage(msg("unknown-block", "block", args[1])); return; }

        Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
        List<Location> locs = getCuboidLocations(l1, l2);
        performOperationFiltered(p, locs, loc -> loc.getBlock().getType() == from, loc -> to);
    }

    // ============ WALLS ============
    private void cmdWalls(Player p, String[] args) {
        if (args.length < 1) { p.sendMessage(msg("usage", "usage", "//walls <блок>")); return; }
        if (!checkRegion(p)) return;
        Material mat = parseBlock(args[0]);
        if (mat == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }

        Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
        int minX = Math.min(l1.getBlockX(), l2.getBlockX()), maxX = Math.max(l1.getBlockX(), l2.getBlockX());
        int minY = Math.min(l1.getBlockY(), l2.getBlockY()), maxY = Math.max(l1.getBlockY(), l2.getBlockY());
        int minZ = Math.min(l1.getBlockZ(), l2.getBlockZ()), maxZ = Math.max(l1.getBlockZ(), l2.getBlockZ());
        List<Location> locs = new ArrayList<>();
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    if (x == minX || x == maxX || z == minZ || z == maxZ)
                        locs.add(new Location(p.getWorld(), x, y, z));
        performOperation(p, locs, loc -> mat);
    }

    // ============ OUTLINE ============
    private void cmdOutline(Player p, String[] args) {
        if (args.length < 1) { p.sendMessage(msg("usage", "usage", "//outline <блок>")); return; }
        if (!checkRegion(p)) return;
        Material mat = parseBlock(args[0]);
        if (mat == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }

        Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
        int minX = Math.min(l1.getBlockX(), l2.getBlockX()), maxX = Math.max(l1.getBlockX(), l2.getBlockX());
        int minY = Math.min(l1.getBlockY(), l2.getBlockY()), maxY = Math.max(l1.getBlockY(), l2.getBlockY());
        int minZ = Math.min(l1.getBlockZ(), l2.getBlockZ()), maxZ = Math.max(l1.getBlockZ(), l2.getBlockZ());
        List<Location> locs = new ArrayList<>();
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    int edges = 0;
                    if (x == minX || x == maxX) edges++;
                    if (y == minY || y == maxY) edges++;
                    if (z == minZ || z == maxZ) edges++;
                    if (edges >= 2) locs.add(new Location(p.getWorld(), x, y, z));
                }
        performOperation(p, locs, loc -> mat);
    }

    // ============ SPHERE ============
    private void cmdSphere(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(msg("usage", "usage", "//sphere <блок> <радиус>")); return; }
        Material mat = parseBlock(args[0]);
        if (mat == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }
        int r;
        try { r = Integer.parseInt(args[1]); } catch (NumberFormatException e) { p.sendMessage(msg("usage", "usage", "//sphere <блок> <радиус>")); return; }

        Location c = p.getLocation();
        List<Location> locs = new ArrayList<>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++)
                    if (x*x + y*y + z*z <= r*r)
                        locs.add(c.clone().add(x, y, z));
        performOperation(p, locs, loc -> mat);
    }

    // ============ CYL ============
    private void cmdCyl(Player p, String[] args) {
        if (args.length < 3) { p.sendMessage(msg("usage", "usage", "//cyl <блок> <радиус> <высота>")); return; }
        Material mat = parseBlock(args[0]);
        if (mat == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }
        int r, h;
        try { r = Integer.parseInt(args[1]); h = Integer.parseInt(args[2]); } catch (NumberFormatException e) { p.sendMessage(msg("usage", "usage", "//cyl <блок> <радиус> <высота>")); return; }

        Location c = p.getLocation();
        List<Location> locs = new ArrayList<>();
        for (int x = -r; x <= r; x++)
            for (int y = 0; y < h; y++)
                for (int z = -r; z <= r; z++)
                    if (x*x + z*z <= r*r)
                        locs.add(c.clone().add(x, y, z));
        performOperation(p, locs, loc -> mat);
    }

    // ============ PYRAMID ============
    private void cmdPyramid(Player p, String[] args) {
        if (args.length < 2) { p.sendMessage(msg("usage", "usage", "//pyramid <блок> <размер>")); return; }
        Material mat = parseBlock(args[0]);
        if (mat == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }
        int size;
        try { size = Integer.parseInt(args[1]); } catch (NumberFormatException e) { p.sendMessage(msg("usage", "usage", "//pyramid <блок> <размер>")); return; }

        Location c = p.getLocation();
        List<Location> locs = new ArrayList<>();
        for (int y = 0; y < size; y++) {
            int w = size - y;
            for (int x = -w; x <= w; x++)
                for (int z = -w; z <= w; z++)
                    locs.add(c.clone().add(x, y, z));
        }
        performOperation(p, locs, loc -> mat);
    }

    // ============ COPY / PASTE ============
    private void cmdCopy(Player p) {
        if (!checkRegion(p)) return;
        Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
        int minX = Math.min(l1.getBlockX(), l2.getBlockX()), maxX = Math.max(l1.getBlockX(), l2.getBlockX());
        int minY = Math.min(l1.getBlockY(), l2.getBlockY()), maxY = Math.max(l1.getBlockY(), l2.getBlockY());
        int minZ = Math.min(l1.getBlockZ(), l2.getBlockZ()), maxZ = Math.max(l1.getBlockZ(), l2.getBlockZ());

        ClipboardData cd = new ClipboardData();
        cd.origin = p.getLocation().clone();
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = p.getWorld().getBlockAt(x, y, z);
                    cd.blocks.add(new ClipboardBlock(x - cd.origin.getBlockX(), y - cd.origin.getBlockY(), z - cd.origin.getBlockZ(), b.getBlockData().clone()));
                }
        clipboards.put(p.getUniqueId(), cd);
        p.sendMessage(msg("copy-done", "count", String.valueOf(cd.blocks.size())));
    }

    private void cmdPaste(Player p) {
        ClipboardData cd = clipboards.get(p.getUniqueId());
        if (cd == null) { p.sendMessage(ChatColor.RED + "Буфер обмена пуст! Используйте //copy"); return; }

        Location base = p.getLocation();
        List<BlockSnapshot> snap = new ArrayList<>();
        for (ClipboardBlock cb : cd.blocks) {
            Location target = base.clone().add(cb.dx, cb.dy, cb.dz);
            Block b = target.getBlock();
            snap.add(new BlockSnapshot(b.getLocation(), b.getBlockData().clone()));
            b.setBlockData(cb.data, false);
        }
        pushUndo(p, snap);
        p.sendMessage(msg("paste-done", "count", String.valueOf(cd.blocks.size())));
    }

    // ============ UNDO / REDO ============
    private void cmdUndo(Player p) {
        Deque<List<BlockSnapshot>> stack = undoHistory.get(p.getUniqueId());
        if (stack == null || stack.isEmpty()) { p.sendMessage(msg("nothing-to-undo")); return; }
        List<BlockSnapshot> snap = stack.pop();
        List<BlockSnapshot> redo = new ArrayList<>();
        for (BlockSnapshot s : snap) {
            Block b = s.location.getBlock();
            redo.add(new BlockSnapshot(s.location, b.getBlockData().clone()));
            b.setBlockData(s.data, false);
        }
        redoHistory.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>()).push(redo);
        p.sendMessage(msg("undo-done", "count", String.valueOf(snap.size())));
    }

    private void cmdRedo(Player p) {
        Deque<List<BlockSnapshot>> stack = redoHistory.get(p.getUniqueId());
        if (stack == null || stack.isEmpty()) { p.sendMessage(msg("nothing-to-redo")); return; }
        List<BlockSnapshot> snap = stack.pop();
        List<BlockSnapshot> undo = new ArrayList<>();
        for (BlockSnapshot s : snap) {
            Block b = s.location.getBlock();
            undo.add(new BlockSnapshot(s.location, b.getBlockData().clone()));
            b.setBlockData(s.data, false);
        }
        undoHistory.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>()).push(undo);
        p.sendMessage(msg("redo-done", "count", String.valueOf(snap.size())));
    }

    // ============ COUNT / SIZE ============
    private void cmdCount(Player p, String[] args) {
        if (args.length < 1) { p.sendMessage(msg("usage", "usage", "//count <блок>")); return; }
        if (!checkRegion(p)) return;
        Material mat = parseBlock(args[0]);
        if (mat == null) { p.sendMessage(msg("unknown-block", "block", args[0])); return; }

        Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
        int count = 0;
        int minX = Math.min(l1.getBlockX(), l2.getBlockX()), maxX = Math.max(l1.getBlockX(), l2.getBlockX());
        int minY = Math.min(l1.getBlockY(), l2.getBlockY()), maxY = Math.max(l1.getBlockY(), l2.getBlockY());
        int minZ = Math.min(l1.getBlockZ(), l2.getBlockZ()), maxZ = Math.max(l1.getBlockZ(), l2.getBlockZ());
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    if (p.getWorld().getBlockAt(x, y, z).getType() == mat) count++;
        p.sendMessage(msg("count-result", "count", String.valueOf(count), "block", args[0]));
    }

    private void cmdSize(Player p) {
        if (!checkRegion(p)) return;
        Location l1 = pos1.get(p.getUniqueId()), l2 = pos2.get(p.getUniqueId());
        int sx = Math.abs(l1.getBlockX() - l2.getBlockX()) + 1;
        int sy = Math.abs(l1.getBlockY() - l2.getBlockY()) + 1;
        int sz = Math.abs(l1.getBlockZ() - l2.getBlockZ()) + 1;
        p.sendMessage(msg("size-result", "x", String.valueOf(sx), "y", String.valueOf(sy), "z", String.valueOf(sz), "total", String.valueOf(sx*sy*sz)));
    }

    private void sendHelp(Player p) {
        p.sendMessage(ChatColor.AQUA + "══════ AveliumBuild ══════");
        p.sendMessage(ChatColor.YELLOW + "//wand " + ChatColor.GRAY + "- топор выделения");
        p.sendMessage(ChatColor.YELLOW + "//pos1, //pos2 " + ChatColor.GRAY + "- точки");
        p.sendMessage(ChatColor.YELLOW + "//set <блок> " + ChatColor.GRAY + "- заполнить");
        p.sendMessage(ChatColor.YELLOW + "//replace <старый> <новый>");
        p.sendMessage(ChatColor.YELLOW + "//walls, //outline <блок>");
        p.sendMessage(ChatColor.YELLOW + "//sphere <блок> <радиус>");
        p.sendMessage(ChatColor.YELLOW + "//cyl <блок> <радиус> <высота>");
        p.sendMessage(ChatColor.YELLOW + "//pyramid <блок> <размер>");
        p.sendMessage(ChatColor.YELLOW + "//copy, //paste");
        p.sendMessage(ChatColor.YELLOW + "//undo, //redo");
        p.sendMessage(ChatColor.YELLOW + "//count <блок>, //size");
    }

    // ============ OPERATIONS ============
    private List<Location> getCuboidLocations(Location l1, Location l2) {
        List<Location> locs = new ArrayList<>();
        int minX = Math.min(l1.getBlockX(), l2.getBlockX()), maxX = Math.max(l1.getBlockX(), l2.getBlockX());
        int minY = Math.min(l1.getBlockY(), l2.getBlockY()), maxY = Math.max(l1.getBlockY(), l2.getBlockY());
        int minZ = Math.min(l1.getBlockZ(), l2.getBlockZ()), maxZ = Math.max(l1.getBlockZ(), l2.getBlockZ());
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                for (int z = minZ; z <= maxZ; z++)
                    locs.add(new Location(l1.getWorld(), x, y, z));
        return locs;
    }

    private void performOperation(Player p, List<Location> locs, java.util.function.Function<Location, Material> mapper) {
        performOperationFiltered(p, locs, loc -> true, mapper);
    }

    private void performOperationFiltered(Player p, List<Location> locs, java.util.function.Predicate<Location> filter, java.util.function.Function<Location, Material> mapper) {
        int batch = getConfig().getInt("settings.batch-size", 5000);
        int progressInterval = getConfig().getInt("settings.progress-interval", 50000);
        int total = locs.size();
        List<BlockSnapshot> snap = new ArrayList<>();

        if (total > batch) p.sendMessage(msg("operation-started", "count", String.valueOf(total)));

        new BukkitRunnable() {
            int index = 0;
            @Override
            public void run() {
                int end = Math.min(index + batch, total);
                for (int i = index; i < end; i++) {
                    Location loc = locs.get(i);
                    if (!filter.test(loc)) continue;
                    Block b = loc.getBlock();
                    Material target = mapper.apply(loc);
                    if (target == null || b.getType() == target) continue;
                    snap.add(new BlockSnapshot(loc, b.getBlockData().clone()));
                    b.setType(target, false);
                }
                index = end;
                if (index >= total) {
                    pushUndo(p, snap);
                    p.sendMessage(msg("operation-done", "count", String.valueOf(snap.size())));
                    cancel();
                    return;
                }
                if (index % progressInterval < batch && total > progressInterval) {
                    int percent = (int) ((index * 100L) / total);
                    p.sendMessage(msg("operation-progress", "done", String.valueOf(index), "total", String.valueOf(total), "percent", String.valueOf(percent)));
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void pushUndo(Player p, List<BlockSnapshot> snap) {
        if (snap.isEmpty()) return;
        undoHistory.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>()).push(snap);
        redoHistory.remove(p.getUniqueId());
    }

    // ============ TAB COMPLETE ============
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 0) return Collections.emptyList();
        String name = cmd.getName().toLowerCase();
        Set<String> blockCmds = Set.of("set", "replace", "walls", "outline", "sphere", "cyl", "pyramid", "count");
        if (!blockCmds.contains(name)) return Collections.emptyList();

        boolean expectBlock = args.length == 1 || (name.equals("replace") && args.length == 2);
        if (!expectBlock) return Collections.emptyList();

        String input = args[args.length - 1].toLowerCase();
        List<String> result = new ArrayList<>();
        for (String key : ruBlocks.keySet()) if (key.startsWith(input)) result.add(key);
        for (Material m : Material.values()) {
            if (m.isBlock()) {
                String en = m.name().toLowerCase();
                if (en.startsWith(input)) result.add(en);
            }
        }
        return result.stream().limit(50).collect(Collectors.toList());
    }

    // ============ HELPER CLASSES ============
    private static class BlockSnapshot {
        Location location; BlockData data;
        BlockSnapshot(Location l, BlockData d) { location = l; data = d; }
    }
    private static class ClipboardBlock {
        int dx, dy, dz; BlockData data;
        ClipboardBlock(int x, int y, int z, BlockData d) { dx = x; dy = y; dz = z; data = d; }
    }
    private static class ClipboardData {
        Location origin;
        List<ClipboardBlock> blocks = new ArrayList<>();
    }

    // ============ RUSSIAN BLOCK DICTIONARY ============
    private void registerRussianBlocks() {
        // Основные
        add("камень", Material.STONE);
        add("булыжник", Material.COBBLESTONE);
        add("мшистый_булыжник", Material.MOSSY_COBBLESTONE);
        add("гладкий_камень", Material.SMOOTH_STONE);
        add("земля", Material.DIRT);
        add("грязь", Material.MUD);
        add("трава", Material.GRASS_BLOCK);
        add("подзол", Material.PODZOL);
        add("микелий", Material.MYCELIUM);
        add("песок", Material.SAND);
        add("красный_песок", Material.RED_SAND);
        add("гравий", Material.GRAVEL);
        add("глина", Material.CLAY);
        add("снег", Material.SNOW_BLOCK);
        add("лед", Material.ICE);
        add("плотный_лед", Material.PACKED_ICE);
        add("голубой_лед", Material.BLUE_ICE);
        add("обсидиан", Material.OBSIDIAN);
        add("плачущий_обсидиан", Material.CRYING_OBSIDIAN);
        add("бедрок", Material.BEDROCK);
        add("коренная_порода", Material.BEDROCK);

        // Руды
        add("угольная_руда", Material.COAL_ORE);
        add("железная_руда", Material.IRON_ORE);
        add("золотая_руда", Material.GOLD_ORE);
        add("алмазная_руда", Material.DIAMOND_ORE);
        add("изумрудная_руда", Material.EMERALD_ORE);
        add("лазуритовая_руда", Material.LAPIS_ORE);
        add("редстоуновая_руда", Material.REDSTONE_ORE);
        add("медная_руда", Material.COPPER_ORE);
        add("незер_кварц", Material.NETHER_QUARTZ_ORE);
        add("незер_золото", Material.NETHER_GOLD_ORE);
        add("древний_обломок", Material.ANCIENT_DEBRIS);

        // Блоки руд
        add("уголь_блок", Material.COAL_BLOCK);
        add("железо_блок", Material.IRON_BLOCK);
        add("золото_блок", Material.GOLD_BLOCK);
        add("алмаз_блок", Material.DIAMOND_BLOCK);
        add("изумруд_блок", Material.EMERALD_BLOCK);
        add("лазурит_блок", Material.LAPIS_BLOCK);
        add("редстоун_блок", Material.REDSTONE_BLOCK);
        add("медь_блок", Material.COPPER_BLOCK);
        add("незерит_блок", Material.NETHERITE_BLOCK);

        // Древесина
        add("дуб", Material.OAK_PLANKS);
        add("береза", Material.BIRCH_PLANKS);
        add("ель", Material.SPRUCE_PLANKS);
        add("сосна", Material.SPRUCE_PLANKS);
        add("акация", Material.ACACIA_PLANKS);
        add("тропическое_дерево", Material.JUNGLE_PLANKS);
        add("темный_дуб", Material.DARK_OAK_PLANKS);
        add("мангр", Material.MANGROVE_PLANKS);
        add("вишня", Material.CHERRY_PLANKS);
        add("бамбук_доски", Material.BAMBOO_PLANKS);
        add("багровый", Material.CRIMSON_PLANKS);
        add("искаженный", Material.WARPED_PLANKS);

        // Брёвна
        add("дуб_бревно", Material.OAK_LOG);
        add("береза_бревно", Material.BIRCH_LOG);
        add("ель_бревно", Material.SPRUCE_LOG);
        add("акация_бревно", Material.ACACIA_LOG);
        add("тропическое_бревно", Material.JUNGLE_LOG);
        add("темный_дуб_бревно", Material.DARK_OAK_LOG);
        add("мангр_бревно", Material.MANGROVE_LOG);
        add("вишня_бревно", Material.CHERRY_LOG);

        // Листва
        add("дуб_листва", Material.OAK_LEAVES);
        add("береза_листва", Material.BIRCH_LEAVES);
        add("ель_листва", Material.SPRUCE_LEAVES);

        // Шерсть
        add("белая_шерсть", Material.WHITE_WOOL);
        add("оранжевая_шерсть", Material.ORANGE_WOOL);
        add("сиреневая_шерсть", Material.MAGENTA_WOOL);
        add("голубая_шерсть", Material.LIGHT_BLUE_WOOL);
        add("желтая_шерсть", Material.YELLOW_WOOL);
        add("салатовая_шерсть", Material.LIME_WOOL);
        add("розовая_шерсть", Material.PINK_WOOL);
        add("серая_шерсть", Material.GRAY_WOOL);
        add("светло_серая_шерсть", Material.LIGHT_GRAY_WOOL);
        add("бирюзовая_шерсть", Material.CYAN_WOOL);
        add("фиолетовая_шерсть", Material.PURPLE_WOOL);
        add("синяя_шерсть", Material.BLUE_WOOL);
        add("коричневая_шерсть", Material.BROWN_WOOL);
        add("зеленая_шерсть", Material.GREEN_WOOL);
        add("красная_шерсть", Material.RED_WOOL);
        add("черная_шерсть", Material.BLACK_WOOL);
        add("шерсть", Material.WHITE_WOOL);

        // Бетон
        add("белый_бетон", Material.WHITE_CONCRETE);
        add("оранжевый_бетон", Material.ORANGE_CONCRETE);
        add("сиреневый_бетон", Material.MAGENTA_CONCRETE);
        add("голубой_бетон", Material.LIGHT_BLUE_CONCRETE);
        add("желтый_бетон", Material.YELLOW_CONCRETE);
        add("салатовый_бетон", Material.LIME_CONCRETE);
        add("розовый_бетон", Material.PINK_CONCRETE);
        add("серый_бетон", Material.GRAY_CONCRETE);
        add("светло_серый_бетон", Material.LIGHT_GRAY_CONCRETE);
        add("бирюзовый_бетон", Material.CYAN_CONCRETE);
        add("фиолетовый_бетон", Material.PURPLE_CONCRETE);
        add("синий_бетон", Material.BLUE_CONCRETE);
        add("коричневый_бетон", Material.BROWN_CONCRETE);
        add("зеленый_бетон", Material.GREEN_CONCRETE);
        add("красный_бетон", Material.RED_CONCRETE);
        add("черный_бетон", Material.BLACK_CONCRETE);
        add("бетон", Material.WHITE_CONCRETE);

        // Стекло
        add("стекло", Material.GLASS);
        add("белое_стекло", Material.WHITE_STAINED_GLASS);
        add("оранжевое_стекло", Material.ORANGE_STAINED_GLASS);
        add("сиреневое_стекло", Material.MAGENTA_STAINED_GLASS);
        add("голубое_стекло", Material.LIGHT_BLUE_STAINED_GLASS);
        add("желтое_стекло", Material.YELLOW_STAINED_GLASS);
        add("салатовое_стекло", Material.LIME_STAINED_GLASS);
        add("розовое_стекло", Material.PINK_STAINED_GLASS);
        add("серое_стекло", Material.GRAY_STAINED_GLASS);
        add("бирюзовое_стекло", Material.CYAN_STAINED_GLASS);
        add("фиолетовое_стекло", Material.PURPLE_STAINED_GLASS);
        add("синее_стекло", Material.BLUE_STAINED_GLASS);
        add("коричневое_стекло", Material.BROWN_STAINED_GLASS);
        add("зеленое_стекло", Material.GREEN_STAINED_GLASS);
        add("красное_стекло", Material.RED_STAINED_GLASS);
        add("черное_стекло", Material.BLACK_STAINED_GLASS);

        // Кирпичи и камень
        add("кирпич", Material.BRICKS);
        add("кирпичи", Material.BRICKS);
        add("каменный_кирпич", Material.STONE_BRICKS);
        add("мшистый_каменный_кирпич", Material.MOSSY_STONE_BRICKS);
        add("треснувший_каменный_кирпич", Material.CRACKED_STONE_BRICKS);
        add("резной_каменный_кирпич", Material.CHISELED_STONE_BRICKS);
        add("незер_кирпич", Material.NETHER_BRICKS);
        add("красный_незер_кирпич", Material.RED_NETHER_BRICKS);
        add("энд_кирпич", Material.END_STONE_BRICKS);
        add("призматин_кирпич", Material.PRISMARINE_BRICKS);

        // Кварц и природа
        add("кварц", Material.QUARTZ_BLOCK);
        add("гладкий_кварц", Material.SMOOTH_QUARTZ);
        add("резной_кварц", Material.CHISELED_QUARTZ_BLOCK);
        add("кварц_столб", Material.QUARTZ_PILLAR);
        add("призматин", Material.PRISMARINE);
        add("темный_призматин", Material.DARK_PRISMARINE);
        add("морской_фонарь", Material.SEA_LANTERN);
        add("маяк", Material.BEACON);

        // Незер
        add("незеррак", Material.NETHERRACK);
        add("душевой_песок", Material.SOUL_SAND);
        add("душевая_почва", Material.SOUL_SOIL);
        add("магма", Material.MAGMA_BLOCK);
        add("глаукус", Material.GLOWSTONE);
        add("светящийся_камень", Material.GLOWSTONE);
        add("шроомсвет", Material.SHROOMLIGHT);
        add("базальт", Material.BASALT);
        add("гладкий_базальт", Material.SMOOTH_BASALT);
        add("блэкстоун", Material.BLACKSTONE);
        add("полированный_блэкстоун", Material.POLISHED_BLACKSTONE);

        // Энд
        add("эндер_камень", Material.END_STONE);
        add("энд_камень", Material.END_STONE);
        add("пурпур", Material.PURPUR_BLOCK);
        add("пурпурная_плитка", Material.PURPUR_PILLAR);

        // Терракота
        add("терракота", Material.TERRACOTTA);
        add("белая_терракота", Material.WHITE_TERRACOTTA);
        add("оранжевая_терракота", Material.ORANGE_TERRACOTTA);
        add("желтая_терракота", Material.YELLOW_TERRACOTTA);
        add("красная_терракота", Material.RED_TERRACOTTA);
        add("коричневая_терракота", Material.BROWN_TERRACOTTA);
        add("черная_терракота", Material.BLACK_TERRACOTTA);
        add("зеленая_терракота", Material.GREEN_TERRACOTTA);
        add("синяя_терракота", Material.BLUE_TERRACOTTA);

        // Растения
        add("роза", Material.POPPY);
        add("одуванчик", Material.DANDELION);
        add("трава_куст", Material.GRASS);
        add("папоротник", Material.FERN);
        add("кактус", Material.CACTUS);
        add("тростник", Material.SUGAR_CANE);
        add("бамбук", Material.BAMBOO);
        add("тыква", Material.PUMPKIN);
        add("арбуз", Material.MELON);
        add("сено", Material.HAY_BLOCK);

        // Особенные
        add("воздух", Material.AIR);
        add("вода", Material.WATER);
        add("лава", Material.LAVA);
        add("грибница", Material.MYCELIUM);
        add("торт", Material.CAKE);
        add("тнт", Material.TNT);
        add("динамит", Material.TNT);
        add("наковальня", Material.ANVIL);
        add("сундук", Material.CHEST);
        add("верстак", Material.CRAFTING_TABLE);
        add("печь", Material.FURNACE);
        add("плавильня", Material.BLAST_FURNACE);
        add("коптильня", Material.SMOKER);
        add("зачарователь", Material.ENCHANTING_TABLE);
        add("книжная_полка", Material.BOOKSHELF);
        add("нотный_блок", Material.NOTE_BLOCK);
        add("юкебокс", Material.JUKEBOX);
    }

    private void add(String ru, Material mat) {
        ruBlocks.put(ru.toLowerCase(), mat);
    }
}
