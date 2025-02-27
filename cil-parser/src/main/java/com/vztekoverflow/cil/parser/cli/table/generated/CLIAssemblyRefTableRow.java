package com.vztekoverflow.cil.parser.cli.table.generated;

import com.vztekoverflow.cil.parser.cli.table.*;

public class CLIAssemblyRefTableRow extends CLITableRow<CLIAssemblyRefTableRow> {

  public CLIAssemblyRefTableRow(CLITables tables, int cursor, int rowIndex) {
    super(tables, cursor, rowIndex);
  }

  public final short getMajorVersion() {
    int offset = 0;
    return getShort(offset);
  }

  public final short getMinorVersion() {
    int offset = 2;
    return getShort(offset);
  }

  public final short getBuildNumber() {
    int offset = 4;
    return getShort(offset);
  }

  public final short getRevisionNumber() {
    int offset = 6;
    return getShort(offset);
  }

  public final int getFlags() {
    int offset = 8;
    return getInt(offset);
  }

  public final CLIBlobHeapPtr getPublicKeyOrTokenHeapPtr() {
    int offset = 12;
    int heapOffset = 0;
    if (tables.isBlobHeapBig()) {
      heapOffset = getInt(offset);
    } else {
      heapOffset = getUShort(offset);
    }
    return new CLIBlobHeapPtr(heapOffset);
  }

  public final CLIStringHeapPtr getNameHeapPtr() {
    int offset = 14;
    if (tables.isBlobHeapBig()) offset += 2;
    int heapOffset = 0;
    if (tables.isStringHeapBig()) {
      heapOffset = getInt(offset);
    } else {
      heapOffset = getUShort(offset);
    }
    return new CLIStringHeapPtr(heapOffset);
  }

  public final CLIStringHeapPtr getCultureHeapPtr() {
    int offset = 16;
    if (tables.isStringHeapBig()) offset += 2;
    if (tables.isBlobHeapBig()) offset += 2;
    int heapOffset = 0;
    if (tables.isStringHeapBig()) {
      heapOffset = getInt(offset);
    } else {
      heapOffset = getUShort(offset);
    }
    return new CLIStringHeapPtr(heapOffset);
  }

  public final CLIStringHeapPtr getHashValueHeapPtr() {
    int offset = 18;
    if (tables.isStringHeapBig()) offset += 4;
    if (tables.isBlobHeapBig()) offset += 2;
    int heapOffset = 0;
    if (tables.isStringHeapBig()) {
      heapOffset = getInt(offset);
    } else {
      heapOffset = getUShort(offset);
    }
    return new CLIStringHeapPtr(heapOffset);
  }

  @Override
  public int getLength() {
    int offset = 20;
    if (tables.isStringHeapBig()) offset += 6;
    if (tables.isBlobHeapBig()) offset += 2;
    return offset;
  }

  @Override
  public byte getTableId() {
    return CLITableConstants.CLI_TABLE_ASSEMBLY_REF;
  }

  @Override
  protected CLIAssemblyRefTableRow createNew(CLITables tables, int cursor, int rowIndex) {
    return new CLIAssemblyRefTableRow(tables, cursor, rowIndex);
  }
}
