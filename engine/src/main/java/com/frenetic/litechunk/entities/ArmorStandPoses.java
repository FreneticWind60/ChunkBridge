package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.FloatTag;

/**
 * The thirteen poses a Bedrock armor stand can hold, and the Java rotations each one stands for.
 * <p>
 * Why a table at all: the two editions model the same feature in opposite directions. Java gives every armor stand six
 * independently rotated parts ({@code Pose:{Body,Head,LeftArm,LeftLeg,RightArm,RightLeg}}, three degrees each), while
 * Bedrock gives it a single integer, {@code Pose:{PoseIndex, LastSignal}}, and lets the client pick one of thirteen
 * baked animations. There is no way to carry an arbitrary Java pose across, so the closest of the thirteen is used -
 * the same thing the game itself does when a player cycles the pose button.
 * <p>
 * The order below is not a guess. It is the order of the branches in Mojang's own
 * {@code animation_controllers/armor_stand.animation_controllers.json}, which is what the client switches on:
 * {@code variable.armor_stand.pose_index == N}. The rotations are the Java equivalents published by the
 * Bedrock-matching "Armor Stand Poses" data pack, converted from the same {@code armor_stand.animation.json}.
 * <p>
 * Distances are computed in degrees after wrapping to (-180, 180], so a part at 350 and a part at -10 are the same
 * pose rather than 360 degrees apart - which is exactly how saves written by different tools disagree.
 */
final class ArmorStandPoses {

    /** Identifier of the only entity this table is about. */
    static final String ARMOR_STAND = "minecraft:armor_stand";

    /** Java's part names, in the order the table stores them. */
    private static final String[] BONES = {"Body", "Head", "LeftArm", "LeftLeg", "RightArm", "RightLeg"};

    /** How many poses Bedrock has; valid indices are {@code 0 .. COUNT-1}. */
    static final int COUNT = 13;

    /**
     * Bedrock pose index to Java rotations, in degrees.
     * <p>
     * Row order matches {@code PoseIndex}: 0 default, 1 none, 2 solemn, 3 athena, 4 brandish, 5 honor, 6 entertain,
     * 7 salute, 8 riposte, 9 zombie, 10 can can A, 11 can can B, 12 hero.
     */
    private static final float[][][] POSES = {
            // 0 default_pose
            {{0, 0, 0}, {0, 0, 0}, {-10, 0, -10}, {-1, 0, -1}, {-15, 0, 10}, {1, 0, 1}},
            // 1 no_pose
            {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}},
            // 2 solemn_pose
            {{0, 0, 2}, {15, 0, 0}, {-30, 15, 15}, {-1, 0, -1}, {-60, -20, -10}, {1, 0, 1}},
            // 3 athena_pose
            {{0, 0, 2}, {-5, 0, 0}, {10, 0, -5}, {-3, -3, -3}, {-60, 20, -10}, {3, 3, 3}},
            // 4 brandish_pose
            {{0, 0, -2}, {-15, 0, 0}, {20, 0, -10}, {5, -3, -3}, {-110, 50, 0}, {-5, 3, 3}},
            // 5 honor_pose
            {{0, 0, 0}, {-15, 0, 0}, {-110, 35, 0}, {5, -3, -3}, {-110, -35, 0}, {-5, 3, 3}},
            // 6 entertain_pose
            {{0, 0, 0}, {-15, 0, 0}, {-110, -35, 0}, {5, -3, -3}, {-110, 35, 0}, {-5, 3, 3}},
            // 7 salute_pose
            {{0, 0, 0}, {0, 0, 0}, {10, 0, -5}, {-1, 0, -1}, {-70, -40, 0}, {1, 0, 1}},
            // 8 riposte_pose
            {{0, 0, 0}, {16, 20, 0}, {4, 8, 237}, {-14, -18, -16}, {246, 0, 89}, {8, 20, 4}},
            // 9 zombie_pose
            {{0, 0, 0}, {-10, 0, -5}, {-105, 0, 0}, {7, 0, 0}, {-100, 0, 0}, {-46, 0, 0}},
            // 10 cancan_a_pose
            {{0, 22, 0}, {-5, 18, 0}, {8, 0, -114}, {-111, 55, 0}, {0, 84, 111}, {0, 23, -13}},
            // 11 cancan_b_pose
            {{0, -18, 0}, {-10, -20, 0}, {0, 0, -112}, {0, 0, 13}, {8, 90, 111}, {-119, -42, 0}},
            // 12 hero_pose
            {{0, 8, 0}, {-4, 67, 0}, {16, 32, -8}, {0, -75, -8}, {-99, 63, 0}, {4, 63, 8}},
    };

    private ArmorStandPoses() {
    }

    // ---------------------------------------------------------------------------------------------
    // Java -> Bedrock
    // ---------------------------------------------------------------------------------------------

    /**
     * Write the Bedrock pose of one armor stand, if the Java entity has one.
     * <p>
     * A Java armor stand always carries {@code Pose} after 1.9, but a save edited by hand or produced by an older tool
     * may not: the field is then left alone, so the actor keeps Bedrock's own default rather than being given a pose
     * the world never asked for.
     *
     * @param actor      the Bedrock actor being built.
     * @param javaEntity the Java entity it came from.
     */
    static void toBedrock(CompoundTag actor, CompoundTag javaEntity) {
        Tag<?> raw = javaEntity.get("Pose");
        if (!(raw instanceof CompoundTag pose)) {
            return;
        }
        CompoundTag written = new CompoundTag(2);
        written.put("PoseIndex", nearest(pose));
        // Same field the game writes when a redstone signal last changed the pose; zero means "never driven".
        written.put("LastSignal", 0);
        actor.put("Pose", written);
    }

    /**
     * @param javaPose a Java {@code Pose} compound, possibly missing parts.
     * @return the index of the closest Bedrock pose.
     */
    static int nearest(CompoundTag javaPose) {
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < POSES.length; index++) {
            long distance = distance(javaPose, POSES[index]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
            }
        }
        return best;
    }

    /** Squared angular distance over all eighteen numbers, after wrapping each to (-180, 180]. */
    private static long distance(CompoundTag javaPose, float[][] candidate) {
        long total = 0L;
        for (int bone = 0; bone < BONES.length; bone++) {
            float[] actual = readBone(javaPose, BONES[bone]);
            for (int axis = 0; axis < 3; axis++) {
                float delta = wrap(actual[axis]) - wrap(candidate[bone][axis]);
                total += (long) (delta * delta);
            }
        }
        return total;
    }

    // ---------------------------------------------------------------------------------------------
    // Bedrock -> Java
    // ---------------------------------------------------------------------------------------------

    /**
     * Write the Java {@code Pose} compound for one Bedrock armor stand.
     * <p>
     * Bedrock only stores the index, so the rotations are the table's. An unknown index is ignored rather than
     * clamped: a world carrying pose 20 was written by something that does not follow the format, and inventing a
     * pose for it would hide that.
     *
     * @param javaEntity the Java entity being built.
     * @param actor      the Bedrock actor it came from.
     */
    static void toJava(CompoundTag javaEntity, CompoundTag actor) {
        // Bedrock armor stands always have arms and have no flag for it; Java hides them unless ShowArms is set, so a
        // plain conversion would keep the pose but lose both arms. Setting it unconditionally is the closer match:
        // the arrow only ever points that way (a Java stand with hidden arms becomes a Bedrock one with arms and can
        // never come back as hidden, because Bedrock has nowhere to record the choice).
        javaEntity.put("ShowArms", (byte) 1);

        Tag<?> raw = actor.get("Pose");
        if (!(raw instanceof CompoundTag pose)) {
            return;
        }
        Object index = pose.contains("PoseIndex") ? pose.get("PoseIndex").getBoxedValue() : null;
        if (!(index instanceof Number number)) {
            return;
        }
        int value = number.intValue();
        if (value < 0 || value >= COUNT) {
            return;
        }
        CompoundTag javaPose = new CompoundTag(BONES.length);
        for (int bone = 0; bone < BONES.length; bone++) {
            ListTag<FloatTag, Float> rotation = new ListTag<>(TagType.FLOAT, 3);
            for (int axis = 0; axis < 3; axis++) {
                rotation.add(new FloatTag(POSES[value][bone][axis]));
            }
            javaPose.put(BONES[bone], rotation);
        }
        javaEntity.put("Pose", javaPose);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Read one part, treating anything unreadable as zero.
     * <p>
     * A missing part is not an error: Java's own default for a part is the zero rotation, and half-written saves in
     * the wild routinely carry three of the six.
     */
    private static float[] readBone(CompoundTag javaPose, String bone) {
        float[] rotation = new float[3];
        Tag<?> raw = javaPose.get(bone);
        if (!(raw instanceof ListTag<?, ?> list)) {
            return rotation;
        }
        for (int axis = 0; axis < 3 && axis < list.size(); axis++) {
            Object value = list.get(axis).getBoxedValue();
            if (value instanceof Number number) {
                rotation[axis] = number.floatValue();
            }
        }
        return rotation;
    }

    /** Bring a rotation into (-180, 180], so 360 and 0 - and 237 and -123 - measure as the same angle. */
    private static float wrap(float degrees) {
        float wrapped = degrees % 360F;
        if (wrapped > 180F) {
            wrapped -= 360F;
        } else if (wrapped <= -180F) {
            wrapped += 360F;
        }
        return wrapped;
    }
}
