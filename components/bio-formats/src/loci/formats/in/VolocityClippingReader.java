//
// VolocityClippingReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.io.EOFException;
import java.io.IOException;

import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.codec.LZOCodec;
import loci.formats.meta.MetadataStore;

/**
 * VolocityClippingReader is the file format reader for Volocity library
 * clipping files.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/in/VolocityClippingReader.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/in/VolocityClippingReader.java;hb=HEAD">Gitweb</a></dd></dl>
 */
public class VolocityClippingReader extends FormatReader {

  // -- Constants --

  private static final String CLIPPING_MAGIC_STRING = "FFCA";
  private static final int AISF = 0x46534941;

  // -- Fields --

  private long pixelOffset;

  // -- Constructor --

  /** Constructs a new Volocity clipping reader. */
  public VolocityClippingReader() {
    super("Volocity Library Clipping", "acff");
    domains = new String[] {FormatTools.UNKNOWN_DOMAIN};
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    return false;
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    in.seek(pixelOffset);

    if (FormatTools.getPlaneSize(this) * 2 + in.getFilePointer() < in.length())
    {
      readPlane(in, x, y, w, h, buf);
      return buf;
    }

    byte[] b = new LZOCodec().decompress(in, null);

    RandomAccessInputStream s = new RandomAccessInputStream(b);
    s.seek(0);
    readPlane(s, x, y, w, h, buf);
    s.close();

    return buf;
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (!fileOnly) {
      pixelOffset = 0;
    }
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    super.initFile(id);
    in = new RandomAccessInputStream(id);

    core[0].littleEndian = in.read() == 'I';
    in.order(isLittleEndian());

    in.skipBytes(4);

    String magicString = in.readString(4);

    if (!magicString.equals(CLIPPING_MAGIC_STRING)) {
      throw new FormatException("Found invalid magic string: " + magicString);
    }

    int check = in.readInt();
    while (check != 0x208 && check != AISF) {
      in.seek(in.getFilePointer() - 3);
      check = in.readInt();
    }
    if (check == AISF) {
      core[0].littleEndian = false;
      in.order(isLittleEndian());
      in.skipBytes(28);
    }

    core[0].sizeX = in.readInt();
    core[0].sizeY = in.readInt();
    core[0].sizeZ = in.readInt();
    core[0].sizeC = 1;

    core[0].sizeT = 1;
    core[0].imageCount = getSizeZ() * getSizeT();
    core[0].dimensionOrder = "XYCZT";
    core[0].pixelType = FormatTools.UINT8;

    pixelOffset = in.getFilePointer() + 65;

    if (getSizeX() * getSizeY() * 100 >= in.length()) {
      while (in.getFilePointer() < in.length()) {
        try {
          byte[] b = new LZOCodec().decompress(in, null);
          if (b.length > 0 && (b.length % (getSizeX() * getSizeY())) == 0) {
            int bytes = b.length / (getSizeX() * getSizeY());
            core[0].pixelType =
              FormatTools.pixelTypeFromBytes(bytes, false, false);
            break;
          }
        }
        catch (EOFException e) { }
        pixelOffset++;
        in.seek(pixelOffset);
      }
    }

    MetadataStore store = makeFilterMetadata();
    MetadataTools.populatePixels(store, this);
  }

}
