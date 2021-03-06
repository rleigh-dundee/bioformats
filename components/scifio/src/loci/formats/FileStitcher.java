//
// FileStitcher.java
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

package loci.formats;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;
import java.util.Vector;

import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.formats.in.DefaultMetadataOptions;
import loci.formats.in.MetadataLevel;
import loci.formats.in.MetadataOptions;
import loci.formats.meta.MetadataStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logic to stitch together files with similar names.
 * Assumes that all files have the same characteristics (e.g., dimensions).
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/FileStitcher.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/FileStitcher.java;hb=HEAD">Gitweb</a></dd></dl>
 */
public class FileStitcher extends ReaderWrapper {

  // -- Constants --

  private static final Logger LOGGER =
    LoggerFactory.getLogger(FileStitcher.class);

  // -- Fields --

  /**
   * Whether string ids given should be treated
   * as file patterns rather than single file paths.
   */
  private boolean patternIds = false;

  private boolean doNotChangePattern = false;

  /** Dimensional axis lengths per file. */
  private int[] sizeZ, sizeC, sizeT;

  /** Component lengths for each axis type. */
  private int[][] lenZ, lenC, lenT;

  /** Core metadata. */
  private CoreMetadata[] core;

  /** Current series number. */
  private int series;

  private boolean noStitch;
  private boolean group = true;

  private MetadataStore store;

  private ExternalSeries[] externals;
  private ClassList<IFormatReader> classList;

  // -- Constructors --

  /** Constructs a FileStitcher around a new image reader. */
  public FileStitcher() { this(new ImageReader()); }

  /**
   * Constructs a FileStitcher around a new image reader.
   * @param patternIds Whether string ids given should be treated as file
   *    patterns rather than single file paths.
   */
  public FileStitcher(boolean patternIds) {
    this(new ImageReader(), patternIds);
  }

  /**
   * Constructs a FileStitcher with the given reader.
   * @param r The reader to use for reading stitched files.
   */
  public FileStitcher(IFormatReader r) { this(r, false); }

  /**
   * Constructs a FileStitcher with the given reader.
   * @param r The reader to use for reading stitched files.
   * @param patternIds Whether string ids given should be treated as file
   *   patterns rather than single file paths.
   */
  public FileStitcher(IFormatReader r, boolean patternIds) {
    if (r.getClass().getPackage().getName().equals("loci.formats.in")) {
      ClassList<IFormatReader> classes =
        new ClassList<IFormatReader>(IFormatReader.class);
      classes.addClass(r.getClass());
      setReaderClassList(classes);
    }
    else {
      reader = DimensionSwapper.makeDimensionSwapper(r);
    }
    setUsingPatternIds(patternIds);
  }

  // -- FileStitcher API methods --

  /**
   * Set the ClassList object to use when constructing any helper readers.
   */
  public void setReaderClassList(ClassList<IFormatReader> classList) {
    this.classList = classList;
    reader = DimensionSwapper.makeDimensionSwapper(new ImageReader(classList));
  }

  /** Gets the wrapped reader prototype. */
  public IFormatReader getReader() { return reader; }

  /** Sets whether the reader is using file patterns for IDs. */
  public void setUsingPatternIds(boolean patternIds) {
    this.patternIds = patternIds;
  }

  /** Gets whether the reader is using file patterns for IDs. */
  public boolean isUsingPatternIds() { return patternIds; }

  public void setCanChangePattern(boolean doChange) {
    doNotChangePattern = !doChange;
  }

  public boolean canChangePattern() {
    return !doNotChangePattern;
  }

  /** Gets the reader appropriate for use with the given image plane. */
  public IFormatReader getReader(int no) throws FormatException, IOException {
    if (noStitch) return reader;
    int[] q = computeIndices(no);
    int fno = q[0];
    return getReader(getSeries(), fno);
  }

  /**
   * Gets the reader that should be used with the given series and image plane.
   */
  public DimensionSwapper getReader(int series, int no) {
    if (noStitch) return (DimensionSwapper) reader;
    DimensionSwapper r = externals[getExternalSeries(series)].getReaders()[no];
    initReader(series, no);
    return r;
  }

  /** Gets the local reader index for use with the given image plane. */
  public int getAdjustedIndex(int no) throws FormatException, IOException {
    if (noStitch) return no;
    int[] q = computeIndices(no);
    int ino = q[1];
    return ino;
  }

  /**
   * Gets the axis type for each dimensional block.
   * @return An array containing values from the enumeration:
   *   <ul>
   *     <li>AxisGuesser.Z_AXIS: focal planes</li>
   *     <li>AxisGuesser.T_AXIS: time points</li>
   *     <li>AxisGuesser.C_AXIS: channels</li>
   *     <li>AxisGuesser.S_AXIS: series</li>
   *   </ul>
   */
  public int[] getAxisTypes() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return externals[getExternalSeries()].getAxisGuesser().getAxisTypes();
  }

  /**
   * Sets the axis type for each dimensional block.
   * @param axes An array containing values from the enumeration:
   *   <ul>
   *     <li>AxisGuesser.Z_AXIS: focal planes</li>
   *     <li>AxisGuesser.T_AXIS: time points</li>
   *     <li>AxisGuesser.C_AXIS: channels</li>
   *     <li>AxisGuesser.S_AXIS: series</li>
   *   </ul>
   */
  public void setAxisTypes(int[] axes) throws FormatException {
    FormatTools.assertId(getCurrentFile(), true, 2);
    externals[getExternalSeries()].getAxisGuesser().setAxisTypes(axes);
    computeAxisLengths();
  }

  /** Gets the file pattern object used to build the list of files. */
  public FilePattern getFilePattern() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? findPattern(getCurrentFile()) :
      externals[getExternalSeries()].getFilePattern();
  }

  /**
   * Gets the axis guesser object used to guess
   * which dimensional axes are which.
   */
  public AxisGuesser getAxisGuesser() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return externals[getExternalSeries()].getAxisGuesser();
  }

  public FilePattern findPattern(String id) {
    return new FilePattern(FilePattern.findPattern(id));
  }

  /**
   * Finds the file pattern for the given ID, based on the state of the file
   * stitcher. Takes both ID map entries and the patternIds flag into account.
   */
  public String[] findPatterns(String id) {
    if (!patternIds) {
      // find the containing patterns
      HashMap<String, Object> map = Location.getIdMap();
      if (map.containsKey(id)) {
        // search ID map for pattern, rather than files on disk
        String[] idList = new String[map.size()];
        map.keySet().toArray(idList);
        return FilePattern.findSeriesPatterns(id, null, idList);
      }
      else {
        // id is an unmapped file path; look to similar files on disk
        return FilePattern.findSeriesPatterns(id);
      }
    }
    if (doNotChangePattern) {
      return new String[] {id};
    }
    patternIds = false;
    String[] patterns = findPatterns(new FilePattern(id).getFiles()[0]);
    if (patterns.length == 0) patterns = new String[] {id};
    else {
      FilePattern test = new FilePattern(patterns[0]);
      if (test.getFiles().length == 0) patterns = new String[] {id};
    }
    patternIds = true;
    return patterns;
  }

  // -- IFormatReader API methods --

  /* @see IFormatReader#getImageCount() */
  public int getImageCount() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getImageCount() : core[getSeries()].imageCount;
  }

  /* @see IFormatReader#isRGB() */
  public boolean isRGB() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isRGB() : core[getSeries()].rgb;
  }

  /* @see IFormatReader#getSizeX() */
  public int getSizeX() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getSizeX() : core[getSeries()].sizeX;
  }

  /* @see IFormatReader#getSizeY() */
  public int getSizeY() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getSizeY() : core[getSeries()].sizeY;
  }

  /* @see IFormatReader#getSizeZ() */
  public int getSizeZ() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getSizeZ() : core[getSeries()].sizeZ;
  }

  /* @see IFormatReader#getSizeC() */
  public int getSizeC() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getSizeC() : core[getSeries()].sizeC;
  }

  /* @see IFormatReader#getSizeT() */
  public int getSizeT() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getSizeT() : core[getSeries()].sizeT;
  }

  /* @see IFormatReader#getPixelType() */
  public int getPixelType() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getPixelType() : core[getSeries()].pixelType;
  }

  /* @see IFormatReader#getBitsPerPixel() */
  public int getBitsPerPixel() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getBitsPerPixel() : core[getSeries()].bitsPerPixel;
  }

  /* @see IFormatReader#isIndexed() */
  public boolean isIndexed() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isIndexed() : core[getSeries()].indexed;
  }

  /* @see IFormatReader#isFalseColor() */
  public boolean isFalseColor() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isFalseColor() : core[getSeries()].falseColor;
  }

  /* @see IFormatReader#get8BitLookupTable() */
  public byte[][] get8BitLookupTable() throws FormatException, IOException {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.get8BitLookupTable() :
      getReader(getSeries(), 0).get8BitLookupTable();
  }

  /* @see IFormatReader#get16BitLookupTable() */
  public short[][] get16BitLookupTable() throws FormatException, IOException {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.get16BitLookupTable() :
      getReader(getSeries(), 0).get16BitLookupTable();
  }

  /* @see IFormatReader#getChannelDimLengths() */
  public int[] getChannelDimLengths() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    if (noStitch) return reader.getChannelDimLengths();
    if (core[getSeries()].cLengths == null) {
      return new int[] {core[getSeries()].sizeC};
    }
    return core[getSeries()].cLengths;
  }

  /* @see IFormatReader#getChannelDimTypes() */
  public String[] getChannelDimTypes() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    if (noStitch) return reader.getChannelDimTypes();
    if (core[getSeries()].cTypes == null) {
      return new String[] {FormatTools.CHANNEL};
    }
    return core[getSeries()].cTypes;
  }

  /* @see IFormatReader#getThumbSizeX() */
  public int getThumbSizeX() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getThumbSizeX() :
      getReader(getSeries(), 0).getThumbSizeX();
  }

  /* @see IFormatReader#getThumbSizeY() */
  public int getThumbSizeY() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getThumbSizeY() :
      getReader(getSeries(), 0).getThumbSizeY();
  }

  /* @see IFormatReader#isLittleEndian() */
  public boolean isLittleEndian() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isLittleEndian() :
      getReader(getSeries(), 0).isLittleEndian();
  }

  /* @see IFormatReader#getDimensionOrder() */
  public String getDimensionOrder() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    if (noStitch) return reader.getDimensionOrder();
    return core[getSeries()].dimensionOrder;
  }

  /* @see IFormatReader#isOrderCertain() */
  public boolean isOrderCertain() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isOrderCertain() : core[getSeries()].orderCertain;
  }

  /* @see IFormatReader#isThumbnailSeries() */
  public boolean isThumbnailSeries() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isThumbnailSeries() : core[getSeries()].thumbnail;
  }

  /* @see IFormatReader#isInterleaved() */
  public boolean isInterleaved() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isInterleaved() :
      getReader(getSeries(), 0).isInterleaved();
  }

  /* @see IFormatReader#isInterleaved(int) */
  public boolean isInterleaved(int subC) {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.isInterleaved(subC) :
      getReader(getSeries(), 0).isInterleaved(subC);
  }

  /* @see IFormatReader#openBytes(int) */
  public byte[] openBytes(int no) throws FormatException, IOException {
    return openBytes(no, 0, 0, getSizeX(), getSizeY());
  }

  /* @see IFormatReader#openBytes(int, byte[]) */
  public byte[] openBytes(int no, byte[] buf)
    throws FormatException, IOException
  {
    return openBytes(no, buf, 0, 0, getSizeX(), getSizeY());
  }

  /* @see IFormatReader#openBytes(int, int, int, int, int) */
  public byte[] openBytes(int no, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    int bpp = FormatTools.getBytesPerPixel(getPixelType());
    int ch = getRGBChannelCount();
    byte[] buf = new byte[w * h * ch * bpp];
    return openBytes(no, buf, x, y, w, h);
  }

  /* @see IFormatReader#openBytes(int, byte[], int, int, int, int) */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.assertId(getCurrentFile(), true, 2);

    int[] pos = computeIndices(no);
    IFormatReader r = getReader(getSeries(), pos[0]);
    int ino = pos[1];

    if (ino < r.getImageCount()) return r.openBytes(ino, buf, x, y, w, h);

    // return a blank image to cover for the fact that
    // this file does not contain enough image planes
    Arrays.fill(buf, (byte) 0);
    return buf;
  }

  /* @see IFormatReader#openPlane(int, int, int, int, int) */
  public Object openPlane(int no, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.assertId(getCurrentFile(), true, 2);

    IFormatReader r = getReader(no);
    int ino = getAdjustedIndex(no);
    if (ino < r.getImageCount()) return r.openPlane(ino, x, y, w, h);

    return null;
  }

  /* @see IFormatReader#openThumbBytes(int) */
  public byte[] openThumbBytes(int no) throws FormatException, IOException {
    FormatTools.assertId(getCurrentFile(), true, 2);

    IFormatReader r = getReader(no);
    int ino = getAdjustedIndex(no);
    if (ino < r.getImageCount()) return r.openThumbBytes(ino);

    // return a blank image to cover for the fact that
    // this file does not contain enough image planes
    return externals[getExternalSeries()].getBlankThumbBytes();
  }

  /* @see IFormatReader#close() */
  public void close() throws IOException {
    close(false);
  }

  /* @see IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (externals != null) {
      for (ExternalSeries s : externals) {
        if (s != null && s.getReaders() != null) {
          for (DimensionSwapper r : s.getReaders()) {
            if (r != null) r.close(fileOnly);
          }
        }
      }
    }
    if (!fileOnly) {
      noStitch = false;
      externals = null;
      sizeZ = sizeC = sizeT = null;
      lenZ = lenC = lenT = null;
      core = null;
      series = 0;
      store = null;
    }
  }

  /* @see IFormatReader#getSeriesCount() */
  public int getSeriesCount() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getSeriesCount() : core.length;
  }

  /* @see IFormatReader#setSeries(int) */
  public void setSeries(int no) {
    FormatTools.assertId(getCurrentFile(), true, 2);
    int n = reader.getSeriesCount();
    if (n > 1) reader.setSeries(no);
    else series = no;
  }

  /* @see IFormatReader#getSeries() */
  public int getSeries() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return reader.getSeries() > 0 ? reader.getSeries() : series;
  }

  /* @see IFormatReader#setGroupFiles(boolean) */
  public void setGroupFiles(boolean group) {
    this.group = group;
  }

  /* @see IFormatReader#isGroupFiles(boolean) */
  public boolean isGroupFiles() {
    return group;
  }

  /* @see IFormatReader#setNormalized(boolean) */
  public void setNormalized(boolean normalize) {
    FormatTools.assertId(getCurrentFile(), false, 2);
    if (externals == null) reader.setNormalized(normalize);
    else {
      for (ExternalSeries s : externals) {
        for (DimensionSwapper r : s.getReaders()) {
          r.setNormalized(normalize);
        }
      }
    }
  }

  /**
   * @deprecated
   * @see IFormatReader#setMetadataCollected(boolean)
   */
  public void setMetadataCollected(boolean collect) {
    FormatTools.assertId(getCurrentFile(), false, 2);
    if (externals == null) reader.setMetadataCollected(collect);
    else {
      for (ExternalSeries s : externals) {
        for (DimensionSwapper r : s.getReaders()) {
          r.setMetadataCollected(collect);
        }
      }
    }
  }

  /* @see IFormatReader#setOriginalMetadataPopulated(boolean) */
  public void setOriginalMetadataPopulated(boolean populate) {
    FormatTools.assertId(getCurrentFile(), false, 1);
    if (externals == null) reader.setOriginalMetadataPopulated(populate);
    else {
      for (ExternalSeries s : externals) {
        for (DimensionSwapper r : s.getReaders()) {
          r.setOriginalMetadataPopulated(populate);
        }
      }
    }
  }

  /* @see IFormatReader#getUsedFiles() */
  public String[] getUsedFiles() {
    FormatTools.assertId(getCurrentFile(), true, 2);

    if (noStitch) return reader.getUsedFiles();

    // returning the files list directly here is fast, since we do not
    // have to call initFile on each constituent file; but we can only do so
    // when each constituent file does not itself have multiple used files

    Vector<String> files = new Vector<String>();
    for (ExternalSeries s : externals) {
      String[] f = s.getFiles();
      for (String file : f) {
        if (!files.contains(file)) files.add(file);
      }
    }
    return files.toArray(new String[files.size()]);
  }

  /* @see IFormatReader#getUsedFiles() */
  public String[] getUsedFiles(boolean noPixels) {
    return noPixels && noStitch ?
      reader.getUsedFiles(noPixels) : getUsedFiles();
  }

  /* @see IFormatReader#getSeriesUsedFiles() */
  public String[] getSeriesUsedFiles() {
    return getUsedFiles();
  }

  /* @see IFormatReader#getSeriesUsedFiles(boolean) */
  public String[] getSeriesUsedFiles(boolean noPixels) {
    return getUsedFiles(noPixels);
  }

  /* @see IFormatReader#getAdvancedUsedFiles(boolean) */
  public FileInfo[] getAdvancedUsedFiles(boolean noPixels) {
    if (noStitch) return reader.getAdvancedUsedFiles(noPixels);
    String[] files = getUsedFiles(noPixels);
    if (files == null) return null;
    FileInfo[] infos = new FileInfo[files.length];
    for (int i=0; i<infos.length; i++) {
      infos[i] = new FileInfo();
      infos[i].filename = files[i];
      try {
        infos[i].reader = ((DimensionSwapper) reader).unwrap().getClass();
      }
      catch (FormatException e) {
        LOGGER.debug("", e);
      }
      catch (IOException e) {
        LOGGER.debug("", e);
      }
      infos[i].usedToInitialize = files[i].endsWith(getCurrentFile());
    }
    return infos;
  }

  /* @see IFormatReader#getAdvancedSeriesUsedFiles(boolean) */
  public FileInfo[] getAdvancedSeriesUsedFiles(boolean noPixels) {
    if (noStitch) return reader.getAdvancedSeriesUsedFiles(noPixels);
    String[] files = getSeriesUsedFiles(noPixels);
    if (files == null) return null;
    FileInfo[] infos = new FileInfo[files.length];
    for (int i=0; i<infos.length; i++) {
      infos[i] = new FileInfo();
      infos[i].filename = files[i];
      try {
        infos[i].reader = ((DimensionSwapper) reader).unwrap().getClass();
      }
      catch (FormatException e) {
        LOGGER.debug("", e);
      }
      catch (IOException e) {
        LOGGER.debug("", e);
      }
      infos[i].usedToInitialize = files[i].endsWith(getCurrentFile());
    }
    return infos;
  }

  /* @see IFormatReader#getIndex(int, int, int) */
  public int getIndex(int z, int c, int t) {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return FormatTools.getIndex(this, z, c, t);
  }

  /* @see IFormatReader#getZCTCoords(int) */
  public int[] getZCTCoords(int index) {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getZCTCoords(index) :
      FormatTools.getZCTCoords(core[getSeries()].dimensionOrder,
      getSizeZ(), getEffectiveSizeC(), getSizeT(), getImageCount(), index);
  }

  /* @see IFormatReader#getSeriesMetadata() */
  public Hashtable<String, Object> getSeriesMetadata() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getSeriesMetadata() :
      core[getSeries()].seriesMetadata;
  }

  /* @see IFormatReader#getCoreMetadata() */
  public CoreMetadata[] getCoreMetadata() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getCoreMetadata() : core;
  }

  /* @see IFormatReader#setMetadataStore(MetadataStore) */
  public void setMetadataStore(MetadataStore store) {
    FormatTools.assertId(getCurrentFile(), false, 2);
    reader.setMetadataStore(store);
    this.store = store;
  }

  /* @see IFormatReader#getMetadataStore() */
  public MetadataStore getMetadataStore() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getMetadataStore() : store;
  }

  /* @see IFormatReader#getMetadataStoreRoot() */
  public Object getMetadataStoreRoot() {
    FormatTools.assertId(getCurrentFile(), true, 2);
    return noStitch ? reader.getMetadataStoreRoot() : store.getRoot();
  }

  /* @see IFormatReader#getUnderlyingReaders() */
  public IFormatReader[] getUnderlyingReaders() {
    List<IFormatReader> list = new ArrayList<IFormatReader>();
    for (ExternalSeries s : externals) {
      for (DimensionSwapper r : s.getReaders()) {
        list.add(r);
      }
    }
    return list.toArray(new IFormatReader[0]);
  }

  /* @see IFormatReader#setId(String) */
  public void setId(String id) throws FormatException, IOException {
    close();
    initFile(id);
  }

  // -- Internal FormatReader API methods --

  /** Initializes the given file or file pattern. */
  protected void initFile(String id) throws FormatException, IOException {
    LOGGER.debug("initFile: {}", id);

    FilePattern fp = new FilePattern(id);
    if (!patternIds) {
      patternIds = fp.isValid() && fp.getFiles().length > 1;
    }
    else {
      patternIds =
        !new Location(id).exists() && Location.getMappedId(id).equals(id);
    }

    boolean mustGroup = false;
    if (patternIds) {
      mustGroup = fp.isValid() &&
        reader.fileGroupOption(fp.getFiles()[0]) == FormatTools.MUST_GROUP;
    }
    else {
      mustGroup = reader.fileGroupOption(id) == FormatTools.MUST_GROUP;
    }

    if (mustGroup || !group) {
      // reader subclass is handling file grouping
      noStitch = true;
      reader.close();
      reader.setGroupFiles(group);

      if (patternIds && fp.isValid()) {
        reader.setId(fp.getFiles()[0]);
      }
      else reader.setId(id);
      return;
    }

    if (fp.isRegex()) {
      setCanChangePattern(false);
    }

    String[] patterns = findPatterns(id);
    if (patterns.length == 0) patterns = new String[] {id};
    externals = new ExternalSeries[patterns.length];

    for (int i=0; i<externals.length; i++) {
      externals[i] = new ExternalSeries(new FilePattern(patterns[i]));
    }
    fp = new FilePattern(patterns[0]);

    reader.close();
    reader.setGroupFiles(false);

    if (!fp.isValid()) {
      throw new FormatException("Invalid file pattern: " + fp.getPattern());
    }
    reader.setId(fp.getFiles()[0]);

    String msg = " Please rename your files or disable file stitching.";
    if (reader.getSeriesCount() > 1 && externals.length > 1) {
      throw new FormatException("Unsupported grouping: File pattern contains " +
        "multiple files and each file contains multiple series." + msg);
    }

    if (reader.getUsedFiles().length > 1) {
      noStitch = true;
      return;
    }

    AxisGuesser guesser = new AxisGuesser(fp, reader.getDimensionOrder(),
      reader.getSizeZ(), reader.getSizeT(), reader.getEffectiveSizeC(),
      reader.isOrderCertain());

    // use the dimension order recommended by the axis guesser
    ((DimensionSwapper) reader).swapDimensions(guesser.getAdjustedOrder());

    // if this is a multi-series dataset, we need some special logic
    int seriesCount = externals.length;
    if (externals.length == 1) {
      seriesCount = reader.getSeriesCount();
    }

    // verify that file pattern is valid and matches existing files
    if (!fp.isValid()) {
      throw new FormatException("Invalid " +
        (patternIds ? "file pattern" : "filename") +
        " (" + id + "): " + fp.getErrorMessage() + msg);
    }
    String[] files = fp.getFiles();

    if (files == null) {
      throw new FormatException("No files matching pattern (" +
        fp.getPattern() + "). " + msg);
    }

    for (int i=0; i<files.length; i++) {
      String file = files[i];

      // HACK: skip file existence check for fake files
      if (file.toLowerCase().endsWith(".fake")) continue;

      if (!new Location(file).exists()) {
        throw new FormatException("File #" + i +
          " (" + file + ") does not exist.");
      }
    }

    // determine reader type for these files; assume all are the same type
    Class<? extends IFormatReader> readerClass =
      ((DimensionSwapper) reader).unwrap(files[0]).getClass();

    sizeZ = new int[seriesCount];
    sizeC = new int[seriesCount];
    sizeT = new int[seriesCount];
    boolean[] certain = new boolean[seriesCount];
    lenZ = new int[seriesCount][];
    lenC = new int[seriesCount][];
    lenT = new int[seriesCount][];

    // analyze first file; assume each file has the same parameters
    core = new CoreMetadata[seriesCount];
    int oldSeries = getSeries();
    for (int i=0; i<seriesCount; i++) {
      IFormatReader rr = getReader(i, 0);

      core[i] = new CoreMetadata();

      core[i].sizeX = rr.getSizeX();
      core[i].sizeY = rr.getSizeY();
      // NB: core.sizeZ populated in computeAxisLengths below
      // NB: core.sizeC populated in computeAxisLengths below
      // NB: core.sizeT populated in computeAxisLengths below
      core[i].pixelType = rr.getPixelType();

      ExternalSeries external = externals[getExternalSeries(i)];
      core[i].imageCount = rr.getImageCount() * external.getFiles().length;
      core[i].thumbSizeX = rr.getThumbSizeX();
      core[i].thumbSizeY = rr.getThumbSizeY();
      // NB: core.cLengths[i] populated in computeAxisLengths below
      // NB: core.cTypes[i] populated in computeAxisLengths below
      core[i].dimensionOrder = rr.getDimensionOrder();
      // NB: core.orderCertain[i] populated below
      core[i].rgb = rr.isRGB();
      core[i].littleEndian = rr.isLittleEndian();
      core[i].interleaved = rr.isInterleaved();
      core[i].seriesMetadata = rr.getSeriesMetadata();
      core[i].indexed = rr.isIndexed();
      core[i].falseColor = rr.isFalseColor();
      core[i].bitsPerPixel = rr.getBitsPerPixel();
      sizeZ[i] = rr.getSizeZ();
      sizeC[i] = rr.getSizeC();
      sizeT[i] = rr.getSizeT();
      certain[i] = rr.isOrderCertain();
    }

    // order may need to be adjusted
    for (int i=0; i<seriesCount; i++) {
      setSeries(i);
      AxisGuesser ag = externals[getExternalSeries()].getAxisGuesser();
      core[i].dimensionOrder = ag.getAdjustedOrder();
      core[i].orderCertain = ag.isCertain();
      computeAxisLengths();
    }
    setSeries(oldSeries);

    // populate metadata store
    store = reader.getMetadataStore();
    // don't overwrite pixel info if files aren't actually grouped
    if (!noStitch) {
      MetadataTools.populatePixels(store, this, false, false);
      if (reader.getSeriesCount() == 1 && getSeriesCount() > 1) {
        for (int i=0; i<getSeriesCount(); i++) {
          int index = getExternalSeries(i);
          String pattern = externals[index].getFilePattern().getPattern();
          pattern = pattern.substring(pattern.lastIndexOf(File.separator) + 1);
          store.setImageName(pattern, i);
        }
      }
    }
  }

  // -- Helper methods --

  private int getExternalSeries() {
    return getExternalSeries(getSeries());
  }

  private int getExternalSeries(int currentSeries) {
    if (reader.getSeriesCount() > 1) return 0;
    return currentSeries;
  }

  /** Computes axis length arrays, and total axis lengths. */
  protected void computeAxisLengths() throws FormatException {
    int sno = getSeries();
    ExternalSeries s = externals[getExternalSeries()];
    FilePattern p = s.getFilePattern();

    int[] count = p.getCount();

    initReader(sno, 0);

    AxisGuesser ag = s.getAxisGuesser();
    int[] axes = ag.getAxisTypes();

    int numZ = ag.getAxisCountZ();
    int numC = ag.getAxisCountC();
    int numT = ag.getAxisCountT();

    if (axes.length == 0 && s.getFiles().length > 1) {
      axes = new int[] {AxisGuesser.T_AXIS};
      count = new int[] {s.getFiles().length};
      numT++;
    }

    core[sno].sizeZ = sizeZ[sno];
    core[sno].sizeC = sizeC[sno];
    core[sno].sizeT = sizeT[sno];
    lenZ[sno] = new int[numZ + 1];
    lenC[sno] = new int[numC + 1];
    lenT[sno] = new int[numT + 1];
    lenZ[sno][0] = sizeZ[sno];
    lenC[sno][0] = sizeC[sno];
    lenT[sno][0] = sizeT[sno];

    for (int i=0, z=1, c=1, t=1; i<count.length; i++) {
      switch (axes[i]) {
        case AxisGuesser.Z_AXIS:
          core[sno].sizeZ *= count[i];
          lenZ[sno][z++] = count[i];
          break;
        case AxisGuesser.C_AXIS:
          core[sno].sizeC *= count[i];
          lenC[sno][c++] = count[i];
          break;
        case AxisGuesser.T_AXIS:
          core[sno].sizeT *= count[i];
          lenT[sno][t++] = count[i];
          break;
        case AxisGuesser.S_AXIS:
          break;
        default:
          throw new FormatException("Unknown axis type for axis #" +
            i + ": " + axes[i]);
      }
    }
    core[sno].imageCount = core[sno].sizeZ * core[sno].sizeT;
    if (!isRGB()) {
      core[sno].imageCount *= core[sno].sizeC;
    }
    else core[sno].imageCount *= reader.getEffectiveSizeC();

    int[] cLengths = reader.getChannelDimLengths();
    String[] cTypes = reader.getChannelDimTypes();
    int cCount = 0;
    for (int i=0; i<cLengths.length; i++) {
      if (cLengths[i] > 1) cCount++;
    }
    for (int i=1; i<lenC[sno].length; i++) {
      if (lenC[sno][i] > 1) cCount++;
    }
    if (cCount == 0) {
      core[sno].cLengths = new int[] {1};
      core[sno].cTypes = new String[] {FormatTools.CHANNEL};
    }
    else {
      core[sno].cLengths = new int[cCount];
      core[sno].cTypes = new String[cCount];
    }
    int c = 0;
    for (int i=0; i<cLengths.length; i++) {
      if (cLengths[i] == 1) continue;
      core[sno].cLengths[c] = cLengths[i];
      core[sno].cTypes[c] = cTypes[i];
      c++;
    }
    for (int i=1; i<lenC[sno].length; i++) {
      if (lenC[sno][i] == 1) continue;
      core[sno].cLengths[c] = lenC[sno][i];
      core[sno].cTypes[c] = FormatTools.CHANNEL;
    }
  }

  /**
   * Gets the file index, and image index into that file,
   * corresponding to the given global image index.
   *
   * @return An array of size 2, dimensioned {file index, image index}.
   */
  protected int[] computeIndices(int no) throws FormatException, IOException {
    if (noStitch) return new int[] {0, no};
    int sno = getSeries();
    ExternalSeries s = externals[getExternalSeries()];

    int[] axes = s.getAxisGuesser().getAxisTypes();
    int[] count = s.getFilePattern().getCount();

    // get Z, C and T positions
    int[] zct = getZCTCoords(no);
    int[] posZ = FormatTools.rasterToPosition(lenZ[sno], zct[0]);
    int[] posC = FormatTools.rasterToPosition(lenC[sno], zct[1]);
    int[] posT = FormatTools.rasterToPosition(lenT[sno], zct[2]);

    int[] tmpZ = new int[posZ.length];
    System.arraycopy(posZ, 0, tmpZ, 0, tmpZ.length);
    int[] tmpC = new int[posC.length];
    System.arraycopy(posC, 0, tmpC, 0, tmpC.length);
    int[] tmpT = new int[posT.length];
    System.arraycopy(posT, 0, tmpT, 0, tmpT.length);

    // convert Z, C and T position lists into file index and image index
    int[] pos = new int[axes.length];
    int z = 1, c = 1, t = 1;
    for (int i=0; i<axes.length; i++) {
      if (axes[i] == AxisGuesser.Z_AXIS) pos[i] = posZ[z++];
      else if (axes[i] == AxisGuesser.C_AXIS) pos[i] = posC[c++];
      else if (axes[i] == AxisGuesser.T_AXIS) pos[i] = posT[t++];
      else if (axes[i] == AxisGuesser.S_AXIS) {
        pos[i] = 0;
      }
      else {
        throw new FormatException("Unknown axis type for axis #" +
          i + ": " + axes[i]);
      }
    }

    int fno = FormatTools.positionToRaster(count, pos);
    DimensionSwapper r = getReader(sno, fno);

    int ino;
    if (posZ[0] < r.getSizeZ() && posC[0] < r.getSizeC() &&
      posT[0] < r.getSizeT())
    {
      if (r.isRGB() && (posC[0] * r.getRGBChannelCount() >= lenC[sno][0])) {
        posC[0] /= lenC[sno][0];
      }
      ino = FormatTools.getIndex(r, posZ[0], posC[0], posT[0]);
    }
    else ino = Integer.MAX_VALUE; // coordinates out of range

    return new int[] {fno, ino};
  }

  protected void initReader(int sno, int fno) {
    int external = getExternalSeries(sno);
    DimensionSwapper r = externals[external].getReaders()[fno];
    try {
      if (r.getCurrentFile() == null) {
        r.setGroupFiles(false);
      }
      r.setId(externals[external].getFiles()[fno]);
      r.setSeries(reader.getSeriesCount() > 1 ? sno : 0);
      String newOrder = ((DimensionSwapper) reader).getInputOrder();
      if ((externals[external].getFiles().length > 1 || !r.isOrderCertain()) &&
        (r.getRGBChannelCount() == 1 ||
        newOrder.indexOf("C") == r.getDimensionOrder().indexOf("C")))
      {
        r.swapDimensions(newOrder);
      }
      r.setOutputOrder(newOrder);
    }
    catch (FormatException e) {
      LOGGER.debug("", e);
    }
    catch (IOException e) {
      LOGGER.debug("", e);
    }
  }

  // -- Helper classes --

  class ExternalSeries {
    private DimensionSwapper[] readers;
    private String[] files;
    private FilePattern pattern;
    private byte[] blankThumbBytes;
    private String originalOrder;
    private AxisGuesser ag;
    private int imagesPerFile;

    public ExternalSeries(FilePattern pattern)
      throws FormatException, IOException
    {
      this.pattern = pattern;
      files = this.pattern.getFiles();

      readers = new DimensionSwapper[files.length];
      for (int i=0; i<readers.length; i++) {
        if (classList != null) {
          readers[i] = new DimensionSwapper(new ImageReader(classList));
        }
        else readers[i] = new DimensionSwapper();
        readers[i].setGroupFiles(false);
      }
      readers[0].setId(files[0]);

      ag = new AxisGuesser(this.pattern, readers[0].getDimensionOrder(),
        readers[0].getSizeZ(), readers[0].getSizeT(),
        readers[0].getSizeC(), readers[0].isOrderCertain());

      blankThumbBytes = new byte[FormatTools.getPlaneSize(readers[0],
        readers[0].getThumbSizeX(), readers[0].getThumbSizeY())];

      originalOrder = readers[0].getDimensionOrder();
      imagesPerFile = readers[0].getImageCount();
    }

    public DimensionSwapper[] getReaders() {
      return readers;
    }

    public FilePattern getFilePattern() {
      return pattern;
    }

    public String getOriginalOrder() {
      return originalOrder;
    }

    public AxisGuesser getAxisGuesser() {
      return ag;
    }

    public byte[] getBlankThumbBytes() {
      return blankThumbBytes;
    }

    public String[] getFiles() {
      return files;
    }

    public int getImagesPerFile() {
      return imagesPerFile;
    }

  }

}
