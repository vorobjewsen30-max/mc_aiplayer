package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DangerWatcher {
    public static final DangerWatcher INSTANCE = new DangerWatcher();
    private final Map<UUID, Integer> nextThreatAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextEatAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextResupplyAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextNightAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> observedSleepCompletionTicks = new ConcurrentHashMap<>();
    private final Map<UUID, TrapRecord> trapRecords = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextHuntAttemptTick = new ConcurrentHashMap<>();
    private final Map<UUID, PosRecord> darkStuckRecords = new ConcurrentHashMap<>(); // 规避:困死陷阱检测
    private final Map<UUID, Integer> nextEscapeHelpTick = new ConcurrentHashMap<>();  // 撤离求助节流

    // 第1层 困死退避:逃避类任务(evade/shelter)在同一格反复触发却没脱身,即判"被困",
    // 退避一段时间不再空派、并按间隔节流求助。终结"夜间困坑底每 2 秒 shelter/evade 死循环刷屏"。
    private static final int TRAP_REPEAT_LIMIT = 4;      // 同格反复避险 4 次 → 判被困
    private static final int TRAP_BACKOFF_TICKS = 600;   // 被困后退避 30s 不再空派威胁任务
    private static final int TRAP_HELP_INTERVAL = 1200;  // 求助消息最短间隔 60s(防刷屏)
    private static final int HUNT_FOOD_TARGET = 3;       // 第2层 饥饿链:没食物时主动猎取的生肉数量
    private static final int DARK_STUCK_TICKS = 160;     // 规避:地下黑暗处静止 8s 判"困死陷阱",撤回地面
    private static final float EMERGENCY_SHELTER_HP = 4.0F; // 夜间怪海:≤4 心+有敌 → 无视冷却立即筑墙保命

    private DangerWatcher() {
    }

    private record TrapRecord(BlockPos pos, int repeatCount, int lastHelpTick) {
    }

    private record PosRecord(BlockPos pos, int sinceTick) {
    }

    public void scanAll(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            scanBot(server, bot);
        }
    }

    public boolean scanBot(MinecraftServer server, AIPlayerEntity bot) {
        // SAFE-DEAD:死亡的 bot 不再无限派 evade(僵尸循环)。满血复活到地表,清任务/计划,中文告知。
        if (bot.getHealth() <= 0.0F || !bot.isAlive()) {
            BlockPos deathPos = bot.getBlockPos();
            long deathTick = server.getTicks();
            AIPlayerManager.INSTANCE.respawnDeadBot(bot);
            TaskManager.INSTANCE.abort(bot);
            io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.clear(bot);
            // 死亡找回反射:装备掉在死亡点(5 分钟 despawn),真实玩家第一反应就是跑尸。
            // 自动出发的两个闸:①重生点离死亡点 ≤160(太远赶不上白跑);②死亡点不在危险区
            // (同区两死记忆会立牌——记忆劝阻就听劝,别第三次送死,装备认亏)。
            boolean nearEnough = bot.getBlockPos().isWithinDistance(deathPos, 160.0D);
            boolean dangerous = io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE
                    .isDanger(bot.getUuid(), deathPos);
            if (nearEnough && !dangerous) {
                TaskManager.INSTANCE.assign(bot, new RecoverDropsTask(deathPos, deathTick));
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                        bot.getGameProfile().getName() + " 死亡后已复活,正赶回 "
                                + deathPos.toShortString() + " 找回掉落装备。");
            } else {
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                        bot.getGameProfile().getName() + " 死亡后已自动复活到地面。"
                                + (dangerous ? "(死亡点已是危险区,放弃跑尸)" : ""));
            }
            return true;
        }
        Optional<Threat> threat = collectTopThreat(bot);
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        // 入浆即自救(最高优先,压倒威胁):岩浆每 tick 烧 4,几秒就死。SurvivalGuard 只中断作业、注释说
        // "让位 DangerWatcher 脱困"但从未实现——bot 泡在岩浆里被烧死(real_diamond 下潜挖穿岩浆袋,14/15 步功亏一篑)。
        // 这里补上:身陷岩浆且当前不是逃浆任务 → 立即派 LavaEscapeTask,把命先捞回来。
        if (bot.isInLava() && !(active.isPresent() && active.get() instanceof LavaEscapeTask)) {
            if (active.isPresent()) {
                TaskManager.INSTANCE.pauseFor(bot, "lava_escape");
            }
            TaskManager.INSTANCE.assign(bot, new LavaEscapeTask());
            BotLog.danger(bot, "lava_escape_start", "pos", bot.getBlockPos().toShortString(),
                    "hp", (int) bot.getHealth());
            return true;
        }
        // 夜间怪海保命(治死亡螺旋):濒死(≤4 心)+ 有敌 + 当前没在筑墙 → 立即筑墙自保,**无视威胁冷却**。
        // 元凶:combat 完(~100t 没杀光)→进 100t 冷却→gather 恢复挨打→guard 中止→冷却没过 shelter
        // 派不出→再挨打到死(real_diamond 三种子全栽这,bot 会打但打不赢多怪围殴)。保命压倒一切:
        // 围一圈墙把自己封进去,怪够不到,血止住,熬过去。需有可放方块(有原木/圆石即可)。
        // 地下无处可逃:深处隧道挖矿被怪贴脸时,evade 逃向 20 格外的点多半落在实心石里→假逃磨血(已在
        // EvadeTask 修为 fail)。这里主动兜底:头顶不见天(地下)且正在挨打(hurtTime>0)→ 不等濒死直接封墙,
        // 因为地下逃跑无效、拖到 ≤4 心才入土往往已死(real_diamond 深层挖矿 evade/guard_low_hp 送命主因)。
        boolean cannotFlee = !bot.getServerWorld().isSkyVisible(bot.getBlockPos());
        boolean entombNow = bot.getHealth() <= EMERGENCY_SHELTER_HP || (cannotFlee && bot.hurtTime > 0);
        // 死亡螺旋修复(self-inflicted 倒挂):collectTopThreat 在血<6 时把 top 改写成 LOW_HP/entity=null,
        // 使本闸的 type==HOSTILE 判定恰在最该入土时失效(血越低越筑不了墙,正是上面注释想治的螺旋)。
        // 补:LOW_HP 抢占时独立扫一次近处可达 hostile,有则照样入土。不动威胁分类早返回,避免误伤 DROWNING/LAVA。
        boolean topIsHostile = threat.isPresent() && threat.get().type() == Threat.Type.HOSTILE;
        boolean lowHpUnderHostile = threat.isPresent() && threat.get().type() == Threat.Type.LOW_HP
                && hasReachableHostile(bot);
        if ((topIsHostile || lowHpUnderHostile)
                && entombNow
                && EmergencyShelterTask.hasShelterBlock(bot)
                && !(active.isPresent() && active.get() instanceof EmergencyShelterTask)) {
            if (active.isPresent()) {
                TaskManager.INSTANCE.pauseFor(bot, "emergency_entomb");
            }
            TaskManager.INSTANCE.assign(bot, new EmergencyShelterTask());
            BotLog.danger(bot, "emergency_entomb", "hp", (int) bot.getHealth(),
                    "underground", cannotFlee, "threat", threat.get().type());
            return true;
        }
        if (threat.isPresent()) {
            Threat top = threat.get();
            if (top.severity().ordinal() >= Threat.Severity.MEDIUM.ordinal()
                    && shouldAssignThreatTask(active, top)
                    && canAssignThreatTask(server, bot, top)) {
                Task task = decideCombatOrEvade(bot, top);
                if (trappedBackoff(server, bot, task)) {
                    return true; // 被困:退避并(节流)求助,不再每 2 秒空派 shelter/evade
                }
                if (active.isPresent() && shouldPauseForThreat(active.get(), top, task)) {
                    TaskManager.INSTANCE.pauseFor(bot, "threat: " + top.type());
                }
                TaskManager.INSTANCE.assign(bot, task);
                nextThreatAttemptTick.put(bot.getUuid(), server.getTicks() + threatCooldownTicks(top, task));
                BotLog.danger(bot, "threat_detected",
                        "type", top.type(),
                        "severity", top.severity(),
                        "source", top.pos(),
                        "decision", task.name());
                return true;
            }
        }
        // 规避加固(保命兜底):困死在地下黑暗处 → 撤回地面,优先于补给/进食。
        if (maybeEscapeDarkTrap(server, bot, active)) {
            return true;
        }
        if (maybeResupply(server, bot, active)) {
            return true;
        }
        if (maybeEat(server, bot, active)) {
            return true;
        }
        if (maybeStartNightTask(server, bot, active)) {
            return true;
        }
        if (maybeLightDarkArea(server, bot, active)) {
            return true;
        }
        if (active.isEmpty() && BrainCoordinator.INSTANCE.maybeWakeForFailureOrGoal(bot)) {
            return true;
        }
        if (active.isEmpty() && TaskManager.INSTANCE.hasPaused(bot)) {
            TaskManager.INSTANCE.resumeFromPause(bot);
            return true;
        }
        return false;
    }

    private boolean maybeResupply(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (active.isPresent() && active.get() instanceof ResupplyTask) {
            return true;
        }
        if (active.isPresent() && (active.get() instanceof EvadeTask || active.get() instanceof CombatTask || active.get() instanceof EatTask)) {
            return false;
        }
        int now = server.getTicks();
        if (now < nextResupplyAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }

        ResupplyTask task = null;
        ItemStack mainHand = bot.getMainHandStack();
        if (isNearlyBroken(mainHand)) {
            Item item = mainHand.getItem();
            task = ResupplyTask.tool(item);
        } else {
            AIBotConfig.Survival survival = AIBotConfig.get().survival();
            // 没食物时:周围有猎物 → 让路给 maybeEat 的猎食(野外猎肉比翻箱找小麦可靠,见第2层饥饿链),
            // 周围没猎物才走 ResupplyTask.food()(翻储备箱)。修"饿了反复 resupply 找小麦失败而不去猎肉"。
            if (bot.getHungerManager().getFoodLevel() <= survival.hungerEatThreshold()
                    && InventoryAction.findFoodSlot(bot) < 0
                    && !HuntTask.hasPreyNearby(bot)) {
                task = ResupplyTask.food();
            }
        }

        if (task == null) {
            return false;
        }
        if (active.isPresent()) {
            TaskManager.INSTANCE.pauseFor(bot, "resupply");
        }
        TaskManager.INSTANCE.assign(bot, task);
        nextResupplyAttemptTick.put(bot.getUuid(), now + 200);
        BotLog.danger(bot, "resupply_started", "need", task.describe());
        return true;
    }

    private boolean maybeEat(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        int foodLevel = bot.getHungerManager().getFoodLevel();
        AIBotConfig.Survival survival = AIBotConfig.get().survival();
        if (foodLevel > survival.hungerEatThreshold()) {
            return false;
        }
        if (active.isPresent() && active.get() instanceof EatTask) {
            return true;
        }
        int now = server.getTicks();
        if (now < nextEatAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }
        if (InventoryAction.findFoodSlot(bot) < 0) {
            // 第2层 饥饿链:没有任何食物 → 若周围有可猎动物,主动猎杀获取生肉,而非干等饿死。
            if (huntForFood(server, bot, active)) {
                return true;
            }
            nextEatAttemptTick.put(bot.getUuid(), now + 100);
            return false;
        }

        boolean critical = foodLevel <= survival.hungerCriticalThreshold();
        if (active.isPresent()) {
            if (!critical || active.get() instanceof EvadeTask) {
                return false;
            }
            TaskManager.INSTANCE.pauseFor(bot, "hunger: " + foodLevel);
        }
        TaskManager.INSTANCE.assign(bot, new EatTask());
        nextEatAttemptTick.put(bot.getUuid(), now + 100);
        BotLog.danger(bot, "hunger_eat_started", "food", foodLevel, "critical", critical);
        return true;
    }

    // 第2层 饥饿链:没食物时主动猎食(获取生肉)。仅在不处于威胁应对(evade/combat)时派;周围无猎物则不空派。
    private boolean huntForFood(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (active.isPresent()) {
            if (active.get() instanceof HuntTask) {
                return true; // 已在猎食,保持
            }
            if (active.get() instanceof EvadeTask || active.get() instanceof CombatTask) {
                return false; // 正在应对威胁,别打断
            }
        }
        int now = server.getTicks();
        if (now < nextHuntAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }
        if (!HuntTask.hasPreyNearby(bot)) {
            nextHuntAttemptTick.put(bot.getUuid(), now + 200); // 周围没猎物,过会儿再看
            return false;
        }
        if (active.isPresent()) {
            TaskManager.INSTANCE.pauseFor(bot, "hunt_for_food");
        }
        TaskManager.INSTANCE.assign(bot, new HuntTask(HUNT_FOOD_TARGET));
        nextHuntAttemptTick.put(bot.getUuid(), now + 400);
        BotLog.danger(bot, "hunt_for_food_started", "food", bot.getHungerManager().getFoodLevel());
        return true;
    }

    private Task decideCombatOrEvade(AIPlayerEntity bot, Threat threat) {
        AIBotConfig.Combat combat = AIBotConfig.get().combat();
        // combat 困死:连续多次 combat 被 stuck 中止(目标够不到——如僵尸在下方矿洞/墙后)→ 别再站桩等死,改逃跑。
        if (canFight(bot, threat, combat) && !combatStuck(bot)) {
            return new CombatTask(threat.entity().getType(), 1, combat.retreatHp());
        }
        // Night shelter removed � bot fights instead (PVP path)
        return new EvadeTask(threat);
    }

    // 第1层:困死退避 + 求助。仅针对逃避类(evade/shelter);战斗(canFight→CombatTask)不拦。
    // bot 反复在同一格触发逃避却没移动(被围/困坑底)→ 累加;达阈值即退避(长 cooldown 静默等救援)
    // 并节流向玩家求助,而非每 2 秒空派一次 shelter/evade 刷屏。bot 真在逃(位置变)则计数自然重置。
    private boolean trappedBackoff(MinecraftServer server, AIPlayerEntity bot, Task next) {
        if (!(next instanceof EvadeTask) && !(next instanceof EmergencyShelterTask)) {
            trapRecords.remove(bot.getUuid());
            return false;
        }
        int now = server.getTicks();
        BlockPos here = bot.getBlockPos().toImmutable();
        TrapRecord rec = trapRecords.get(bot.getUuid());
        if (rec == null || !rec.pos().isWithinDistance(here, 2.5D)) {
            trapRecords.put(bot.getUuid(), new TrapRecord(here, 1, 0));
            return false;
        }
        int repeat = rec.repeatCount() + 1;
        // 绝境反击:困住(逃跑反复原地)且正在挨打——退避=站着等死(real_iron 实测:洞穴 13 蛛贴脸,
        // evade 目标算出原地 1t 完成,backoff 停发威胁任务后被围殴致死)。canFight 的武器/数量闸
        // 是"打得划算吗"的算计,绝境没得算:空手也开打,伤害换活命窗口。
        if (repeat >= 2 && bot.hurtTime > 0) {
            trapRecords.remove(bot.getUuid());
            var hostile = bot.getServerWorld().getEntitiesByClass(
                    net.minecraft.entity.mob.HostileEntity.class,
                    bot.getBoundingBox().expand(4.0D), e -> e.isAlive())
                    .stream().findFirst().orElse(null);
            if (hostile != null) {
                BotLog.danger(bot, "trapped_fight_back", "target", hostile.getType().toString());
                TaskManager.INSTANCE.assign(bot, new CombatTask(hostile.getType(), 1, 0.0F));
                return true;
            }
        }
        if (repeat < TRAP_REPEAT_LIMIT) {
            trapRecords.put(bot.getUuid(), new TrapRecord(rec.pos(), repeat, rec.lastHelpTick()));
            return false;
        }
        nextThreatAttemptTick.put(bot.getUuid(), now + TRAP_BACKOFF_TICKS);
        if (now - rec.lastHelpTick() >= TRAP_HELP_INTERVAL) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                    bot.getGameProfile().getName() + " 被困在 (" + here.getX() + "," + here.getY() + "," + here.getZ()
                            + "),反复避险都没能脱身。请把我传送到安全开阔的地面。");
            BotLog.danger(bot, "trapped_backoff", "pos", here.getX() + "," + here.getY() + "," + here.getZ(), "repeat", repeat);
            trapRecords.put(bot.getUuid(), new TrapRecord(here, 0, now));
        } else {
            trapRecords.put(bot.getUuid(), new TrapRecord(here, repeat, rec.lastHelpTick()));
        }
        return true;
    }

    private boolean maybeStartNightTask(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        AIBotConfig.Night night = AIBotConfig.get().night();
        if (!night.autoSleep() || bot.getServerWorld().isDay() || active.isPresent()) {
            return false;
        }
        // 目标计划进行中(步骤间隙 active 短暂为空)不插夜间照明:它是 foreign task,会让 GoalExecutor
        // 放弃整个目标(与 maybeLightDarkArea 同款守护——实测 real_iron_bulk 夜里挖到 91/100 时,步骤间隙被
        // 夜间点灯抢走 → goal_abandoned → 卡 light_area churn 永不完成)。深矿照明由 GoalPlanner 火把前置负责。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        int now = server.getTicks();
        TaskStatus lastStatus = TaskManager.INSTANCE.status(bot);
        if ("sleep".equals(lastStatus.name()) && lastStatus.state() == TaskState.COMPLETED) {
            Integer observedElapsed = observedSleepCompletionTicks.putIfAbsent(bot.getUuid(), lastStatus.elapsedTicks());
            if (observedElapsed == null || observedElapsed != lastStatus.elapsedTicks()) {
                observedSleepCompletionTicks.put(bot.getUuid(), lastStatus.elapsedTicks());
                nextNightAttemptTick.put(bot.getUuid(), now + 600);
                return false;
            }
        }
        if (now < nextNightAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false;
        }
        // 睡觉功能暂时取消(以后再加):夜间不睡床,只在有火把时补光防刷怪。
        Task task;
        if (InventoryAction.countItem(bot, net.minecraft.item.Items.TORCH) > 0) {
            task = new LightAreaTask(8, 8);
        } else {
            nextNightAttemptTick.put(bot.getUuid(), now + 600);
            return false;
        }
        TaskManager.INSTANCE.assign(bot, task);
        nextNightAttemptTick.put(bot.getUuid(), now + 600);
        BotLog.danger(bot, "night_task_started", "task", task.name());
        return true;
    }

    // 规避加固:地下/黑暗处(方块光照<8)只要 idle 且有火把,就先点亮——从源头减少怪物在身边刷新。
    // 不限夜晚(地下白天 light=0 同样刷怪)。仅 active 为空(idle/目标步骤间隙)时派,避免打断挖矿。
    private boolean maybeLightDarkArea(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        if (active.isPresent()) {
            return false;
        }
        // 目标计划进行中(步骤间隙 active 会短暂为空)不要插照明:它是 foreign task,会让 GoalExecutor
        // 放弃整个目标(实测:金锭挖到 raw_gold、熔炼前的空隙被照明抢走 → goal_abandoned、没熔炼 → 无金锭)。
        // 深矿照明由 GoalPlanner 的挖矿前置(火把步)负责,不靠这个 idle 反射。
        if (io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        if (world.isSkyVisible(feet)
                || world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) >= 8) {
            return false;
        }
        if (InventoryAction.countItem(bot, net.minecraft.item.Items.TORCH) <= 0) {
            return false; // 没火把点不了——由 GoalPlanner 挖深矿前置备火把兜底
        }
        int now = server.getTicks();
        if (now < nextNightAttemptTick.getOrDefault(bot.getUuid(), 0)) {
            return false; // 复用夜间节流,避免每次扫描都派
        }
        TaskManager.INSTANCE.assign(bot, new LightAreaTask(8, 8));
        nextNightAttemptTick.put(bot.getUuid(), now + 600);
        BotLog.danger(bot, "dark_area_lit",
                "light", world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet));
        return true;
    }

    // 规避加固(保命兜底):bot 卡在"地下 + 黑暗"处 = 困死陷阱(随时被刷怪秒杀)。只盯移动类(move)卡住
    // 与 idle 静止——挖矿/熔炼等有各自看门狗或属合理静止,先让它们 fail。检测到困死就 teleport 撤回
    // 地面 + 清当前目标 + 求助(节流)。牺牲当次目标换保命;回地面后大脑可重试(届时已备火把更安全)。
    private boolean maybeEscapeDarkTrap(MinecraftServer server, AIPlayerEntity bot, Optional<Task> active) {
        // isWaiting=任务自报"原地作业是正常态":MoveTask 挖掘式直行破硬石时一站好几秒,
        // 黑暗+同格被误判困死、被'救'上地面任务报废(nav 套件画布后实测两连 aborted)。
        if (active.isPresent() && (!"move".equals(active.get().name()) || active.get().isWaiting())) {
            darkStuckRecords.remove(bot.getUuid());
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        boolean darkUnderground = !world.isSkyVisible(feet)
                && world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) < 8;
        if (!darkUnderground) {
            darkStuckRecords.remove(bot.getUuid());
            return false;
        }
        int now = server.getTicks();
        PosRecord rec = darkStuckRecords.get(bot.getUuid());
        if (rec == null || !rec.pos().equals(feet)) {
            darkStuckRecords.put(bot.getUuid(), new PosRecord(feet, now));
            return false;
        }
        if (now - rec.sinceTick() < DARK_STUCK_TICKS) {
            return false; // 还没卡够久
        }
        darkStuckRecords.remove(bot.getUuid());
        if (!escapeToSurface(bot)) {
            return false; // 上方没有露天可站点(极少),交还其它逻辑
        }
        TaskManager.INSTANCE.abort(bot);
        // 问题4:不再 clear 目标——撤回地面后保留挖钻石目标,GoalExecutor 会重规划/重试当前步继续
        //(abort 当前困住的 task → handleStepFailure 重规划;bot 在地面、环境变了不再困)。实测:旧逻辑撤回后把任务忘了。
        BotLog.danger(bot, "dark_trap_escape",
                "from", feet.getX() + "," + feet.getY() + "," + feet.getZ());
        if (now >= nextEscapeHelpTick.getOrDefault(bot.getUuid(), 0)) {
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "system",
                    bot.getGameProfile().getName() + " 被困在黑暗矿洞太久、有被刷怪秒杀的风险,已撤回地面,稍后继续未完成的任务。");
            nextEscapeHelpTick.put(bot.getUuid(), now + TRAP_HELP_INTERVAL);
        }
        return true;
    }

    // teleport 上浮到正上方最近的露天可站点(保命兜底,清 fallDistance)。
    private boolean escapeToSurface(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        int top = world.getBottomY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 120; dy++) {
            BlockPos cand = feet.up(dy);
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(world, cand)
                    && world.isSkyVisible(cand)) {
                bot.getActionPack().stopAll();
                bot.teleport(world, cand.getX() + 0.5D, cand.getY(), cand.getZ() + 0.5D,
                        java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                return true;
            }
        }
        return false;
    }

    // combat 困死检测:连续 ≥2 次 combat 被 StuckWatcher 中止(stuck:combat),说明目标够不到 → 改逃,别站桩被打死。
    private boolean combatStuck(AIPlayerEntity bot) {
        Optional<TaskManager.FailureRecord> fail = TaskManager.INSTANCE.peekFailure(bot);
        return fail.isPresent()
                && "combat".equals(fail.get().name())
                && fail.get().reason().contains("stuck")
                && fail.get().count() >= 2;
    }

    private boolean canFight(AIPlayerEntity bot, Threat threat, AIBotConfig.Combat combat) {
        if (threat.type() != Threat.Type.HOSTILE || threat.entity() == null || !threat.entity().isAlive()) {
            return false;
        }
        if (bot.getHealth() <= combat.retreatHp()) {
            return false;
        }
        if (threat.entity() instanceof CreeperEntity) {
            return false; // 苦力怕一律不近战(会爆炸秒杀满血 bot),始终改逃
        }
        int hostiles = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(8.0D),
                        entity -> entity instanceof HostileEntity && entity.isAlive())
                .size();
        return hostiles <= combat.maxEnemiesToFight() && EquipAction.bestWeaponSlot(bot).isPresent();
    }

    private static boolean shouldAssignThreatTask(Optional<Task> active, Threat threat) {
        if (active.isEmpty()) {
            return true;
        }
        Task task = active.get();
        if (task instanceof EvadeTask) {
            return false;
        }
        return !(task instanceof CombatTask) || threat.type() == Threat.Type.LOW_HP;
    }

    private static boolean shouldPauseForThreat(Task active, Threat threat, Task nextTask) {
        // 已在战斗/逃跑 → 不二次暂停(让其自行重定向)。
        if (active instanceof CombatTask || active instanceof EvadeTask) {
            return false;
        }
        // FREEZE fix:其它进行中的任务(挖矿/采集/合成…)遇任何威胁一律**暂停保留**,打完/逃完再 resume,
        // 而不是被后续 assign 直接 abort 销毁。旧逻辑对"敌对→战斗"和 LOW_HP 都返回 false=不暂停=销毁当前任务,
        // 导致 GoalExecutor 把它判为 foreign 而整体放弃目标(实测刷怪时挖矿目标被反复放弃、空转发呆)。
        return true;
    }

    private boolean canAssignThreatTask(MinecraftServer server, AIPlayerEntity bot, Threat threat) {
        return server.getTicks() >= nextThreatAttemptTick.getOrDefault(bot.getUuid(), 0);
    }

    private static int threatCooldownTicks(Threat threat, Task task) {
        if (threat.type() == Threat.Type.LOW_HP || threat.severity() == Threat.Severity.HIGH) {
            return 100;
        }
        return task instanceof EvadeTask ? 80 : 40;
    }

    private static boolean isNearlyBroken(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) {
            return false;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return false;
        }
        return max - stack.getDamage() <= max * 0.10D;
    }

    private static Optional<Threat> collectTopThreat(AIPlayerEntity bot) {
        if (bot.getHealth() < 6.0F) {
            return Optional.of(new Threat(Threat.Type.LOW_HP, Threat.Severity.HIGH, null, bot.getBlockPos()));
        }
        // 规避加固:检测半径 10,但只把"能真正威胁到 bot"的敌对怪算进来——bot 眼睛到怪眼睛之间若被实心
        // 方块阻隔(隔着墙/在另一条隧道),怪根本够不到 bot,不应触发战斗/逃跑(实测 bug:被方块挡着的怪
        // 让 bot 一直"正在战斗"、中断正常挖矿)。按距离从近到远找第一个有视线(可达)的怪。
        List<LivingEntity> hostiles = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(10.0D),
                        entity -> entity instanceof HostileEntity && entity.isAlive());
        hostiles.sort(Comparator.comparingDouble(bot::distanceTo));
        for (LivingEntity mob : hostiles) {
            if (!canReachThreat(bot, mob)) {
                continue; // 被方块阻隔,够不到 bot → 不算威胁
            }
            Threat.Severity severity = mob instanceof CreeperEntity
                    ? Threat.Severity.HIGH : Threat.Severity.MEDIUM;
            return Optional.of(new Threat(Threat.Type.HOSTILE, severity, mob, mob.getBlockPos()));
        }
        // BUGFIX: игрок бьёт бота — тоже угроза
        if (bot.hurtTime > 0) {
            var src = bot.getRecentDamageSource();
            if (src != null && src.getAttacker() instanceof net.minecraft.entity.player.PlayerEntity attacker) {
                return Optional.of(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH, attacker, attacker.getBlockPos()));
            }
        }
        if (bot.isSubmergedInWater() && bot.getAir() < 50) {
            return Optional.of(new Threat(Threat.Type.DROWNING, Threat.Severity.MEDIUM, null, bot.getBlockPos()));
        }
        Optional<BlockPos> lava = BlockPos.stream(bot.getBlockPos().add(-2, -1, -2), bot.getBlockPos().add(2, 1, 2))
                .filter(pos -> {
                    BlockState state = bot.getServerWorld().getBlockState(pos);
                    return state.getFluidState().isIn(FluidTags.LAVA);
                })
                .map(BlockPos::toImmutable)
                .findFirst();
        if (lava.isPresent()) {
            return Optional.of(new Threat(Threat.Type.LAVA, Threat.Severity.HIGH, null, lava.get()));
        }
        if (bot.fallDistance > 5.0F && !bot.isOnGround()) {
            return Optional.of(new Threat(Threat.Type.FALLING, Threat.Severity.LOW, null, bot.getBlockPos()));
        }
        return Optional.empty();
    }

    private static boolean canReachThreat(AIPlayerEntity bot, LivingEntity mob) {
        return CombatCore.hasLineOfSight(bot, mob);
    }


    // 近处(8 格)是否有可达(有视线)的敌对怪。用于濒死封墙闸在 LOW_HP 抢占下补判——血<6 时 collectTopThreat
    // 已把 top 改写成 LOW_HP/entity=null,丢了 hostile 信息,这里独立扫一次还原"是否真被怪围"。复用同款视线判定。
    private static boolean hasReachableHostile(AIPlayerEntity bot) {
        List<LivingEntity> hostiles = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(8.0D),
                        entity -> entity instanceof HostileEntity && entity.isAlive());
        for (LivingEntity mob : hostiles) {
            if (canReachThreat(bot, mob)) {
                return true;
            }
        }
        return false;
    }
}



