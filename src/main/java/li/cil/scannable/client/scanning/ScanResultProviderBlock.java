package li.cil.scannable.client.scanning;

import com.google.common.base.Strings;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import li.cil.scannable.api.API;
import li.cil.scannable.api.prefab.AbstractScanResultProvider;
import li.cil.scannable.api.scanning.ScanFilterBlock;
import li.cil.scannable.api.scanning.ScanResult;
import li.cil.scannable.api.scanning.ScannerModule;
import li.cil.scannable.api.scanning.ScannerModuleBlock;
import li.cil.scannable.client.shader.ScanResultShader;
import li.cil.scannable.common.capabilities.CapabilityScannerModule;
import li.cil.scannable.common.config.Settings;
import li.cil.scannable.common.scanning.filter.ScanFilterIgnoredBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ITag;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.palette.PalettedContainer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.util.LazyOptional;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

@OnlyIn(Dist.CLIENT)
public final class ScanResultProviderBlock extends AbstractScanResultProvider {
    public static final ScanResultProviderBlock INSTANCE = new ScanResultProviderBlock();

    // --------------------------------------------------------------------- //

    // Sanity performance check. Maybe some day I'll do some research on how to
    // do the clustering more efficiently, but for now this is good enough. We
    // really only need this when scanning for stupid stuff like stone.
    private static final int MAX_RESULTS_PER_BLOCK = 8192;
    private static final int DEFAULT_COLOR = 0x4466CC;

    private final List<ScanFilterLayer> scanFilterLayers = new ArrayList<>();
    private final List<ChunkSectionPos> pendingChunkSections = new ArrayList<>();
    private int currentChunkSection, chunkSectionsPerTick;
    private final Map<Block, Map<BlockPos, BlockScanResult>> resultClusters = new HashMap<>();
    private final List<BlockScanResult> results = new ArrayList<>();

    // --------------------------------------------------------------------- //
    // ScanResultProvider

    @Override
    public void initialize(final PlayerEntity player, final Collection<ItemStack> modules, final Vector3d center, final float radius, final int scanTicks) {
        super.initialize(player, modules, center, radius, scanTicks);

        scanFilterLayers.clear();

        final IntObjectMap<List<ScanFilterBlock>> filterByRadius = new IntObjectHashMap<>();
        for (final ItemStack module : modules) {
            final LazyOptional<ScannerModule> capability = module.getCapability(CapabilityScannerModule.SCANNER_MODULE_CAPABILITY);
            capability
                    .filter(c -> c instanceof ScannerModuleBlock)
                    .ifPresent(c -> {
                        final ScannerModuleBlock m = (ScannerModuleBlock) c;
                        final Optional<ScanFilterBlock> filter = m.getFilter(module);
                        filter.ifPresent(f -> {
                            final int localRadius = (int) Math.ceil(m.adjustLocalRange(this.radius));
                            filterByRadius.computeIfAbsent(localRadius, r -> new ArrayList<>()).add(f);
                        });
                    });
        }

        final IntList scanFilterKeys = new IntArrayList();
        scanFilterKeys.addAll(filterByRadius.keySet());
        scanFilterKeys.sort((a, b) -> -Integer.compare(a, b));

        if (scanFilterKeys.size() > 0) {
            this.radius = scanFilterKeys.getInt(0);
            for (final int r : scanFilterKeys) {
                scanFilterLayers.add(new ScanFilterLayer(r, filterByRadius.get(r)));
            }

            final BlockPos minBlockPos = new BlockPos(center).offset(-this.radius, -this.radius, -this.radius);
            final BlockPos maxBlockPos = new BlockPos(center).offset(this.radius, this.radius, this.radius);
            final ChunkPos minChunkPos = new ChunkPos(minBlockPos);
            final ChunkPos maxChunkPos = new ChunkPos(maxBlockPos);
            final int minChunkSectionIndex = Math.max(minBlockPos.getY() >> 4, 0);
            final int maxChunkSectionIndex = Math.min(maxBlockPos.getY() >> 4, 15);

            for (int chunkSectionIndex = minChunkSectionIndex; chunkSectionIndex <= maxChunkSectionIndex; chunkSectionIndex++) {
                for (int chunkZ = minChunkPos.z; chunkZ <= maxChunkPos.z; chunkZ++) {
                    for (int chunkX = minChunkPos.x; chunkX <= maxChunkPos.x; chunkX++) {
                        final double dx = Math.min(Math.abs((chunkX << 4) - center.x), Math.abs((chunkX << 4) + 15 - center.x));
                        final double dz = Math.min(Math.abs((chunkZ << 4) - center.z), Math.abs((chunkZ << 4) + 15 - center.z));
                        final double dy = Math.min(Math.abs((chunkSectionIndex << 4) - center.y), Math.abs((chunkSectionIndex << 4) + 15 - center.y));
                        final double squareDistToCenter = dx * dx + dy * dy + dz * dz;

                        if (squareDistToCenter > radius * radius) {
                            continue;
                        }

                        pendingChunkSections.add(new ChunkSectionPos(chunkX, chunkZ, chunkSectionIndex, squareDistToCenter));
                    }
                }
            }

            pendingChunkSections.sort(Comparator.comparingDouble(p -> p.squareDistToCenter));

            chunkSectionsPerTick = MathHelper.ceil(pendingChunkSections.size() / (float) scanTicks);
            this.currentChunkSection = 0;
        }
    }

    @Override
    public void computeScanResults() {
        final World world = player.getCommandSenderWorld();
        for (int i = 0; i < chunkSectionsPerTick; i++) {
            if (currentChunkSection >= pendingChunkSections.size()) {
                return;
            }

            final ChunkSectionPos chunkSectionPos = pendingChunkSections.get(currentChunkSection);
            currentChunkSection++;

            final int chunkX = chunkSectionPos.chunkX;
            final int chunkZ = chunkSectionPos.chunkZ;
            final int chunkSectionIndex = chunkSectionPos.chunkSectionIndex;

            final IChunk chunk = world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }

            final ChunkSection[] sections = chunk.getSections();
            assert sections.length == 16;

            final ChunkSection section = sections[chunkSectionIndex];
            if (section == null || section.isEmpty()) {
                continue;
            }

            final PalettedContainer<BlockState> palette = section.getStates();
            final BlockPos origin = chunk.getPos().getWorldPosition().offset(0, section.bottomBlockY(), 0);
            final int originX = origin.getX();
            final int originY = origin.getY();
            final int originZ = origin.getZ();
            for (int index = 0; index < 16 * 16 * 16; index++) {
                final BlockState state = palette.get(index);
                final Block block = state.getBlock();
                final Map<BlockPos, BlockScanResult> clusters = resultClusters.computeIfAbsent(block, b -> new HashMap<>());
                if (clusters.size() > MAX_RESULTS_PER_BLOCK) {
                    continue;
                }

                if (ScanFilterIgnoredBlocks.shouldIgnore(state)) {
                    continue;
                }

                final int x = index & 0xf;
                final int z = (index >> 4) & 0xf;
                final int y = (index >> 8) & 0xf;

                final int globalX = originX + x;
                final int globalY = originY + y;
                final int globalZ = originZ + z;

                final double squaredDistance = center.distanceToSqr(globalX + 0.5, globalY + 0.5, globalZ + 0.5);

                outer:
                for (final ScanFilterLayer layer : scanFilterLayers) {
                    if (squaredDistance > layer.radius * layer.radius) {
                        break; // Filters radii only get smaller in the sorted filter list.
                    }

                    for (final ScanFilterBlock filter : layer.filters) {
                        if (filter.matches(state)) {
                            final BlockPos pos = new BlockPos(globalX, globalY, globalZ);
                            if (!tryAddToCluster(clusters, pos)) {
                                final BlockScanResult result = new BlockScanResult(state.getBlock(), pos);
                                clusters.put(pos, result);
                                results.add(result);
                            }
                            break outer;
                        }
                    }
                }
            }
        }
    }

    @Override
    public void collectScanResults(final IBlockReader world, final Consumer<ScanResult> callback) {
        for (final BlockScanResult result : results) {
            if (result.isRoot()) {
                result.bake(world);
                callback.accept(result);
            }
        }
    }

    @Override
    public void render(final IRenderTypeBuffer renderTypeBuffer, final MatrixStack matrixStack, final Matrix4f projectionMatrix, final ActiveRenderInfo renderInfo, final float partialTicks, final List<ScanResult> results) {
        // Re-render hands into depth buffer to avoid rendering overlay on top of player hands.
        if (Minecraft.getInstance().gameRenderer.renderHand) {
            RenderSystem.colorMask(false, false, false, false);
            matrixStack.pushPose();
            Minecraft.getInstance().gameRenderer.renderItemInHand(matrixStack, renderInfo, partialTicks);
            matrixStack.popPose();
            RenderSystem.colorMask(true, true, true, true);
        }

        ScanResultShader.setProjectionMatrix(projectionMatrix);
        ScanResultShader.setViewMatrix(matrixStack.last().pose());

        final RenderType renderType = getBlockScanResultRenderLayer();
        renderType.setupRenderState();
        for (final ScanResult result : results) {
            final BlockScanResult blockResult = (BlockScanResult) result;
            final VertexBuffer vbo = blockResult.vbo;
            vbo.bind();
            DefaultVertexFormats.POSITION_COLOR_TEX.setupBufferState(0);
            vbo.draw(matrixStack.last().pose(), GL11.GL_QUADS);
            VertexBuffer.unbind();
            DefaultVertexFormats.POSITION_COLOR_TEX.clearBufferState();
        }
        renderType.clearRenderState();

        final Vector3d lookVec = new Vector3d(renderInfo.getLookVector());
        final Vector3d viewerEyes = renderInfo.getPosition();
        final float yaw = renderInfo.getYRot();
        final float pitch = renderInfo.getXRot();
        final boolean showDistance = renderInfo.getEntity().isShiftKeyDown();

        // Order results by distance to center of screen (deviation from look
        // vector) so that labels we're looking at are in front of others.
        results.sort(Comparator.comparing(result -> {
            final BlockScanResult blockResult = (BlockScanResult) result;
            final Vector3d resultPos = blockResult.getPosition();
            final Vector3d toResult = resultPos.subtract(viewerEyes);
            return lookVec.dot(toResult.normalize());
        }));

        for (final ScanResult result : results) {
            final BlockScanResult blockResult = (BlockScanResult) result;

            final Vector3d resultPos = result.getPosition();
            final Vector3d toResult = resultPos.subtract(viewerEyes);
            final float lookDirDot = (float) lookVec.dot(toResult.normalize());

            final Block block = blockResult.block;
            final ITextComponent label = block.getName();
            if (lookDirDot > 0.98f && !Strings.isNullOrEmpty(label.getString())) {
                final float distance = showDistance ? (float) resultPos.subtract(viewerEyes).length() : 0f;
                renderIconLabel(renderTypeBuffer, matrixStack, yaw, pitch, lookVec, viewerEyes, distance, resultPos, API.ICON_INFO, label);
            }
        }
    }

    @Override
    public void reset() {
        super.reset();
        scanFilterLayers.clear();
        currentChunkSection = chunkSectionsPerTick = 0;
        pendingChunkSections.clear();
        resultClusters.clear();
        results.clear();
    }

    // --------------------------------------------------------------------- //

    private static RenderType getBlockScanResultRenderLayer() {
        return RenderType.create("scan_result",
                DefaultVertexFormats.POSITION_COLOR_TEX,
                GL11.GL_QUADS,
                65536,
                RenderType.State.builder()
                        .setTransparencyState(RenderState.LIGHTNING_TRANSPARENCY)
                        .setWriteMaskState(RenderState.COLOR_WRITE)
                        .setCullState(RenderState.NO_CULL)
                        .setTexturingState(new RenderState.TexturingState("shader",
                                ScanResultShader.INSTANCE::bind, ScanResultShader.INSTANCE::unbind))
                        .createCompositeState(false));
    }

    private boolean tryAddToCluster(final Map<BlockPos, BlockScanResult> clusters, final BlockPos pos) {
        BlockScanResult root = null;
        root = tryAddToCluster(clusters, pos, pos.east(), root);
        root = tryAddToCluster(clusters, pos, pos.west(), root);
        root = tryAddToCluster(clusters, pos, pos.north(), root);
        root = tryAddToCluster(clusters, pos, pos.south(), root);
        root = tryAddToCluster(clusters, pos, pos.above(), root);
        root = tryAddToCluster(clusters, pos, pos.below(), root);
        return root != null;
    }

    @Nullable
    private BlockScanResult tryAddToCluster(final Map<BlockPos, BlockScanResult> clusters, final BlockPos pos, final BlockPos clusterPos, @Nullable BlockScanResult root) {
        final BlockScanResult cluster = clusters.get(clusterPos);
        if (cluster == null) {
            return root;
        }

        if (root == null) {
            root = cluster.getRoot();
            root.add(pos);
            clusters.put(pos, root);
        } else {
            cluster.getRoot().setRoot(root);
        }

        return root;
    }

    private static final class ScanFilterLayer {
        public final int radius;
        public final List<ScanFilterBlock> filters;

        public ScanFilterLayer(final int radius, final List<ScanFilterBlock> filters) {
            this.radius = radius;
            this.filters = filters;
        }
    }

    private static final class ChunkSectionPos {
        public final int chunkX;
        public final int chunkZ;
        public final int chunkSectionIndex;
        public double squareDistToCenter;

        private ChunkSectionPos(final int chunkX, final int chunkZ, final int chunkSectionIndex, final double squareDistToCenter) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.chunkSectionIndex = chunkSectionIndex;
            this.squareDistToCenter = squareDistToCenter;
        }
    }

    // --------------------------------------------------------------------- //

    private static final class BlockScanResult implements ScanResult {
        private final Block block;
        private AxisAlignedBB bounds;
        @Nullable
        private BlockScanResult parent;
        private final Set<BlockPos> blocks;
        private int color;
        private VertexBuffer vbo;

        BlockScanResult(final Block block, final BlockPos pos) {
            this.block = block;
            bounds = new AxisAlignedBB(pos);
            blocks = new HashSet<>();
            blocks.add(pos);
        }

        void bake(final IBlockReader world) {
            final BlockState blockState = block.defaultBlockState();

            color = blockState.getMapColor(world, new BlockPos(bounds.getCenter())).col;
            if (color == 0) { // E.g. glass.
                color = DEFAULT_COLOR;
            }

            final FluidState fluidState = blockState.getFluidState();
            if (!fluidState.isEmpty()) {
                if (Settings.fluidColors.containsKey(fluidState.getType().getRegistryName())) {
                    color = Settings.fluidColors.getInt(fluidState.getType());
                } else {
                    Settings.fluidTagColors.forEach((k, v) -> {
                        final ITag<Fluid> tag = FluidTags.getAllTags().getTag(k);
                        if (tag != null && tag.contains(fluidState.getType())) {
                            color = v;
                        }
                    });
                }
            } else {
                if (Settings.blockColors.containsKey(blockState.getBlock().getRegistryName())) {
                    color = Settings.blockColors.getInt(blockState.getBlock());
                } else {
                    Settings.blockTagColors.forEach((k, v) -> {
                        final ITag<Block> tag = BlockTags.getAllTags().getTag(k);
                        if (tag != null && tag.contains(blockState.getBlock())) {
                            color = v;
                        }
                    });
                }
            }

            final Tessellator tessellator = Tessellator.getInstance();
            final BufferBuilder buffer = tessellator.getBuilder();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR_TEX);
            final MatrixStack matrixStack = new MatrixStack();
            render(buffer, matrixStack);
            buffer.end();
            vbo = new VertexBuffer(DefaultVertexFormats.POSITION_COLOR_TEX);
            vbo.upload(buffer);
        }

        boolean isRoot() {
            return parent == null;
        }

        BlockScanResult getRoot() {
            if (parent != null) {
                return parent.getRoot();
            }
            return this;
        }

        void setRoot(final BlockScanResult root) {
            if (root == this) {
                return;
            }

            assert parent == null;

            root.bounds = root.bounds.minmax(bounds);
            root.blocks.addAll(blocks);
            blocks.clear();
            parent = root;
        }

        void add(final BlockPos pos) {
            assert parent == null : "Trying to add to non-root node.";
            bounds = bounds.minmax(new AxisAlignedBB(pos));
            blocks.add(pos);
        }

        void render(final IVertexBuilder buffer, final MatrixStack matrixStack) {
            final Matrix4f matrix = matrixStack.last().pose();

            final float colorNormalizer = 1 / 255f;
            final float r = ((color >> 16) & 0xFF) * colorNormalizer;
            final float g = ((color >> 8) & 0xFF) * colorNormalizer;
            final float b = (color & 0xFF) * colorNormalizer;

            final float sizeUvX = (float) (1.0 / bounds.getXsize());
            final float sizeUvY = (float) (1.0 / bounds.getYsize());
            final float sizeUvZ = (float) (1.0 / bounds.getZsize());
            for (final BlockPos cell : blocks) {
                if (!blocks.contains(cell.offset(-1, 0, 0))) {
                    final float x = cell.getX();
                    final float minY = cell.getY();
                    final float maxY = cell.getY() + 1;
                    final float minZ = cell.getZ();
                    final float maxZ = cell.getZ() + 1;
                    final float u0 = (minY - (float) bounds.minY) * sizeUvY;
                    final float u1 = u0 + sizeUvY;
                    final float v0 = (minZ - (float) bounds.minZ) * sizeUvZ;
                    final float v1 = v0 + sizeUvZ;
                    buffer.vertex(matrix, x, minY, minZ).color(r, g, b, 0.8f).uv(u0, v0).endVertex();
                    buffer.vertex(matrix, x, minY, maxZ).color(r, g, b, 0.8f).uv(u0, v1).endVertex();
                    buffer.vertex(matrix, x, maxY, maxZ).color(r, g, b, 0.8f).uv(u1, v1).endVertex();
                    buffer.vertex(matrix, x, maxY, minZ).color(r, g, b, 0.8f).uv(u1, v0).endVertex();
                }
                if (!blocks.contains(cell.offset(1, 0, 0))) {
                    final float x = cell.getX() + 1;
                    final float minY = cell.getY();
                    final float maxY = cell.getY() + 1;
                    final float minZ = cell.getZ();
                    final float maxZ = cell.getZ() + 1;
                    final float u0 = (minY - (float) bounds.minY) * sizeUvY;
                    final float u1 = u0 + sizeUvY;
                    final float v0 = (minZ - (float) bounds.minZ) * sizeUvZ;
                    final float v1 = v0 + sizeUvZ;
                    buffer.vertex(matrix, x, minY, minZ).color(r, g, b, 0.8f).uv(u0, v0).endVertex();
                    buffer.vertex(matrix, x, maxY, minZ).color(r, g, b, 0.8f).uv(u1, v0).endVertex();
                    buffer.vertex(matrix, x, maxY, maxZ).color(r, g, b, 0.8f).uv(u1, v1).endVertex();
                    buffer.vertex(matrix, x, minY, maxZ).color(r, g, b, 0.8f).uv(u0, v1).endVertex();
                }
                if (!blocks.contains(cell.offset(0, -1, 0))) {
                    final float y = cell.getY();
                    final float minX = cell.getX();
                    final float maxX = cell.getX() + 1;
                    final float minZ = cell.getZ();
                    final float maxZ = cell.getZ() + 1;
                    final float u0 = (minX - (float) bounds.minX) * sizeUvX;
                    final float u1 = u0 + sizeUvX;
                    final float v0 = (minZ - (float) bounds.minZ) * sizeUvZ;
                    final float v1 = v0 + sizeUvZ;
                    buffer.vertex(matrix, minX, y, minZ).color(r, g, b, 0.7f).uv(u0, v0).endVertex();
                    buffer.vertex(matrix, maxX, y, minZ).color(r, g, b, 0.7f).uv(u1, v0).endVertex();
                    buffer.vertex(matrix, maxX, y, maxZ).color(r, g, b, 0.7f).uv(u1, v1).endVertex();
                    buffer.vertex(matrix, minX, y, maxZ).color(r, g, b, 0.7f).uv(u0, v1).endVertex();
                }
                if (!blocks.contains(cell.offset(0, 1, 0))) {
                    final float y = cell.getY() + 1;
                    final float minX = cell.getX();
                    final float maxX = cell.getX() + 1;
                    final float minZ = cell.getZ();
                    final float maxZ = cell.getZ() + 1;
                    final float u0 = (minX - (float) bounds.minX) * sizeUvX;
                    final float u1 = u0 + sizeUvX;
                    final float v0 = (minZ - (float) bounds.minZ) * sizeUvZ;
                    final float v1 = v0 + sizeUvZ;
                    buffer.vertex(matrix, minX, y, minZ).color(r, g, b, 1.0f).uv(u0, v0).endVertex();
                    buffer.vertex(matrix, minX, y, maxZ).color(r, g, b, 1.0f).uv(u0, v1).endVertex();
                    buffer.vertex(matrix, maxX, y, maxZ).color(r, g, b, 1.0f).uv(u1, v1).endVertex();
                    buffer.vertex(matrix, maxX, y, minZ).color(r, g, b, 1.0f).uv(u1, v0).endVertex();
                }
                if (!blocks.contains(cell.offset(0, 0, -1))) {
                    final float z = cell.getZ();
                    final float minX = cell.getX();
                    final float maxX = cell.getX() + 1;
                    final float minY = cell.getY();
                    final float maxY = cell.getY() + 1;
                    final float u0 = (minX - (float) bounds.minX) * sizeUvX;
                    final float u1 = u0 + sizeUvX;
                    final float v0 = (minY - (float) bounds.minY) * sizeUvY;
                    final float v1 = v0 + sizeUvY;
                    buffer.vertex(matrix, minX, minY, z).color(r, g, b, 0.9f).uv(u0, v0).endVertex();
                    buffer.vertex(matrix, minX, maxY, z).color(r, g, b, 0.9f).uv(u0, v1).endVertex();
                    buffer.vertex(matrix, maxX, maxY, z).color(r, g, b, 0.9f).uv(u1, v1).endVertex();
                    buffer.vertex(matrix, maxX, minY, z).color(r, g, b, 0.9f).uv(u1, v0).endVertex();
                }
                if (!blocks.contains(cell.offset(0, 0, 1))) {
                    final float z = cell.getZ() + 1;
                    final float minX = cell.getX();
                    final float maxX = cell.getX() + 1;
                    final float minY = cell.getY();
                    final float maxY = cell.getY() + 1;
                    final float u0 = (minX - (float) bounds.minX) * sizeUvX;
                    final float u1 = u0 + sizeUvX;
                    final float v0 = (minY - (float) bounds.minY) * sizeUvY;
                    final float v1 = v0 + sizeUvY;
                    buffer.vertex(matrix, minX, minY, z).color(r, g, b, 0.9f).uv(u0, v0).endVertex();
                    buffer.vertex(matrix, maxX, minY, z).color(r, g, b, 0.9f).uv(u1, v0).endVertex();
                    buffer.vertex(matrix, maxX, maxY, z).color(r, g, b, 0.9f).uv(u1, v1).endVertex();
                    buffer.vertex(matrix, minX, maxY, z).color(r, g, b, 0.9f).uv(u0, v1).endVertex();
                }
            }
        }

        // --------------------------------------------------------------------- //
        // ScanResult

        @Nullable
        @Override
        public AxisAlignedBB getRenderBounds() {
            return bounds;
        }

        @Override
        public Vector3d getPosition() {
            return bounds.getCenter();
        }

        @Override
        public void close() {
            if (vbo != null) {
                vbo.close();
                vbo = null;
            }
        }
    }

    // --------------------------------------------------------------------- //

    private ScanResultProviderBlock() {
    }
}
