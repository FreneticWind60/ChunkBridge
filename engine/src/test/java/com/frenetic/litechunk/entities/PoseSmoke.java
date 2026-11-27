package com.frenetic.litechunk.entities;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.primitive.DoubleTag;
import com.hivemc.chunker.nbt.tags.primitive.FloatTag;

/**
 * Desktop check for {@link ArmorStandPoses}.
 * <p>
 * The pose table cannot be verified against a real world: no world in the fixtures has armor stands in all thirteen
 * poses, and the mapping is a guess either way - the two editions do not share a representation. What can be checked
 * is that the table is coherent with itself and with the published Java rotations: every canonical pose maps to its
 * own index, a pose written in the other direction of rotation (350 for -10) is recognised as the same pose, an
 * unknown index does not invent a pose, and the Java -> Bedrock -> Java cycle lands back on the index it started on.
 * <p>
 * Usage: {@code gradlew :engine:poseSmoke}
 */
public final class PoseSmoke {

    private static int failures;

    private PoseSmoke() {
    }

    public static void main(String[] args) {
        // The Java rotations published for the poses the table describes, written out here on purpose: if someone
        // edits the table, these numbers fail instead of quietly following the edit.
        check("default", 0, pose(
                new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{-10, 0, -10},
                new float[]{-1, 0, -1}, new float[]{-15, 0, 10}, new float[]{1, 0, 1}));
        check("no_pose", 1, pose(
                new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{0, 0, 0},
                new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{0, 0, 0}));
        check("solemn", 2, pose(
                new float[]{0, 0, 2}, new float[]{15, 0, 0}, new float[]{-30, 15, 15},
                new float[]{-1, 0, -1}, new float[]{-60, -20, -10}, new float[]{1, 0, 1}));
        check("honor", 5, pose(
                new float[]{0, 0, 0}, new float[]{-15, 0, 0}, new float[]{-110, 35, 0},
                new float[]{5, -3, -3}, new float[]{-110, -35, 0}, new float[]{-5, 3, 3}));
        check("riposte", 8, pose(
                new float[]{0, 0, 0}, new float[]{16, 20, 0}, new float[]{4, 8, 237},
                new float[]{-14, -18, -16}, new float[]{246, 0, 89}, new float[]{8, 20, 4}));
        check("hero", 12, pose(
                new float[]{0, 8, 0}, new float[]{-4, 67, 0}, new float[]{16, 32, -8},
                new float[]{0, -75, -8}, new float[]{-99, 63, 0}, new float[]{4, 63, 8}));

        // 350 degrees is the same rotation as -10, which is how tools that write unsigned angles disagree with us.
        check("wrapped", 0, pose(
                new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{350, 0, 350},
                new float[]{359, 0, 359}, new float[]{345, 0, 10}, new float[]{1, 0, 1}));

        // A pose that is close to nothing in particular must still land inside the valid range.
        int arbitrary = ArmorStandPoses.nearest(pose(
                new float[]{3, 4, 5}, new float[]{-7, 8, 9}, new float[]{20, 30, 40},
                new float[]{-50, 60, 70}, new float[]{80, -90, 100}, new float[]{11, 12, 13}));
        if (arbitrary < 0 || arbitrary >= ArmorStandPoses.COUNT) {
            fail("arbitrary pose index out of range: " + arbitrary);
        } else {
            pass("arbitrary pose index " + arbitrary + " is in range");
        }

        // --- table cycle: every index must survive Java -> Bedrock -> Java -------------------------
        for (int index = 0; index < ArmorStandPoses.COUNT; index++) {
            CompoundTag actor = new CompoundTag(4);
            actor.put("identifier", ArmorStandPoses.ARMOR_STAND);
            CompoundTag bedrockPose = new CompoundTag(2);
            bedrockPose.put("PoseIndex", index);
            bedrockPose.put("LastSignal", 0);
            actor.put("Pose", bedrockPose);

            CompoundTag javaEntity = new CompoundTag(4);
            javaEntity.put("identifier", "minecraft:armor_stand");
            ArmorStandPoses.toJava(javaEntity, actor);

            // Arms are not part of the pose table: Bedrock armor stands always show them, Java hides them unless the
            // entity says otherwise, so the flag has to be set here or the stand arrives armless (the pose still
            // looks right from the legs, which is exactly how this bug hid).
            Object arms = javaEntity.contains("ShowArms")
                    ? javaEntity.get("ShowArms").getBoxedValue()
                    : null;
            if (arms instanceof Number flag && flag.intValue() == 1) {
                pass("index " + index + " keeps the arms visible on the Java side");
            } else {
                fail("index " + index + " wrote ShowArms = " + arms);
            }

            Tag<?> written = javaEntity.get("Pose");
            if (!(written instanceof CompoundTag)) {
                fail("index " + index + " wrote no Java pose");
                continue;
            }
            int back = ArmorStandPoses.nearest((CompoundTag) written);
            if (back == index) {
                pass("index " + index + " survives the Java -> Bedrock -> Java cycle");
            } else {
                fail("index " + index + " came back as " + back);
            }
        }

        // --- edges --------------------------------------------------------------------------------
        CompoundTag outOfRange = new CompoundTag(4);
        outOfRange.put("identifier", ArmorStandPoses.ARMOR_STAND);
        CompoundTag badPose = new CompoundTag(2);
        badPose.put("PoseIndex", ArmorStandPoses.COUNT);
        outOfRange.put("Pose", badPose);
        CompoundTag untouched = new CompoundTag(4);
        ArmorStandPoses.toJava(untouched, outOfRange);
        if (untouched.contains("Pose")) {
            fail("an out of range PoseIndex must not be turned into a Java pose");
        } else {
            pass("out of range PoseIndex is ignored");
        }

        CompoundTag noJavaPose = new CompoundTag(4);
        CompoundTag actorWithoutPose = new CompoundTag(4);
        actorWithoutPose.put("identifier", ArmorStandPoses.ARMOR_STAND);
        ArmorStandPoses.toBedrock(actorWithoutPose, noJavaPose);
        if (actorWithoutPose.contains("Pose")) {
            fail("a Java entity without Pose must not gain one");
        } else {
            pass("a Java entity without Pose leaves the actor alone");
        }

        // A Java pose with only some parts written still resolves: the missing parts are Java's defaults.
        CompoundTag partial = new CompoundTag(4);
        CompoundTag partialPose = new CompoundTag(2);
        partialPose.put("Head", rotation(16f, 20f, 0f));
        partial.put("Pose", partialPose);
        int partialIndex = ArmorStandPoses.nearest(partialPose);
        if (partialIndex >= 0 && partialIndex < ArmorStandPoses.COUNT) {
            pass("a partially written Java pose resolves to " + partialIndex);
        } else {
            fail("a partially written Java pose resolved to " + partialIndex);
        }

        // --- the real class the conversion runs, not just the table -------------------------------
        CompoundTag javaStand = new CompoundTag(6);
        javaStand.put("id", "minecraft:armor_stand");
        javaStand.put("Pose", pose(
                new float[]{0, 8, 0}, new float[]{-4, 67, 0}, new float[]{16, 32, -8},
                new float[]{0, -75, -8}, new float[]{-99, 63, 0}, new float[]{4, 63, 8}));
        javaStand.put("Pos", doubleList(0.5, 64.0, 0.5));
        CompoundTag actor = BedrockActorBuilder.build(javaStand, 1L);
        Tag<?> actorPose = actor == null ? null : actor.get("Pose");
        if (actorPose instanceof CompoundTag compound && compound.getInt("PoseIndex", -1) == 12) {
            pass("BedrockActorBuilder wrote PoseIndex 12 for the hero pose");
        } else {
            fail("BedrockActorBuilder did not write the hero PoseIndex: " + actorPose);
        }

        CompoundTag flatStand = new CompoundTag(4);
        flatStand.put("id", "minecraft:armor_stand");
        flatStand.put("Pos", doubleList(0.5, 64.0, 0.5));
        CompoundTag flatActor = BedrockActorBuilder.build(flatStand, 2L);
        if (flatActor != null && !flatActor.contains("Pose")) {
            pass("an armor stand without a Java Pose reaches Bedrock with no Pose tag");
        } else {
            fail("an armor stand without a Java Pose gained one");
        }

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " pose check(s) failed");
            System.exit(1);
        }
        System.out.println("all pose checks passed");
    }

    // ---------------------------------------------------------------------------------------------

    private static void check(String name, int expected, CompoundTag javaPose) {
        int actual = ArmorStandPoses.nearest(javaPose);
        if (actual == expected) {
            pass(name + " -> " + actual);
        } else {
            fail(name + ": expected " + expected + ", got " + actual);
        }
    }

    /** Builds a Java {@code Pose} compound in the bone order Body, Head, LeftArm, LeftLeg, RightArm, RightLeg. */
    private static CompoundTag pose(float[]... bones) {
        CompoundTag pose = new CompoundTag(bones.length);
        String[] names = {"Body", "Head", "LeftArm", "LeftLeg", "RightArm", "RightLeg"};
        for (int i = 0; i < names.length && i < bones.length; i++) {
            pose.put(names[i], rotation(bones[i][0], bones[i][1], bones[i][2]));
        }
        return pose;
    }

    private static ListTag<FloatTag, Float> rotation(float x, float y, float z) {
        ListTag<FloatTag, Float> list = new ListTag<>(TagType.FLOAT, 3);
        list.add(new FloatTag(x));
        list.add(new FloatTag(y));
        list.add(new FloatTag(z));
        return list;
    }

    private static ListTag<DoubleTag, Double> doubleList(double x, double y, double z) {
        ListTag<DoubleTag, Double> list = new ListTag<>(TagType.DOUBLE, 3);
        list.add(new DoubleTag(x));
        list.add(new DoubleTag(y));
        list.add(new DoubleTag(z));
        return list;
    }

    private static void pass(String message) {
        System.out.println("ok   " + message);
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }
}
