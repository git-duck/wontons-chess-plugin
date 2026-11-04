package com.wonton.chess;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ChessPlugin: combined top+player inventory 8x8 board.
 *
 * - Top chest (rows 0..3, cols 0..7) -> board rows 0..3
 * - Player inventory visible area (main rows + hotbar; first 8 columns) -> board rows 4..7
 *
 * Compatibility: compiled against the Spigot 1.8.8 API with Java 8 bytecode and written to
 * avoid APIs that were removed/changed between 1.8.8 and the newest versions (no NamespacedKey,
 * no PersistentDataContainer, no direct InventoryView calls, no hard-coded modern Material constants).
 *
 * Safety:
 * - Player inventories are saved before showing board and restored on close/end.
 * - Clicks in board area are cancelled and handled as chess moves.
 */
public class ChessPlugin extends JavaPlugin implements Listener {

    static final String GUI_TITLE = ChatColor.DARK_GREEN + "Chess (8x8)";

    private ChallengeManager challengeManager;
    private final Map<UUID, ChessGame> activeGames = new ConcurrentHashMap<>();

    // Configurable items (loaded from config.yml)
    private final Map<Color, Map<ChessPieceType, ItemSpec>> pieceItems = new EnumMap<>(Color.class);
    private ItemSpec emptySquare = new ItemSpec(Material.PAPER, (short) 0);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadItemConfig();
        this.challengeManager = new ChallengeManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(this.getCommand("chess")).setExecutor(new ChessCommand());
        Objects.requireNonNull(this.getCommand("chessaccept")).setExecutor(new ChessAcceptCommand());
        Objects.requireNonNull(this.getCommand("chessdeny")).setExecutor(new ChessDenyCommand());
        Objects.requireNonNull(this.getCommand("chessreload")).setExecutor(new ChessReloadCommand());
        getLogger().info("ChessPlugin enabled (" + (ItemSpec.LEGACY ? "legacy" : "modern") + " material mode)");
    }

    @Override
    public void onDisable() {
        // End games (restores inventories)
        for (ChessGame g : new ArrayList<>(activeGames.values())) {
            g.endGame("Server shutting down");
        }
    }

    // Config / items ----------------------------------------------------------

    void loadItemConfig() {
        reloadConfig();
        pieceItems.clear();
        for (Color color : Color.values()) {
            Map<ChessPieceType, ItemSpec> m = new EnumMap<>(ChessPieceType.class);
            for (ChessPieceType type : ChessPieceType.values()) {
                String path = "pieces." + color.name().toLowerCase(Locale.ROOT) + "." + type.name().toLowerCase(Locale.ROOT);
                m.put(type, loadSpec(path, defaultPiece(color, type)));
            }
            pieceItems.put(color, m);
        }
        emptySquare = loadSpec("empty-square", "GRAY_STAINED_GLASS_PANE");
    }

    private ItemSpec loadSpec(String path, String def) {
        String configured = getConfig().getString(path, def);
        ItemSpec spec = ItemSpec.parse(configured);
        if (spec == null) {
            getLogger().warning("Unknown item '" + configured + "' at '" + path
                    + "' for this server version; using default '" + def + "'.");
            spec = ItemSpec.parse(def);
        }
        if (spec == null) spec = new ItemSpec(Material.PAPER, (short) 0);
        return spec;
    }

    static String defaultPiece(Color color, ChessPieceType type) {
        boolean w = color == Color.WHITE;
        switch (type) {
            case PAWN:   return w ? "OAK_FENCE" : "DARK_OAK_FENCE";
            case ROOK:   return w ? "IRON_BLOCK" : "BLACKSTONE";
            case KNIGHT: return w ? "SADDLE" : "LEATHER_HORSE_ARMOR";
            case BISHOP: return w ? "BRICK" : "NETHER_BRICKS";
            case QUEEN:  return w ? "GOLD_BLOCK" : "CRYING_OBSIDIAN";
            case KING:   return w ? "DIAMOND_BLOCK" : "OBSIDIAN";
            default:     return "PAPER";
        }
    }

    ItemStack pieceItem(Color color, ChessPieceType type) {
        return pieceItems.get(color).get(type).create();
    }

    ItemStack emptySquareItem() {
        return emptySquare.create();
    }

    /** Item for GUI buttons; falls back to PAPER if the material doesn't exist on this version. */
    static ItemStack buttonItem(String material) {
        ItemSpec s = ItemSpec.parse(material);
        if (s == null) s = new ItemSpec(Material.PAPER, (short) 0);
        return s.create();
    }

    /** Material + legacy data value, resolved safely across versions. */
    static final class ItemSpec {
        /** true on 1.8 - 1.12 (pre "flattening"), where items use numeric data values. */
        static final boolean LEGACY = detectLegacy();
        private static final Map<String, String> LEGACY_ALIASES = new HashMap<>();

        static {
            // modern name -> legacy name[:data]
            LEGACY_ALIASES.put("OAK_FENCE", "FENCE");
            LEGACY_ALIASES.put("BRICK", "CLAY_BRICK");   // the brick ITEM
            LEGACY_ALIASES.put("BRICKS", "BRICK");       // the brick BLOCK
            LEGACY_ALIASES.put("NETHER_BRICKS", "NETHER_BRICK");
            LEGACY_ALIASES.put("BLACKSTONE", "COAL_BLOCK");
            LEGACY_ALIASES.put("LEATHER_HORSE_ARMOR", "IRON_BARDING");
            LEGACY_ALIASES.put("CRYING_OBSIDIAN", "LAPIS_BLOCK");
            LEGACY_ALIASES.put("CLOCK", "WATCH");
            LEGACY_ALIASES.put("OAK_SIGN", "SIGN");
            String[] colors = {"WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
                    "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK"};
            for (int i = 0; i < colors.length; i++) {
                LEGACY_ALIASES.put(colors[i] + "_STAINED_GLASS_PANE", "STAINED_GLASS_PANE:" + i);
                LEGACY_ALIASES.put(colors[i] + "_STAINED_GLASS", "STAINED_GLASS:" + i);
                LEGACY_ALIASES.put(colors[i] + "_WOOL", "WOOL:" + i);
                LEGACY_ALIASES.put(colors[i] + "_TERRACOTTA", "STAINED_CLAY:" + i);
                LEGACY_ALIASES.put(colors[i] + "_CARPET", "CARPET:" + i);
            }
        }

        final Material material;
        final short data;

        ItemSpec(Material material, short data) {
            this.material = material;
            this.data = data;
        }

        ItemStack create() {
            return data == 0 ? new ItemStack(material) : new ItemStack(material, 1, data);
        }

        private static boolean detectLegacy() {
            try {
                String[] parts = Bukkit.getBukkitVersion().split("-")[0].split("\\.");
                if (parts.length >= 2 && parts[0].equals("1")) {
                    return Integer.parseInt(parts[1]) < 13;
                }
            } catch (RuntimeException ignored) {
                // unparseable (e.g. new year-based versions like 26.x) -> modern
            }
            return false;
        }

        /** Accepts "NAME" or "NAME:data". Returns null if the material is unknown on this server. */
        static ItemSpec parse(String raw) {
            if (raw == null) return null;
            String s = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (s.isEmpty()) return null;
            String name = s;
            short data = 0;
            boolean explicit = false;
            int idx = s.indexOf(':');
            if (idx >= 0) {
                name = s.substring(0, idx);
                try {
                    data = Short.parseShort(s.substring(idx + 1));
                    explicit = true;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (LEGACY) {
                String alias = LEGACY_ALIASES.get(name);
                if (alias != null) {
                    int a = alias.indexOf(':');
                    if (a >= 0) {
                        name = alias.substring(0, a);
                        if (!explicit) data = Short.parseShort(alias.substring(a + 1));
                    } else {
                        name = alias;
                    }
                }
            }
            Material m = Material.matchMaterial(name);
            if (m == null || m == Material.AIR) return null;
            return new ItemSpec(m, LEGACY ? data : (short) 0);
        }
    }

    // Commands ----------------------------------------------------------------

    private class ChessCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player p = (Player) sender;
            if (args.length == 0) {
                ChessGame g = activeGames.get(p.getUniqueId());
                if (g != null) {
                    g.openFor(p); // reopen the board after closing it
                    return true;
                }
            }
            if (args.length != 1) {
                p.sendMessage(ChatColor.RED + "Usage: /chess <player>  (or /chess to reopen your board)");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !target.isOnline()) {
                p.sendMessage(ChatColor.RED + "Player not found or offline.");
                return true;
            }
            if (target.getUniqueId().equals(p.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "You cannot challenge yourself.");
                return true;
            }
            if (activeGames.containsKey(p.getUniqueId()) || activeGames.containsKey(target.getUniqueId())) {
                p.sendMessage(ChatColor.RED + "Either you or the target is already in a game.");
                return true;
            }
            challengeManager.createChallenge(p, target);
            p.sendMessage(ChatColor.GREEN + "Challenge sent to " + target.getName() + ". Expires in 30s.");
            target.sendMessage(ChatColor.YELLOW + p.getName() + " has challenged you to a chess game! Type " + ChatColor.AQUA + "/chessaccept " + ChatColor.YELLOW + "to accept or " + ChatColor.RED + "/chessdeny" + ChatColor.YELLOW + " to deny.");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase();
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(prefix))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }

    private class ChessAcceptCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player accepter = (Player) sender;
            UUID challengerId = challengeManager.acceptChallenge(accepter);
            if (challengerId == null) {
                accepter.sendMessage(ChatColor.RED + "You have no pending challenges.");
                return true;
            }
            Player challenger = Bukkit.getPlayer(challengerId);
            if (challenger == null || !challenger.isOnline()) {
                accepter.sendMessage(ChatColor.RED + "Challenger is no longer online.");
                return true;
            }
            if (activeGames.containsKey(challengerId) || activeGames.containsKey(accepter.getUniqueId())) {
                accepter.sendMessage(ChatColor.RED + "Either you or the challenger is already in a game.");
                return true;
            }
            ChessGame game = new ChessGame(ChessPlugin.this, challenger, accepter);
            activeGames.put(challenger.getUniqueId(), game);
            activeGames.put(accepter.getUniqueId(), game);
            game.start();
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private class ChessDenyCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            Player denier = (Player) sender;
            UUID challenger = challengeManager.denyChallenge(denier);
            if (challenger == null) {
                denier.sendMessage(ChatColor.RED + "You have no pending challenges.");
                return true;
            }
            Player c = Bukkit.getPlayer(challenger);
            if (c != null && c.isOnline()) {
                c.sendMessage(ChatColor.RED + denier.getName() + " denied your challenge.");
            }
            denier.sendMessage(ChatColor.YELLOW + "Challenge denied.");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private class ChessReloadCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("chess.admin")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission.");
                return true;
            }
            loadItemConfig();
            sender.sendMessage(ChatColor.GREEN + "Chess config reloaded. Open boards update on the next tick.");
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    // Events ------------------------------------------------------------------

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        // Only intercept clicks while THIS game's board is the open inventory
        if (g == null || !g.isBoard(p, e.getInventory())) return;
        e.setCancelled(true);
        g.onInventoryClick(p, e);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        HumanEntity he = e.getWhoClicked();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null && g.isBoard(p, e.getInventory())) e.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        HumanEntity he = e.getPlayer();
        if (!(he instanceof Player)) return;
        Player p = (Player) he;
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) {
            g.onClose(p, e.getInventory());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        ChessGame g = activeGames.get(p.getUniqueId());
        if (g != null) {
            g.onPlayerQuit(p);
        }
    }

    void removeGame(ChessGame g) {
        activeGames.remove(g.white.getUniqueId());
        activeGames.remove(g.black.getUniqueId());
    }

    // Challenge manager -------------------------------------------------------

    static class ChallengeManager {
        private final Map<UUID, Challenge> pending = new ConcurrentHashMap<>();
        private final JavaPlugin plugin;

        ChallengeManager(JavaPlugin plugin) {
            this.plugin = plugin;
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                long now = System.currentTimeMillis();
                List<UUID> toRemove = new ArrayList<>();
                for (Map.Entry<UUID, Challenge> e : pending.entrySet()) {
                    if (e.getValue().expiresAt <= now) {
                        Player c = plugin.getServer().getPlayer(e.getValue().challenger);
                        Player t = plugin.getServer().getPlayer(e.getKey());
                        if (c != null && c.isOnline()) c.sendMessage(ChatColor.RED + "Your challenge to " + (t != null ? t.getName() : "player") + " expired.");
                        if (t != null && t.isOnline()) t.sendMessage(ChatColor.RED + "Challenge from " + (c != null ? c.getName() : "player") + " expired.");
                        toRemove.add(e.getKey());
                    }
                }
                toRemove.forEach(pending::remove);
            }, 20L, 20L);
        }

        void createChallenge(Player challenger, Player target) {
            pending.put(target.getUniqueId(), new Challenge(challenger.getUniqueId(), System.currentTimeMillis() + 30_000L));
        }

        UUID acceptChallenge(Player target) {
            Challenge c = pending.remove(target.getUniqueId());
            if (c == null) return null;
            return c.challenger;
        }

        UUID denyChallenge(Player target) {
            Challenge c = pending.remove(target.getUniqueId());
            if (c == null) return null;
            return c.challenger;
        }

        static class Challenge {
            final UUID challenger;
            final long expiresAt;
            Challenge(UUID challenger, long expiresAt) { this.challenger = challenger; this.expiresAt = expiresAt; }
        }
    }

    // Chess game (engine + combined inventory GUI) ----------------------------

    /**
     * Marks an inventory as one of our chess boards. Bukkit hands event listeners a different wrapper
     * object than the one we created, so identity checks on the Inventory fail; the holder survives.
     */
    static final class BoardHolder implements InventoryHolder {
        final ChessGame game;
        private Inventory inventory;
        BoardHolder(ChessGame game) { this.game = game; }
        void setInventory(Inventory inventory) { this.inventory = inventory; }
        @Override
        public Inventory getInventory() { return inventory; }
    }

    static class ChessGame {
        // Side column (chest column 8) button slots
        static final int SLOT_WHITE_CLOCK = 8;
        static final int SLOT_BLACK_CLOCK = 17;
        static final int SLOT_FLIP = 26;
        static final int SLOT_RESIGN = 35;
        static final int SLOT_TIME = 44;
        static final int SLOT_CLOSE = 53;

        final ChessPlugin plugin;
        final Player white;
        final Player black;
        final ChessBoard board;
        UUID selectedPlayer = null;
        int selectedX = -1, selectedY = -1;
        // player's original inventory, kept until the board is closed / game ends
        final Map<UUID, SavedInventory> saved = new HashMap<>();
        // the board inventory each player currently has open
        final Map<UUID, Inventory> boards = new HashMap<>();
        // players whose board is being re-opened (ignore the close event this causes)
        final Set<UUID> switching = new HashSet<>();
        // players viewing the board from Black's side (White at the top)
        final Set<UUID> flipped = new HashSet<>();
        // timers
        int whiteTime = 5 * 60;
        int blackTime = 5 * 60;
        BukkitTask timerTask;
        boolean running = false;
        // en passant: last double pawn end coordinate or null
        int[] lastDoublePawn = null;

        ChessGame(ChessPlugin plugin, Player white, Player black) {
            this.plugin = plugin;
            this.white = white;
            this.black = black;
            this.board = new ChessBoard();
        }

        void start() {
            board.resetInitialPosition();
            running = true;
            flipped.add(black.getUniqueId()); // each player starts with their own pieces at the bottom
            openFor(white);
            openFor(black);
            timerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
            sendBoth(ChatColor.GREEN + "Game started! White to move. 5-minute blitz.");
            updateAllInventories();
        }

        /** True if this inventory is THIS game's board, currently open for that player. */
        boolean isBoard(Player p, Inventory inv) {
            if (inv == null || !boards.containsKey(p.getUniqueId())) return false;
            InventoryHolder h = inv.getHolder();
            return h instanceof BoardHolder && ((BoardHolder) h).game == this;
        }

        // Display <-> board coordinate mapping. Unflipped: White at the bottom. Flipped: Black at the bottom.
        private int boardRow(UUID viewer, int displayRow) { return flipped.contains(viewer) ? displayRow : 7 - displayRow; }
        private int boardCol(UUID viewer, int displayCol) { return flipped.contains(viewer) ? 7 - displayCol : displayCol; }

        void tick() {
            if (!running) return;
            if (board.whiteToMove) {
                whiteTime--;
                if (whiteTime <= 0) endGame("Black wins on time");
            } else {
                blackTime--;
                if (blackTime <= 0) endGame("White wins on time");
            }
            if (running) updateAllInventories();
        }

        void endGame(String reason) {
            if (!running) return;
            running = false;
            if (timerTask != null) timerTask.cancel();
            sendBoth(ChatColor.YELLOW + "Game ended: " + reason);
            for (Player p : Arrays.asList(white, black)) {
                boards.remove(p.getUniqueId()); // so the close event below is ignored
                if (p.isOnline()) p.closeInventory();
            }
            for (UUID id : new ArrayList<>(saved.keySet())) {
                Player p = Bukkit.getPlayer(id);
                SavedInventory s = saved.remove(id);
                if (p != null && p.isOnline() && s != null) {
                    s.restore(p);
                    p.updateInventory();
                }
            }
            plugin.removeGame(this);
        }

        void onPlayerQuit(Player p) {
            if (!running) return;
            Player other = (p.getUniqueId().equals(white.getUniqueId())) ? black : white;
            endGame(other.getName() + " wins (opponent disconnected)");
        }

        void onClose(Player p, Inventory closed) {
            UUID id = p.getUniqueId();
            if (switching.contains(id)) return;
            if (!isBoard(p, closed)) return;
            boards.remove(id);
            // restore player's inventory when they close the board; game continues
            SavedInventory s = saved.remove(id);
            if (s != null) {
                s.restore(p);
                p.updateInventory();
                p.sendMessage(ChatColor.GRAY + "Your inventory has been restored. Type /chess to reopen the board.");
            }
        }

        void sendBoth(String s) {
            if (white.isOnline()) white.sendMessage(s);
            if (black.isOnline()) black.sendMessage(s);
        }

        // Build and open combined view for player
        void openFor(Player p) {
            if (!p.isOnline() || !running) return;
            UUID id = p.getUniqueId();
            // Save current inventory only once
            if (!saved.containsKey(id)) {
                saved.put(id, SavedInventory.save(p));
            }
            BoardHolder holder = new BoardHolder(this);
            Inventory inv = Bukkit.createInventory(holder, 54, GUI_TITLE);
            holder.setInventory(inv);
            fillTop(inv, id);
            fillBottom(p);
            switching.add(id);
            try {
                p.openInventory(inv);
            } finally {
                switching.remove(id);
            }
            boards.put(id, inv);
            p.updateInventory();
        }

        /** Top chest: display rows 0..3 of the board, plus the button column on the right. */
        private void fillTop(Inventory inv, UUID viewer) {
            for (int dr = 0; dr < 4; dr++) {
                for (int dc = 0; dc < 8; dc++) {
                    inv.setItem(dr * 9 + dc, toItemFor(board.getPiece(boardRow(viewer, dr), boardCol(viewer, dc))));
                }
            }
            inv.setItem(SLOT_WHITE_CLOCK, clockItem(true));
            inv.setItem(SLOT_BLACK_CLOCK, clockItem(false));
            inv.setItem(SLOT_FLIP, createButton("ARROW", ChatColor.GREEN + "Flip", "Turn the board around"));
            inv.setItem(SLOT_RESIGN, createButton("BARRIER", ChatColor.RED + "Resign", "Give up the game"));
            inv.setItem(SLOT_TIME, createButton("CLOCK", ChatColor.GOLD + "Time",
                    "White: " + formatTime(whiteTime), "Black: " + formatTime(blackTime), "Click to post in chat"));
            inv.setItem(SLOT_CLOSE, createButton("OAK_SIGN", ChatColor.GRAY + "Close", "Hide the board (/chess reopens it)"));
        }

        /**
         * Display rows 4..7 go into the player's own inventory:
         * rows 4..6 -> main rows (slots 9..16, 18..25, 27..34), row 7 -> hotbar (0..7).
         */
        private void fillBottom(Player p) {
            UUID viewer = p.getUniqueId();
            PlayerInventory pinv = p.getInventory();
            for (int br = 0; br < 4; br++) {
                int dr = 4 + br;
                for (int dc = 0; dc < 8; dc++) {
                    int invIndex = (br == 3) ? dc : 9 + br * 9 + dc;
                    pinv.setItem(invIndex, toItemFor(board.getPiece(boardRow(viewer, dr), boardCol(viewer, dc))));
                }
            }
        }

        private ItemStack clockItem(boolean whiteSide) {
            boolean active = running && board.whiteToMove == whiteSide;
            String name = (active ? ChatColor.GREEN + "> " : ChatColor.AQUA.toString())
                    + (whiteSide ? "White: " : "Black: ") + formatTime(whiteSide ? whiteTime : blackTime);
            return createButton(whiteSide ? "WHITE_WOOL" : "BLACK_WOOL", name, active ? "Their turn" : "Waiting");
        }

        private ItemStack createButton(String material, String name, String... lore) {
            ItemStack it = ChessPlugin.buttonItem(material);
            ItemMeta m = it.getItemMeta();
            if (m != null) {
                m.setDisplayName(name);
                if (lore.length > 0) m.setLore(Arrays.asList(lore));
                it.setItemMeta(m);
            }
            return it;
        }

        private String formatTime(int secs) {
            if (secs < 0) secs = 0;
            return String.format("%d:%02d", secs / 60, secs % 60);
        }

        private ItemStack toItemFor(ChessPiece p) {
            if (p == null) return plugin.emptySquareItem();
            ItemStack it = plugin.pieceItem(p.color, p.type);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                boolean sel = selectedPlayer != null && p.x == selectedX && p.y == selectedY;
                String name = (p.color == Color.WHITE ? ChatColor.WHITE : ChatColor.DARK_GRAY) + p.type.name();
                meta.setDisplayName(sel ? ChatColor.GREEN + "Selected: " + name : name);
                meta.setLore(Collections.singletonList(squareName(p.x, p.y)));
                it.setItemMeta(meta);
            }
            return it;
        }

        // Handle clicks in combined view
        void onInventoryClick(Player p, InventoryClickEvent e) {
            if (!running) return;
            int raw = e.getRawSlot();
            if (raw < 0) return;
            UUID id = p.getUniqueId();
            if (raw < 54) {
                int r = raw / 9;
                int c = raw % 9;
                if (c == 8) {
                    handleButton(p, raw);
                } else if (r < 4) {
                    handleBoardClick(p, boardRow(id, r), boardCol(id, c));
                }
            } else {
                // player inventory region: rawSlot >= 54 (main rows first, then hotbar)
                int bottomIndex = raw - 54;
                int brow = bottomIndex / 9;
                int bcol = bottomIndex % 9;
                if (brow >= 0 && brow < 4 && bcol < 8) {
                    handleBoardClick(p, boardRow(id, 4 + brow), boardCol(id, bcol));
                }
            }
        }

        private void handleButton(Player p, int slot) {
            switch (slot) {
                case SLOT_FLIP:
                    UUID id = p.getUniqueId();
                    if (!flipped.remove(id)) flipped.add(id);
                    refresh(p);
                    break;
                case SLOT_RESIGN:
                    endGame(p.getName() + " resigned. " + (p.getUniqueId().equals(white.getUniqueId()) ? black.getName() : white.getName()) + " wins.");
                    break;
                case SLOT_WHITE_CLOCK:
                case SLOT_BLACK_CLOCK:
                case SLOT_TIME:
                    p.sendMessage(ChatColor.AQUA + "White: " + formatTime(whiteTime) + " | Black: " + formatTime(blackTime));
                    break;
                case SLOT_CLOSE:
                    p.closeInventory();
                    break;
                default:
                    break;
            }
        }

        private void handleBoardClick(Player player, int br, int bc) {
            if (!running) return;
            ChessPiece clicked = board.getPiece(br, bc);
            boolean isWhitePlayer = player.getUniqueId().equals(white.getUniqueId());
            if (selectedPlayer == null) {
                if (clicked == null) return;
                if ((clicked.color == Color.WHITE) != isWhitePlayer) {
                    player.sendMessage(ChatColor.RED + "You are playing " + (isWhitePlayer ? "White" : "Black") + ".");
                    return;
                }
                if (clicked.color == Color.WHITE && !board.whiteToMove) {
                    player.sendMessage(ChatColor.RED + "It's not White's turn.");
                    return;
                }
                if (clicked.color == Color.BLACK && board.whiteToMove) {
                    player.sendMessage(ChatColor.RED + "It's not Black's turn.");
                    return;
                }
                selectedPlayer = player.getUniqueId();
                selectedX = br; selectedY = bc;
                player.sendMessage(ChatColor.YELLOW + "Selected " + clicked.type.name() + " at " + squareName(br, bc));
                refresh(player);
            } else {
                if (!selectedPlayer.equals(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Another player is mid-selection.");
                    return;
                }
                int fromX = selectedX, fromY = selectedY;
                int toX = br, toY = bc;
                List<ChessMove> legal = board.legalMoves(fromX, fromY, lastDoublePawn);
                Optional<ChessMove> chosen = legal.stream().filter(m -> m.toX == toX && m.toY == toY).findFirst();
                if (!chosen.isPresent()) {
                    ChessPiece cc = board.getPiece(br, bc);
                    ChessPiece fromPiece = board.getPiece(fromX, fromY);
                    if (cc != null && fromPiece != null && cc.color == fromPiece.color) {
                        selectedX = br; selectedY = bc;
                        player.sendMessage(ChatColor.YELLOW + "Reselected " + cc.type.name() + " at " + squareName(br, bc));
                        refresh(player);
                        return;
                    }
                    player.sendMessage(ChatColor.RED + "Illegal move.");
                    selectedPlayer = null; selectedX = -1; selectedY = -1;
                    refresh(player);
                    return;
                }
                ChessMove move = chosen.get();
                if (move.isDoublePawn) lastDoublePawn = new int[]{move.toX, move.toY};
                else lastDoublePawn = null;
                board.applyMove(move);
                // promotion to queen if needed
                if (move.promotion) {
                    board.setPiece(move.toX, move.toY, new ChessPiece(move.promotionColor, ChessPieceType.QUEEN, move.toX, move.toY));
                }
                board.whiteToMove = !board.whiteToMove;
                selectedPlayer = null; selectedX = -1; selectedY = -1;
                updateAllInventories();
                if (board.isInCheckmate(board.whiteToMove)) {
                    endGame((board.whiteToMove ? "White" : "Black") + " is checkmated. " + (board.whiteToMove ? black.getName() : white.getName()) + " wins.");
                    return;
                }
                if (board.isStalemate(board.whiteToMove)) {
                    endGame("Draw by stalemate.");
                }
            }
        }

        /** Board row 0 is White's back rank (rank 1); column 0 is the a-file. */
        private String squareName(int r, int c) {
            return "" + (char) ('a' + c) + (r + 1);
        }

        /** Redraw one player's board (top chest + their own inventory half). */
        void refresh(Player p) {
            if (!p.isOnline()) return;
            Inventory top = boards.get(p.getUniqueId());
            if (top == null) return; // board not open for this player
            fillTop(top, p.getUniqueId());
            fillBottom(p);
            p.updateInventory();
        }

        void updateAllInventories() {
            refresh(white);
            refresh(black);
        }
    }

    // Inventory snapshot to restore later
    static class SavedInventory {
        private static Method GET_OFFHAND;
        private static Method SET_OFFHAND;

        static {
            // off-hand only exists on 1.9+, so look it up reflectively
            try {
                GET_OFFHAND = PlayerInventory.class.getMethod("getItemInOffHand");
                SET_OFFHAND = PlayerInventory.class.getMethod("setItemInOffHand", ItemStack.class);
            } catch (Exception ignored) {
                GET_OFFHAND = null;
                SET_OFFHAND = null;
            }
        }

        final ItemStack[] contents;
        final ItemStack[] armor;
        final ItemStack offhand;

        private SavedInventory(ItemStack[] contents, ItemStack[] armor, ItemStack offhand) {
            this.contents = contents;
            this.armor = armor;
            this.offhand = offhand;
        }

        private static ItemStack[] cloneAll(ItemStack[] items) {
            ItemStack[] out = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) out[i] = items[i] == null ? null : items[i].clone();
            return out;
        }

        static SavedInventory save(Player p) {
            PlayerInventory inv = p.getInventory();
            ItemStack off = null;
            if (GET_OFFHAND != null) {
                try {
                    ItemStack o = (ItemStack) GET_OFFHAND.invoke(inv);
                    off = o == null ? null : o.clone();
                } catch (Exception ignored) {
                    // leave null
                }
            }
            return new SavedInventory(cloneAll(inv.getContents()), cloneAll(inv.getArmorContents()), off);
        }

        void restore(Player p) {
            PlayerInventory inv = p.getInventory();
            inv.setContents(cloneAll(contents));
            inv.setArmorContents(cloneAll(armor));
            if (SET_OFFHAND != null) {
                try {
                    SET_OFFHAND.invoke(inv, offhand == null ? null : offhand.clone());
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    // ----------------- Chess engine simplified (en passant, castling, check/checkmate) -----------------
    enum Color { WHITE, BLACK }
    enum ChessPieceType { PAWN, ROOK, KNIGHT, BISHOP, QUEEN, KING }

    static class ChessPiece {
        final Color color;
        final ChessPieceType type;
        int x, y;
        boolean hasMoved = false;
        ChessPiece(Color color, ChessPieceType type, int x, int y) { this.color = color; this.type = type; this.x = x; this.y = y; }
        String toShortString() { return color.name().charAt(0) + "-" + type.name().charAt(0); }
    }

    static class ChessMove {
        int fromX, fromY, toX, toY;
        ChessPieceType promotionTo = null;
        boolean isDoublePawn = false;
        boolean promotion = false;
        Color promotionColor = null;
        boolean isCastling = false;
        ChessMove(int fx,int fy,int tx,int ty){fromX=fx;fromY=fy;toX=tx;toY=ty;}
    }

    static class ChessBoard {
        private final ChessPiece[][] b = new ChessPiece[8][8];
        boolean whiteToMove = true;

        ChessPiece getPiece(int x, int y) {
            if (x<0||x>7||y<0||y>7) return null;
            return b[x][y];
        }
        void setPiece(int x, int y, ChessPiece p) {
            if (x<0||x>7||y<0||y>7) return;
            b[x][y] = p;
            if (p!=null){p.x=x;p.y=y;}
        }
        void resetInitialPosition() {
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) b[x][y]=null;
            for (int y=0;y<8;y++) setPiece(1,y,new ChessPiece(Color.WHITE, ChessPieceType.PAWN,1,y));
            for (int y=0;y<8;y++) setPiece(6,y,new ChessPiece(Color.BLACK, ChessPieceType.PAWN,6,y));
            setPiece(0,0,new ChessPiece(Color.WHITE,ChessPieceType.ROOK,0,0));
            setPiece(0,7,new ChessPiece(Color.WHITE,ChessPieceType.ROOK,0,7));
            setPiece(7,0,new ChessPiece(Color.BLACK,ChessPieceType.ROOK,7,0));
            setPiece(7,7,new ChessPiece(Color.BLACK,ChessPieceType.ROOK,7,7));
            setPiece(0,1,new ChessPiece(Color.WHITE,ChessPieceType.KNIGHT,0,1));
            setPiece(0,6,new ChessPiece(Color.WHITE,ChessPieceType.KNIGHT,0,6));
            setPiece(7,1,new ChessPiece(Color.BLACK,ChessPieceType.KNIGHT,7,1));
            setPiece(7,6,new ChessPiece(Color.BLACK,ChessPieceType.KNIGHT,7,6));
            setPiece(0,2,new ChessPiece(Color.WHITE,ChessPieceType.BISHOP,0,2));
            setPiece(0,5,new ChessPiece(Color.WHITE,ChessPieceType.BISHOP,0,5));
            setPiece(7,2,new ChessPiece(Color.BLACK,ChessPieceType.BISHOP,7,2));
            setPiece(7,5,new ChessPiece(Color.BLACK,ChessPieceType.BISHOP,7,5));
            setPiece(0,3,new ChessPiece(Color.WHITE,ChessPieceType.QUEEN,0,3));
            setPiece(7,3,new ChessPiece(Color.BLACK,ChessPieceType.QUEEN,7,3));
            setPiece(0,4,new ChessPiece(Color.WHITE,ChessPieceType.KING,0,4));
            setPiece(7,4,new ChessPiece(Color.BLACK,ChessPieceType.KING,7,4));
            whiteToMove = true;
        }

        List<ChessMove> legalMoves(int x, int y, int[] lastDoublePawn) {
            ChessPiece p = getPiece(x,y);
            if (p == null) return Collections.emptyList();
            List<ChessMove> moves = pseudoLegalMoves(x,y,lastDoublePawn);
            List<ChessMove> legal = new ArrayList<>();
            for (ChessMove m : moves) {
                ChessBoard copy = copy();
                copy.applyMove(m);
                if (!copy.isKingInCheck(p.color)) {
                    // Check castling constraints
                    if (m.isCastling) {
                        if (isKingInCheck(p.color)) continue; // Cannot castle out of check
                        
                        // Check if the passing square is under attack
                        int passY = m.fromY + (m.toY > m.fromY ? 1 : -1);
                        ChessBoard passCopy = copy();
                        // Simulate King moving just one square to check if it's attacked
                        passCopy.applyMove(new ChessMove(m.fromX, m.fromY, m.fromX, passY));
                        if (passCopy.isKingInCheck(p.color)) continue; // Cannot castle through check
                    }
                    legal.add(m);
                }
            }
            return legal;
        }

        List<ChessMove> pseudoLegalMoves(int x, int y, int[] lastDoublePawn) {
            ChessPiece p = getPiece(x,y);
            if (p == null) return Collections.emptyList();
            List<ChessMove> out = new ArrayList<>();
            int dir = (p.color == Color.WHITE) ? 1 : -1;
            switch (p.type) {
                case PAWN:
                    int nx = x + dir;
                    if (onBoard(nx,y) && getPiece(nx,y)==null) {
                        ChessMove m = new ChessMove(x,y,nx,y);
                        if (nx==7 || nx==0) { m.promotion = true; m.promotionColor = p.color; m.promotionTo = ChessPieceType.QUEEN; }
                        out.add(m);
                        if ((p.color==Color.WHITE && x==1) || (p.color==Color.BLACK && x==6)) {
                            int nx2 = x + 2*dir;
                            if (onBoard(nx2,y) && getPiece(nx2,y)==null) {
                                ChessMove m2 = new ChessMove(x,y,nx2,y); m2.isDoublePawn = true;
                                out.add(m2);
                            }
                        }
                    }
                    for (int dy : new int[]{-1,1}) {
                        int cx = x + dir, cy = y + dy;
                        if (onBoard(cx,cy)) {
                            ChessPiece targ = getPiece(cx,cy);
                            if (targ != null && targ.color != p.color) {
                                ChessMove m = new ChessMove(x,y,cx,cy);
                                if (cx==7 || cx==0) { m.promotion=true; m.promotionTo=ChessPieceType.QUEEN; m.promotionColor=p.color;}
                                out.add(m);
                            }
                        }
                    }
                    if (lastDoublePawn != null) {
                        int ldX = lastDoublePawn[0], ldY = lastDoublePawn[1];
                        if (x == (p.color==Color.WHITE ? 4 : 3)) {
                            if (Math.abs(ldY - y) == 1 && ldX == x) {
                                int capToX = x + dir;
                                int capToY = ldY;
                                ChessMove m = new ChessMove(x,y,capToX,capToY);
                                out.add(m);
                            }
                        }
                    }
                    break;
                case KNIGHT:
                    int[][] ksteps = {{1,2},{2,1},{-1,2},{-2,1},{1,-2},{2,-1},{-1,-2},{-2,-1}};
                    for (int[] s: ksteps) {
                        int tx=x+s[0], ty=y+s[1];
                        if (!onBoard(tx,ty)) continue;
                        ChessPiece t = getPiece(tx,ty);
                        if (t==null || t.color!=p.color) out.add(new ChessMove(x,y,tx,ty));
                    }
                    break;
                case BISHOP:
                    addSlidingMoves(out,x,y,p,new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}});
                    break;
                case ROOK:
                    addSlidingMoves(out,x,y,p,new int[][]{{1,0},{-1,0},{0,1},{0,-1}});
                    break;
                case QUEEN:
                    addSlidingMoves(out,x,y,p,new int[][]{{1,1},{1,-1},{-1,1},{-1,-1},{1,0},{-1,0},{0,1},{0,-1}});
                    break;
                case KING:
                    for (int dx=-1;dx<=1;dx++) for (int dy=-1;dy<=1;dy++){
                        if (dx==0&&dy==0) continue;
                        int tx=x+dx, ty=y+dy;
                        if (!onBoard(tx,ty)) continue;
                        ChessPiece t = getPiece(tx,ty);
                        if (t==null || t.color!=p.color) out.add(new ChessMove(x,y,tx,ty));
                    }
                    
                    // Castling Logic
                    if (!p.hasMoved) {
                        // Kingside (short) -> check right rook at y = 7
                        ChessPiece hRook = getPiece(x, 7);
                        if (hRook != null && hRook.type == ChessPieceType.ROOK && !hRook.hasMoved) {
                            if (getPiece(x, 5) == null && getPiece(x, 6) == null) {
                                ChessMove m = new ChessMove(x, y, x, y + 2);
                                m.isCastling = true;
                                out.add(m);
                            }
                        }
                        // Queenside (long) -> check left rook at y = 0
                        ChessPiece aRook = getPiece(x, 0);
                        if (aRook != null && aRook.type == ChessPieceType.ROOK && !aRook.hasMoved) {
                            if (getPiece(x, 1) == null && getPiece(x, 2) == null && getPiece(x, 3) == null) {
                                ChessMove m = new ChessMove(x, y, x, y - 2);
                                m.isCastling = true;
                                out.add(m);
                            }
                        }
                    }
                    break;
            }
            return out;
        }

        private void addSlidingMoves(List<ChessMove> out, int x, int y, ChessPiece p, int[][] dirs) {
            for (int[] d : dirs) {
                int tx = x + d[0], ty = y + d[1];
                while (onBoard(tx,ty)) {
                    ChessPiece t = getPiece(tx,ty);
                    if (t == null) out.add(new ChessMove(x,y,tx,ty));
                    else { if (t.color != p.color) out.add(new ChessMove(x,y,tx,ty)); break; }
                    tx += d[0]; ty += d[1];
                }
            }
        }

        boolean onBoard(int x,int y){return x>=0&&x<8&&y>=0&&y<8;}

        void applyMove(ChessMove m) {
            ChessPiece p = getPiece(m.fromX,m.fromY);
            if (p==null) return;
            // en passant capture detection: if pawn moves diagonally into empty square -> en passant
            if (p.type == ChessPieceType.PAWN && Math.abs(m.toY - m.fromY) == 1 && getPiece(m.toX, m.toY) == null && m.toX != m.fromX) {
                // capture pawn behind
                int capX = m.fromX;
                int capY = m.toY;
                setPiece(capX, capY, null);
            }
            
            // Castling Execution
            if (m.isCastling) {
                if (m.toY > m.fromY) { // Kingside
                    ChessPiece rook = getPiece(m.fromX, 7);
                    setPiece(m.fromX, 5, rook); // Move rook next to king
                    setPiece(m.fromX, 7, null);
                    if (rook != null) rook.hasMoved = true;
                } else { // Queenside
                    ChessPiece rook = getPiece(m.fromX, 0);
                    setPiece(m.fromX, 3, rook); // Move rook next to king
                    setPiece(m.fromX, 0, null);
                    if (rook != null) rook.hasMoved = true;
                }
            }
            
            p.hasMoved = true; // Mark piece as moved
            setPiece(m.toX,m.toY,p);
            setPiece(m.fromX,m.fromY,null);
        }

        ChessBoard copy() {
            ChessBoard c = new ChessBoard();
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = b[x][y];
                if (p != null) {
                    c.b[x][y] = new ChessPiece(p.color, p.type, x, y);
                    c.b[x][y].hasMoved = p.hasMoved; // Preserve movement state
                }
            }
            c.whiteToMove = whiteToMove;
            return c;
        }

        boolean isKingInCheck(Color kingColor) {
            // find king
            int kx=-1, ky=-1;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.type==ChessPieceType.KING && p.color==kingColor) { kx=x; ky=y; }
            }
            if (kx==-1) return true; // no king?
            // scan for enemy attacks (simple: generate pseudo-legal enemy moves and see if any hits king square)
            Color enemy = kingColor == Color.WHITE ? Color.BLACK : Color.WHITE;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == enemy) {
                    for (ChessMove m : pseudoLegalMoves(x,y, null)) {
                        if (m.toX == kx && m.toY == ky) return true;
                    }
                }
            }
            return false;
        }

        boolean isInCheckmate(boolean whiteToMoveSide) {
            Color c = whiteToMoveSide ? Color.WHITE : Color.BLACK;
            // if king not in check -> false
            if (!isKingInCheck(c)) return false;
            // if no legal move for side -> checkmate
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == c) {
                    if (!legalMoves(x,y, null).isEmpty()) return false;
                }
            }
            return true;
        }

        boolean isStalemate(boolean whiteToMoveSide) {
            Color c = whiteToMoveSide ? Color.WHITE : Color.BLACK;
            if (isKingInCheck(c)) return false;
            for (int x=0;x<8;x++) for (int y=0;y<8;y++) {
                ChessPiece p = getPiece(x,y);
                if (p != null && p.color == c) {
                    if (!legalMoves(x,y, null).isEmpty()) return false;
                }
            }
            return true;
        }
    }
}
