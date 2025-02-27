package com.vztekoverflow.cilostazol.runtime.symbols;

import com.vztekoverflow.cil.parser.cli.signature.RetTypeSig;
import com.vztekoverflow.cilostazol.runtime.context.ContextProviderImpl;
import com.vztekoverflow.cilostazol.runtime.other.SymbolResolver;

public final class ReturnSymbol extends Symbol {
  private final boolean byRef;
  private final TypeSymbol type;

  private ReturnSymbol(boolean byRef, TypeSymbol type) {
    super(ContextProviderImpl.getInstance());
    this.byRef = byRef;
    this.type = type;
  }

  @Override
  public String toString() {
    if (type == null) {
      return "null";
    }

    return type.toString();
  }

  public boolean isByRef() {
    return byRef;
  }

  public TypeSymbol getType() {
    return type;
  }

  public static final class ReturnSymbolFactory {
    public static ReturnSymbol create(
        RetTypeSig sig, TypeSymbol[] mvars, TypeSymbol[] vars, ModuleSymbol module) {
      return new ReturnSymbol(
          sig.isByRef(), SymbolResolver.resolveType(sig.getTypeSig(), mvars, vars, module));
    }

    public static ReturnSymbol createWith(ReturnSymbol symbol, TypeSymbol type) {
      return new ReturnSymbol(symbol.isByRef(), type);
    }
  }
}
