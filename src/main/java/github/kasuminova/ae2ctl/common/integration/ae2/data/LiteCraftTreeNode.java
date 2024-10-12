package github.kasuminova.ae2ctl.common.integration.ae2.data;

import appeng.api.storage.data.IAEItemStack;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.CraftingTreeProcess;
import github.kasuminova.ae2ctl.common.util.AEItemStackSet;
import github.kasuminova.ae2ctl.common.util.ByteBufUtils;
import github.kasuminova.ae2ctl.mixin.ae2.AccessorCraftingTreeNode;
import io.netty.buffer.ByteBuf;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class LiteCraftTreeNode implements Comparable<LiteCraftTreeNode> {
    private final LiteCraftTreeProc parent;
    private final IAEItemStack output;
    private final List<LiteCraftTreeProc> inputs;
    private final long missing;

    private boolean missingCached = false;
    private boolean missingCache = false;

    public LiteCraftTreeNode(final LiteCraftTreeProc parent, IAEItemStack output, List<LiteCraftTreeProc> inputs, long missing) {
        this.parent = parent;
        this.output = output;
        this.inputs = inputs;
        this.missing = missing;
    }

    public static LiteCraftTreeNode of(final CraftingTreeNode node, final LiteCraftTreeProc parent) {
        AccessorCraftingTreeNode accessor = (AccessorCraftingTreeNode) node;
        List<LiteCraftTreeProc> inputs = new ArrayList<>();
        for (CraftingTreeProcess process : accessor.getNodes()) {
            LiteCraftTreeProc proc = LiteCraftTreeProc.of(process);
            if (proc != null) {
                inputs.add(proc);
            }
        }
        return new LiteCraftTreeNode(parent, accessor.getWhat().copy().setCraftable(false), inputs, accessor.getMissing());
    }

    public static LiteCraftTreeNode fromBuffer(final ByteBuf buf, final AEItemStackSet stackSet, final LiteCraftTreeProc parent) {
        int stackID = (int) ByteBufUtils.readVarLong(buf);
        IAEItemStack output = stackSet.get(stackID);

        long stackSize = ByteBufUtils.readVarLong(buf);
        output.setStackSize(stackSize);

        int size = buf.readByte();
        List<LiteCraftTreeProc> inputs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            inputs.add(LiteCraftTreeProc.fromBuffer(buf, stackSet));
        }

        long missing = ByteBufUtils.readVarLong(buf);
        return new LiteCraftTreeNode(parent, output, inputs, missing);
    }

    public void writeToBuffer(final ByteBuf buf, final AEItemStackSet stackSet) {
        if (inputs.size() > Byte.MAX_VALUE) {
            throw new IllegalStateException("Too many inputs for a single node");
        }

        int stackID = stackSet.add(output);
        ByteBufUtils.writeVarLong(buf, stackID);

        long stackSize = output.getStackSize();
        ByteBufUtils.writeVarLong(buf, stackSize);

        buf.writeByte(inputs.size());
        inputs.forEach(input -> input.writeToBuffer(buf, stackSet));
        ByteBufUtils.writeVarLong(buf, missing);
    }

    public void sort() {
        inputs.sort(Comparator.reverseOrder());
        for (final LiteCraftTreeProc input : inputs) {
            input.sort();
            for (final LiteCraftTreeNode subNode : input.inputs()) {
                subNode.sort();
            }
        }
    }

    @Override
    public int compareTo(@Nonnull final LiteCraftTreeNode o) {
        return Integer.compare(diveToDeep(this, 0, new DepthRecorder()), diveToDeep(o, 0, new DepthRecorder()));
    }

    public static int diveToDeep(final LiteCraftTreeNode node, final int depth, final DepthRecorder recorder) {
        for (final LiteCraftTreeProc input : node.inputs) {
            for (final LiteCraftTreeNode subNode : input.inputs()) {
                int newDepth = depth + 1;
                recorder.dive(newDepth);
                diveToDeep(subNode, newDepth, recorder);
            }
        }
        return recorder.getDepth();
    }

    public int totalProcessors() {
        int size = inputs.size();
        for (final LiteCraftTreeProc input : inputs) {
            for (final LiteCraftTreeNode node : input.inputs()) {
                size += node.totalProcessors();
            }
        }
        return size;
    }

    public int getRenderExpandNodes() {
        int size = Math.max(inputs.size() - 1, 0);
        for (final LiteCraftTreeProc input : inputs) {
            size += Math.max(input.inputs().size() - 1, 0);
            for (final LiteCraftTreeNode node : input.inputs()) {
                size += node.getRenderExpandNodes();
            }
        }
        return size;
    }

    public int getLastNodeRenderExpandNodes() {
        if (inputs.isEmpty()) {
            return 0;
        }
        LiteCraftTreeProc proc = inputs.get(inputs.size() - 1);

        List<LiteCraftTreeNode> subNodes = proc.inputs();
        if (subNodes.isEmpty()) {
            return 0;
        }

        LiteCraftTreeNode subNode = subNodes.get(subNodes.size() - 1);
        return subNode.getRenderExpandNodes();
    }

    public LiteCraftTreeNode withMissingOnly() {
        if (!isMissing(this)) {
            return null;
        }

        List<LiteCraftTreeProc> missingInputs = new ArrayList<>();
        for (final LiteCraftTreeProc input : inputs) {
            List<LiteCraftTreeNode> missingSubNodes = new ArrayList<>();
            for (final LiteCraftTreeNode subNode : input.inputs()) {
                if (isMissing(subNode)) {
                    missingSubNodes.add(subNode.withMissingOnly());
                }
            }
            if (!missingSubNodes.isEmpty()) {
                missingInputs.add(new LiteCraftTreeProc(missingSubNodes));
            }
        }

        LiteCraftTreeNode node = new LiteCraftTreeNode(parent, output, missingInputs, missing);
        node.missingCached = true;
        node.missingCache = true;
        return node;
    }

    public LiteCraftTreeProc parent() {
        return parent;
    }

    public IAEItemStack output() {
        return output;
    }

    public List<LiteCraftTreeProc> inputs() {
        return inputs;
    }

    public long missing() {
        return missing;
    }

    public static boolean isMissing(final LiteCraftTreeNode node) {
        if (node.missingCached) {
            return node.missingCache;
        }
        if (node.missing() > 0) {
            node.missingCached = true;
            return node.missingCache = true;
        }
        for (final LiteCraftTreeProc input : node.inputs()) {
            for (final LiteCraftTreeNode subNode : input.inputs()) {
                if (isMissing(subNode)) {
                    return node.missingCached = node.missingCache = true;
                }
            }
        }
        node.missingCached = true;
        node.missingCache = false;
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (LiteCraftTreeNode) obj;
        return Objects.equals(this.output, that.output) &&
               Objects.equals(this.inputs, that.inputs) &&
               this.missing == that.missing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(output, inputs, missing);
    }

    @Override
    public String toString() {
        return "LiteCraftTreeNode[" +
               "output=" + output + ", " +
               "inputs=" + inputs + ", " +
               "missing=" + missing + ']';
    }

    public static class DepthRecorder {

        private int depth;

        void dive(int depth) {
            this.depth = Math.max(this.depth, depth);
        }

        public int getDepth() {
            return depth;
        }

    }

}
