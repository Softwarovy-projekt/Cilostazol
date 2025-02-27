package com.vztekoverflow.cilostazol.nodes;

import static com.vztekoverflow.cil.parser.bytecode.BytecodeInstructions.*;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.HostCompilerDirectives;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.BytecodeOSRNode;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.staticobject.StaticProperty;
import com.vztekoverflow.cil.parser.bytecode.BytecodeBuffer;
import com.vztekoverflow.cil.parser.bytecode.BytecodeInstructions;
import com.vztekoverflow.cil.parser.cli.table.CLITablePtr;
import com.vztekoverflow.cil.parser.cli.table.CLIUSHeapPtr;
import com.vztekoverflow.cilostazol.CILOSTAZOLBundle;
import com.vztekoverflow.cilostazol.exceptions.InterpreterException;
import com.vztekoverflow.cilostazol.exceptions.NotImplementedException;
import com.vztekoverflow.cilostazol.exceptions.RuntimeCILException;
import com.vztekoverflow.cilostazol.nodes.internal.IndirectLoader;
import com.vztekoverflow.cilostazol.nodes.nodeized.*;
import com.vztekoverflow.cilostazol.runtime.objectmodel.StaticField;
import com.vztekoverflow.cilostazol.runtime.objectmodel.StaticObject;
import com.vztekoverflow.cilostazol.runtime.other.SymbolResolver;
import com.vztekoverflow.cilostazol.runtime.symbols.*;
import com.vztekoverflow.cilostazol.runtime.symbols.MethodSymbol.MethodFlags.Flag;
import com.vztekoverflow.cilostazol.staticanalysis.StaticOpCodeAnalyser;
import java.lang.reflect.Array;
import java.util.Arrays;

public class CILMethodNode extends CILNodeBase implements BytecodeOSRNode {
  private final MethodSymbol method;
  private final byte[] cil;
  private final BytecodeBuffer bytecodeBuffer;
  private final FrameDescriptor frameDescriptor;

  @Children private NodeizedNodeBase[] nodes = new NodeizedNodeBase[0];
  @CompilerDirectives.CompilationFinal private Object osrMetadata;

  CILMethodNode(MethodSymbol method) {
    this.method = method;
    cil = method.getCIL();
    frameDescriptor =
        CILOSTAZOLFrame.create(
            method.getParameterCountIncludingInstance(),
            method.getLocals().length,
            method.getMaxStack());
    this.bytecodeBuffer = new BytecodeBuffer(cil);
  }

  public static CILMethodNode create(MethodSymbol method) {
    return new CILMethodNode(method);
  }

  public MethodSymbol getMethod() {
    return method;
  }

  public FrameDescriptor getFrameDescriptor() {
    return frameDescriptor;
  }

  // region CILNodeBase
  @Override
  public Object execute(VirtualFrame frame) {
    initializeFrame(frame);
    return execute(frame, 0, CILOSTAZOLFrame.getStartStackOffset(method));
  }
  // endregion

  // region OSR
  @Override
  public Object executeOSR(VirtualFrame osrFrame, int target, Object interpreterState) {
    OSRInterpreterState state = (OSRInterpreterState) interpreterState;
    return execute(osrFrame, target, state.top);
  }

  @Override
  public Object getOSRMetadata() {
    return osrMetadata;
  }

  @Override
  public void setOSRMetadata(Object osrMetadata) {
    this.osrMetadata = osrMetadata;
  }

  private int beforeJumpChecks(VirtualFrame frame, int curBCI, int targetBCI, int top) {
    CompilerAsserts.partialEvaluationConstant(targetBCI);
    if (targetBCI <= curBCI) {
      if (CompilerDirectives.inInterpreter() && BytecodeOSRNode.pollOSRBackEdge(this)) {
        Object osrResult;
        try {
          osrResult =
              BytecodeOSRNode.tryOSR(this, targetBCI, new OSRInterpreterState(top), null, frame);
        } catch (Throwable any) {
          // Has already been guest-handled in OSR. Shortcut out of the method.
          throw new OSRReturnException(any);
        }
        if (osrResult != null) {
          throw new OSRReturnException(osrResult);
        }
      }
    }
    return targetBCI;
  }

  // endregion

  private void initializeFrame(VirtualFrame frame) {
    // Init arguments
    Object[] args = frame.getArguments();
    TypeSymbol[] argTypes = getMethod().getParameterTypesIncludingInstance();
    int argsOffset = CILOSTAZOLFrame.getStartArgsOffset(getMethod());

    for (int i = 0; i < args.length; i++) {
      CILOSTAZOLFrame.put(frame, args[i], argsOffset + i, argTypes[i]);
    }
  }

  @ExplodeLoop(kind = ExplodeLoop.LoopExplosionKind.MERGE_EXPLODE)
  @HostCompilerDirectives.BytecodeInterpreterSwitch
  private Object execute(VirtualFrame frame, int startPc, int top) {
    CompilerAsserts.partialEvaluationConstant(startPc);
    int pc = startPc;
    int topStack = top;
    RuntimeCILException currentEx = null;

    while (true) {
      int curOpcode = bytecodeBuffer.getOpcode(pc);
      int nextpc = bytecodeBuffer.nextInstruction(pc);
      try {
        CompilerAsserts.partialEvaluationConstant(topStack);
        CompilerAsserts.partialEvaluationConstant(pc);
        CompilerAsserts.partialEvaluationConstant(curOpcode);
        switch (curOpcode) {
          case NOP:
            break;
          case POP:
            pop(frame, topStack, getMethod().getOpCodeTypes()[pc]);
            break;
          case DUP:
            duplicateSlot(frame, topStack);
            break;

            // Loading on top of the stack
          case LDNULL:
            CILOSTAZOLFrame.putObject(frame, topStack, StaticObject.NULL);
            break;
          case LDC_I4_M1:
          case LDC_I4_0:
          case LDC_I4_1:
          case LDC_I4_2:
          case LDC_I4_3:
          case LDC_I4_4:
          case LDC_I4_5:
          case LDC_I4_6:
          case LDC_I4_7:
          case LDC_I4_8:
            CILOSTAZOLFrame.putInt32(frame, topStack, curOpcode - LDC_I4_0);
            break;
          case LDC_I4_S:
            CILOSTAZOLFrame.putInt32(frame, topStack, bytecodeBuffer.getImmByte(pc));
            break;
          case LDC_I4:
            CILOSTAZOLFrame.putInt32(frame, topStack, bytecodeBuffer.getImmInt(pc));
            break;
          case LDC_I8:
            CILOSTAZOLFrame.putInt64(frame, topStack, bytecodeBuffer.getImmLong(pc));
            break;
          case LDC_R4:
            CILOSTAZOLFrame.putNativeFloat(
                frame, topStack, Float.intBitsToFloat(bytecodeBuffer.getImmInt(pc)));
            break;
          case LDC_R8:
            CILOSTAZOLFrame.putNativeFloat(
                frame, topStack, Double.longBitsToDouble(bytecodeBuffer.getImmLong(pc)));
            break;
          case LDSTR:
            loadString(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;

            // Storing to locals
          case STLOC_0:
          case STLOC_1:
          case STLOC_2:
          case STLOC_3:
            CILOSTAZOLFrame.copyStatic(frame, topStack - 1, curOpcode - STLOC_0);
            if (topStack - 1 != curOpcode - STLOC_0) frame.clearStatic(topStack - 1);
            break;
          case STLOC_S:
            int slot = bytecodeBuffer.getImmUByte(pc);
            CILOSTAZOLFrame.copyStatic(frame, topStack - 1, slot);
            if (topStack - 1 != slot) frame.clearStatic(topStack - 1);
            break;

            // Loading locals to top
          case LDLOC_0:
          case LDLOC_1:
          case LDLOC_2:
          case LDLOC_3:
            CILOSTAZOLFrame.copyStatic(frame, curOpcode - LDLOC_0, topStack);
            break;
          case LDLOC_S:
            CILOSTAZOLFrame.copyStatic(frame, bytecodeBuffer.getImmUByte(pc), topStack);
            break;
          case LDLOC:
            CILOSTAZOLFrame.copyStatic(frame, bytecodeBuffer.getImmUShort(pc), topStack);
            break;
          case LDLOCA_S:
            loadLocalIndirect(frame, bytecodeBuffer.getImmUByte(pc), topStack);
            break;
          case LDLOCA:
            loadLocalIndirect(frame, bytecodeBuffer.getImmUShort(pc), topStack);
            break;

            // Loading args to top
          case LDARG_0:
          case LDARG_1:
          case LDARG_2:
          case LDARG_3:
            CILOSTAZOLFrame.copyStatic(
                frame,
                CILOSTAZOLFrame.getStartArgsOffset(getMethod()) + curOpcode - LDARG_0,
                topStack);
            break;
          case LDARG_S:
            CILOSTAZOLFrame.copyStatic(
                frame,
                CILOSTAZOLFrame.getStartArgsOffset(getMethod()) + bytecodeBuffer.getImmUByte(pc),
                topStack);
            break;
          case LDARG:
            CILOSTAZOLFrame.copyStatic(
                frame,
                CILOSTAZOLFrame.getStartArgsOffset(getMethod()) + bytecodeBuffer.getImmUShort(pc),
                topStack);
            break;
          case LDARGA_S:
            loadArgument(frame, bytecodeBuffer.getImmUByte(pc), topStack);
            break;
          case LDARGA:
            loadArgument(frame, bytecodeBuffer.getImmUShort(pc), topStack);
            break;

            // Storing args
          case STARG_S:
            CILOSTAZOLFrame.moveValueStatic(
                frame,
                topStack - 1,
                CILOSTAZOLFrame.getStartArgsOffset(getMethod()) + bytecodeBuffer.getImmUByte(pc));
            break;
          case STARG:
            CILOSTAZOLFrame.moveValueStatic(
                frame,
                topStack - 1,
                CILOSTAZOLFrame.getStartArgsOffset(getMethod()) + bytecodeBuffer.getImmUShort(pc));

            // Loading fields
          case LDFLD:
            loadInstanceField(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;
          case LDSFLD:
            loadStaticField(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;
          case LDFLDA:
            loadFieldInstanceFieldRef(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;
          case LDSFLDA:
            loadFieldStaticFieldRef(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;

            // Storing fields
          case STFLD:
            storeInstanceField(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;
          case STSFLD:
            storeStaticField(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;

            // Object manipulation
          case LDOBJ:
            copyObject(
                frame,
                bytecodeBuffer.getImmToken(pc),
                getSlotFromReference(frame, topStack - 1),
                topStack - 1);
            break;
          case STOBJ:
            copyObject(
                frame,
                bytecodeBuffer.getImmToken(pc),
                topStack - 1,
                getSlotFromReference(frame, topStack - 2));
            break;

          case INITOBJ:
            initializeObject(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;
          case NEWOBJ:
            topStack =
                nodeizeOpToken(frame, topStack, bytecodeBuffer.getImmToken(pc), pc, curOpcode);
            break;
          case CPOBJ:
            copyObjectIndirectly(frame, topStack - 2, topStack - 1);
            break;
          case ISINST:
            checkIsInstance(frame, topStack - 1, bytecodeBuffer.getImmToken(pc));
            break;
          case CASTCLASS:
            castClass(frame, topStack - 1, bytecodeBuffer.getImmToken(pc));
            break;
          case BOX:
            box(frame, topStack - 1, bytecodeBuffer.getImmToken(pc));
            break;
          case UNBOX:
            unbox(frame, topStack - 1, bytecodeBuffer.getImmToken(pc));
            break;
          case UNBOX_ANY:
            unboxAny(frame, topStack - 1, bytecodeBuffer.getImmToken(pc));
            break;
          case SIZEOF:
            getSize(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;
          case MKREFANY:
            makeTypedRef(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;
          case REFANYTYPE:
            getTypeFromTypedRef(frame, topStack);
            break;
          case REFANYVAL:
            getRefFromTypedRef(frame, topStack, bytecodeBuffer.getImmToken(pc));
            break;

            // Branching
          case BEQ:
          case BGE:
          case BGT:
          case BLE:
          case BLT:
          case BGE_UN:
          case BGT_UN:
          case BLE_UN:
          case BLT_UN:
          case BNE_UN:
            if (binaryCompare(
                curOpcode, frame, topStack - 2, topStack - 1, getMethod().getOpCodeTypes()[pc])) {
              int targetPc = nextpc + bytecodeBuffer.getImmInt(pc);
              topStack += BytecodeInstructions.getStackEffect(curOpcode);
              pc = beforeJumpChecks(frame, pc, targetPc, topStack);
              continue;
            }
            break;

            // Branching - short
          case BEQ_S:
          case BGE_S:
          case BGT_S:
          case BLE_S:
          case BLT_S:
          case BGE_UN_S:
          case BGT_UN_S:
          case BLE_UN_S:
          case BLT_UN_S:
          case BNE_UN_S:
            if (binaryCompare(
                curOpcode, frame, topStack - 2, topStack - 1, getMethod().getOpCodeTypes()[pc])) {
              int targetPc = nextpc + bytecodeBuffer.getImmByte(pc);
              topStack += BytecodeInstructions.getStackEffect(curOpcode);
              pc = beforeJumpChecks(frame, pc, targetPc, topStack);
              continue;
            }
            break;

          case BR:
            {
              int targetPc = nextpc + bytecodeBuffer.getImmInt(pc);
              pc = beforeJumpChecks(frame, pc, targetPc, topStack);
              continue;
            }
          case BR_S:
            {
              int targetPc = nextpc + bytecodeBuffer.getImmByte(pc);
              pc = beforeJumpChecks(frame, pc, targetPc, topStack);
              continue;
            }
          case BRTRUE:
          case BRFALSE:
            if (shouldBranch(curOpcode, frame, topStack - 1, getMethod().getOpCodeTypes()[pc])) {
              int targetPc = nextpc + bytecodeBuffer.getImmInt(pc);
              topStack += BytecodeInstructions.getStackEffect(curOpcode);
              pc = beforeJumpChecks(frame, pc, targetPc, topStack);
              continue;
            }
            break;

          case BRTRUE_S:
          case BRFALSE_S:
            if (shouldBranch(curOpcode, frame, topStack - 1, getMethod().getOpCodeTypes()[pc])) {
              int targetPc = nextpc + bytecodeBuffer.getImmByte(pc);
              topStack += BytecodeInstructions.getStackEffect(curOpcode);
              pc = beforeJumpChecks(frame, pc, targetPc, topStack);
              continue;
            }
            break;

          case CEQ:
          case CGT:
          case CLT:
          case CGT_UN:
          case CLT_UN:
            binaryCompareAndPutOnTop(
                curOpcode, frame, topStack - 2, topStack - 1, getMethod().getOpCodeTypes()[pc]);
            break;

          case BREAK:
            // Inform a debugger that a breakpoint has been reached
            // This does not interest us at the moment
            break;

          case SWITCH:
            {
              var numCases = bytecodeBuffer.getImmUInt(pc);
              var stackValue = (long) CILOSTAZOLFrame.popInt32(frame, topStack - 1);
              if (stackValue >= 0 && stackValue <= numCases) {
                var caseOffset = pc + 4 + ((int) stackValue * 4);
                nextpc += bytecodeBuffer.getImmInt(caseOffset);
              }
              // else fall through
              break;
            }
          case RET:
            return getReturnValue(frame, topStack - 1);

          case JMP:
          case CALL:
          case CALLVIRT:
            {
              var methodToken = bytecodeBuffer.getImmToken(pc);
              topStack = nodeizeOpToken(frame, topStack, methodToken, pc, curOpcode);
              break;
            }
            // Store indirect
          case STIND_I1:
            storeIndirectByte(frame, topStack);
            break;
          case STIND_I2:
            storeIndirectShort(frame, topStack);
            break;
          case STIND_I4:
            storeIndirectInt(frame, topStack);
            break;
          case STIND_I8:
            storeIndirectLong(frame, topStack);
            break;
          case STIND_I:
            storeIndirectNative(frame, topStack);
            break;
          case STIND_R4:
            storeIndirectFloat(frame, topStack);
            break;
          case STIND_R8:
            storeIndirectDouble(frame, topStack);
            break;
          case STIND_REF:
            storeIndirectRef(frame, topStack);
            break;

            // Load indirect
          case LDIND_I1:
          case LDIND_U1:
            loadIndirectByte(frame, topStack);
            break;
          case LDIND_I2:
          case LDIND_U2:
            loadIndirectShort(frame, topStack);
            break;
          case LDIND_I4:
          case LDIND_U4:
            loadIndirectInt(frame, topStack);
            break;
          case LDIND_I8:
            loadIndirectLong(frame, topStack);
            break;
          case LDIND_I:
            loadIndirectNative(frame, topStack);
            break;
          case LDIND_R4:
            loadIndirectFloat(frame, topStack);
            break;
          case LDIND_R8:
            loadIndirectDouble(frame, topStack);
            break;
          case LDIND_REF:
            loadIndirectRef(frame, topStack);
            break;

            // array
          case NEWARR:
            createArray(frame, bytecodeBuffer.getImmToken(pc), topStack - 1);
            break;
          case LDLEN:
            getArrayLength(frame, topStack - 1);
            break;

          case LDELEM:
            {
              var arrType = CILOSTAZOLFrame.getLocalObject(frame, topStack - 2).getTypeSymbol();
              var elementSystemType = ((ArrayTypeSymbol) arrType).getElementType().getSystemType();
              var element = getJavaArrElem(frame, topStack - 1);
              switch (elementSystemType) {
                case Boolean -> CILOSTAZOLFrame.putInt32(
                    frame, topStack - 2, (boolean) element ? (byte) 1 : (byte) 0);
                case Byte -> CILOSTAZOLFrame.putInt32(frame, topStack - 2, (byte) element);
                case Char -> CILOSTAZOLFrame.putInt32(frame, topStack - 2, (char) element);
                case Short -> CILOSTAZOLFrame.putInt32(frame, topStack - 2, (short) element);
                case Int -> CILOSTAZOLFrame.putInt32(frame, topStack - 2, (int) element);
                case Long -> CILOSTAZOLFrame.putInt64(frame, topStack - 2, (long) element);
                case Float -> CILOSTAZOLFrame.putNativeFloat(frame, topStack - 2, (float) element);
                case Double -> CILOSTAZOLFrame.putNativeFloat(
                    frame, topStack - 2, (double) element);
                case Object -> CILOSTAZOLFrame.putObject(
                    frame, topStack - 2, (StaticObject) element);
                case Void -> throw new InterpreterException();
              }
              break;
            }
          case LDELEM_REF:
            CILOSTAZOLFrame.putObject(
                frame, topStack - 2, (StaticObject) getJavaArrElem(frame, topStack - 1));
            break;
          case LDELEM_I, LDELEM_U4, LDELEM_I4:
            CILOSTAZOLFrame.putInt32(
                frame, topStack - 2, (int) getJavaArrElem(frame, topStack - 1));
            break;
          case LDELEM_I1, LDELEM_U1:
            CILOSTAZOLFrame.putInt32(
                frame, topStack - 2, (byte) getJavaArrElem(frame, topStack - 1));
            break;
          case LDELEM_I2, LDELEM_U2:
            CILOSTAZOLFrame.putInt32(
                frame, topStack - 2, (short) getJavaArrElem(frame, topStack - 1));
            break;
          case LDELEM_I8:
            CILOSTAZOLFrame.putInt64(
                frame, topStack - 2, (long) getJavaArrElem(frame, topStack - 1));
            break;
          case LDELEM_R4:
            CILOSTAZOLFrame.putNativeFloat(
                frame, topStack - 2, (float) getJavaArrElem(frame, topStack - 1));
            break;
          case LDELEM_R8:
            CILOSTAZOLFrame.putNativeFloat(
                frame, topStack - 2, (double) getJavaArrElem(frame, topStack - 1));
            break;
          case LDELEMA:
            CILOSTAZOLFrame.putObject(
                frame,
                topStack - 2,
                getMethod()
                    .getContext()
                    .getAllocator()
                    .createArrayElementReference(
                        SymbolResolver.resolveReference(
                            ReferenceSymbol.ReferenceType.ArrayElement, getMethod().getContext()),
                        CILOSTAZOLFrame.popObject(frame, topStack - 2),
                        CILOSTAZOLFrame.popInt32(frame, topStack - 1)));
            break;

          case STELEM:
            try {
              var stackArray = CILOSTAZOLFrame.popObject(frame, topStack - 3);
              var array = getMethod().getContext().getArrayProperty().getObject(stackArray);
              var idx = CILOSTAZOLFrame.popInt32(frame, topStack - 2);
              switch (((ArrayTypeSymbol) (stackArray).getTypeSymbol())
                  .getElementType()
                  .getSystemType()) {
                case Boolean -> Array.set(
                    array, idx, CILOSTAZOLFrame.popInt32(frame, topStack - 1) != 0);
                case Byte -> Array.set(
                    array, idx, (byte) CILOSTAZOLFrame.popInt32(frame, topStack - 1));
                case Int -> Array.set(array, idx, CILOSTAZOLFrame.popInt32(frame, topStack - 1));
                case Short -> Array.set(
                    array, idx, (short) CILOSTAZOLFrame.popInt32(frame, topStack - 1));
                case Char -> Array.set(
                    array, idx, (char) CILOSTAZOLFrame.popInt32(frame, topStack - 1));
                case Float -> Array.set(
                    array, idx, (float) CILOSTAZOLFrame.popNativeFloat(frame, topStack - 1));
                case Long -> Array.set(array, idx, CILOSTAZOLFrame.popInt64(frame, topStack - 1));
                case Double -> Array.set(
                    array, idx, CILOSTAZOLFrame.popNativeFloat(frame, topStack - 1));
                case Void -> throw new InterpreterException();
                case Object -> Array.set(
                    array, idx, CILOSTAZOLFrame.popObject(frame, topStack - 1));
              }
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IndexOutOfBoundsException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.IndexOutOfRange,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;
          case STELEM_REF:
            try {
              Array.set(
                  getMethod()
                      .getContext()
                      .getArrayProperty()
                      .getObject(CILOSTAZOLFrame.popObject(frame, topStack - 3)),
                  CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                  CILOSTAZOLFrame.popObject(frame, topStack - 1));
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IndexOutOfBoundsException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.IndexOutOfRange,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;
          case STELEM_I, STELEM_I4:
            try {
              Array.set(
                  getMethod()
                      .getContext()
                      .getArrayProperty()
                      .getObject(CILOSTAZOLFrame.popObject(frame, topStack - 3)),
                  CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                  CILOSTAZOLFrame.popInt32(frame, topStack - 1));
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IndexOutOfBoundsException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.IndexOutOfRange,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;
          case STELEM_I1:
            try {
              Array.set(
                  getMethod()
                      .getContext()
                      .getArrayProperty()
                      .getObject(CILOSTAZOLFrame.popObject(frame, topStack - 3)),
                  CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                  (byte) CILOSTAZOLFrame.popInt32(frame, topStack - 1));
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IndexOutOfBoundsException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.IndexOutOfRange,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;
          case STELEM_I2:
            try {
              StaticObject array = CILOSTAZOLFrame.popObject(frame, topStack - 3);
              if (((ArrayTypeSymbol) array.getTypeSymbol())
                  .getElementType()
                  .equals(method.getContext().getChar())) {
                Array.set(
                    getMethod().getContext().getArrayProperty().getObject(array),
                    CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                    (char) CILOSTAZOLFrame.popInt32(frame, topStack - 1));
              } else {
                Array.set(
                    getMethod().getContext().getArrayProperty().getObject(array),
                    CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                    (short) CILOSTAZOLFrame.popInt32(frame, topStack - 1));
              }
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;
          case STELEM_I8:
            try {
              Array.set(
                  getMethod()
                      .getContext()
                      .getArrayProperty()
                      .getObject(CILOSTAZOLFrame.popObject(frame, topStack - 3)),
                  CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                  CILOSTAZOLFrame.popInt64(frame, topStack - 1));
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IndexOutOfBoundsException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.IndexOutOfRange,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;
          case STELEM_R4:
            try {
              Array.set(
                  getMethod()
                      .getContext()
                      .getArrayProperty()
                      .getObject(CILOSTAZOLFrame.popObject(frame, topStack - 3)),
                  CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                  (float) CILOSTAZOLFrame.popNativeFloat(frame, topStack - 1));
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IndexOutOfBoundsException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.IndexOutOfRange,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;
          case STELEM_R8:
            try {
              Array.set(
                  getMethod()
                      .getContext()
                      .getArrayProperty()
                      .getObject(CILOSTAZOLFrame.popObject(frame, topStack - 3)),
                  CILOSTAZOLFrame.popInt32(frame, topStack - 2),
                  CILOSTAZOLFrame.popNativeFloat(frame, topStack - 1));
            } catch (NullPointerException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IllegalArgumentException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ArrayTypeMismatch,
                  getMethod().getContext(),
                  frame,
                  topStack);
            } catch (IndexOutOfBoundsException ex) {
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.IndexOutOfRange,
                  getMethod().getContext(),
                  frame,
                  topStack);
            }
            break;

            // Conversion
          case CONV_I:
          case CONV_I1:
          case CONV_I2:
          case CONV_I4:
          case CONV_I8:
            convertFromSignedToInteger(
                curOpcode,
                frame,
                topStack - 1,
                getIntegerValueForConversion(
                    frame, topStack - 1, getMethod().getOpCodeTypes()[pc], true));
            break;
          case CONV_U:
          case CONV_U1:
          case CONV_U2:
          case CONV_U4:
          case CONV_U8:
            convertFromSignedToInteger(
                curOpcode,
                frame,
                topStack - 1,
                getIntegerValueForConversion(
                    frame, topStack - 1, getMethod().getOpCodeTypes()[pc], false));
            break;

          case CONV_R4:
          case CONV_R8:
          case CONV_R_UN:
            convertToFloat(curOpcode, frame, topStack - 1, getMethod().getOpCodeTypes()[pc]);
            break;

          case CONV_OVF_I1:
          case CONV_OVF_I2:
          case CONV_OVF_I4:
          case CONV_OVF_I8:
          case CONV_OVF_I:
            convertFromSignedToIntegerAndCheckOverflow(
                curOpcode,
                frame,
                topStack - 1,
                getIntegerValueForConversion(
                    frame, topStack - 1, getMethod().getOpCodeTypes()[pc], true));
            break;

          case CONV_OVF_U1:
          case CONV_OVF_U2:
          case CONV_OVF_U4:
          case CONV_OVF_U8:
          case CONV_OVF_U:
          case CONV_OVF_I1_UN:
          case CONV_OVF_I2_UN:
          case CONV_OVF_I4_UN:
          case CONV_OVF_I8_UN:
          case CONV_OVF_I_UN:
          case CONV_OVF_U1_UN:
          case CONV_OVF_U2_UN:
          case CONV_OVF_U4_UN:
          case CONV_OVF_U8_UN:
          case CONV_OVF_U_UN:
            convertFromSignedToIntegerAndCheckOverflow(
                curOpcode,
                frame,
                topStack - 1,
                getIntegerValueForConversion(
                    frame, topStack - 1, getMethod().getOpCodeTypes()[pc], false));
            break;

            //  arithmetics
          case ADD:
          case DIV:
          case MUL:
          case REM:
          case SUB:
            doNumericBinary(frame, topStack, curOpcode, getMethod().getOpCodeTypes()[pc]);
            break;

          case OR:
          case AND:
          case XOR:
            doIntegerBinary(frame, topStack, curOpcode, getMethod().getOpCodeTypes()[pc]);
            break;

          case NEG:
            doNeg(frame, topStack, getMethod().getOpCodeTypes()[pc]);
            break;
          case NOT:
            doNot(frame, topStack, getMethod().getOpCodeTypes()[pc]);
            break;

          case SHL:
          case SHR:
          case SHR_UN:
            doShiftBinary(frame, topStack, curOpcode, getMethod().getOpCodeTypes()[pc]);
            break;

          case ADD_OVF:
          case MUL_OVF:
          case SUB_OVF:
            doNumericBinary(frame, topStack, curOpcode, getMethod().getOpCodeTypes()[pc]);
            break;
          case ADD_OVF_UN:
          case SUB_OVF_UN:
          case MUL_OVF_UN:
            doNumericBinary(frame, topStack, curOpcode, getMethod().getOpCodeTypes()[pc]);
            break;

          case DIV_UN:
          case REM_UN:
            doNumericBinary(frame, topStack, curOpcode, getMethod().getOpCodeTypes()[pc]);
            break;

            // Unmanaged memory manipulation
          case INITBLK:
          case CPBLK:
          case LDFTN:
          case LDVIRTFTN:
          case LOCALLOC:
          case LDTOKEN:
            throw new InterpreterException(
                "Unmanaged memory manipulation not implemented for opcode " + curOpcode);

            // exceptions
          case THROW:
            var exObj = CILOSTAZOLFrame.popObject(frame, topStack - 1);
            if (exObj.equals(StaticObject.NULL))
              throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.NullReference,
                  getMethod().getContext(),
                  frame,
                  topStack);
            throw RuntimeCILException.RuntimeCILExceptionFactory.create(exObj);
          case RETHROW:
            throw currentEx;
          case ENDFINALLY:
            if (currentEx != null) throw currentEx;
            break;
          case LEAVE:
            {
              currentEx = null;
              var fin = getNearestFinally(pc);
              if (fin != null) nextpc = fin.getHandlerOffset();
              else nextpc += bytecodeBuffer.getImmInt(pc);
            }
            break;
          case LEAVE_S:
            {
              currentEx = null;
              var fin = getNearestFinally(pc);
              if (fin != null) pc = fin.getHandlerOffset();
              else nextpc += bytecodeBuffer.getImmByte(pc);
            }
            break;

          case TRUFFLE_NODE:
            topStack = nodes[bytecodeBuffer.getImmInt(pc)].execute(frame);
            break;

          default:
            LogUnsupportedOpcode(curOpcode);
            break;
        }

        topStack += BytecodeInstructions.getStackEffect(curOpcode);
        pc = nextpc;
      } catch (OSRReturnException e) {
        return e.getResultOrRethrow();
      } catch (Exception ex) {
        RuntimeCILException cilEx = null;
        if (!(ex instanceof RuntimeCILException))
          cilEx =
              RuntimeCILException.RuntimeCILExceptionFactory.create(
                  RuntimeCILException.Exception.ExecutionEngine,
                  getMethod().getContext(),
                  frame,
                  topStack);
        else cilEx = (RuntimeCILException) ex;

        topStack += BytecodeInstructions.getStackEffect(curOpcode);
        CILOSTAZOLFrame.clearEvaluationStack(frame, topStack - 1, getMethod());
        topStack = CILOSTAZOLFrame.getStartStackOffset(getMethod());
        ExceptionHandlerSymbol handler = getNearestHandler(pc, cilEx);
        if (handler == null) {
          throw cilEx;
        } else {
          if (handler
              .getFlags()
              .hasFlag(
                  ExceptionHandlerSymbol.ExceptionClauseFlags.Flag
                      .COR_ILEXCEPTION_CLAUSE_EXCEPTION)) {
            CILOSTAZOLFrame.putObject(frame, topStack, cilEx.getException());
            currentEx = cilEx;
            pc = handler.getHandlerOffset();
          } else if (handler
              .getFlags()
              .hasFlag(
                  ExceptionHandlerSymbol.ExceptionClauseFlags.Flag
                      .COR_ILEXCEPTION_CLAUSE_FINALLY)) {
            currentEx = cilEx;
            pc = handler.getHandlerOffset();
          } else {
            throw new InterpreterException();
          }
        }
      }
    }
  }

  // region exception
  private ExceptionHandlerSymbol getNearestHandler(int pc, RuntimeCILException ex) {
    var handlers = getMethod().getExceptionHandlers();
    for (var handler : handlers) {
      if (isSatisfied(handler, ex, pc)) {
        return handler;
      }
    }

    return null;
  }

  private ExceptionHandlerSymbol getNearestFinally(int pc) {
    var handlers = getMethod().getExceptionHandlers();
    for (var handler : handlers) {
      if (handler.getTryOffset() <= pc
          && handler.getTryOffset() + handler.getTryLength() > pc
          && handler
              .getFlags()
              .hasFlag(
                  ExceptionHandlerSymbol.ExceptionClauseFlags.Flag
                      .COR_ILEXCEPTION_CLAUSE_FINALLY)) {
        return handler;
      }
    }

    return null;
  }

  private boolean isSatisfied(ExceptionHandlerSymbol handler, RuntimeCILException ex, int pc) {
    if (handler.getTryOffset() > pc || handler.getTryOffset() + handler.getTryLength() <= pc) {
      return false;
    }

    if (handler
            .getFlags()
            .hasFlag(ExceptionHandlerSymbol.ExceptionClauseFlags.Flag.COR_ILEXCEPTION_CLAUSE_FAULT)
        || handler
            .getFlags()
            .hasFlag(
                ExceptionHandlerSymbol.ExceptionClauseFlags.Flag.COR_ILEXCEPTION_CLAUSE_FILTER))
      throw new InterpreterException();

    return !handler
            .getFlags()
            .hasFlag(
                ExceptionHandlerSymbol.ExceptionClauseFlags.Flag.COR_ILEXCEPTION_CLAUSE_EXCEPTION)
        || handler.getHandlerException().isAssignableFrom(ex.getException().getTypeSymbol());
  }
  // endregion

  @CompilerDirectives.TruffleBoundary
  private void LogUnsupportedOpcode(int curOpcode) {
    System.err.println(
        CILOSTAZOLBundle.message("cilostazol.exception.not.supported.OpCode", curOpcode));
  }

  // region arithmetics
  private int doNumericBinary(int op1, int op2, int opcode, VirtualFrame frame, int tp) {
    return switch (opcode) {
      case ADD:
        yield op1 + op2;
      case SUB:
        yield op1 - op2;
      case MUL:
        yield op1 * op2;
      case DIV:
        try {
          yield op1 / op2;
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case REM:
        try {
          yield op1 % op2;
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case ADD_OVF:
        try {
          yield Math.addExact(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case ADD_OVF_UN:
        {
          var res = op1 + op2;
          if (Integer.compareUnsigned(res, op1) < 0)
            throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case MUL_OVF:
        try {
          yield Math.multiplyExact(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case MUL_OVF_UN:
        {
          var res = op1 * op2;
          if (Integer.divideUnsigned(res, op2) != op1)
            throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case SUB_OVF:
        try {
          yield Math.subtractExact(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case SUB_OVF_UN:
        {
          var res = op1 - op2;
          if (Integer.compareUnsigned(res, op1) > 0)
            throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case DIV_UN:
        try {
          yield Integer.divideUnsigned(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case REM_UN:
        try {
          yield Integer.remainderUnsigned(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      default:
        throw new InterpreterException();
    };
  }

  private long doNumericBinary(long op1, long op2, int opcode, VirtualFrame frame, int tp) {
    return switch (opcode) {
      case ADD:
        yield op1 + op2;
      case SUB:
        yield op1 - op2;
      case MUL:
        yield op1 * op2;
      case DIV:
        try {
          yield op1 / op2;
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case REM:
        try {
          yield op1 % op2;
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case ADD_OVF:
        try {
          yield Math.addExact(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case ADD_OVF_UN:
        {
          var res = op1 + op2;
          if (Long.compareUnsigned(res, op1) < 0)
            throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case MUL_OVF:
        try {
          yield Math.multiplyExact(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case MUL_OVF_UN:
        {
          var res = op1 * op2;
          if (Long.divideUnsigned(res, op2) != op1)
            throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case SUB_OVF:
        try {
          yield Math.subtractExact(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case SUB_OVF_UN:
        {
          var res = op1 - op2;
          if (Long.compareUnsigned(res, op1) > 0)
            throw RuntimeCILException.RuntimeCILExceptionFactory.create(
                RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        }
      case DIV_UN:
        try {
          yield Long.divideUnsigned(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case REM_UN:
        try {
          yield Long.remainderUnsigned(op1, op2);
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      default:
        throw new InterpreterException();
    };
  }

  private double doNumericBinary(double op1, double op2, int opcode, VirtualFrame frame, int tp) {
    return switch (opcode) {
      case ADD:
        yield op1 + op2;
      case ADD_OVF:
        var result = op1 + op2;
        if (Double.isInfinite(result))
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        yield result;
      case DIV:
        try {
          yield op1 / op2;
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case MUL:
        yield op1 * op2;
      case MUL_OVF:
        result = op1 * op2;
        if (Double.isInfinite(result))
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        yield result;
      case REM:
        try {
          yield op1 % op2;
        } catch (Exception ex) {
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.DivideByZero, getMethod().getContext(), frame, tp);
        }
      case SUB_OVF:
        result = op1 - op2;
        if (Double.isInfinite(result))
          throw RuntimeCILException.RuntimeCILExceptionFactory.create(
              RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, tp);
        yield result;
      case SUB:
        yield op1 - op2;
      default:
        throw new InterpreterException();
    };
  }

  private int doIntegerBinary(int op1, int op2, int opcode) {
    return switch (opcode) {
      case AND:
        yield op1 & op2;
      case OR:
        yield op1 | op2;
      case XOR:
        yield op1 ^ op2;
      default:
        throw new InterpreterException();
    };
  }

  private long doIntegerBinary(long op1, long op2, int opcode) {
    return switch (opcode) {
      case AND:
        yield op1 & op2;
      case OR:
        yield op1 | op2;
      case XOR:
        yield op1 ^ op2;
      default:
        throw new InterpreterException();
    };
  }

  private void doIntegerBinary(
      VirtualFrame frame, int top, int opcode, StaticOpCodeAnalyser.OpCodeType type) {
    switch (type) {
      case Int32 -> {
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popInt32(frame, top - 2);
        CILOSTAZOLFrame.putInt32(frame, top - 2, doIntegerBinary(op1, op2, opcode));
      }
      case Int64 -> {
        final var op1 = CILOSTAZOLFrame.popInt64(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popInt64(frame, top - 2);
        CILOSTAZOLFrame.putInt64(frame, top - 2, doIntegerBinary(op1, op2, opcode));
      }
      case NativeInt -> {
        final var op1 = CILOSTAZOLFrame.popNativeInt(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popNativeInt(frame, top - 2);
        CILOSTAZOLFrame.putNativeInt(frame, top - 2, doIntegerBinary(op1, op2, opcode));
      }
      case Int32_NativeInt -> {
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popNativeInt(frame, top - 2);
        CILOSTAZOLFrame.putNativeInt(frame, top - 2, doIntegerBinary(op1, op2, opcode));
      }
      case NativeInt_Int32 -> {
        final var op1 = CILOSTAZOLFrame.popNativeInt(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popInt32(frame, top - 2);
        CILOSTAZOLFrame.putNativeInt(frame, top - 2, doIntegerBinary(op1, op2, opcode));
      }
      default -> throw new InterpreterException();
    }
  }

  private void doNumericBinary(
      VirtualFrame frame, int top, int opcode, StaticOpCodeAnalyser.OpCodeType type) {
    switch (type) {
      case Int32 -> {
        final var op2 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 2);
        CILOSTAZOLFrame.putInt32(frame, top - 2, doNumericBinary(op1, op2, opcode, frame, top));
      }
      case Int64 -> {
        final var op2 = CILOSTAZOLFrame.popInt64(frame, top - 1);
        final var op1 = CILOSTAZOLFrame.popInt64(frame, top - 2);
        CILOSTAZOLFrame.putInt64(frame, top - 2, doNumericBinary(op1, op2, opcode, frame, top));
      }
      case NativeInt -> {
        final var op2 = CILOSTAZOLFrame.popNativeInt(frame, top - 1);
        final var op1 = CILOSTAZOLFrame.popNativeInt(frame, top - 2);
        CILOSTAZOLFrame.putNativeInt(frame, top - 2, doNumericBinary(op1, op2, opcode, frame, top));
      }
      case NativeFloat -> {
        final var op2 = CILOSTAZOLFrame.popNativeFloat(frame, top - 1);
        final var op1 = CILOSTAZOLFrame.popNativeFloat(frame, top - 2);
        CILOSTAZOLFrame.putNativeFloat(
            frame, top - 2, doNumericBinary(op1, op2, opcode, frame, top));
      }
      case Int32_NativeInt -> {
        final var op2 = CILOSTAZOLFrame.popNativeInt(frame, top - 1);
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 2);
        CILOSTAZOLFrame.putNativeInt(frame, top - 2, doNumericBinary(op1, op2, opcode, frame, top));
      }
      case NativeInt_Int32 -> {
        final var op2 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op1 = CILOSTAZOLFrame.popNativeInt(frame, top - 2);
        CILOSTAZOLFrame.putNativeInt(frame, top - 2, doNumericBinary(op1, op2, opcode, frame, top));
      }
      default -> throw new InterpreterException();
    }
  }

  private int doShiftBinary(int value, int amount, int opcode) {
    return switch (opcode) {
      case SHL -> value << amount;
      case SHR -> value >> amount;
      case SHR_UN -> value >>> amount;
      default -> throw new InterpreterException();
    };
  }

  private long doShiftBinary(long value, int amount, int opcode) {
    return switch (opcode) {
      case SHL -> value << amount;
      case SHR -> value >> amount;
      case SHR_UN -> value >>> amount;
      default -> throw new InterpreterException();
    };
  }

  private void doShiftBinary(
      VirtualFrame frame, int top, int opcode, StaticOpCodeAnalyser.OpCodeType type) {
    switch (type) {
      case Int32 -> {
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popInt32(frame, top - 2);
        CILOSTAZOLFrame.putInt32(frame, top - 2, doShiftBinary(op2, op1, opcode));
      }
      case NativeInt -> {
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popInt64(frame, top - 2);
        CILOSTAZOLFrame.putInt64(frame, top - 2, doShiftBinary(op2, op1, opcode));
      }
      case Int64_Int32 -> {
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popInt64(frame, top - 2);
        CILOSTAZOLFrame.putInt64(frame, top - 2, doShiftBinary(op2, op1, opcode));
      }
      case NativeInt_Int32 -> {
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popNativeInt(frame, top - 2);
        CILOSTAZOLFrame.putInt32(frame, top - 2, doShiftBinary(op2, op1, opcode));
      }
      case Int32_NativeInt -> {
        final var op1 = CILOSTAZOLFrame.popInt32(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popNativeInt(frame, top - 2);
        CILOSTAZOLFrame.putInt64(frame, top - 2, doShiftBinary(op2, op1, opcode));
      }
      case Int64_NativeInt -> {
        final var op1 = CILOSTAZOLFrame.popNativeInt(frame, top - 1);
        final var op2 = CILOSTAZOLFrame.popInt64(frame, top - 2);
        CILOSTAZOLFrame.putInt64(frame, top - 2, doShiftBinary(op2, op1, opcode));
      }

      default -> throw new InterpreterException();
    }
  }

  private void doNot(VirtualFrame frame, int top, StaticOpCodeAnalyser.OpCodeType type) {
    switch (type) {
      case Int32 -> CILOSTAZOLFrame.putInt32(
          frame, top - 1, ~CILOSTAZOLFrame.popInt32(frame, top - 1));
      case Int64 -> CILOSTAZOLFrame.putInt64(
          frame, top - 1, ~CILOSTAZOLFrame.popInt64(frame, top - 1));
      case NativeInt -> CILOSTAZOLFrame.putNativeInt(
          frame, top - 1, ~CILOSTAZOLFrame.popNativeInt(frame, top - 1));
      default -> throw new InterpreterException();
    }
  }

  private void doNeg(VirtualFrame frame, int top, StaticOpCodeAnalyser.OpCodeType type) {
    switch (type) {
      case Int32 -> CILOSTAZOLFrame.putInt32(
          frame, top - 1, -CILOSTAZOLFrame.popInt32(frame, top - 1));
      case Int64 -> CILOSTAZOLFrame.putInt64(
          frame, top - 1, -CILOSTAZOLFrame.popInt64(frame, top - 1));
      case NativeInt -> CILOSTAZOLFrame.putNativeInt(
          frame, top - 1, -CILOSTAZOLFrame.popNativeInt(frame, top - 1));
      case NativeFloat -> CILOSTAZOLFrame.putNativeFloat(
          frame, top - 1, -CILOSTAZOLFrame.popNativeFloat(frame, top - 1));
      default -> throw new InterpreterException();
    }
  }
  // endregion

  // region array
  private void createArray(VirtualFrame frame, CLITablePtr token, int top) {
    TypeSymbol elemType =
        SymbolResolver.resolveType(
            token,
            getMethod().getTypeArguments(),
            getMethod().getDefiningType().getTypeArguments(),
            getMethod().getModule());
    int num = CILOSTAZOLFrame.popInt32(frame, top);
    if (num < 0)
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, top);
    var arrayType = SymbolResolver.resolveArray(elemType, getMethod().getContext());
    StaticObject object = null;
    try {
      object = getMethod().getContext().getArrayShape().getFactory().create(arrayType);
    } catch (Exception ex) {
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.OutOfMemory, getMethod().getContext(), frame, top);
    }
    Object value =
        switch (elemType.getSystemType()) {
          case Boolean -> new boolean[num];
          case Char -> new char[num];
          case Int -> new int[num];
          case Byte -> new byte[num];
          case Short -> new short[num];
          case Float -> new float[num];
          case Long -> new long[num];
          case Double -> new double[num];
          case Void -> throw new InterpreterException();
          case Object -> {
            var res = new StaticObject[num];
            Arrays.fill(res, StaticObject.NULL);
            yield res;
          }
        };
    getMethod().getContext().getArrayProperty().setObject(object, value);
    CILOSTAZOLFrame.putObject(frame, top, object);
  }

  private void getArrayLength(VirtualFrame frame, int top) {
    StaticObject arr = CILOSTAZOLFrame.popObject(frame, top);
    Object javaArr = getMethod().getContext().getArrayProperty().getObject(arr);
    if (javaArr.equals(StaticObject.NULL))
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.NullReference, getMethod().getContext(), frame, top);
    CILOSTAZOLFrame.putInt32(frame, top, Array.getLength(javaArr));
  }

  private Object getJavaArrElem(VirtualFrame frame, int top) {
    var idx = CILOSTAZOLFrame.popInt32(frame, top);
    var arr = CILOSTAZOLFrame.popObject(frame, top - 1);
    if (arr.equals(StaticObject.NULL))
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.NullReference, getMethod().getContext(), frame, top);
    try {
      return Array.get(getMethod().getContext().getArrayProperty().getObject(arr), idx);
    } catch (IndexOutOfBoundsException ex) {
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.IndexOutOfRange, getMethod().getContext(), frame, top);
    }
  }
  // endregion

  // region indirect
  private void loadIndirectByte(VirtualFrame frame, int top) {
    var reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    int value = IndirectLoader.loadByteFromReference(reference, getMethod().getContext());
    CILOSTAZOLFrame.putInt32(frame, top - 1, value);
  }

  private void loadIndirectShort(VirtualFrame frame, int top) {
    var reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    int value = IndirectLoader.loadShortFromReference(reference, getMethod().getContext());
    CILOSTAZOLFrame.putInt32(frame, top - 1, value);
  }

  private void loadIndirectInt(VirtualFrame frame, int top) {
    var reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    int value = IndirectLoader.loadInt32FromReference(reference, getMethod().getContext());
    CILOSTAZOLFrame.putInt32(frame, top - 1, value);
  }

  private void loadIndirectLong(VirtualFrame frame, int top) {
    var reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    long value = IndirectLoader.loadInt64FromReference(reference, getMethod().getContext());
    CILOSTAZOLFrame.putInt64(frame, top - 1, value);
  }

  private void loadIndirectFloat(VirtualFrame frame, int top) {
    var reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    double value = IndirectLoader.loadFloatFromReference(reference, getMethod().getContext());
    CILOSTAZOLFrame.putNativeFloat(frame, top - 1, value);
  }

  private void loadIndirectDouble(VirtualFrame frame, int top) {
    var reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    double value = IndirectLoader.loadDoubleFromReference(reference, getMethod().getContext());
    CILOSTAZOLFrame.putNativeFloat(frame, top - 1, value);
  }

  private void loadIndirectRef(VirtualFrame frame, int top) {
    var reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    StaticObject ref = IndirectLoader.loadRef(reference, getMethod().getContext());
    CILOSTAZOLFrame.putObject(frame, top - 1, ref);
  }

  private void loadIndirectNative(VirtualFrame frame, int top) {
    loadIndirectInt(frame, top);
  }

  private void storeIndirectByte(VirtualFrame frame, int top) {
    var value = (byte) CILOSTAZOLFrame.popInt32(frame, top - 1);
    var reference = CILOSTAZOLFrame.popObject(frame, top - 2);
    var referenceType = (ReferenceSymbol) reference.getTypeSymbol();
    switch (referenceType.getReferenceType()) {
      case Local, Argument -> {
        Frame refFrame =
            (Frame) getMethod().getContext().getStackReferenceFrameProperty().getObject(reference);
        int index = getMethod().getContext().getStackReferenceIndexProperty().getInt(reference);
        CILOSTAZOLFrame.putInt32(refFrame, index, value);
      }
      case Field -> {
        StaticObject refObj =
            (StaticObject)
                getMethod().getContext().getFieldReferenceObjectProperty().getObject(reference);
        StaticProperty refField =
            (StaticProperty)
                getMethod().getContext().getFieldReferenceFieldProperty().getObject(reference);
        refField.setByte(refObj, value);
      }
      case ArrayElement -> {
        StaticObject refArr =
            (StaticObject)
                getMethod()
                    .getContext()
                    .getArrayElementReferenceArrayProperty()
                    .getObject(reference);
        int index = getMethod().getContext().getArrayElementReferenceIndexProperty().getInt(refArr);
        var javaArr = getMethod().getContext().getArrayProperty().getObject(refArr);
        Array.setByte(javaArr, index, value);
      }
    }
  }

  private void storeIndirectShort(VirtualFrame frame, int top) {
    var value = (short) CILOSTAZOLFrame.popInt32(frame, top - 1);
    var reference = CILOSTAZOLFrame.popObject(frame, top - 2);
    var referenceType = (ReferenceSymbol) reference.getTypeSymbol();
    switch (referenceType.getReferenceType()) {
      case Local, Argument -> {
        Frame refFrame =
            (Frame) getMethod().getContext().getStackReferenceFrameProperty().getObject(reference);
        int index = getMethod().getContext().getStackReferenceIndexProperty().getInt(reference);
        CILOSTAZOLFrame.putInt32(refFrame, index, value);
      }
      case Field -> {
        StaticObject refObj =
            (StaticObject)
                getMethod().getContext().getFieldReferenceObjectProperty().getObject(reference);
        StaticProperty refField =
            (StaticProperty)
                getMethod().getContext().getFieldReferenceFieldProperty().getObject(reference);
        refField.setShort(refObj, value);
      }
      case ArrayElement -> {
        StaticObject refArr =
            (StaticObject)
                getMethod()
                    .getContext()
                    .getArrayElementReferenceArrayProperty()
                    .getObject(reference);
        int index = getMethod().getContext().getArrayElementReferenceIndexProperty().getInt(refArr);
        var javaArr = getMethod().getContext().getArrayProperty().getObject(refArr);
        Array.setShort(javaArr, index, value);
      }
    }
  }

  private void storeIndirectInt(VirtualFrame frame, int top) {
    var value = CILOSTAZOLFrame.popInt32(frame, top - 1);
    var reference = CILOSTAZOLFrame.popObject(frame, top - 2);
    var referenceType = (ReferenceSymbol) reference.getTypeSymbol();
    switch (referenceType.getReferenceType()) {
      case Local, Argument -> {
        Frame refFrame =
            (Frame) getMethod().getContext().getStackReferenceFrameProperty().getObject(reference);
        int index = getMethod().getContext().getStackReferenceIndexProperty().getInt(reference);
        CILOSTAZOLFrame.putInt32(refFrame, index, value);
      }
      case Field -> {
        StaticObject refObj =
            (StaticObject)
                getMethod().getContext().getFieldReferenceObjectProperty().getObject(reference);
        StaticProperty refField =
            (StaticProperty)
                getMethod().getContext().getFieldReferenceFieldProperty().getObject(reference);
        refField.setInt(refObj, value);
      }
      case ArrayElement -> {
        StaticObject refArr =
            (StaticObject)
                getMethod()
                    .getContext()
                    .getArrayElementReferenceArrayProperty()
                    .getObject(reference);
        int index =
            getMethod().getContext().getArrayElementReferenceIndexProperty().getInt(reference);
        var javaArr = getMethod().getContext().getArrayProperty().getObject(refArr);
        Array.setInt(javaArr, index, value);
      }
    }
  }

  private void storeIndirectLong(VirtualFrame frame, int top) {
    var value = CILOSTAZOLFrame.popInt64(frame, top - 1);
    var reference = CILOSTAZOLFrame.popObject(frame, top - 2);
    var referenceType = (ReferenceSymbol) reference.getTypeSymbol();
    switch (referenceType.getReferenceType()) {
      case Local, Argument -> {
        Frame refFrame =
            (Frame) getMethod().getContext().getStackReferenceFrameProperty().getObject(reference);
        int index = getMethod().getContext().getStackReferenceIndexProperty().getInt(reference);
        CILOSTAZOLFrame.putInt64(refFrame, index, value);
      }
      case Field -> {
        StaticObject refObj =
            (StaticObject)
                getMethod().getContext().getFieldReferenceObjectProperty().getObject(reference);
        StaticProperty refField =
            (StaticProperty)
                getMethod().getContext().getFieldReferenceFieldProperty().getObject(reference);
        refField.setLong(refObj, value);
      }
      case ArrayElement -> {
        StaticObject refArr =
            (StaticObject)
                getMethod()
                    .getContext()
                    .getArrayElementReferenceArrayProperty()
                    .getObject(reference);
        int index = getMethod().getContext().getArrayElementReferenceIndexProperty().getInt(refArr);
        var javaArr = getMethod().getContext().getArrayProperty().getObject(refArr);
        Array.setLong(javaArr, index, value);
      }
    }
  }

  private void storeIndirectFloat(VirtualFrame frame, int top) {
    var value = (float) CILOSTAZOLFrame.popNativeFloat(frame, top - 1);
    var reference = CILOSTAZOLFrame.popObject(frame, top - 2);
    var referenceType = (ReferenceSymbol) reference.getTypeSymbol();
    switch (referenceType.getReferenceType()) {
      case Local, Argument -> {
        Frame refFrame =
            (Frame) getMethod().getContext().getStackReferenceFrameProperty().getObject(reference);
        int index = getMethod().getContext().getStackReferenceIndexProperty().getInt(reference);
        CILOSTAZOLFrame.putNativeFloat(refFrame, index, value);
      }
      case Field -> {
        StaticObject refObj =
            (StaticObject)
                getMethod().getContext().getFieldReferenceObjectProperty().getObject(reference);
        StaticProperty refField =
            (StaticProperty)
                getMethod().getContext().getFieldReferenceFieldProperty().getObject(reference);
        refField.setFloat(refObj, value);
      }
      case ArrayElement -> {
        StaticObject refArr =
            (StaticObject)
                getMethod()
                    .getContext()
                    .getArrayElementReferenceArrayProperty()
                    .getObject(reference);
        int index = getMethod().getContext().getArrayElementReferenceIndexProperty().getInt(refArr);
        var javaArr = getMethod().getContext().getArrayProperty().getObject(refArr);
        Array.setFloat(javaArr, index, value);
      }
    }
  }

  private void storeIndirectDouble(VirtualFrame frame, int top) {
    var value = CILOSTAZOLFrame.popNativeFloat(frame, top - 1);
    var reference = CILOSTAZOLFrame.popObject(frame, top - 2);
    var referenceType = (ReferenceSymbol) reference.getTypeSymbol();
    switch (referenceType.getReferenceType()) {
      case Local, Argument -> {
        Frame refFrame =
            (Frame) getMethod().getContext().getStackReferenceFrameProperty().getObject(reference);
        int index = getMethod().getContext().getStackReferenceIndexProperty().getInt(reference);
        CILOSTAZOLFrame.putNativeFloat(refFrame, index, value);
      }
      case Field -> {
        StaticObject refObj =
            (StaticObject)
                getMethod().getContext().getFieldReferenceObjectProperty().getObject(reference);
        StaticProperty refField =
            (StaticProperty)
                getMethod().getContext().getFieldReferenceFieldProperty().getObject(reference);
        refField.setDouble(refObj, value);
      }
      case ArrayElement -> {
        StaticObject refArr =
            (StaticObject)
                getMethod()
                    .getContext()
                    .getArrayElementReferenceArrayProperty()
                    .getObject(reference);
        int index = getMethod().getContext().getArrayElementReferenceIndexProperty().getInt(refArr);
        var javaArr = getMethod().getContext().getArrayProperty().getObject(refArr);
        Array.setDouble(javaArr, index, value);
      }
    }
  }

  private void storeIndirectRef(VirtualFrame frame, int top) {
    var value = CILOSTAZOLFrame.popObject(frame, top - 1);
    var reference = CILOSTAZOLFrame.popObject(frame, top - 2);
    var referenceType = (ReferenceSymbol) reference.getTypeSymbol();
    switch (referenceType.getReferenceType()) {
      case Local, Argument -> {
        Frame refFrame =
            (Frame) getMethod().getContext().getStackReferenceFrameProperty().getObject(reference);
        int index = getMethod().getContext().getStackReferenceIndexProperty().getInt(reference);
        CILOSTAZOLFrame.putObject(refFrame, index, value);
      }
      case Field -> {
        StaticObject refObj =
            (StaticObject)
                getMethod().getContext().getFieldReferenceObjectProperty().getObject(reference);
        StaticProperty refField =
            (StaticProperty)
                getMethod().getContext().getFieldReferenceFieldProperty().getObject(reference);
        refField.setObject(refObj, value);
      }
      case ArrayElement -> {
        StaticObject refArr =
            (StaticObject)
                getMethod()
                    .getContext()
                    .getArrayElementReferenceArrayProperty()
                    .getObject(reference);
        int index = getMethod().getContext().getArrayElementReferenceIndexProperty().getInt(refArr);
        var javaArr = getMethod().getContext().getArrayProperty().getObject(refArr);
        Array.set(javaArr, index, value);
      }
    }
  }

  private void storeIndirectNative(VirtualFrame frame, int top) {
    storeIndirectInt(frame, top);
  }

  private void makeTypedRef(VirtualFrame frame, int top, CLITablePtr token) {
    StaticObject reference = CILOSTAZOLFrame.popObject(frame, top - 1);
    StaticObject typedReference =
        getMethod()
            .getContext()
            .getAllocator()
            .createTypedReference(
                SymbolResolver.resolveReference(
                    ReferenceSymbol.ReferenceType.Typed, getMethod().getContext()),
                reference,
                token);
    CILOSTAZOLFrame.putObject(frame, top - 1, typedReference);
  }

  private void getTypeFromTypedRef(VirtualFrame frame, int top) {
    StaticObject typedReference = CILOSTAZOLFrame.popObject(frame, top - 1);
    CLITablePtr token =
        (CLITablePtr)
            getMethod().getContext().getTypedReferenceTypeTokenProperty().getObject(typedReference);
    CILOSTAZOLFrame.putInt32(frame, top - 1, token.getToken());
  }

  private void getRefFromTypedRef(VirtualFrame frame, int top, CLITablePtr token) {
    StaticObject typedReference = CILOSTAZOLFrame.popObject(frame, top - 1);
    CLITablePtr constructingToken =
        (CLITablePtr)
            getMethod().getContext().getTypedReferenceTypeTokenProperty().getObject(typedReference);
    if (token != constructingToken) {
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.InvalidCast, getMethod().getContext(), frame, top);
    }
    CILOSTAZOLFrame.putObject(
        frame,
        top - 1,
        (StaticObject)
            getMethod().getContext().getTypedReferenceInnerRefProperty().getObject(typedReference));
  }
  // endregion

  // region other helpers
  private void pop(VirtualFrame frame, int top, StaticOpCodeAnalyser.OpCodeType type) {
    switch (type) {
      case Int32 -> CILOSTAZOLFrame.popInt32(frame, top - 1);
      case Int64 -> CILOSTAZOLFrame.popInt64(frame, top - 1);
      case Object -> CILOSTAZOLFrame.popObject(frame, top - 1);
      case ManagedPointer -> CILOSTAZOLFrame.popObject(frame, top - 1);
      case NativeInt -> CILOSTAZOLFrame.popNativeInt(frame, top - 1);
      case NativeFloat -> CILOSTAZOLFrame.popNativeFloat(frame, top - 1);
      default -> throw new InterpreterException();
    }
  }

  private void loadString(VirtualFrame frame, int top, CLITablePtr token) {
    CLIUSHeapPtr ptr = new CLIUSHeapPtr(token.getRowNo());
    String value = ptr.readString(method.getModule().getDefiningFile().getUSHeap());
    CILOSTAZOLFrame.putObject(
        frame, top, getMethod().getContext().getAllocator().createString(value, frame, top));
  }

  private void duplicateSlot(VirtualFrame frame, int top) {
    CILOSTAZOLFrame.copyStatic(frame, top - 1, top);
  }

  private void loadArgument(VirtualFrame frame, int index, int topStack) {
    CILOSTAZOLFrame.putObject(
        frame,
        topStack,
        getMethod()
            .getContext()
            .getAllocator()
            .createStackReference(
                SymbolResolver.resolveReference(
                    ReferenceSymbol.ReferenceType.Argument, getMethod().getContext()),
                frame,
                index + CILOSTAZOLFrame.getStartArgsOffset(getMethod())));
  }

  private void loadLocalIndirect(VirtualFrame frame, int slot, int topStack) {
    CILOSTAZOLFrame.putObject(
        frame,
        topStack,
        getMethod()
            .getContext()
            .getAllocator()
            .createStackReference(
                SymbolResolver.resolveReference(
                    ReferenceSymbol.ReferenceType.Local, getMethod().getContext()),
                frame,
                slot));
  }

  private Object getReturnValue(VirtualFrame frame, int top) {
    if (getMethod().hasReturnValue()) {
      TypeSymbol retType = getMethod().getReturnType().getType();
      return CILOSTAZOLFrame.pop(frame, top, retType);
    }
    // return code 0;
    return 0;
  }

  // endregion

  // region object
  private void copyObject(VirtualFrame frame, CLITablePtr typePtr, int sourceSlot, int destSlot) {
    var type =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    CILOSTAZOLFrame.copyStatic(frame, sourceSlot, destSlot);
  }

  private void copyObjectIndirectly(VirtualFrame frame, int sourceIdx, int destIdx) {
    int sourceSlot = CILOSTAZOLFrame.popInt32(frame, sourceIdx);
    int descSlot = CILOSTAZOLFrame.popInt32(frame, destIdx);
    CILOSTAZOLFrame.copyStatic(frame, sourceSlot, descSlot);
  }

  private int getSlotFromReference(VirtualFrame frame, int referenceIdx) {
    return CILOSTAZOLFrame.popInt32(frame, referenceIdx);
  }

  private void checkIsInstance(VirtualFrame frame, int slot, CLITablePtr typePtr) {
    // TODO: The value can be a Nullable<T>, which is handled differently than T
    var targetType =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    var object = CILOSTAZOLFrame.popObject(frame, slot);
    var sourceType = object.getTypeSymbol();
    if (sourceType.isAssignableFrom(targetType)) {
      // Success: put object back on stack with a new type
      CILOSTAZOLFrame.putObject(frame, slot, object);
      // TODO: Decide how to denote the change in type in the static analysis
    } else {
      // Failure: keep the previous type and put null on the stack
      CILOSTAZOLFrame.putObject(frame, slot, StaticObject.NULL);
    }
  }

  private void castClass(VirtualFrame frame, int slot, CLITablePtr typePtr) {
    StaticObject object = CILOSTAZOLFrame.popObject(frame, slot);
    if (object == StaticObject.NULL) {
      CILOSTAZOLFrame.putObject(frame, slot, StaticObject.NULL);
      return;
    }

    // TODO: The value can be a Nullable<T>, which is handled differently than T
    var targetType =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    var sourceType = object.getTypeSymbol();
    if (targetType.isAssignableFrom(sourceType)) {
      // Success: put object back on stack with a new type
      CILOSTAZOLFrame.putObject(frame, slot, object);
    } else {
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.InvalidCast, getMethod().getContext(), frame, slot + 1);
    }
  }

  private void box(VirtualFrame frame, int slot, CLITablePtr typePtr) {
    var type =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    if (!type.isValueType()) return;

    // TODO: Nullable<T> requires special handling
    StaticObject object = null;
    try {
      object = getMethod().getContext().getAllocator().box(type, frame, slot);
    } catch (Exception ex) {
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.OutOfMemory, getMethod().getContext(), frame, slot + 1);
    }
    CILOSTAZOLFrame.putObject(frame, slot, object);
  }

  private void unbox(VirtualFrame frame, int slot, CLITablePtr typePtr) {
    var type =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    if (!type.isValueType()) return;

    StaticObject valueReference =
        getMethod().getContext().getAllocator().unboxToReference(type, frame, method, slot);
    CILOSTAZOLFrame.putObject(frame, slot, valueReference);
  }

  private void unboxAny(VirtualFrame frame, int slot, CLITablePtr typePtr) {
    var targetType =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    var object = CILOSTAZOLFrame.popObject(frame, slot);
    var sourceType = (NamedTypeSymbol) object.getTypeSymbol();
    if (!sourceType.isValueType()) {
      // Reference types are handled like castclass
      if (object == StaticObject.NULL) {
        CILOSTAZOLFrame.putObject(frame, slot, StaticObject.NULL);
        return;
      }

      // TODO: The value can be a Nullable<T>, which is handled differently than T
      if (sourceType.isAssignableFrom(targetType)) {
        CILOSTAZOLFrame.putObject(frame, slot, object);
      } else {
        throw RuntimeCILException.RuntimeCILExceptionFactory.create(
            RuntimeCILException.Exception.InvalidCast, getMethod().getContext(), frame, slot + 1);
      }
    }

    try {
      unboxValueType(frame, slot, targetType, sourceType, object);
    } catch (Exception e) {
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.InvalidCast, getMethod().getContext(), frame, slot + 1);
    }
  }

  private void unboxValueType(
      VirtualFrame frame,
      int slot,
      NamedTypeSymbol targetType,
      NamedTypeSymbol sourceType,
      StaticObject object) {
    switch (targetType.getSystemType()) {
      case Boolean -> {
        boolean value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getBoolean(object);
        CILOSTAZOLFrame.putInt32(frame, slot, value ? 1 : 0);
      }
      case Char -> {
        char value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getChar(object);
        CILOSTAZOLFrame.putInt32(frame, slot, value);
      }
      case Byte -> {
        byte value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getByte(object);
        CILOSTAZOLFrame.putInt32(frame, slot, value);
      }
      case Int -> {
        int value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getInt(object);
        CILOSTAZOLFrame.putInt32(frame, slot, value);
      }
      case Short -> {
        short value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getShort(object);
        CILOSTAZOLFrame.putInt32(frame, slot, value);
      }
      case Float -> {
        float value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getFloat(object);
        CILOSTAZOLFrame.putNativeFloat(frame, slot, value);
      }
      case Long -> {
        long value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getLong(object);
        CILOSTAZOLFrame.putInt64(frame, slot, value);
      }
      case Double -> {
        double value =
            sourceType
                .getAssignableInstanceField(sourceType.getFields()[0], frame, slot + 1)
                .getDouble(object);
        CILOSTAZOLFrame.putNativeFloat(frame, slot, value);
      }
      case Void -> throw new InterpreterException("Cannot unbox void");
      case Object -> // Unboxing a struct -> we don't need to change any values
      CILOSTAZOLFrame.putObject(frame, slot, object);
    }
  }

  private void getSize(VirtualFrame frame, int slot, CLITablePtr typePtr) {
    var type =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    CILOSTAZOLFrame.putInt32(frame, slot, type.getSize(frame, slot));
  }

  private void createNewObjectOnStack(
      VirtualFrame frame, NamedTypeSymbol type, int dest, int topStack) {
    StaticObject object = type.getContext().getAllocator().createNew(type, frame, topStack);
    CILOSTAZOLFrame.setLocalObject(frame, dest, object);
  }

  private void initializeObject(VirtualFrame frame, int top, CLITablePtr typePtr) {
    var type =
        (NamedTypeSymbol)
            SymbolResolver.resolveType(
                typePtr,
                method.getTypeArguments(),
                method.getDefiningType().getTypeArguments(),
                method.getModule());
    var destReference = CILOSTAZOLFrame.popObject(frame, top - 1);
    assert ((ReferenceSymbol) destReference.getTypeSymbol()).getReferenceType()
        == ReferenceSymbol.ReferenceType.Local;
    int dest = getMethod().getContext().getStackReferenceIndexProperty().getInt(destReference);

    if (type.isValueType()) {
      // Initialize value type
      createNewObjectOnStack(frame, type, dest, top);
      return;
    }

    // Initialize reference type
    CILOSTAZOLFrame.setLocalObject(frame, dest, StaticObject.NULL);
  }

  private void loadFieldInstanceFieldRef(VirtualFrame frame, int top, CLITablePtr fieldPtr) {
    final var object =
        (StaticObject)
            CILOSTAZOLFrame.popObjectFromPossibleReference(
                frame, top - 1, getMethod().getContext());
    var instance = (NamedTypeSymbol) object.getTypeSymbol();
    final var classMember =
        SymbolResolver.resolveField(fieldPtr, instance.getTypeArguments(), method.getModule());
    final var field = classMember.symbol.getAssignableInstanceField(classMember.member, frame, top);
    CILOSTAZOLFrame.putObject(
        frame,
        top - 1,
        getMethod()
            .getContext()
            .getAllocator()
            .createFieldReference(
                SymbolResolver.resolveReference(
                    ReferenceSymbol.ReferenceType.Field, getMethod().getContext()),
                object,
                field));
  }

  private void loadFieldStaticFieldRef(VirtualFrame frame, int top, CLITablePtr fieldPtr) {
    final var classMember = SymbolResolver.resolveField(fieldPtr, method.getModule());
    final var field = classMember.symbol.getAssignableStaticField(classMember.member, frame, top);
    final var object = classMember.symbol.getStaticInstance(frame, top);
    CILOSTAZOLFrame.putObject(
        frame,
        top,
        getMethod()
            .getContext()
            .getAllocator()
            .createFieldReference(
                SymbolResolver.resolveReference(
                    ReferenceSymbol.ReferenceType.Field, getMethod().getContext()),
                object,
                field));
  }

  private void loadInstanceField(VirtualFrame frame, int top, CLITablePtr fieldPtr) {
    StaticObject object =
        (StaticObject)
            CILOSTAZOLFrame.popObjectFromPossibleReference(
                frame, top - 1, getMethod().getContext());
    var instance = (NamedTypeSymbol) object.getTypeSymbol();
    var classMember =
        SymbolResolver.resolveField(fieldPtr, instance.getTypeArguments(), method.getModule());
    StaticField field =
        classMember.symbol.getAssignableInstanceField(classMember.member, frame, top);

    if (object.equals(StaticObject.NULL))
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.NullReference, getMethod().getContext(), frame, top);
    loadValueFromField(frame, top, field, object);
  }

  private void loadStaticField(VirtualFrame frame, int top, CLITablePtr fieldPtr) {
    var classMember = SymbolResolver.resolveField(fieldPtr, method.getModule());
    StaticField field = classMember.symbol.getAssignableStaticField(classMember.member, frame, top);
    StaticObject object = classMember.symbol.getStaticInstance(frame, top);
    loadValueFromField(frame, top + 1, field, object);
  }

  private void loadValueFromField(
      VirtualFrame frame, int top, StaticField field, StaticObject object) {
    switch (field.getKind()) {
      case Boolean -> {
        boolean value = field.getBoolean(object);
        CILOSTAZOLFrame.putInt32(frame, top - 1, value ? 1 : 0);
      }
      case Byte -> {
        byte value = field.getByte(object);
        CILOSTAZOLFrame.putInt32(frame, top - 1, value);
      }
      case Char -> {
        char value = field.getChar(object);
        CILOSTAZOLFrame.putInt32(frame, top - 1, value);
      }
      case Short -> {
        short value = field.getShort(object);
        CILOSTAZOLFrame.putInt32(frame, top - 1, value);
      }
      case Float -> {
        float value = field.getFloat(object);
        CILOSTAZOLFrame.putNativeFloat(frame, top - 1, value);
      }
      case Double -> {
        double value = field.getDouble(object);
        CILOSTAZOLFrame.putNativeFloat(frame, top - 1, value);
      }
      case Int -> {
        int value = field.getInt(object);
        CILOSTAZOLFrame.putInt32(frame, top - 1, value);
      }
      case Long -> {
        long value = field.getLong(object);
        CILOSTAZOLFrame.putInt64(frame, top - 1, value);
      }
      default -> {
        StaticObject value = (StaticObject) field.getObject(object);
        CILOSTAZOLFrame.putObject(frame, top - 1, value);
      }
    }
  }

  void storeInstanceField(VirtualFrame frame, int top, CLITablePtr fieldPtr) {
    StaticObject object =
        (StaticObject)
            CILOSTAZOLFrame.popObjectFromPossibleReference(
                frame, top - 2, getMethod().getContext());
    var instance = (NamedTypeSymbol) object.getTypeSymbol();
    var classMember =
        SymbolResolver.resolveField(fieldPtr, instance.getTypeArguments(), method.getModule());
    StaticField field =
        classMember.symbol.getAssignableInstanceField(classMember.member, frame, top);
    assignValueToField(frame, top, field, object);
  }

  private void storeStaticField(VirtualFrame frame, int top, CLITablePtr fieldPtr) {
    var classMember = SymbolResolver.resolveField(fieldPtr, method.getModule());
    StaticField field = classMember.symbol.getAssignableStaticField(classMember.member, frame, top);
    StaticObject object = classMember.symbol.getStaticInstance(frame, top);
    if (object.equals(StaticObject.NULL))
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.NullReference, getMethod().getContext(), frame, top);
    assignValueToField(frame, top, field, object);
  }

  private void assignValueToField(
      VirtualFrame frame, int top, StaticField field, StaticObject object) {
    switch (field.getKind()) {
      case Boolean -> {
        int value = CILOSTAZOLFrame.popInt32(frame, top - 1);
        field.setBoolean(object, value != 0);
      }
      case Byte -> {
        int value = CILOSTAZOLFrame.popInt32(frame, top - 1);
        field.setByte(object, (byte) value);
      }
      case Short -> {
        int value = CILOSTAZOLFrame.popInt32(frame, top - 1);
        field.setShort(object, (short) value);
      }
      case Char -> {
        int value = CILOSTAZOLFrame.popInt32(frame, top - 1);
        field.setChar(object, (char) value);
      }
      case Float -> {
        double value = CILOSTAZOLFrame.popNativeFloat(frame, top - 1);
        field.setFloat(object, (float) value);
      }
      case Double -> {
        double value = CILOSTAZOLFrame.popNativeFloat(frame, top - 1);
        field.setDouble(object, value);
      }
      case Int -> {
        int value = CILOSTAZOLFrame.popInt32(frame, top - 1);
        field.setInt(object, value);
      }
      case Long -> {
        long value = CILOSTAZOLFrame.popInt64(frame, top - 1);
        field.setLong(object, value);
      }
      default -> {
        StaticObject value = CILOSTAZOLFrame.popObject(frame, top - 1);
        field.setObject(object, value);
      }
    }
  }
  // endregion

  // region Nodeization
  /**
   * Get a byte[] representing an instruction with the specified opcode and a 32-bit immediate
   * value.
   *
   * @param opcode opcode of the new instruction
   * @param imm 32-bit immediate value of the new instruction
   * @param targetLength the length of the resulting patch, instruction will be padded with NOPs
   * @return The new instruction bytes.
   */
  private byte[] preparePatch(byte opcode, int imm, int targetLength) {
    assert (targetLength >= 5); // Smaller instructions won't fit the 32-bit immediate
    byte[] patch = new byte[targetLength];
    patch[0] = opcode;
    patch[1] = (byte) (imm & 0xFF);
    patch[2] = (byte) ((imm >> 8) & 0xFF);
    patch[3] = (byte) ((imm >> 16) & 0xFF);
    patch[4] = (byte) ((imm >> 24) & 0xFF);
    return patch;
  }

  private int nodeizeOpToken(VirtualFrame frame, int top, CLITablePtr token, int pc, int opcode) {
    CompilerDirectives.transferToInterpreterAndInvalidate();
    final NodeizedNodeBase node;
    switch (opcode) {
      case NEWOBJ -> {
        var method =
            SymbolResolver.resolveMethod(
                token,
                getMethod().getTypeArguments(),
                getMethod().getDefiningType().getTypeArguments(),
                getMethod().getModule());
        node = new NEWOBJNode(method.member, top);
      }
      case JMP -> {
        var method =
            SymbolResolver.resolveMethod(
                token,
                getMethod().getTypeArguments(),
                getMethod().getDefiningType().getTypeArguments(),
                getMethod().getModule());
        if (method.member.getMethodFlags().hasFlag(Flag.UNMANAGED_EXPORT)) {
          // Either native support must be supported or some workaround must be implemented
          throw new NotImplementedException();
        }

        node = new JMPNode(method.member, top);
      }
      case CALL -> {
        var method =
            SymbolResolver.resolveMethod(
                token,
                getMethod().getTypeArguments(),
                getMethod().getDefiningType().getTypeArguments(),
                getMethod().getModule());
        node = getCheckedCALLNode(method.member, top);
      }
      case CALLVIRT -> {
        // This is not very efficient, but it's the easiest way to get one of the correct methods
        var method =
            SymbolResolver.resolveMethod(
                token,
                getMethod().getTypeArguments(),
                getMethod().getDefiningType().getTypeArguments(),
                getMethod().getModule());
        node = getCheckedCALLVIRTNode(method.member, top);
      }
      default -> {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new InterpreterException();
      }
    }

    int index = addNode(node);

    byte[] patch =
        preparePatch(
            (byte) TRUFFLE_NODE,
            index,
            com.vztekoverflow.cil.parser.bytecode.BytecodeInstructions.getLength(opcode));
    bytecodeBuffer.patchBytecode(pc, patch);

    // execute the new node
    return nodes[index].execute(frame);
  }

  private CALLNode getCheckedCALLNode(MethodSymbol method, int top) {
    if (method.getMethodFlags().hasFlag(Flag.UNMANAGED_EXPORT)) {
      // Either native support must be supported or some workaround must be implemented
      throw new NotImplementedException();
    }
    return new CALLNode(method, top);
  }

  private CALLVIRTNode getCheckedCALLVIRTNode(MethodSymbol method, int top) {
    if (method.getMethodFlags().hasFlag(Flag.UNMANAGED_EXPORT)) {
      // Either native support must be supported or some workaround must be implemented
      throw new NotImplementedException();
    }
    return new CALLVIRTNode(method, top);
  }

  private int addNode(NodeizedNodeBase node) {
    CompilerAsserts.neverPartOfCompilation();
    nodes = Arrays.copyOf(nodes, nodes.length + 1);
    int nodeIndex = nodes.length - 1; // latest empty slot
    nodes[nodeIndex] = insert(node);
    return nodeIndex;
  }
  // endregion

  // region Conversion
  private void convertFromSignedToInteger(int opcode, VirtualFrame frame, int top, long value) {
    switch (opcode) {
      case CONV_I1 -> CILOSTAZOLFrame.putInt32(frame, top, TypeHelpers.signExtend8(value));
      case CONV_I2 -> CILOSTAZOLFrame.putInt32(frame, top, TypeHelpers.signExtend16(value));
      case CONV_I4 -> CILOSTAZOLFrame.putInt32(frame, top, (int) TypeHelpers.truncate32(value));
      case CONV_I8, CONV_U8 -> CILOSTAZOLFrame.putInt64(frame, top, value);
      case CONV_U1 -> CILOSTAZOLFrame.putInt32(frame, top, TypeHelpers.zeroExtend8(value));
      case CONV_U2 -> CILOSTAZOLFrame.putInt32(frame, top, TypeHelpers.zeroExtend16(value));
      case CONV_U4 -> CILOSTAZOLFrame.putInt32(
          frame, top, (int) TypeHelpers.zeroExtend32(TypeHelpers.truncate32(value)));
      case CONV_I, CONV_U -> CILOSTAZOLFrame.putNativeInt(
          frame, top, (int) TypeHelpers.truncate32(value));
      default -> {
        CompilerAsserts.neverPartOfCompilation();
        throw new InterpreterException("Invalid opcode for conversion");
      }
    }
  }

  private void convertFromSignedToIntegerAndCheckOverflow(
      int opcode, VirtualFrame frame, int top, long value) {
    try {
      switch (opcode) {
        case CONV_OVF_I1, CONV_OVF_I1_UN -> CILOSTAZOLFrame.putInt32(
            frame, top, TypeHelpers.signExtend8Exact(value));
        case CONV_OVF_I2, CONV_OVF_I2_UN -> CILOSTAZOLFrame.putInt32(
            frame, top, TypeHelpers.signExtend16Exact(value));
        case CONV_OVF_I4, CONV_OVF_I4_UN -> CILOSTAZOLFrame.putInt32(
            frame, top, (int) TypeHelpers.truncate32Exact(value));
        case CONV_OVF_I8, CONV_OVF_I8_UN, CONV_OVF_U8, CONV_OVF_U8_UN -> CILOSTAZOLFrame.putInt64(
            frame, top, value);
        case CONV_OVF_U1, CONV_OVF_U1_UN -> CILOSTAZOLFrame.putInt32(
            frame, top, TypeHelpers.zeroExtend8Exact(value));
        case CONV_OVF_U2, CONV_OVF_U2_UN -> CILOSTAZOLFrame.putInt32(
            frame, top, TypeHelpers.zeroExtend16Exact(value));
        case CONV_OVF_U4, CONV_OVF_U4_UN -> CILOSTAZOLFrame.putInt32(
            frame, top, (int) TypeHelpers.zeroExtend32Exact(TypeHelpers.truncate32Exact(value)));
        case CONV_OVF_I, CONV_OVF_I_UN -> CILOSTAZOLFrame.putNativeInt(
            frame, top, (int) TypeHelpers.truncate32Exact(value));
        case CONV_OVF_U, CONV_OVF_U_UN -> CILOSTAZOLFrame.putNativeInt(
            frame, top, (int) TypeHelpers.zeroExtend32Exact(TypeHelpers.truncate32Exact(value)));
        default -> {
          CompilerAsserts.neverPartOfCompilation();
          throw new InterpreterException("Invalid opcode for conversion");
        }
      }
    } catch (ArithmeticException ex) {
      throw RuntimeCILException.RuntimeCILExceptionFactory.create(
          RuntimeCILException.Exception.Overflow, getMethod().getContext(), frame, top);
    }
  }

  private long getIntegerValueForConversion(
      VirtualFrame frame, int top, StaticOpCodeAnalyser.OpCodeType type, boolean signed) {
    return switch (type) {
      case Int32 -> signed
          ? TypeHelpers.signExtend32(CILOSTAZOLFrame.popInt32(frame, top))
          : TypeHelpers.zeroExtend32(CILOSTAZOLFrame.popInt32(frame, top));
      case NativeInt -> signed
          ? TypeHelpers.signExtend32(CILOSTAZOLFrame.popNativeInt(frame, top))
          : TypeHelpers.zeroExtend32(CILOSTAZOLFrame.popNativeInt(frame, top));
      case Int64 -> CILOSTAZOLFrame.popInt64(frame, top);
      case NativeFloat -> (long) CILOSTAZOLFrame.popNativeFloat(frame, top);
      default -> throw new InterpreterException("Invalid type for conversion: " + type);
    };
  }

  private void convertToFloat(
      int opcode, VirtualFrame frame, int top, StaticOpCodeAnalyser.OpCodeType type) {
    double value =
        switch (type) {
          case Int32 -> CILOSTAZOLFrame.popInt32(frame, top);
          case Int64 -> CILOSTAZOLFrame.popInt64(frame, top);
          case NativeInt -> CILOSTAZOLFrame.popNativeInt(frame, top);
          case NativeFloat -> CILOSTAZOLFrame.popNativeFloat(frame, top);
          default -> throw new InterpreterException("Invalid type for conversion: " + type);
        };

    switch (opcode) {
      case CONV_R4 -> CILOSTAZOLFrame.putNativeFloat(frame, top, (float) value);
      case CONV_R8 -> CILOSTAZOLFrame.putNativeFloat(frame, top, value);
      default -> {
        CompilerAsserts.neverPartOfCompilation();
        throw new InterpreterException("Invalid opcode for conversion");
      }
    }
  }
  // endregion

  // region Branching
  /**
   * Evaluate whether the branch should be taken for simple (true/false) conditional branch
   * instructions based on a value on the evaluation stack.
   *
   * @return whether to take the branch or not
   */
  private boolean shouldBranch(
      int opcode, VirtualFrame frame, int slot, StaticOpCodeAnalyser.OpCodeType type) {
    boolean value;
    if (type == StaticOpCodeAnalyser.OpCodeType.Object) {
      value = CILOSTAZOLFrame.popObject(frame, slot) != StaticObject.NULL;
    } else {
      value = CILOSTAZOLFrame.popInt32(frame, slot) != 0;
    }

    if (opcode == BRFALSE || opcode == BRFALSE_S) {
      value = !value;
    }

    return value;
  }

  /**
   * Do a binary comparison of values on the evaluation stack and put the result on the evaluation
   * stack.
   */
  private void binaryCompareAndPutOnTop(
      int opcode, VirtualFrame frame, int slot1, int slot2, StaticOpCodeAnalyser.OpCodeType type) {
    boolean result = binaryCompare(opcode, frame, slot1, slot2, type);
    CILOSTAZOLFrame.putInt32(frame, slot1, result ? 1 : 0);
  }

  /**
   * Do a binary comparison of values on the evaluation stack and return the result as a boolean.
   *
   * @return the comparison result as a boolean
   */
  private boolean binaryCompare(
      int opcode, VirtualFrame frame, int slot1, int slot2, StaticOpCodeAnalyser.OpCodeType type) {
    assert slot1 < slot2;

    if (type == StaticOpCodeAnalyser.OpCodeType.Int32
        || type == StaticOpCodeAnalyser.OpCodeType.NativeInt) {
      long op1 = CILOSTAZOLFrame.popInt32(frame, slot1);
      long op2 = CILOSTAZOLFrame.popInt32(frame, slot2);
      return binaryCompareInt32(opcode, op1, op2);
    }

    if (type == StaticOpCodeAnalyser.OpCodeType.Int64) {
      long op1 = CILOSTAZOLFrame.popInt64(frame, slot1);
      long op2 = CILOSTAZOLFrame.popInt64(frame, slot2);
      return binaryCompareInt64(opcode, op1, op2);
    }

    if (type == StaticOpCodeAnalyser.OpCodeType.NativeFloat) {
      double op1 = CILOSTAZOLFrame.popNativeFloat(frame, slot1);
      double op2 = CILOSTAZOLFrame.popNativeFloat(frame, slot2);
      return binaryCompareDouble(opcode, op1, op2);
    }

    if (type == StaticOpCodeAnalyser.OpCodeType.Object) {
      var op1 = CILOSTAZOLFrame.popObject(frame, slot1);
      var op2 = CILOSTAZOLFrame.popObject(frame, slot2);
      return binaryCompareByReference(opcode, frame, op1, op2);
    }

    CompilerDirectives.transferToInterpreterAndInvalidate();
    throw new InterpreterException("Invalid types for comparison: " + type.name());
  }

  private boolean binaryCompareByReference(int opcode, VirtualFrame frame, Object op1, Object op2) {
    switch (opcode) {
      case CEQ:
      case BEQ:
      case BEQ_S:
        return op1 == op2;

      case BNE_UN:
      case BNE_UN_S:
        /*
        cgt.un is allowed and verifiable on ObjectRefs (O). This is commonly used when comparing an
        ObjectRef with null (there is no "compare-not-equal" instruction, which would otherwise be a more
        obvious solution)
         */
      case CGT_UN:
        return op1 != op2;
    }

    CompilerDirectives.transferToInterpreterAndInvalidate();
    throw new InterpreterException("Unimplemented opcode for reference comparison: " + opcode);
  }

  private boolean binaryCompareInt32(int opcode, long op1, long op2) {
    switch (opcode) {
      case CGT:
      case BGT:
      case BGT_S:
      case BGE:
      case BGE_S:
      case CLT:
      case BLT:
      case BLT_S:
      case BLE:
      case BLE_S:
      case CEQ:
      case BEQ:
      case BEQ_S:
        op1 = TypeHelpers.signExtend32(op1);
        op2 = TypeHelpers.signExtend32(op2);
        break;
      case CGT_UN:
      case BGT_UN:
      case BGT_UN_S:
      case BGE_UN:
      case BGE_UN_S:
      case CLT_UN:
      case BLT_UN:
      case BLT_UN_S:
      case BLE_UN:
      case BLE_UN_S:
      case BNE_UN:
      case BNE_UN_S:
        op1 = TypeHelpers.zeroExtend32(op1);
        op2 = TypeHelpers.zeroExtend32(op2);
        break;
      default:
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new InterpreterException("Unimplemented opcode for int32 comparison: " + opcode);
    }

    return binaryCompareInt64(opcode, op1, op2);
  }

  private boolean binaryCompareInt64(int opcode, long op1, long op2) {
    boolean result;
    switch (opcode) {
      case CGT:
      case BGT:
      case BGT_S:
        result = op1 > op2;
        break;

      case BGE:
      case BGE_S:
        result = op1 >= op2;
        break;

      case CLT:
      case BLT:
      case BLT_S:
        result = op1 < op2;
        break;
      case BLE:
      case BLE_S:
        result = op1 <= op2;
        break;

      case CEQ:
      case BEQ:
      case BEQ_S:
        result = op1 == op2;
        break;

      case CGT_UN:
      case BGT_UN:
      case BGT_UN_S:
        result = Long.compareUnsigned(op1, op2) > 0;
        break;

      case BGE_UN:
      case BGE_UN_S:
        result = Long.compareUnsigned(op1, op2) >= 0;
        break;

      case CLT_UN:
      case BLT_UN:
      case BLT_UN_S:
        result = Long.compareUnsigned(op1, op2) < 0;
        break;
      case BLE_UN:
      case BLE_UN_S:
        result = Long.compareUnsigned(op1, op2) <= 0;
        break;

      case BNE_UN:
      case BNE_UN_S:
        result = op1 != op2;
        break;
      default:
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new InterpreterException("Unimplemented opcode for int64 comparison: " + opcode);
    }

    return result;
  }

  private boolean binaryCompareDouble(int opcode, double op1, double op2) {
    final boolean isUnordered = Double.isNaN(op1) || Double.isNaN(op2);
    boolean result;
    switch (opcode) {
      case CGT:
      case BGT:
      case BGT_S:
        if (isUnordered) {
          return false;
        }
        result = op1 > op2;
        break;

      case CGT_UN:
      case BGT_UN:
      case BGT_UN_S:
        if (isUnordered) {
          return true;
        }
        result = op1 > op2;
        break;

      case BGE:
      case BGE_S:
        if (isUnordered) {
          return false;
        }
        result = op1 >= op2;
        break;

      case BGE_UN:
      case BGE_UN_S:
        if (isUnordered) {
          return true;
        }
        result = op1 >= op2;
        break;

      case CLT:
      case BLT:
      case BLT_S:
        if (isUnordered) {
          return false;
        }
        result = op1 < op2;
        break;

      case CLT_UN:
      case BLT_UN:
      case BLT_UN_S:
        if (isUnordered) {
          return true;
        }
        result = op1 < op2;
        break;

      case BLE:
      case BLE_S:
        if (isUnordered) {
          return false;
        }
        result = op1 <= op2;
        break;

      case BLE_UN:
      case BLE_UN_S:
        if (isUnordered) {
          return true;
        }
        result = op1 <= op2;
        break;

      case CEQ:
      case BEQ:
      case BEQ_S:
        if (isUnordered) {
          return false;
        }

        result = op1 == op2;
        break;

      case BNE_UN:
      case BNE_UN_S:
        if (isUnordered) {
          return true;
        }
        result = op1 != op2;
        break;
      default:
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new InterpreterException("Unimplemented opcode for double comparison: " + opcode);
    }

    return result;
  }
  // endregion

  // region OSR classes
  private static final class OSRInterpreterState {
    final int top;

    OSRInterpreterState(int top) {
      this.top = top;
    }
  }

  private static final class OSRReturnException extends ControlFlowException {
    private final Object result;
    private final Throwable throwable;

    OSRReturnException(Object result) {
      this.result = result;
      this.throwable = null;
    }

    OSRReturnException(Throwable throwable) {
      this.result = null;
      this.throwable = throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable ex) throws T {
      throw (T) ex;
    }

    Object getResultOrRethrow() {
      if (throwable != null) {
        throw sneakyThrow(throwable);
      }
      return result;
    }
  }
  // endregion
}
