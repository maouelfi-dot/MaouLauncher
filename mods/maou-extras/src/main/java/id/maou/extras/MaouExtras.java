package id.maou.extras;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * Maou Extras - semua logika di sisi server (host / dunia sendiri), jadi teman tidak perlu memasang mod.
 * Fitur: tab list (koin, K/D), koin dari kill, toko (/shop), duduk di tangga, emote, kosmetik partikel.
 */
public class MaouExtras implements ModInitializer {
    private static final Gson GSON = new Gson();
    private static final Map<UUID, PlayerData> DATA = new HashMap<>();
    private static final Set<AreaEffectCloud> SEATS = new HashSet<>();
    private static final Map<UUID, Emote> EMOTES = new HashMap<>();
    private static File saveFile;
    private static int tickCounter;
    private static boolean scoreboardDirty = true;

    /** id -> {harga, nama tampil}. Item biasa. */
    private static final Map<String, Object[]> ITEMS = new LinkedHashMap<>();
    /** kosmetik: id -> harga. */
    private static final Map<String, Integer> COSMETICS = new LinkedHashMap<>();
    private static final List<String> EMOTE_NAMES = List.of("wave", "heart", "dance", "cheer");

    static {
        ITEMS.put("apel_emas", new Object[]{150, "Apel Emas"});
        ITEMS.put("mutiara_ender", new Object[]{80, "Mutiara Ender x4"});
        ITEMS.put("berlian", new Object[]{120, "Berlian x3"});
        ITEMS.put("besi", new Object[]{60, "Besi x16"});
        ITEMS.put("steak", new Object[]{40, "Steak x16"});
        ITEMS.put("netherite", new Object[]{900, "Netherite Ingot"});
        COSMETICS.put("halo", 300);
        COSMETICS.put("api", 250);
        COSMETICS.put("hati", 200);
        COSMETICS.put("sihir", 350);
    }

    private static ItemStack stackFor(String id) {
        return switch (id) {
            case "apel_emas" -> new ItemStack(Items.GOLDEN_APPLE, 1);
            case "mutiara_ender" -> new ItemStack(Items.ENDER_PEARL, 4);
            case "berlian" -> new ItemStack(Items.DIAMOND, 3);
            case "besi" -> new ItemStack(Items.IRON_INGOT, 16);
            case "steak" -> new ItemStack(Items.COOKED_BEEF, 16);
            case "netherite" -> new ItemStack(Items.NETHERITE_INGOT, 1);
            default -> ItemStack.EMPTY;
        };
    }

    private static class Emote {
        final String type;
        int left;
        Emote(String type, int left) { this.type = type; this.left = left; }
    }

    private static PlayerData data(ServerPlayer p) {
        return DATA.computeIfAbsent(p.getUUID(), k -> new PlayerData());
    }

    @Override
    public void onInitializeServer() { /* tidak dipakai */ }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onStart);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> save());
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer dead) {
                data(dead).deaths++;
                scoreboardDirty = true;
            }
            if (source.getEntity() instanceof ServerPlayer killer && killer != entity) {
                PlayerData d = data(killer);
                if (entity instanceof ServerPlayer) {
                    d.kills++;
                    d.coins += 50;
                } else if (entity instanceof Monster) {
                    d.coins += 5;
                }
                scoreboardDirty = true;
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (player.isShiftKeyDown() || !player.getMainHandItem().isEmpty() || player.isPassenger()) return InteractionResult.PASS;
            BlockPos pos = hit.getBlockPos();
            if (!(level.getBlockState(pos).getBlock() instanceof StairBlock)) return InteractionResult.PASS;
            AreaEffectCloud seat = new AreaEffectCloud(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5);
            seat.setRadius(0f);
            seat.setDuration(Integer.MAX_VALUE);
            seat.setNoGravity(true);
            level.addFreshEntity(seat);
            player.startRiding(seat, true);
            SEATS.add(seat);
            return InteractionResult.SUCCESS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private void onStart(MinecraftServer server) {
        saveFile = new File(server.getWorldPath(LevelResource.ROOT).toFile(), "maou_extras.json");
        try {
            if (saveFile.isFile()) {
                String json = Files.readString(saveFile.toPath());
                Map<UUID, PlayerData> loaded = GSON.fromJson(json, new TypeToken<Map<UUID, PlayerData>>() {}.getType());
                if (loaded != null) DATA.putAll(loaded);
            }
        } catch (Exception ignored) { }
        run(server, "scoreboard objectives add maou_kills dummy \"Kill\"");
        run(server, "scoreboard objectives setdisplay list maou_kills");
    }

    private static void run(MinecraftServer server, String cmd) {
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), cmd);
        } catch (Exception ignored) { }
    }

    private static void save() {
        if (saveFile == null) return;
        try {
            Files.writeString(saveFile.toPath(), GSON.toJson(DATA));
        } catch (Exception ignored) { }
    }

    private void onTick(MinecraftServer server) {
        tickCounter++;
        // kursi: bersihkan yang sudah kosong
        if (tickCounter % 5 == 0 && !SEATS.isEmpty()) {
            Iterator<AreaEffectCloud> it = SEATS.iterator();
            while (it.hasNext()) {
                AreaEffectCloud s = it.next();
                if (s.isRemoved() || s.getPassengers().isEmpty()) {
                    s.discard();
                    it.remove();
                }
            }
        }
        // emote
        if (!EMOTES.isEmpty()) {
            Iterator<Map.Entry<UUID, Emote>> it = EMOTES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Emote> e = it.next();
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                Emote em = e.getValue();
                if (p == null || --em.left <= 0) { it.remove(); continue; }
                ServerLevel lvl = p.serverLevel();
                switch (em.type) {
                    case "wave" -> { if (em.left % 6 == 0) p.swing(InteractionHand.MAIN_HAND, true); }
                    case "heart" -> { if (em.left % 8 == 0) lvl.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2.2, p.getZ(), 3, 0.3, 0.2, 0.3, 0);}
                    case "cheer" -> {
                        if (em.left % 6 == 0) p.swing(InteractionHand.MAIN_HAND, true);
                        if (em.left % 10 == 0) lvl.sendParticles(ParticleTypes.NOTE, p.getX(), p.getY() + 2.2, p.getZ(), 2, 0.4, 0.2, 0.4, 0);
                    }
                    case "dance" -> p.connection.teleport(p.getX(), p.getY(), p.getZ(), p.getYRot() + 18f, p.getXRot());
                    default -> { }
                }
            }
        }
        // kosmetik
        if (tickCounter % 4 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String c = data(p).cosmetic;
                if (c == null || c.isEmpty()) continue;
                ServerLevel lvl = p.serverLevel();
                double t = tickCounter * 0.25;
                switch (c) {
                    case "halo" -> {
                        for (int i = 0; i < 6; i++) {
                            double a = t + i * Math.PI / 3;
                            lvl.sendParticles(ParticleTypes.END_ROD, p.getX() + Math.cos(a) * 0.35, p.getY() + 2.15, p.getZ() + Math.sin(a) * 0.35, 1, 0, 0, 0, 0);
                        }
                    }
                    case "api" -> lvl.sendParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 0.1, p.getZ(), 2, 0.25, 0.05, 0.25, 0.01);
                    case "hati" -> lvl.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2.3, p.getZ(), 1, 0.3, 0.1, 0.3, 0);
                    case "sihir" -> lvl.sendParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.2, p.getZ(), 6, 0.5, 0.8, 0.5, 0.2);
                    default -> { }
                }
            }
        }
        // tab list + papan skor + simpan berkala
        if (tickCounter % 40 == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                PlayerData d = data(p);
                Component header = Component.literal("\n  MAOU SERVER  \n").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
                Component footer = Component.literal("\nKoin " + d.coins + "  |  Kill " + d.kills + "  |  Mati " + d.deaths
                        + "\n/shop  /emote  /cosmetic\n").withStyle(ChatFormatting.GRAY);
                p.connection.send(new ClientboundTabListPacket(header, footer));
            }
            if (scoreboardDirty) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    run(server, "scoreboard players set " + p.getGameProfile().getName() + " maou_kills " + data(p).kills);
                }
                scoreboardDirty = false;
            }
        }
        if (tickCounter % 2400 == 0) save();
    }

    private static Component btn(String label, String cmd, ChatFormatting color) {
        MutableComponent c = Component.literal(label).withStyle(Style.EMPTY.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)));
        return c;
    }

    private void registerCommands(com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("coins").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayerOrException();
            PlayerData d = data(p);
            ctx.getSource().sendSuccess(() -> Component.literal("Koin " + d.coins + " | Kill " + d.kills + " | Mati " + d.deaths), false);
            return 1;
        }));

        dispatcher.register(Commands.literal("shop")
            .executes(ctx -> {
                ServerPlayer p = ctx.getSource().getPlayerOrException();
                PlayerData d = data(p);
                ctx.getSource().sendSuccess(() -> Component.literal("=== TOKO MAOU === (koin: " + d.coins + ")").withStyle(ChatFormatting.LIGHT_PURPLE), false);
                for (Map.Entry<String, Object[]> e : ITEMS.entrySet()) {
                    String id = e.getKey();
                    Object[] v = e.getValue();
                    ctx.getSource().sendSuccess(() -> Component.literal((String) v[1] + " - " + v[0] + " koin  ")
                        .append(btn("[Beli]", "/shop buy " + id, ChatFormatting.GREEN)), false);
                }
                for (Map.Entry<String, Integer> e : COSMETICS.entrySet()) {
                    String id = e.getKey();
                    ctx.getSource().sendSuccess(() -> Component.literal("Kosmetik " + id + " - " + e.getValue() + " koin  ")
                        .append(btn("[Beli]", "/shop buy " + id, ChatFormatting.GREEN)), false);
                }
                return 1;
            })
            .then(Commands.literal("buy").then(Commands.argument("item", StringArgumentType.word())
                .suggests((c, b) -> {
                    List<String> all = new ArrayList<>(ITEMS.keySet());
                    all.addAll(COSMETICS.keySet());
                    return SharedSuggestionProvider.suggest(all, b);
                })
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerData d = data(p);
                    String id = StringArgumentType.getString(ctx, "item");
                    if (ITEMS.containsKey(id)) {
                        int price = (Integer) ITEMS.get(id)[0];
                        if (d.coins < price) {
                            ctx.getSource().sendFailure(Component.literal("Koin kurang (butuh " + price + ")"));
                            return 0;
                        }
                        d.coins -= price;
                        ItemStack s = stackFor(id);
                        if (!p.getInventory().add(s)) p.drop(s, false);
                        ctx.getSource().sendSuccess(() -> Component.literal("Dibeli: " + ITEMS.get(id)[1]).withStyle(ChatFormatting.GREEN), false);
                        return 1;
                    }
                    if (COSMETICS.containsKey(id)) {
                        if (d.owned.contains(id)) {
                            ctx.getSource().sendFailure(Component.literal("Kosmetik ini sudah kamu miliki. Pakai: /cosmetic " + id));
                            return 0;
                        }
                        int price = COSMETICS.get(id);
                        if (d.coins < price) {
                            ctx.getSource().sendFailure(Component.literal("Koin kurang (butuh " + price + ")"));
                            return 0;
                        }
                        d.coins -= price;
                        d.owned.add(id);
                        d.cosmetic = id;
                        ctx.getSource().sendSuccess(() -> Component.literal("Kosmetik " + id + " dibeli dan dipakai!").withStyle(ChatFormatting.GREEN), false);
                        return 1;
                    }
                    ctx.getSource().sendFailure(Component.literal("Barang tidak ditemukan. Ketik /shop"));
                    return 0;
                }))));

        dispatcher.register(Commands.literal("cosmetic")
            .then(Commands.argument("nama", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(COSMETICS.keySet(), b))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    PlayerData d = data(p);
                    String n = StringArgumentType.getString(ctx, "nama");
                    if (n.equals("off")) {
                        d.cosmetic = "";
                        ctx.getSource().sendSuccess(() -> Component.literal("Kosmetik dimatikan"), false);
                        return 1;
                    }
                    if (!d.owned.contains(n)) {
                        ctx.getSource().sendFailure(Component.literal("Kamu belum punya kosmetik itu. Beli di /shop"));
                        return 0;
                    }
                    d.cosmetic = n;
                    ctx.getSource().sendSuccess(() -> Component.literal("Kosmetik dipakai: " + n), false);
                    return 1;
                })));

        dispatcher.register(Commands.literal("emote")
            .then(Commands.argument("nama", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(EMOTE_NAMES, b))
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    String n = StringArgumentType.getString(ctx, "nama");
                    if (!EMOTE_NAMES.contains(n)) {
                        ctx.getSource().sendFailure(Component.literal("Emote: " + String.join(", ", EMOTE_NAMES)));
                        return 0;
                    }
                    EMOTES.put(p.getUUID(), new Emote(n, 80));
                    return 1;
                })));
    }
}
