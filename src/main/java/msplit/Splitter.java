package msplit;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnalyzerAdapter;
import org.objectweb.asm.tree.*;

import java.util.*;

public class Splitter implements Iterable<Splitter.SplitPoint> {
  protected final int api;
  protected final String owner;
  protected final MethodNode method;
  protected final int minSize;
  protected final int maxSize;

  public Splitter(int api, String owner, MethodNode method, int minSize, int maxSize) {
    this.api = api;
    this.owner = owner;
    this.method = method;
    this.minSize = minSize;
    this.maxSize = maxSize;
  }

  @Override
  public Iterator<SplitPoint> iterator() { return new Iter(); }

  public static class SplitPoint {
    public final Set<Integer> localsRead;
    public final Set<Integer> localsWritten;
    public final List<Type> neededFromStackAtStart;
    public final List<Type> putOnStackAtEnd;
    public final int start;
    public final int length;

    public SplitPoint(Set<Integer> localsRead, Set<Integer> localsWritten,
        List<Type> neededFromStackAtStart, List<Type> putOnStackAtEnd, int start, int length) {
      this.localsRead = localsRead;
      this.localsWritten = localsWritten;
      this.neededFromStackAtStart = neededFromStackAtStart;
      this.putOnStackAtEnd = putOnStackAtEnd;
      this.start = start;
      this.length = length;
    }
  }

  protected class Iter implements Iterator<SplitPoint> {
    protected final AbstractInsnNode[] insns;
    protected int currIndex = -1;
    protected boolean peeked;
    protected SplitPoint peekedValue;

    protected Iter() { insns = method.instructions.toArray(); }

    @Override
    public boolean hasNext() {
      if (!peeked) {
        peeked = true;
        peekedValue = nextOrNull();
      }
      return peekedValue != null;
    }

    @Override
    public SplitPoint next() {
      // If we've peeked in hasNext, use that
      SplitPoint ret;
      if (peeked) {
        peeked = false;
        ret = peekedValue;
      } else {
        ret = nextOrNull();
      }
      if (ret == null) throw new NoSuchElementException();
      return ret;
    }

    protected SplitPoint nextOrNull() {
      // Try for each index
      while (++currIndex + minSize <= insns.length) {
        SplitPoint longest = longestForCurrIndex();
        if (longest != null) return longest;
      }
      return null;
    }

    protected SplitPoint longestForCurrIndex() {
      // As a special case, if the previous insn was a line number, that was good enough
      if (currIndex - 1 >- 0 && insns[currIndex - 1] instanceof LineNumberNode) return null;
      // Build the info object
      InsnTraverseInfo info = new InsnTraverseInfo();
      info.startIndex = currIndex;
      info.endIndex = Math.min(currIndex + maxSize - 1, insns.length - 1);
      // Reduce the end based on try/catch blocks the start is in or that jump to
      constrainEndByTryCatchBlocks(info);
      // Reduce the end based on any jumps within
      constrainEndByInternalJumps(info);
      // Reduce the end based on any jumps into
      constrainEndByExternalJumps(info);
      // Make sure we didn't reduce the end too far
      if (info.getSize() < minSize) return null;
      // Now that we have our largest range from the start index, we can go over each updating the local refs and stack
      // For the stack, we are going to use the
      return splitPointFromInfo(info);
    }

    protected void constrainEndByTryCatchBlocks(InsnTraverseInfo info) {
      // If there are try blocks that the start is in, we can only go to the earliest block end
      for (TryCatchBlockNode block : method.tryCatchBlocks) {
        // No matter what, for now, we don't include catch handling
        int handleIndex = method.instructions.indexOf(block.handler);
        if (info.startIndex < handleIndex) info.endIndex = Math.min(info.endIndex, handleIndex);
        // Now we can check the try-block range
        int start = method.instructions.indexOf(block.start);
        if (info.startIndex < start) continue;
        int end = method.instructions.indexOf(block.end);
        if (info.startIndex >= end) continue;
        info.endIndex = Math.min(info.endIndex, end - 1);
      }
    }

    // Returns false if any jumps jump outside of the current range
    protected void constrainEndByInternalJumps(InsnTraverseInfo info) {
      for (int i = info.startIndex; i <= info.endIndex; i++) {
        AbstractInsnNode node = insns[i];
        int earliestIndex;
        int furthestIndex;
        if (node instanceof JumpInsnNode) {
          earliestIndex = method.instructions.indexOf(((JumpInsnNode) node).label);
          furthestIndex = earliestIndex;
        } else if (node instanceof TableSwitchInsnNode) {
          earliestIndex = method.instructions.indexOf(((TableSwitchInsnNode) node).dflt);
          furthestIndex = earliestIndex;
          for (LabelNode label : ((TableSwitchInsnNode) node).labels) {
            int index = method.instructions.indexOf(label);
            earliestIndex = Math.min(earliestIndex, index);
            furthestIndex = Math.max(furthestIndex, index);
          }
        } else if (node instanceof LookupSwitchInsnNode) {
          earliestIndex = method.instructions.indexOf(((LookupSwitchInsnNode) node).dflt);
          furthestIndex = earliestIndex;
          for (LabelNode label : ((LookupSwitchInsnNode) node).labels) {
            int index = method.instructions.indexOf(label);
            earliestIndex = Math.min(earliestIndex, index);
            furthestIndex = Math.max(furthestIndex, index);
          }
        } else continue;
        // Stop here if any indexes are out of range, otherwise, change end
        if (earliestIndex < info.startIndex || furthestIndex > info.endIndex) {
          info.endIndex = i - 1;
          return;
        }
        info.endIndex = Math.max(info.endIndex, furthestIndex);
      }
    }

    protected void constrainEndByExternalJumps(InsnTraverseInfo info) {
      // Basically, if any external jumps jump into our range, that can't be included in the range
      for (int i = 0; i < insns.length; i++) {
        if (i >= info.startIndex && i <= info.endIndex) continue;
        AbstractInsnNode node = insns[i];
        if (node instanceof JumpInsnNode) {
          int index = method.instructions.indexOf(((JumpInsnNode) node).label);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
        } else if (node instanceof TableSwitchInsnNode) {
          int index = method.instructions.indexOf(((TableSwitchInsnNode) node).dflt);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          for (LabelNode label : ((TableSwitchInsnNode) node).labels) {
            index = method.instructions.indexOf(label);
            if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          }
        } else if (node instanceof LookupSwitchInsnNode) {
          int index = method.instructions.indexOf(((LookupSwitchInsnNode) node).dflt);
          if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          for (LabelNode label : ((LookupSwitchInsnNode) node).labels) {
            index = method.instructions.indexOf(label);
            if (index >= info.startIndex) info.endIndex = Math.min(info.endIndex, index - 1);
          }
        }
      }
    }

    protected SplitPoint splitPointFromInfo(InsnTraverseInfo info) {
      // We're going to use the analyzer adapter and run it for the up until the end, a step at a time
      StackAndLocalTrackingAdapter adapter = new StackAndLocalTrackingAdapter(Splitter.this);
      // Visit all of the insns up our start.
      // XXX: I checked the source of AnalyzerAdapter to confirm I don't need any of the surrounding stuff
      for (int i = 0; i < info.startIndex; i++) insns[i].accept(adapter);
      // Take the stack at the start and copy it off
      List<Object> stackAtStart = new ArrayList<>(adapter.stack);
      // Reset some adapter state
      adapter.lowestStackSize = stackAtStart.size();
      adapter.localsRead.clear();
      adapter.localsWritten.clear();
      // Now go over the remaining range
      for (int i = info.startIndex; i <= info.endIndex; i++) insns[i].accept(adapter);
      // Build the split point
      return new SplitPoint(
          adapter.localsRead,
          adapter.localsWritten,
          typesFromAdapterStackRange(stackAtStart, adapter.lowestStackSize, adapter.uninitializedTypes),
          typesFromAdapterStackRange(adapter.stack, adapter.lowestStackSize, adapter.uninitializedTypes),
          info.startIndex,
          info.getSize()
      );
    }

    protected List<Type> typesFromAdapterStackRange(
        List<Object> stack, int start, Map<Object, Object> uninitializedTypes) {
      List<Type> ret = new ArrayList<>();
      for (int i = start; i < stack.size(); i++) {
        Object item = stack.get(i);
        if (item == Opcodes.INTEGER) ret.add(Type.INT_TYPE);
        else if (item == Opcodes.FLOAT) ret.add(Type.FLOAT_TYPE);
        else if (item == Opcodes.LONG) ret.add(Type.LONG_TYPE);
        else if (item == Opcodes.DOUBLE) ret.add(Type.DOUBLE_TYPE);
        else if (item == Opcodes.NULL) ret.add(Type.getType(Object.class));
        else if (item == Opcodes.UNINITIALIZED_THIS) ret.add(Type.getObjectType(owner));
        else if (item instanceof Label) ret.add(Type.getObjectType((String) uninitializedTypes.get(item)));
        else if (item instanceof String) ret.add(Type.getObjectType((String) item));
        else throw new IllegalStateException("Unrecognized stack item: " + item);
        // Jump an extra spot for longs and doubles
        if (item == Opcodes.LONG || item == Opcodes.DOUBLE) {
          if (stack.get(++i) != Opcodes.TOP) throw new IllegalStateException("Expected top after long/double");
        }
      }
      return ret;
    }
  }

  protected static class StackAndLocalTrackingAdapter extends AnalyzerAdapter {
    public int lowestStackSize;
    public final Set<Integer> localsRead = new TreeSet<>();
    public final Set<Integer> localsWritten = new TreeSet<>();

    protected StackAndLocalTrackingAdapter(Splitter splitter) {
      super(splitter.api, splitter.owner, splitter.method.access, splitter.method.name, splitter.method.desc, null);
      stack = new SizeChangeNotifyList<Object>() {
        @Override
        protected void onSizeChanged() { lowestStackSize = Math.min(lowestStackSize, size()); }
      };
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
      super.visitVarInsn(opcode, var);
      switch (opcode) {
        case Opcodes.ILOAD:
        case Opcodes.LLOAD:
        case Opcodes.FLOAD:
        case Opcodes.DLOAD:
        case Opcodes.ALOAD:
          localsRead.add(var);
          break;
        case Opcodes.ISTORE:
        case Opcodes.LSTORE:
        case Opcodes.FSTORE:
        case Opcodes.DSTORE:
        case Opcodes.ASTORE:
          localsWritten.add(var);
          break;
      }
    }

    @Override
    public void visitIincInsn(int var, int increment) {
      super.visitIincInsn(var, increment);
      localsRead.add(var);
      localsWritten.add(var);
    }
  }

  protected static class SizeChangeNotifyList<T> extends AbstractList<T> {
    protected final ArrayList<T> list = new ArrayList<>();

    protected void onSizeChanged() { }

    @Override
    public T get(int index) { return list.get(index); }

    @Override
    public int size() { return list.size(); }

    @Override
    public T set(int index, T element) { return list.set(index, element); }

    @Override
    public void add(int index, T element) {
      list.add(index, element);
      onSizeChanged();
    }

    @Override
    public T remove(int index) {
      T ret = list.remove(index);
      onSizeChanged();
      return ret;
    }
  }

  protected static class InsnTraverseInfo {
    public int startIndex;
    // Can only shrink, never increase in size
    public int endIndex;

    public int getSize() { return endIndex - startIndex + 1; }
  }
}
