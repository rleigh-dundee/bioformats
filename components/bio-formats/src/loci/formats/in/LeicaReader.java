//
// LeicaReader.java
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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.StringTokenizer;
import java.util.Vector;

import loci.common.DataTools;
import loci.common.DateTools;
import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.formats.AxisGuesser;
import loci.formats.CoreMetadata;
import loci.formats.FilePattern;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.MetadataStore;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffConstants;
import loci.formats.tiff.TiffParser;

import ome.xml.model.enums.Correction;
import ome.xml.model.enums.Immersion;
import ome.xml.model.primitives.PositiveFloat;
import ome.xml.model.primitives.PositiveInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LeicaReader is the file format reader for Leica files.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/in/LeicaReader.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/in/LeicaReader.java;hb=HEAD">Gitweb</a></dd></dl>
 *
 * @author Melissa Linkert melissa at glencoesoftware.com
 */
public class LeicaReader extends FormatReader {

  // -- Constants -

  private static final Logger LOGGER =
    LoggerFactory.getLogger(LeicaReader.class);

  public static final String[] LEI_SUFFIX = {"lei"};

  /** All Leica TIFFs have this tag. */
  private static final int LEICA_MAGIC_TAG = 33923;

  /** Format for dates. */
  private static final String DATE_FORMAT = "yyyy:MM:dd,HH:mm:ss:SSS";

  /** IFD tags. */
  private static final Integer SERIES = new Integer(10);
  private static final Integer IMAGES = new Integer(15);
  private static final Integer DIMDESCR = new Integer(20);
  private static final Integer FILTERSET = new Integer(30);
  private static final Integer TIMEINFO = new Integer(40);
  private static final Integer SCANNERSET = new Integer(50);
  private static final Integer EXPERIMENT = new Integer(60);
  private static final Integer LUTDESC = new Integer(70);
  private static final Integer CHANDESC = new Integer(80);
  private static final Integer SEQUENTIALSET = new Integer(90);
  private static final Integer SEQ_SCANNERSET = new Integer(200);
  private static final Integer SEQ_FILTERSET = new Integer(700);

  private static final int SEQ_SCANNERSET_END = 300;
  private static final int SEQ_FILTERSET_END = 800;

  private static final Hashtable<Integer, String> DIMENSION_NAMES =
    makeDimensionTable();

  // -- Fields --

  protected IFDList ifds;

  /** Array of IFD-like structures containing metadata. */
  protected IFDList headerIFDs;

  /** Helper readers. */
  protected MinimalTiffReader tiff;

  /** Array of image file names. */
  protected Vector[] files;

  /** Number of series in the file. */
  private int numSeries;

  /** Name of current LEI file */
  private String leiFilename;

  /** Length of each file name. */
  private int fileLength;

  private boolean[] valid;

  private String[][] timestamps;

  private Vector<String> seriesNames;
  private Vector<String> seriesDescriptions;
  private int lastPlane = 0, nameLength = 0;

  private double[][] physicalSizes;
  private double[] pinhole, exposureTime;

  private int nextDetector = 0, nextChannel = 0;
  private Vector<Integer> activeChannelIndices = new Vector<Integer>();
  private boolean sequential = false;

  private Vector[] channelNames;
  private Vector[] emWaves;
  private Vector[] exWaves;

  private boolean[][] cutInPopulated;
  private boolean[][] cutOutPopulated;
  private boolean[][] filterRefPopulated;

  private Double detectorOffset, detectorVoltage;

  private int[] tileWidth, tileHeight;

  private int[][] channelColor;

  // -- Constructor --

  /** Constructs a new Leica reader. */
  public LeicaReader() {
    super("Leica", new String[] {"lei", "tif", "tiff", "raw"});
    domains = new String[] {FormatTools.LM_DOMAIN};
    hasCompanionFiles = true;
    datasetDescription = "One .lei file with at least one .tif/.tiff file " +
      "and an optional .txt file";
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isSingleFile(String) */
  public boolean isSingleFile(String id) throws FormatException, IOException {
    return false;
  }

  /* @see loci.formats.IFormatReader#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    if (checkSuffix(name, LEI_SUFFIX)) return true;
    if (!checkSuffix(name, TiffReader.TIFF_SUFFIXES) &&
      !checkSuffix(name, "raw"))
    {
      return false;
    }

    if (!open) return false; // not allowed to touch the file system

    // check for that there is an .lei file in the same directory
    String prefix = name;
    if (prefix.indexOf(".") != -1) {
      prefix = prefix.substring(0, prefix.lastIndexOf("."));
    }
    Location lei = new Location(prefix + ".lei");
    if (!lei.exists()) {
      lei = new Location(prefix + ".LEI");
      while (!lei.exists() && prefix.indexOf("_") != -1) {
        prefix = prefix.substring(0, prefix.lastIndexOf("_"));
        lei = new Location(prefix + ".lei");
        if (!lei.exists()) lei = new Location(prefix + ".LEI");
      }
    }
    return lei.exists();
  }

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    TiffParser tp = new TiffParser(stream);
    IFD ifd = tp.getFirstIFD();
    if (ifd == null) return false;
    return ifd.containsKey(new Integer(LEICA_MAGIC_TAG));
  }

  /* @see loci.formats.IFormatReader#get8BitLookupTable() */
  public byte[][] get8BitLookupTable() throws FormatException, IOException {
    FormatTools.assertId(currentId, true, 1);
    try {
      int index = (int) Math.min(lastPlane, files[series].size() - 1);
      tiff.setId((String) files[series].get(index));
      return tiff.get8BitLookupTable();
    }
    catch (FormatException e) {
      LOGGER.debug("Failed to retrieve lookup table", e);
    }
    catch (IOException e) {
      LOGGER.debug("Failed to retrieve lookup table", e);
    }
    return null;
  }

  /* @see loci.formats.IFormatReader#get16BitLookupTable() */
  public short[][] get16BitLookupTable() throws FormatException, IOException {
    FormatTools.assertId(currentId, true, 1);
    try {
      int index = (int) Math.min(lastPlane, files[series].size() - 1);
      tiff.setId((String) files[series].get(index));
      return tiff.get16BitLookupTable();
    }
    catch (FormatException e) {
      LOGGER.debug("Failed to retrieve lookup table", e);
    }
    catch (IOException e) {
      LOGGER.debug("Failed to retrieve lookup table", e);
    }
    return null;
  }

  /* @see loci.formats.IFormatReader#fileGroupOption(String) */
  public int fileGroupOption(String id) throws FormatException, IOException {
    return FormatTools.MUST_GROUP;
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    lastPlane = no;

    int fileIndex = no < files[series].size() ? no : 0;
    int planeIndex = no < files[series].size() ? 0 : no;
    String filename = (String) files[series].get(fileIndex);

    if (new Location(filename).exists()) {
      if (checkSuffix(filename, TiffReader.TIFF_SUFFIXES)) {
        tiff.setId(filename);
        return tiff.openBytes(planeIndex, buf, x, y, w, h);
      }
      else {
        RandomAccessInputStream s = new RandomAccessInputStream(filename);
        s.seek(planeIndex * FormatTools.getPlaneSize(this));
        readPlane(s, x, y, w, h, buf);
        s.close();
      }
    }

    // imitate Leica's software and return a blank plane if the
    // appropriate TIFF file is missing
    return buf;
  }

  /* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
  public String[] getSeriesUsedFiles(boolean noPixels) {
    FormatTools.assertId(currentId, true, 1);
    Vector<String> v = new Vector<String>();
    if (leiFilename != null) v.add(leiFilename);
    if (!noPixels && files != null) {
      for (Object file : files[getSeries()]) {
        if (file != null && new Location((String) file).exists()) {
          v.add((String) file);
        }
      }
    }
    return v.toArray(new String[v.size()]);
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (tiff != null) tiff.close(fileOnly);
    if (!fileOnly) {
      leiFilename = null;
      files = null;
      ifds = headerIFDs = null;
      tiff = null;
      seriesNames = null;
      numSeries = 0;
      lastPlane = 0;
      physicalSizes = null;
      seriesDescriptions = null;
      pinhole = exposureTime = null;
      nextDetector = 0;
      nextChannel = 0;
      sequential = false;
      activeChannelIndices.clear();
      channelNames = null;
      emWaves = null;
      exWaves = null;
      cutInPopulated = null;
      cutOutPopulated = null;
      filterRefPopulated = null;
    }
  }

  /* @see loci.formats.IFormatReader#getOptimalTileWidth() */
  public int getOptimalTileWidth() {
    FormatTools.assertId(currentId, true, 1);
    if (tileWidth[getSeries()] != 0) {
      return tileWidth[getSeries()];
    }
    return super.getOptimalTileWidth();
  }

  /* @see loci.formats.IFormatReader#getOptimalTileHeight() */
  public int getOptimalTileHeight() {
    FormatTools.assertId(currentId, true, 1);
    if (tileHeight[getSeries()] != 0) {
      return tileHeight[getSeries()];
    }
    return super.getOptimalTileHeight();
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    close();

    String leiFile = findLEIFile(id);
    if (leiFile == null || leiFile.trim().length() == 0 ||
      new Location(leiFile).isDirectory())
    {
      if (checkSuffix(id, TiffReader.TIFF_SUFFIXES)) {
        super.initFile(id);
        TiffReader r = new TiffReader();
        r.setMetadataStore(getMetadataStore());
        r.setId(id);

        core = r.getCoreMetadata();
        metadataStore = r.getMetadataStore();

        Hashtable globalMetadata = r.getGlobalMetadata();
        for (Object key : globalMetadata.keySet()) {
          addGlobalMeta(key.toString(), globalMetadata.get(key));
        }

        r.close();

        files = new Vector[] {new Vector()};
        files[0].add(id);
        tiff = new MinimalTiffReader();

        return;
      }
      else {
        throw new FormatException("LEI file not found.");
      }
    }

    // parse the LEI file

    super.initFile(leiFile);

    leiFilename =
      new File(leiFile).exists()? new Location(leiFile).getAbsolutePath() : id;

    in = new RandomAccessInputStream(leiFile);
    MetadataLevel metadataLevel = metadataOptions.getMetadataLevel();

    seriesNames = new Vector<String>();

    byte[] fourBytes = new byte[4];
    in.read(fourBytes);
    core[0].littleEndian = (fourBytes[0] == TiffConstants.LITTLE &&
      fourBytes[1] == TiffConstants.LITTLE &&
      fourBytes[2] == TiffConstants.LITTLE &&
      fourBytes[3] == TiffConstants.LITTLE);
    boolean realLittleEndian = isLittleEndian();

    in.order(isLittleEndian());

    LOGGER.info("Reading metadata blocks");

    in.skipBytes(8);
    int addr = in.readInt();

    headerIFDs = new IFDList();

    while (addr != 0) {
      IFD ifd = new IFD();
      headerIFDs.add(ifd);
      in.seek(addr + 4);

      int tag = in.readInt();

      while (tag != 0) {
        // create the IFD structure
        int offset = in.readInt();

        long pos = in.getFilePointer();
        in.seek(offset + 12);

        int size = in.readInt();
        ifd.putIFDValue(tag, in.getFilePointer());
        in.seek(pos);
        tag = in.readInt();
      }

      addr = in.readInt();
    }

    numSeries = headerIFDs.size();

    tileWidth = new int[numSeries];
    tileHeight = new int[numSeries];

    core = new CoreMetadata[numSeries];
    for (int i=0; i<numSeries; i++) {
      core[i] = new CoreMetadata();
    }

    files = new Vector[numSeries];

    channelNames = new Vector[getSeriesCount()];
    emWaves = new Vector[getSeriesCount()];
    exWaves = new Vector[getSeriesCount()];
    cutInPopulated = new boolean[getSeriesCount()][];
    cutOutPopulated = new boolean[getSeriesCount()][];
    filterRefPopulated = new boolean[getSeriesCount()][];

    for (int i=0; i<getSeriesCount(); i++) {
      channelNames[i] = new Vector();
      emWaves[i] = new Vector();
      exWaves[i] = new Vector();
    }

    // determine the length of a filename

    LOGGER.info("Parsing metadata blocks");

    core[0].littleEndian = !isLittleEndian();

    int seriesIndex = 0;
    int invalidCount = 0;
    valid = new boolean[numSeries];
    timestamps = new String[headerIFDs.size()][];
    for (int i=0; i<headerIFDs.size(); i++) {
      IFD ifd = headerIFDs.get(i);
      valid[i] = true;
      if (ifd.get(SERIES) != null) {
        long offset = ((Long) ifd.get(SERIES)).longValue();
        in.seek(offset + 8);
        nameLength = in.readInt() * 2;
      }

      in.seek(((Long) ifd.get(IMAGES)).longValue());
      parseFilenames(i);
      if (!valid[i]) invalidCount++;
    }

    numSeries -= invalidCount;

    int[] count = new int[getSeriesCount()];
    for (int i=0; i<getSeriesCount(); i++) {
      count[i] = core[i].imageCount;
    }

    Vector[] tempFiles = files;
    IFDList tempIFDs = headerIFDs;
    core = new CoreMetadata[numSeries];
    files = new Vector[numSeries];
    headerIFDs = new IFDList();
    int index = 0;

    for (int i=0; i<numSeries; i++) {
      core[i] = new CoreMetadata();
      while (!valid[index]) index++;
      core[i].imageCount = count[index];
      files[i] = tempFiles[index];
      Object[] sorted = files[i].toArray();
      Arrays.sort(sorted);
      files[i].clear();
      files[i].addAll(Arrays.asList(sorted));

      headerIFDs.add(tempIFDs.get(index));
      index++;
    }

    tiff = new MinimalTiffReader();

    LOGGER.info("Populating metadata");

    if (headerIFDs == null) headerIFDs = ifds;

    seriesDescriptions = new Vector<String>();

    physicalSizes = new double[headerIFDs.size()][5];
    pinhole = new double[headerIFDs.size()];
    exposureTime = new double[headerIFDs.size()];

    channelColor = new int[headerIFDs.size()][];

    for (int i=0; i<headerIFDs.size(); i++) {
      IFD ifd = headerIFDs.get(i);

      core[i].littleEndian = isLittleEndian();
      setSeries(i);
      Integer[] keys = ifd.keySet().toArray(new Integer[ifd.size()]);
      Arrays.sort(keys);

      for (Integer key : keys) {
        long offset = ((Long) ifd.get(key)).longValue();
        in.seek(offset);

        if (key.equals(SERIES)) {
          parseSeriesTag();
        }
        else if (key.equals(IMAGES)) {
          parseImageTag(i);
        }
        else if (key.equals(DIMDESCR)) {
          parseDimensionTag(i);
        }
        else if (key.equals(TIMEINFO) && metadataLevel != MetadataLevel.MINIMUM)
        {
          parseTimeTag(i);
        }
        else if (key.equals(EXPERIMENT) &&
          metadataLevel != MetadataLevel.MINIMUM)
        {
          parseExperimentTag();
        }
        else if (key.equals(LUTDESC)) {
          parseLUT(i);
        }
        else if (key.equals(CHANDESC) && metadataLevel != MetadataLevel.MINIMUM)
        {
          parseChannelTag();
        }
      }

      core[i].orderCertain = true;
      core[i].littleEndian = isLittleEndian();
      core[i].falseColor = true;
      core[i].metadataComplete = true;
      core[i].interleaved = false;

      String filename = (String) files[i].get(0);

      if (checkSuffix(filename, TiffReader.TIFF_SUFFIXES)) {
        tiff.setId(filename);

        core[i].sizeX = tiff.getSizeX();
        core[i].sizeY = tiff.getSizeY();
        tileWidth[i] = tiff.getOptimalTileWidth();
        tileHeight[i] = tiff.getOptimalTileHeight();
      }
    }

    for (int i=0; i<numSeries; i++) {
      setSeries(i);
      if (getSizeZ() == 0) core[i].sizeZ = 1;
      if (getSizeT() == 0) core[i].sizeT = 1;
      if (getSizeC() == 0) core[i].sizeC = 1;
      if (getImageCount() == 0) core[i].imageCount = 1;
      if (getImageCount() == 1 && getSizeZ() * getSizeT() > 1) {
        core[i].sizeZ = 1;
        core[i].sizeT = 1;
      }
      if (getSizeY() == 1 || getSizeY() == getSizeZ() ||
        getSizeY() == getSizeT())
      {
        // XZ or XT scan
        if (getSizeZ() > 1 && getImageCount() == getSizeC() * getSizeT()) {
          core[i].sizeY = getSizeZ();
          core[i].sizeZ = 1;
        }
        else if (getSizeT() > 1 && getImageCount() == getSizeC() * getSizeZ()) {
          core[i].sizeY = getSizeT();
          core[i].sizeT = 1;
        }
      }
      if (isRGB()) core[i].indexed = false;

      core[i].dimensionOrder =
        MetadataTools.makeSaneDimensionOrder(getDimensionOrder());
      core[i].littleEndian = realLittleEndian;
    }

    MetadataStore store = makeFilterMetadata();
    MetadataTools.populatePixels(store, this, true);

    // Ensure we populate Image names before returning due to a possible
    // minimum metadata level.
    for (int i=0; i<numSeries;i ++) {
      store.setImageName(seriesNames.get(i), i);
    }
    if (metadataLevel == MetadataLevel.MINIMUM) return;

    for (int i=0; i<numSeries; i++) {
      IFD ifd = headerIFDs.get(i);
      long firstPlane = 0;

      if (i < timestamps.length && timestamps[i] != null &&
        timestamps[i].length > 0)
      {
        firstPlane = DateTools.getTime(timestamps[i][0], DATE_FORMAT);
        store.setImageAcquiredDate(
          DateTools.formatDate(timestamps[i][0], DATE_FORMAT), i);
      }

      store.setImageDescription(seriesDescriptions.get(i), i);

      String instrumentID = MetadataTools.createLSID("Instrument", i);
      store.setInstrumentID(instrumentID, i);

      // parse instrument data

      nextDetector = 0;
      nextChannel = 0;

      cutInPopulated[i] = new boolean[core[i].sizeC];
      cutOutPopulated[i] = new boolean[core[i].sizeC];
      filterRefPopulated[i] = new boolean[core[i].sizeC];

      Integer[] keys = ifd.keySet().toArray(new Integer[ifd.size()]);
      Arrays.sort(keys);
      int nextInstrumentBlock = 1;
      sequential = DataTools.indexOf(keys, SEQ_SCANNERSET) != -1;
      for (Integer key : keys) {
        if (key.equals(FILTERSET) || key.equals(SCANNERSET) ||
          key.equals(SEQ_SCANNERSET) || key.equals(SEQ_FILTERSET) ||
          (key > SEQ_SCANNERSET && key < SEQ_SCANNERSET_END) ||
          (key > SEQ_FILTERSET && key < SEQ_FILTERSET_END))
        {
          if (sequential && (key.equals(FILTERSET) || key.equals(SCANNERSET))) {
            continue;
          }
          long offset = ((Long) ifd.get(key)).longValue();
          in.seek(offset);
          setSeries(i);
          parseInstrumentData(store, nextInstrumentBlock++);
        }
      }
      activeChannelIndices.clear();

      // link Instrument and Image
      store.setImageInstrumentRef(instrumentID, i);

      if (physicalSizes[i][0] > 0) {
        store.setPixelsPhysicalSizeX(new PositiveFloat(physicalSizes[i][0]), i);
      }
      else {
        LOGGER.warn("Expected positive value for PhysicalSizeX; got {}",
          physicalSizes[i][0]);
      }
      if (physicalSizes[i][1] > 0) {
        store.setPixelsPhysicalSizeY(new PositiveFloat(physicalSizes[i][1]), i);
      }
      else {
        LOGGER.warn("Expected positive value for PhysicalSizeY; got {}",
          physicalSizes[i][1]);
      }
      if (physicalSizes[i][2] > 0) {
        store.setPixelsPhysicalSizeZ(new PositiveFloat(physicalSizes[i][2]), i);
      }
      else {
        LOGGER.warn("Expected positive value for PhysicalSizeZ; got {}",
          physicalSizes[i][2]);
      }
      if ((int) physicalSizes[i][4] > 0) {
        store.setPixelsTimeIncrement(physicalSizes[i][4], i);
      }

      for (int j=0; j<core[i].imageCount; j++) {
        if (timestamps[i] != null && j < timestamps[i].length) {
          long time = DateTools.getTime(timestamps[i][j], DATE_FORMAT);
          double elapsedTime = (double) (time - firstPlane) / 1000;
          store.setPlaneDeltaT(elapsedTime, i, j);
          store.setPlaneExposureTime(exposureTime[i], i, j);
        }
      }
    }
    setSeries(0);
  }

  // -- Helper methods --

  /** Find the .lei file that belongs to the same dataset as the given file. */
  private String findLEIFile(String baseFile)
    throws FormatException, IOException
  {
    if (checkSuffix(baseFile, LEI_SUFFIX)) {
      return baseFile;
    }
    else if (checkSuffix(baseFile, TiffReader.TIFF_SUFFIXES) && isGroupFiles())
    {
      // need to find the associated .lei file
      if (ifds == null) super.initFile(baseFile);

      in = new RandomAccessInputStream(baseFile);
      TiffParser tp = new TiffParser(in);
      in.order(tp.checkHeader().booleanValue());

      in.seek(0);

      LOGGER.info("Finding companion file name");

      // open the TIFF file and look for the "Image Description" field

      ifds = tp.getIFDs();
      if (ifds == null) throw new FormatException("No IFDs found");
      String descr = ifds.get(0).getComment();

      // remove anything of the form "[blah]"

      descr = descr.replaceAll("\\[.*.\\]\n", "");

      // each remaining line in descr is a (key, value) pair,
      // where '=' separates the key from the value

      String lei =
        baseFile.substring(0, baseFile.lastIndexOf(File.separator) + 1);

      StringTokenizer lines = new StringTokenizer(descr, "\n");
      String line = null, key = null, value = null;
      while (lines.hasMoreTokens()) {
        line = lines.nextToken();
        if (line.indexOf("=") == -1) continue;
        key = line.substring(0, line.indexOf("=")).trim();
        value = line.substring(line.indexOf("=") + 1).trim();
        addGlobalMeta(key, value);

        if (key.startsWith("Series Name")) lei += value;
      }

      // now open the LEI file

      Location l = new Location(lei).getAbsoluteFile();
      if (l.exists()) {
        return lei;
      }
      else {
        if (!lei.endsWith("lei") && !lei.endsWith("LEI")) {
          lei = lei.substring(0, lei.lastIndexOf(".") + 1);
          String test = lei + "lei";
          if (new Location(test).exists()) {
            return test;
          }
          test = lei + "LEI";
          if (new Location(test).exists()) {
            return test;
          }
        }

        l = l.getParentFile();
        String[] list = l.list();
        for (int i=0; i<list.length; i++) {
          if (checkSuffix(list[i], LEI_SUFFIX)) {
            return new Location(l.getAbsolutePath(), list[i]).getAbsolutePath();
          }
        }
      }
    }
    else if (checkSuffix(baseFile, "raw") && isGroupFiles()) {
      // check for that there is an .lei file in the same directory
      String prefix = baseFile;
      if (prefix.indexOf(".") != -1) {
        prefix = prefix.substring(0, prefix.lastIndexOf("."));
      }
      Location lei = new Location(prefix + ".lei");
      if (!lei.exists()) {
        lei = new Location(prefix + ".LEI");
        while (!lei.exists() && prefix.indexOf("_") != -1) {
          prefix = prefix.substring(0, prefix.lastIndexOf("_"));
          lei = new Location(prefix + ".lei");
          if (!lei.exists()) lei = new Location(prefix + ".LEI");
        }
      }
      if (lei.exists()) return lei.getAbsolutePath();
    }
    return null;
  }

  private void parseFilenames(int seriesIndex) throws IOException {
    int maxPlanes = 0;
    Vector<String> f = new Vector<String>();
    int tempImages = in.readInt();

    if (((long) tempImages * nameLength) > in.length()) {
      in.order(!isLittleEndian());
      tempImages = in.readInt();
      in.order(isLittleEndian());
    }

    core[seriesIndex].sizeX = in.readInt();
    core[seriesIndex].sizeY = in.readInt();
    in.skipBytes(4);
    int samplesPerPixel = in.readInt();
    core[seriesIndex].rgb = samplesPerPixel > 1;
    core[seriesIndex].sizeC = samplesPerPixel;

    File dirFile = new File(currentId).getAbsoluteFile();
    String[] listing = null;
    String dirPrefix = "";
    if (dirFile.exists()) {
      listing = dirFile.getParentFile().list();
      dirPrefix = dirFile.getParent();
      if (!dirPrefix.endsWith(File.separator)) dirPrefix += File.separator;
    }
    else {
      listing = Location.getIdMap().keySet().toArray(new String[0]);
    }

    Vector<String> list = new Vector<String>();

    for (int k=0; k<listing.length; k++) {
      if (checkSuffix(listing[k], TiffReader.TIFF_SUFFIXES)) {
        list.add(listing[k]);
      }
    }

    boolean tiffsExist = false;

    String prefix = "";
    for (int j=0; j<tempImages; j++) {
      // read in each filename
      prefix = getString(nameLength);
      f.add(dirPrefix + prefix);
      // test to make sure the path is valid
      Location test = new Location(f.get(f.size() - 1)).getAbsoluteFile();
      LOGGER.debug("Expected to find TIFF file {}", test.getAbsolutePath());
      if (!test.exists()) {
        LOGGER.debug("  file does not exist");
      }
      if (test.exists()) list.remove(prefix);
      if (!tiffsExist) tiffsExist = test.exists();
    }

    // all of the TIFF files were renamed

    if (!tiffsExist) {
      // Strategy for handling renamed files:
      // 1) Assume that files for each series follow a pattern.
      // 2) Assign each file group to the first series with the correct count.
      LOGGER.info("Handling renamed TIFF files");

      listing = list.toArray(new String[list.size()]);

      // grab the file patterns
      Vector<String> filePatterns = new Vector<String>();
      for (String q : listing) {
        Location l = new Location(dirPrefix, q).getAbsoluteFile();
        FilePattern pattern = new FilePattern(l);
        if (!pattern.isValid()) continue;

        AxisGuesser guess = new AxisGuesser(pattern, "XYZCT", 1, 1, 1, false);
        String fp = pattern.getPattern();

        if (guess.getAxisCountS() >= 1) {
          String pre = pattern.getPrefix(guess.getAxisCountS());
          Vector<String> fileList = new Vector<String>();
          for (int n=0; n<listing.length; n++) {
            Location p = new Location(dirPrefix, listing[n]);
            if (p.getAbsolutePath().startsWith(pre)) {
              fileList.add(listing[n]);
            }
          }
          fp = FilePattern.findPattern(l.getAbsolutePath(), dirPrefix,
            fileList.toArray(new String[fileList.size()]));
        }

        if (fp != null && !filePatterns.contains(fp)) {
          filePatterns.add(fp);
        }
      }

      for (String q : filePatterns) {
        String[] pattern = new FilePattern(q).getFiles();
        if (pattern.length == tempImages) {
          // make sure that this pattern hasn't already been used

          boolean validPattern = true;
          for (int n=0; n<seriesIndex; n++) {
            if (files[n] == null) continue;
            if (files[n].contains(pattern[0])) {
              validPattern = false;
              break;
            }
          }

          if (validPattern) {
            files[seriesIndex] = new Vector<String>();
            files[seriesIndex].addAll(Arrays.asList(pattern));
          }
        }
      }
    }
    else files[seriesIndex] = f;
    if (files[seriesIndex] == null) valid[seriesIndex] = false;
    else {
      core[seriesIndex].imageCount = files[seriesIndex].size();
      maxPlanes = (int) Math.max(maxPlanes, core[seriesIndex].imageCount);
    }
  }

  private void parseSeriesTag() throws IOException {
    addSeriesMeta("Version", in.readInt());
    addSeriesMeta("Number of Series", in.readInt());
    fileLength = in.readInt();
    addSeriesMeta("Length of filename", fileLength);
    int extLen = in.readInt();
    if (extLen > fileLength) {
      in.seek(8);
      core[0].littleEndian = !isLittleEndian();
      in.order(isLittleEndian());
      fileLength = in.readInt();
      extLen = in.readInt();
    }
    addSeriesMeta("Length of file extension", extLen);
    addSeriesMeta("Image file extension", getString(extLen));
  }

  private void parseImageTag(int seriesIndex) throws IOException {
    core[seriesIndex].imageCount = in.readInt();
    core[seriesIndex].sizeX = in.readInt();
    core[seriesIndex].sizeY = in.readInt();

    addSeriesMeta("Number of images", getImageCount());
    addSeriesMeta("Image width", getSizeX());
    addSeriesMeta("Image height", getSizeY());
    addSeriesMeta("Bits per Sample", in.readInt());
    addSeriesMeta("Samples per pixel", in.readInt());

    String name = getString(fileLength * 2);

    if (name.indexOf(".") != -1) {
      name = name.substring(0, name.lastIndexOf("."));
    }

    String[] tokens = name.split("_");
    StringBuffer buf = new StringBuffer();
    for (int p=1; p<tokens.length; p++) {
      String lcase = tokens[p].toLowerCase();
      if (!lcase.startsWith("ch0") && !lcase.startsWith("c0") &&
        !lcase.startsWith("z0") && !lcase.startsWith("t0"))
      {
        if (buf.length() > 0) buf.append("_");
        buf.append(tokens[p]);
      }
    }
    seriesNames.add(buf.toString());
  }

  private void parseDimensionTag(int seriesIndex)
    throws FormatException, IOException
  {
    addSeriesMeta("Voxel Version", in.readInt());
    core[seriesIndex].rgb = in.readInt() == 20;
    addSeriesMeta("VoxelType", isRGB() ? "RGB" : "gray");

    int bpp = in.readInt();
    addSeriesMeta("Bytes per pixel", bpp);

    if (bpp % 3 == 0) {
      core[seriesIndex].sizeC = 3;
      core[seriesIndex].rgb = true;
      bpp /= 3;
    }
    core[seriesIndex].pixelType =
      FormatTools.pixelTypeFromBytes(bpp, false, false);

    core[seriesIndex].dimensionOrder = "XY";

    int resolution = in.readInt();
    core[seriesIndex].bitsPerPixel = resolution;
    addSeriesMeta("Real world resolution", resolution);
    addSeriesMeta("Maximum voxel intensity", getString(true));
    addSeriesMeta("Minimum voxel intensity", getString(true));
    int len = in.readInt();
    in.skipBytes(len * 2 + 4);

    len = in.readInt();
    for (int j=0; j<len; j++) {
      int dimId = in.readInt();
      String dimType = DIMENSION_NAMES.get(new Integer(dimId));
      if (dimType == null) dimType = "";

      int size = in.readInt();
      int distance = in.readInt();
      int strlen = in.readInt() * 2;
      String[] sizeData = getString(strlen).split(" ");
      String physicalSize = sizeData[0];
      String unit = "";
      if (sizeData.length > 1) unit = sizeData[1];

      double physical = Double.parseDouble(physicalSize) / size;
      if (unit.equals("m")) {
        physical *= 1000000;
      }

      if (dimType.equals("x")) {
        core[seriesIndex].sizeX = size;
        physicalSizes[seriesIndex][0] = physical;
      }
      else if (dimType.equals("y")) {
        core[seriesIndex].sizeY = size;
        physicalSizes[seriesIndex][1] = physical;
      }
      else if (dimType.equals("channel")) {
        if (getSizeC() == 0) core[seriesIndex].sizeC = 1;
        core[seriesIndex].sizeC *= size;
        if (getDimensionOrder().indexOf("C") == -1) {
          core[seriesIndex].dimensionOrder += "C";
        }
        physicalSizes[seriesIndex][3] = physical;
      }
      else if (dimType.equals("z")) {
        core[seriesIndex].sizeZ = size;
        if (getDimensionOrder().indexOf("Z") == -1) {
          core[seriesIndex].dimensionOrder += "Z";
        }
        physicalSizes[seriesIndex][2] = physical;
      }
      else {
        core[seriesIndex].sizeT = size;
        if (getDimensionOrder().indexOf("T") == -1) {
          core[seriesIndex].dimensionOrder += "T";
        }
        physicalSizes[seriesIndex][4] = physical;
      }

      String dimPrefix = "Dim" + j;

      addSeriesMeta(dimPrefix + " type", dimType);
      addSeriesMeta(dimPrefix + " size", size);
      addSeriesMeta(dimPrefix + " distance between sub-dimensions",
        distance);

      addSeriesMeta(dimPrefix + " physical length",
        physicalSize + " " + unit);

      addSeriesMeta(dimPrefix + " physical origin", getString(true));
    }
    addSeriesMeta("Series name", getString(false));

    String description = getString(false);
    seriesDescriptions.add(description);
    addSeriesMeta("Series description", description);
  }

  private void parseTimeTag(int seriesIndex) throws IOException {
    int nDims = in.readInt();
    addSeriesMeta("Number of time-stamped dimensions", nDims);
    addSeriesMeta("Time-stamped dimension", in.readInt());

    for (int j=0; j<nDims; j++) {
      String dimPrefix = "Dimension " + j;
      addSeriesMeta(dimPrefix + " ID", in.readInt());
      addSeriesMeta(dimPrefix + " size", in.readInt());
      addSeriesMeta(dimPrefix + " distance", in.readInt());
    }

    int numStamps = in.readInt();
    addSeriesMeta("Number of time-stamps", numStamps);
    timestamps[seriesIndex] = new String[numStamps];
    for (int j=0; j<numStamps; j++) {
      timestamps[seriesIndex][j] = getString(64);
      addSeriesMeta("Timestamp " + j, timestamps[seriesIndex][j]);
    }

    if (in.getFilePointer() < in.length()) {
      int numTMs = in.readInt();
      addSeriesMeta("Number of time-markers", numTMs);
      for (int j=0; j<numTMs; j++) {
        if (in.getFilePointer() + 4 >= in.length()) break;
        int numDims = in.readInt();

        String time = "Time-marker " + j + " Dimension ";

        for (int k=0; k<numDims; k++) {
          if (in.getFilePointer() + 4 < in.length()) {
            addSeriesMeta(time + k + " coordinate", in.readInt());
          }
          else break;
        }
        if (in.getFilePointer() >= in.length()) break;
        addSeriesMeta("Time-marker " + j, getString(64));
      }
    }
  }

  private void parseExperimentTag() throws IOException {
    in.skipBytes(8);
    String description = getString(true);
    addSeriesMeta("Image Description", description);
    addSeriesMeta("Main file extension", getString(true));
    addSeriesMeta("Image format identifier", getString(true));
    addSeriesMeta("Single image extension", getString(true));
  }

  private void parseLUT(int seriesIndex) throws IOException {
    int nChannels = in.readInt();
    if (nChannels > 0) core[seriesIndex].indexed = true;
    addSeriesMeta("Number of LUT channels", nChannels);
    addSeriesMeta("ID of colored dimension", in.readInt());

    channelColor[seriesIndex] = new int[nChannels];

    for (int j=0; j<nChannels; j++) {
      String p = "LUT Channel " + j;
      addSeriesMeta(p + " version", in.readInt());
      addSeriesMeta(p + " inverted?", in.read() == 1);
      addSeriesMeta(p + " description", getString(false));
      addSeriesMeta(p + " filename", getString(false));
      String lut = getString(false);
      addSeriesMeta(p + " name", lut);

      channelColor[seriesIndex][j] = 0xffffffff;

      if (lut.equalsIgnoreCase("red")) {
        channelColor[seriesIndex][j] = 0xff0000ff;
      }
      else if (lut.equalsIgnoreCase("green")) {
        channelColor[seriesIndex][j] = 0xff00ff;
      }
      else if (lut.equalsIgnoreCase("blue")) {
        channelColor[seriesIndex][j] = 0xffff;
      }
      else if (lut.equalsIgnoreCase("yellow")) {
        channelColor[seriesIndex][j] = 0xffff00ff;
      }
      else if (lut.equalsIgnoreCase("cyan")) {
        channelColor[seriesIndex][j] = 0xffffff;
      }
      else if (lut.equalsIgnoreCase("magenta")) {
        channelColor[seriesIndex][j] = 0xff00ffff;
      }

      in.skipBytes(8);
    }
  }

  private void parseChannelTag() throws IOException {
    int nBands = in.readInt();
    for (int band=0; band<nBands; band++) {
      String p = "Band #" + (band + 1) + " ";
      addSeriesMeta(p + "Lower wavelength", in.readDouble());
      in.skipBytes(4);
      addSeriesMeta(p + "Higher wavelength", in.readDouble());
      in.skipBytes(4);
      addSeriesMeta(p + "Gain", in.readDouble());
      addSeriesMeta(p + "Offset", in.readDouble());
    }
  }

  private void parseInstrumentData(MetadataStore store, int blockNum)
    throws FormatException, IOException
  {
    int series = getSeries();

    // read 24 byte SAFEARRAY
    in.skipBytes(4);
    int cbElements = in.readInt();
    in.skipBytes(8);
    int nElements = in.readInt();
    in.skipBytes(4);

    long initialOffset = in.getFilePointer();
    long elementOffset = 0;

    LOGGER.trace("Element LOOP; series {} at offset", series, initialOffset);
    for (int j=0; j<nElements; j++) {
      elementOffset = initialOffset + j * cbElements;
      LOGGER.trace("Seeking to: {}", elementOffset);
      in.seek(elementOffset);
      String contentID = getString(128);
      LOGGER.trace("contentID: {}", contentID);
      String description = getString(64);
      LOGGER.trace("description: {}", description);
      String data = getString(64);
      int dataType = in.readShort();
      LOGGER.trace("dataType: {}", dataType);
      in.skipBytes(6);

      // read data
      switch (dataType) {
        case 2:
          data = String.valueOf(in.readShort());
          break;
        case 3:
          data = String.valueOf(in.readInt());
          break;
        case 4:
          data = String.valueOf(in.readFloat());
          break;
        case 5:
          data = String.valueOf(in.readDouble());
          break;
        case 7:
        case 11:
          data = in.read() == 0 ? "false" : "true";
          break;
        case 17:
          data = in.readString(1);
          break;
      }

      LOGGER.trace("data: {}", data);
      if (data.trim().length() == 0) {
        LOGGER.trace("Zero length dat string, continuing...");
        continue;
      }

      String[] tokens = contentID.split("\\|");

      LOGGER.trace("Parsing tokens: {}", tokens);
      if (tokens[0].startsWith("CDetectionUnit")) {
        // detector information

        if (tokens[1].startsWith("PMT")) {
          try {
            if (tokens[2].equals("VideoOffset")) {
              detectorOffset = new Double(data);
            }
            else if (tokens[2].equals("HighVoltage")) {
              detectorVoltage = new Double(data);
              nextDetector++;
            }
            else if (tokens[2].equals("State")) {
              // link Detector to Image, if the detector was actually used
              if (data.equals("Active")) {
                store.setDetectorOffset(detectorOffset, series, nextDetector);
                store.setDetectorVoltage(detectorVoltage, series, nextDetector);
                store.setDetectorType(
                  getDetectorType("PMT"), series, nextDetector);
                String index = tokens[1].substring(tokens[1].indexOf(" ") + 1);
                int channelIndex = -1;
                try {
                  channelIndex = Integer.parseInt(index) - 1;
                }
                catch (NumberFormatException e) { }
                if (channelIndex >= 0) {
                  activeChannelIndices.add(new Integer(channelIndex));
                }

                String detectorID =
                  MetadataTools.createLSID("Detector", series, nextDetector);
                store.setDetectorID(detectorID, series, nextDetector);

                if (nextDetector == 0) {
                  // link every channel to the first detector in the beginning
                  // if additional detectors are found, the links will be
                  // overwritten
                  for (int c=0; c<getEffectiveSizeC(); c++) {
                    store.setDetectorSettingsID(detectorID, series, c);
                  }
                }

                if (nextChannel < getEffectiveSizeC()) {
                  store.setDetectorSettingsID(
                    detectorID, series, nextChannel++);
                }
              }
            }
          }
          catch (NumberFormatException e) {
            LOGGER.debug("Failed to parse detector metadata", e);
          }
        }
      }
      else if (tokens[0].startsWith("CTurret")) {
        // objective information

        int objective = Integer.parseInt(tokens[3]);
        if (tokens[2].equals("NumericalAperture")) {
          store.setObjectiveLensNA(new Double(data), series, objective);
        }
        else if (tokens[2].equals("Objective")) {
          String[] objectiveData = data.split(" ");
          StringBuffer model = new StringBuffer();
          String mag = null, na = null;
          String immersion = null, correction = null;
          for (int i=0; i<objectiveData.length; i++) {
            if (objectiveData[i].indexOf("x") != -1 && mag == null &&
              na == null)
            {
              int xIndex = objectiveData[i].indexOf("x");
              mag = objectiveData[i].substring(0, xIndex).trim();
              na = objectiveData[i].substring(xIndex + 1).trim();
            }
            else if (mag == null && na == null) {
              model.append(objectiveData[i]);
              model.append(" ");
            }
            else if (correction == null) {
              correction = objectiveData[i];
            }
            else if (immersion == null) {
              immersion = objectiveData[i];
            }
          }

          if (immersion != null) immersion = immersion.trim();
          if (correction != null) correction = correction.trim();

          Correction realCorrection = getCorrection(correction);
          Correction testCorrection = getCorrection(immersion);
          Immersion realImmersion = getImmersion(immersion);
          Immersion testImmersion = getImmersion(correction);

          // Correction and Immersion are reversed
          if ((testCorrection != Correction.OTHER &&
            realCorrection == Correction.OTHER) ||
            (testImmersion != Immersion.OTHER &&
            realImmersion == Immersion.OTHER))
          {
            String tmp = correction;
            correction = immersion;
            immersion = tmp;
          }

          store.setObjectiveImmersion(
            getImmersion(immersion), series, objective);
          store.setObjectiveCorrection(
            getCorrection(correction), series, objective);
          store.setObjectiveModel(model.toString().trim(), series, objective);
          store.setObjectiveLensNA(new Double(na), series, objective);

          int magnification = (int) Double.parseDouble(mag);
          if (magnification > 0) {
            store.setObjectiveNominalMagnification(
              new PositiveInteger(magnification), series, objective);
          }
          else {
            LOGGER.warn(
              "Expected positive value for NominalMagnification; got {}",
              magnification);
          }
        }
        else if (tokens[2].equals("OrderNumber")) {
          store.setObjectiveSerialNumber(data, series, objective);
        }
        else if (tokens[2].equals("RefractionIndex")) {
          store.setImageObjectiveSettingsRefractiveIndex(
            new Double(data), series);
        }

        // link Objective to Image
        String objectiveID =
          MetadataTools.createLSID("Objective", series, objective);
        store.setObjectiveID(objectiveID, series, objective);
        if (objective == 0) {
          store.setImageObjectiveSettingsID(objectiveID, series);
        }
      }
      else if (tokens[0].startsWith("CSpectrophotometerUnit")) {
        int ndx = tokens[1].lastIndexOf(" ");
        int channel = Integer.parseInt(tokens[1].substring(ndx + 1)) - 1;

        if (tokens[2].equals("Wavelength")) {
          Integer wavelength = new Integer((int) Double.parseDouble(data));
          store.setFilterModel(tokens[1], series, channel);

          String filterID = MetadataTools.createLSID("Filter", series, channel);
          store.setFilterID(filterID, series, channel);

          int index = activeChannelIndices.indexOf(new Integer(channel));
          if (index >= 0 && index < core[series].sizeC) {
            if (!filterRefPopulated[series][index]) {
              store.setLightPathEmissionFilterRef(filterID, series, index, 0);
              filterRefPopulated[series][index] = true;
            }

            if (wavelength > 0) {
              if (tokens[3].equals("0") && !cutInPopulated[series][index]) {
                store.setTransmittanceRangeCutIn(
                  new PositiveInteger(wavelength), series, channel);
                cutInPopulated[series][index] = true;
              }
              else if (tokens[3].equals("1") && !cutOutPopulated[series][index])
              {
                store.setTransmittanceRangeCutOut(
                  new PositiveInteger(wavelength), series, channel);
                cutOutPopulated[series][index] = true;
              }
            }
            else {
              LOGGER.warn("Expected positive value for CutIn/CutOut; got {}",
                wavelength);
            }
          }
        }
        else if (tokens[2].equals("Stain")) {
          if (activeChannelIndices.contains(new Integer(channel))) {
            int nNames = channelNames[series].size();
            String prevValue = nNames == 0 ? "" :
              (String) channelNames[series].get(nNames - 1);
            if (!prevValue.equals(data)) {
              channelNames[series].add(data);
            }
          }
        }
      }
      else if (tokens[0].startsWith("CXYZStage")) {
        // NB: there is only one stage position specified for each series
        if (tokens[2].equals("XPos")) {
          for (int q=0; q<core[series].imageCount; q++) {
            store.setPlanePositionX(new Double(data), series, q);
            if (q == 0) {
              addGlobalMeta("X position for position #" + (series + 1), data);
            }
          }
        }
        else if (tokens[2].equals("YPos")) {
          for (int q=0; q<core[series].imageCount; q++) {
            store.setPlanePositionY(new Double(data), series, q);
            if (q == 0) {
              addGlobalMeta("Y position for position #" + (series + 1), data);
            }
          }
        }
        else if (tokens[2].equals("ZPos")) {
          for (int q=0; q<core[series].imageCount; q++) {
            store.setPlanePositionZ(new Double(data), series, q);
            if (q == 0) {
              addGlobalMeta("Z position for position #" + (series + 1), data);
            }
          }
        }
      }
      else if (tokens[0].equals("CScanActuator") &&
        tokens[1].equals("Z Scan Actuator") && tokens[2].equals("Position"))
      {
        double pos = Double.parseDouble(data) * 1000000;
        for (int q=0; q<core[series].imageCount; q++) {
          store.setPlanePositionZ(pos, series, q);
        }
      }

      if (contentID.equals("dblVoxelX")) {
        physicalSizes[series][0] = Double.parseDouble(data);
      }
      else if (contentID.equals("dblVoxelY")) {
        physicalSizes[series][1] = Double.parseDouble(data);
      }
      else if (contentID.equals("dblStepSize")) {
        double size = Double.parseDouble(data);
        if (size > 0) {
          physicalSizes[series][2] = size;
        }
      }
      else if (contentID.equals("dblPinhole")) {
        // pinhole is stored in meters
        pinhole[series] = Double.parseDouble(data) * 1000000;
      }
      else if (contentID.startsWith("nDelayTime")) {
        exposureTime[series] = Double.parseDouble(data);
        if (contentID.endsWith("_ms")) {
          exposureTime[series] /= 1000;
        }
      }

      addSeriesMeta("Block " + blockNum + " " + contentID, data);
    }

    // populate saved LogicalChannel data

    for (int i=0; i<getSeriesCount(); i++) {
      setSeries(i);
      for (int channel=0; channel<getEffectiveSizeC(); channel++) {
        if (channel < channelNames[i].size()) {
          String name = (String) channelNames[i].get(channel);
          if (name != null && !name.trim().equals("") && !name.equals("None")) {
            store.setChannelName(name, i, channel);
          }
        }
        if (channel < emWaves[i].size()) {
          Integer wave = new Integer(emWaves[i].get(channel).toString());
          if (wave > 0) {
            store.setChannelEmissionWavelength(
              new PositiveInteger(wave), i, channel);
          }
          else {
            LOGGER.warn(
              "Expected positive value for EmissionWavelength; got {}", wave);
          }
        }
        if (channel < exWaves[i].size()) {
          Integer wave = new Integer(exWaves[i].get(channel).toString());
          if (wave > 0) {
            store.setChannelExcitationWavelength(
              new PositiveInteger(wave), i, channel);
          }
          else {
            LOGGER.warn(
              "Expected positive value for ExcitationWavelength; got {}", wave);
          }
        }
        if (i < pinhole.length) {
          store.setChannelPinholeSize(new Double(pinhole[i]), i, channel);
        }
        if (channel < channelColor[i].length) {
          store.setChannelColor(channelColor[i][channel], i, channel);
        }
      }
    }

    setSeries(0);
  }

  private boolean usedFile(String s) {
    if (files == null) return false;

    for (int i=0; i<files.length; i++) {
      if (files[i] == null) continue;
      for (int j=0; j<files[i].size(); j++) {
        if (((String) files[i].get(j)).endsWith(s)) return true;
      }
    }
    return false;
  }

  private String getString(int len) throws IOException {
    return DataTools.stripString(in.readString(len));
  }

  private String getString(boolean doubleLength) throws IOException {
    int len = in.readInt();
    if (doubleLength) len *= 2;
    return getString(len);
  }

  private static Hashtable<Integer, String> makeDimensionTable() {
    Hashtable<Integer, String> table = new Hashtable<Integer, String>();
    table.put(new Integer(0), "undefined");
    table.put(new Integer(120), "x");
    table.put(new Integer(121), "y");
    table.put(new Integer(122), "z");
    table.put(new Integer(116), "t");
    table.put(new Integer(6815843), "channel");
    table.put(new Integer(6357100), "wave length");
    table.put(new Integer(7602290), "rotation");
    table.put(new Integer(7798904), "x-wide for the motorized xy-stage");
    table.put(new Integer(7798905), "y-wide for the motorized xy-stage");
    table.put(new Integer(7798906), "z-wide for the z-stage-drive");
    table.put(new Integer(4259957), "user1 - unspecified");
    table.put(new Integer(4325493), "user2 - unspecified");
    table.put(new Integer(4391029), "user3 - unspecified");
    table.put(new Integer(6357095), "graylevel");
    table.put(new Integer(6422631), "graylevel1");
    table.put(new Integer(6488167), "graylevel2");
    table.put(new Integer(6553703), "graylevel3");
    table.put(new Integer(7864398), "logical x");
    table.put(new Integer(7929934), "logical y");
    table.put(new Integer(7995470), "logical z");
    table.put(new Integer(7602254), "logical t");
    table.put(new Integer(7077966), "logical lambda");
    table.put(new Integer(7471182), "logical rotation");
    table.put(new Integer(5767246), "logical x-wide");
    table.put(new Integer(5832782), "logical y-wide");
    table.put(new Integer(5898318), "logical z-wide");
    return table;
  }

}
