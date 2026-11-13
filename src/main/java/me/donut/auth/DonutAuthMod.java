package me.donut.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;

public class DonutAuthMod implements ModInitializer {

    private final Set<UUID> loggedInPlayers = new HashSet<>();
    private final Map<UUID, Vec3d> frozenPositions = new HashMap<>();
    private Map<String, String> passwords = new HashMap<>();
    private File passwordFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        loadPasswords();

        // Register /register and /login commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {

            // /register <password> <finalpassword>
            dispatcher.register(CommandManager.literal("register")
                .then(CommandManager.argument("password", StringArgumentType.word())
                    .then(CommandManager.argument("finalpassword", StringArgumentType.word())
                        .executes(context -> {
                            ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                            UUID uuid = player.getUuid();

                            if (loggedInPlayers.contains(uuid)) {
                                player.sendMessage(Text.literal("§cʏᴏᴜ ᴀʀᴇ ᴀʟʀᴇᴀᴅʏ ʟᴏɢɢᴇᴅ ɪɴ!"), false);
                                return 1;
                            }

                            if (isRegistered(uuid)) {
                                player.sendMessage(Text.literal("§cʏᴏᴜ ᴀʀᴇ ᴀʟʀᴇᴀᴅʏ ʀᴇɢɪsᴛᴇʀᴇᴅ! ᴜsᴇ §e/login <password>"), false);
                                playErrorSound(player);
                                return 1;
                            }

                            String pass1 = StringArgumentType.getString(context, "password");
                            String pass2 = StringArgumentType.getString(context, "finalpassword");

                            if (!pass1.equals(pass2)) {
                                player.sendMessage(Text.literal("§cᴘᴀssᴡᴏʀᴅs ᴅᴏ ɴᴏᴛ ᴍᴀᴛᴄʜ! ᴛʀʏ ᴀɢᴀɪɴ."), false);
                                playErrorSound(player);
                                return 1;
                            }

                            passwords.put(uuid.toString(), pass1);
                            savePasswords();

                            player.sendMessage(Text.literal("§a§l✔ §aʏᴏᴜ ʜᴀᴠᴇ sᴜᴄᴄᴇssғᴜʟʟʏ ʀᴇɢɪsᴛᴇʀᴇᴅ!"), false);
                            authenticate(player);
                            return 1;
                        })
                    )
                )
            );

            // /login <password>
            dispatcher.register(CommandManager.literal("login")
                .then(CommandManager.argument("password", StringArgumentType.word())
                    .executes(context -> {
                        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
                        UUID uuid = player.getUuid();

                        if (loggedInPlayers.contains(uuid)) {
                            player.sendMessage(Text.literal("§cʏᴏᴜ ᴀʀᴇ ᴀʟʀᴇᴀᴅʏ ʟᴏɢɢᴇᴅ ɪɴ!"), false);
                            return 1;
                        }

                        if (!isRegistered(uuid)) {
                            player.sendMessage(Text.literal("§cʏᴏᴜ ᴀʀᴇ ɴᴏᴛ ʀᴇɢɪsᴛᴇʀᴇᴅ! ᴜsᴇ §a/register <password> <finalpassword>"), false);
                            playErrorSound(player);
                            return 1;
                        }

                        String inputPass = StringArgumentType.getString(context, "password");
                        String savedPass = passwords.get(uuid.toString());

                        if (inputPass.equals(savedPass)) {
                            player.sendMessage(Text.literal("§a§l✔ §aʏᴏᴜ ʜᴀᴠᴇ sᴜᴄᴄᴇssғᴜʟʟʏ ʟᴏɢɢᴇᴅ ɪɴ!"), false);
                            authenticate(player);
                        } else {
                            player.sendMessage(Text.literal("§c§l✖ §cᴡʀᴏɴɢ ᴘᴀssᴡᴏʀᴅ! ᴛʀʏ ᴀɢᴀɪɴ."), false);
                            playErrorSound(player);
                        }
                        return 1;
                    })
                )
            );
        });

        // When player joins
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID uuid = player.getUuid();
            loggedInPlayers.remove(uuid);
            frozenPositions.put(uuid, player.getPos());
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 100, 255, false, false, false));
        });

        // When player leaves
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUuid();
            loggedInPlayers.remove(uuid);
            frozenPositions.remove(uuid);
        });

        // Every tick: Freeze unlogged players + Black Vision + Screen Text
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID uuid = player.getUuid();
                if (!loggedInPlayers.contains(uuid)) {
                    // Freeze movement
                    Vec3d lockPos = frozenPositions.computeIfAbsent(uuid, k -> player.getPos());
                    if (player.squaredDistanceTo(lockPos) > 0.001) {
                        player.requestTeleport(lockPos.x, lockPos.y, lockPos.z);
                    }

                    // Update Black Vision & Screen Title every 15 ticks
                    if (tickCounter % 15 == 0) {
                        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 255, false, false, false));

                        player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 40, 0));
                        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§6§lᴅᴏɴᴜᴛ §f§lᴀᴜᴛʜ")));

                        if (isRegistered(uuid)) {
                            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§e/Login §f<password>")));
                        } else {
                            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§a/Register §f<password> <finalpassword>")));
                        }
                    }
                }
            }
        });

        // Block breaking, placing, interacting, chatting, and taking damage before login
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) ->
            loggedInPlayers.contains(player.getUuid()) ? ActionResult.PASS : ActionResult.FAIL);

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) ->
            loggedInPlayers.contains(player.getUuid()) ? ActionResult.PASS : ActionResult.FAIL);

        UseItemCallback.EVENT.register((player, world, hand) ->
            loggedInPlayers.contains(player.getUuid())
                ? TypedActionResult.pass(player.getStackInHand(hand))
                : TypedActionResult.fail(player.getStackInHand(hand)));

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
            loggedInPlayers.contains(player.getUuid()) ? ActionResult.PASS : ActionResult.FAIL);

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity player && !loggedInPlayers.contains(player.getUuid())) {
                return false;
            }
            return true;
        });

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (!loggedInPlayers.contains(sender.getUuid())) {
                sender.sendMessage(Text.literal("§cʏᴏᴜ ᴍᴜsᴛ ʟᴏɢɪɴ ʙᴇғᴏʀᴇ ᴄʜᴀᴛᴛɪɴɢ!"), false);
                return false;
            }
            return true;
        });
    }

    private boolean isRegistered(UUID uuid) {
        return passwords.containsKey(uuid.toString());
    }

    private void authenticate(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        loggedInPlayers.add(uuid);
        frozenPositions.remove(uuid);
        player.removeStatusEffect(StatusEffects.BLINDNESS);

        player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 10));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("§a§lʟᴏɢɢᴇᴅ ɪɴ!")));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("§7ᴡᴇʟᴄᴏᴍᴇ ᴛᴏ ᴛʜᴇ sᴇʀᴠᴇʀ")));

        player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    private void playErrorSound(ServerPlayerEntity player) {
        player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    private void loadPasswords() {
        try {
            passwordFile = FabricLoader.getInstance().getConfigDir().resolve("donutauth_passwords.json").toFile();
            if (passwordFile.exists()) {
                try (FileReader reader = new FileReader(passwordFile)) {
                    Type type = new TypeToken<HashMap<String, String>>() {}.getType();
                    Map<String, String> loaded = gson.fromJson(reader, type);
                    if (loaded != null) passwords = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void savePasswords() {
        try {
            if (!passwordFile.getParentFile().exists()) passwordFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(passwordFile)) {
                gson.toJson(passwords, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
