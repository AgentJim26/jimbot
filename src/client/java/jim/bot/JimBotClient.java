package jim.bot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * JimBot v1.7: fixed global-mention capture (bare Name » text fallback) + normalized sender key.
 *
 * Out: ~/backend/mcbot/dm-queue.jsonl lines:
 *   {"t":..,"sender":"..","text":".."}              incoming whisper
 *   {"t":..,"sender":"..","text":"..","global":true} global chat mention of jim/varenxd/caine
 * In:  ~/backend/mcbot/mod-cmd.jsonl lines: {"action":"interact"}
 * Out: ~/backend/mcbot/mod-result.jsonl lines: {"t":..,"ok":true/false,"detail":".."}
 */
public class JimBotClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("jimbot");
    private static final Path QUEUE = Paths.get(System.getProperty("user.home"), "backend", "mcbot", "dm-queue.jsonl");
    private static final Path CMD = Paths.get(System.getProperty("user.home"), "backend", "mcbot", "mod-cmd.jsonl");
    private static final Path RESULT = Paths.get(System.getProperty("user.home"), "backend", "mcbot", "mod-result.jsonl");

    private static final Pattern HEAD_FMT = Pattern.compile("\\[([^\\]]+) head\\] ([^»]+?) » (.*)");
    private static final Pattern VANILLA_FMT = Pattern.compile("<([^>]+)> (.*)");
    // 26.3 reality: message.getString() for head-component chat is bare "Name » text"
    // (no [CHAT] log prefix, no [Name head] wrapper) — the log-only decorations never reach us.
    private static final Pattern BARE_FMT = Pattern.compile("^(.+?) » (.*)");
    private static final Pattern[] WHISPERS = {
            Pattern.compile("\\((\\w+) » me\\) (.*)"),
            Pattern.compile("(\\w+) whispers(?::| to you:) (.*)"),
            Pattern.compile("\\[(\\w+) -> [^\\]]+\\] (.*)"),
            Pattern.compile("(\\w+) -> (?:you|me)\\b:?(.*)", Pattern.CASE_INSENSITIVE),
    };
    private static final Pattern TRIGGER = Pattern.compile("\\b(jim|varenxd|caine)\\b", Pattern.CASE_INSENSITIVE);

    private static long cmdOffset = 0;
    private static boolean cmdInitialized = false;
    private static String lookTarget = null;
    private static final java.nio.file.Path DIRS = java.nio.file.Paths.get(
            System.getProperty("user.home"), "backend", "mcbot", "directions.json");
    private static long depositDeadline = 0;
    private static String depositTarget = null;
    private static String depositChest = null;
    private static int depositWant = 0;
    private static String depositChestDesc = "";
    private static int depositMoved = 0;
    private static boolean fishActive = false;
    private static boolean fishBanking = false;
    private static boolean fishNeedReel = false;
    private static String fishChest = "copper";
    private static java.util.Map<String, Integer> fishBaseline = new java.util.LinkedHashMap<>();
    private static int fishBanked = 0;
    private static float fishYaw = 0.0f;
    private static float fishPitch = 0.0f;
    private static java.util.List<String> depositTargets = null;
    private static boolean depositIsFish = false;
    private static long withdrawDeadline = 0;
    private static String withdrawTarget = null;
    private static String withdrawChest = null;
    private static int withdrawWant = 0;
    private static String withdrawChestDesc = "";
    private static int withdrawMoved = 0;
    private static long lookLoggedAt = 0;
    private static long pendingBoardCheckAt = 0;
    private static String pendingBoardDetail = "";
    private static long pendingReelCheckAt = 0;
    private static long pendingCastCheckAt = 0;
    private static net.minecraft.network.syncher.EntityDataAccessor<Boolean> fishBitingKey = null;
    private static boolean fishKeyFailed = false;
    private static double fishPrevHookY = Double.NaN;
    private static boolean fishRecastScheduled = false;
    private static boolean fishJustAutoReeled = false;
    private static int fishRecastTimer = 0;
    private static int fishActionCooldown = 0;
    private static long fishCastAt = 0;
    private static boolean fishHookWasWet = false;
    private static int fishRetries = 0;
    private static int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            debugIncoming(message, overlay);
            if (overlay) {
                return;
            }
            handleGameMessage(message.getString());
        });
        // Signed player chat (global) arrives here, NOT on GAME (system-only).
        // Missing this registration was why global mentions never reached the queue.
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, boundType, timestamp) -> {
            String name = sender == null ? null : sender.name();
            debugChatMessage(name, message);
            if (name != null) {
                handlePlayerChat(name, message.getString());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(JimBotClient::onTick);
        LOG.info("[jimbot] DM bridge + interact armed, queue={}", QUEUE);
    }

    private static void handleGameMessage(String raw) {
        int idx = raw.indexOf("[CHAT]");
        String text = idx >= 0 ? raw.substring(idx + 6).trim() : raw.trim();
        // 1) whispers first
        for (Pattern p : WHISPERS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                emit("{\"t\":" + Instant.now().toEpochMilli()
                        + ",\"sender\":" + q(m.group(1).trim())
                        + ",\"key\":" + q(normKey(m.group(1).trim()))
                        + ",\"text\":" + q(m.group(2).trim()) + "}");
                return;
            }
        }
        // 2) global chat mentions of the bot
        Matcher mh = HEAD_FMT.matcher(text);
        String sender = null;
        String msg = null;
        if (mh.find()) {
            sender = mh.group(2).trim();
            msg = mh.group(3);
        } else {
            Matcher mv = VANILLA_FMT.matcher(text);
            if (mv.find()) {
                sender = mv.group(1).trim();
                msg = mv.group(2);
            } else {
                Matcher mb = BARE_FMT.matcher(text);
                if (mb.find()) {
                    sender = mb.group(1).trim();
                    msg = mb.group(2);
                }
            }
        }
        if (sender != null && msg != null && TRIGGER.matcher(msg).find()) {
            emit("{\"t\":" + Instant.now().toEpochMilli()
                    + ",\"sender\":" + q(sender)
                    + ",\"key\":" + q(normKey(sender))
                    + ",\"text\":" + q(msg.trim())
                    + ",\"global\":true}");
        }
    }

    private static void onTick(Minecraft mc) {
        trackFishFast(mc);
        tickCounter++;
        if (tickCounter % 20 != 0) {
            return;
        }
        pollCommands(mc);
        trackLook(mc);
        trackDeposit(mc);
        trackWithdraw(mc);
        trackFish(mc);
        trackReel(mc);
        trackCast(mc);
        if (pendingBoardCheckAt > 0 && System.currentTimeMillis() >= pendingBoardCheckAt) {
            pendingBoardCheckAt = 0;
            boolean boarded = mc.player != null && mc.player.getVehicle() != null;
            result(boarded, (boarded ? "boarded: " : "not boarded (still on foot): ") + pendingBoardDetail);
            pendingBoardDetail = "";
        }
    }

    private static void pollCommands(Minecraft mc) {
        if (!cmdInitialized) {
            // Commands are append-only across restarts. Skip everything that
            // existed before this client tick, or old fishstart/fishstop/reel
            // commands replay at boot and tear down the live state.
            cmdInitialized = true;
            try {
                cmdOffset = Files.exists(CMD) ? Files.size(CMD) : 0;
            } catch (IOException e) {
                cmdOffset = 0;
            }
            return;
        }
        List<String> lines;
        try {
            if (!Files.exists(CMD)) {
                return;
            }
            long size = Files.size(CMD);
            if (size < cmdOffset) {
                cmdOffset = 0;
            }
            if (size == cmdOffset) {
                return;
            }
            byte[] all = Files.readAllBytes(CMD);
            String chunk = new String(all, (int) cmdOffset, (int) (size - cmdOffset), StandardCharsets.UTF_8);
            cmdOffset = size;
            lines = List.of(chunk.split("\n"));
        } catch (IOException e) {
            return;
        }
        for (String line : lines) {
            String t = line.trim();
            if (t.contains("\"board\"")) {
                doBoard(mc);
            } else if (t.contains("\"unlook\"")) {
                lookTarget = null;
                result(true, "look cleared");
            } else if (t.contains("\"look\"")) {
                String name = jsonVal(t, "target");
                if (name == null || name.isEmpty()) {
                    result(false, "look needs a target name");
                } else {
                    lookTarget = name;
                    lookLoggedAt = 0;
                    faceTargetNow(mc, name);
                }
            } else if (t.contains("\"unequip\"")) {
                String target = jsonVal(t, "target");
                if (target == null || target.isEmpty()) {
                    target = "all";
                }
                doUnequip(mc, target);
            } else if (t.contains("\"dropAll\"")) {
                doDropAll(mc);
            } else if (t.contains("\"drop\"")) {
                String target = jsonVal(t, "target");
                int count = jsonInt(t, "count", 1);
                if (target == null || target.isEmpty()) {
                    result(false, "drop needs a target name");
                } else {
                    doDropNamed(mc, target, count);
                }
            } else if (t.contains("\"wear\"")) {
                String target = jsonVal(t, "target");
                if (target == null || target.isEmpty()) {
                    result(false, "wear needs a target name");
                } else {
                    doWear(mc, target);
                }
            } else if (t.contains("\"hold\"")) {
                String target = jsonVal(t, "target");
                if (target == null || target.isEmpty()) {
                    result(false, "hold needs a target name or slot");
                } else {
                    doHold(mc, target);
                }
            } else if (t.contains("\"rememberDir\"")) {
                String name = jsonVal(t, "name");
                if (name == null || name.isEmpty()) {
                    result(false, "rememberDir needs a name");
                } else {
                    doRememberDir(mc, name);
                }
            } else if (t.contains("\"faceDir\"")) {
                String name = jsonVal(t, "name");
                if (name == null || name.isEmpty()) {
                    result(false, "faceDir needs a name");
                } else {
                    doFaceDir(mc, name);
                }
            } else if (t.contains("\"dirs\"")) {
                doListDirs(mc);
            } else if (t.contains("\"withdraw\"")) {
                String target = jsonVal(t, "target");
                String chest = jsonVal(t, "chest");
                int count = jsonInt(t, "count", 99);
                doWithdraw(mc, target, chest, count);
            } else if (t.contains("\"deposit\"")) {
                String target = jsonVal(t, "target");
                String chest = jsonVal(t, "chest");
                int count = jsonInt(t, "count", 99);
                doDeposit(mc, target, chest, count);
            } else if (t.contains("\"inv\"")) {
                doInv(mc);
            } else if (t.contains("\"aim\"")) {
                double dyaw = jsonDouble(t, "dyaw", jsonDouble(t, "yaw", 0.0));
                double dpitch = jsonDouble(t, "dpitch", jsonDouble(t, "pitch", 0.0));
                doAim(mc, dyaw, dpitch);
            } else if (t.contains("\"cast\"")) {
                doCast(mc);
            } else if (t.contains("\"reel\"")) {
                doReel(mc);
            } else if (t.contains("\"fishstart\"")) {
                doFishStart(mc, jsonVal(t, "chest"));
            } else if (t.contains("\"fishstop\"")) {
                doFishStop(mc);
            } else if (t.contains("\"fishstatus\"")) {
                doFishStatus(mc);
            } else if (t.contains("\"interact\"")) {
                doInteract(mc);
            }
        }
    }

    private static void doInteract(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;

        // 1) Prefer whatever the crosshair is already on.
        HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            interactBlock(mc, p, (BlockHitResult) hit);
            return;
        }
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            interactEntity(mc, p, ((EntityHitResult) hit).getEntity());
            return;
        }

        // 2) Otherwise find the nearest interactable block within reach.
        BlockPos origin = p.blockPosition();
        BlockPos bestPos = null;
        double bestDist = 5.0 * 5.0;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    BlockState state = mc.level.getBlockState(pos);
                    if (state.isAir() || !isInteractable(state)) {
                        continue;
                    }
                    double d = p.position().distanceToSqr(Vec3.atCenterOf(pos));
                    if (d < bestDist) {
                        bestDist = d;
                        bestPos = pos;
                    }
                }
            }
        }
        if (bestPos != null) {
            Vec3 center = Vec3.atCenterOf(bestPos);
            face(p, center);
            Direction side = sideFacing(p, center);
            interactBlock(mc, p, new BlockHitResult(center, side, bestPos, false));
            return;
        }

        // 3) Fall back to the nearest non-player entity.
        Entity best = null;
        double bestEntityDist = 5.0;
        for (Entity e : mc.level.getEntities(p, new AABB(p.position(), p.position()).inflate(5.0),
                entity -> !(entity instanceof Player))) {
            double d = e.position().distanceTo(p.position());
            if (d < bestEntityDist) {
                bestEntityDist = d;
                best = e;
            }
        }
        if (best != null) {
            face(p, best.position().add(0.0, best.getBbHeight() * 0.5, 0.0));
            interactEntity(mc, p, best);
            return;
        }

        result(false, "nothing interactable within 5m");
    }

    private static void doBoard(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        Entity best = null;
        double bestDist = 6.0;
        for (Entity e : mc.level.getEntities(p, new AABB(p.position(), p.position()).inflate(6.0),
                entity -> entity instanceof Boat)) {
            double d = e.position().distanceTo(p.position());
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        if (best == null) {
            result(false, "no boat within 6m");
            return;
        }
        face(p, best.position().add(0.0, best.getBbHeight() * 0.5, 0.0));
        interactEntity(mc, p, best);
    }

    private static void interactBlock(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        try {
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
            BlockState state = mc.level.getBlockState(hit.getBlockPos());
            String detail = state.getBlock().getName().getString()
                    + " at " + hit.getBlockPos().toShortString()
                    + " (" + String.format("%.1fm", Math.sqrt(p.position().distanceToSqr(Vec3.atCenterOf(hit.getBlockPos())))) + ")";
            result(true, "right-clicked block " + detail);
        } catch (Exception e) {
            result(false, "block interact threw: " + e.getClass().getSimpleName());
        }
    }

    private static void interactEntity(Minecraft mc, LocalPlayer p, Entity entity) {
        try {
            mc.gameMode.interact(p, entity, new EntityHitResult(entity), InteractionHand.MAIN_HAND);
            if (entity instanceof Boat) {
                pendingBoardDetail = entity.getName().getString() + " at "
                        + String.format("%.1fm", entity.position().distanceTo(p.position()));
                pendingBoardCheckAt = System.currentTimeMillis() + 3000;
                result(true, "right-clicked boat " + pendingBoardDetail + " (boarding check pending)");
            } else {
                result(true, "right-clicked entity " + entity.getName().getString()
                        + " (" + entity.getType().toString() + ") at "
                        + String.format("%.1fm", entity.position().distanceTo(p.position())));
            }
        } catch (Exception e) {
            result(false, "entity interact threw: " + e.getClass().getSimpleName());
        }
    }

    private static boolean isInteractable(BlockState state) {
        String name = state.getBlock().getClass().getSimpleName().toLowerCase();
        String[] hints = {
                "button", "lever", "door", "trapdoor", "fencegate", "chest", "barrel",
                "furnace", "shulkerbox", "hopper", "dispenser", "dropper", "beacon",
                "anvil", "bed", "cauldron", "composter", "grindstone", "loom",
                "smithingtable", "stonecutter", "cartographytable", "enchantingtable",
                "brewingstand", "lectern", "campfire", "bell", "noteblock", "repeater",
                "comparator", "daylightdetector", "craftingtable", "crafter",
                "decoratedpot", "chiseledbookshelf", "jukebox", "respawnanchor",
                "beehive", "beenest"
        };
        for (String hint : hints) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static Direction sideFacing(LocalPlayer p, Vec3 target) {
        double dx = p.getX() - target.x;
        double dy = p.getEyeY() - target.y;
        double dz = p.getZ() - target.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (az >= ax && az >= ay) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return dy > 0 ? Direction.UP : Direction.DOWN;
    }

    private static void face(LocalPlayer p, Vec3 target) {
        double dx = target.x - p.getX();
        double dy = target.y - p.getEyeY();
        double dz = target.z - p.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
    }

    private static String jsonVal(String line, String key) {
        String k = "\"" + key + "\"";
        int i = line.indexOf(k);
        if (i < 0) {
            return null;
        }
        int c = line.indexOf(':', i + k.length());
        if (c < 0) {
            return null;
        }
        int q1 = line.indexOf('"', c + 1);
        if (q1 < 0) {
            return null;
        }
        int q2 = line.indexOf('"', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return line.substring(q1 + 1, q2);
    }

    private static Entity findPlayer(Minecraft mc, String name) {
        if (mc.level == null || mc.player == null) {
            return null;
        }
        Entity best = null;
        double bestDist = 64.0 * 64.0;
        for (Entity e : mc.level.getEntities(mc.player,
                new AABB(mc.player.position(), mc.player.position()).inflate(64.0),
                entity -> entity instanceof Player && !(entity instanceof LocalPlayer))) {
            String nm;
            try {
                nm = e.getName().getString();
            } catch (Exception ex) {
                continue;
            }
            if (!(nm.equalsIgnoreCase(name) || nm.toLowerCase().contains(name.toLowerCase()))) {
                continue;
            }
            double d = e.position().distanceToSqr(mc.player.position());
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private static void faceTargetNow(Minecraft mc, String name) {
        if (mc.player == null) {
            result(false, "look: no player yet");
            return;
        }
        Entity e = findPlayer(mc, name);
        if (e == null) {
            result(false, name + " not around");
            return;
        }
        Vec3 eyes = e.position().add(0.0, e.getBbHeight() * 0.9, 0.0);
        face(mc.player, eyes);
        lookLoggedAt = System.currentTimeMillis();
        result(true, "looking at " + e.getName().getString());
    }

    private static void trackLook(Minecraft mc) {
        if (lookTarget == null || mc.player == null || mc.level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lookLoggedAt < 5000) {
            return;
        }
        Entity e = findPlayer(mc, lookTarget);
        if (e == null) {
            return;
        }
        Vec3 eyes = e.position().add(0.0, e.getBbHeight() * 0.9, 0.0);
        face(mc.player, eyes);
        lookLoggedAt = now;
    }

    private static void doUnequip(Minecraft mc, String target) {
        if (mc.player == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        boolean all = target.equalsIgnoreCase("all") || target.equalsIgnoreCase("armor") || target.equalsIgnoreCase("armour");
        int moved = 0;
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 5; i <= 8; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (!all && !nameMatches(s, target)) {
                    continue;
                }
                String lbl = stackLabel(s);
                try {
                    mc.gameMode.handleContainerInput(p.containerMenu.containerId, i, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);
                    moved++;
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(lbl);
                } catch (Exception ex) {
                    result(false, "unequip threw: " + ex.getClass().getSimpleName() + " after " + moved);
                    return;
                }
            }
            if (moved == 0) {
                result(false, all ? "nothing worn" : "not wearing any '" + target + "'");
            } else {
                result(true, "unequipped:" + moved + " (" + sb.toString() + ")");
            }
        } catch (Exception e) {
            result(false, "unequip threw: " + e.getClass().getSimpleName());
        }
    }

    private static void doDropAll(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        int dropped = 0;
        try {
            int size = p.containerMenu.slots.size();
            for (int pass = 0; pass < 3; pass++) {
                boolean any = false;
                for (int i = 5; i < size; i++) {
                    if (!p.containerMenu.getSlot(i).getItem().isEmpty()) {
                        mc.gameMode.handleContainerInput(
                                p.containerMenu.containerId, i, 1,
                                net.minecraft.world.inventory.ContainerInput.THROW, p);
                        dropped++;
                        any = true;
                    }
                }
                if (!any) {
                    break;
                }
            }
            result(true, "dropped:" + dropped + " stacks (armor included)");
        } catch (Exception e) {
            result(false, "dropAll threw: " + e.getClass().getSimpleName() + " after " + dropped);
        }
    }

    private static int jsonInt(String line, String key, int def) {
        String v = jsonVal(line, key);
        if (v == null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(line);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ex) {
                    return def;
                }
            }
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static double jsonDouble(String line, String key, double def) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[\\d.]+)").matcher(line);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1));
            } catch (NumberFormatException ex) {
                return def;
            }
        }
        return def;
    }

    private static void doAim(Minecraft mc, double dyaw, double dpitch) {
        if (mc.player == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        lookTarget = null;
        float yaw = p.getYRot() + (float) dyaw;
        float pitch = p.getXRot() - (float) dpitch;
        if (pitch > 90.0f) {
            pitch = 90.0f;
        }
        if (pitch < -90.0f) {
            pitch = -90.0f;
        }
        yaw = (float) ((yaw + 180.0) % 360.0);
        if (yaw < 0) {
            yaw += 360.0f;
        }
        yaw -= 180.0f;
        p.setYRot(yaw);
        p.setXRot(pitch);
        result(true, "aimed yaw " + Math.round(yaw) + " pitch " + Math.round(pitch) + " (tracking off)");
    }

    private static String stackLabel(net.minecraft.world.item.ItemStack s) {
        try {
            String name = s.getHoverName().getString();
            return name + " x" + s.getCount();
        } catch (Exception ex) {
            return s.toString();
        }
    }

    private static boolean nameMatches(net.minecraft.world.item.ItemStack s, String target) {
        String tl = target.toLowerCase();
        try {
            String hover = s.getHoverName().getString().toLowerCase();
            if (hover.contains(tl)) {
                return true;
            }
        } catch (Exception ex) {
        }
        try {
            String desc = s.getItem().getDescriptionId().toLowerCase();
            String simple = desc.substring(desc.lastIndexOf('.') + 1);
            if (desc.contains(tl) || simple.contains(tl)) {
                return true;
            }
        } catch (Exception ex) {
        }
        try {
            if (s.getItem().toString().toLowerCase().contains(tl)) {
                return true;
            }
        } catch (Exception ex) {
        }
        return false;
    }

    private static void doInv(Minecraft mc) {
        if (mc.player == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        try {
            StringBuilder sb = new StringBuilder();
            int total = 0;
            int size = p.containerMenu.slots.size();
            for (int i = 0; i < size; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (total > 0) {
                    sb.append(" | ");
                }
                sb.append("slot").append(i).append(":").append(stackLabel(s));
                total++;
                if (sb.length() > 1500) {
                    sb.append(" | ...");
                    break;
                }
            }
            try {
                sb.append(" || selected=").append(p.getInventory().getSelectedSlot());
            } catch (Exception ex) {
            }
            if (total == 0) {
                result(true, "inv empty");
            } else {
                result(true, "inv(" + total + "): " + sb.toString());
            }
        } catch (Exception e) {
            result(false, "inv threw: " + e.getClass().getSimpleName());
        }
    }

    private static void doDropNamed(Minecraft mc, String target, int count) {
        if (mc.player == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        try {
            int want = count <= 0 ? 99 : count;
            int dropped = 0;
            StringBuilder sb = new StringBuilder();
            int size = p.containerMenu.slots.size();
            for (int pass = 0; pass < 3 && dropped < want; pass++) {
                boolean any = false;
                for (int i = 5; i < size && dropped < want; i++) {
                    net.minecraft.world.item.ItemStack s;
                    try {
                        s = p.containerMenu.getSlot(i).getItem();
                    } catch (Exception ex) {
                        continue;
                    }
                    if (s == null || s.isEmpty()) {
                        continue;
                    }
                    if (!nameMatches(s, target)) {
                        continue;
                    }
                    String lbl = stackLabel(s);
                    mc.gameMode.handleContainerInput(p.containerMenu.containerId, i, 1, net.minecraft.world.inventory.ContainerInput.THROW, p);
                    dropped++;
                    any = true;
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(lbl);
                }
                if (!any) {
                    break;
                }
            }
            if (dropped == 0) {
                result(false, "drop: no match for " + target);
            } else {
                result(true, "dropped:" + dropped + " stack(s): " + sb.toString());
            }
        } catch (Exception e) {
            result(false, "drop threw: " + e.getClass().getSimpleName());
        }
    }

    private static boolean wornMatches(LocalPlayer p, String target) {
        try {
            for (int i = 5; i <= 8; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s != null && !s.isEmpty() && nameMatches(s, target)) {
                    return true;
                }
            }
        } catch (Exception ex) {
        }
        return false;
    }

    private static void doWear(Minecraft mc, String target) {
        if (mc.player == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        try {
            if (wornMatches(p, target)) {
                result(true, "already wearing " + target);
                return;
            }
            int size = p.containerMenu.slots.size();
            for (int i = 9; i < size; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (!nameMatches(s, target)) {
                    continue;
                }
                String lbl = stackLabel(s);
                mc.gameMode.handleContainerInput(p.containerMenu.containerId, i, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);
                if (wornMatches(p, target)) {
                    result(true, "wearing: " + lbl);
                } else {
                    result(false, "could not equip " + lbl + " (it stayed in pockets)");
                }
                return;
            }
            result(false, "wear: no match for " + target);
        } catch (Exception e) {
            result(false, "wear threw: " + e.getClass().getSimpleName());
        }
    }

    private static void doHold(Minecraft mc, String target) {
        if (mc.player == null) {
            result(false, "no player/world yet");
            return;
        }
        LocalPlayer p = mc.player;
        try {
            String t = target.trim();
            try {
                int slot = Integer.parseInt(t);
                if (slot >= 0 && slot <= 8) {
                    p.getInventory().setSelectedSlot(slot);
                    syncSelected(mc, slot);
                    result(true, "holding hotbar slot " + slot);
                    return;
                }
            } catch (NumberFormatException ex) {
            }
            for (int i = 0; i < 9; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.getInventory().getItem(i);
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (nameMatches(s, t)) {
                    p.getInventory().setSelectedSlot(i);
                    syncSelected(mc, i);
                    result(true, "holding " + stackLabel(s) + " (hotbar " + i + ")");
                    return;
                }
            }
            result(false, "hold: " + target + " not in hotbar (slots 0-8)");
        } catch (Exception e) {
            result(false, "hold threw: " + e.getClass().getSimpleName());
        }
    }

    private static void syncSelected(Minecraft mc, int slot) {
        try {
            if (mc.getConnection() != null) {
                mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(slot));
            }
        } catch (Exception ex) {
        }
    }

    private static java.util.Map<String, double[]> loadDirs() {
        java.util.Map<String, double[]> out = new java.util.LinkedHashMap<>();
        try {
            if (!java.nio.file.Files.exists(DIRS)) {
                return out;
            }
            String txt = new String(java.nio.file.Files.readAllBytes(DIRS),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "\"([^\"]+)\"\\s*:\\s*\\{\\s*\"yaw\"\\s*:\\s*(-?[\\d.]+)\\s*,\\s*\"pitch\"\\s*:\\s*(-?[\\d.]+)\\s*\\}")
                    .matcher(txt);
            while (m.find()) {
                try {
                    out.put(m.group(1), new double[]{Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))});
                } catch (NumberFormatException ex) {
                }
            }
        } catch (java.io.IOException ex) {
        }
        return out;
    }

    private static void saveDirs(java.util.Map<String, double[]> dirs) {
        try {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, double[]> e : dirs.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(e.getKey().replace("\"", "")).append("\":{\"yaw\":")
                        .append(e.getValue()[0]).append(",\"pitch\":").append(e.getValue()[1]).append("}");
            }
            sb.append("}");
            java.nio.file.Files.write(DIRS, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException ex) {
            result(false, "dirs save failed");
        }
    }

    private static void doRememberDir(Minecraft mc, String name) {
        if (mc.player == null) {
            result(false, "no player/world yet");
            return;
        }
        java.util.Map<String, double[]> dirs = loadDirs();
        dirs.put(name.toLowerCase(), new double[]{mc.player.getYRot(), mc.player.getXRot()});
        saveDirs(dirs);
        result(true, "remembered " + name + " yaw " + Math.round(mc.player.getYRot())
                + " pitch " + Math.round(mc.player.getXRot()));
    }

    private static void doFaceDir(Minecraft mc, String name) {
        if (mc.player == null) {
            result(false, "no player/world yet");
            return;
        }
        java.util.Map<String, double[]> dirs = loadDirs();
        double[] a = dirs.get(name.toLowerCase());
        if (a == null) {
            result(false, "no saved direction '" + name + "'");
            return;
        }
        lookTarget = null;
        mc.player.setYRot((float) a[0]);
        mc.player.setXRot((float) a[1]);
        result(true, "facing " + name + " yaw " + Math.round(a[0]) + " pitch " + Math.round(a[1]) + " (tracking off)");
    }

    private static void doListDirs(Minecraft mc) {
        java.util.Map<String, double[]> dirs = loadDirs();
        if (dirs.isEmpty()) {
            result(true, "no saved directions yet");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, double[]> e : dirs.entrySet()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(e.getKey()).append(" yaw ").append(Math.round(e.getValue()[0]))
                    .append(" pitch ").append(Math.round(e.getValue()[1]));
        }
        result(true, "dirs: " + sb.toString());
    }

    private static boolean isContainerBlock(net.minecraft.world.level.block.state.BlockState state) {
        String n = state.getBlock().getClass().getSimpleName().toLowerCase();
        return n.contains("chest") || n.contains("barrel") || n.contains("shulkerbox") || n.contains("hopper");
    }

    private static void doDeposit(Minecraft mc, String target, String chest, int count) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        if (depositDeadline > 0) {
            result(false, "deposit already in progress");
            return;
        }
        depositTargets = null;
        depositIsFish = false;
        LocalPlayer p = mc.player;
        net.minecraft.core.BlockPos origin = p.blockPosition();
        net.minecraft.core.BlockPos bestPos = null;
        double bestDist = 25.0;
        String chestWant = chest == null ? "" : chest.toLowerCase(java.util.Locale.ROOT);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    net.minecraft.core.BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(pos);
                    if (st.isAir() || !isContainerBlock(st)) {
                        continue;
                    }
                    if (!chestWant.isEmpty()) {
                        String bn;
                        try {
                            bn = st.getBlock().getName().getString().toLowerCase();
                        } catch (Exception ex) {
                            bn = "";
                        }
                        if (!bn.contains(chestWant)) {
                            continue;
                        }
                    }
                    double d = p.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
                    if (d < bestDist) {
                        bestDist = d;
                        bestPos = pos;
                    }
                }
            }
        }
        if (bestPos == null) {
            result(false, chestWant.isEmpty() ? "no chest/barrel/shulker within 5m"
                    : "no '" + chest + "' container within 5m");
            return;
        }
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(bestPos);
        face(p, center);
        net.minecraft.core.Direction side = sideFacing(p, center);
        try {
            mc.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(center, side, bestPos, false));
        } catch (Exception e) {
            result(false, "chest open threw: " + e.getClass().getSimpleName());
            return;
        }
        try {
            depositChestDesc = mc.level.getBlockState(bestPos).getBlock().getName().getString()
                    + " at " + bestPos.toShortString();
        } catch (Exception ex) {
            depositChestDesc = bestPos.toShortString();
        }
        depositTarget = (target == null || target.equalsIgnoreCase("all") || target.isEmpty()) ? null : target;
        depositChest = chest;
        depositWant = count <= 0 ? 99 : count;
        depositMoved = 0;
        depositDeadline = System.currentTimeMillis() + 5000;
    }

    private static void trackDeposit(Minecraft mc) {
        if (depositDeadline <= 0) {
            return;
        }
        if (mc.player == null || mc.gameMode == null) {
            depositDeadline = 0;
            depositIsFish = false;
            fishBanking = false;
            fishActive = false;
            result(false, "deposit aborted: no player");
            return;
        }
        LocalPlayer p = mc.player;
        boolean open = false;
        try {
            open = !(p.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu);
        } catch (Exception ex) {
            open = false;
        }
        if (!open) {
            if (System.currentTimeMillis() >= depositDeadline) {
                depositDeadline = 0;
                boolean wasFish = depositIsFish;
                depositIsFish = false;
                if (wasFish) {
                    fishBanking = false;
                    fishActive = false;
                }
                result(false, "chest did not open (" + depositChestDesc + ")");
            }
            return;
        }
        depositDeadline = 0;
        int moved = 0;
        boolean restoreView = depositIsFish;
        int leftover = 0;
        try {
            int size = p.containerMenu.slots.size();
            int playerStart = Math.max(0, size - 36);
            // During an automatic fish bank, never quick-move the rod in the
            // active hotbar slot. A caught junk rod has the same display name
            // and would otherwise take the good rod down with it.
            int activeHotbarSlot = -1;
            if (depositIsFish) {
                try {
                    activeHotbarSlot = size - 9 + p.getInventory().getSelectedSlot();
                } catch (Exception ex) {
                    activeHotbarSlot = -1;
                }
            }
            for (int i = playerStart; i < size && moved < depositWant; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (i == activeHotbarSlot) {
                    continue;
                }
                if (!depositWants(s)) {
                    continue;
                }
                String lbl = stackLabel(s);
                try {
                    mc.gameMode.handleContainerInput(p.containerMenu.containerId, i, 0,
                            net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);
                    moved++;
                } catch (Exception ex) {
                    result(false, "deposit move threw: " + ex.getClass().getSimpleName() + " after " + moved);
                    depositIsFish = false;
                    fishBanking = false;
                    fishActive = false;
                    try {
                        p.closeContainer();
                    } catch (Exception ex2) {
                    }
                    return;
                }
            }
            // Count what the chest refused while ITS menu is still open. The
            // post-close player menu has a different layout and previously
            // produced phantom leftovers (the active rod was counted).
            leftover = restoreView ? countDepositLeftovers(p, true)
                    : countDepositLeftovers(p, false);
            // One in-menu retry for a transient single-stack refusal before
            // declaring the chest full.
            if (leftover > 0) {
                for (int i = playerStart; i < size && moved < depositWant; i++) {
                    if (i == activeHotbarSlot) {
                        continue;
                    }
                    net.minecraft.world.item.ItemStack s;
                    try {
                        s = p.containerMenu.getSlot(i).getItem();
                    } catch (Exception ex) {
                        continue;
                    }
                    if (s == null || s.isEmpty() || !depositWants(s)) {
                        continue;
                    }
                    try {
                        mc.gameMode.handleContainerInput(p.containerMenu.containerId, i, 0,
                                net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);
                        moved++;
                    } catch (Exception ex) {
                        break;
                    }
                }
                leftover = restoreView ? countDepositLeftovers(p, true)
                        : countDepositLeftovers(p, false);
            }
        } finally {
            try {
                p.closeContainer();
            } catch (Exception ex) {
            }
        }
        depositMoved = moved;
        if (depositIsFish) {
            depositIsFish = false;
            try {
                fishBaseline = buildTotals(mc);
            } catch (Exception ex) {
            }
            fishBanked += moved;
        }
        depositTargets = null;
        if (moved == 0) {
            result(false, leftover > 0 ? "chest full (" + leftover + " stack(s) left in pockets)"
                    : depositTarget == null ? "nothing to move (pockets empty)"
                    : "no match for '" + depositTarget + "' in pockets");
        } else if (leftover > 0) {
            result(true, "deposited:" + moved + " stack(s)"
                    + (depositTarget == null ? "" : " of " + depositTarget) + " into " + depositChestDesc
                    + " - chest full, " + leftover + " stack(s) left in pockets");
        } else {
            result(true, "deposited:" + moved + " stack(s)"
                    + (depositTarget == null ? "" : " of " + depositTarget) + " into " + depositChestDesc);
        }
        depositTarget = null;
        depositChestDesc = "";
        if (restoreView) {
            fishBanking = false;
            if (leftover > 0 && fishActive) {
                // The chest could not take everything. Stop instead of fishing
                // on with full pockets and dropping catches on the ground.
                fishActive = false;
                fishNeedReel = false;
                fishJustAutoReeled = false;
                fishRecastScheduled = false;
                fishRecastTimer = 0;
                lookTarget = null;
                result(false, "chest full - fishing stopped with " + leftover + " stack(s) in pockets");
                queueFishEvent("chestfull", "chest full, " + leftover + " stack(s) left in pockets");
                // Reel the line back in, too. The earlier check used to stop
                // and leave the hook floating there.
                try {
                    if (mc.gameMode != null && holdingRod(mc) && findMyHook(mc) != null) {
                        mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                        pendingReelCheckAt = System.currentTimeMillis() + 3000;
                    }
                } catch (Exception ex) {
                }
            } else if (moved > 0 && fishActive) {
                try {
                    if (mc.player != null) {
                        mc.player.setYRot(fishYaw);
                        mc.player.setXRot(fishPitch);
                    }
                } catch (Exception ex) {
                }
                // Resume the catch/store/repeat loop at the saved water view.
                // If a stale hook survived the bank trip, reel it first.
                fishNeedReel = findMyHook(mc) != null;
                fishRecastScheduled = true;
                fishRecastTimer = 0;
                fishJustAutoReeled = fishNeedReel;
                fishActionCooldown = 0;
                fishCastAt = 0;
            } else {
                fishActive = false;
                fishNeedReel = false;
            }
        }
    }

    private static void doWithdraw(Minecraft mc, String target, String chest, int count) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        if (depositDeadline > 0 || withdrawDeadline > 0) {
            result(false, "container job already in progress");
            return;
        }
        LocalPlayer p = mc.player;
        net.minecraft.core.BlockPos origin = p.blockPosition();
        net.minecraft.core.BlockPos bestPos = null;
        double bestDist = 25.0;
        String chestWant = chest == null ? "" : chest.toLowerCase(java.util.Locale.ROOT);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    net.minecraft.core.BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(pos);
                    if (st.isAir() || !isContainerBlock(st)) {
                        continue;
                    }
                    if (!chestWant.isEmpty()) {
                        String bn;
                        try {
                            bn = st.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
                        } catch (Exception ex) {
                            bn = "";
                        }
                        if (!bn.contains(chestWant)) {
                            continue;
                        }
                    }
                    double d = p.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
                    if (d < bestDist) {
                        bestDist = d;
                        bestPos = pos;
                    }
                }
            }
        }
        if (bestPos == null) {
            result(false, chestWant.isEmpty() ? "no chest/barrel/shulker within 5m" : "no '" + chest + "' container within 5m");
            return;
        }
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(bestPos);
        face(p, center);
        net.minecraft.core.Direction side = sideFacing(p, center);
        try {
            mc.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(center, side, bestPos, false));
        } catch (Exception e) {
            result(false, "chest open threw: " + e.getClass().getSimpleName());
            return;
        }
        try {
            withdrawChestDesc = mc.level.getBlockState(bestPos).getBlock().getName().getString() + " at " + bestPos.toShortString();
        } catch (Exception ex) {
            withdrawChestDesc = bestPos.toShortString();
        }
        withdrawTarget = (target == null || target.equalsIgnoreCase("all") || target.isEmpty()) ? null : target;
        withdrawWant = count <= 0 ? 99 : count;
        withdrawMoved = 0;
        withdrawDeadline = System.currentTimeMillis() + 5000;
    }

    private static void trackWithdraw(Minecraft mc) {
        if (withdrawDeadline <= 0) {
            return;
        }
        if (mc.player == null || mc.gameMode == null) {
            withdrawDeadline = 0;
            result(false, "withdraw aborted: no player");
            return;
        }
        LocalPlayer p = mc.player;
        boolean open = false;
        try {
            open = !(p.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu);
        } catch (Exception ex) {
            open = false;
        }
        if (!open) {
            if (System.currentTimeMillis() >= withdrawDeadline) {
                withdrawDeadline = 0;
                result(false, "chest did not open (" + withdrawChestDesc + ")");
            }
            return;
        }
        withdrawDeadline = 0;
        int moved = 0;
        StringBuilder sb = new StringBuilder();
        try {
            int size = p.containerMenu.slots.size();
            int chestSlots = Math.max(0, size - 36);
            for (int i = 0; i < chestSlots && moved < withdrawWant; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (withdrawTarget != null && !nameMatches(s, withdrawTarget)) {
                    continue;
                }
                String lbl = stackLabel(s);
                try {
                    mc.gameMode.handleContainerInput(p.containerMenu.containerId, i, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);
                    moved++;
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(lbl);
                } catch (Exception ex) {
                    result(false, "withdraw threw: " + ex.getClass().getSimpleName() + " after " + moved);
                    try {
                        p.closeContainer();
                    } catch (Exception ex2) {
                    }
                    return;
                }
            }
        } finally {
            try {
                p.closeContainer();
            } catch (Exception ex) {
            }
        }
        if (moved == 0) {
            result(false, withdrawTarget == null ? "chest empty (" + withdrawChestDesc + ")" : "no '" + withdrawTarget + "' in " + withdrawChestDesc);
        } else {
            result(true, "withdrew:" + moved + " stack(s)" + (withdrawTarget == null ? "" : " of " + withdrawTarget) + " from " + withdrawChestDesc + (sb.length() > 0 ? " (" + sb.toString() + ")" : ""));
        }
        withdrawTarget = null;
        withdrawChestDesc = "";
    }

    private static boolean depositWants(net.minecraft.world.item.ItemStack s) {
        if (depositTargets != null) {
            for (String t : depositTargets) {
                if (nameMatches(s, t)) {
                    return true;
                }
            }
            return false;
        }
        return depositTarget == null || nameMatches(s, depositTarget);
    }

    /**
     * Stacks still in pockets that the current deposit run wanted but the chest
     * refused. Must only run while the target container menu is open: after
     * closeContainer the player inventory menu has a different layout, and a
     * naive scan there falsely counted the active rod as a leftover.
     */
    private static int countDepositLeftovers(LocalPlayer p, boolean skipActiveSlot) {
        try {
            if (p.containerMenu == null
                    || p.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu) {
                return 0;
            }
            int size = p.containerMenu.slots.size();
            int playerStart = Math.max(0, size - 36);
            int activeHotbarSlot = -1;
            if (skipActiveSlot) {
                try {
                    // The last 9 player slots in any container menu are hotbar 0..8.
                    activeHotbarSlot = size - 9 + p.getInventory().getSelectedSlot();
                } catch (Exception ex) {
                    activeHotbarSlot = -1;
                }
            }
            if (depositTargets != null) {
                // Fish banking: compare the pocket region against the pre-catch
                // baseline. The held rod, amour and any earlier pocketed items
                // are baked into that baseline, so only a stack the chest
                // REFUSED can exceed it.
                java.util.Map<String, Integer> cur = pocketNameCounts(p, size, playerStart, activeHotbarSlot);
                int left = 0;
                for (String t : depositTargets) {
                    left += Math.max(0,
                            cur.getOrDefault(t, 0) - fishBaseline.getOrDefault(t, 0));
                }
                return left;
            }
            int left = 0;
            for (int i = playerStart; i < size; i++) {
                if (i == activeHotbarSlot) {
                    continue;
                }
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                if (depositWants(s)) {
                    left++;
                }
            }
            return left;
        } catch (Exception ex) {
            return 0;
        }
    }

    /** Name -> count of stacks still in the container's player pocket region. */
    private static java.util.Map<String, Integer> pocketNameCounts(LocalPlayer p,
            int size, int playerStart, int activeHotbarSlot) {
        java.util.Map<String, Integer> cur = new java.util.LinkedHashMap<>();
        for (int i = playerStart; i < size; i++) {
            if (i == activeHotbarSlot) {
                continue;
            }
            net.minecraft.world.item.ItemStack s;
            try {
                s = p.containerMenu.getSlot(i).getItem();
            } catch (Exception ex) {
                continue;
            }
            if (s == null || s.isEmpty()) {
                continue;
            }
            try {
                String k = s.getHoverName().getString();
                cur.put(k, cur.getOrDefault(k, 0) + s.getCount());
            } catch (Exception ex) {
            }
        }
        return cur;
    }

    private static java.util.Map<String, Integer> buildTotals(Minecraft mc) {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        try {
            LocalPlayer p = mc.player;
            int size = p.containerMenu.slots.size();
            for (int i = 5; i < size; i++) {
                net.minecraft.world.item.ItemStack s;
                try {
                    s = p.containerMenu.getSlot(i).getItem();
                } catch (Exception ex) {
                    continue;
                }
                if (s == null || s.isEmpty()) {
                    continue;
                }
                String k;
                try {
                    k = s.getHoverName().getString();
                } catch (Exception ex) {
                    continue;
                }
                m.put(k, m.getOrDefault(k, 0) + s.getCount());
            }
        } catch (Exception ex) {
        }
        return m;
    }

    private static boolean holdingRod(Minecraft mc) {
        try {
            if (mc.player == null) {
                return false;
            }
            // Fishing always casts with MAIN_HAND. An offhand rod is not the
            // rod in use and must never satisfy the fishing checks.
            return isRod(mc.player.getMainHandItem());
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isRod(net.minecraft.world.item.ItemStack s) {
        try {
            if (s == null || s.isEmpty()) {
                return false;
            }
            String n;
            try {
                n = s.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            } catch (Exception ex) {
                return false;
            }
            if (n.contains("fishing rod")) {
                return true;
            }
            return s.getItem().getDescriptionId().toLowerCase(java.util.Locale.ROOT).contains("fishing_rod");
        } catch (Exception ex) {
            return false;
        }
    }

    /** Remaining uses of the main-hand rod only, or -1 if no such rod is held. */
    private static int rodRemainingDurability(Minecraft mc) {
        try {
            if (mc.player == null) {
                return -1;
            }
            net.minecraft.world.item.ItemStack main = mc.player.getMainHandItem();
            if (isRod(main)) {
                return main.isDamageableItem()
                        ? main.getMaxDamage() - main.getDamageValue() : -1;
            }
            return -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private static void stopLowRod(Minecraft mc, int remaining) {
        fishActive = false;
        fishBanking = false;
        fishNeedReel = false;
        fishRecastScheduled = false;
        fishJustAutoReeled = false;
        fishRecastTimer = 0;
        result(false, "rod durability low (" + remaining + " uses left) - fishing stopped");
        queueFishEvent("rodlow", "rod has " + remaining + " uses left");
    }

    private static void doCast(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        fishActive = false;
        fishRecastScheduled = false;
        fishJustAutoReeled = false;
        if (findMyHook(mc) != null) {
            result(false, "line already out - reel first");
            return;
        }
        try {
            mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            pendingCastCheckAt = System.currentTimeMillis() + 3500;
        } catch (Exception e) {
            result(false, "cast threw: " + e.getClass().getSimpleName());
        }
    }

    private static void trackCast(Minecraft mc) {
        if (pendingCastCheckAt <= 0) {
            return;
        }
        if (System.currentTimeMillis() < pendingCastCheckAt) {
            return;
        }
        pendingCastCheckAt = 0;
        if (mc.player == null) {
            result(false, "cast check: no player");
            return;
        }
        net.minecraft.world.entity.projectile.FishingHook hook = findMyHook(mc);
        if (hook == null) {
            result(false, "cast failed, no line out");
            return;
        }
        boolean wet;
        try {
            wet = hook.isInWater();
        } catch (Exception ex) {
            wet = false;
        }
        result(wet, wet ? "cast out, bobber in water" : "cast out, but NOT in water - re-aim him at water");
    }

    private static void doFishStart(Minecraft mc, String chest) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        if (!holdingRod(mc)) {
            result(false, "hold a fishing rod in the main hand first");
            return;
        }
        int rodLeft = rodRemainingDurability(mc);
        if (rodLeft >= 0 && rodLeft < 3) {
            stopLowRod(mc, rodLeft);
            return;
        }
        net.minecraft.world.entity.projectile.FishingHook existing = findMyHook(mc);
        if (fishActive) {
            if (existing == null) {
                result(true, "already fishing, casting soon");
                return;
            }
            if (hookNearWater(mc, existing)) {
                result(true, "already fishing, line is out");
                return;
            }
            // The line is on land/chest. Reel it and recast at the saved
            // water angle instead of leaving the session stuck.
            faceSavedFishingAngle(mc);
            fishYaw = mc.player.getYRot();
            fishPitch = mc.player.getXRot();
            fishNeedReel = true;
            fishRecastScheduled = true;
            fishRecastTimer = 0;
            fishJustAutoReeled = true;
            fishActionCooldown = 0;
            result(true, "line was dry - reeling and recasting");
            return;
        }
        lookTarget = null;
        fishBanking = false;
        fishNeedReel = existing != null;
        faceSavedFishingAngle(mc);
        fishYaw = mc.player.getYRot();
        fishPitch = mc.player.getXRot();
        fishChest = (chest == null || chest.isEmpty()) ? "copper" : chest;
        fishBaseline = buildTotals(mc);
        fishBanked = 0;
        fishRetries = 0;
        fishHookWasWet = false;
        fishCastAt = 0;
        fishPrevHookY = Double.NaN;
        fishJustAutoReeled = fishNeedReel;
        fishRecastScheduled = true;
        fishRecastTimer = 0;
        fishActionCooldown = fishNeedReel ? 6 : 10;
        fishActive = true;
        result(true, "fishing: watching pockets, banking to " + fishChest + ", facing yaw " + Math.round(fishYaw) + " pitch " + Math.round(fishPitch));
    }

    private static void doFishStop(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        fishActive = false;
        fishBanking = false;
        fishNeedReel = false;
        fishRecastScheduled = false;
        fishJustAutoReeled = false;
        fishRecastTimer = 0;
        net.minecraft.world.entity.projectile.FishingHook hook = findMyHook(mc);
        if (hook == null) {
            result(true, "fishing stopped, banked " + fishBanked + " stack(s); line already in");
            return;
        }
        try {
            mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            pendingReelCheckAt = System.currentTimeMillis() + 3000;
            result(true, "fishing stopped, banked " + fishBanked + " stack(s); reeling line");
        } catch (Exception ex) {
            result(false, "fishing stopped, banked " + fishBanked + " stack(s); line still out (reel failed)");
        }
    }

    private static void doFishStatus(Minecraft mc) {
        if (!fishActive) {
            result(true, "fishing: off");
            return;
        }
        result(true, "fishing: on, chest=" + fishChest + ", banked=" + fishBanked);
    }

    private static void queueFishEvent(String kind, String detail) {
        emit("{\"t\":" + Instant.now().toEpochMilli()
                + ",\"sender\":\"_system\",\"key\":\"_system\",\"text\":" + q(detail)
                + ",\"fish\":\"" + kind + "\"}");
    }

    private static void trackFish(Minecraft mc) {
        if (!fishActive) {
            return;
        }
        if (mc.player == null || mc.gameMode == null) {
            fishActive = false;
            fishBanking = false;
            result(false, "fishing aborted: no player");
            queueFishEvent("stopped", "fishing aborted: no player");
            return;
        }
        if (fishBanking || depositDeadline > 0 || withdrawDeadline > 0) {
            return;
        }
        if (tickCounter % 20 != 0) {
            return;
        }
        if (!holdingRod(mc)) {
            fishActive = false;
            fishRecastScheduled = false;
            result(false, "rod gone: fishing stopped");
            queueFishEvent("rodbroke", "rod broke or left hand");
            return;
        }
        int rodLeft = rodRemainingDurability(mc);
        if (rodLeft >= 0 && rodLeft < 3) {
            stopLowRod(mc, rodLeft);
            return;
        }
        net.minecraft.world.entity.projectile.FishingHook fh = findMyHook(mc);
        if (fh == null) {
            // A missing hook during an active session is the normal result of
            // a catch or a despawn. Recast; only an explicit fishstop cancels.
            if (!fishRecastScheduled && !fishJustAutoReeled) {
                fishRecastScheduled = true;
                fishRecastTimer = 20;
                fishJustAutoReeled = false;
            }
        } else {
            boolean wetNow = false;
            try {
                wetNow = fh.isInWater();
            } catch (Exception ex) {
                wetNow = false;
            }
            if (wetNow) {
                fishHookWasWet = true;
            }
            // Do NOT declare a dry cast from isInWater: a bobber sitting on the
            // surface can read false while the line is visibly in water. The
            // server bite flag is the authoritative signal; wait for it.
            // (A genuinely dry cast simply never bites and the owner can re-aim.)
        }
        java.util.Map<String, Integer> cur = buildTotals(mc);
        java.util.List<String> fresh = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : cur.entrySet()) {
            if (e.getValue() > fishBaseline.getOrDefault(e.getKey(), 0)) {
                fresh.add(e.getKey());
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        // Pause the fishing state machine while banking. The chest has to be
        // opened and the catch has to land before we ever cast again.
        fishBanking = true;
        depositTargets = fresh;
        depositTarget = null;
        depositIsFish = true;
        depositWant = 99;
        LocalPlayer p = mc.player;
        net.minecraft.core.BlockPos origin = p.blockPosition();
        net.minecraft.core.BlockPos bestPos = null;
        double bestDist = 25.0;
        String chestWant = fishChest.toLowerCase(java.util.Locale.ROOT);
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    net.minecraft.core.BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    net.minecraft.world.level.block.state.BlockState st = mc.level.getBlockState(pos);
                    if (st.isAir() || !isContainerBlock(st)) {
                        continue;
                    }
                    String bn;
                    try {
                        bn = st.getBlock().getName().getString().toLowerCase(java.util.Locale.ROOT);
                    } catch (Exception ex) {
                        bn = "";
                    }
                    if (!bn.contains(chestWant)) {
                        continue;
                    }
                    double d = p.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos));
                    if (d < bestDist) {
                        bestDist = d;
                        bestPos = pos;
                    }
                }
            }
        }
        if (bestPos == null) {
            depositTargets = null;
            depositIsFish = false;
            fishBanking = false;
            fishActive = false;
            result(false, "no '" + fishChest + "' container within 5m - fishing stopped");
            queueFishEvent("stopped", "no chest within 5m");
            return;
        }
        net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(bestPos);
        face(p, center);
        net.minecraft.core.Direction side = sideFacing(p, center);
        try {
            mc.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(center, side, bestPos, false));
        } catch (Exception e) {
            depositTargets = null;
            depositIsFish = false;
            fishBanking = false;
            fishActive = false;
            result(false, "chest open threw: " + e.getClass().getSimpleName());
            return;
        }
        try {
            depositChestDesc = mc.level.getBlockState(bestPos).getBlock().getName().getString() + " at " + bestPos.toShortString();
        } catch (Exception ex) {
            depositChestDesc = bestPos.toShortString();
        }
        depositWant = 99;
        depositDeadline = System.currentTimeMillis() + 5000;
    }

    private static net.minecraft.world.entity.projectile.FishingHook findMyHook(Minecraft mc) {
        try {
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) {
                return null;
            }
            for (net.minecraft.world.entity.Entity e : mc.level.getEntities(p,
                    new net.minecraft.world.phys.AABB(p.position(), p.position()).inflate(64.0),
                    entity -> entity instanceof net.minecraft.world.entity.projectile.FishingHook)) {
                try {
                    net.minecraft.world.entity.projectile.FishingHook h =
                            (net.minecraft.world.entity.projectile.FishingHook) e;
                    if (h.getPlayerOwner() == p) {
                        return h;
                    }
                } catch (Exception ex) {
                }
            }
        } catch (Exception ex) {
        }
        return null;
    }

    private static void doReel(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) {
            result(false, "no player/world yet");
            return;
        }
        fishActive = false;
        fishRecastScheduled = false;
        fishJustAutoReeled = false;
        if (findMyHook(mc) == null) {
            result(true, "line already in");
            return;
        }
        try {
            mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
            pendingReelCheckAt = System.currentTimeMillis() + 3000;
        } catch (Exception e) {
            result(false, "reel threw: " + e.getClass().getSimpleName());
        }
    }

    private static void trackReel(Minecraft mc) {
        if (pendingReelCheckAt <= 0) {
            return;
        }
        if (System.currentTimeMillis() < pendingReelCheckAt) {
            return;
        }
        pendingReelCheckAt = 0;
        if (mc.player == null) {
            result(false, "reel check: no player");
            return;
        }
        result(findMyHook(mc) == null, findMyHook(mc) == null ? "reeled in" : "hook still out");
    }

    private static void trackFishFast(Minecraft mc) {
        if (!fishActive || fishBanking || depositDeadline > 0 || withdrawDeadline > 0) {
            return;
        }
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        int rodLeft = rodRemainingDurability(mc);
        if (rodLeft >= 0 && rodLeft < 3) {
            stopLowRod(mc, rodLeft);
            return;
        }
        if (fishActionCooldown > 0) {
            fishActionCooldown--;
        }
        if (fishRecastTimer > 0) {
            fishRecastTimer--;
        }
        if (fishNeedReel) {
            if (findMyHook(mc) != null) {
                try {
                    faceFishDir(mc);
                    mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                } catch (Exception ex) {
                }
                fishNeedReel = false;
                fishRecastTimer = 20;
                fishActionCooldown = 6;
                return;
            }
            fishNeedReel = false;
        }
        if (fishRecastScheduled && fishRecastTimer <= 0 && fishActionCooldown <= 0) {
            if (!holdingRod(mc)) {
                return;
            }
            // Never use the rod while a hook is still out: that would reel,
            // not cast, and creates the random reel/cast loop.
            if (findMyHook(mc) != null) {
                fishRecastScheduled = false;
                fishJustAutoReeled = false;
                return;
            }
            try {
                faceFishDir(mc);
                mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
                fishCastAt = System.currentTimeMillis();
                fishHookWasWet = false;
            } catch (Exception ex) {
            }
            fishRecastScheduled = false;
            fishRecastTimer = 0;
            fishActionCooldown = 6;
            fishJustAutoReeled = false;
            return;
        }
        if (fishActionCooldown > 0) {
            return;
        }
        net.minecraft.world.entity.projectile.FishingHook hook = findMyHook(mc);
        if (hook == null) {
            return;
        }
        // Emergency stale-cast recovery: a line that has been out for over a
        // minute is reeled and recast even if the bite flag never arrived.
        long now = System.currentTimeMillis();
        if (fishCastAt > 0 && now - fishCastAt >= 60_000L) {
            fishCastAt = 0;
            fishNeedReel = true;
            fishRecastScheduled = true;
            fishRecastTimer = 0;
            fishJustAutoReeled = true;
            fishActionCooldown = 0;
            result(true, "cast idle over 60s - reeling and recasting");
            return;
        }
        boolean wet;
        try {
            wet = hook.isInWater();
        } catch (Exception ex) {
            return;
        }
        if (!wet) {
            return;
        }
        if (!isBiteHook(hook)) {
            return;
        }
        try {
            mc.gameMode.useItem(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND);
        } catch (Exception ex) {
            return;
        }
        fishJustAutoReeled = true;
        fishRecastScheduled = true;
        fishRecastTimer = 20;
        fishActionCooldown = 6;
    }

    private static void faceFishDir(Minecraft mc) {
        try {
            if (mc.player != null) {
                mc.player.setYRot(fishYaw);
                mc.player.setXRot(fishPitch);
            }
        } catch (Exception ex) {
        }
    }

    /** Use the saved water bearing instead of whatever direction the chest left us facing. */
    private static boolean faceSavedFishingAngle(Minecraft mc) {
        try {
            if (mc.player == null) {
                return false;
            }
            double[] a = loadDirs().get("fishing angle");
            if (a == null) {
                return false;
            }
            mc.player.setYRot((float) a[0]);
            mc.player.setXRot((float) a[1]);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** True when the bobber is actually over/near a water block, not a chest or dry land. */
    private static boolean hookNearWater(Minecraft mc,
            net.minecraft.world.entity.projectile.FishingHook hook) {
        try {
            if (mc.level == null || hook == null) {
                return false;
            }
            net.minecraft.core.BlockPos base = hook.blockPosition();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -2; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (mc.level.getBlockState(base.offset(dx, dy, dz))
                                .is(net.minecraft.world.level.block.Blocks.WATER)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Exception ex) {
            // If the check itself fails, do not reset a possibly good cast.
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isBiteHook(net.minecraft.world.entity.projectile.FishingHook hook) {
        double y;
        try {
            y = hook.getY();
        } catch (Exception ex) {
            return false;
        }
        boolean dipped = !Double.isNaN(fishPrevHookY) && (fishPrevHookY - y) > 0.2;
        fishPrevHookY = y;
        if (!fishKeyFailed) {
            try {
                if (fishBitingKey == null) {
                    net.minecraft.network.syncher.SynchedEntityData data = hook.getEntityData();
                    for (java.lang.reflect.Field f : net.minecraft.world.entity.projectile.FishingHook.class.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                                && net.minecraft.network.syncher.EntityDataAccessor.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            net.minecraft.network.syncher.EntityDataAccessor<?> key =
                                    (net.minecraft.network.syncher.EntityDataAccessor<?>) f.get(null);
                            Object val;
                            try {
                                val = data.get(key);
                            } catch (Exception e) {
                                continue;
                            }
                            if (val instanceof Boolean) {
                                fishBitingKey = (net.minecraft.network.syncher.EntityDataAccessor<Boolean>) key;
                                break;
                            }
                        }
                    }
                    if (fishBitingKey == null) {
                        fishKeyFailed = true;
                    }
                }
                if (fishBitingKey != null) {
                    fishPrevHookY = hook.getY();
                    return Boolean.TRUE.equals(hook.getEntityData().get(fishBitingKey));
                }
            } catch (Exception e) {
                fishKeyFailed = true;
            }
        }
        return dipped;
    }

    private static void emit(String json) {
        try {
            Files.write(QUEUE, (json + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("[jimbot] queue write failed: {}", e.toString());
        }
    }

    private static void result(boolean ok, String detail) {
        String json = "{\"t\":" + Instant.now().toEpochMilli() + ",\"ok\":" + ok
                + ",\"detail\":" + q(detail) + "}";
        try {
            Files.write(RESULT, (json + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("[jimbot] result write failed: {}", e.toString());
        }
    }

    private static final java.nio.file.Path CHATDEBUG = java.nio.file.Paths.get(
            System.getProperty("user.home"), "backend", "mcbot", "chat-debug.log");

    private static void debugIncoming(net.minecraft.network.chat.Component message, boolean overlay) {
        try {
            String s;
            try {
                s = message.getString();
            } catch (Exception ex) {
                s = "<getString threw " + ex.getClass().getSimpleName() + ">";
            }
            if (s.length() > 200) {
                s = s.substring(0, 200);
            }
            String line = System.currentTimeMillis() + " overlay=" + overlay
                    + " class=" + message.getClass().getName()
                    + " text=" + s.replace("\n", " ") + "\n";
            java.nio.file.Files.write(CHATDEBUG, line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ex) {
        }
    }

    private static void debugChatMessage(String sender, net.minecraft.network.chat.Component message) {
        try {
            String s;
            try {
                s = message.getString();
            } catch (Exception ex) {
                s = "<getString threw " + ex.getClass().getSimpleName() + ">";
            }
            if (s.length() > 200) {
                s = s.substring(0, 200);
            }
            String line = System.currentTimeMillis() + " CHAT sender=" + sender
                    + " class=" + message.getClass().getName()
                    + " text=" + s.replace("\n", " ") + "\n";
            java.nio.file.Files.write(CHATDEBUG, line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ex) {
        }
    }

    private static void handlePlayerChat(String sender, String text) {
        if (text == null) {
            return;
        }
        // Account name from the profile is authoritative; the visible text may
        // carry display-name decorations. Match triggers against the raw text.
        if (TRIGGER.matcher(text).find()) {
            emit("{\"t\":" + Instant.now().toEpochMilli()
                    + ",\"sender\":" + q(sender)
                    + ",\"key\":" + q(normKey(sender))
                    + ",\"text\":" + q(text.trim())
                    + ",\"global\":true}");
        }
    }

    private static String normKey(String name) {
        try {
            String folded = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
            if (folded.equals("varen")) {
                return "varen7";
            }
            return folded;
        } catch (Exception ex) {
            return name.toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", "") + "\"";
    }
}
