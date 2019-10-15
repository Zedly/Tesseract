package dev.syndek.tesseract;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import java.util.HashMap;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Dropper;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;

final class TesseractListener implements Listener {

    private static final BlockFace[] CARDINAL_FACES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final TesseractListener INSTANCE = new TesseractListener();
    private static final RegionContainer CONTAINER = WorldGuard.getInstance().getPlatform().getRegionContainer();
    private static final HashMap<Block, Integer> DROPPER_POWER_CACHE = new HashMap<>();
    private static final HashMap<Player, Long> DOUBLE_CLICK_TIMER = new HashMap<>();
    private static final long DOUBLE_CLICK_MAX_MILLIS = 500;

    private TesseractListener() {
    }

    static TesseractListener getInstance() {
        return INSTANCE;
    }

    /**
     * A Tesseract can be created by anyone with the appropriate permission by placing a sign with the text "[Tesseract]" in the top line.
     * The top line will turn blue and the other lines will initialize to represent an empty Tesseract.
     * @param event 
     */
    @EventHandler
    public void onTesseractCreate(final SignChangeEvent event) {
        final String topLine = event.getLine(0);
        if (topLine == null || !topLine.equalsIgnoreCase("[Tesseract]")) {
            return;
        }
        if (!event.getPlayer().hasPermission("tesseract.create")) {
            event.getPlayer().sendMessage("§cYou do not have permission to create Tesseracts.");
            event.setLine(0, ChatColor.DARK_RED + "[Tesseract]");
        } else {
            Sign sign = (Sign) event.getBlock().getState();
            Tesseract tesseract = new Tesseract();
            tesseract.update(sign);
            event.setCancelled(true);
        }
    }

    /**
     * Left clicking withdraws items from a Tesseract, right clicking deposits items.
     * Sneaking causes the transaction to happen on a single-item basis, normally the max. stack size of the material is moved.
     * If a player right clicks in intervals of 500ms or less (or holds down the right mouse button), each Tesseract will absorb all compatible items from the player's inventory.
     * @param event 
     */
    @EventHandler
    public void onTesseractClick(final PlayerInteractEvent event) {
        // Reject non-Tesseracts
        if (!Tesseract.isTesseract(event.getClickedBlock())) {
            return;
        }

        // Reject players lacking permission
        Player player = event.getPlayer();
        if (!canUseBlock(player, event.getClickedBlock())) {
            player.sendMessage("§cYou do not have permission to use this Tesseract!");
            event.setCancelled(true);
            return;
        }

        // Load Tesseract from sign text
        Sign sign = (Sign) event.getClickedBlock().getState();
        Tesseract tesseract = Tesseract.of(sign);
        if (tesseract == null) {
            return;
        }

        // Decide mode of interaction and perform
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (player.isSneaking()) {
                tesseract.depositHeldItemAndUpdate(player.getInventory(), true, sign);
            } else if (isDoubleClick(player)) {
                tesseract.depositAllAndUpdate(player.getInventory(), sign);
            } else {
                tesseract.depositHeldItemAndUpdate(player.getInventory(), false, sign);
            }
            rememberClick(player);
            event.setCancelled(true);
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Always cancel the event if items are going to be withdrawn from the Tesseract.
            // This prevents strange instances in which a Tesseract can both drop items and break at the same time.
            // If the Tesseract IS empty, then don't cancel the event,
            // as cancelling a PlayerInteractEvent will also prevent a block from breaking.
            if (!tesseract.isEmpty()) {
                tesseract.dispenseAndUpdate(sign, player.isSneaking());
                event.setCancelled(true);
            }
        }
    }

    /**
     * We want non-empty Tesseracts to be unbreakable. 
     * This method cancels a BlockBreakEvent if it concerns a Tesseract which is not empty.
     * @param event
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTesseractBreak(final BlockBreakEvent event) {
        if (!Tesseract.isSign(event.getBlock())) {
            return;
        }
        Sign sign = (Sign) event.getBlock().getState();
        Tesseract tesseract = Tesseract.of(sign);
        if (tesseract == null || tesseract.isEmpty()) {
            return;
        }
        event.setCancelled(true);
    }

    
    /**
     * A dropper with Tesseracts attached dumps its contents into the Tesseracts on a positive redstone edge.
     * Pulling this off is tricky because of unexplained overrides in the server code. 
     * This approach catches an event which precedes the BlockDispenseEvent in order to avoid conflict with the server over the dropper's inventory contents.
     * The primitive compare causes the method to return after approx. 200 nanoseconds, the power cache lookup returns after 2000ns. This is reasonably efficient.
     * @param evt 
     */
    @EventHandler
    public void onDropperRedstone(BlockPhysicsEvent evt) {
        // Reject non-droppers
        if (evt.isCancelled() || evt.getBlock().getType() != Material.DROPPER) {
            return;
        }
        
        Block dropperBlock = evt.getBlock();
        int power = dropperBlock.getBlockPower();
        Integer oldPower = DROPPER_POWER_CACHE.put(dropperBlock, power);
        
        // Accept only positive edges
        if (oldPower != null && oldPower == 0 && power > 0) {
            Dropper dropper = (Dropper) dropperBlock.getState();
            Inventory dropperSnapshotInv = dropper.getSnapshotInventory();
            // Make bulk deposit into each Tesseract
            for (BlockFace face : CARDINAL_FACES) {
                if (Tesseract.isTesseract(dropperBlock.getRelative(face))) {
                    Sign sign = (Sign) dropperBlock.getRelative(face).getState();
                    Tesseract tesseract = Tesseract.of(sign);
                    tesseract.depositAllAndUpdate(dropperSnapshotInv, sign);
                    dropper.update(true, true);
                }
            }
        }

    }

    
    /**
     * Determine a player's permission to use Tesseracts in a given location.
     * Interacts with Worldguard to enforce protection of Tesseracts on claimed terrain. Additional protection plugins may be added upon request
     * @param player
     * @param block
     * @return 
     */
    private boolean canUseBlock(final Player player, Block block) {
        // If a player has the Tesseract anywhere permission, we can bypass all WorldGuard checks.
        if (!player.hasPermission("tesseract.use")) {
            //player.sendMessage("§cYou do not have permission to use Tesseracts.");
            return false;
        }
        if (player.hasPermission("tesseract.use.anywhere")) {
            return true;
        }

        final LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        // If not, we test if they have WorldGuard bypass permissions for that world (allowing them to build anywhere).
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(localPlayer, localPlayer.getWorld())) {
            return true;
        } else {
            // Finally, if they have no bypass permissions, we test whether or not they can build in that area.
            return CONTAINER.createQuery().testBuild(BukkitAdapter.adapt(block.getLocation()), localPlayer);
        }
    }

    public static boolean isDoubleClick(Player player) {
        return DOUBLE_CLICK_TIMER.containsKey(player) && (System.currentTimeMillis() - DOUBLE_CLICK_TIMER.get(player)) < DOUBLE_CLICK_MAX_MILLIS;
    }

    public static void rememberClick(Player player) {
        DOUBLE_CLICK_TIMER.put(player, System.currentTimeMillis());
    }
}
