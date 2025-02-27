package com.vztekoverflow.cil.parser.cli.table.generated;

import com.oracle.truffle.api.CompilerDirectives;
import com.vztekoverflow.cil.parser.cli.table.*;

public class CLIConstantTableRow extends CLITableRow<CLIConstantTableRow> {

  @CompilerDirectives.CompilationFinal(dimensions = 1)
  private static final byte[] MAP_PARENT_TABLES =
      new byte[] {
        CLITableConstants.CLI_TABLE_FIELD,
        CLITableConstants.CLI_TABLE_PARAM,
        CLITableConstants.CLI_TABLE_PROPERTY
      };

  public CLIConstantTableRow(CLITables tables, int cursor, int rowIndex) {
    super(tables, cursor, rowIndex);
  }

  public final byte getType() {
    int offset = 0;
    return getByte(offset);
  }

  public final CLITablePtr getParentTablePtr() {
    int offset = 2;
    int codedValue;
    var isSmall = areSmallEnough(MAP_PARENT_TABLES);
    if (isSmall) {
      codedValue = getShort(offset) & 0xFFFF;
    } else {
      codedValue = getInt(offset);
    }
    if ((isSmall && (codedValue & 0xffff) == 0xffff)
        || (!isSmall && (codedValue & 0xffffffff) == 0xffffffff)) return null;
    return new CLITablePtr(
        MAP_PARENT_TABLES[codedValue & 3],
        (isSmall ? (0x0000ffff & codedValue) : codedValue) >>> 2);
  }

  public final CLIBlobHeapPtr getValueHeapPtr() {
    int offset = 4;
    if (!areSmallEnough(MAP_PARENT_TABLES)) offset += 2;
    int heapOffset = 0;
    if (tables.isBlobHeapBig()) {
      heapOffset = getInt(offset);
    } else {
      heapOffset = getUShort(offset);
    }
    return new CLIBlobHeapPtr(heapOffset);
  }

  @Override
  public int getLength() {
    int offset = 6;
    if (tables.isBlobHeapBig()) offset += 2;
    if (!areSmallEnough(MAP_PARENT_TABLES)) offset += 2;
    return offset;
  }

  @Override
  public byte getTableId() {
    return CLITableConstants.CLI_TABLE_CONSTANT;
  }

  @Override
  protected CLIConstantTableRow createNew(CLITables tables, int cursor, int rowIndex) {
    return new CLIConstantTableRow(tables, cursor, rowIndex);
  }
}
