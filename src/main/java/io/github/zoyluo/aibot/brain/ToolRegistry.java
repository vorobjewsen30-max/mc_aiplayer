package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.MovementAction;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.ForageTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public ToolRegistry() {
        registerDefaults();
    }

    public Optional<ToolDefinition> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> allTools() {
        return List.copyOf(tools.values());
    }

    private void registerDefaults() {
        register("say", "Reply to the human in chat", objectSchema()
                .property("message", stringSchema("the text to say"))
                .required("message")
                .build(), (bot, args) -> {
            String message = requiredString(args, "message");
            bot.getServer().getPlayerManager().broadcast(net.minecraft.text.Text.literal("<" + bot.getGameProfile().getName() + "> " + message), false);
            return ok("said");
        });

        register("look_at", "Turn the bot's head toward a coordinate", xyzSchema(), (bot, args) -> {
            LookAction.lookAt(bot, new Vec3d(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z")));
            return ok("looked");
        });

        register("move_to", "Pathfind to a coordinate. Falls back to straight-line walking if pathfinding fails.", xyzSchema(), (bot, args) -> {
            BlockPos goal = blockPos(args);
            io.github.zoyluo.aibot.action.ActionResult pathResult = MovementAction.startPathTo(bot, goal);
            if (pathResult.isInProgress() || pathResult.isSuccess()) {
                return ok("pathfinding_started");
            }
            io.github.zoyluo.aibot.action.ActionResult fallback = MovementAction.startWalkTo(bot, Vec3d.ofCenter(goal));
            if (fallback.isInProgress() || fallback.isSuccess()) {
                return ok("fallback_walk_started: " + pathResult.reason());
            }
            return fail("path_and_walk_both_failed: " + pathResult.reason());
        });

        register("mine_block", "Break the block at given coords. Bot must already be within reach.", xyzSchema(), (bot, args) -> {
            BlockPos pos = blockPos(args);
            MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
            return ok("started");
        });

        register("place_block", "Place the currently held block at given coords", xyzSchema(), (bot, args) -> {
            return result(BuildAction.placeBlockAt(bot, blockPos(args)));
        });

        register("select_hotbar", "Select hotbar slot 0..8", objectSchema()
                .property("slot", integerSchema("hotbar slot", 0, 8))
                .required("slot")
                .build(), (bot, args) -> result(InventoryAction.selectHotbar(bot, requiredInt(args, "slot"))));

        register("inventory", "Get the bot's current inventory", objectSchema().build(), (bot, args) ->
                ok(InventoryAction.summarize(bot).toString()));

        register("attack_entity", "Attack a nearby entity by type", objectSchema()
                .property("entity_type", stringSchema("entity type, for example minecraft:cow"))
                .required("entity_type")
                .build(), (bot, args) -> {
            String entityType = requiredString(args, "entity_type");
            Identifier id = Identifier.of(entityType);
            Optional<Entity> target = bot.getServerWorld()
                    .getOtherEntities(bot, bot.getBoundingBox().expand(4.5D), entity -> Registries.ENTITY_TYPE.getId(entity.getType()).equals(id))
                    .stream()
                    .min(Comparator.comparingDouble(bot::distanceTo));
            if (target.isEmpty()) {
                return fail("no_nearby_entity: " + entityType);
            }
            return result(InteractAction.attackEntity(bot, target.get()));
        });

        register("stop", "Stop all ongoing actions", objectSchema().build(), (bot, args) -> {
            MovementAction.stopAll(bot);
            return ok("stopped");
        });

        register("assign_task", "Start a high-level task for the bot. Supersedes any current task. Build params: blueprint plus anchor_x/anchor_y/anchor_z. x/y/z aliases are accepted.", objectSchema()
                .property("task_type", stringSchema("move, forage, mine, or build"))
                .property("params", objectSchema().build())
                .required("task_type")
                .required("params")
                .build(), (bot, args) -> {
            Task task = createTask(bot, requiredString(args, "task_type"), args.getAsJsonObject("params"));
            TaskManager.INSTANCE.assign(bot, task);
            return ok("assigned: " + task.name());
        });

        register("get_task_status", "Get the current task status", objectSchema().build(), (bot, args) -> {
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            return ok("{\"name\":\"" + escape(status.name())
                    + "\",\"state\":\"" + status.state()
                    + "\",\"progress\":" + status.progress()
                    + ",\"description\":\"" + escape(status.description()) + "\"}");
        });

        register("abort_task", "Cancel the current task", objectSchema().build(), (bot, args) -> {
            TaskManager.INSTANCE.abort(bot);
            return ok("aborted");
        });
    }

    private static Task createTask(io.github.zoyluo.aibot.entity.AIPlayerEntity bot, String taskType, JsonObject params) {
        if (params == null) {
            throw new IllegalArgumentException("missing_or_bad_arg: params");
        }
        return switch (taskType) {
            case "move" -> new MoveTask(bot, new BlockPos(requiredInt(params, "x"), requiredInt(params, "y"), requiredInt(params, "z")));
            case "forage" -> new ForageTask(
                    Registries.ENTITY_TYPE.get(Identifier.of(requiredString(params, "entity_type"))),
                    optionalInt(params, "count", 1));
            case "mine" -> {
                Block block = Registries.BLOCK.get(Identifier.of(requiredString(params, "block")));
                yield new MineTask(block, optionalInt(params, "count", 1));
            }
            case "build" -> {
                try {
                    yield new BuildTask(
                            BlueprintLoader.load(requiredString(params, "blueprint")),
                            new BlockPos(
                                    intWithAlias(params, "anchor_x", "x"),
                                    intWithAlias(params, "anchor_y", "y"),
                                    intWithAlias(params, "anchor_z", "z")));
                } catch (java.io.IOException exception) {
                    throw new IllegalArgumentException(exception.getMessage(), exception);
                }
            }
            default -> throw new IllegalArgumentException("unknown_task_type: " + taskType);
        };
    }

    private void register(String name, String description, JsonObject schema, ToolDefinition.Handler handler) {
        tools.put(name, new ToolDefinition(name, description, schema, handler));
    }

    private static ToolDefinition.ToolResult result(io.github.zoyluo.aibot.action.ActionResult actionResult) {
        if (actionResult.isSuccess() || actionResult.isInProgress()) {
            return ok(actionResult.status().name().toLowerCase());
        }
        return fail(actionResult.reason());
    }

    private static ToolDefinition.ToolResult ok(String message) {
        return new ToolDefinition.ToolResult(true, message);
    }

    private static ToolDefinition.ToolResult fail(String message) {
        return new ToolDefinition.ToolResult(false, message);
    }

    private static BlockPos blockPos(JsonObject args) {
        return new BlockPos(requiredInt(args, "x"), requiredInt(args, "y"), requiredInt(args, "z"));
    }

    private static int requiredInt(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsInt();
    }

    private static int intWithAlias(JsonObject args, String primary, String alias) {
        if (args.has(primary) && args.get(primary).isJsonPrimitive()) {
            return args.get(primary).getAsInt();
        }
        if (args.has(alias) && args.get(alias).isJsonPrimitive()) {
            return args.get(alias).getAsInt();
        }
        throw new IllegalArgumentException("missing_or_bad_arg: " + primary);
    }

    private static String requiredString(JsonObject args, String name) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing_or_bad_arg: " + name);
        }
        return args.get(name).getAsString();
    }

    private static int optionalInt(JsonObject args, String name, int defaultValue) {
        if (!args.has(name) || !args.get(name).isJsonPrimitive()) {
            return defaultValue;
        }
        return args.get(name).getAsInt();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static JsonObject xyzSchema() {
        return objectSchema()
                .property("x", integerSchema("block x"))
                .property("y", integerSchema("block y"))
                .property("z", integerSchema("block z"))
                .required("x")
                .required("y")
                .required("z")
                .build();
    }

    private static JsonObject stringSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("description", description);
        return schema;
    }

    private static JsonObject integerSchema(String description, int min, int max) {
        JsonObject schema = integerSchema(description);
        schema.addProperty("minimum", min);
        schema.addProperty("maximum", max);
        return schema;
    }

    private static ObjectSchemaBuilder objectSchema() {
        return new ObjectSchemaBuilder();
    }

    private static final class ObjectSchemaBuilder {
        private final JsonObject root = new JsonObject();
        private final JsonObject properties = new JsonObject();
        private final com.google.gson.JsonArray required = new com.google.gson.JsonArray();

        private ObjectSchemaBuilder() {
            root.addProperty("type", "object");
            root.add("properties", properties);
            root.add("required", required);
        }

        private ObjectSchemaBuilder property(String name, JsonObject schema) {
            properties.add(name, schema);
            return this;
        }

        private ObjectSchemaBuilder required(String name) {
            required.add(name);
            return this;
        }

        private JsonObject build() {
            return root;
        }
    }
}
