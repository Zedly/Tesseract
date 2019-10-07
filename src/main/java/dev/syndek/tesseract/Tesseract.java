package dev.syndek.tesseract;

import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang.ArrayUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.material.MaterialData;

final class Tesseract {

    private static final char[] BASE64_CHARS
            = {'0', '1', '2', '3', '4', '5', '6', '7',
                '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
                'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N',
                'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V',
                'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd',
                'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l',
                'm', 'n', 'o', 'p', 'q', 'r', 's', 't',
                'u', 'v', 'w', 'x', 'y', 'z', '+', '/'};
    private static final Pattern STACK_NOTATION_PATTERN = Pattern.compile("^(\\d+)x(\\d{1,2})\\+(\\d{1,2})", 0);
    private static final long MAX_CAPACITY = 9999999999L;

    private Material material;
    private long amount;

    /**
     * Creates an empty Tesseract
     */
    public Tesseract() {
        this(Material.AIR, 0);
    }

    /**
     * Creates a Tesseract with the specified contents
     *
     * @param mat
     * @param amount
     */
    public Tesseract(Material mat, long amount) {
        if (mat == null || amount <= 0) {
            mat = Material.AIR;
            amount = 0;
        }
        this.material = mat;
        this.amount = amount;
    }

    /**
     * Creates a Tesseract from legacy material ID and damage value. This exists
     * for compatibility with pre-1.13 Tesseracts
     *
     * @param legacyItemId
     * @param legacyDamage
     * @param amount
     * @deprecated
     */
    @Deprecated
    public Tesseract(long legacyItemId, long legacyDamage, long amount) {
        this(convertLegacyMaterial(legacyItemId, legacyDamage), amount);
    }

    /**
     * Finds all compatible items in the provided inventory, deposits them into
     * the Tesseract and removes them from the inventory. If called on a Block
     * inventory, the BlockState must be updated after completion
     *
     * @param inventory
     * @param sign
     * @return true if any items have been deposited
     */
    boolean depositAllAndUpdate(Inventory inventory, Sign sign) {
        // Inventory dumping only works on non-empty Tesseracts
        if (material == Material.AIR || amount == 0) {
            return false;
        }

        // Loop over each item slot
        boolean changed = false;
        int size = inventory.getSize();
        for (int i = 0; i < size; i++) {
            // Ignore empty and incompatible slots
            ItemStack stack = inventory.getItem(i);
            if (stack == null || !canHold(stack)) {
                continue;
            }

            // Deposit as much of the stack as possible
            changed = true;
            long storeAmount = Math.min(getCapacity() - amount, stack.getAmount());
            if (stack.getAmount() > storeAmount) {
                amount = getCapacity();
                stack.setAmount(stack.getAmount() - (int) storeAmount);
                inventory.setItem(i, stack);
            } else {
                amount += stack.getAmount();
                inventory.setItem(i, new ItemStack(Material.AIR));
            }
        }
        if (changed) {
            update(sign);
        }
        return changed;
    }

    /**
     * Deposit only the item stack held in the user's hand. Only applicable to
     * inventories with a primary slot (Player inventories).
     *
     * @param inventory
     * @param singleItem
     * @param sign
     * @return true if any items have been deposited
     */
    boolean depositHeldItemAndUpdate(PlayerInventory inventory, boolean singleItem, Sign sign) {
        ItemStack stack = inventory.getItemInMainHand();
        // Handle the held ItemStack. If it can't be held, we skip to handling the remaining items in the inventory if necessary.
        if (canHold(stack)) {
            if (isEmpty()) {
                material = stack.getType();
            }
            final long freeSpace = getCapacity() - amount;
            if (freeSpace == 0) {
                return false;
            }

            long storeAmount = Math.min(freeSpace, singleItem ? 1 : stack.getAmount());
            if (stack.getAmount() > storeAmount) {
                amount += storeAmount;
                stack.setAmount(stack.getAmount() - (int) storeAmount);
                inventory.setItemInMainHand(stack);
            } else {
                amount += stack.getAmount();
                inventory.setItemInMainHand(null);
            }
            update(sign);
            return true;
        }
        return false;
    }

    /**
     * Withdraws items from the Tesseract and spawns the item stack in the
     * provided Sign's location.
     *
     * @param sign
     * @param singleItem
     */
    void dispenseAndUpdate(Sign sign, boolean singleItem) {
        // Don't try to withdraw items if this Tesseract is empty, or there are no items being withdrawn.
        if (isEmpty() || amount <= 0) {
            return;
        }
        // Clamp withdrawal amount down to safe amount.
        long dispenseAmount = singleItem ? 1 : Math.min(amount, material.getMaxStackSize());
        amount -= dispenseAmount;
        final Item item = sign.getWorld().dropItem(sign.getLocation().add(0.5, 0.5, 0.5), new ItemStack(material, (int) dispenseAmount));
        item.setPickupDelay(0);
        update(sign);
    }

    public boolean isEmpty() {
        return amount == 0 || material == Material.AIR;
    }

    /**
     * Test compatibility of an ItemStack with the Tesseract. The ItemStack must
     * contain the same material as the Tesseract and hold no item metadata.
     *
     * @param stack
     * @return true if the Tesseract can accept items from the given ItemStack
     */
    private boolean canHold(final ItemStack stack) {
        // Don't try to add an empty stack.
        if (stack == null || stack.getType() == Material.AIR) {
            return false;
        }
        // Only allow items of the same type if this Tesseract isn't empty.
        if (!isEmpty() && material != stack.getType()) {
            return false;
        }
        // Don't allow items with meta, as the meta will be lost.
        if (stack.hasItemMeta()) {
            return false;
        }
        // At this point, we can assume the Tesseract can safely hold the item.
        return true;
    }

    /**
     * Write the state of the Tesseract to a sign.
     *
     * @param sign
     */
    public void update(Sign sign) {
        sign.setLine(0, ChatColor.DARK_BLUE + "[Tesseract]");
        sign.setLine(3, "");

        if (isEmpty()) {
            sign.setLine(1, "EMPTY");
            sign.setLine(2, "0");
        } else {
            // Even if the name of the material is cut off in-game,
            // The sign's data will retain the full string.
            // We trust that this will not be optimized away anytime soon
            sign.setLine(1, material.toString());
            sign.setLine(2, encodeStackNotation(amount, material.getMaxStackSize()));
        }
        sign.update(true);
    }

    private long getCapacity() {
        if (material == Material.AIR) {
            return 999999999L * 64L + 63L;
        } else if (material.getMaxStackSize() == 1) {
            return 999999999999999L;
        } else {
            return 1000000000L * (long) material.getMaxStackSize() - 1L;
        }
    }

    /**
     * Checks if the given Block is any of the materials representing a type of
     * sign in 1.14.4.
     *
     * @param block
     * @return true if the block is a sign
     */
    public static boolean isSign(Block block) {
        return block != null && isMaterialSign(block.getType());
    }

    public static boolean isMaterialSign(final Material material) {
        switch (material) {
            case ACACIA_SIGN:
            case ACACIA_WALL_SIGN:
            case BIRCH_SIGN:
            case BIRCH_WALL_SIGN:
            case DARK_OAK_SIGN:
            case DARK_OAK_WALL_SIGN:
            case JUNGLE_SIGN:
            case JUNGLE_WALL_SIGN:
            case OAK_SIGN:
            case OAK_WALL_SIGN:
            case SPRUCE_SIGN:
            case SPRUCE_WALL_SIGN:
                return true;
            default:
                return false;
        }
    }

    /**
     * Check if the given block meets all criteria for a valid Tesseract. - The
     * block must be a sign according to isSign(Block) - The sign must contain
     * the top line [Tesseract] in DARK_BLUE - The sign must match any official
     * encoding scheme (V1-V4)
     *
     * @param block
     * @return
     */
    public static boolean isTesseract(Block block) {
        if (!isSign(block)) {
            return false;
        }
        Sign sign = (Sign) block.getState();
        return isTesseractV4(sign) || isTesseractV3(sign) || isTesseractV2(sign) || isTesseractV1(sign);
    }

    /**
     * Create a Tesseract based on the contents of a sign. The sign must contain
     * a valid Tesseract encoding according to isTesseract(Sign). Supports all
     * encoding schemes ever published since 2012.
     *
     * @param sign
     * @return
     */
    public static Tesseract of(final Sign sign) {
        // Attempt to parse
        if (isTesseractV4(sign)) {
            return ofV4(sign);
        }
        if (isTesseractV3(sign)) {
            return ofV3(sign);
        }
        if (isTesseractV2(sign)) {
            return ofV2(sign);
        }
        if (isTesseractV1(sign)) {
            return ofV1(sign);
        }

        // Ignore non-tesseracts entirely.
        // TODO: Add error message if top line matches?
        return null;
    }

    /*
        Tesseract V4 encoding scheme (October 2019):
    
        (1) &1[Tesseract]
        (2) MATERIAL
        (3) AxB+C or A
        (4)
    
        A = Number of stacks if material is stackable, or number of items
        B = Max stack size of material contained
        C = Number of items % stack size
     */
    private static boolean isTesseractV4(Sign sign) {
        return (sign.getLine(0).equals(ChatColor.DARK_BLUE + "[Tesseract]")
                && sign.getLine(3).isEmpty()
                && (sign.getLine(2).matches("\\d+") || sign.getLine(2).matches("^(\\d+)x(\\d{1,2})\\+\\d{1,2}$"))
                && (sign.getLine(1).equals("EMPTY") || Material.valueOf(sign.getLine(1)) != null));
    }

    private static Tesseract ofV4(Sign sign) {
        if (sign.getLine(1).equals("EMPTY")) {
            return new Tesseract();
        }

        Material mat = Material.valueOf(sign.getLine(1));
        long amount = parseStackNotation(sign.getLine(2));
        if (mat == null || amount == -1) {
            return null;
        }
        return new Tesseract(mat, amount);
    }

    /*
        Tesseract V3 encoding scheme (Early 2019):
    
        (1) &1[Tesseract]
        (2) -
        (3) MATERIAL
        (4) Number of items (Base 10)
    
     */
    private static boolean isTesseractV3(Sign sign) {
        return (sign.getLine(0).equals(ChatColor.DARK_BLUE + "[Tesseract]")
                && sign.getLine(1).equals("-")
                && sign.getLine(3).matches("^\\d+$")
                && (sign.getLine(1).equals("EMPTY") || Material.valueOf(sign.getLine(2)) != null));
    }

    private static Tesseract ofV3(Sign sign) {
        if (sign.getLine(2).equals("EMPTY")) {
            return new Tesseract();
        }

        Material mat = Material.valueOf(sign.getLine(2));
        long amount = Long.parseLong(sign.getLine(3));
        if (mat == null || amount == -1) {
            return null;
        }
        return new Tesseract(mat, amount);
    }

    /*
        Tesseract V2 encoding scheme (2017):
    
        (1) &1[Tess&1eract]
        (2) MATERIAL
        (3) Ax64+B
        (4) [base64]
    
        (4) fully contains the Tesseract's state as a 90-bit structure encoded in base64.
            Encoding is MSB (bit 89) first. Fields:
            Bit 89-27: Item amount (Long.MAX_VALUE)
            Bit 26-15: Item material ID
            Bit 14-0: Item damage value
     */
    private static boolean isTesseractV2(Sign sign) {
        return (sign.getLine(0).equals(ChatColor.DARK_BLUE + "[Tess" + ChatColor.DARK_BLUE + "eract]")
                && sign.getLine(2).matches("^(\\d+)x64\\+\\d{1,2}$")
                && sign.getLine(3).matches("^[0-9A-Za-z+/]{15}$")
                && Material.valueOf(sign.getLine(1)) != null);
    }

    private static Tesseract ofV2(Sign sign) {
        String dataField = sign.getLine(3);
        long upperBits = parseLong64(dataField, 0, 7);
        long lowerBits = parseLong64(dataField, 7, 15);

        long legacyItemId = ((lowerBits >> 15) & 0xFFF);
        long legacyDamage = (lowerBits) & 0x7FFFL;
        long amount = ((upperBits << 21) & 0x7FFFFFFFFFE00000L) | ((lowerBits >> 27) & 0x1FFFFF);

        return new Tesseract(legacyItemId, legacyDamage, amount);
    }

    /*
        Tesseract V1 encoding (2012):
    
        (1) &1[Tesseract]
        (2) MATERIAL
        (3) Ax64+B
        (4) [base16]
    
        (4) fully contains the Tesseract's state as a 60-bit structure encoded in base16.
            Encoding is MSB (bit 59) first. Fields:
            Bit 59-32: Item amount (2^28)
            Bit 31-16: Item material ID
            Bit 15-0: Item damage value
     */
    private static boolean isTesseractV1(Sign sign) {
        return (sign.getLine(0).equals(ChatColor.DARK_BLUE + "[Tess" + ChatColor.DARK_BLUE + "eract]")
                && sign.getLine(2).matches("^(\\d+)x64\\+\\d{1,2}$")
                && sign.getLine(3).matches("^[0-9A-F]{15}$")
                && Material.valueOf(sign.getLine(1)) != null);
    }

    private static Tesseract ofV1(Sign sign) {
        String dataField = sign.getLine(3);
        if (!dataField.matches("^[0-9A-Fa-f]{15}")) {
            return null;
        }
        long dataBits = Long.parseLong(sign.getLine(3), 16);

        long legacyItemId = ((dataBits >> 16) & 0xFFFF);
        long legacyDamage = (dataBits) & 0xFFFF;
        long amount = dataBits >> 32;

        return new Tesseract(legacyItemId, legacyDamage, amount);
    }

    
    /**
     * Formats the number of items in a Tesseract in stacks+items.
     * Respects the contained material's stack size. 
     * Amounts of unstackable items are displayed as simple natural numbers.
     * @param amount
     * @param stackSize
     * @return 
     */
    private static String encodeStackNotation(long amount, long stackSize) {
        if(stackSize == 1) {
            return String.valueOf(amount);
        }
        return (amount / stackSize) + "x" + stackSize + "+" + (amount % stackSize);
    }

    /**
     * Reconstructs the true number of items from the stacks+items notation.
     * @param amount
     * @return 
     */
    private static long parseStackNotation(String amount) {
        Matcher matcher = STACK_NOTATION_PATTERN.matcher(amount);
        if (!matcher.find()) {
            if (amount.matches("\\d+")) {
                return Long.parseLong(amount);
            } else {
                return -1;
            }
        }
        String s_stacks = matcher.group(1);
        String s_stackSize = matcher.group(2);
        String s_items = matcher.group(3);
        return Long.parseLong(s_stacks) * Long.parseLong(s_stackSize) + Long.parseLong(s_items);
    }

    /**
     * Parses substrings of the Tesseract base64 encoding scheme.
     * @param s_source
     * @param start
     * @param end
     * @return the parsed bits as a long integer
     */
    public static long parseLong64(String s_source, int start, int end) {
        char[] source = s_source.toCharArray();
        long result = 0;
        for (int i = start; i < end; i++) {
            result |= ((long) (ArrayUtils.indexOf(BASE64_CHARS, source[i])) << (6 * (end - i - 1)));
        }
        return result;
    }

    /**
     * Converts the legacy item-ID/damage encoding to the modern Material encoding.
     * @param legacyItemId
     * @param legacyDamage
     * @return the corresponding material or null if the magic numbers cannot be parsed
     */
    @SuppressWarnings("deprecation")
    public static Material convertLegacyMaterial(long legacyItemId, long legacyDamage) {
        for (Material i : EnumSet.allOf(Material.class)) {
            if (i.getId() == legacyItemId) {
                return Bukkit.getUnsafe().fromLegacy(new MaterialData(i, (byte) legacyDamage));
            }
        }
        return null;
    }

    /**
     * Produces a String containing the Tesseract's state for debugging purposes.
     * @return 
     */
    @Override
    public String toString() {
        if (material == Material.AIR) {
            return "{Tesseract: EMPTY}";
        } else if (material.getMaxStackSize() == 1) {
            return "{Tesseract: " + amount + " " + material + "}";
        } else {
            return "{Tesseract: " + (amount / material.getMaxStackSize()) + "x" + material.getMaxStackSize() + "+" + (amount % material.getMaxStackSize()) + " " + material + "}";
        }
    }
}
