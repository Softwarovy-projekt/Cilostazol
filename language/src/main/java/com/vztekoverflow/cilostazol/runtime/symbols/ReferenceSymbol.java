package com.vztekoverflow.cilostazol.runtime.symbols;

import com.vztekoverflow.cilostazol.exceptions.InvalidOperationException;
import com.vztekoverflow.cilostazol.nodes.CILOSTAZOLFrame;
import com.vztekoverflow.cilostazol.runtime.objectmodel.SystemType;

public final class ReferenceSymbol extends TypeSymbol {
  @Override
  protected int getHierarchyDepth() {
    throw new InvalidOperationException();
  }

  @Override
  public boolean isInterface() {
    throw new InvalidOperationException();
  }

  @Override
  public boolean isArray() {
    throw new InvalidOperationException();
  }

  @Override
  public boolean isClosed() {
    throw new InvalidOperationException();
  }

  @Override
  public NamedTypeSymbol[] getInterfaces() {
    throw new InvalidOperationException();
  }

  @Override
  public NamedTypeSymbol[] getSuperClasses() {
    throw new InvalidOperationException();
  }

  public enum ReferenceType {
    Local,
    Argument,
    Field,
    ArrayElement,
    Typed
  }

  private final ReferenceType type;

  private ReferenceSymbol(ReferenceType type) {
    super(null, CILOSTAZOLFrame.StackType.Object, SystemType.Object);
    this.type = type;
  }

  public ReferenceType getReferenceType() {
    return type;
  }

  public static class ReferenceSymbolFactory {
    public static ReferenceSymbol createLocalReference() {
      return new ReferenceSymbol(ReferenceType.Local);
    }

    public static ReferenceSymbol createArgumentReference() {
      return new ReferenceSymbol(ReferenceType.Argument);
    }

    public static ReferenceSymbol createFieldReference() {
      return new ReferenceSymbol(ReferenceType.Field);
    }

    public static ReferenceSymbol createArrayElemReference() {
      return new ReferenceSymbol(ReferenceType.ArrayElement);
    }

    public static ReferenceSymbol createTypedReference() {
      return new ReferenceSymbol(ReferenceType.Typed);
    }
  }
}
