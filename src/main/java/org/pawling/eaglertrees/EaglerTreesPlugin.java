package org.pawling.eaglertrees;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EaglerTreesPlugin extends JavaPlugin implements Listener {

    private final Map<TreeKey, Notch> notches = new ConcurrentHashMap<>();
    private final Set<TreeKey> activeFalls = ConcurrentHashMap.newKeySet();

    private Settings settings;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("EaglerTrees enabled: first axe strike sets fall direction; natural trees rotate onto their side.");
    }

    @Override
    public void onDisable() {
        notches.clear();
        activeFalls.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFirstStrike(PlayerInteractEvent event) {
        if (!settings.enabled || event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null || !isLog(clicked.getType())) {
            return;
        }

        if (settings.requireAxe && !isAxe(event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }

        TreeSnapshot tree = discoverTree(clicked);
        if (!tree.isNatural(settings)) {
            return;
        }

        TreeKey key = tree.key();
        if (activeFalls.contains(key)) {
            return;
        }

        purgeExpiredNotches();
        Direction direction = directionFromHit(event.getBlockFace(), event.getPlayer(), tree.baseBlock());
        long expiresAt = System.currentTimeMillis() + settings.notchTimeoutSeconds * 1000L;

        Notch existing = notches.putIfAbsent(key, new Notch(direction, expiresAt, event.getPlayer().getUniqueId()));
        if (existing == null) {
            if (settings.playFeedback) {
                Location center = clicked.getLocation().add(0.5, 0.5, 0.5);
                clicked.getWorld().playSound(center, Sound.BLOCK_WOOD_HIT, 0.85f, 0.72f);
                clicked.getWorld().spawnParticle(Particle.BLOCK, center, 8, 0.18, 0.18, 0.18, 0.02, clicked.getBlockData());
            }
            if (settings.sendNotchMessage) {
                event.getPlayer().sendMessage("§6[EaglerTrees] §fNotch set on the §e" + direction.name().toLowerCase(Locale.ROOT)
                    + " §fside — this tree will fall §e" + direction.name().toLowerCase(Locale.ROOT) + "§f.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTreeBreak(BlockBreakEvent event) {
        if (!settings.enabled || !isLog(event.getBlock().getType())) {
            return;
        }

        Player player = event.getPlayer();
        if (settings.requireAxe && !isAxe(player.getInventory().getItemInMainHand())) {
            return;
        }

        TreeSnapshot tree = discoverTree(event.getBlock());
        if (!tree.isNatural(settings)) {
            return;
        }

        TreeKey key = tree.key();
        if (!activeFalls.add(key)) {
            event.setCancelled(true);
            return;
        }

        purgeExpiredNotches();
        Notch notch = notches.remove(key);
        Direction direction = notch != null ? notch.direction() : directionTowardPlayer(player, tree.baseBlock());

        event.setCancelled(true);
        event.setDropItems(false);
        event.setExpToDrop(0);

        beginFall(tree, direction, player);
    }

    private void beginFall(TreeSnapshot tree, Direction direction, Player player) {
        World world = tree.world();
        Block base = tree.baseBlock();

        if (settings.removeLeavesAtStart) {
            for (BlockPos leaf : tree.leaves()) {
                Block block = leaf.block(world);
                if (isLeaves(block.getType())) {
                    block.setType(Material.AIR, false);
                }
            }
        }

        if (settings.playFeedback) {
            Location baseCenter = base.getLocation().add(0.5, 0.5, 0.5);
            world.playSound(baseCenter, Sound.BLOCK_WOOD_BREAK, 1.0f, 0.62f);
            world.spawnParticle(Particle.BLOCK, baseCenter, 18, 0.5, 0.35, 0.5, 0.03, base.getBlockData());

            BlockPos top = tree.logs().stream().max(Comparator.comparingInt(BlockPos::y)).orElse(tree.base());
            Location topCenter = top.block(world).getLocation().add(0.5, 0.5, 0.5);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                world.playSound(topCenter, Sound.BLOCK_WOOD_FALL, 1.15f, 0.78f);
                world.spawnParticle(Particle.BLOCK, topCenter, 24, 0.6, 0.6, 0.6, 0.04, base.getBlockData());
            }, Math.max(2L, settings.fallDelayTicks / 2L));
        }

        Bukkit.getScheduler().runTaskLater(this, () -> finishFall(tree, direction, player), settings.fallDelayTicks);
    }

    private void finishFall(TreeSnapshot tree, Direction direction, Player player) {
        TreeKey key = tree.key();
        World world = tree.world();

        try {
            List<SourceLog> fallingLogs = new ArrayList<>();
            int baseY = tree.base().y();

            for (BlockPos pos : tree.logs()) {
                Block block = pos.block(world);
                if (!isLog(block.getType())) {
                    continue;
                }

                boolean stump = settings.leaveStump && pos.y() == baseY;
                if (stump) {
                    continue;
                }

                fallingLogs.add(new SourceLog(pos, block.getType(), block.getBlockData().clone(), isBranch(pos, block.getBlockData(), tree)));
            }

            if (fallingLogs.isEmpty()) {
                return;
            }

            for (SourceLog source : fallingLogs) {
                Block block = source.pos().block(world);
                if (isLog(block.getType())) {
                    block.setType(Material.AIR, false);
                }
            }

            List<LandingLog> landing = new ArrayList<>(fallingLogs.size());
            int minTrunkY = Integer.MAX_VALUE;

            for (SourceLog source : fallingLogs) {
                RelativePos rotated = rotate(source.pos(), tree.base(), direction);
                landing.add(new LandingLog(source, rotated.x(), rotated.y(), rotated.z(), false));
                if (!source.branch()) {
                    minTrunkY = Math.min(minTrunkY, rotated.y());
                }
            }

            if (minTrunkY == Integer.MAX_VALUE) {
                minTrunkY = landing.stream().mapToInt(LandingLog::y).min().orElse(baseY);
            }

            int shapeLift = baseY - minTrunkY;
            List<LandingLog> shapeAdjusted = new ArrayList<>(landing.size());
            for (LandingLog item : landing) {
                shapeAdjusted.add(item.withY(item.y() + shapeLift));
            }

            int obstacleLift = settings.protectSolidObstacles ? calculateObstacleLift(shapeAdjusted, world) : 0;
            List<LandingLog> finalLanding = new ArrayList<>(shapeAdjusted.size());

            for (LandingLog item : shapeAdjusted) {
                int y = item.y() + obstacleLift;
                boolean crushed = settings.convertGroundBranchesToPlanks && item.source().branch() && y <= baseY + obstacleLift;
                finalLanding.add(new LandingLog(item.source(), item.x(), y, item.z(), crushed));
            }

            finalLanding.sort(Comparator
                .comparingInt(LandingLog::y)
                .thenComparingInt(LandingLog::x)
                .thenComparingInt(LandingLog::z));

            Set<BlockPos> reserved = new HashSet<>();
            for (LandingLog item : finalLanding) {
                Material targetMaterial = item.crushed() ? plankFor(item.source().material()) : item.source().material();
                BlockData data = item.crushed()
                    ? targetMaterial.createBlockData()
                    : rotateBlockData(item.source().data().clone(), direction);

                BlockPos requested = new BlockPos(item.x(), item.y(), item.z());
                BlockPos destination = chooseSafeDestination(requested, world, reserved, item.source().branch());

                if (destination == null) {
                    world.dropItemNaturally(new Location(world, requested.x() + 0.5, requested.y() + 0.5, requested.z() + 0.5), new ItemStack(targetMaterial));
                    continue;
                }

                Block block = destination.block(world);
                if (!settings.protectSolidObstacles || isReplaceableForFall(block.getType())) {
                    block.setBlockData(data, false);
                    reserved.add(destination);
                } else {
                    world.dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), new ItemStack(targetMaterial));
                }
            }

            if (settings.playFeedback) {
                Location baseCenter = tree.baseBlock().getLocation().add(0.5, 0.5, 0.5);
                world.playSound(baseCenter, Sound.BLOCK_WOOD_PLACE, 1.25f, 0.58f);
                world.spawnParticle(Particle.BLOCK, baseCenter.clone().add(direction.dx * 2.0, 0.35, direction.dz * 2.0),
                    32, 1.1, 0.3, 1.1, 0.05, tree.baseBlock().getBlockData());
            }

            if (!settings.removeLeavesAtStart) {
                for (BlockPos leaf : tree.leaves()) {
                    Block block = leaf.block(world);
                    if (isLeaves(block.getType())) {
                        block.setType(Material.AIR, false);
                    }
                }
            }

            if (player.isOnline() && settings.sendNotchMessage) {
                player.sendMessage("§6[EaglerTrees] §fTimber! The tree fell §e"
                    + direction.name().toLowerCase(Locale.ROOT) + "§f.");
            }
        } finally {
            activeFalls.remove(key);
        }
    }

    private int calculateObstacleLift(List<LandingLog> landing, World world) {
        for (int lift = 0; lift <= settings.maxObstacleLift; lift++) {
            boolean clear = true;
            for (LandingLog item : landing) {
                if (item.source().branch()) {
                    continue;
                }
                Block block = world.getBlockAt(item.x(), item.y() + lift, item.z());
                if (!isReplaceableForFall(block.getType())) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return lift;
            }
        }
        return settings.maxObstacleLift;
    }

    private BlockPos chooseSafeDestination(BlockPos requested, World world, Set<BlockPos> reserved, boolean branch) {
        Block block = requested.block(world);
        if (!reserved.contains(requested) && (!settings.protectSolidObstacles || isReplaceableForFall(block.getType()))) {
            return requested;
        }

        int search = branch ? settings.branchPlacementSearchHeight : 1;
        for (int dy = 1; dy <= search; dy++) {
            BlockPos candidate = new BlockPos(requested.x(), requested.y() + dy, requested.z());
            Block candidateBlock = candidate.block(world);
            if (!reserved.contains(candidate) && (!settings.protectSolidObstacles || isReplaceableForFall(candidateBlock.getType()))) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isBranch(BlockPos pos, BlockData data, TreeSnapshot tree) {
        int dx = Math.abs(pos.x() - tree.base().x());
        int dz = Math.abs(pos.z() - tree.base().z());
        if (dx > 1 || dz > 1) {
            return true;
        }
        if (data instanceof Orientable orientable) {
            return orientable.getAxis() != Axis.Y;
        }
        return false;
    }

    private RelativePos rotate(BlockPos source, BlockPos base, Direction direction) {
        int dx = source.x() - base.x();
        int dy = source.y() - base.y();
        int dz = source.z() - base.z();

        return switch (direction) {
            case EAST -> new RelativePos(base.x() + dy, base.y() - dx, base.z() + dz);
            case WEST -> new RelativePos(base.x() - dy, base.y() + dx, base.z() + dz);
            case SOUTH -> new RelativePos(base.x() + dx, base.y() - dz, base.z() + dy);
            case NORTH -> new RelativePos(base.x() + dx, base.y() + dz, base.z() - dy);
        };
    }

    private BlockData rotateBlockData(BlockData data, Direction direction) {
        if (!(data instanceof Orientable orientable)) {
            return data;
        }

        Axis old = orientable.getAxis();
        Axis rotated = old;

        if (direction == Direction.EAST || direction == Direction.WEST) {
            if (old == Axis.Y) rotated = Axis.X;
            else if (old == Axis.X) rotated = Axis.Y;
        } else {
            if (old == Axis.Y) rotated = Axis.Z;
            else if (old == Axis.Z) rotated = Axis.Y;
        }

        if (orientable.getAxes().contains(rotated)) {
            orientable.setAxis(rotated);
        }
        return data;
    }

    private TreeSnapshot discoverTree(Block start) {
        World world = start.getWorld();
        String species = speciesKey(start.getType());
        BlockPos startPos = BlockPos.of(start);
        Set<BlockPos> logs = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(startPos);

        int minAllowedY = start.getY() - settings.maxVerticalSpan;
        int maxAllowedY = start.getY() + settings.maxVerticalSpan;

        while (!queue.isEmpty() && logs.size() < settings.maxTreeLogs) {
            BlockPos pos = queue.removeFirst();
            if (logs.contains(pos)) {
                continue;
            }

            if (Math.abs(pos.x() - start.getX()) > settings.maxHorizontalRadius
                || Math.abs(pos.z() - start.getZ()) > settings.maxHorizontalRadius
                || pos.y() < minAllowedY || pos.y() > maxAllowedY) {
                continue;
            }

            Block block = pos.block(world);
            if (!isLog(block.getType()) || !speciesKey(block.getType()).equals(species)) {
                continue;
            }

            logs.add(pos);

            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oy == 0 && oz == 0) continue;
                        queue.addLast(new BlockPos(pos.x() + ox, pos.y() + oy, pos.z() + oz));
                    }
                }
            }
        }

        BlockPos base = logs.stream()
            .min(Comparator.comparingInt(BlockPos::y)
                .thenComparingInt(p -> manhattanXZ(p, startPos)))
            .orElse(startPos);

        Set<BlockPos> leaves = collectNearbyLeaves(world, logs);
        return new TreeSnapshot(world, base, logs, leaves);
    }

    private Set<BlockPos> collectNearbyLeaves(World world, Set<BlockPos> logs) {
        Set<BlockPos> leaves = new LinkedHashSet<>();
        int r = settings.leafSearchRadius;

        for (BlockPos log : logs) {
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        if (leaves.size() >= settings.maxLeavesRemoved) {
                            return leaves;
                        }
                        BlockPos candidate = new BlockPos(log.x() + x, log.y() + y, log.z() + z);
                        if (isLeaves(candidate.block(world).getType())) {
                            leaves.add(candidate);
                        }
                    }
                }
            }
        }
        return leaves;
    }

    private static int manhattanXZ(BlockPos a, BlockPos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
    }

    private static Direction directionFromHit(BlockFace face, Player player, Block base) {
        return switch (face) {
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            default -> directionTowardPlayer(player, base);
        };
    }

    private static Direction directionTowardPlayer(Player player, Block base) {
        double dx = player.getLocation().getX() - (base.getX() + 0.5);
        double dz = player.getLocation().getZ() - (base.getZ() + 0.5);
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private void purgeExpiredNotches() {
        long now = System.currentTimeMillis();
        notches.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private static boolean isAxe(ItemStack stack) {
        return stack != null && stack.getType().name().endsWith("_AXE");
    }

    private static boolean isLog(Material material) {
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    private static boolean isLeaves(Material material) {
        return material.name().endsWith("_LEAVES");
    }

    private static boolean isReplaceableForFall(Material material) {
        if (material.isAir() || isLeaves(material)) {
            return true;
        }
        String name = material.name();
        return name.endsWith("_GRASS")
            || name.endsWith("_FLOWER")
            || name.endsWith("_SAPLING")
            || name.endsWith("_VINES")
            || name.equals("VINE")
            || name.equals("FERN")
            || name.equals("LARGE_FERN")
            || name.equals("DEAD_BUSH")
            || name.equals("SNOW")
            || name.equals("SHORT_GRASS")
            || name.equals("TALL_GRASS");
    }

    private static String speciesKey(Material material) {
        String name = material.name();
        if (name.startsWith("STRIPPED_")) {
            name = name.substring("STRIPPED_".length());
        }
        for (String suffix : List.of("_LOG", "_WOOD", "_STEM", "_HYPHAE")) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private static Material plankFor(Material log) {
        String species = speciesKey(log);
        Material plank = Material.matchMaterial(species + "_PLANKS");
        return plank != null ? plank : Material.OAK_PLANKS;
    }

    private void reloadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        settings = new Settings(
            c.getBoolean("enabled", true),
            c.getBoolean("require-axe", true),
            Math.max(2, c.getInt("min-tree-logs", 4)),
            Math.max(16, c.getInt("max-tree-logs", 256)),
            Math.max(2, c.getInt("max-horizontal-radius", 10)),
            Math.max(8, c.getInt("max-vertical-span", 48)),
            Math.max(0, c.getInt("min-nearby-leaves", 4)),
            Math.max(1, c.getInt("leaf-search-radius", 3)),
            Math.max(32, c.getInt("max-leaves-removed", 1400)),
            Math.max(10, c.getLong("notch-timeout-seconds", 90)),
            c.getBoolean("send-notch-message", true),
            Math.max(1L, c.getLong("fall-delay-ticks", 12)),
            c.getBoolean("play-feedback", true),
            c.getBoolean("remove-leaves-at-start", true),
            c.getBoolean("leave-stump", true),
            c.getBoolean("protect-solid-obstacles", true),
            Math.max(0, c.getInt("max-obstacle-lift", 8)),
            c.getBoolean("convert-ground-branches-to-planks", true),
            Math.max(0, c.getInt("branch-placement-search-height", 4))
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§6EaglerTrees §f" + getDescription().getVersion()
                + " §7| enabled=" + settings.enabled
                + " | require-axe=" + settings.requireAxe
                + " | active-falls=" + activeFalls.size()
                + " | stored-notches=" + notches.size());
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("eaglertrees.admin")) {
                sender.sendMessage("§cYou do not have permission to reload EaglerTrees.");
                return true;
            }
            reloadSettings();
            notches.clear();
            sender.sendMessage("§aEaglerTrees configuration reloaded.");
            return true;
        }

        sender.sendMessage("§7Usage: /eaglertrees <status|reload>");
        return true;
    }

    private enum Direction {
        NORTH(0, -1), SOUTH(0, 1), EAST(1, 0), WEST(-1, 0);
        private final int dx;
        private final int dz;
        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }
    }

    private record TreeKey(UUID worldId, int x, int y, int z) {}
    private record Notch(Direction direction, long expiresAtMillis, UUID playerId) {}
    private record RelativePos(int x, int y, int z) {}

    private record BlockPos(int x, int y, int z) {
        static BlockPos of(Block block) {
            return new BlockPos(block.getX(), block.getY(), block.getZ());
        }
        Block block(World world) {
            return world.getBlockAt(x, y, z);
        }
    }

    private record TreeSnapshot(World world, BlockPos base, Set<BlockPos> logs, Set<BlockPos> leaves) {
        TreeKey key() {
            return new TreeKey(world.getUID(), base.x(), base.y(), base.z());
        }
        Block baseBlock() {
            return base.block(world);
        }
        boolean isNatural(Settings settings) {
            return logs.size() >= settings.minTreeLogs && leaves.size() >= settings.minNearbyLeaves;
        }
    }

    private record SourceLog(BlockPos pos, Material material, BlockData data, boolean branch) {}

    private record LandingLog(SourceLog source, int x, int y, int z, boolean crushed) {
        LandingLog withY(int newY) {
            return new LandingLog(source, x, newY, z, crushed);
        }
    }

    private record Settings(
        boolean enabled,
        boolean requireAxe,
        int minTreeLogs,
        int maxTreeLogs,
        int maxHorizontalRadius,
        int maxVerticalSpan,
        int minNearbyLeaves,
        int leafSearchRadius,
        int maxLeavesRemoved,
        long notchTimeoutSeconds,
        boolean sendNotchMessage,
        long fallDelayTicks,
        boolean playFeedback,
        boolean removeLeavesAtStart,
        boolean leaveStump,
        boolean protectSolidObstacles,
        int maxObstacleLift,
        boolean convertGroundBranchesToPlanks,
        int branchPlacementSearchHeight
    ) {}
}
