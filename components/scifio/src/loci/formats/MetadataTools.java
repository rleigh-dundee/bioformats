//
// MetadataTools.java
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

import java.util.Arrays;
import java.util.Hashtable;
import java.util.Map;

import loci.common.DateTools;
import loci.common.Location;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;
import loci.formats.ome.OMEXMLMetadataImpl;
import loci.formats.services.OMEXMLService;

import ome.xml.model.BinData;
import ome.xml.model.OME;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.EnumerationException;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.NonNegativeLong;
import ome.xml.model.primitives.PositiveInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A utility class for working with metadata objects,
 * including {@link MetadataStore}, {@link MetadataRetrieve},
 * and OME-XML strings.
 * Most of the methods require the optional {@link loci.formats.ome}
 * package, and optional ome-xml.jar library, to be present at runtime.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/MetadataTools.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/MetadataTools.java;hb=HEAD">Gitweb</a></dd></dl>
 */
public final class MetadataTools {

  // -- Constants --

  private static final Logger LOGGER =
    LoggerFactory.getLogger(MetadataTools.class);

  // -- Static fields --

  // -- Constructor --

  private MetadataTools() { }

  // -- Utility methods - OME-XML --

  /**
   * Populates the 'pixels' element of the given metadata store, using core
   * metadata from the given reader.
   */
  public static void populatePixels(MetadataStore store, IFormatReader r) {
    populatePixels(store, r, false, true);
  }

  /**
   * Populates the 'pixels' element of the given metadata store, using core
   * metadata from the given reader.  If the 'doPlane' flag is set,
   * then the 'plane' elements will be populated as well.
   */
  public static void populatePixels(MetadataStore store, IFormatReader r,
    boolean doPlane)
  {
    populatePixels(store, r, doPlane, true);
  }

  /**
   * Populates the 'pixels' element of the given metadata store, using core
   * metadata from the given reader.  If the 'doPlane' flag is set,
   * then the 'plane' elements will be populated as well.
   * If the 'doImageName' flag is set, then the image name will be populated
   * as well.  By default, 'doImageName' is true.
   */
  public static void populatePixels(MetadataStore store, IFormatReader r,
    boolean doPlane, boolean doImageName)
  {
    if (store == null || r == null) return;
    int oldSeries = r.getSeries();
    for (int i=0; i<r.getSeriesCount(); i++) {
      r.setSeries(i);
      
      String imageName = null;
      if (doImageName) {
        Location f = new Location(r.getCurrentFile());
        imageName = f.getName();
      }
      String pixelType = FormatTools.getPixelTypeString(r.getPixelType());

      populateMetadata(store, i, imageName, r.isLittleEndian(),
        r.getDimensionOrder(), pixelType, r.getSizeX(), r.getSizeY(),
        r.getSizeZ(), r.getSizeC(), r.getSizeT(), r.getRGBChannelCount());

      try {
        OMEXMLService service =
          new ServiceFactory().getInstance(OMEXMLService.class);
        if (service.isOMEXMLRoot(store.getRoot())) {
          MetadataStore baseStore = r.getMetadataStore();
          if (service.isOMEXMLMetadata(baseStore)) {
            ((OMEXMLMetadataImpl) baseStore).resolveReferences();
          }

          OME root = (OME) store.getRoot();
          BinData bin = root.getImage(i).getPixels().getBinData(0);
          bin.setLength(new NonNegativeLong(0L));
          store.setRoot(root);
        }
      }
      catch (DependencyException exc) {
        LOGGER.warn("Failed to set BinData.Length", exc);
      }

      if (doPlane) {
        for (int q=0; q<r.getImageCount(); q++) {
          int[] coords = r.getZCTCoords(q);
          store.setPlaneTheZ(new NonNegativeInteger(coords[0]), i, q);
          store.setPlaneTheC(new NonNegativeInteger(coords[1]), i, q);
          store.setPlaneTheT(new NonNegativeInteger(coords[2]), i, q);
        }
      }
    }
    r.setSeries(oldSeries);
  }

  /**
   * Populates the given {@link MetadataStore}, for the specified series, using
   * the values from the provided {@link CoreMetadata}.
   * <p>
   * After calling this method, the metadata store will be sufficiently
   * populated for use with an {@link IFormatWriter} (assuming it is also a
   * {@link MetadataRetrieve}).
   * </p>
   */
  public static void populateMetadata(MetadataStore store, int series,
    String imageName, CoreMetadata coreMeta)
  {
    final String pixelType = FormatTools.getPixelTypeString(coreMeta.pixelType);
    final int effSizeC = coreMeta.imageCount / coreMeta.sizeZ / coreMeta.sizeT;
    final int samplesPerPixel = coreMeta.sizeC / effSizeC;
    populateMetadata(store, series, imageName, coreMeta.littleEndian,
      coreMeta.dimensionOrder, pixelType, coreMeta.sizeX, coreMeta.sizeY,
      coreMeta.sizeZ, coreMeta.sizeC, coreMeta.sizeT, samplesPerPixel);
  }

  /**
   * Populates the given {@link MetadataStore}, for the specified series, using
   * the provided values.
   * <p>
   * After calling this method, the metadata store will be sufficiently
   * populated for use with an {@link IFormatWriter} (assuming it is also a
   * {@link MetadataRetrieve}).
   * </p>
   */
  public static void populateMetadata(MetadataStore store, int series,
    String imageName, boolean littleEndian, String dimensionOrder,
    String pixelType, int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
    int samplesPerPixel)
  {
    store.setImageID(createLSID("Image", series), series);
    setDefaultCreationDate(store, null, series);
    if (imageName != null) store.setImageName(imageName, series);
    store.setPixelsID(createLSID("Pixels", series), series);
    store.setPixelsBinDataBigEndian(!littleEndian, series, 0);
    try {
      store.setPixelsDimensionOrder(
        DimensionOrder.fromString(dimensionOrder), series);
    }
    catch (EnumerationException e) {
      LOGGER.warn("Invalid dimension order: " + dimensionOrder, e);
    }
    try {
      store.setPixelsType(PixelType.fromString(pixelType), series);
    }
    catch (EnumerationException e) {
      LOGGER.warn("Invalid pixel type: " + pixelType, e);
    }
    store.setPixelsSizeX(new PositiveInteger(sizeX), series);
    store.setPixelsSizeY(new PositiveInteger(sizeY), series);
    store.setPixelsSizeZ(new PositiveInteger(sizeZ), series);
    store.setPixelsSizeC(new PositiveInteger(sizeC), series);
    store.setPixelsSizeT(new PositiveInteger(sizeT), series);
    int effSizeC = sizeC / samplesPerPixel;
    for (int i=0; i<effSizeC; i++) {
      store.setChannelID(createLSID("Channel", series, i),
        series, i);
      store.setChannelSamplesPerPixel(new PositiveInteger(samplesPerPixel),
        series, i);
    }
  }

  /**
   * Constructs an LSID, given the object type and indices.
   * For example, if the arguments are "Detector", 1, and 0, the LSID will
   * be "Detector:1:0".
   */
  public static String createLSID(String type, int... indices) {
    StringBuffer lsid = new StringBuffer(type);
    for (int index : indices) {
      lsid.append(":");
      lsid.append(index);
    }
    return lsid.toString();
  }

  /**
   * Checks whether the given metadata object has the minimum metadata
   * populated to successfully describe an Image.
   *
   * @throws FormatException if there is a missing metadata field,
   *   or the metadata object is uninitialized
   */
  public static void verifyMinimumPopulated(MetadataRetrieve src)
    throws FormatException
  {
    verifyMinimumPopulated(src, 0);
  }

  /**
   * Checks whether the given metadata object has the minimum metadata
   * populated to successfully describe the nth Image.
   *
   * @throws FormatException if there is a missing metadata field,
   *   or the metadata object is uninitialized
   */
  public static void verifyMinimumPopulated(MetadataRetrieve src, int n)
    throws FormatException
  {
    if (src == null) {
      throw new FormatException("Metadata object is null; " +
          "call IFormatWriter.setMetadataRetrieve() first");
    }
    if (src instanceof MetadataStore
        && ((MetadataStore) src).getRoot() == null) {
      throw new FormatException("Metadata object has null root; " +
        "call IMetadata.createRoot() first");
    }
    if (src.getImageID(n) == null) {
      throw new FormatException("Image ID #" + n + " is null");
    }
    if (src.getPixelsID(n) == null) {
      throw new FormatException("Pixels ID #" + n + " is null");
    }
    for (int i=0; i<src.getChannelCount(n); i++) {
      if (src.getChannelID(n, i) == null) {
        throw new FormatException("Channel ID #" + i + " in Image #" + n +
          " is null");
      }
    }
    if (src.getPixelsBinDataBigEndian(n, 0) == null) {
      throw new FormatException("BigEndian #" + n + " is null");
    }
    if (src.getPixelsDimensionOrder(n) == null) {
      throw new FormatException("DimensionOrder #" + n + " is null");
    }
    if (src.getPixelsType(n) == null) {
      throw new FormatException("PixelType #" + n + " is null");
    }
    if (src.getPixelsSizeC(n) == null) {
      throw new FormatException("SizeC #" + n + " is null");
    }
    if (src.getPixelsSizeT(n) == null) {
      throw new FormatException("SizeT #" + n + " is null");
    }
    if (src.getPixelsSizeX(n) == null) {
      throw new FormatException("SizeX #" + n + " is null");
    }
    if (src.getPixelsSizeY(n) == null) {
      throw new FormatException("SizeY #" + n + " is null");
    }
    if (src.getPixelsSizeZ(n) == null) {
      throw new FormatException("SizeZ #" + n + " is null");
    }
  }

  /**
   * Sets a default creation date.  If the named file exists, then the creation
   * date is set to the file's last modification date.  Otherwise, it is set
   * to the current date.
   */
  public static void setDefaultCreationDate(MetadataStore store, String id,
    int series)
  {
    Location file = id == null ? null : new Location(id).getAbsoluteFile();
    long time = System.currentTimeMillis();
    if (file != null && file.exists()) time = file.lastModified();
    store.setImageAcquiredDate(DateTools.convertDate(time, DateTools.UNIX),
      series);
  }

  /**
   * Adjusts the given dimension order as needed so that it contains exactly
   * one of each of the following characters: 'X', 'Y', 'Z', 'C', 'T'.
   */
  public static String makeSaneDimensionOrder(String dimensionOrder) {
    String order = dimensionOrder.toUpperCase();
    order = order.replaceAll("[^XYZCT]", "");
    String[] axes = new String[] {"X", "Y", "C", "Z", "T"};
    for (String axis : axes) {
      if (order.indexOf(axis) == -1) order += axis;
      while (order.indexOf(axis) != order.lastIndexOf(axis)) {
        order = order.replaceFirst(axis, "");
      }
    }
    return order;
  }

  // -- Utility methods - original metadata --

  /** Gets a sorted list of keys from the given hashtable. */
  public static String[] keys(Hashtable<String, Object> meta) {
    String[] keys = new String[meta.size()];
    meta.keySet().toArray(keys);
    Arrays.sort(keys);
    return keys;
  }

  /**
   * Merges the given lists of metadata, prepending the
   * specified prefix for the destination keys.
   */
  public static void merge(Map<String, Object> src, Map<String, Object> dest,
    String prefix)
  {
    for (String key : src.keySet()) {
      dest.put(prefix + key, src.get(key));
    }
  }

  // -- Deprecated methods --

  private static OMEXMLService omexmlService = createOMEXMLService();
  private static OMEXMLService createOMEXMLService() {
    try {
      return new ServiceFactory().getInstance(OMEXMLService.class);
    }
    catch (DependencyException exc) {
      return null;
    }
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#asRetrieve(MetadataStore)}
   * instead.
   */
  public static MetadataRetrieve asRetrieve(MetadataStore meta) {
    if (omexmlService == null) return null;
    return omexmlService.asRetrieve(meta);
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#getLatestVersion()}
   * instead.
   */
  public static String getLatestVersion() {
    if (omexmlService == null) return null;
    return omexmlService.getLatestVersion();
  }

  /**
   * Creates an OME-XML metadata object using reflection, to avoid
   * direct dependencies on the optional {@link loci.formats.ome} package.
   * @return A new instance of {@link loci.formats.ome.AbstractOMEXMLMetadata},
   *   or null if one cannot be created.
   */
  public static IMetadata createOMEXMLMetadata() {
    try {
      final OMEXMLService service =
        new ServiceFactory().getInstance(OMEXMLService.class);
      if (service == null) return null;
      return service.createOMEXMLMetadata();
    }
    catch (DependencyException exc) {
      return null;
    }
    catch (ServiceException exc) {
      return null;
    }
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#createOMEXMLMetadata(String)}
   * instead.
   */
  public static IMetadata createOMEXMLMetadata(String xml) {
    if (omexmlService == null) return null;
    try {
      return omexmlService.createOMEXMLMetadata(xml);
    }
    catch (ServiceException exc) {
      return null;
    }
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#createOMEXMLMetadata(String,
   *   String)}
   * instead.
   */
  public static IMetadata createOMEXMLMetadata(String xml, String version) {
    if (omexmlService == null) return null;
    try {
      return omexmlService.createOMEXMLMetadata(xml);
    }
    catch (ServiceException exc) {
      return null;
    }
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#createOMEXMLRoot(String)}
   * instead.
   */
  public static Object createOMEXMLRoot(String xml) {
    if (omexmlService == null) return null;
    try {
      return omexmlService.createOMEXMLMetadata(xml);
    }
    catch (ServiceException exc) {
      return null;
    }
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#isOMEXMLMetadata(Object)}
   * instead.
   */
  public static boolean isOMEXMLMetadata(Object o) {
    if (omexmlService == null) return false;
    return omexmlService.isOMEXMLMetadata(o);
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#isOMEXMLRoot(Object)}
   * instead.
   */
  public static boolean isOMEXMLRoot(Object o) {
    if (omexmlService == null) return false;
    return omexmlService.isOMEXMLRoot(o);
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#getOMEXMLVersion(Object)}
   * instead.
   */
  public static String getOMEXMLVersion(Object o) {
    if (omexmlService == null) return null;
    return omexmlService.getOMEXMLVersion(o);
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#getOMEMetadata(MetadataRetrieve)}
   * instead.
   */
  public static IMetadata getOMEMetadata(MetadataRetrieve src) {
    if (omexmlService == null) return null;
    try {
      return omexmlService.getOMEMetadata(src);
    }
    catch (ServiceException exc) {
      return null;
    }
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#getOMEXML(MetadataRetrieve)}
   * instead.
   */
  public static String getOMEXML(MetadataRetrieve src) {
    if (omexmlService == null) return null;
    try {
      return omexmlService.getOMEXML(src);
    }
    catch (ServiceException exc) {
      return null;
    }
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#validateOMEXML(String)}
   * instead.
   */
  public static boolean validateOMEXML(String xml) {
    if (omexmlService == null) return false;
    return omexmlService.validateOMEXML(xml);
  }

  /**
   * @deprecated This method is not thread-safe; use
   * {@link loci.formats.services.OMEXMLService#validateOMEXML(String, boolean)}
   * instead.
   */
  public static boolean validateOMEXML(String xml, boolean pixelsHack) {
    if (omexmlService == null) return false;
    return omexmlService.validateOMEXML(xml, pixelsHack);
  }

}
