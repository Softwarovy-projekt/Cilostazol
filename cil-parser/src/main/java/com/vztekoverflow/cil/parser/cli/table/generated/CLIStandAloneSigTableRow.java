package com.vztekoverflow.cil.parser.cli.table.generated;

import com.vztekoverflow.cil.parser.cli.table.*;

public class CLIStandAloneSigTableRow extends CLITableRow<CLIStandAloneSigTableRow> {

  public CLIStandAloneSigTableRow(CLITables tables, int cursor, int rowIndex) {
    super(tables, cursor, rowIndex);
  }

  public final CLIBlobHeapPtr getSignatureHeapPtr() {
    int offset = 0;
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
    int offset = 2;
    if (tables.isBlobHeapBig()) offset += 2;
    return offset;
  }

  @Override
  public byte getTableId() {
    return CLITableConstants.CLI_TABLE_STAND_ALONE_SIG;
  }

  @Override
  protected CLIStandAloneSigTableRow createNew(CLITables tables, int cursor, int rowIndex) {
    return new CLIStandAloneSigTableRow(tables, cursor, rowIndex);
  }
}
