package com.vztekoverflow.cilostazol.runtime.symbols;

import com.vztekoverflow.cil.parser.cli.signature.ParamSig;
import com.vztekoverflow.cil.parser.cli.table.generated.CLIParamTableRow;
import com.vztekoverflow.cilostazol.runtime.context.ContextProviderImpl;
import com.vztekoverflow.cilostazol.runtime.other.SymbolResolver;

public final class ParameterSymbol extends Symbol {
  private final boolean isByRef;
  private final TypeSymbol type;
  private final String name;
  private final int idx;
  private final ParamFlags flags;

  private ParameterSymbol(
      boolean isByRef, TypeSymbol type, String name, int idx, ParamFlags flags) {
    super(ContextProviderImpl.getInstance());
    this.isByRef = isByRef;
    this.type = type;
    this.name = name;
    this.idx = idx;
    this.flags = flags;
  }

  public boolean isByRef() {
    return isByRef;
  }

  public ParamFlags getFlags() {
    return flags;
  }

  public int getIndex() {
    return idx;
  }

  public String getName() {
    return name;
  }

  public TypeSymbol getType() {
    return type;
  }

  @Override
  public String toString() {
    if (type == null) {
      return "null";
    }

    return type.toString();
  }

  public static final class ParameterSymbolFactory {
    public static ParameterSymbol create(
        ParamSig paramSig,
        CLIParamTableRow row,
        TypeSymbol[] mvars,
        TypeSymbol[] vars,
        ModuleSymbol module) {
      return new ParameterSymbol(
          paramSig.isByRef(),
          SymbolResolver.resolveType(paramSig.getTypeSig(), mvars, vars, module),
          row.getNameHeapPtr().read(module.getDefiningFile().getStringHeap()),
          row.getSequence(),
          new ParamFlags(row.getFlags()));
    }

    public static ParameterSymbol[] create(
        ParamSig[] params,
        CLIParamTableRow[] rows,
        TypeSymbol[] mvars,
        TypeSymbol[] vars,
        ModuleSymbol module) {
      final ParameterSymbol[] parameters = new ParameterSymbol[params.length];
      for (int i = 0; i < params.length; i++) {
        parameters[i] = create(params[i], rows[i], mvars, vars, module);
      }
      return parameters;
    }

    public static ParameterSymbol createWith(ParameterSymbol symbol, TypeSymbol type) {
      return new ParameterSymbol(
          symbol.isByRef(), type, symbol.getName(), symbol.getIndex(), symbol.getFlags());
    }
  }

  public static class ParamFlags {
    private final int _flags;

    public ParamFlags(int flags) {
      _flags = flags;
    }

    public boolean hasFlag(ParamFlags.Flag flag) {
      return (_flags & flag.code) == flag.code;
    }

    public enum Flag {
      IN(0x0001),
      OUT(0x0002),
      OPTIONAL(0x0010),
      HAS_DEFAULT(0x1000),
      HAS_FIELD_MARSHAL(0x2000),
      UNUSED(0xCFE0);

      public final int code;

      Flag(int code) {
        this.code = code;
      }
    }
  }
}
