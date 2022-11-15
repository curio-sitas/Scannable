package li.cil.scannable.common.scanning;

import li.cil.scannable.api.scanning.BlockScannerModule;
import li.cil.scannable.api.scanning.ScanResultProvider;
import li.cil.scannable.client.scanning.ScanResultProviders;
import li.cil.scannable.client.scanning.filter.BlockCacheScanFilter;
import li.cil.scannable.client.scanning.filter.BlockScanFilter;
import li.cil.scannable.client.scanning.filter.BlockTagScanFilter;
import li.cil.scannable.common.config.CommonConfig;
import li.cil.scannable.common.config.Constants;
import li.cil.scannable.common.scanning.filter.IgnoredBlocks;
import li.cil.scannable.util.PlatformUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public enum RareOresBlockScannerModule implements BlockScannerModule {
    INSTANCE;

    private Predicate<BlockState> filter;

    public static void clearCache() {
        INSTANCE.filter = null;
    }

    @Override
    public int getEnergyCost(final ItemStack module) {
        return CommonConfig.energyCostModuleOreRare;
    }

    @Environment(EnvType.CLIENT)
    @Override
    public ScanResultProvider getResultProvider() {
        return ScanResultProviders.BLOCKS.get();
    }

    @Environment(EnvType.CLIENT)
    @Override
    public float adjustLocalRange(final float range) {
        return range * Constants.ORE_MODULE_RADIUS_MULTIPLIER;
    }

    @Environment(EnvType.CLIENT)
    @Override
    public Predicate<BlockState> getFilter(final ItemStack module) {
        validateFilter();
        return filter;
    }

    @Environment(EnvType.CLIENT)
    private void validateFilter() {
        if (filter != null) {
            return;
        }

        final List<Predicate<BlockState>> filters = new ArrayList<>();
        for (final ResourceLocation location : CommonConfig.rareOreBlocks) {
            Registry.BLOCK.getOptional(location).ifPresent(block ->
                filters.add(new BlockScanFilter(block)));
        }
        Registry.BLOCK.getTagNames().forEach(tag -> {
            if (CommonConfig.rareOreBlockTags.contains(tag.location())) {
                filters.add(new BlockTagScanFilter(tag));
            }
        });

        // Treat all blocks tagged as ores but not part of the common ore category as rare.
        filters.add(state -> !IgnoredBlocks.contains(state) &&
            state.is(PlatformUtils.getTopLevelOreTag()) &&
            !CommonOresBlockScannerModule.INSTANCE.getFilter(ItemStack.EMPTY).test(state));

        filter = new BlockCacheScanFilter(filters);
    }
}