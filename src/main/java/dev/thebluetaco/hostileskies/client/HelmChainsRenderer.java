package dev.thebluetaco.hostileskies.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.thebluetaco.hostileskies.entity.HelmChainsEntity;
import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class HelmChainsRenderer extends EntityRenderer<HelmChainsEntity> {

    private static final ResourceLocation CHAIN_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(HostileSkies.MODID, "textures/entity/captain_chains.png");
    private static final ResourceLocation MODEL_JSON =
            ResourceLocation.fromNamespaceAndPath(HostileSkies.MODID, "models/entity/helm_chains.json");

    private static final float TEX_W = 16.0f, TEX_H = 16.0f;
    private static final int CHAIN_ELEMENT_START = 0;

    /** Extra Y rotation (degrees) to align the chain model with the wheel face. */
    private static final float MODEL_YAW_OFFSET = 0f;

    private static List<Quad> chainQuads = null;

    public HelmChainsRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

    @Override
    public ResourceLocation getTextureLocation(HelmChainsEntity entity) { return CHAIN_TEXTURE; }

    @Override
    public void render(HelmChainsEntity entity, float entityYaw, float partialTick,
                       PoseStack stack, MultiBufferSource bufferSource, int packedLight) {
        if (chainQuads == null) {
            chainQuads = loadChainModel();
            HostileSkies.LOGGER.info("Loaded helm chain model: {} quads", chainQuads.size());
        }
        if (chainQuads.isEmpty()) return;

        stack.pushPose();

        // Sub-level orientation (ship rotation)
        Vec3 plotPos = ((EntityStickExtension) entity).sable$getPlotPosition();
        if (plotPos != null) {
            SubLevel sl = Sable.HELPER.getContaining(entity.level(), plotPos);
            if (sl != null) {
                Quaterniondc q = sl.logicalPose().orientation();
                stack.mulPose(new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()));
            }
        }

        // Wheel facing direction
        float wheelYaw = entity.getWheelYaw();
        boolean onFloor = entity.isWheelOnFloor();

        stack.mulPose(Axis.YP.rotationDegrees(wheelYaw + MODEL_YAW_OFFSET));

        if (!onFloor) {
            // Ceiling-mounted: flip Y around the model's center (Y=0.5)
            stack.translate(0, 0.5, 0);
            stack.scale(1f, -1f, 1f);
            stack.translate(0, -0.5, 0);
        }

        // Scale block pixels to world units
        stack.scale(1f / 16f, 1f / 16f, 1f / 16f);
        stack.translate(-8.0, 0.0, -8.0);

        PoseStack.Pose poseEntry = stack.last();
        Matrix4f pose = poseEntry.pose();

        // Opaque self-lit chains + enchantment glint (same technique as enchanted armor):
        // entityCutoutNoCull for the base, entityGlintDirect for the shimmer,
        // VertexMultiConsumer writes vertex data to both buffers simultaneously.
        VertexConsumer base = bufferSource.getBuffer(
                RenderType.entityCutoutNoCull(CHAIN_TEXTURE));
        VertexConsumer glint = bufferSource.getBuffer(
                RenderType.entityGlintDirect());
        VertexConsumer combined = com.mojang.blaze3d.vertex.VertexMultiConsumer
                .create(glint, base);
        renderAllQuads(combined, pose, poseEntry, 0xF000F0, 255, 255, 255, 255);

        stack.popPose();
    }

    private void renderAllQuads(VertexConsumer consumer, Matrix4f pose, PoseStack.Pose poseEntry,
                                int packedLight, int r, int g, int b, int a) {
        for (Quad quad : chainQuads) {
            for (int v = 0; v < 4; v++) {
                consumer.addVertex(pose, quad.x[v], quad.y[v], quad.z[v])
                        .setColor(r, g, b, a)
                        .setUv(quad.u[v], quad.v[v])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(packedLight)
                        .setNormal(poseEntry, quad.nx, quad.ny, quad.nz);
            }
        }
    }

    // Model loading

    private static List<Quad> loadChainModel() {
        List<Quad> quads = new ArrayList<>();
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(MODEL_JSON);
            if (res.isEmpty()) { HostileSkies.LOGGER.error("Chain model not found: {}", MODEL_JSON); return quads; }
            JsonObject root;
            try (var reader = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonArray elements = root.getAsJsonArray("elements");
            for (int i = CHAIN_ELEMENT_START; i < elements.size(); i++)
                parseElement(elements.get(i).getAsJsonObject(), quads);
        } catch (Exception e) { HostileSkies.LOGGER.error("Failed to load chain model", e); }
        return quads;
    }

    private static void parseElement(JsonObject elem, List<Quad> quads) {
        JsonArray from = elem.getAsJsonArray("from"), to = elem.getAsJsonArray("to");
        float x0 = from.get(0).getAsFloat(), y0 = from.get(1).getAsFloat(), z0 = from.get(2).getAsFloat();
        float x1 = to.get(0).getAsFloat(), y1 = to.get(1).getAsFloat(), z1 = to.get(2).getAsFloat();
        Matrix4f rotMat = elem.has("rotation") ? computeRotationMatrix(elem.getAsJsonObject("rotation")) : null;
        JsonObject faces = elem.getAsJsonObject("faces");
        for (var entry : faces.entrySet()) {
            JsonObject fd = entry.getValue().getAsJsonObject();
            JsonArray uv = fd.getAsJsonArray("uv");
            float u0 = uv.get(0).getAsFloat(), v0 = uv.get(1).getAsFloat();
            float u1 = uv.get(2).getAsFloat(), v1 = uv.get(3).getAsFloat();
            if (u0 == u1 && v0 == v1) continue;
            if (u0 == 0 && v0 == 0 && u1 == 0 && v1 == 0) continue;
            JsonElement tex = fd.get("texture");
            if (tex == null || !tex.getAsString().equals("#3")) continue;
            float[][] corners = getFaceCorners(entry.getKey(), x0, y0, z0, x1, y1, z1);
            if (corners == null) continue;
            if (rotMat != null) for (float[] c : corners) {
                Vector4f v4 = new Vector4f(c[0], c[1], c[2], 1f); rotMat.transform(v4);
                c[0] = v4.x; c[1] = v4.y; c[2] = v4.z;
            }
            float[] n = getFaceNormal(entry.getKey());
            quads.add(new Quad(
                new float[]{corners[0][0],corners[1][0],corners[2][0],corners[3][0]},
                new float[]{corners[0][1],corners[1][1],corners[2][1],corners[3][1]},
                new float[]{corners[0][2],corners[1][2],corners[2][2],corners[3][2]},
                new float[]{u0/TEX_W,u1/TEX_W,u1/TEX_W,u0/TEX_W},
                new float[]{v0/TEX_H,v0/TEX_H,v1/TEX_H,v1/TEX_H},
                n[0], n[1], n[2]));
        }
    }

    private static Matrix4f computeRotationMatrix(JsonObject rot) {
        JsonArray oa = rot.getAsJsonArray("origin");
        float ox = oa.get(0).getAsFloat(), oy = oa.get(1).getAsFloat(), oz = oa.get(2).getAsFloat();
        Matrix4f mat = new Matrix4f().identity().translate(ox, oy, oz);

        // Blockbench exports two rotation formats:
        //   Single-axis: {"axis":"y", "angle":22.5}  (vanilla, 22.5° increments)
        //   Euler xyz:   {"x":17.9, "y":82.5, "z":-7.3}  (arbitrary angles, takes priority)
        float rx = rot.has("x") ? rot.get("x").getAsFloat() : 0;
        float ry = rot.has("y") && rot.get("y").isJsonPrimitive() && rot.getAsJsonPrimitive("y").isNumber()
                ? rot.get("y").getAsFloat() : 0;
        float rz = rot.has("z") ? rot.get("z").getAsFloat() : 0;

        if (rx != 0 || ry != 0 || rz != 0) {
            // Multi-axis Euler (ZYX order)
            mat.rotateZ((float) Math.toRadians(rz));
            mat.rotateY((float) Math.toRadians(ry));
            mat.rotateX((float) Math.toRadians(rx));
        } else if (rot.has("axis") && rot.has("angle")) {
            float a = (float) Math.toRadians(rot.get("angle").getAsFloat());
            switch (rot.get("axis").getAsString()) {
                case "x" -> mat.rotateX(a);
                case "y" -> mat.rotateY(a);
                case "z" -> mat.rotateZ(a);
            }
        }

        return mat.translate(-ox, -oy, -oz);
    }

    private static float[][] getFaceCorners(String f, float x0, float y0, float z0, float x1, float y1, float z1) {
        return switch(f) {
            case "north"->new float[][]{{x1,y0,z0},{x0,y0,z0},{x0,y1,z0},{x1,y1,z0}};
            case "south"->new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}};
            case "east" ->new float[][]{{x1,y0,z1},{x1,y0,z0},{x1,y1,z0},{x1,y1,z1}};
            case "west" ->new float[][]{{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}};
            case "up"   ->new float[][]{{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}};
            case "down" ->new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y0,z0},{x0,y0,z0}};
            default->null;
        };
    }

    private static float[] getFaceNormal(String f) {
        return switch(f) {
            case "north"->new float[]{0,0,-1}; case "south"->new float[]{0,0,1};
            case "east"->new float[]{1,0,0}; case "west"->new float[]{-1,0,0};
            case "up"->new float[]{0,1,0}; case "down"->new float[]{0,-1,0};
            default->new float[]{0,1,0};
        };
    }

    record Quad(float[] x, float[] y, float[] z, float[] u, float[] v, float nx, float ny, float nz) {}
}
