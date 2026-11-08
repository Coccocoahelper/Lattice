package net.h2020.bots.client;

import net.minecraft.server.v1_8_R3.Block;
import net.minecraft.server.v1_8_R3.Item;
import net.minecraft.server.v1_8_R3.ItemBlock;
import net.minecraft.server.v1_8_R3.ItemStack;

/**
 * Client-side predictions that a real client gets from running block/item code on its own world copy. We cannot run
 * the server's versions (they would act on the server), so the outcomes that matter for the packet stream
 * (does the right click get consumed by the block? does the item "use on block" succeed and swing?) are tabulated.
 */
final class ClientBlocks {
    private ClientBlocks() {}

    private static final boolean[] ACTIVATES = new boolean[4096];
    private static final boolean[] ITEM_USE_ON_BLOCK = new boolean[32000];

    static {
        int[] blocks = {
                54, 146, 130,           // chest, trapped chest, ender chest
                58, 61, 62,             // crafting table, furnace, lit furnace
                23, 158, 154,           // dispenser, dropper, hopper
                117, 116, 145, 138,     // brewing stand, enchanting table, anvil, beacon
                26,                     // bed
                64, 193, 194, 195, 196, 197, // wooden doors
                96,                     // wooden trapdoor
                107, 183, 184, 185, 186, 187, // fence gates
                69, 77, 143,            // lever, buttons
                25, 84,                 // note block, jukebox
                93, 94, 149, 150,       // repeaters, comparators
                92, 151, 178,           // cake, daylight sensors
                122, 137, 118           // dragon egg, command block, cauldron
        };
        for (int id : blocks) ACTIVATES[id] = true;
        int[] items = {
                259, 385, 383,          // flint and steel, fire charge, spawn egg
                323, 324, 330, 427, 428, 429, 430, 431, // sign, doors
                355, 397, 331,          // bed, skull, redstone
                295, 361, 362, 372, 391, 392, // seeds, nether wart, carrot, potato
                351, 338, 354, 356, 404, 390, // dye (bonemeal/cocoa), reeds, cake, repeater, comparator, flower pot
                290, 291, 292, 293, 294, // hoes
                328, 342, 343, 407, 408, // minecarts
                416, 389, 321, 379, 380 // armor stand, item frame, painting, brewing stand, cauldron
        };
        for (int id : items) ITEM_USE_ON_BLOCK[id] = true;
    }

    /** Block.onBlockActivated returns true on the client. */
    static boolean activates(Block block) {
        int id = Block.getId(block);
        return id >= 0 && id < ACTIVATES.length && ACTIVATES[id];
    }

    /** ItemStack.onItemUse succeeded on the client (block placed / item applied) -> the client swings. */
    static boolean itemUseOnBlockSucceeds(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof ItemBlock) return true;
        int id = Item.getId(item);
        return id >= 0 && id < ITEM_USE_ON_BLOCK.length && ITEM_USE_ON_BLOCK[id];
    }
}
