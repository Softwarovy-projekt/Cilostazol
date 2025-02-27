package com.vztekoverflow.cil.parser.cli.table.generated;

import com.oracle.truffle.api.CompilerDirectives;
import com.vztekoverflow.cil.parser.cli.table.*;

public class CLINestedKlassTableRow extends CLITableRow<CLINestedKlassTableRow> {

  @CompilerDirectives.CompilationFinal(dimensions = 1)
  private static final byte[] MAP_NESTED_KLASS_TABLES =
      new byte[] {CLITableConstants.CLI_TABLE_TYPE_DEF};

  @CompilerDirectives.CompilationFinal(dimensions = 1)
  private static final byte[] MAP_ENCLOSING_KLASS_TABLES =
      new byte[] {CLITableConstants.CLI_TABLE_TYPE_DEF};

  public CLINestedKlassTableRow(CLITables tables, int cursor, int rowIndex) {
    super(tables, cursor, rowIndex);
  }

  public final CLITablePtr getNestedKlassTablePtr() {
    int offset = 0;
    final int rowNo;
    if (areSmallEnough(MAP_NESTED_KLASS_TABLES)) {
      rowNo = getShort(offset) & 0xFFFF;
    } else {
      rowNo = getInt(offset);
    }
    return new CLITablePtr(CLITableConstants.CLI_TABLE_TYPE_DEF, rowNo);
  }

  public final CLITablePtr getEnclosingKlassTablePtr() {
    int offset = 2;
    if (!areSmallEnough(MAP_NESTED_KLASS_TABLES)) offset += 2;
    final int rowNo;
    if (areSmallEnough(MAP_ENCLOSING_KLASS_TABLES)) {
      rowNo = getShort(offset) & 0xFFFF;
    } else {
      rowNo = getInt(offset);
    }
    return new CLITablePtr(CLITableConstants.CLI_TABLE_TYPE_DEF, rowNo);
  }

  @Override
  public int getLength() {
    int offset = 4;
    if (!areSmallEnough(MAP_NESTED_KLASS_TABLES)) offset += 2;
    if (!areSmallEnough(MAP_ENCLOSING_KLASS_TABLES)) offset += 2;
    return offset;
  }

  @Override
  public byte getTableId() {
    return CLITableConstants.CLI_TABLE_NESTED_KLASS;
  }

  @Override
  protected CLINestedKlassTableRow createNew(CLITables tables, int cursor, int rowIndex) {
    return new CLINestedKlassTableRow(tables, cursor, rowIndex);
  }
}
