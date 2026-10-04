package tntcombo;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Клавиша G: если в хотбаре есть динамит, раздатчик и редстоуновый блок,
 * ставит редстоун-блок туда, куда смотришь, рядом с ним раздатчик,
 * и кладёт в раздатчик динамит. Раздатчик при этом сразу не стреляет.
 */
@Environment(EnvType.CLIENT)
public class TntComboMod implements ClientModInitializer {

    private enum Stage { IDLE, WAIT_REDSTONE, WAIT_DISPENSER, WAIT_GUI }

    private static final int TIMEOUT_TICKS = 40;
    private static final Direction[] DISPENSER_SIDES = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    private static KeyBinding triggerKey;

    private Stage stage = Stage.IDLE;
    private int timeout;
    private int previousSlot;
    private int redstoneSlot, dispenserSlot;
    private BlockPos redstonePos, dispenserPos;
    private Direction dispenserSide;

    @Override
    public void onInitializeClient() {
        triggerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tntcombo.place",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "key.category.tntcombo.main"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (triggerKey.wasPressed()) {
                start(client);
            }
            tick(client);
        });
    }

    private void start(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) return;
        if (stage != Stage.IDLE || client.currentScreen != null) return;

        if (player.isSneaking()) {
            msg(player, "Отпусти Shift");
            return;
        }

        redstoneSlot = findInHotbar(player, Items.REDSTONE_BLOCK);
        dispenserSlot = findInHotbar(player, Items.DISPENSER);
        int tntSlot = findInHotbar(player, Items.TNT);
        if (redstoneSlot < 0 || dispenserSlot < 0 || tntSlot < 0) {
            msg(player, "В хотбаре нужны динамит, раздатчик и редстоуновый блок");
            return;
        }

        if (!(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            msg(player, "Наведись на блок");
            return;
        }

        redstonePos = hit.getBlockPos().offset(hit.getSide());
        if (!canPlaceAt(client, redstonePos)) {
            msg(player, "Здесь недостаточно места");
            return;
        }

        dispenserSide = null;
        for (Direction side : DISPENSER_SIDES) {
            if (side == hit.getSide().getOpposite()) continue;
            if (canPlaceAt(client, redstonePos.offset(side))) {
                dispenserSide = side;
                break;
            }
        }
        if (dispenserSide == null) {
            msg(player, "Здесь недостаточно места");
            return;
        }
        dispenserPos = redstonePos.offset(dispenserSide);

        previousSlot = player.getInventory().selectedSlot;
        selectSlot(player, redstoneSlot);
        interact(client, hit);

        stage = Stage.WAIT_REDSTONE;
        timeout = TIMEOUT_TICKS;
    }

    private void tick(MinecraftClient client) {
        if (stage == Stage.IDLE) return;
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            stage = Stage.IDLE;
            return;
        }

        if (--timeout <= 0) {
            if (player.currentScreenHandler != player.playerScreenHandler) {
                player.closeHandledScreen();
            }
            selectSlot(player, previousSlot);
            stage = Stage.IDLE;
            msg(player, "Не получилось (сервер не принял установку)");
            return;
        }

        switch (stage) {
            case WAIT_REDSTONE -> {
                if (client.world.getBlockState(redstonePos).isOf(Blocks.REDSTONE_BLOCK)) {
                    selectSlot(player, dispenserSlot);
                    Vec3d face = Vec3d.ofCenter(redstonePos).add(
                            dispenserSide.getOffsetX() * 0.5,
                            dispenserSide.getOffsetY() * 0.5,
                            dispenserSide.getOffsetZ() * 0.5);
                    interact(client, new BlockHitResult(face, dispenserSide, redstonePos, false));
                    stage = Stage.WAIT_DISPENSER;
                    timeout = TIMEOUT_TICKS;
                }
            }
            case WAIT_DISPENSER -> {
                if (client.world.getBlockState(dispenserPos).isOf(Blocks.DISPENSER)) {
                    Vec3d top = Vec3d.ofCenter(dispenserPos).add(0, 0.5, 0);
                    interact(client, new BlockHitResult(top, Direction.UP, dispenserPos, false));
                    stage = Stage.WAIT_GUI;
                    timeout = TIMEOUT_TICKS;
                }
            }
            case WAIT_GUI -> {
                if (player.currentScreenHandler instanceof Generic3x3ContainerScreenHandler handler
                        && handler != player.playerScreenHandler) {
                    for (Slot slot : handler.slots) {
                        if (slot.inventory instanceof PlayerInventory
                                && slot.getIndex() < 9
                                && slot.getStack().isOf(Items.TNT)) {
                            client.interactionManager.clickSlot(
                                    handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
                            break;
                        }
                    }
                    player.closeHandledScreen();
                    selectSlot(player, previousSlot);
                    stage = Stage.IDLE;
                    msg(player, "Готово");
                }
            }
            default -> { }
        }
    }

    private static int findInHotbar(ClientPlayerEntity player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static boolean canPlaceAt(MinecraftClient client, BlockPos pos) {
        return client.world.getBlockState(pos).isReplaceable()
                && !client.player.getBoundingBox().intersects(new Box(pos));
    }

    private static void selectSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;
        player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private static void interact(MinecraftClient client, BlockHitResult hit) {
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private static void msg(ClientPlayerEntity player, String text) {
        player.sendMessage(Text.literal(text), true);
    }
}
