package mdbtools.libmdb;

import java.util.ArrayList;

public class Sargs
{
  public static boolean mdb_test_sargs(MdbHandle mdb, MdbColumn col, int offset, int len)
  {
    MdbSarg sarg;
    int i;

    for (i = 0;i < col.num_sargs;i++)
    {
      sarg = (MdbSarg)col.sargs.get(i);
      if (mdb_test_sarg(mdb, col, sarg, offset, len) == 0)
      {
       /* sarg didn't match, no sense going on */
        return false;
      }
    }
    return true;
  }

  public static int mdb_add_sarg(MdbColumn col, MdbSarg in_sarg)
  {
    MdbSarg sarg;

    if (col.sargs == null)
    {
      col.sargs = new ArrayList();
    }
    sarg = (MdbSarg)in_sarg.clone();
    col.sargs.add(sarg);
    col.num_sargs++;

    return 1;
  }

  public static int mdb_test_sarg(MdbHandle mdb, MdbColumn col, MdbSarg sarg,
                                  int offset, int len)
  {
//    char tmpbuf[256];
    int lastchar;

    switch (col.col_type)
    {
      case Constants.MDB_BYTE:
        throw new RuntimeException("not ported yet");
//        return mdb_test_int(sarg, mdb_get_byte(mdb, offset));
//      break;
      case Constants.MDB_INT:
        throw new RuntimeException("not ported yet");
//        return mdb_test_int(sarg, mdb_get_int16(mdb, offset));
//      break;
      case Constants.MDB_LONGINT:
        throw new RuntimeException("not ported yet");
//        return mdb_test_int(sarg, mdb_get_int32(mdb, offset));
//      break;
      case Constants.MDB_TEXT:
        throw new RuntimeException("not ported yet");
//        strncpy(tmpbuf, &mdb->pg_buf[offset],255);
//        lastchar = len > 255 ? 255 : len;
//        tmpbuf[lastchar]='\0';
//        return mdb_test_string(sarg, tmpbuf);
      default:
        throw new IllegalArgumentException("Calling mdb_test_sarg on unknown type. "
                                       + "Add code to mdb_test_sarg() for type " + col.col_type);
//      break;
    }
//    return 1;
  }

  public static int mdb_test_int(MdbSarg sarg, int i)
  {
    switch (sarg.op)
    {
      case Constants.MDB_EQUAL:
        if (sarg.value.i == i)
          return 1;
      break;
      case Constants.MDB_GT:
        if (sarg.value.i < i)
          return 1;
      break;
      case Constants.MDB_LT:
        if (sarg.value.i > i)
          return 1;
      break;
      case Constants.MDB_GTEQ:
        if (sarg.value.i <= i)
          return 1;
      break;
      case Constants.MDB_LTEQ:
        if (sarg.value.i >= i)
          return 1;
      break;
      default:
        throw new IllegalArgumentException("Calling mdb_test_sarg on unknown operator.  " +
                           "Add code to mdb_test_int() for operator " + sarg.op);
//      break;
    }
    return 0;
  }
}
